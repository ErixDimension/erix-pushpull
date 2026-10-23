package com.erix.ptp.adb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import okio.Buffer
import okio.Source
import okio.Sink
import okio.Timeout
import java.io.IOException

class UsbSource(
    private val connection: UsbDeviceConnection,
    private val inEp: UsbEndpoint
) : Source {
    override fun read(sink: Buffer, byteCount: Long): Long {
        val buf = ByteArray(16384)
        val n = connection.bulkTransfer(inEp, buf, buf.size, 10000)
        if (n < 0) throw IOException("Read timeout (err: $n)")
        sink.write(buf, 0, n)
        return n.toLong()
    }
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() {}
}

class UsbSink(
    private val connection: UsbDeviceConnection,
    private val outEp: UsbEndpoint
) : Sink {
    override fun write(source: Buffer, byteCount: Long) {
        val data = source.readByteArray()
        var off = 0
        while (off < data.size) {
            val chunk = minOf(16384, data.size - off)
            val slice = data.copyOfRange(off, off + chunk)
            val n = connection.bulkTransfer(outEp, slice, chunk, 5000)
            if (n < 0) throw IOException("Write timeout (err: $n)")
            off += n
        }
    }
    override fun flush() {}
    override fun timeout(): Timeout = Timeout.NONE
    override fun close() {}
}
