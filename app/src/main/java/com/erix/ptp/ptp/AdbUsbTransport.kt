package com.erix.ptp.ptp

import android.content.Context
import android.hardware.usb.UsbDevice
import com.erix.ptp.util.DebugLogger
import dadb.AdbKeyPair
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
         * Pastikan key pair ADB ada dalam format yang BENAR.
         * JANGAN generate manual — pakai Dadb.AdbKeyPair.create()
         * yang mengimplementasikan format mincrypt ADB dengan benar.
         */
        private fun ensureAdbKey(context: Context) {
            System.setProperty("user.home", context.filesDir.absolutePath)
            val androidDir = File(context.filesDir, ".android").apply { mkdirs() }
            val priv = File(androidDir, "adbkey")
            val pub  = File(androidDir, "adbkey.pub")

            // Hapus key lama yang dibuat manual (format n+e = SALAH)
            if (priv.exists() || pub.exists()) {
                DebugLogger.log("Hapus key lama (format manual salah)...")
                priv.delete()
                pub.delete()
            }

            // Generate via Dadb → format mincrypt yang benar
            DebugLogger.log("Generate ADB key pair via Dadb (format mincrypt)...")
            AdbKeyPair.create(priv, pub)
            DebugLogger.log("✓ ADB key pair valid: ${priv.absolutePath}")
        }
    }

    private var dadb: Dadb? = null
    val isConnected: Boolean get() = dadb != null

    suspend fun connect(timeoutMs: Long = 25000): Dadb = withContext(Dispatchers.IO) {
        ensureAdbKey(context)   // ✅ key valid sebelum discover

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
                "1. Layar device B UNLOCKED (tidak terkunci)\n" +
                "2. Device B sudah set PIN/password (wajib untuk RSA dialog)\n" +
                "3. USB mode = File Transfer/MTP (bukan Charge only)\n" +
                "4. Developer Options → Revoke USB debugging authorizations → colok ulang\n" +
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
