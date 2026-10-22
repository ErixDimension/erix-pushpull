package com.erix.twrp.storage
import android.content.Context
import java.io.File
class LocalStorageManager(private val context: Context) {
    fun list(path: String): List<StorageItem> {
        val dir = File(path); if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.map { StorageItem(it.name, it.absolutePath, it.isDirectory, if (it.isFile) it.length() else 0L) }?.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })) ?: emptyList()
    }
    fun sizeOf(path: String): Long { val f = File(path); if (f.isFile) return f.length(); if (!f.isDirectory) return 0L; var t = 0L; f.walkTopDown().forEach { if (it.isFile) t += it.length() }; return t }
    fun exists(path: String) = File(path).exists()
    fun mkdirs(path: String) = File(path).mkdirs()
    fun delete(path: String): Boolean { val f = File(path); return if (f.isDirectory) f.deleteRecursively() else f.delete() }
    fun uniqueName(dir: String, name: String): File {
        val dot = name.lastIndexOf('.'); val (stem, ext) = if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        for (n in 2..50) { val c = File(dir, "$stem ($n)$ext"); if (!c.exists()) return c }
        return File(dir, "$stem (${System.currentTimeMillis()})$ext")
    }
}
