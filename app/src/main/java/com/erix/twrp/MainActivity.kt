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
import com.erix.twrp.databinding.ActivityMainBinding
import com.erix.twrp.ui.BrowserActivity
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding; private val scope = CoroutineScope(Dispatchers.Main)
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        requestBasePermissions(); AdbDeviceWatcher.start(this)
        
        scope.launch {
            AdbDeviceWatcher.transport.collect { t ->
                if (t != null) b.tvDevice.text = "✓ Terhubung: ${AdbDeviceWatcher.currentUsbDevice?.deviceName ?: "-"}"
                else b.tvDevice.text = "Tidak ada device"
            }
        }
        b.btnPush.setOnClickListener { startActivity(Intent(this, BrowserActivity::class.java).apply { putExtra("side", "LOCAL"); putExtra("start_path", "/storage/emulated/0") }) }
        b.btnPull.setOnClickListener { if (AdbDeviceWatcher.transport.value == null) Toast.makeText(this, "Colok device B dulu", Toast.LENGTH_SHORT).show() else startActivity(Intent(this, BrowserActivity::class.java).apply { putExtra("side", "REMOTE"); putExtra("start_path", "/sdcard/") }) }
        b.btnExit.setOnClickListener { finish() }
    }

    private fun requestBasePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) { try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:$packageName") }) } catch (_: Exception) {} }
    }
}
