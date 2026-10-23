package com.erix.ptp.manager

import com.erix.ptp.data.FileEntry
import com.erix.ptp.ptp.AdbUsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AdbFileManager — analog PtpFileManager tapi pakai AdbUsbTransport (AdbLib shell).
 *
 * Semua operasi file remote device B lewat shell ADB:
 *   list        : `ls -la <path>/`
 *   push        : `cat > <dest>`    (via transport.push)
 *   pull        : `cat <src>`       (via transport.pull)
 *   storage     : `df -k <path>`
 *   walkFolder  : BFS via list()
 */
class AdbFileManager(private val transport: AdbUsbTransport) {

    data class StorageInfo(
        val totalBytes: Long,
        val freeBytes: Long,
        val mountPath: String
    )

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    /**
     * List isi direktori. Entry "." dan ".." difilter keluar.
     */
    suspend fun list(path: String): List<FileEntry.Remote> = withContext(Dispatchers.IO) {
        val normalized = path.removeSuffix("/").ifEmpty { "/" }
        val out = try {
            transport.exec("ls -la ${shellQuote(normalized)}/ 2>/dev/null")
        } catch (_: Throwable) {
            return@withContext emptyList()
        }
        out.lines().mapNotNull { parseLsLine(it, normalized) }
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
        return FileEntry.Remote(
            name = name,
            fullPath = fullPath,
            parentPath = parentPath,
            size = size,
            isDir = isDir
        )
    }

    /** Info storage di path tertentu via `df -k`. Return null jika gagal parse. */
    suspend fun getStorageInfo(path: String): StorageInfo? = withContext(Dispatchers.IO) {
        val out = try {
            transport.exec("df -k ${shellQuote(path)} 2>/dev/null")
        } catch (_: Throwable) {
            return@withContext null
        }
        val lines = out.lines().filter { it.isNotBlank() }
        if (lines.size < 2) return@withContext null
        val dataLine = lines[1]
        val parts = dataLine.trim().split(Regex("\\s+"), limit = 6)
        if (parts.size < 4) return@withContext null
        val totalKb = parts[1].toLongOrNull() ?: return@withContext null
        val freeKb  = parts[3].toLongOrNull() ?: return@withContext null
        StorageInfo(
            totalBytes = totalKb * 1024,
            freeBytes  = freeKb * 1024,
            mountPath  = parts.getOrNull(5) ?: path
        )
    }

    /**
     * Upload satu file lokal → remote dest path (path lengkap di remote).
     * Contoh: upload(File("/sdcard/a.txt"), "/sdcard/Download/a.txt")
     */
    suspend fun upload(local: File, remoteDestPath: String): Result<Unit> =
        transport.push(local, remoteDestPath)

    /** Download satu file dari remote source path ke file lokal. */
    suspend fun download(remoteSrcPath: String, local: File): Result<Unit> =
        transport.pull(remoteSrcPath, local)

    /**
     * Walk rekursif: kembalikan semua FILE (bukan folder) di dalam path.
     * BFS dengan visited-set untuk cegah infinite loop.
     */
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
