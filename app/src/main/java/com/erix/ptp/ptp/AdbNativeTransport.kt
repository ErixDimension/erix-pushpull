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
        const val A_CNXN = 0x4e584e43.toInt()
        const val A_AUTH = 0x48545541.toInt()
        const val A_VERSION_MIN = 0x01000000
        const val A_MAX_PAYLOAD = 4096
        const val ADB_AUTH_TOKEN = 1
        const val ADB_AUTH_SIGNATURE = 2
        const val ADB_AUTH_RSAPUBLICKEY = 3
    }

    private data class Msg(val cmd: Int, val a0: Int, val a1: Int, val payload: ByteArray)

    init {
        try {
            connection.controlTransfer(0x02, 0x01, 0x00, inEp.address, null, 0, 500)
            connection.controlTransfer(0x02, 0x01, 0x00, outEp.address, null, 0, 500)
            DebugLogger.log("[ADB] Clear Halt OK")
        } catch (e: Exception) {
            DebugLogger.log("[ADB] Clear Halt gagal: ${e.message}")
        }
    }

    private fun hexDump(data: ByteArray, maxLen: Int = 32): String {
        val sb = StringBuilder()
        val limit = minOf(data.size, maxLen)
        for (i in 0 until limit) {
            sb.append(String.format("%02X ", data[i]))
        }
        if (data.size > maxLen) sb.append("...")
        return sb.toString()
    }

    private fun kotlinWrite(data: ByteArray) {
        DebugLogger.log("[TX] ${data.size}B: ${hexDump(data)}")
        var offset = 0
        while (offset < data.size) {
            val chunk = minOf(16384, data.size - offset)
            val slice = data.copyOfRange(offset, offset + chunk)
            val res = connection.bulkTransfer(outEp, slice, chunk, 5000)
            if (res < 0) throw IOException("TX fail: $res")
            offset += res
        }
    }

    private fun kotlinRead(expectedLen: Int, timeout: Int): ByteArray {
        val buffer = ByteArray(expectedLen)
        var read = 0
        while (read < expectedLen) {
            val res = connection.bulkTransfer(inEp, buffer, read, expectedLen - read, timeout)
            if (res <= 0) {
                if (res == 0) throw IOException("RX timeout")
                throw IOException("RX fail (stall): $res")
            }
            read += res
        }
        DebugLogger.log("[RX] ${buffer.size}B: ${hexDump(buffer)}")
        return buffer
    }

    private fun checksum(p: ByteArray): Int { 
        var s = 0
        for (b in p) s += (b.toInt() and 0xFF)
        return s 
    }

    private fun sendMsg(cmd: Int, a0: Int, a1: Int, payload: ByteArray) {
        val bb = ByteBuffer.allocate(24 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(cmd); bb.putInt(a0); bb.putInt(a1); bb.putInt(payload.size)
        bb.putInt(checksum(payload)); bb.putInt(cmd xor -1); bb.put(payload)
        kotlinWrite(bb.array())
    }

    private fun recvMsg(): Msg {
        val header = kotlinRead(24, 5000)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int
        val a0 = bb.int
        val a1 = bb.int
        val len = bb.int
        bb.int // checksum
        bb.int // magic
        
        val payload = if (len > 0) kotlinRead(len, 5000) else ByteArray(0)
        return Msg(cmd, a0, a1, payload)
    }

    fun connect() {
        DebugLogger.log("[ADB] Mengirim CNXN...")
        val banner = "host::erix\u0000".toByteArray()
        sendMsg(A_CNXN, A_VERSION_MIN, A_MAX_PAYLOAD, banner)

        var loop = true
        while (loop) {
            val msg = recvMsg()
            when (msg.cmd) {
                A_CNXN -> {
                    DebugLogger.log("✅ ADB TERHUBUNG! (CNXN OK)")
                    DebugLogger.setStatus("✓ ADB Terhubung!")
                    loop = false
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
                else -> DebugLogger.log("⚠ Cmd: 0x${Integer.toHexString(msg.cmd)}")
            }
        }
    }

    override fun close() {
        try { connection.close() } catch (_: Exception) {}
    }
}
