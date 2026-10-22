package com.erix.twrp.adb
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.widget.Toast
import dadb.Dadb
import dadb.AdbKeyPair
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*

object AdbDeviceWatcher {
    private const val ACTION_USB_PERM = "com.erix.twrp.USB_PERMISSION"
    private val _dadb = MutableStateFlow<Dadb?>(null)
    val dadb: StateFlow<Dadb?> = _dadb
    private val _device = MutableStateFlow<UsbDevice?>(null)
    
    var currentUsbDevice: UsbDevice? = null; private set
    
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
                        val d = getDevice(intent)
                        if (d != null && isAdbDevice(d)) requestPermission(d)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        _dadb.value?.close()
                        _dadb.value = null
                        _device.value = null
                    }
                    ACTION_USB_PERM -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        val d = getDevice(intent)
                        if (granted && d != null) connectDadb(d)
                        else Toast.makeText(appContext, "Izin USB DITOLAK", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERM)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)

        usbManager?.deviceList?.values?.forEach { if (isAdbDevice(it)) requestPermission(it) }
    }

    private fun getDevice(intent: Intent): UsbDevice? {
        return if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    }

    private fun isAdbDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == 0xFF && intf.interfaceSubclass == 0x42 && intf.interfaceProtocol == 0x01) return true
        }
        return false
    }

    private fun requestPermission(device: UsbDevice) {
        val manager = usbManager ?: return
        if (manager.hasPermission(device)) { connectDadb(device); return }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERM).setPackage(appContext?.packageName), flags)
        manager.requestPermission(device, pi)
    }

    private fun connectDadb(device: UsbDevice) {
        try {
            val connection = usbManager!!.openDevice(device) ?: throw IllegalStateException("openDevice null")
            var fi: UsbEndpoint? = null
            var fo: UsbEndpoint? = null
            val intf = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
                it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x01
            } ?: device.getInterface(0)
            
            if (!connection.claimInterface(intf, true)) throw IllegalStateException("Gagal claim interface")
            
            for (i in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.direction == UsbConstants.USB_DIR_IN) fi = ep
                    if (ep.direction == UsbConstants.USB_DIR_OUT) fo = ep
                }
            }
            val inEp = fi ?: throw IllegalStateException("No IN Endpoint")
            val outEp = fo ?: throw IllegalStateException("No OUT Endpoint")
            
            val transport = UsbTransport(connection, inEp, outEp)
            // dadb membutuhkan AdbKeyPair untuk enkripsi RSA
            val keyPair = AdbKeyPair.generate()
            val dadbInstance = Dadb.create(transport, keyPair)
            
            _dadb.value = dadbInstance
            currentUsbDevice = device
            Toast.makeText(appContext, "✓ Terhubung via dadb", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(appContext, "Gagal koneksi dadb: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    suspend fun awaitDevice(): UsbDevice = _device.filterNotNull().first()
}
