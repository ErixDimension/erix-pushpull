package com.erix.ptp.manager

import com.erix.ptp.data.FileEntry
import com.erix.ptp.ptp.AdbUsbTransport
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AdbFileManager — file listing & info (list, getStorageInfo, walkFolder).
 *
 * Parser ls -la dibuat robust untuk menangani custom ROM (MIUI/HyperOS)
 * yang menambahkan kolom SELinux context. Nama file dicari berdasarkan
 * posisi field waktu (HH:MM), bukan index tetap.
 */
class AdbFileManager(private val transport: AdbUsbTransport) {

    data class StorageInfo(
        val totalBytes: Long,
        val freeBytes: Long,
        val mountPath: String
    )

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun stripAnsi(s: String): String =
        s.replace(Regex("\u001B\\[[;\\d]*m"), "")

    /** Resolve /sdcard ke /storage/emulated/0 (hindari symlink di custom ROM). */
    private fun resolvePath(path: String): String {
        val p = path.trim().removeSuffix("/").ifEmpty { "/" }
        return when {
            p == "/sdcard" -> "/storage/emulated/0"
            p.startsWith("/sdcard/") -> "/storage/emulated/0" + p.substring("/sdcard".length)
            p.startsWith("/mnt/sdcard") -> "/storage/emulated/0" + p.substring("/mnt/sdcard".length)
            p.startsWith("/data/media/0") -> "/storage/emulated/0" + p.substring("/data/media/0".length)
            else -> p
        }
    }

    suspend fun list(path: String): List<FileEntry.Remote> = withContext(Dispatchers.IO) {
        val normalized = resolvePath(path)
        val out = try {
            transport.exec("ls -la --color=never ${shellQuote(normalized)}/ 2>/dev/null")
        } catch (e: Throwable) {
            DebugLogger.log("[R] exec error: ${e.message}")
            return@withContext emptyList()
        }

        if (out.isBlank()) {
            DebugLogger.log("[R] empty output untuk $normalized")
            return@withContext emptyList()
        }

        val lines = out.lines().map { stripAnsi(it) }.filter { it.isNotBlank() }
        val parsed = lines.mapNotNull { parseLsLine(it, normalized) }

        if (parsed.isEmpty()) {
            val sample = lines.take(2).joinToString(" | ")
            DebugLogger.log("[R] parsed 0 dari ${lines.size} baris untuk $normalized")
            DebugLogger.log("[R] sample: ${sample.take(200)}")
        }

        parsed
    }

    private fun parseLsLine(line: String, parentPath: String): FileEntry.Remote? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val first = t[0]
        if (first != 'd' && first != '-' && first != 'l') return null
        val parts = t.split(Regex("\\s+"))
        if (parts.size < 8) return null

        // Cari index field waktu (HH:MM) — penanda sebelum nama file
        var timeIdx = -1
        for (i in parts.indices.reversed()) {
            if (parts[i].matches(Regex("^\\d{2}:\\d{2}$"))) {
                timeIdx = i
                break
            }
        }
        if (timeIdx < 0 || timeIdx >= parts.size - 1) return null

        val name = parts.drop(timeIdx + 1).joinToString(" ")
        if (name == "." || name == "..") return null

        // Size = 2 field sebelum tanggal (date time name)
        val sizeStr = parts.getOrNull(timeIdx - 2) ?: "0"
        val size = sizeStr.toLongOrNull() ?: 0L

        val isDir = first == 'd'
        val fullPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
        return FileEntry.Remote(name, fullPath, parentPath, size, isDir)
    }

    suspend fun getStorageInfo(path: String): StorageInfo? = withContext(Dispatchers.IO) {
        val out = try {
            transport.exec("df -k ${shellQuote(resolvePath(path))} 2>/dev/null")
        } catch (_: Throwable) {
            return@withContext null
        }
        val lines = out.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return@withContext null
        val parts = lines[1].trim().split(Regex("\\s+"), limit = 6)
        if (parts.size < 4) return@withContext null
        val totalKb = parts[1].toLongOrNull() ?: return@withContext null
        val freeKb = parts[3].toLongOrNull() ?: return@withContext null
        StorageInfo(totalKb * 1024, freeKb * 1024, parts.getOrNull(5) ?: path)
    }

    suspend fun walkFolder(path: String): List<FileEntry.Remote> = withContext(Dispatchers.IO) {
        val result = mutableListOf<FileEntry.Remote>()
        val queue = ArrayDeque<String>()
        queue.add(resolvePath(path))
        val visited = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            if (!visited.add(dir)) continue
            val entries = try { list(dir) } catch (_: Throwable) { emptyList() }
            for (e in entries) {
                if (e.isDir) queue.add(e.fullPath) else result.add(e)
            }
        }
        result
    }
}
