package com.erix.twrp.adb

import android.hardware.usb.*
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
        DebugLogger.log("=== BRIDGE v48 AKTIF (AOSP PURE) ===")
        DebugLogger.log("[USB] Claim interface...")
        // AOSP PURE: Hanya claim. TIDAK setInterface, TIDAK clearHalt di init.
        val claimed = connection.claimInterface(intf, true)
        DebugLogger.log("[USB] Claim: $claimed")
        DebugLogger.log(
            "[USB] Interface id=${intf.id}, class=${intf.interfaceClass}, " +
            "subclass=${intf.interfaceSubclass}, protocol=${intf.interfaceProtocol}"
        )
        DebugLogger.log(
            "[USB] IN addr=0x${Integer.toHexString(inEp.address)}, " +
            "type=${inEp.type}, maxPacket=${inEp.maxPacketSize}"
        )
        DebugLogger.log(
            "[USB] OUT addr=0x${Integer.toHexString(outEp.address)}, " +
            "type=${outEp.type}, maxPacket=${outEp.maxPacketSize}"
        )
        if (!claimed) throw IOException("Claim gagal")
        Thread.sleep(100)
    }

    private fun write(data: ByteArray) {
        val res = connection.bulkTransfer(outEp, data, 0, data.size, 5000)
        if (res < 0) throw IOException("Write fail: $res")
        if (res != data.size) throw IOException("Partial write: $res/${data.size}")
    }

    private fun readExact(buffer: ByteArray, offset: Int, len: Int) {
        var off = offset
        var rem = len
        while (rem > 0) {
            val res = connection.bulkTransfer(inEp, buffer, off, rem, 10000)
            if (res < 0) {
                DebugLogger.log(
                    "[USB] bulkTransfer IN gagal: res=$res, addr=0x" +
                    Integer.toHexString(inEp.address) +
                    ", type=${inEp.type}, maxPacket=${inEp.maxPacketSize}, " +
                    "offset=$off, remaining=$rem"
                )
                throw IOException("Read fail: $res")
            }
            if (res == 0) throw IOException("Read timeout")
            off += res; rem -= res
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
        DebugLogger.log("[USB] Kirim CNXN (A_VERSION_MIN)...")
        val banner = "host::erix-server\u0000".toByteArray()
        val packet = encode(Msg(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION_MIN, maxPayload, banner))
        DebugLogger.log("[USB] TX: ${packet.size} bytes")
        write(packet)
        DebugLogger.log("[USB] Menunggu balasan...")

        while (true) {
            val m = readMsg()
            when (m.cmd) {
                AdbProtocol.A_CNXN -> {
                    DebugLogger.log("[USB] ✓✓✓ CNXN DITERIMA - SUKSES! ✓✓✓")
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
                            DebugLogger.log("[USB] Public key dikirim; menunggu token AUTH berikutnya")
                        }
                    }
                }
                else -> throw IOException("Cmd aneh: ${Integer.toHexString(m.cmd)}")
            }
        }
    }

    override fun close() {
        try { connection.releaseInterface(intf); connection.close() } catch (_: Exception) {}
    }
}
