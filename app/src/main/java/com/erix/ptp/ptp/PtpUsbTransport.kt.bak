package com.erix.ptp.ptp

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PtpException(message: String) : Exception(message)

class PtpUsbTransport(
    device: UsbDevice,
    private val conn: UsbDeviceConnection
) : Closeable {

    companion object {
        const val RESP_OK = 0x2001
        const val RESP_SESSION_OPEN = 0x201e
        private const val TIMEOUT = 15000

        fun hasPtp(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).any {
                val i = device.getInterface(it)
                i.interfaceClass == 0x06 && i.interfaceSubclass == 0x01 && i.interfaceProtocol == 0x01
            }
    }

    private val intf: UsbInterface
    private val epIn: UsbEndpoint
    private val epOut: UsbEndpoint
    private var txn = 0

    init {
        intf = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull {
                it.interfaceClass == 0x06 && it.interfaceSubclass == 0x01 && it.interfaceProtocol == 0x01
            } ?: throw PtpException("Interface PTP tidak ditemukan")

        var fin: UsbEndpoint? = null
        var fout: UsbEndpoint? = null
        for (i in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) fin = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) fout = ep
            }
        }
        epIn = fin ?: throw PtpException("Endpoint IN tidak ada")
        epOut = fout ?: throw PtpException("Endpoint OUT tidak ada")
        if (!conn.claimInterface(intf, true)) throw PtpException("Gagal claim interface PTP")
    }

    private fun sendCommand(opcode: Int, params: IntArray = IntArray(0)) {
        val len = 12 + params.size * 4
        val bb = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(len)
        bb.putShort(1)
        bb.putShort(opcode.toShort())
        bb.putInt(txn)
        for (p in params) bb.putInt(p)
        val n = conn.bulkTransfer(epOut, bb.array(), len, TIMEOUT)
        if (n < 0) throw PtpException("Kirim command 0x${opcode.toString(16)} gagal")
    }

    private fun readResponse(): Int {
        val buf = ByteArray(512)
        val n = conn.bulkTransfer(epIn, buf, buf.size, TIMEOUT)
        if (n < 12) throw PtpException("Respons pendek ($n)")
        return ByteBuffer.wrap(buf, 6, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
    }

    private fun readData(): ByteArray {
        val buf = ByteArray(65536)
        val n = conn.bulkTransfer(epIn, buf, buf.size, TIMEOUT)
        if (n < 12) throw PtpException("Data pendek ($n)")
        return buf.copyOfRange(12, n)
    }

    fun openSession(): Int {
        txn = 0
        sendCommand(0x1002, intArrayOf(1))
        val code = readResponse()
        if (code != RESP_OK && code != RESP_SESSION_OPEN)
            throw PtpException("OpenSession gagal: 0x${code.toString(16)}")
        return code
    }

    fun getStorageId(): Int {
        txn++
        sendCommand(0x1004)
        val data = readData()
        val code = readResponse()
        if (code != RESP_OK || data.size < 8) throw PtpException("GetStorageIDs gagal: 0x${code.toString(16)}")
        return ByteBuffer.wrap(data, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }

    fun closeSession() {
        try { txn++; sendCommand(0x1003); readResponse() } catch (_: Exception) {}
    }

    override fun close() {
        try { conn.releaseInterface(intf) } catch (_: Exception) {}
        try { conn.close() } catch (_: Exception) {}
    }
}
