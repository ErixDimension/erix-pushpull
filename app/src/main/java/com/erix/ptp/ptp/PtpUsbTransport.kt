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
        const val OP_GET_STORAGE_INFO = 0x1005
        const val OP_GET_OBJECT_HANDLES = 0x1007
        const val OP_GET_OBJECT_INFO = 0x1008
        const val OP_GET_OBJECT = 0x1009
        const val OP_DELETE_OBJECT = 0x100B
        const val OP_SEND_OBJECT_INFO = 0x100C
        const val OP_SEND_OBJECT = 0x100D
        const val OP_GET_OBJECT_PROP_VALUE = 0x9803
        const val OP_GET_OBJECT_PROP_DESC = 0x9802

        const val PROP_OBJECT_SIZE = 0xDC04
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
            .firstOrNull { it.interfaceClass == 0x06 && it.interfaceSubclass == 0x01 && it.interfaceProtocol == 0x01 }
            ?: throw IllegalStateException("Interface PTP tidak ditemukan")
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
        if (!connection.claimInterface(intf, true)) throw IllegalStateException("Gagal claim interface PTP")
    }

    data class Response(val code: Int, val params: IntArray)
    data class PtpObject(
        val handle: Int, val parentHandle: Int, val name: String,
        val size: Long, val isDir: Boolean, val propSize: Long? = null
    )
    data class StorageInfo(val totalBytes: Long, val freeBytes: Long, val description: String)

    // ============ PTP STREAM READER ============
    /**
     * Baca byte-by-byte dari USB endpoint, buffer internal,
     * supaya header 12-byte tiap PTP packet bisa dibaca utuh.
     */
    private inner class PtpStreamReader {
        private val buf = ByteArray(BULK_BUF)
        private var pos = 0
        private var len = 0
        private var stall = 0

        fun readBytes(count: Int): ByteArray {
            val out = ByteArray(count)
            var written = 0
            while (written < count) {
                if (pos >= len) {
                    len = connection.bulkTransfer(epIn, buf, buf.size, BULK_TIMEOUT)
                    if (len <= 0) {
                        stall++
                        if (stall > 3) throw IOException("Read stall: $len")
                        continue
                    }
                    stall = 0
                    pos = 0
                }
                val toCopy = minOf(count - written, len - pos)
                System.arraycopy(buf, pos, out, written, toCopy)
                pos += toCopy
                written += toCopy
            }
            return out
        }

        fun readHeader(): Header {
            val h = readBytes(12)
            val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
            val length = bb.int
            val type = bb.short.toInt() and 0xFFFF
            val code = bb.short.toInt() and 0xFFFF
            val txn = bb.int
            return Header(length, type, code, txn)
        }
    }

    private data class Header(val length: Int, val type: Int, val code: Int, val txn: Int)

    // ============ LOW-LEVEL HELPERS ============
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
        // Baca header first
        val hdr = ByteArray(12)
        var hdrRead = 0
        while (hdrRead < 12) {
            val n = connection.bulkTransfer(epIn, hdr, hdrRead, 12 - hdrRead, BULK_TIMEOUT)
            if (n <= 0) throw PtpException("Header data fail")
            hdrRead += n
        }
        val bb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val totalLen = bb.int
        bb.short // type
        bb.short // code
        bb.int // txn
        val payloadLen = totalLen - 12
        if (payloadLen <= 0) return ByteArray(0)
        if (payloadLen > 10_000_000) throw PtpException("Payload terlalu besar: $payloadLen")

        val payload = ByteArray(payloadLen)
        var read = 0
        while (read < payloadLen) {
            val n = connection.bulkTransfer(epIn, payload, read, payloadLen - read, BULK_TIMEOUT)
            if (n <= 0) throw PtpException("Payload read fail at $read/$payloadLen")
            read += n
        }
        return payload
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

    // ============ SESSION ============
    fun openSession(): Int {
        txn = 0
        sendCommand(OP_OPEN_SESSION, intArrayOf(1))
        val resp = readResponse()
        if (resp.code != RESP_OK && resp.code != RESP_SESSION_ALREADY_OPEN)
            throw PtpException("OpenSession: 0x${resp.code.toString(16)}")
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

    fun getStorageInfo(storageId: Int): StorageInfo {
        txn++
        sendCommand(OP_GET_STORAGE_INFO, intArrayOf(storageId))
        val data = try { readFullData() } catch (e: Exception) { drain(); throw e }
        val resp = try { readResponse() } catch (e: Exception) { drain(); throw e }
        if (resp.code != RESP_OK || data.size < 26) throw PtpException("GetStorageInfo gagal")
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(6)
        val maxCap = bb.long
        val freeSpace = bb.long
        bb.int
        val desc = parseString(data, bb.position())
        return StorageInfo(maxCap, freeSpace, desc)
    }

    private fun parseString(data: ByteArray, offset: Int): String {
        if (offset >= data.size) return ""
        val lenBytes = data[offset].toInt() and 0xFF
        if (lenBytes == 0) return ""
        val sb = StringBuilder()
        var pos = offset + 1
        for (i in 0 until (lenBytes - 1).coerceAtLeast(0)) {
            if (pos + 1 >= data.size) break
            val code = (data[pos].toInt() and 0xFF) or ((data[pos + 1].toInt() and 0xFF) shl 8)
            if (code == 0) break
            sb.append(code.toChar())
            pos += 2
        }
        return sb.toString()
    }

    fun drain() {
        try {
            val buf = ByteArray(4096); var stall = 0
            while (stall < 2) {
                val n = connection.bulkTransfer(epIn, buf, buf.size, 200)
                if (n <= 0) stall++ else stall = 0
            }
        } catch (_: Exception) {}
    }

    fun recover() {
        try { closeSession() } catch (_: Exception) {}
        drain()
        try { openSession() } catch (_: Exception) {}
    }

    // ============ LIST ============
    fun listDir(storageId: Int, parentHandle: Int): List<PtpObject> {
        txn++
        sendCommand(OP_GET_OBJECT_HANDLES, intArrayOf(storageId, 0, parentHandle))
        val data = try { readFullData() } catch (e: Exception) { drain(); throw e }
        val resp = try { readResponse() } catch (e: Exception) { drain(); throw e }
        if (resp.code != RESP_OK || data.size < 4) throw PtpException("GetObjectHandles gagal")
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = bb.int
        if (count < 0 || count > 100000) throw PtpException("Handle count: $count")
        val handles = IntArray(count) { bb.int }
        return handles.toList().mapNotNull { getObjectInfo(it) }
    }

    fun listRoot(storageId: Int): List<PtpObject> = listDir(storageId, PARENT_ROOT)

    fun getObjectInfo(handle: Int): PtpObject? {
        txn++
        sendCommand(OP_GET_OBJECT_INFO, intArrayOf(handle))
        val data = try { readFullData() } catch (e: Exception) { drain(); return null }
        val resp = try { readResponse() } catch (e: Exception) { drain(); return null }
        if (resp.code != RESP_OK || data.size < 53) return null
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4)
        val objFormat = bb.short.toInt() and 0xFFFF
        val isDir = (objFormat == 0x3001)
        bb.position(8)
        val size = bb.int.toLong() and 0xFFFFFFFFL
        bb.position(38)
        val parentHandle = bb.int
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
        var propSize: Long? = null
        if (!isDir) {
            try { propSize = getObjectPropValue64(handle, PROP_OBJECT_SIZE) } catch (_: Exception) {}
        }
        return PtpObject(handle, parentHandle, sb.toString(), size, isDir, propSize)
    }

    fun getObjectPropValue64(handle: Int, propCode: Int): Long? {
        return try {
            txn++
            sendCommand(OP_GET_OBJECT_PROP_VALUE, intArrayOf(handle, propCode))
            val data = readFullData()
            val resp = readResponse()
            if (resp.code != RESP_OK || data.size < 8) return null
            ByteBuffer.wrap(data, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        } catch (_: Exception) { null }
    }

    fun getObjectPropDescDataType(propCode: Int): Int? {
        return try {
            txn++
            sendCommand(OP_GET_OBJECT_PROP_DESC, intArrayOf(propCode))
            val data = readFullData()
            val resp = readResponse()
            if (resp.code != RESP_OK || data.size < 6) return null
            ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).let { bb ->
                bb.position(4)
                bb.short.toInt() and 0xFFFF
            }
        } catch (_: Exception) { null }
    }

    fun objectExists(storageId: Int, parentHandle: Int, name: String): Int? {
        return try {
            listDir(storageId, parentHandle).firstOrNull { it.name == name }?.handle
        } catch (_: Exception) { null }
    }

    fun deleteObject(handle: Int): Boolean {
        txn++
        sendCommand(OP_DELETE_OBJECT, intArrayOf(handle))
        val resp = try { readResponse() } catch (_: Exception) { return false }
        return resp.code == RESP_OK
    }

    // ============ PUSH ============
    fun pushToFolder(
        storageId: Int, localFile: File, remoteName: String, parentHandle: Int,
        onProgress: (Long, Long) -> Unit = {_,_->},
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        val size = localFile.length()
        val objectInfo = buildObjectInfo(storageId, parentHandle, size, remoteName)
        txn++
        sendCommand(OP_SEND_OBJECT_INFO, intArrayOf(storageId, parentHandle))
        sendData(OP_SEND_OBJECT_INFO, objectInfo)
        val resp1 = readResponse()
        if (resp1.code != RESP_OK) throw PtpException("SendObjectInfo: 0x${resp1.code.toString(16)}")

        txn++
        // Kirim header 12-byte untuk OP_SEND_OBJECT (bukan lewat sendCommand)
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(12 + size.toInt()); header.putShort(TYPE_DATA.toShort())
        header.putShort(OP_SEND_OBJECT.toShort()); header.putInt(txn)
        bulkWrite(header.array())

        RandomAccessFile(localFile, "r").use { raf ->
            val buf = ByteArray(BULK_BUF)
            var sent = 0L
            while (sent < size) {
                if (isCancelled()) return false
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
                if (n <= 0) break
                bulkWrite(buf.copyOfRange(0, n))
                sent += n
                onProgress(sent, size)
            }
        }
        val resp2 = readResponse()
        return resp2.code == RESP_OK
    }

    private fun buildObjectInfo(storageId: Int, parentHandle: Int, size: Long, name: String): ByteArray {
        val nameChars = name.toCharArray()
        val nameLen = nameChars.size + 1
        val total = 52 + 1 + nameLen * 2 + 3
        val bb = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(storageId); bb.putShort(0x3000.toShort()); bb.putShort(0.toShort())
        bb.putInt(size.toInt()); bb.putShort(0.toShort())
        repeat(6) { bb.putInt(0) }
        bb.putInt(parentHandle)
        bb.putShort(0.toShort()); bb.putInt(0); bb.putInt(0)
        bb.put(nameLen.toByte())
        for (c in nameChars) bb.putShort(c.code.toShort())
        bb.putShort(0.toShort())
        bb.put(0.toByte()); bb.put(0.toByte()); bb.put(0.toByte())
        return bb.array().copyOf(bb.position())
    }

    // ============ PULL (FIXED - MULTI-PACKET) ============
    fun pullByHandle(
        handle: Int, destFile: File,
        onProgress: (Long, Long) -> Unit = {_,_->},
        isCancelled: () -> Boolean = { false }
    ): Boolean {
        // Ambil info
        val info = getObjectInfo(handle) ?: throw PtpException("Object $handle tidak ada")
        val compressedSize = info.size
        val realSize = info.propSize?.takeIf { it > 0 } ?: compressedSize
        // Kalau compressed = sentinel 0xFFFFFFFF, wajib pakai propSize
        val expectedSize = if (compressedSize == 0xFFFFFFFFL && info.propSize != null && info.propSize > 0)
            info.propSize else compressedSize

        txn++
        sendCommand(OP_GET_OBJECT, intArrayOf(handle))

        var success = false
        try {
            FileOutputStream(destFile).use { out ->
                val reader = PtpStreamReader()
                var received = 0L
                var packetCount = 0

                while (received < expectedSize) {
                    if (isCancelled()) return false

                    val hdr = reader.readHeader()
                    packetCount++
                    val payloadLen = (hdr.length - 12).toLong()

                    if (hdr.type == TYPE_RESP) {
                        // Unexpected response sebelum data habis
                        throw PtpException("Response dini (0x${hdr.code.toString(16)}) di packet $packetCount, received=$received/$expectedSize")
                    }
                    if (hdr.type != TYPE_DATA) {
                        throw PtpException("Packet type tidak dikenal: ${hdr.type}")
                    }
                    if (payloadLen < 0) throw PtpException("Payload negatif")

                    // Baca payload
                    var remaining = payloadLen
                    while (remaining > 0) {
                        if (isCancelled()) return false
                        val toRead = minOf(remaining, 65536L).toInt()
                        val chunk = reader.readBytes(toRead)
                        out.write(chunk)
                        received += chunk.size
                        remaining -= chunk.size
                        onProgress(received, expectedSize)
                    }
                }
                out.flush()
            }
            // Baca response final
            val reader = PtpStreamReader()  // reader baru karena reader lokal tadi
            // Butuh buffer epIn baru untuk response — baca langsung
            val resp = readResponse()
            success = resp.code == RESP_OK
            if (!success) throw PtpException("GetObject final response: 0x${resp.code.toString(16)}")
            return true
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
