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
        const val A_VERSION_MIN = 0x01000000
        const val A_MAX_PAYLOAD = 4096
        const val ADB_AUTH_TOKEN = 1
        const val ADB_AUTH_SIGNATURE = 2
        const val ADB_AUTH_RSAPUBLICKEY = 3
    }

    private val fd: Int by lazy { getUsbFd(connection) }

    init {
        // PENTING: Bersihkan kedua endpoint dari sisa stall koneksi sebelumnya
        try {
            connection.controlTransfer(0x02, 0x01, 0x00, inEp.address, null, 0, 500)
            connection.controlTransfer(0x02, 0x01, 0x00, outEp.address, null, 0, 500)
            DebugLogger.log("[ADB] Clear Halt (IN & OUT) berhasil.")
        } catch (e: Exception) {
            DebugLogger.log("[ADB] Clear Halt gagal: ${e.message}")
        }
    }

    private fun getUsbFd(conn: UsbDeviceConnection): Int {
        try {
            val method = UsbDeviceConnection::class.java.getDeclaredMethod("getFileDescriptor")
            method.isAccessible = true
            return method.invoke(conn) as Int
        } catch (e: Exception) {
            throw RuntimeException("Gagal mendapat FD USB: ${e.message}")
        }
    }

    private fun kotlinWrite(data: ByteArray) {
        val res = connection.bulkTransfer(outEp, data, data.size, 5000)
        if (res < 0) throw IOException("Kotlin Write gagal: $res")
    }

    private fun kotlinRead(buffer: ByteArray, timeout: Int): Int {
        // Paksa maksimal 16KB sesuai batasan driver USB Android
        val safeSize = minOf(buffer.size, 16384)
        return connection.bulkTransfer(inEp, buffer, safeSize, timeout)
    }

    private data class Msg(val cmd: Int, val a0: Int, val a1: Int, val payload: ByteArray)

    private fun sendMsg(cmd: Int, a0: Int, a1: Int, payload: ByteArray) {
        val bb = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(cmd); bb.putInt(a0); bb.putInt(a1); bb.putInt(payload.size)
        bb.putInt(checksum(payload)); bb.putInt(cmd xor -1); bb.put(payload)
        kotlinWrite(bb.array())
    }

    private fun recvMsg(): Msg {
        val headerBuf = ByteArray(24)
        var read = 0
        while (read < 24) {
            val n = kotlinRead(headerBuf, 5000)
            if (n < 0) throw IOException("Kotlin Read gagal (err: $n)")
            read += n
        }
        val bb = ByteBuffer.wrap(headerBuf).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int; val a0 = bb.int; val a1 = bb.int; val len = bb.int; bb.int; bb.int
        
        val payload = ByteArray(if (len > 0) len else 0)
        if (len > 0) {
            var pRead = 0
            while (pRead < len) {
                val n = kotlinRead(payload.copyOfRange(pRead, payload.size), 5000)
                if (pRead < len && n < 0) throw IOException("Kotlin Payload gagal")
                pRead += n
            }
        }
        return Msg(cmd, a0, a1, payload)
    }

    private fun checksum(p: ByteArray): Int { var s = 0; for (b in p) s += (b.toInt() and 0xFF); return s }

    fun connect() {
        DebugLogger.log("[ADB] Mengirim CNXN (Handshake via Kotlin)...")
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
                        DebugLogger.log("🔑 AUTH Token. Menandatangani...")
                        val sig = crypto.sign(msg.payload)
                        if (sig != null) {
                            sendMsg(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig)
                        } else {
                            DebugLogger.log("📱 SILAKAN KLIK IZINKAN DI LAYAR TWRP!")
                            DebugLogger.setStatus("📱 Izinkan ADB di TWRP!")
                            sendMsg(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, crypto.publicKeyBytes())
                            sendMsg(A_CNXN, A_VERSION_MIN, A_MAX_PAYLOAD, banner)
                        }
                    }
                }
                else -> DebugLogger.log("⚠ Pesan: 0x${Integer.toHexString(msg.cmd)}")
            }
        }
    }

    // Fungsi ini yang nanti akan dipanggil untuk transfer >4GB

    fun lockAdbKey(): Boolean {
        try {
            val pubKey = String(crypto.publicKeyBytes(), Charsets.UTF_8)
            // Perintah shell untuk menulis kunci ke file adb_keys
            val cmd = "mkdir -p /data/misc/adb && echo '$pubKey' >> /data/misc/adb/adb_keys && chmod 640 /data/misc/adb/adb_keys"
            // Kita gunakan shell service ADB untuk mengeksekusi perintah ini
            val shellId = openService("shell:$cmd")
            val output = readAll(shellId)
            closeStream(shellId)
            val result = String(output, Charsets.UTF_8)
            DebugLogger.log("Lock result: $result")
            return result.contains("error", ignoreCase = true) == false
        } catch (e: Exception) {
            DebugLogger.log("Lock gagal: ${e.message}")
            return false
        }
    }

    fun getNativeFd(): Int = fd

    override fun close() {
        try { connection.close() } catch (_: Exception) {}
    }
}
