package com.erix.ptp.adb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import dadb.AdbTransport
import java.io.Closeable
import java.io.IOException

class UsbTransport(
    private val connection: UsbDeviceConnection,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint
) : AdbTransport, Closeable {

    override fun read(): ByteArray {
        val buf = ByteArray(16384) // 16KB Max
        val n = connection.bulkTransfer(inEp, buf, buf.size, 10000)
        if (n < 0) throw IOException("Read timeout (err: $n)")
        return buf.copyOf(n)
    }

    override fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val chunk = minOf(16384, data.size - off)
            val slice = data.copyOfRange(off, off + chunk)
            val n = connection.bulkTransfer(outEp, slice, chunk, 5000)
            if (n < 0) throw IOException("Write timeout (err: $n)")
            off += n
        }
    }

    override fun close() {
        try { connection.releaseInterface(null) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }
}
