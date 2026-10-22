package com.erix.twrp.adb

import android.hardware.usb.*
import android.os.Build
import com.erix.twrp.util.DebugLogger
import kotlinx.coroutines.*
import java.io.*
import java.nio.*

class AdbUsbTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val crypto: AdbCrypto
) : Closeable {
    private val intf: UsbInterface
    private val inEp: UsbEndpoint
    private val outEp: UsbEndpoint
    private var maxPayload = AdbProtocol.A_MAX_PAYLOAD_V1
    private var nextLocalId = 1
    private val pending = mutableMapOf<Int, ByteArrayOutputStream>()

    init {
        var adbIntf: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val it = device.getInterface(i)
            DebugLogger.log("Interface $i: Class=${it.interfaceClass}, Sub=${it.interfaceSubclass}, Proto=${it.interfaceProtocol}")
            if (it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x01) {
                adbIntf = it
                break
            }
        }
        if (adbIntf == null) throw IllegalStateException("Bukan interface ADB!")
        intf = adbIntf
        
        var fi: UsbEndpoint? = null
        var fo: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) fi = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) fo = ep
            }
        }
        inEp = fi ?: throw IllegalStateException("No IN Endpoint")
        outEp = fo ?: throw IllegalStateException("No OUT Endpoint")
        
        DebugLogger.log("Endpoint IN=${Integer.toHexString(inEp.address)}, OUT=${Integer.toHexString(outEp.address)}")

        var claimed = connection.claimInterface(intf, false)
        if (!claimed) {
            DebugLogger.log("Claim(false) gagal, coba force(true)...")
            claimed = connection.claimInterface(intf, true)
        }
        if (!claimed) throw IllegalStateException("Gagal klaim interface.")
        DebugLogger.log("Interface berhasil di-claim.")

        try { connection.setInterface(intf); DebugLogger.log("setInterface OK.") } catch (e: Exception) { DebugLogger.log("setInterface warning: ${e.message}") }

        // clearHalt hanya tersedia di API 28+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                connection.clearHalt(inEp)
                connection.clearHalt(outEp)
                DebugLogger.log("Endpoint halt dibersihkan (API 28+).")
            } catch (e: Exception) {
                DebugLogger.log("clearHalt warning: ${e.message}")
            }
        } else {
            DebugLogger.log("API < 28, clearHalt di-skip.")
        }

        Thread.sleep(200)
    }

    private fun write(data: ByteArray) {
        if (data.size >= 4) DebugLogger.log("TX: ${data.size} bytes, cmd=${Integer.toHexString(ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).int)}")
        var off = 0
        while (off < data.size) {
            val chunk = minOf(16384, data.size - off)
            val w = connection.bulkTransfer(outEp, data, off, chunk, 5000)
            if (w < 0) throw IOException("USB Write Stall: $w")
            if (w == 0) throw IOException("Write Timeout")
            off += w
        }
    }

    private fun readExact(buffer: ByteArray, offset: Int, len: Int) {
        var off = offset
        var rem = len
        var retries = 0
        while (rem > 0) {
            val n = connection.bulkTransfer(inEp, buffer, off, rem, 10000)
            if (n < 0) {
                if (retries < 2) {
                    retries++
                    DebugLogger.log("USB Read Stall ($n). Retry $retries/2...")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        try { connection.clearHalt(inEp) } catch (_: Exception) {}
                    }
                    Thread.sleep(100)
                    continue
                }
                throw IOException("USB Read Stall: $n")
            }
            if (n == 0) throw IOException("Read timeout (TWRP diam)")
            off += n
            rem -= n
            retries = 0
        }
    }

    private data class Msg(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun readMsg(): Msg {
        val h = ByteArray(24)
        readExact(h, 0, 24)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.int; val a0 = bb.int; val a1 = bb.int; val len = bb.int; bb.int; bb.int
        DebugLogger.log("RX: cmd=${Integer.toHexString(cmd)}, arg0=$a0, arg1=$a1, len=$len")
        val p = ByteArray(len); if (len > 0) readExact(p, 0, len)
        return Msg(cmd, a0, a1, p)
    }

    private fun encode(m: Msg): ByteArray {
        val bb = ByteBuffer.allocate(24 + m.payload.size).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(m.command); bb.putInt(m.arg0); bb.putInt(m.arg1); bb.putInt(m.payload.size)
        bb.putInt(checksum(m.payload)); bb.putInt(m.command xor -1); bb.put(m.payload)
        return bb.array()
    }

    private fun checksum(p: ByteArray): Int { var s = 0; for (b in p) s += (b.toInt() and 0xFF); return s }

    suspend fun connect() = withContext(Dispatchers.IO) {
        DebugLogger.log("Memulai Handshake ADB...")
        val banner = "host::erix-apk\u0000".toByteArray()
        write(encode(Msg(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION_SKIP_CHECKSUM, AdbProtocol.A_MAX_PAYLOAD_V1, banner)))
        while (true) {
            val m = readMsg()
            when (m.command) {
                AdbProtocol.A_CNXN -> { DebugLogger.log("✓ Menerima CNXN (Sukses!)"); maxPayload = minOf(m.arg1, AdbProtocol.A_MAX_PAYLOAD_V2); return@withContext }
                AdbProtocol.A_AUTH -> {
                    DebugLogger.log("Menerima AUTH (Token)")
                    if (m.arg0 == AdbProtocol.ADB_AUTH_TOKEN) {
                        val sig = crypto.sign(m.payload)
                        if (sig != null) write(encode(Msg(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_SIGNATURE, 0, sig)))
                        else { write(encode(Msg(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, crypto.publicKeyBytes()))); write(encode(Msg(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION_SKIP_CHECKSUM, maxPayload, banner))) }
                    } else throw IllegalStateException("Auth unknown")
                }
                else -> throw IllegalStateException("Unexpected: ${Integer.toHexString(m.command)}")
            }
        }
    }

    suspend fun openService(dest: String): Int = withContext(Dispatchers.IO) {
        val id = nextLocalId++; write(encode(Msg(AdbProtocol.A_OPEN, id, 0, (dest + "\u0000").toByteArray())))
        while (true) { val m = readMsg(); when (m.command) {
            AdbProtocol.A_OKAY -> if (m.arg1 == id) return@withContext id
            AdbProtocol.A_CLSE -> throw IOException("Rejected")
            AdbProtocol.A_WRTE -> { pending.getOrPut(m.arg0) { ByteArrayOutputStream() }.write(m.payload); write(encode(Msg(AdbProtocol.A_OKAY, id, m.arg0, ByteArray(0)))) } } }
        throw IllegalStateException("EOF")
    }
    suspend fun writeStream(localId: Int, data: ByteArray) = withContext(Dispatchers.IO) {
        var off = 0
        while (off < data.size) { val chunk = minOf(maxPayload - 24, data.size - off); val slice = data.copyOfRange(off, off + chunk); write(encode(Msg(AdbProtocol.A_WRTE, localId, 0, slice)))
            while (true) { val m = readMsg()
                if (m.command == AdbProtocol.A_OKAY && m.arg1 == localId) break
                if (m.command == AdbProtocol.A_CLSE) throw IOException("Closed")
                if (m.command == AdbProtocol.A_WRTE) { pending.getOrPut(m.arg0) { ByteArrayOutputStream() }.write(m.payload); write(encode(Msg(AdbProtocol.A_OKAY, localId, m.arg0, ByteArray(0)))) } }; off += chunk } }
    suspend fun readAll(localId: Int): ByteArray = withContext(Dispatchers.IO) {
        write(encode(Msg(AdbProtocol.A_OKAY, localId, 0, ByteArray(0)))); val baos = pending.remove(localId) ?: ByteArrayOutputStream()
        while (true) { val m = readMsg(); when (m.command) {
            AdbProtocol.A_WRTE -> { baos.write(m.payload); write(encode(Msg(AdbProtocol.A_OKAY, localId, m.arg0, ByteArray(0)))) }
            AdbProtocol.A_CLSE -> return@withContext baos.toByteArray() } }
        throw IllegalStateException("EOF")
    }
    fun closeStream(localId: Int) { try { write(encode(Msg(AdbProtocol.A_CLSE, localId, 0, ByteArray(0)))) } catch (_: Exception) {} }
    override fun close() { try { connection.releaseInterface(intf) } catch (_: Exception) {}; try { connection.close() } catch (_: Exception) {} }
}
