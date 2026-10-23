package com.erix.ptp.manager

import com.erix.ptp.data.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * LocalFileManager — operasi file lokal device A.
 *
 * Safety guard:
 * - Path "/" ditolak (SELinux Android tidak allow read root, bisa hang di MIUI)
 * - Timeout 10 detik per list (jaga-jaga kalau filesystem freeze)
 * - canRead() check sebelum listFiles()
 * - Try-catch per file, satu file corrupt tidak gagalkan semua
 */
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

    /**
     * List isi folder lokal.
     * Guard: path "/" atau path di luar /storage/ /sdcard/ langsung return empty.
     * Timeout 10s untuk jaga-jaga kalau filesystem freeze.
     */
    suspend fun list(path: String): List<FileEntry.Local> = withContext(Dispatchers.IO) {
        if (!isSafePath(path)) return@withContext emptyList()

        val dir = File(path)
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
            val destDir = File(destDirPath)
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
