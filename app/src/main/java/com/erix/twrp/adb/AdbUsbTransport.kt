package com.erix.twrp.adb

import android.hardware.usb.*
import kotlinx.coroutines.*
import java.io.*
import java.nio.*
import java.util.*

class AdbUsbTransport(private val usbManager: UsbManager, private val device: UsbDevice, private val connection: UsbDeviceConnection, private val crypto: AdbCrypto) : Closeable {
    private val intf: UsbInterface; private val inEp: UsbEndpoint; private val outEp: UsbEndpoint
    private var maxPayload = AdbProtocol.A_MAX_PAYLOAD_V1; private var nextLocalId = 1
    private val pending = mutableMapOf<Int, ByteArrayOutputStream>()
    init {
        intf = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x01 } ?: device.getInterface(0)
        var fi: UsbEndpoint? = null; var fo: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) { val ep = intf.getEndpoint(i); if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) { if (ep.direction == UsbConstants.USB_DIR_IN) fi = ep; if (ep.direction == UsbConstants.USB_DIR_OUT) fo = ep } }
        inEp = fi ?: throw IllegalStateException("No IN"); outEp = fo ?: throw IllegalStateException("No OUT")
        if (!connection.claimInterface(intf, true)) throw IllegalStateException("Gagal klaim interface")
    }
    private fun write(data: ByteArray) { var off = 0; while (off < data.size) { val chunk = minOf(16384, data.size - off); val w = connection.bulkTransfer(outEp, data, off, chunk, 5000); if (w <= 0) throw IOException("Write timeout"); off += w } }
    private fun read(): ByteArray { val buf = ByteArray(maxPayload + 24); val n = connection.bulkTransfer(inEp, buf, buf.size, 5000); if (n <= 0) throw IOException("Read timeout"); return buf.copyOf(n) }
    private data class Msg(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)
    private fun encode(m: Msg): ByteArray { val bb = ByteBuffer.allocate(24 + m.payload.size).order(ByteOrder.LITTLE_ENDIAN); bb.putInt(m.command); bb.putInt(m.arg0); bb.putInt(m.arg1); bb.putInt(m.payload.size); bb.putInt(checksum(m.payload)); bb.putInt(m.command xor -1); bb.put(m.payload); return bb.array() }
    private fun decode(data: ByteArray): Msg { require(data.size >= 24); val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN); val cmd = bb.int; val a0 = bb.int; val a1 = bb.int; val len = bb.int; bb.int; bb.int; val p = ByteArray(len); if (len > 0) bb.get(p); return Msg(cmd, a0, a1, p) }
    private fun checksum(payload: ByteArray): Int { var s = 0; for (b in payload) s += (b.toInt() and 0xFF); return s }
    suspend fun connect() = withContext(Dispatchers.IO) {
        val banner = "host::erix-apk\x00".toByteArray(); write(encode(Msg(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION_SKIP_CHECKSUM, AdbProtocol.A_MAX_PAYLOAD_V2, banner)))
        while (true) { val m = decode(read()); when (m.command) { AdbProtocol.A_CNXN -> { maxPayload = minOf(m.arg1, AdbProtocol.A_MAX_PAYLOAD_V2); return@withContext }; AdbProtocol.A_AUTH -> { if (m.arg0 == AdbProtocol.ADB_AUTH_TOKEN) { val sig = crypto.sign(m.payload); if (sig != null) write(encode(Msg(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_SIGNATURE, 0, sig))) else { write(encode(Msg(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_RSAPUBLICKEY, 0, crypto.publicKeyBytes()))); write(encode(Msg(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION_SKIP_CHECKSUM, maxPayload, banner))) } } else throw IllegalStateException("Auth unknown") }; else -> throw IllegalStateException("Handshake unexpected") } }
    }
    suspend fun openService(dest: String): Int = withContext(Dispatchers.IO) {
        val id = nextLocalId++; write(encode(Msg(AdbProtocol.A_OPEN, id, 0, (dest + "\x00").toByteArray())))
        while (true) { val m = decode(read()); when (m.command) { AdbProtocol.A_OKAY -> if (m.arg1 == id) return@withContext id; AdbProtocol.A_CLSE -> throw IOException("Rejected"); AdbProtocol.A_WRTE -> { pending.getOrPut(m.arg0) { ByteArrayOutputStream() }.write(m.payload); write(encode(Msg(AdbProtocol.A_OKAY, id, m.arg0, ByteArray(0)))) } } }
        throw IllegalStateException("EOF")
    }
    suspend fun writeStream(localId: Int, data: ByteArray) = withContext(Dispatchers.IO) {
        var off = 0
        while (off < data.size) { val chunk = minOf(maxPayload - 24, data.size - off); val slice = data.copyOfRange(off, off + chunk); write(encode(Msg(AdbProtocol.A_WRTE, localId, 0, slice))); while (true) { val m = decode(read()); if (m.command == AdbProtocol.A_OKAY && m.arg1 == localId) break; if (m.command == AdbProtocol.A_CLSE) throw IOException("Closed"); if (m.command == AdbProtocol.A_WRTE) { pending.getOrPut(m.arg0) { ByteArrayOutputStream() }.write(m.payload); write(encode(Msg(AdbProtocol.A_OKAY, localId, m.arg0, ByteArray(0)))) } }; off += chunk }
    }
    suspend fun readAll(localId: Int): ByteArray = withContext(Dispatchers.IO) {
        write(encode(Msg(AdbProtocol.A_OKAY, localId, 0, ByteArray(0)))); val baos = pending.remove(localId) ?: ByteArrayOutputStream()
        while (true) { val m = decode(read()); when (m.command) { AdbProtocol.A_WRTE -> { baos.write(m.payload); write(encode(Msg(AdbProtocol.A_OKAY, localId, m.arg0, ByteArray(0)))) }; AdbProtocol.A_CLSE -> return@withContext baos.toByteArray() } }
        throw IllegalStateException("EOF")
    }
    fun closeStream(localId: Int) { try { write(encode(Msg(AdbProtocol.A_CLSE, localId, 0, ByteArray(0)))) } catch (_: Exception) {} }
    override fun close() { try { connection.releaseInterface(intf) } catch (_: Exception) {}; try { connection.close() } catch (_: Exception) {} }
}
