package com.erix.ptp.manager

import com.erix.ptp.data.FileEntry
import com.erix.ptp.ptp.AdbUsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AdbFileManager — file listing & info (list, getStorageInfo, walkFolder).
 * Upload/download dipindah ke AdbTransferManager.
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

    suspend fun list(path: String): List<FileEntry.Remote> = withContext(Dispatchers.IO) {
        val normalized = path.removeSuffix("/").ifEmpty { "/" }
        val out = try {
            transport.exec("ls -la --color=never ${shellQuote(normalized)}/ 2>/dev/null")
        } catch (_: Throwable) {
            return@withContext emptyList()
        }
        out.lines().mapNotNull { parseLsLine(stripAnsi(it), normalized) }
    }

    private fun parseLsLine(line: String, parentPath: String): FileEntry.Remote? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val first = t[0]
        if (first != 'd' && first != '-') return null
        val parts = t.split(Regex("\\s+"), limit = 8)
        if (parts.size < 8) return null
        val name = parts[7]
        if (name == "." || name == "..") return null
        val size = parts[4].toLongOrNull() ?: 0L
        val isDir = first == 'd'
        val fullPath = if (parentPath == "/") "/$name" else "$parentPath/$name"
        return FileEntry.Remote(name, fullPath, parentPath, size, isDir)
    }

    suspend fun getStorageInfo(path: String): StorageInfo? = withContext(Dispatchers.IO) {
        val out = try {
            transport.exec("df -k ${shellQuote(path)} 2>/dev/null")
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
        queue.add(path.removeSuffix("/").ifEmpty { "/" })
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
