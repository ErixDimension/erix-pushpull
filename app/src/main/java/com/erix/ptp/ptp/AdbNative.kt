package com.erix.ptp.ptp

import android.util.Log

object AdbNative {
    @Volatile private var loaded = false
    
    init {
        try {
            System.loadLibrary("erixadb")
            loaded = true
            Log.i("ERIX_NDK", "✓ liberixadb.so loaded")
        } catch (t: Throwable) {
            Log.e("ERIX_NDK", "✗ load failed: ${t.message}")
        }
    }
    
    fun isLoaded(): Boolean = loaded
    
    external fun initUsb(fd: Int, outEp: Int, inEp: Int): Boolean
    external fun bulkTransfer(epAddr: Int, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int
}
