package com.erix.ptp.adb

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.erix.ptp.ptp.PtpUsbTransport
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AdbDeviceWatcher {
    private const val ACTION_USB_PERM = "com.erix.ptp.USB_PERMISSION"
    @Volatile var transport: PtpUsbTransport? = null; private set
    private var usbManager: UsbManager? = null
    private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var lastId = -1
    private var lastTime = 0L

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
                            val now = System.currentTimeMillis()
                            if (device.deviceId == lastId && now - lastTime < 1500) return
                            lastId = device.deviceId; lastTime = now
                            DebugLogger.log("USB Dicolok: ${device.deviceName}")
                            requestPermission(device)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        transport?.close()
                        transport = null
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
                if (!PtpUsbTransport.hasPtp(device)) {
                    DebugLogger.log("Device tanpa interface PTP, dilewati")
                    return@launch
                }
                DebugLogger.log("Membuka koneksi PTP...")
                val conn = usbManager?.openDevice(device) ?: throw IllegalStateException("openDevice gagal")
                val t = PtpUsbTransport(device, conn)
                transport = t
                val code = t.openSession()
                DebugLogger.log("OpenSession OK (0x${code.toString(16)})")
                val sid = t.getStorageId()
                DebugLogger.log("✓ PTP TERHUBUNG, storage 0x${sid.toString(16)}")
                DebugLogger.setStatus("✓ PTP Terhubung")
            } catch (t: Throwable) {
                DebugLogger.log("❌ PTP Gagal: ${t.message}")
                DebugLogger.setStatus("❌ ${t.message}")
            }
        }
    }
}
