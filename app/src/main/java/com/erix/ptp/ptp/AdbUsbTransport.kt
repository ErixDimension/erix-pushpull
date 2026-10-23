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
    }

    private var dadb: Dadb? = null
    val isConnected: Boolean get() = dadb != null

    suspend fun connect(timeoutMs: Long = 20000): Dadb = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        var lastErr: String? = null

        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val d = Dadb.discover()
                if (d != null) { dadb = d; return@withContext d }
            } catch (t: Throwable) { lastErr = t.message }

            try {
                val m = Dadb.list().firstOrNull()
                if (m != null) { dadb = m; return@withContext m }
            } catch (t: Throwable) { lastErr = t.message }

            delay(500)
        }

        throw IllegalStateException(
            "ADB belum authorize. Setujui dialog 'Allow USB debugging' di device, " +
                "lalu cabut & colok ulang USB." +
                (lastErr?.let { " (last: $it)" } ?: "")
        )
    }

    suspend fun push(local: File, remotePath: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = dadb ?: error("ADB belum terhubung")
                require(local.exists()) { "File tidak ada: $local" }
                val mb = local.length() / (1024.0 * 1024.0)
                DebugLogger.setStatus("⬆ Push ${local.name} (%.1f MB)".format(mb))
                d.push(local, remotePath)
                DebugLogger.setStatus("✓ Push selesai: ${local.name}")
            }.also { r ->
                if (r.isFailure) DebugLogger.log("❌ Push: ${r.exceptionOrNull()?.message}")
            }
        }

    suspend fun pull(remotePath: String, local: File): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val d = dadb ?: error("ADB belum terhubung")
                local.parentFile?.mkdirs()
                DebugLogger.setStatus("⬇ Pull ${local.name}")
                d.pull(local, remotePath)
                DebugLogger.setStatus("✓ Pull selesai: ${local.name}")
            }.also { r ->
                if (r.isFailure) DebugLogger.log("❌ Pull: ${r.exceptionOrNull()?.message}")
            }
        }

    override fun close() {
        runCatching { dadb?.close() }
        dadb = null
    }
}
