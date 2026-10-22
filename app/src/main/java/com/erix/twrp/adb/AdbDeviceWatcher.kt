package com.erix.twrp.adb
import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import kotlinx.coroutines.flow.*
object AdbDeviceWatcher {
    private const val ACTION_USB_PERM = "com.erix.twrp.USB_PERMISSION"
    private val _device = MutableStateFlow<UsbDevice?>(null); val device: StateFlow<UsbDevice?> = _device
    private var usbManager: UsbManager? = null; private var appContext: Context? = null; private var receiver: BroadcastReceiver? = null
    fun start(context: Context) {
        appContext = context.applicationContext; usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager; if (receiver != null) return
        receiver = object : BroadcastReceiver() { override fun onReceive(ctx: Context?, intent: Intent?) { when (intent?.action) { UsbManager.ACTION_USB_DEVICE_ATTACHED -> { val d = getDev(intent); if (d != null) reqPerm(d) }; UsbManager.ACTION_USB_DEVICE_DETACHED -> { _device.value = null }; ACTION_USB_PERM -> { if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) getDev(intent)?.let { _device.value = it } } } } }
        val filter = IntentFilter().apply { addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED); addAction(UsbManager.ACTION_USB_DEVICE_DETACHED); addAction(ACTION_USB_PERM) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) else context.registerReceiver(receiver, filter)
        usbManager?.deviceList?.values?.forEach { if (isAdb(it)) reqPerm(it) }
    }
    private fun getDev(intent: Intent): UsbDevice? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
    private fun isAdb(d: UsbDevice): Boolean { for (i in 0 until d.interfaceCount) { val it = d.getInterface(i); if (it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x01) return true }; return false }
    private fun reqPerm(d: UsbDevice) { val m = usbManager ?: return; if (m.hasPermission(d)) { _device.value = d; return }; m.requestPermission(d, PendingIntent.getBroadcast(appContext, 0, Intent(ACTION_USB_PERM).setPackage(appContext?.packageName), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) }
    suspend fun awaitDevice(): UsbDevice = device.filterNotNull().first()
}
