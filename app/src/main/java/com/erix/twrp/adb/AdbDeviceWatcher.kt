package com.erix.twrp.adb
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.widget.Toast
import com.erix.twrp.util.DebugLogger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

object AdbDeviceWatcher {
    private const val ACTION_USB_PERM = "com.erix.twrp.USB_PERMISSION"
    private val _transport = MutableStateFlow<AdbUsbTransport?>(null)
    val transport: StateFlow<AdbUsbTransport?> = _transport
    var currentUsbDevice: UsbDevice? = null; private set
    private var usbManager: UsbManager? = null; private var appContext: Context? = null; private var receiver: BroadcastReceiver? = null
    
    // Anti-Spam Guard
    private var isConnecting = false
    private var lastDeviceId = -1
    private var lastConnectTime = 0L

    fun start(context: Context) {
        appContext = context.applicationContext; usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                when (action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val d = getDevice(intent) ?: return
                        
                        // Cek spam: jika device sama dan belum 2 detik, abaikan
                        if (d.deviceId == lastDeviceId && System.currentTimeMillis() - lastConnectTime < 2000) {
                            return
                        }
                        lastDeviceId = d.deviceId
                        lastConnectTime = System.currentTimeMillis()
                        
                        DebugLogger.log("USB Dicolok: ${d.deviceName}")
                        if (isAdbDevice(d)) {
                            DebugLogger.log("Terdeteksi ADB. Meminta izin...")
                            requestPermission(d)
                        } else {
                            DebugLogger.log("Bukan ADB (Fastboot?).")
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> { 
                        DebugLogger.log("USB Dicabut"); 
                        _transport.value?.close(); _transport.value = null; currentUsbDevice = null; isConnecting = false 
                    }
                    ACTION_USB_PERM -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        DebugLogger.log("Izin USB: ${if (granted) "OKE" else "BATAL"}")
                        if (granted) getDevice(intent)?.let { connect(it) }
                    }
                }
            }
        }
        val filter = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(ACTION_USB_PERM) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
    }

    private fun getDevice(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    private fun isAdbDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == 0xFF && intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x01) return true
        }
        return false
    }

    private fun requestPermission(device: UsbDevice) {
        val manager = usbManager ?: return
        if (manager.hasPermission(device)) { connect(device); return }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERM).setPackage(appContext?.packageName), flags)
        manager.requestPermission(device, pi)
    }

    private fun connect(device: UsbDevice) {
        if (isConnecting) return
        isConnecting = true
        DebugLogger.log("Membuka USB (openDevice)...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conn = usbManager!!.openDevice(device) ?: throw IllegalStateException("openDevice null")
                DebugLogger.log("Koneksi Terbuka. Memulai Transport...")
                val t = AdbUsbTransport(usbManager!!, device, conn, AdbCrypto())
                t.connect()
                withContext(Dispatchers.Main) { 
                    _transport.value = t; currentUsbDevice = device; 
                    DebugLogger.log("✓ SUKSES TERHUBUNG!"); 
                    Toast.makeText(appContext, "✓ Terhubung!", Toast.LENGTH_SHORT).show() 
                }
            } catch (e: Exception) {
                DebugLogger.log("❌ ERROR: ${e.message}")
                withContext(Dispatchers.Main) { Toast.makeText(appContext, "Gagal: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                isConnecting = false
            }
        }
    }
}
