package com.erix.twrp.adb
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import com.erix.twrp.util.DebugLogger
import kotlinx.coroutines.*

object AdbDeviceWatcher {
    private const val ACTION_USB_PERM = "com.erix.twrp.USB_PERMISSION"
    var currentUsbDevice: UsbDevice? = null; private set
    private var usbManager: UsbManager? = null; private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null

    fun start(context: Context) {
        appContext = context.applicationContext; usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val d = getDevice(intent); DebugLogger.log("USB Dicolok: ${d?.deviceName}")
                        if (d != null && isAdbDevice(d)) requestPermission(d)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> { DebugLogger.log("USB Dicabut"); currentUsbDevice = null }
                    ACTION_USB_PERM -> {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) getDevice(intent)?.let { connect(it) }
                    }
                }
            }
        }
        val filter = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(ACTION_USB_PERM) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
    }
    private fun getDevice(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    private fun isAdbDevice(device: UsbDevice): Boolean { for (i in 0 until device.interfaceCount) { val intf = device.getInterface(i); if (intf.interfaceClass == 0xFF && intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x01) return true }; return false }
    private fun requestPermission(device: UsbDevice) {
        val manager = usbManager ?: return
        if (manager.hasPermission(device)) { connect(device); return }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        manager.requestPermission(device, PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERM).setPackage(appContext?.packageName), flags))
    }
    private fun connect(device: UsbDevice) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = usbManager!!.openDevice(device) ?: throw IllegalStateException("openDevice null")
                var fi: UsbEndpoint? = null; var fo: UsbEndpoint? = null
                val intf = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull { it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 }
                if (intf != null) { for (i in 0 until intf.endpointCount) { val ep = intf.getEndpoint(i); if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) { if (ep.direction == UsbConstants.USB_DIR_IN) fi = ep; if (ep.direction == UsbConstants.USB_DIR_OUT) fo = ep } } }
                if (fi == null || fo == null) throw IllegalStateException("Endpoint tidak ditemukan")
                if (!conn.claimInterface(intf, true)) throw IllegalStateException("Claim gagal")
                val bridge = AdbUsbBridge(conn, fi, fo, AdbCrypto())
                bridge.connect()
                currentUsbDevice = device
                DebugLogger.log("✓ Bridge v45 berhasil connect!")
                DebugLogger.setStatus("✓ Terhubung!")
                // JALANKAN MINI SERVER!
                ErixAdbServer(bridge).start()
            } catch (e: Exception) { DebugLogger.log("❌ Gagal: ${e.message}") }
        }
    }
}
