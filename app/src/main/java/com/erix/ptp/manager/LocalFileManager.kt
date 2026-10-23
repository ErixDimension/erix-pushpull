package com.erix.ptp.manager

import com.erix.ptp.data.FileEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalFileManager {
    suspend fun list(path: String): List<FileEntry.Local> = withContext(Dispatchers.IO) {
        val dir = File(path)
        if (!dir.isDirectory) return@withContext emptyList()
        dir.listFiles()?.map { f ->
            FileEntry.Local(
                name = f.name,
                absolutePath = f.absolutePath,
                size = if (f.isFile) f.length() else 0L,
                isDir = f.isDirectory
            )
        }?.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })) ?: emptyList()
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

    fun rootPath(): String = "/storage/emulated/0"
}
