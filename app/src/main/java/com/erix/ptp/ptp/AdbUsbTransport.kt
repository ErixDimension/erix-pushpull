package com.erix.ptp.ptp

import android.content.Context
import android.hardware.usb.UsbManager
import com.erix.ptp.util.DebugLogger
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AdbUsbTransport(private val context: Context) {

    private var dadb: Dadb? = null

    val isConnected: Boolean get() = dadb != null

    suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            DebugLogger.setStatus("Mencari perangkat ADB via USB...")
            // Dadb.discover() mendukung perangkat USB jika tersedia
            val d = Dadb.discover()
                ?: error("Tidak ada perangkat ADB USB. Aktifkan USB debugging.")

            dadb = d
            DebugLogger.setStatus("✓ Dadb TERHUBUNG")
        }.also { r ->
            if (r.isFailure) {
                DebugLogger.setStatus("❌ Dadb connect: ${r.exceptionOrNull()?.message}")
            }
        }
    }

    suspend fun push(local: File, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = dadb ?: error("Dadb belum terhubung")
                require(local.exists()) { "File lokal tidak ada: $local" }
                DebugLogger.setStatus("⬆ Push ${local.name} → $remotePath")
                val result = d.push(local, remotePath)
                // Dadb mengembalikan SyncResult, bukan melempar exception
                if (result is dadb.SyncResult.Failure) {
                    error("Push gagal: ${result.reason}")
                }
                DebugLogger.setStatus("✓ Push selesai: ${local.name}")
            }.also { r ->
                if (r.isFailure) DebugLogger.setStatus("❌ Push: ${r.exceptionOrNull()?.message}")
            }
        }

    suspend fun pull(remotePath: String, local: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = dadb ?: error("Dadb belum terhubung")
                local.parentFile?.mkdirs()
                DebugLogger.setStatus("⬇ Pull $remotePath → ${local.name}")
                val result = d.pull(local, remotePath)
                if (result is dadb.SyncResult.Failure) {
                    error("Pull gagal: ${result.reason}")
                }
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
