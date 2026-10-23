package com.erix.ptp.ptp

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.*

object PtpWatcher {
    private const val ACTION_USB_PERM = "com.erix.ptp.USB_PERMISSION"
    @Volatile var transport: PtpUsbTransport? = null; private set
    @Volatile var storageId: Int = -1; private set
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
                        val d = getDevice(intent) ?: return
                        val now = System.currentTimeMillis()
                        if (d.deviceId == lastId && now - lastTime < 1000) return
                        lastId = d.deviceId; lastTime = now
                        DebugLogger.log("USB Dicolok: ${d.deviceName}")
                        if (isPtp(d)) requestPerm(d) else DebugLogger.log("Bukan PTP")
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        DebugLogger.log("USB Dicabut")
                        try { transport?.closeSession(); transport?.close() } catch (_: Exception) {}
                        transport = null; storageId = -1
                        DebugLogger.setStatus("Menunggu device...")
                    }
                    ACTION_USB_PERM -> {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                            getDevice(intent)?.let { connect(it) }
                        else DebugLogger.log("Izin USB ditolak")
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

    private fun isPtp(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val it = device.getInterface(i)
            if (it.interfaceClass == 0x06 && it.interfaceSubclass == 0x01 && it.interfaceProtocol == 0x01) {
                DebugLogger.log("PTP interface ditemukan (i=$i)")
                return true
            }
        }
        return false
    }

    private fun requestPerm(device: UsbDevice) {
        val m = usbManager ?: return
        if (m.hasPermission(device)) { connect(device); return }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        m.requestPermission(device,
            PendingIntent.getBroadcast(appContext, 0,
                Intent(ACTION_USB_PERM).setPackage(appContext?.packageName), flags))
    }

    private fun connect(device: UsbDevice) {
        CoroutineScope(Dispatchers.IO).launch {
            DebugLogger.log("=== v52 PTP PULL ===")
            try {
                val conn = usbManager!!.openDevice(device) ?: throw IllegalStateException("openDevice null")
                DebugLogger.log("Transport init...")
                val t = PtpUsbTransport(device, conn)
                DebugLogger.log("OpenSession...")
                t.openSession()
                DebugLogger.log("GetStorageId...")
                val sid = t.getStorageId()
                DebugLogger.log("Storage = 0x${sid.toString(16)}")
                transport = t; storageId = sid
                DebugLogger.log("✓ PTP READY!")
                DebugLogger.setStatus("✓ PTP Ready (0x${sid.toString(16)})")
            } catch (e: Exception) {
                DebugLogger.log("❌ PTP FAIL: ${e.message}")
                DebugLogger.setStatus("❌ ${e.message}")
            }
        }
    }
}
