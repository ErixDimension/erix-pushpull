package com.erix.ptp.data

/**
 * Model data untuk file/folder di sisi LOCAL (device A) dan REMOTE (device B via ADB).
 * Remote pakai fullPath (string) karena ADB shell bekerja dengan path, bukan handle PTP.
 */
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
        val fullPath: String,
        val parentPath: String,
        override val size: Long,
        override val isDir: Boolean
    ) : FileEntry()
}
