package com.erix.ptp.ptp

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.erix.ptp.util.DebugLogger
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.usb.KadbUsb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ADB over USB via Kadb — bebas limit 4GB PTP/MTP.
 * Perangkat target WAJIB mengaktifkan USB debugging.
 */
class AdbUsbTransport(private val context: Context) {

    private var kadb: Kadb? = null
    private var device: UsbDevice? = null

    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    val isConnected: Boolean get() = kadb != null

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            // ✅ devices(Context), bukan devices(UsbManager)
            val dev = KadbUsb.devices(usbManager).firstOrNull()
                ?: error("Tidak ada interface ADB. Aktifkan USB debugging di perangkat target.")

            if (!usbManager.hasPermission(dev)) {
                error("Izin USB belum diberikan untuk ${dev.deviceName}")
            }

            DebugLogger.setStatus("Membuka koneksi ADB USB...")
            val k = KadbUsb.create(context, dev)   // ✅ create(Context, UsbDevice)

            DebugLogger.setStatus("Menunggu otorisasi ADB (cek layar perangkat)...")
            val pong = k.shell("echo kadb-ok")
            check(pong.exitCode == 0) { "Handshake ADB gagal (exit ${pong.exitCode})" }

            kadb = k
            device = dev
            DebugLogger.setStatus("✓ ADB TERHUBUNG (${dev.deviceName})")
        }.also { r ->
            if (r.isFailure) {
                runCatching { kadb?.close() }
                kadb = null
                DebugLogger.setStatus("❌ ADB connect: ${r.exceptionOrNull()?.message}")
            }
        }
    }

    suspend fun push(local: File, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(local.exists()) { "File lokal tidak ada: $local" }
                val k = kadb ?: error("ADB belum terhubung")
                val mb = local.length() / (1024.0 * 1024.0)
                DebugLogger.setStatus("⬆ Push ${local.name} (%.1f MB) → $remotePath".format(mb))
                k.push(local, remotePath)          // ✅ push(File, String)
                DebugLogger.setStatus("✓ Push selesai: ${local.name}")
            }.also { r ->
                if (r.isFailure) DebugLogger.setStatus("❌ Push: ${r.exceptionOrNull()?.message}")
            }
        }

    suspend fun pull(remotePath: String, local: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val k = kadb ?: error("ADB belum terhubung")
                local.parentFile?.mkdirs()
                DebugLogger.setStatus("⬇ Pull $remotePath → ${local.name}")
                k.pull(local, remotePath)          // ✅ pull(dst, remotePath)
                DebugLogger.setStatus("✓ Pull selesai: ${local.name}")
            }.also { r ->
                if (r.isFailure) DebugLogger.setStatus("❌ Pull: ${r.exceptionOrNull()?.message}")
            }
        }

    fun disconnect() {
        runCatching { kadb?.close() }
        kadb = null
        device = null
        DebugLogger.setStatus("ADB terputus")
    }
}
