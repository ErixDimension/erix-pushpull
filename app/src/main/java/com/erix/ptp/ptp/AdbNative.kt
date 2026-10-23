package com.erix.ptp.ptp
import android.util.Log
object AdbNative {
    init { System.loadLibrary("erixadb") }
    external fun initLibusb(fd: Int): Boolean
    external fun bulkTransfer(epAddr: Int, buffer: ByteArray, offset: Int, length: Int, timeout: Int): Int
}
