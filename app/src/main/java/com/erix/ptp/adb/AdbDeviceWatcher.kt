// ═════════════════════════════════════════════════════════════════
// ⚠️  DO NOT DELETE / DO NOT OVERWRITE — WATCHER USB ADB
// Sudah terhubung dengan AdbUsbTransport (bukan PTP lagi).
// Kalau mau eksperimen, buat AdbDeviceWatcher_v2.kt.
// ═════════════════════════════════════════════════════════════════


package com.erix.ptp.adb

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.erix.ptp.ptp.AdbUsbTransport
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object AdbDeviceWatcher {
    private const val ACTION_USB_PERM = "com.erix.ptp.USB_PERMISSION"

    @Volatile var transport: AdbUsbTransport? = null; private set

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
                            DebugLogger.log("Izin USB diberikan. Delay 1s...")
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

        // Auto-reconnect: cek device yang sudah ter-colok
        scanExistingDevices()
    }

    private fun getDevice(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    private fun requestPermission(device: UsbDevice) {
        val manager = usbManager ?: return
        if (manager.hasPermission(device)) {
            connect(device)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(
            appContext, 0,
            Intent(ACTION_USB_PERM).setPackage(appContext?.packageName), flags
        )
        manager.requestPermission(device, pi)
    }

    private fun connect(device: UsbDevice) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!AdbUsbTransport.hasAdb(device)) {
                    DebugLogger.log("Device tanpa interface ADB, dilewati")
                    return@launch
                }
                DebugLogger.log("Membuka koneksi ADB...")
                DebugLogger.setStatus("Mencari ADB device...")
                val ctx = appContext ?: throw IllegalStateException("Context null")
                val t = AdbUsbTransport(ctx, device)
                transport = t
                t.connect()
                DebugLogger.log("✓ ADB TERHUBUNG (${device.deviceName})")
                DebugLogger.setStatus("✓ ADB Terhubung")
            } catch (t: Throwable) {
                DebugLogger.log("❌ ADB Gagal: ${t.message}")
                DebugLogger.setStatus("❌ ${t.message}")
                transport?.close()
                transport = null
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // [Fix 2026-10-24] Auto-reconnect: scan device yang sudah ter-colok
    // saat app start (bukan hanya tunggu broadcast USB_DEVICE_ATTACHED).
    // Berguna saat app di-kill lalu buka lagi, USB masih ter-colok.
    // ──────────────────────────────────────────────────────────
    private fun scanExistingDevices() {
        val manager = usbManager ?: return
        val ctx = appContext ?: return
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            for (dev in manager.deviceList.values) {
                if (!AdbUsbTransport.hasAdb(dev)) continue
                DebugLogger.log("USB sudah ter-colok saat start: ${dev.deviceName}")
                if (manager.hasPermission(dev)) {
                    connect(dev)
                } else {
                    requestPermission(dev)
                }
                break
            }
        }
    }
}
