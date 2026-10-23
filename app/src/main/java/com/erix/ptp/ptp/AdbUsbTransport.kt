package com.erix.ptp.ptp

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.erix.ptp.util.DebugLogger
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class AdbUsbTransport(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "com.erix.ptp.USB_PERMISSION"
        private const val ADB_CLASS = 0xFF
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01
    }

    private var dadb: Dadb? = null
    private val usbManager: UsbManager
        get() = context.getSystemService(Context.USB_SERVICE) as UsbManager

    val isConnected: Boolean get() = dadb != null

    // ── 1. SCAN: cari perangkat dengan interface ADB ──
    private fun findAdbDevices(): List<UsbDevice> =
        usbManager.deviceList.values.filter { dev ->
            (0 until dev.interfaceCount).any { i ->
                val intf: UsbInterface = dev.getInterface(i)
                intf.interfaceClass == ADB_CLASS &&
                    intf.interfaceSubclass == ADB_SUBCLASS &&
                    intf.interfaceProtocol == ADB_PROTOCOL
            }
        }

    // ── 2. PERMISSION: minta izin USB dan tunggu hasilnya ──
    private suspend fun requestUsbPermission(dev: UsbDevice): Boolean =
        suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    context.unregisterReceiver(this)
                    val granted = intent.getBooleanExtra(
                        UsbManager.EXTRA_PERMISSION_GRANTED, false
                    )
                    if (cont.isActive) cont.resume(granted)
                }
            }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                Context.RECEIVER_NOT_EXPORTED else 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION), flags)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(receiver, IntentFilter(ACTION_USB_PERMISSION))
            }

            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            usbManager.requestPermission(dev, pi)

            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
        }

    // ── 3. ROUTING: pilih Dadb yang cocok dengan perangkat ──
    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val candidates = findAdbDevices()
            check(candidates.isNotEmpty()) {
                "Tidak ada perangkat ADB USB. Aktifkan USB debugging."
            }

            // Pilih perangkat yang belum punya izin dulu, atau yang pertama
            val target = candidates.firstOrNull { !usbManager.hasPermission(it) }
                ?: candidates.first()

            if (!usbManager.hasPermission(target)) {
                DebugLogger.setStatus("Meminta izin USB untuk ${target.deviceName}...")
                val granted = requestUsbPermission(target)
                check(granted) { "Izin USB ditolak untuk ${target.deviceName}" }
            }

            DebugLogger.setStatus("Menghubungkan Dadb ke ${target.deviceName}...")
            val all = Dadb.list()
            check(all.isNotEmpty()) { "Dadb tidak menemukan koneksi ADB" }

            // Routing: utamakan perangkat yang baru diizinkan
            val chosen = all.firstOrNull { it.toString().contains(target.deviceName) }
                ?: all.first()

            dadb = chosen
            DebugLogger.setStatus("✓ Dadb TERHUBUNG (${target.deviceName})")
        }.also { r ->
            if (r.isFailure) {
                DebugLogger.setStatus("❌ SmartUSB: ${r.exceptionOrNull()?.message}")
            }
        }
    }

    // ── PUSH: langsung panggil, exception ditangkap runCatching ──
    suspend fun push(local: File, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = dadb ?: error("Dadb belum terhubung")
                require(local.exists()) { "File tidak ada: $local" }
                DebugLogger.setStatus("⬆ Push ${local.name} → $remotePath")
                d.push(local, remotePath)   // ✅ langsung, tanpa SyncResult
                DebugLogger.setStatus("✓ Push selesai: ${local.name}")
            }.also { r ->
                if (r.isFailure) DebugLogger.setStatus("❌ Push: ${r.exceptionOrNull()?.message}")
            }
        }

    // ── PULL: langsung panggil, exception ditangkap runCatching ──
    suspend fun pull(remotePath: String, local: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = dadb ?: error("Dadb belum terhubung")
                local.parentFile?.mkdirs()
                DebugLogger.setStatus("⬇ Pull $remotePath → ${local.name}")
                d.pull(local, remotePath)   // ✅ langsung, tanpa SyncResult
                DebugLogger.setStatus("✓ Pull selesai: ${local.name}")
            }.also { r ->
                if (r.isFailure) DebugLogger.setStatus("❌ Pull: ${r.exceptionOrNull()?.message}")
            }
        }

    fun disconnect() {
        runCatching { dadb?.close() }
        dadb = null
        DebugLogger.setStatus("Dadb terputus")
    }
}
