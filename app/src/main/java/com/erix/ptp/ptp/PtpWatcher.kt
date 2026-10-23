package com.erix.ptp.ptp

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.widget.Toast
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.*

object PtpWatcher {
    private const val ACTION_USB_PERM = "com.erix.ptp.USB_PERMISSION"
    @Volatile var transport: PtpUsbTransport? = null; private set
    @Volatile var storageId: Int = -1; private set
    
    @Volatile var adbNative: AdbNativeTransport? = null; private set
    
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
                        requestPerm(d)
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        DebugLogger.log("USB Dicabut")
                        try { transport?.closeSession(); transport?.close() } catch (_: Exception) {}
                        try { adbNative?.close() } catch (_: Exception) {}
                        transport = null; storageId = -1; adbNative = null
                        DebugLogger.setStatus("Menunggu device...")
                    }
                    ACTION_USB_PERM -> {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            // Delay 1.5 detik agar popup ke-2 ("Pilih aplikasi") muncul dulu
                            DebugLogger.log("Izin diberikan. Menunggu popup pemilihan app...")
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                getDevice(intent)?.let { connect(it) }
                            }, 1500)
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
            DebugLogger.log("=== v77 NATIVE ADB START ===")
            try {
                val conn = usbManager!!.openDevice(device) ?: throw IllegalStateException("openDevice null")
                
                // Cari interface ADB
                var adbIntf: UsbInterface? = null
                for (i in 0 until device.interfaceCount) {
                    val it = device.getInterface(i)
                    if (it.interfaceClass == 0xFF && it.interfaceSubclass == 0x42 && it.interfaceProtocol == 0x01) {
                        adbIntf = it; break
                    }
                }
                
                if (adbIntf == null) {
                    DebugLogger.log("⚠ Bukan ADB Interface. Coba PTP...")
                    val t = PtpUsbTransport(device, conn)
                    t.openSession()
                    val sid = t.getStorageId()
                    transport = t; storageId = sid
                    DebugLogger.log("✓ PTP READY (fallback)!")
                    return@launch
                }

                var fi: UsbEndpoint? = null; var fo: UsbEndpoint? = null
                for (i in 0 until adbIntf.endpointCount) {
                    val ep = adbIntf.getEndpoint(i)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (ep.direction == UsbConstants.USB_DIR_IN) fi = ep
                        if (ep.direction == UsbConstants.USB_DIR_OUT) fo = ep
                    }
                }

                if (!conn.claimInterface(adbIntf, true)) throw IllegalStateException("Claim ADB Interface gagal")

                DebugLogger.log("Memulai Native Transport...")
                val nativeT = AdbNativeTransport(conn, fi!!, fo!!, AdbCrypto())
                nativeT.connect()
                
                adbNative = nativeT
                DebugLogger.log("✓ ADB Native SIAP DIGUNAKAN!")
                DebugLogger.setStatus("✓ ADB Terhubung (Native)")
                
            } catch (e: Exception) {
                DebugLogger.log("❌ ADB Gagal: ${e.message}")
                DebugLogger.setStatus("❌ Gagal: ${e.message}")
            }
        }
    }
}
