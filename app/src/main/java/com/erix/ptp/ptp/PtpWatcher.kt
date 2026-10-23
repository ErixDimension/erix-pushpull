package com.erix.ptp.ptp

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.*

object PtpWatcher {
    private const val ACTION_USB_PERM = "com.erix.ptp.USB_PERMISSION"
    @Volatile var adbNative: AdbNativeTransport? = null; private set
    private var usbManager: UsbManager? = null
    private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var lastId = -1
    private var lastTime = 0L
    private var connecting = false

    fun start(context: Context) {
        appContext = context.applicationContext
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (receiver != null) return
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                try {
                    when (intent?.action) {
                        UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                            val d = getDevice(intent) ?: return
                            val now = System.currentTimeMillis()
                            if (d.deviceId == lastId && now - lastTime < 1500) return
                            lastId = d.deviceId; lastTime = now
                            DebugLogger.log("USB Dicolok: ${d.deviceName}")
                            requestPerm(d)
                        }
                        UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                            try { adbNative?.close() } catch (_: Throwable) {}
                            adbNative = null
                            connecting = false
                            DebugLogger.log("USB Dicabut")
                            DebugLogger.setStatus("Menunggu device...")
                        }
                        ACTION_USB_PERM -> {
                            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                DebugLogger.log("Izin diberikan. Delay 1.5s...")
                                Handler(Looper.getMainLooper()).postDelayed({
                                    try { getDevice(intent)?.let { connectSafe(it) } } catch (_: Throwable) {}
                                }, 1500)
                            } else {
                                DebugLogger.log("Izin USB ditolak")
                            }
                        }
                    }
                } catch (t: Throwable) {
                    DebugLogger.log("❌ Broadcast crash: ${t.message}")
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

    private fun requestPerm(device: UsbDevice) {
        val m = usbManager ?: return
        if (m.hasPermission(device)) { connectSafe(device); return }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        m.requestPermission(device, PendingIntent.getBroadcast(
            appContext, 0,
            Intent(ACTION_USB_PERM).setPackage(appContext?.packageName), flags))
    }

    private fun connectSafe(device: UsbDevice) {
        if (connecting) { DebugLogger.log("Sudah connecting, skip"); return }
        connecting = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                doConnect(device)
            } catch (t: Throwable) {
                DebugLogger.log("❌ CRASH CATCH: ${t.javaClass.simpleName}: ${t.message}")
                DebugLogger.setStatus("❌ ${t.message}")
            } finally {
                connecting = false
            }
        }
    }

    private fun doConnect(device: UsbDevice) {
        if (!AdbNative.isLoaded()) {
            throw IllegalStateException("erixadb.so gagal dimuat. Cek logcat!")
        }
        val conn = usbManager!!.openDevice(device) ?: throw IllegalStateException("openDevice null")
        var adbIntf: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val it = device.getInterface(i)
            if (it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x01) {
                adbIntf = it; break
            }
        }
        if (adbIntf == null) throw IllegalStateException("Bukan ADB Interface")
        var fi: UsbEndpoint? = null; var fo: UsbEndpoint? = null
        for (i in 0 until adbIntf.endpointCount) {
            val ep = adbIntf.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) fi = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) fo = ep
            }
        }
        if (fi == null || fo == null) throw IllegalStateException("Endpoint tidak lengkap")
                DebugLogger.log("=== v103 USBREQ ===")
        val t = AdbNativeTransport(conn, fi, fo, AdbCrypto())
        t.connect()
        adbNative = t
        DebugLogger.setStatus("✓ ADB Terhubung (libusb)")
    }
}
