package com.erix.twrp.adb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import dadb.AdbTransport
import java.io.IOException

class UsbTransport(
    private val connection: UsbDeviceConnection,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint
) : AdbTransport {
    override fun read(): ByteArray {
        val buf = ByteArray(256 * 1024)
        val n = connection.bulkTransfer(inEp, buf, buf.size, 15000)
        if (n <= 0) throw IOException("Read timeout")
        return buf.copyOf(n)
    }

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val w = connection.bulkTransfer(outEp, data, off, data.size - off, 5000)
            if (w <= 0) throw IOException("Write timeout")
            off += w
        }
    }

    override fun close() {
        try { connection.close() } catch (_: Exception) {}
    }
}
