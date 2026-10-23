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
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

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

        /** Generate ADB key pair (RSA 2048, PKCS#8 PEM) di internal storage. */
        private fun ensureKeyPair(context: Context): AdbKeyPair {
            val dir = File(context.filesDir, "adb").apply { mkdirs() }
            val priv = File(dir, "adbkey")
            val pub  = File(dir, "adbkey.pub")

            if (!priv.exists() || !pub.exists()) {
                DebugLogger.log("Generate ADB key pair baru...")
                val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                val kp = kpg.generateKeyPair()

                // Private key: PKCS#8 PEM
                val privDer = kp.private.encoded
                val privPem = buildString {
                    appendLine("-----BEGIN PRIVATE KEY-----")
                    appendLine(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(privDer))
                    appendLine("-----END PRIVATE KEY-----")
                }
                priv.writeText(privPem)

                // Public key: base64 dari modulus+exponent (format ADB)
                val rsaPub = kp.public as RSAPublicKey
                val n = rsaPub.modulus.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
                val e = rsaPub.publicExponent.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
                val adbPub = android.util.Base64.encodeToString(n + e, android.util.Base64.NO_WRAP)
                pub.writeText("$adbPub erix@android\n")

                DebugLogger.log("✓ Key pair dibuat: ${priv.absolutePath}")
            }

            return AdbKeyPair.read(priv, pub)
        }
    }

    private var dadb: Dadb? = null
    val isConnected: Boolean get() = dadb != null

    suspend fun connect(timeoutMs: Long = 25000): Dadb = withContext(Dispatchers.IO) {
        val keyPair = ensureKeyPair(context)   // ✅ kunci selalu tersedia
        val start = System.currentTimeMillis()
        var lastErr: String? = null

        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                // ✅ Sisipkan keyPair ke Dadb
                val d = Dadb.discover(keyPair) ?: Dadb.list(keyPair).firstOrNull()
                if (d != null) { dadb = d; return@withContext d }
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
