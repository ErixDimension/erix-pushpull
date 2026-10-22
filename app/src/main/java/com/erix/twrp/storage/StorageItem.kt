package com.erix.twrp.storage
data class StorageItem(val name: String, val path: String, val isDir: Boolean, val size: Long) {
    val isArchive: Boolean get() { val n = name.lowercase(); return n.endsWith(".zip") || n.endsWith(".tgz") || n.endsWith(".tar.gz") || n.endsWith(".tar") || n.endsWith(".tar.md5") }
}
