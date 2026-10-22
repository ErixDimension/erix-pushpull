package com.erix.twrp.storage
import android.content.Context
import java.io.File
class LocalStorageManager(private val context: Context) {
    fun list(path: String): List<StorageItem> { val dir = File(path); if (!dir.isDirectory) return emptyList(); return dir.listFiles()?.map { StorageItem(it.name, it.absolutePath, it.isDirectory, if (it.isFile) it.length() else 0L) }?.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() })) ?: emptyList() }
    fun sizeOf(path: String): Long { val f = File(path); if (f.isFile) return f.length(); if (!f.isDirectory) return 0L; var t = 0L; f.walkTopDown().forEach { if (it.isFile) t += it.length() }; return t }
    fun mkdirs(path: String) = File(path).mkdirs()
}
