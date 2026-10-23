package com.erix.ptp.ptp

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
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
        const val A_CNXN = 0x4e584e43.toInt(); const val A_AUTH = 0x48545541.toInt()
        const val A_VERSION_MIN = 0x01000000; const val A_MAX_PAYLOAD = 4096
        const val ADB_AUTH_TOKEN = 1; const val ADB_AUTH_SIGNATURE = 2; const val ADB_AUTH_RSAPUBLICKEY = 3
    }

    init {
        if (!AdbNative.isLoaded()) throw IOException("Native lib not loaded")
        val m = UsbDeviceConnection::class.java.getDeclaredMethod("getFileDescriptor")
        m.isAccessible = true
        val fd = m.invoke(connection) as Int
        DebugLogger.log("[NATIVE] FD=$fd")
        // Clear Halt sebelum mulai
        val ok1 = AdbNative.clearHalt(fd, inEp.address)
        val ok2 = AdbNative.clearHalt(fd, outEp.address)
        DebugLogger.log("[NATIVE] ClearHalt IN=${ok1} OUT=${ok2}")
    }

    private fun sendMsg(cmd: Int, a0: Int, a1: Int, payload: ByteArray) {
        val bb = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(cmd); bb.putInt(a0); bb.putInt(a1); bb.putInt(payload.size)
        var s = 0; for (b in payload) s += (b.toInt() and 0xFF)
        bb.putInt(s); bb.putInt(cmd xor -1); bb.put(payload)
        DebugLogger.log("[TX] ${24+payload.size}B")
        val r = connection.bulkTransfer(outEp, bb.array(), 24 + payload.size, 5000)
        if (r < 0) throw IOException("TX fail (err: $r)")
    }

    private fun recvMsg(): ByteArray {
        val buf = ByteArray(65536)
        val n = connection.bulkTransfer(inEp, buf, buf.size, 5000)
        if (n < 0) throw IOException("RX fail (err: $n)")
        DebugLogger.log("[RX] ${n}B")
        return buf.copyOf(n)
    }

    fun connect() {
        DebugLogger.log("[ADB] Kirim CNXN...")
        sendMsg(A_CNXN, A_VERSION_MIN, A_MAX_PAYLOAD, "host::erix\u0000".toByteArray())
        var attempts = 0
        while (attempts < 10) {
            attempts++
            val data = recvMsg()
            if (data.size < 24) continue
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val cmd = bb.int; val a0 = bb.int; bb.int; val len = bb.int
            when (cmd) {
                A_CNXN -> { DebugLogger.log("✅ ADB TERHUBUNG!"); return }
                A_AUTH -> {
                    if (a0 == ADB_AUTH_TOKEN) {
                        DebugLogger.log("🔑 AUTH Token")
                        val token = ByteArray(len.coerceAtMost(data.size - 24))
                        bb.get(token)
                        val sig = crypto.sign(token)
                        if (sig != null) sendMsg(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig)
                        else {
                            DebugLogger.log("📱 IZINKAN DI TWRP!")
                            sendMsg(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, crypto.publicKeyBytes())
                            sendMsg(A_CNXN, A_VERSION_MIN, A_MAX_PAYLOAD, "host::erix\u0000".toByteArray())
                        }
                    }
                }
                else -> DebugLogger.log("⚠ Cmd 0x${Integer.toHexString(cmd)}")
            }
        }
        throw IOException("Timeout")
    }
    override fun close() {}
}
