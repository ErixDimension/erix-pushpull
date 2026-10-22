package com.erix.twrp
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.erix.twrp.util.DebugLogger
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding; private val scope = CoroutineScope(Dispatchers.Main)
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); b = ActivityMainBinding.inflate(layoutInflater); setContentView(b.root)
        requestBasePermissions(); DebugLogger.log("App dibuka."); AdbDeviceWatcher.start(this)
        
        scope.launch {
            DebugLogger.logs.collect { logText ->
                b.tvDebugLog.text = logText
                b.scrollLog.post { b.scrollLog.fullScroll(android.view.View.FOCUS_DOWN) }
            }
        }
        scope.launch {
            AdbDeviceWatcher.transport.collect { t ->
                if (t != null) b.tvDevice.text = "✓ Terhubung: ${AdbDeviceWatcher.currentUsbDevice?.deviceName ?: "-"}"
                else b.tvDevice.text = "Tidak ada device"
            }
        }
        
        b.btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ERIX Log", b.tvDebugLog.text))
            Toast.makeText(this, "Log berhasil dicopy!", Toast.LENGTH_SHORT).show()
        }
        b.btnClear.setOnClickListener { DebugLogger.clear() }
        b.btnExit.setOnClickListener { finish() }
    }
    private fun requestBasePermissions() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) { try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = Uri.parse("package:$packageName") }) } catch (_: Exception) {} }
    }
}
