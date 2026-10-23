package com.erix.ptp.data

sealed class FileEntry {
    abstract val name: String
    abstract val size: Long
    abstract val isDir: Boolean

    data class Local(
        override val name: String,
        val absolutePath: String,
        override val size: Long,
        override val isDir: Boolean
    ) : FileEntry()

    data class Remote(
        override val name: String,
        val handle: Int,
        val parentHandle: Int,
        override val size: Long,
        override val isDir: Boolean
    ) : FileEntry()
}
