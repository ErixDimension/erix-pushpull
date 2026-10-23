package com.erix.ptp.ptp

import android.hardware.usb.*
import com.erix.ptp.util.DebugLogger
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AdbNativeTransport(
    private val connection: UsbDeviceConnection,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
    private val crypto: AdbCrypto
) : Closeable {

    companion object {
        const val A_CNXN = 0x4e584e43.toInt()
        const val A_AUTH = 0x48545541.toInt()
        const val A_OPEN = 0x4e45504f.toInt()
        const val A_OKAY = 0x59414b4f.toInt()
        const val A_CLSE = 0x45534c43.toInt()
        const val A_WRTE = 0x45545257.toInt()
        
        const val A_VERSION_MIN = 0x01000000
        const val A_MAX_PAYLOAD = 4096
        
        const val ADB_AUTH_TOKEN = 1
        const val ADB_AUTH_SIGNATURE = 2
        const val ADB_AUTH_RSAPUBLICKEY = 3
    }

    private var nextLocalId = 1

    init {
        // Ambil File Descriptor dari UsbDeviceConnection menggunakan reflection
        val fdField = UsbDeviceConnection::class.java.getDeclaredField("mNativeContext")
        fdField.isAccessible = true
        val fd = fdField.get(connection) as Int
        
        val ok = AdbNative.testConnection(fd)
        if (!ok) throw IOException("C++ gagal menerima File Descriptor USB")
        DebugLogger.log("[C++] File Descriptor USB diterima.")
    }

    private fun bulkWrite(data: ByteArray) {
        // Ambil FD lagi untuk C++
        val fdField = UsbDeviceConnection::class.java.getDeclaredField("mNativeContext")
        fdField.isAccessible = true
        val fd = fdField.get(connection) as Int

        val res = AdbNative.bulkTransferNative(fd, outEp.address, false, data, 0, data.size, 5000)
        if (res < 0) throw IOException("C++ Write gagal: $res")
    }

    private fun bulkRead(buffer: ByteArray, timeout: Int): Int {
        val fdField = UsbDeviceConnection::class.java.getDeclaredField("mNativeContext")
        fdField.isAccessible = true
        val fd = fdField.get(connection) as Int

        return AdbNative.bulkTransferNative(fd, inEp.address, true, buffer, 0, buffer.size, timeout)
    }

    private data class Msg(val cmd: Int, val a0: Int, val a1: Int, val payload: ByteArray)

    private fun sendMsg(cmd: Int, a0: Int, a1: Int, payload: ByteArray) {
        val bb = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(cmd); bb.putInt(a0); bb.putInt(a1); bb.putInt(payload.size)
        bb.putInt(checksum(payload)); bb.putInt(cmd xor -1); bb.put(payload)
        bulkWrite(bb.array())
    }

    private fun recvMsg(): Msg {
        val buf = ByteArray(24 + 65536)
        var read = 0
        // Baca header 24 byte dulu
        while (read < 24) {
            val n = bulkRead(buf, 5000) // C++ handle timeout
            if (n < 0) throw IOException("C++ Read gagal (timeout/stall)")
            read += n
            if (read >= 24) break
        }
        val bb = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int; val a0 = bb.int; val a1 = bb.int; val len = bb.int; bb.int; bb.int
        if (len > 0 && len < 65536) {
            var payloadRead = 0
            while (payloadRead < len) {
                val n = bulkRead(buf.copyOfRange(24 + payloadRead, buf.size), 5000)
                if (n < 0) break
                payloadRead += n
            }
        }
        return Msg(cmd, a0, a1, if (len > 0) buf.copyOfRange(24, 24 + len) else ByteArray(0))
    }

    private fun checksum(p: ByteArray): Int { var s = 0; for (b in p) s += (b.toInt() and 0xFF); return s }

    fun connect() {
        DebugLogger.log("[ADB] Mengirim CNXN (Handshake)...")
        val banner = "host::erix-native\u0000".toByteArray()
        sendMsg(A_CNXN, A_VERSION_MIN, A_MAX_PAYLOAD, banner)

        while (true) {
            val msg = recvMsg()
            when (msg.cmd) {
                A_CNXN -> {
                    DebugLogger.log("✅ ADB TERHUBUNG! (CNXN diterima)")
                    DebugLogger.setStatus("✓ ADB Terhubung!")
                    return
                }
                A_AUTH -> {
                    if (msg.a0 == ADB_AUTH_TOKEN) {
                        DebugLogger.log("🔑 Menerima AUTH Token. Menandatangani...")
                        val sig = crypto.sign(msg.payload)
                        if (sig != null) {
                            // Kirim signature
                            sendMsg(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig)
                        } else {
                            // Kirim Public Key (Ini yang memicu popup di TWRP!)
                            DebugLogger.log("📱 SILAKAN KLIK IZINKAN DI LAYAR TWRP!")
                            DebugLogger.setStatus("📱 Izinkan ADB di TWRP!")
                            sendMsg(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, crypto.publicKeyBytes())
                            sendMsg(A_CNXN, A_VERSION_MIN, A_MAX_PAYLOAD, banner)
                        }
                    }
                }
                else -> {
                    DebugLogger.log("⚠ Pesan tak terduga: 0x${Integer.toHexString(msg.cmd)}")
                }
            }
        }
    }

    override fun close() {
        try { connection.close() } catch (_: Exception) {}
    }
}
