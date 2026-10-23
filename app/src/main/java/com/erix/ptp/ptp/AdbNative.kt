package com.erix.ptp.ptp

import android.util.Log

object AdbNative {
    init {
        try {
            System.loadLibrary("erixadb")
            Log.i("ERIX_NDK", "Library erixadb berhasil dimuat")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("ERIX_NDK", "Gagal memuat library: ${e.message}")
        }
    }

    /**
     * Cek apakah C++ bisa menerima FD
     */
    external fun testConnection(fd: Int): Boolean

    /**
     * Lakukan bulkTransfer LANGSUNG via syscall Linux (ioctl).
     */
    external fun bulkTransferNative(
        fd: Int,
        epAddr: Int,
        isIn: Boolean,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        timeout: Int
    ): Int
}
