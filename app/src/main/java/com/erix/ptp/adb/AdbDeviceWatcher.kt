package com.erix.ptp.adb

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.erix.ptp.util.DebugLogger
import dadb.AdbKeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAPrivateKey
import dadb.Dadb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AdbDeviceWatcher {
    private const val ACTION_USB_PERM = "com.erix.ptp.USB_PERMISSION"
    @Volatile var dadbInstance: Dadb? = null; private set
    private var usbManager: UsbManager? = null
    private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null

    fun start(context: Context) {
        appContext = context.applicationContext
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (receiver != null) return

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = getDevice(intent)
                        if (device != null) {
                            DebugLogger.log("USB Dicolok: ${device.deviceName}")
                            requestPermission(device)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        dadbInstance?.close()
                        dadbInstance = null
                        DebugLogger.log("USB Dicabut")
                        DebugLogger.setStatus("Menunggu device...")
                    }
                    ACTION_USB_PERM -> {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            DebugLogger.log("Izin diberikan. Delay 1s...")
                            Handler(Looper.getMainLooper()).postDelayed({
                                getDevice(intent)?.let { connect(it) }
                            }, 1000)
                        } else {
                            DebugLogger.log("Izin USB ditolak")
                        }
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERM)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
    }

    private fun getDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun requestPermission(device: UsbDevice) {
        val manager = usbManager ?: return
        if (manager.hasPermission(device)) {
            connect(device)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERM).setPackage(appContext?.packageName), flags)
        manager.requestPermission(device, pi)
    }

    private fun connect(device: UsbDevice) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = usbManager ?: return@launch
                
                // 1. Cari Interface & Endpoint
                var adbIntf: UsbInterface? = null
                for (i in 0 until device.interfaceCount) {
                    val it = device.getInterface(i)
                    if (it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x01) {
                        adbIntf = it; break
                    }
                }
                if (adbIntf == null) throw IllegalStateException("Bukan interface ADB")
                
                var fi: UsbEndpoint? = null; var fo: UsbEndpoint? = null
                for (i in 0 until adbIntf.endpointCount) {
                    val ep = adbIntf.getEndpoint(i)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) fi = ep
                        if (ep.direction == UsbConstants.USB_DIR_OUT) fo = ep
                    }
                }
                if (fi == null || fo == null) throw IllegalStateException("Endpoint tidak lengkap")

                // 2. Buka Koneksi
                val connection = manager.openDevice(device) ?: throw IllegalStateException("openDevice null")
                if (!connection.claimInterface(adbIntf, true)) throw IllegalStateException("Claim interface gagal")

                // 3. Buat KeyPair (RSA 2048)
                DebugLogger.log("Membuat RSA KeyPair...")
                val kpg = KeyPairGenerator.getInstance("RSA")
                kpg.initialize(2048)
                val kp = kpg.generateKeyPair()
                val adbKeyPair = AdbKeyPair(
                    kp.public as RSAPublicKey,
                    kp.private as RSAPrivateKey
                )

                // 4. Buat Source & Sink (Okio)
                val source = UsbSource(connection, fi)
                val sink = UsbSink(connection, fo)
                
                // 5. Inisialisasi Dadb
                DebugLogger.log("Membuat Dadb Instance...")
                val dadb = Dadb.create(source, sink, adbKeyPair)
                
                dadbInstance = dadb
                DebugLogger.log("✓ DADB TERHUBUNG!")
                DebugLogger.setStatus("✓ Dadb Terhubung")
                
                // 5. Test Shell
                val resp = dadb.shell("echo ERIX_OK").output
                DebugLogger.log("Shell test: $resp")

            } catch (t: Throwable) {
                DebugLogger.log("❌ Dadb Gagal: ${t.message}")
                DebugLogger.setStatus("❌ ${t.message}")
            }
        }
    }
}
