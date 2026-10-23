package com.erix.ptp

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.erix.ptp.adb.AdbDeviceWatcher
import com.erix.ptp.databinding.ActivityMainBinding
import com.erix.ptp.ui.BrowserActivity
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        AdbDeviceWatcher.start(this)

        lifecycleScope.launch { DebugLogger.logs.collect { b.tvLog.text = it } }
        lifecycleScope.launch {
            DebugLogger.status.collect {
                b.tvStatus.text = it
                b.btnOpenBrowser.isEnabled = AdbDeviceWatcher.transport?.isConnected == true
            }
        }

        b.btnClear.setOnClickListener { DebugLogger.clear() }
        b.btnCopy.setOnClickListener {
            val text = b.tvLog.text.toString()
            if (text.isNotBlank()) {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Log", text))
                Toast.makeText(this, "Log dicopy", Toast.LENGTH_SHORT).show()
            }
        }
        b.btnOpenBrowser.setOnClickListener {
            val t = AdbDeviceWatcher.transport
            if (t?.isConnected == true) {
                startActivity(Intent(this, BrowserActivity::class.java))
            } else {
                Toast.makeText(this, "ADB belum terhubung — colok device dulu", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
