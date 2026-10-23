package com.erix.twrp.adb

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import com.erix.twrp.util.DebugLogger
import kotlinx.coroutines.*

object AdbDeviceWatcher {
    private const val ACTION_USB_PERM = "com.erix.twrp.USB_PERMISSION"
    private var usbManager: UsbManager? = null
    private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null
    private var lastDeviceId = -1
    private var lastAttachTime = 0L
    @Volatile private var activeBridge: AdbUsbBridge? = null
    @Volatile private var connecting = false
    private val connectionLock = Any()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(context: Context) {
        appContext = context.applicationContext
        usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (receiver != null) return

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val d = getDevice(intent) ?: return
                        // Debounce: abaikan jika device sama dalam 1 detik (cegah spam)
                        val now = System.currentTimeMillis()
                        if (d.deviceId == lastDeviceId && now - lastAttachTime < 1000) return
                        lastDeviceId = d.deviceId
                        lastAttachTime = now
                        DebugLogger.log("USB Dicolok: ${d.deviceName}")
                        if (isAdbDevice(d)) requestPermission(d) else DebugLogger.log("Bukan ADB")
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        DebugLogger.log("USB Dicabut")
                        activeBridge?.close()
                        activeBridge = null
                        DebugLogger.setStatus("Menunggu device...")
                    }
                    ACTION_USB_PERM -> {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                            getDevice(intent)?.let { connect(it) }
                        else DebugLogger.log("Izin USB DITOLAK")
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

        DebugLogger.log("[USB] Watcher aktif; receiver terdaftar")
        scanConnectedDevices()
    }

    private fun scanConnectedDevices() {
        val manager = usbManager ?: return
        val devices = manager.deviceList.values.toList()

        DebugLogger.log("[USB] Jumlah perangkat USB: ${devices.size}")

        if (devices.isEmpty()) {
            DebugLogger.setStatus("Menunggu perangkat USB OTG...")
            return
        }

        for (device in devices) {
            DebugLogger.log(
                "[USB] ${device.deviceName} VID=${device.vendorId} PID=${device.productId}"
            )

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                DebugLogger.log(
                    "[USB] Interface $i: class=${intf.interfaceClass}, " +
                    "subclass=${intf.interfaceSubclass}, protocol=${intf.interfaceProtocol}"
                )
            }

            if (isAdbDevice(device)) {
                DebugLogger.log("[USB] Interface ADB ditemukan")
                requestPermission(device)
            } else {
                DebugLogger.log("[USB] Interface ADB tidak ditemukan pada perangkat ini")
            }
        }
    }

    private fun getDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

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
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        manager.requestPermission(device,
            PendingIntent.getBroadcast(appContext, 0,
                Intent(ACTION_USB_PERM).setPackage(appContext?.packageName), flags))
    }

    private fun connect(device: UsbDevice) {
        ioScope.launch {
            var conn: UsbDeviceConnection? = null
            var bridge: AdbUsbBridge? = null

            try {
                val mayConnect = synchronized(connectionLock) {
                    if (activeBridge != null || connecting) {
                        false
                    } else {
                        connecting = true
                        true
                    }
                }

                if (!mayConnect) {
                    DebugLogger.log("[USB] Koneksi sudah aktif/diproses; abaikan duplikat")
                    return@launch
                }

                val manager = usbManager
                    ?: throw IllegalStateException("UsbManager tidak tersedia")

                conn = manager.openDevice(device)
                    ?: throw IllegalStateException("openDevice null; periksa izin USB/kabel")

                var adbIntf: UsbInterface? = null
                for (i in 0 until device.interfaceCount) {
                    val candidate = device.getInterface(i)
                    if (candidate.interfaceClass == 0xFF &&
                        candidate.interfaceSubclass == 0x42 &&
                        candidate.interfaceProtocol == 0x01) {
                        adbIntf = candidate
                        break
                    }
                }

                val selectedIntf = adbIntf
                    ?: throw IllegalStateException("Interface ADB tidak ditemukan")

                var input: UsbEndpoint? = null
                var output: UsbEndpoint? = null

                for (i in 0 until selectedIntf.endpointCount) {
                    val endpoint = selectedIntf.getEndpoint(i)
                    if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        if (endpoint.direction == UsbConstants.USB_DIR_IN) input = endpoint
                        if (endpoint.direction == UsbConstants.USB_DIR_OUT) output = endpoint
                    }
                }

                val inEndpoint = input
                    ?: throw IllegalStateException("Endpoint IN tidak ditemukan")
                val outEndpoint = output
                    ?: throw IllegalStateException("Endpoint OUT tidak ditemukan")

                bridge = AdbUsbBridge(
                    conn, selectedIntf, inEndpoint, outEndpoint, AdbCrypto()
                )

                activeBridge = bridge
                bridge.connect()
                conn = null
                bridge = null

                DebugLogger.log("✓ Handshake ADB berhasil")
                DebugLogger.setStatus("✓ Terhubung!")
            } catch (e: Exception) {
                synchronized(connectionLock) {
                    if (activeBridge === bridge) activeBridge = null
                }
                try { bridge?.close() } catch (_: Exception) {}
                try { conn?.close() } catch (_: Exception) {}
                DebugLogger.log("❌ Koneksi USB gagal: ${e.message}")
                DebugLogger.setStatus("❌ ${e.message}")
            } finally {
                synchronized(connectionLock) {
                    connecting = false
                }
            }
        }
    }
}
