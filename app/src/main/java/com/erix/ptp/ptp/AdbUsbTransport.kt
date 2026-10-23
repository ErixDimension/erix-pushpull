package com.erix.ptp.ptp

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.usb.KadbUsb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ADB over USB — bebas limit 4GB (PTP/MTP).
 * Pakai Kadb (sync protocol, chunk 64KB, dukung file multi-GB).
 */
class AdbUsbTransport(private val context: Context) {

    private var kadb: Kadb? = null
    private var device: UsbDevice? = null

    val isConnected: Boolean get() = kadb != null

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val usb = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val dev = usb.deviceList.values.firstOrNull()
                ?: error("Tidak ada USB device")
            device = dev
            if (!usb.hasPermission(dev)) {
                error("USB permission belum diberikan untuk ${dev.deviceName}")
            }

            DebugLogger.setStatus("Membuka koneksi ADB USB...")
            kadb = KadbUsb.create(dev, usb)

            DebugLogger.setStatus("✓ ADB TERHUBUNG (${dev.deviceName})")
        }.also { r ->
            r.onFailure { DebugLogger.setStatus("❌ ADB connect: ${it.message}") }
        }
    }

    suspend fun push(local: File, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val k = kadb ?: error("ADB belum terhubung")
                val mb = local.length() / (1024.0 * 1024.0)
                DebugLogger.setStatus("⬆ Push ${local.name} (%.1f MB) → $remotePath".format(mb))
                k.push(local to remotePath)
                DebugLogger.setStatus("✓ Push selesai: ${local.name}")
            }.also { r ->
                r.onFailure { DebugLogger.setStatus("❌ Push: ${it.message}") }
            }
        }

    suspend fun pull(remotePath: String, local: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val k = kadb ?: error("ADB belum terhubung")
                DebugLogger.setStatus("⬇ Pull $remotePath → ${local.name}")
                k.pull(remotePath to local)
                DebugLogger.setStatus("✓ Pull selesai: ${local.name}")
            }.also { r ->
                r.onFailure { DebugLogger.setStatus("❌ Pull: ${it.message}") }
            }
        }

    fun disconnect() {
        runCatching { kadb?.close() }
        kadb = null
        device = null
        DebugLogger.setStatus("ADB terputus")
    }
}
