package com.erix.ptp.ptp

import android.util.Log

object AdbNative {
    @Volatile private var loaded = false
    
    init {
        try {
            System.loadLibrary("erixadb")
            loaded = true
            Log.i("ERIX_NDK", "✓ erixadb.so berhasil dimuat")
        } catch (t: Throwable) {
            Log.e("ERIX_NDK", "✗ Gagal load erixadb: ${t.message}")
        }
    }
    
    fun isLoaded(): Boolean = loaded
    
    external fun initLibusb(fd: Int): Boolean
    external fun bulkTransfer(epAddr: Int, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int
}
