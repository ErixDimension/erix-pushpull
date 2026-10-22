package com.erix.twrp.adb

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.widget.Toast
import com.erix.twrp.util.DebugLogger
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.transport.usb.KadbUsb
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

object AdbDeviceWatcher {
    private const val ACTION_USB_PERM = "com.erix.twrp.USB_PERMISSION"
    private val _kadb = MutableStateFlow<Kadb?>(null)
    val kadb: StateFlow<Kadb?> = _kadb
    var currentUsbDevice: UsbDevice? = null; private set
    private var usbManager: UsbManager? = null; private var appContext: Context? = null; private var receiver: BroadcastReceiver? = null
    private var isConnecting = false

    fun start(context: Context) {
        appContext = context.applicationContext; usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val d = getDevice(intent)
                        if (d != null && isAdbDevice(d)) requestPermission(d)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        DebugLogger.log("USB Dicabut")
                        _kadb.value?.close(); _kadb.value = null; currentUsbDevice = null; isConnecting = false
                    }
                    ACTION_USB_PERM -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        if (granted) getDevice(intent)?.let { connectKadb(it) }
                        else Toast.makeText(appContext, "Izin USB DITOLAK", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        val filter = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(ACTION_USB_PERM) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
        usbManager?.deviceList?.values?.forEach { if (isAdbDevice(it)) requestPermission(it) }
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
        if (manager.hasPermission(device)) { connectKadb(device); return }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERM).setPackage(appContext?.packageName), flags)
        manager.requestPermission(device, pi)
    }

    private fun connectKadb(device: UsbDevice) {
        if (isConnecting) return
        isConnecting = true
        DebugLogger.log("Membuka koneksi KadbUsb...")
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val kadbInstance = KadbUsb.create(appContext!!, device)
                withContext(Dispatchers.Main) {
                    _kadb.value = kadbInstance; currentUsbDevice = device
                    DebugLogger.log("✓ Kadb Terhubung!")
                    Toast.makeText(appContext, "✓ Terhubung via Kadb", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                DebugLogger.log("❌ Gagal Kadb: ${e.message}")
                withContext(Dispatchers.Main) { Toast.makeText(appContext, "Gagal: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                isConnecting = false
            }
        }
    }
}
