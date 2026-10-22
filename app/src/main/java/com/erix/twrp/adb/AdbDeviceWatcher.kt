package com.erix.twrp.adb
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.widget.Toast
import kotlinx.coroutines.flow.*
object AdbDeviceWatcher {
    private const val ACTION_USB_PERM = "com.erix.twrp.USB_PERMISSION"
    private val _device = MutableStateFlow<UsbDevice?>(null); val device: StateFlow<UsbDevice?> = _device
    private var usbManager: UsbManager? = null; private var appContext: Context? = null; private var receiver: BroadcastReceiver? = null
    
    fun start(context: Context) {
        appContext = context.applicationContext; usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager; if (receiver != null) return
        receiver = object : BroadcastReceiver() { 
            override fun onReceive(ctx: Context?, intent: Intent?) { 
                when (intent?.action) { 
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> { 
                        val d = getDev(intent); if (d != null && isAdb(d)) reqPerm(d) 
                    }; 
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> { _device.value = null }; 
                    ACTION_USB_PERM -> { 
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        val d = getDev(intent)
                        if (granted && d != null) {
                            _device.value = d 
                            Toast.makeText(appContext, "Izin USB diberikan. Menghubungkan...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(appContext, "Izin USB DITOLAK", Toast.LENGTH_SHORT).show()
                        }
                    } 
                } 
            } 
        }
        val filter = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(ACTION_USB_PERM) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
        usbManager?.deviceList?.values?.forEach { if (isAdb(it)) reqPerm(it) }
    }
    
    private fun getDev(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    
    // Deteksi Interface ADB (0xFF, 0x42, 0x01) -> Berlaku untuk Android & TWRP
    private fun isAdb(d: UsbDevice): Boolean { 
        for (i in 0 until d.interfaceCount) { 
            val intf = d.getInterface(i)
            if (intf.interfaceClass == 0xFF && intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x01) return true 
        }; 
        return false 
    }
    
    private fun reqPerm(d: UsbDevice) { 
        val m = usbManager ?: return; 
        if (m.hasPermission(d)) { _device.value = d; return }; 
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERM).setPackage(appContext?.packageName), flags)
        m.requestPermission(d, pi) 
    }
    suspend fun awaitDevice(): UsbDevice = device.filterNotNull().first()
}
