package com.erix.twrp.adb

import android.hardware.usb.*
import android.os.Build
import com.erix.twrp.util.DebugLogger
import kotlinx.coroutines.*
import java.io.*
import java.nio.*

class AdbUsbBridge(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint,
    private val crypto: AdbCrypto
) : Closeable {
    private var maxPayload = AdbProtocol.A_MAX_PAYLOAD

    init {
        DebugLogger.log("=== BRIDGE v45 AKTIF ===")
        DebugLogger.log("[USB] Claim & reset interface...")

        val claimed = connection.claimInterface(intf, true)
        DebugLogger.log("[USB] Claim interface: $claimed")

        try {
            val r = connection.setInterface(intf)
            DebugLogger.log("[USB] setInterface result: $r")
        } catch (e: Exception) {
            DebugLogger.log("[USB] setInterface err: ${e.message}")
        }

        // Clear halt manual untuk IN dan OUT
        try {
            val rhIn = connection.controlTransfer(0x02, 0x01, 0x00, inEp.address, null, 0, 500)
            val rhOut = connection.controlTransfer(0x02, 0x01, 0x00, outEp.address, null, 0, 500)
            DebugLogger.log("[USB] Clear halt: IN=$rhIn, OUT=$rhOut")
        } catch (e: Exception) {
            DebugLogger.log("[USB] Clear halt err: ${e.message}")
        }

        Thread.sleep(300)
    }

    private fun write(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val res = connection.bulkTransfer(outEp, data, off, data.size - off, 5000)
            if (res <= 0) throw IOException("Write fail: $res")
            off += res
        }
    }

    private fun readExact(buffer: ByteArray, offset: Int, len: Int) {
        var off = offset
        var rem = len
        var retries = 0
        while (rem > 0) {
            val res = connection.bulkTransfer(inEp, buffer, off, rem, 10000)
            if (res < 0) {
                if (retries < 3) {
                    retries++
                    DebugLogger.log("[USB] Stall #$retries, clear halt & retry...")
                    try {
                        connection.controlTransfer(0x02, 0x01, 0x00, inEp.address, null, 0, 500)
                        Thread.sleep(100)
                    } catch (_: Exception) {}
                    continue
                }
                throw IOException("Read fail: $res (stlh $retries retry)")
            }
            if (res == 0) throw IOException("Read timeout")
            off += res
            rem -= res
            retries = 0
        }
    }

    private data class Msg(val cmd: Int, val a0: Int, val a1: Int, val payload: ByteArray)

    private fun readMsg(): Msg {
        val h = ByteArray(24)
        readExact(h, 0, 24)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int; val a0 = bb.int; val a1 = bb.int; val len = bb.int; bb.int; bb.int
        val p = ByteArray(len)
        if (len > 0) readExact(p, 0, len)
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
        DebugLogger.log("[USB] Handshake CNXN...")
        // Ganti ke A_VERSION_MIN (0x01000000) untuk kompatibilitas TWRP maksimal
        val banner = "host::erix-server\u0000".toByteArray()
        val packet = encode(Msg(AdbProtocol.A_VERSION_MIN, AdbProtocol.A_VERSION_MIN, maxPayload, banner))
        
        DebugLogger.log("[USB] TX CNXN: ${packet.size} bytes")
        write(packet)
        
        Thread.sleep(200)
        DebugLogger.log("[USB] Menunggu respons CNXN...")

        while (true) {
            val m = readMsg()
            when (m.cmd) {
                AdbProtocol.A_CNXN -> {
                    DebugLogger.log("[USB] ✓ CNXN OK! Device siap.")
                    return@withContext
                }
                AdbProtocol.A_AUTH -> {
                    DebugLogger.log("[USB] AUTH diminta")
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

    override fun close() { try { connection.releaseInterface(intf); connection.close() } catch (_: Exception) {} }
}
