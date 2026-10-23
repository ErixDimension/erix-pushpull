package com.erix.ptp.ptp

import android.hardware.usb.*
import java.io.*
import java.nio.*

class PtpUsbTransport(
    private val device: UsbDevice,
    private val connection: UsbDeviceConnection
) : Closeable {

    companion object {
        const val TYPE_CMD = 1
        const val TYPE_DATA = 2
        const val TYPE_RESP = 3

        const val OP_OPEN_SESSION = 0x1002
        const val OP_CLOSE_SESSION = 0x1003
        const val OP_GET_STORAGE_IDS = 0x1004
        const val OP_GET_OBJECT_HANDLES = 0x1007
        const val OP_GET_OBJECT_INFO = 0x1008
        const val OP_GET_OBJECT = 0x1009
        const val OP_SEND_OBJECT_INFO = 0x100C
        const val OP_SEND_OBJECT = 0x100D

        const val RESP_OK = 0x2001
        const val RESP_SESSION_ALREADY_OPEN = 0x201e
        const val PARENT_ROOT = 0xFFFFFFFF.toInt()

        private const val BULK_TIMEOUT = 15000
        private const val BULK_BUF = 16384
    }

    private val intf: UsbInterface
    private val epOut: UsbEndpoint
    private val epIn: UsbEndpoint
    private var txn = 0

    init {
        intf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == 0x06 && it.interfaceSubclass == 0x01 && it.interfaceProtocol == 0x01
            } ?: throw IllegalStateException("Interface PTP tidak ditemukan")

        var foundIn: UsbEndpoint? = null
        var foundOut: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) foundIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) foundOut = ep
            }
        }
        epIn = requireNotNull(foundIn) { "PTP IN endpoint tidak ditemukan" }
        epOut = requireNotNull(foundOut) { "PTP OUT endpoint tidak ditemukan" }

        if (!connection.claimInterface(intf, true)) {
            throw IllegalStateException("Gagal claim interface PTP")
        }
    }

    data class Response(val code: Int, val params: IntArray)

    private fun sendCommand(opcode: Int, params: IntArray = IntArray(0)) {
        val len = 12 + params.size * 4
        val bb = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(len); bb.putShort(TYPE_CMD.toShort()); bb.putShort(opcode.toShort()); bb.putInt(txn)
        for (p in params) bb.putInt(p)
        bulkWrite(bb.array())
    }

    private fun sendData(opcode: Int, data: ByteArray) {
        val len = 12 + data.size
        val h = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        h.putInt(len); h.putShort(TYPE_DATA.toShort()); h.putShort(opcode.toShort()); h.putInt(txn)
        bulkWrite(h.array() + data)
    }

    private fun readResponse(): Response {
        val buf = ByteArray(512)
        val n = connection.bulkTransfer(epIn, buf, buf.size, BULK_TIMEOUT)
        if (n < 12) throw PtpException("Respons pendek ($n)")
        val bb = ByteBuffer.wrap(buf, 0, n).order(ByteOrder.LITTLE_ENDIAN)
        bb.int; bb.short
        val code = bb.short.toInt() and 0xFFFF
        bb.int
        val paramCount = (n - 12) / 4
        val params = IntArray(paramCount) { bb.int }
        return Response(code, params)
    }

    private fun readFullData(): ByteArray {
        val buf = ByteArray(65536)
        val n = connection.bulkTransfer(epIn, buf, buf.size, BULK_TIMEOUT)
        if (n < 12) throw PtpException("Data pendek ($n)")
        return buf.copyOfRange(12, n)
    }

    private fun bulkWrite(data: ByteArray) {
        var off = 0
        while (off < data.size) {
            val chunk = minOf(BULK_BUF, data.size - off)
            val slice = data.copyOfRange(off, off + chunk)
            val n = connection.bulkTransfer(epOut, slice, slice.size, BULK_TIMEOUT)
            if (n < 0) throw PtpException("USB write gagal")
            off += n
        }
    }

    fun openSession(): Int {
        txn = 0
        sendCommand(OP_OPEN_SESSION, intArrayOf(1))
        val resp = readResponse()
        if (resp.code != RESP_OK && resp.code != RESP_SESSION_ALREADY_OPEN) {
            throw PtpException("OpenSession: 0x${resp.code.toString(16)}")
        }
        return resp.code
    }

    fun closeSession() {
        txn++; sendCommand(OP_CLOSE_SESSION)
        try { readResponse() } catch (_: Exception) {}
    }

    fun getStorageId(): Int {
        txn++
        sendCommand(OP_GET_STORAGE_IDS)
        val data = readFullData()
        val resp = readResponse()
        if (resp.code != RESP_OK || data.size < 8) throw PtpException("GetStorageIDs gagal")
        return ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    /** Kuras buffer endpoint IN supaya tidak ada data nyangkut. */
    fun drain() {
        try {
            val buf = ByteArray(4096)
            var stall = 0
            while (stall < 2) {
                val n = connection.bulkTransfer(epIn, buf, buf.size, 200)
                if (n <= 0) stall++ else stall = 0
            }
        } catch (_: Exception) {}
    }

    /** Pulihkan session setelah error. */
    fun recover() {
        try { closeSession() } catch (_: Exception) {}
        drain()
        try { openSession() } catch (_: Exception) {}
    }

    data class ObjectEntry(val handle: Int, val name: String, val size: Long, val isDir: Boolean)

    fun listRoot(storageId: Int): List<ObjectEntry> {
        txn++
        sendCommand(OP_GET_OBJECT_HANDLES, intArrayOf(storageId, 0, PARENT_ROOT))
        val data = try { readFullData() } catch (e: Exception) { drain(); throw e }
        val resp = try { readResponse() } catch (e: Exception) { drain(); throw e }
        if (resp.code != RESP_OK || data.size < 4) throw PtpException("GetObjectHandles gagal")

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.int
        if (count < 0 || count > 10000) throw PtpException("Handle count: $count")
        val handles = IntArray(count) { bb.int }

        return handles.toList().mapNotNull { getObjectInfo(it) }
    }

    private fun getObjectInfo(handle: Int): ObjectEntry? {
        txn++
        sendCommand(OP_GET_OBJECT_INFO, intArrayOf(handle))
        val data = try { readFullData() } catch (e: Exception) { drain(); return null }
        val resp = try { readResponse() } catch (e: Exception) { drain(); return null }
        if (resp.code != RESP_OK || data.size <= 52) return null

        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(8)
        val size = bb.int.toLong() and 0xFFFFFFFFL

        val nameLen = data[52].toInt() and 0xFF
        val sb = StringBuilder()
        var pos = 53
        for (i in 0 until (nameLen - 1).coerceAtLeast(0)) {
            if (pos + 1 >= data.size) break
            val code = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            if (code == 0) break
            sb.append(code.toChar())
            pos += 2
        }
        return ObjectEntry(handle, sb.toString(), size, isDir = false)
    }

    fun push(storageId: Int, localFile: File, remoteName: String, onProgress: (Long) -> Unit = {}) {
        val size = localFile.length()
        val objectInfo = buildObjectInfo(storageId, size, remoteName)

        txn++
        sendCommand(OP_SEND_OBJECT_INFO, intArrayOf(storageId, PARENT_ROOT))
        sendData(OP_SEND_OBJECT_INFO, objectInfo)
        val resp1 = readResponse()
        if (resp1.code != RESP_OK) throw PtpException("SendObjectInfo: 0x${resp1.code.toString(16)}")

        txn++
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(12 + size.toInt()); header.putShort(TYPE_DATA.toShort())
        header.putShort(OP_SEND_OBJECT.toShort()); header.putInt(txn)
        sendCommand(OP_SEND_OBJECT)
        bulkWrite(header.array())

        RandomAccessFile(localFile, "r").use { raf ->
            val buf = ByteArray(BULK_BUF)
            var sent = 0L
            while (sent < size) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
                if (n <= 0) break
                bulkWrite(buf.copyOfRange(0, n))
                sent += n
                onProgress(sent)
            }
        }
        val resp2 = readResponse()
        if (resp2.code != RESP_OK) throw PtpException("SendObject: 0x${resp2.code.toString(16)}")
    }

    private fun buildObjectInfo(storageId: Int, size: Long, name: String): ByteArray {
        val nameChars = name.toCharArray()
        val nameLen = nameChars.size + 1
        val total = 52 + 1 + nameLen * 2 + 3
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(storageId); bb.putShort(0x3000.toShort()); bb.putShort(0.toShort())
        bb.putInt(size.toInt()); bb.putShort(0.toShort())
        repeat(6) { bb.putInt(0) }
        bb.putInt(PARENT_ROOT); bb.putShort(0.toShort()); bb.putInt(0); bb.putInt(0)
        bb.put(nameLen.toByte())
        for (c in nameChars) bb.putShort(c.code.toShort())
        bb.putShort(0.toShort())
        bb.put(0.toByte()); bb.put(0.toByte()); bb.put(0.toByte())
        return bb.array().copyOf(bb.position())
    }

    fun pull(storageId: Int, remoteName: String, destFile: File, onProgress: (Long) -> Unit = {}) {
        val entries = listRoot(storageId)
        val entry = entries.firstOrNull { it.name == remoteName }
            ?: throw PtpException("File '$remoteName' tidak ada")

        txn++
        sendCommand(OP_GET_OBJECT, intArrayOf(entry.handle))

        var success = false
        try {
            FileOutputStream(destFile).use { out ->
                var received = 0L; var first = true; var stall = 0
                val buf = ByteArray(BULK_BUF)
                while (received < entry.size) {
                    val n = connection.bulkTransfer(epIn, buf, buf.size, BULK_TIMEOUT)
                    if (n < 0) { stall++; if (stall > 3) break; continue }
                    stall = 0
                    val offset = if (first) 12 else 0
                    first = false
                    if (n <= offset) continue
                    out.write(buf, offset, n - offset)
                    received += (n - offset)
                    onProgress(received)
                }
                if (received != entry.size) throw PtpException("Pull tidak lengkap: $received/${entry.size}")
            }
            val resp = readResponse()
            if (resp.code != RESP_OK) throw PtpException("GetObject: 0x${resp.code.toString(16)}")
            success = true
        } finally {
            if (!success) drain()
        }
    }

    override fun close() {
        try { connection.releaseInterface(intf) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }
}

class PtpException(message: String) : Exception(message)
