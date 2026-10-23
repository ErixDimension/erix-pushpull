// ═════════════════════════════════════════════════════════════════
// ⚠️  DO NOT DELETE / DO NOT OVERWRITE — WRAPPER API PUBLIK
// Wajib untuk AdbDeviceWatcher. Jangan dihapus.
// Kalau mau ubah, edit AdbLibUsbTransport.kt (bukan file ini).
// ─────────────────────────────────────────────────────────────────
// Tanggal : 2026-10-24 (Stage 2c-2: pass-through onProgress)
// ═════════════════════════════════════════════════════════════════


package com.erix.ptp.ptp

import android.content.Context
import android.hardware.usb.UsbDevice
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

class AdbUsbTransport(
    private val context: Context,
    private val usbDevice: UsbDevice
) : Closeable {

    companion object {
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

    suspend fun push(
        local: File,
        remotePath: String,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val t = lib ?: error("ADB belum terhubung")
            DebugLogger.setStatus("⬆ Push ${local.name} → $remotePath")
            t.push(local, remotePath, onProgress)
        }.also { r ->
            if (r.isFailure) DebugLogger.log("❌ Push: ${r.exceptionOrNull()?.message}")
        }
    }

    suspend fun pull(
        remotePath: String,
        local: File,
        expectedSize: Long = 0L,
        onProgress: ((Long, Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val t = lib ?: error("ADB belum terhubung")
            DebugLogger.setStatus("⬇ Pull $remotePath → ${local.name}")
            t.pull(remotePath, local, expectedSize, onProgress)
        }.also { r ->
            if (r.isFailure) DebugLogger.log("❌ Pull: ${r.exceptionOrNull()?.message}")
        }
    }

    override fun close() {
        runCatching { lib?.close() }
        lib = null
        DebugLogger.setStatus("ADB terputus")
    }

    suspend fun exec(command: String): String = withContext(Dispatchers.IO) {
        val t = lib ?: error("ADB belum terhubung")
        t.exec(command)
    }
}
