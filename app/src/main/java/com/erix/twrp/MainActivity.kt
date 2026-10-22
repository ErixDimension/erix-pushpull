package com.erix.twrp
import android.Manifest
import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.erix.twrp.adb.*
import com.erix.twrp.databinding.ActivityMainBinding
import com.erix.twrp.ui.BrowserActivity
import com.erix.twrp.transfer.AdbRuntime
import kotlinx.coroutines.*
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding; private val scope = CoroutineScope(Dispatchers.Main)
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        requestBasePermissions(); AdbDeviceWatcher.start(this)
        scope.launch { AdbDeviceWatcher.device.collect { dev -> if (dev != null) connectToDevice(dev) else { AdbRuntime.active = null; b.tvDevice.text = "Tidak ada device" } } }
        b.btnPush.setOnClickListener {
            if (AdbRuntime.active == null) { b.tvDevice.text = "Tunggu device terhubung..."; return@setOnClickListener }
            startBrowser(true, "ITEM", "SOURCE — DEVICE A", "push_source")
        }
        b.btnPull.setOnClickListener {
            if (AdbRuntime.active == null) { b.tvDevice.text = "Tunggu device terhubung..."; return@setOnClickListener }
            startBrowser(false, "ITEM", "SOURCE — DEVICE B", "pull_source")
        }
        b.btnRefresh.setOnClickListener { b.tvDevice.text = "Refreshing..."; AdbDeviceWatcher.device.value?.let { connectToDevice(it) } }
        b.btnExit.setOnClickListener { finish() }
    }
    private fun connectToDevice(usbDev: android.hardware.usb.UsbDevice) {
        scope.launch {
            try {
                b.tvDevice.text = "Connecting to ${usbDev.deviceName}..."
                val mgr = getSystemService(USB_SERVICE) as UsbManager; val conn = mgr.openDevice(usbDev) ?: throw IllegalStateException("openDevice null")
                val transport = AdbUsbTransport(mgr, usbDev, conn, AdbCrypto())
                withContext(Dispatchers.IO) { transport.connect() }
                AdbRuntime.active = transport to (usbDev.deviceName ?: "-")
                b.tvDevice.text = "✓ Terhubung: ${usbDev.deviceName}"
            } catch (e: Exception) { b.tvDevice.text = "Gagal connect: ${e.message}" }
        }
    }
    private fun startBrowser(isLocal: Boolean, pickMode: String, title: String, action: String) {
        startActivity(Intent(this, BrowserActivity::class.java).apply {
            putExtra(BrowserActivity.EXTRA_SIDE, if (isLocal) "LOCAL" else "REMOTE"); putExtra(BrowserActivity.EXTRA_PICK_MODE, pickMode); putExtra(BrowserActivity.EXTRA_TITLE, title); putExtra(BrowserActivity.EXTRA_ACTION, action)
            putExtra(BrowserActivity.EXTRA_START_PATH, if (isLocal) "/storage/emulated/0" else "/sdcard/")
        })
    }
    private fun requestBasePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) { perms.add(Manifest.permission.READ_EXTERNAL_STORAGE); perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:$packageName") }) } catch (_: Exception) {}
        }
    }
}
