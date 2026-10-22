package com.erix.twrp
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.erix.twrp.adb.AdbDeviceWatcher
import com.erix.twrp.adb.AdbManager
import com.erix.twrp.databinding.ActivityMainBinding
import com.erix.twrp.ui.BrowserActivity
import com.erix.twrp.transfer.AdbRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val scope = CoroutineScope(Dispatchers.Main)
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        requestBasePermissions()
        AdbDeviceWatcher.start(this)

        scope.launch {
            AdbDeviceWatcher.transport.collect { dadb ->
                if (dadb != null) {
                    val serial = AdbDeviceWatcher.currentUsbDevice?.deviceName ?: "-"
                    AdbRuntime.active = dadb to serial
                    b.tvDevice.text = "✓ Terhubung: ${AdbDeviceWatcher.currentUsbDevice?.deviceName ?: "-"}"
                } else {
                    AdbRuntime.active = null
                    b.tvDevice.text = "Tidak ada device"
                }
            }
        }

        b.btnPush.setOnClickListener {
            startBrowser(true, "ITEM", "SOURCE — DEVICE A", "push_source")
        }

        b.btnPull.setOnClickListener {
            if (AdbRuntime.active == null) {
                Toast.makeText(this, "Device B belum terhubung. Colok kabel USB dulu.", Toast.LENGTH_LONG).show()
            } else {
                startBrowser(false, "ITEM", "SOURCE — DEVICE B", "pull_source")
            }
        }

        b.btnRefresh.setOnClickListener {
            b.tvDevice.text = "Refreshing..."
            AdbDeviceWatcher.dadb.value?.let {
                b.tvDevice.text = "✓ Terhubung: ${AdbDeviceWatcher.currentUsbDevice?.deviceName ?: "-"}"
            }
        }

        b.btnExit.setOnClickListener { finish() }
    }

    private fun startBrowser(isLocal: Boolean, pickMode: String, title: String, action: String) {
        startActivity(Intent(this, BrowserActivity::class.java).apply {
            putExtra(BrowserActivity.EXTRA_SIDE, if (isLocal) "LOCAL" else "REMOTE")
            putExtra(BrowserActivity.EXTRA_PICK_MODE, pickMode)
            putExtra(BrowserActivity.EXTRA_TITLE, title)
            putExtra(BrowserActivity.EXTRA_ACTION, action)
            putExtra(BrowserActivity.EXTRA_START_PATH, if (isLocal) "/storage/emulated/0" else "/sdcard/")
        })
    }

    private fun requestBasePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {}
        }
    }
}
