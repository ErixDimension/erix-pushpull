package com.erix.ptp.manager

import com.erix.ptp.data.FileEntry
import com.erix.ptp.ptp.AdbUsbTransport
import com.erix.ptp.util.DebugLogger
import com.erix.ptp.util.PathUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AdbFileManager(private val transport: AdbUsbTransport) {

    data class StorageInfo(
        val totalBytes: Long,
        val freeBytes: Long,
        val mountPath: String
    )

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun stripAnsi(s: String): String =
        s.replace(Regex("\u001B\\[[;\\d]*m"), "")

    private fun resolvePath(path: String): String {
        val cleaned = PathUtil.cleanPath(path)
        return when {
            cleaned == "/sdcard" -> "/storage/emulated/0"
            cleaned.startsWith("/sdcard/") -> "/storage/emulated/0" + cleaned.substring("/sdcard".length)
            cleaned.startsWith("/mnt/sdcard") -> "/storage/emulated/0" + cleaned.substring("/mnt/sdcard".length)
            cleaned.startsWith("/data/media/0") -> "/storage/emulated/0" + cleaned.substring("/data/media/0".length)
            else -> cleaned
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

        var timeIdx = -1
        for (i in parts.indices.reversed()) {
            if (parts[i].matches(Regex("^\\d{2}:\\d{2}$"))) {
                timeIdx = i
                break
            }
        }
        if (timeIdx < 0 || timeIdx >= parts.size - 1) return null

        var name = parts.drop(timeIdx + 1).joinToString(" ")
        // Bersihkan backslash escaping dari ls
        name = name.replace(Regex("\\\\(.)"), "$1")
        if (name == "." || name == "..") return null

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
