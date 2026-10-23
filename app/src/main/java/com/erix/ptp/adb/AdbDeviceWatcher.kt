package com.erix.ptp.adb

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.erix.ptp.util.DebugLogger
import dadb.AdbKeyPair
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
                            DebugLogger.log("Izin diberikan. Menunggu sebentar...")
                            Handler(Looper.getMainLooper()).postDelayed({
                                getDevice(intent)?.let { connect(it) }
                            }, 1000) // Jeda 1 detik
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
                val connection = manager.openDevice(device) ?: throw IllegalStateException("Gagal membuka koneksi USB (openDevice null)")

                DebugLogger.log("Membuat Dadb instance...")
                // Dadb membutuhkan AdbKeyPair. Kita gunakan yang di-generate atau baca dari file.
                val keyPair = AdbKeyPair.read(null, null)
                val dadb = Dadb.create(connection, keyPair)
                
                dadbInstance = dadb
                DebugLogger.log("✓ DADB TERHUBUNG!")
                DebugLogger.setStatus("✓ Dadb Terhubung")
                
                // Test koneksi dengan perintah shell sederhana
                val resp = dadb.shell("echo ERIX_OK").output
                DebugLogger.log("Shell test: $resp")

            } catch (t: Throwable) {
                DebugLogger.log("❌ Dadb Gagal: ${t.message}")
                DebugLogger.setStatus("❌ ${t.message}")
            }
        }
    }
}
