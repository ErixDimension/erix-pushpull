package com.erix.twrp.adb
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

object AdbDeviceWatcher {
    private const val ACTION_USB_PERM = "com.erix.twrp.USB_PERMISSION"
    private val _transport = MutableStateFlow<AdbUsbTransport?>(null)
    val transport: StateFlow<AdbUsbTransport?> = _transport
    var currentUsbDevice: UsbDevice? = null; private set
    private var usbManager: UsbManager? = null; private var appContext: Context? = null; private var receiver: BroadcastReceiver? = null

    fun start(context: Context) {
        appContext = context.applicationContext; usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val action = intent?.action ?: return
                Log.d("ERIX_DEBUG", "Broadcast Diterima: $action")
                
                when (action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val d = getDevice(intent)
                        Log.d("ERIX_DEBUG", "Device USB Dicolok: ${d?.deviceName} VID:${d?.vendorId} PID:${d?.productId}")
                        if (d != null && isAdbDevice(d)) {
                            Log.d("ERIX_DEBUG", "Device Ini Memiliki Interface ADB! Meminta izin...")
                            requestPermission(d)
                        } else {
                            Log.w("ERIX_DEBUG", "Device ini BUKAN ADB (atau interface tidak dikenali)")
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        Log.d("ERIX_DEBUG", "Device USB Dicabut")
                        _transport.value?.close(); _transport.value = null; currentUsbDevice = null
                    }
                    ACTION_USB_PERM -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        Log.d("ERIX_DEBUG", "Hasil Izin USB: ${if (granted) "DIBERIKAN" else "DITOLAK"}")
                        if (granted) getDevice(intent)?.let { connect(it) }
                        else Toast.makeText(appContext, "Izin USB DITOLAK", Toast.LENGTH_LONG).show()
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
            Log.d("ERIX_DEBUG", "Interface $i: Class=${intf.interfaceClass}, Subclass=${intf.interfaceSubclass}, Proto=${intf.interfaceProtocol}")
            if (intf.interfaceClass == 0xFF && intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x01) return true
        }
        return false
    }

    private fun requestPermission(device: UsbDevice) {
        val manager = usbManager ?: return
        if (manager.hasPermission(device)) { 
            Log.d("ERIX_DEBUG", "Sudah punya izin, langsung connect.")
            connect(device); return 
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERM).setPackage(appContext?.packageName), flags)
        manager.requestPermission(device, pi)
    }

    private fun connect(device: UsbDevice) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("ERIX_DEBUG", "Membuka koneksi USB (openDevice)...")
                val conn = usbManager!!.openDevice(device) ?: throw IllegalStateException("openDevice null")
                Log.d("ERIX_DEBUG", "Koneksi USB terbuka. Membuat Transport...")
                val t = AdbUsbTransport(usbManager!!, device, conn, AdbCrypto())
                Log.d("ERIX_DEBUG", "Memulai Handshake ADB...")
                t.connect()
                withContext(Dispatchers.Main) {
                    _transport.value = t; currentUsbDevice = device
                    Toast.makeText(appContext, "✓ Terhubung!", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ERIX_DEBUG", "ERROR KONEKSI: ${e.message}", e)
                withContext(Dispatchers.Main) { Toast.makeText(appContext, "Gagal: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
