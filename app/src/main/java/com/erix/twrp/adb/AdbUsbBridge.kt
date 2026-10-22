package com.erix.twrp.adb

import android.hardware.usb.*
import com.erix.twrp.util.DebugLogger
import kotlinx.coroutines.*
import java.io.*
import java.nio.*

class AdbUsbBridge(
    private val connection: UsbDeviceConnection,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
    private val crypto: AdbCrypto
) : Closeable {
    private var maxPayload = AdbProtocol.A_MAX_PAYLOAD

    init {
        // Menggunakan UsbRequest untuk I/O asinkron (lebih stabil dari bulkTransfer)
        val reqIn = UsbRequest()
        val reqOut = UsbRequest()
        if (!reqIn.initialize(connection, inEp) || !reqOut.initialize(connection, outEp)) {
            throw IOException("Gagal inisialisasi UsbRequest")
        }
    }

    private fun write(data: ByteArray) {
        val buffer = ByteBuffer.wrap(data)
        // Fallback ke bulkTransfer dengan timeout lebih panjang, 
        // karena UsbRequest murni butuh loop thread yang kompleks untuk mini-server.
        // bulkTransfer dengan timeout 5 detik seharusnya cukup jika endpoint bersih.
        val res = connection.bulkTransfer(outEp, data, data.size, 5000)
        if (res < 0) throw IOException("USB Write Stall: $res")
    }

    private fun readExact(buffer: ByteArray, offset: Int, len: Int) {
        var off = offset; var rem = len
        while (rem > 0) {
            val res = connection.bulkTransfer(inEp, buffer, off, rem, 10000)
            if (res < 0) throw IOException("USB Read Stall: $res")
            if (res == 0) throw IOException("Read Timeout")
            off += res; rem -= res
        }
    }

    private data class Msg(val cmd: Int, val a0: Int, val a1: Int, val payload: ByteArray)

    private fun readMsg(): Msg {
        val h = ByteArray(24); readExact(h, 0, 24)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int; val a0 = bb.int; val a1 = bb.int; val len = bb.int; bb.int; bb.int
        val p = ByteArray(len); if (len > 0) readExact(p, 0, len)
        return Msg(cmd, a0, a1, p)
    }

    private fun encode(m: Msg): ByteArray {
        val bb = ByteBuffer.allocate(24 + m.payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(m.cmd); bb.putInt(m.a0); bb.putInt(m.a1); bb.putInt(m.payload.size)
        bb.putInt(checksum(m.payload)); bb.putInt(m.cmd xor -1); bb.put(m.payload)
        return bb.array()
    }

    private fun checksum(p: ByteArray): Int { var s = 0; for (b in p) s += (b.toInt() and 0xFF); return s }

    suspend fun connect() = withContext(Dispatchers.IO) {
        DebugLogger.log("[USB] Handshake...")
        val banner = "host::erix-server\u0000".toByteArray()
        write(encode(Msg(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION_MIN, AdbProtocol.A_MAX_PAYLOAD, banner)))
        
        while (true) {
            val m = readMsg()
            when (m.cmd) {
                AdbProtocol.A_CNXN -> { DebugLogger.log("[USB] CNXN OK"); return@withContext }
                AdbProtocol.A_AUTH -> {
                    if (m.a0 == AdbProtocol.ADB_AUTH_TOKEN) {
                        val sig = crypto.sign(m.payload)
                        if (sig != null) {
                            write(encode(Msg(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_SIGNATURE, 0, sig)))
                        } else {
                            write(encode(Msg(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, crypto.publicKeyBytes())))
                            write(encode(Msg(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION_MIN, maxPayload, banner)))
                        }
                    }
                }
                else -> throw IOException("Unexpected: ${Integer.toHexString(m.cmd)}")
            }
        }
    }

    // Fungsi untuk mengirim data mentah dari socket ke USB (via service)
    suspend fun openService(dest: String): Int = withContext(Dispatchers.IO) {
        val id = 1 // Simplified for Mini-Server
        write(encode(Msg(AdbProtocol.A_OPEN, id, 0, (dest + "\u0000").toByteArray())))
        while (true) {
            val m = readMsg()
            if (m.cmd == AdbProtocol.A_OKAY && m.a1 == id) return@withContext id
            if (m.cmd == AdbProtocol.A_CLSE) throw IOException("Rejected")
        }
        throw IOException("EOF")
    }

    override fun close() { try { connection.close() } catch (_: Exception) {} }
}
