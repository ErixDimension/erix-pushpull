package com.erix.ptp.ptp

import android.content.Context
import android.hardware.usb.UsbDevice
import com.erix.ptp.util.DebugLogger
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File

class AdbUsbTransport(
    private val context: Context,
    private val usbDevice: UsbDevice
) : Closeable {

    companion object {
        private const val ADB_CLASS = 0xFF
        private const val ADB_SUBCLASS = 0x42
        private const val ADB_PROTOCOL = 0x01

        fun hasAdb(device: UsbDevice): Boolean =
            (0 until device.interfaceCount).any {
                val i = device.getInterface(it)
                i.interfaceClass == ADB_CLASS &&
                    i.interfaceSubclass == ADB_SUBCLASS &&
                    i.interfaceProtocol == ADB_PROTOCOL
            }

        /**
         * Arahkan ~/.android/adbkey ke filesDir agar writable.
         * JANGAN generate key manual — Dadb akan otomatis membuat
         * key pair dengan format yang benar jika belum ada.
         */
        private fun ensureHomeDir(context: Context) {
            val home = context.filesDir.absolutePath
            System.setProperty("user.home", home)
            File(home, ".android").mkdirs()
            DebugLogger.log("ADB home: $home/.android")
        }
    }

    private var dadb: Dadb? = null
    val isConnected: Boolean get() = dadb != null

    suspend fun connect(timeoutMs: Long = 25000): Dadb = withContext(Dispatchers.IO) {
        ensureHomeDir(context)   // ✅ hanya set user.home, biarkan Dadb generate key

        val start = System.currentTimeMillis()
        var lastErr: String? = null

        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val d = Dadb.discover() ?: Dadb.list().firstOrNull()
                if (d != null) { dadb = d; return@withContext d }
            } catch (t: Throwable) { lastErr = t.message }
            delay(500)
        }

        throw IllegalStateException(
            "ADB belum authorize. Pastikan:\n" +
                "1. Layar device B UNLOCKED\n" +
                "2. Device B sudah set PIN/password (wajib untuk RSA dialog)\n" +
                "3. USB mode = File Transfer/MTP\n" +
                "4. Revoke USB debugging authorizations → colok ulang\n" +
                (lastErr?.let { "5. Error: $it" } ?: "")
        )
    }

    suspend fun push(local: File, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = dadb ?: error("ADB belum terhubung")
                require(local.exists()) { "File tidak ada: $local" }
                d.push(local, remotePath)
                DebugLogger.setStatus("✓ Push selesai: ${local.name}")
            }.also { r -> if (r.isFailure) DebugLogger.log("❌ Push: ${r.exceptionOrNull()?.message}") }
        }

    suspend fun pull(remotePath: String, local: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = dadb ?: error("ADB belum terhubung")
                local.parentFile?.mkdirs()
                d.pull(local, remotePath)
                DebugLogger.setStatus("✓ Pull selesai: ${local.name}")
            }.also { r -> if (r.isFailure) DebugLogger.log("❌ Pull: ${r.exceptionOrNull()?.message}") }
        }

    override fun close() {
        runCatching { dadb?.close() }
        dadb = null
    }
}
