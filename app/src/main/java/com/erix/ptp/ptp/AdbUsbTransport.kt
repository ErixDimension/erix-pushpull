// ═════════════════════════════════════════════════════════════════
// ⚠️  DO NOT DELETE / DO NOT OVERWRITE — WRAPPER API PUBLIK
// Wajib untuk AdbDeviceWatcher. Jangan dihapus.
// Kalau mau ubah, edit AdbLibUsbTransport.kt (bukan file ini).
// ═════════════════════════════════════════════════════════════════


package com.erix.ptp.ptp

import android.content.Context
import android.hardware.usb.UsbDevice
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

/**
 * Wrapper publik yang dipakai AdbDeviceWatcher.
 * Handshake & transfer didelegasikan ke AdbLibUsbTransport.
 */
class AdbUsbTransport(
    private val context: Context,
    private val usbDevice: UsbDevice
) : Closeable {

    companion object {
        /** Dipertahankan agar AdbDeviceWatcher tidak perlu diubah. */
        fun hasAdb(device: UsbDevice): Boolean =
            AdbLibUsbTransport.hasAdb(device)
    }

    private var lib: AdbLibUsbTransport? = null

    val isConnected: Boolean get() = lib?.isConnected == true

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            DebugLogger.setStatus("Membuka koneksi ADB (AdbLib)...")
            val t = AdbLibUsbTransport(context, usbDevice)
            t.connect()
            lib = t
            DebugLogger.setStatus("✓ ADB Terhubung")
        }.also { r ->
            if (r.isFailure) {
                runCatching { lib?.close() }
                lib = null
                DebugLogger.log("❌ AdbLib connect: ${r.exceptionOrNull()?.message}")
            }
        }
    }

    suspend fun push(local: File, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val t = lib ?: error("ADB belum terhubung")
                DebugLogger.setStatus("⬆ Push ${local.name} → $remotePath")
                t.push(local, remotePath)
            }.also { r ->
                if (r.isFailure) DebugLogger.log("❌ Push: ${r.exceptionOrNull()?.message}")
            }
        }

    suspend fun pull(remotePath: String, local: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val t = lib ?: error("ADB belum terhubung")
                DebugLogger.setStatus("⬇ Pull $remotePath → ${local.name}")
                t.pull(remotePath, local)
            }.also { r ->
                if (r.isFailure) DebugLogger.log("❌ Pull: ${r.exceptionOrNull()?.message}")
            }
        }

    override fun close() {
        runCatching { lib?.close() }
        lib = null
        DebugLogger.setStatus("ADB terputus")
    }

    // ──────────────────────────────────────────────────────────
    // [Stage 2a-fix3] Pass-through untuk AdbFileManager.
    // ──────────────────────────────────────────────────────────
    /** Jalankan shell command via transport — suspend. */
    suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        val t = lib ?: error("ADB belum terhubung")
        t.exec(command)
    }
}
