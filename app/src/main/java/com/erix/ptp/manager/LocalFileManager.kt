package com.erix.ptp.manager

import com.erix.ptp.data.FileEntry
import com.erix.ptp.util.PathUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class LocalFileManager {

    companion object {
        private const val ROOT = "/storage/emulated/0"
        private const val LIST_TIMEOUT_MS = 10_000L
    }

    data class StorageStats(
        val totalBytes: Long,
        val freeBytes: Long,
        val usableBytes: Long
    )

    fun getStorageStats(): StorageStats {
        return try {
            val f = File(rootPath())
            StorageStats(f.totalSpace, f.freeSpace, f.usableSpace)
        } catch (_: Exception) {
            StorageStats(0L, 0L, 0L)
        }
    }

    suspend fun list(path: String): List<FileEntry.Local> = withContext(Dispatchers.IO) {
        val cleaned = PathUtil.cleanPath(path)
        if (!isSafePath(cleaned)) return@withContext emptyList()

        val dir = File(cleaned)
        if (!dir.exists() || !dir.isDirectory || !dir.canRead()) {
            return@withContext emptyList()
        }

        val result = withTimeoutOrNull(LIST_TIMEOUT_MS) {
            try {
                dir.listFiles()?.mapNotNull { f ->
                    try {
                        if (f == null) null
                        else FileEntry.Local(
                            name = f.name,
                            absolutePath = f.absolutePath,
                            size = if (f.isFile) f.length() else 0L,
                            isDir = f.isDirectory
                        )
                    } catch (_: Throwable) { null }
                }?.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
                    ?: emptyList()
            } catch (_: Throwable) {
                emptyList()
            }
        }
        result ?: emptyList()
    }

    private fun isSafePath(path: String): Boolean {
        if (path.isBlank()) return false
        if (path == "/") return false
        if (!path.startsWith("/storage/") && !path.startsWith("/sdcard/")) return false
        return true
    }

    suspend fun copyInto(source: File, destDirPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val destDir = File(PathUtil.cleanPath(destDirPath))
            destDir.mkdirs()
            val target = File(destDir, source.name)
            val finalTarget = if (target.exists()) uniqueName(destDir, source.name) else target
            source.copyTo(finalTarget, overwrite = false)
            true
        } catch (_: Exception) { false }
    }

    private fun uniqueName(dir: File, name: String): File {
        val dot = name.lastIndexOf('.')
        val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        for (n in 2..50) {
            val c = File(dir, "$stem ($n)$ext")
            if (!c.exists()) return c
        }
        return File(dir, "$stem (${System.currentTimeMillis()})$ext")
    }

    fun rootPath(): String = ROOT
}
