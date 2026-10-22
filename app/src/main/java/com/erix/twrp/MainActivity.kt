package com.erix.twrp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.erix.twrp.adb.AdbDeviceWatcher
import com.erix.twrp.databinding.ActivityMainBinding
import com.erix.twrp.util.DebugLogger
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        AdbDeviceWatcher.start(this)

        lifecycleScope.launch {
            DebugLogger.logs.collect { b.tvLog.text = it }
        }
        lifecycleScope.launch {
            DebugLogger.status.collect { b.tvStatus.text = it }
        }

        b.btnCopy.setOnClickListener {
            val text = b.tvLog.text.toString()
            if (text.isBlank()) { Toast.makeText(this, "Log kosong", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ERIX Log", text))
            Toast.makeText(this, "✓ Log dicopy!", Toast.LENGTH_SHORT).show()
        }
        b.btnClear.setOnClickListener {
            DebugLogger.clear()
            Toast.makeText(this, "Log dibersihkan", Toast.LENGTH_SHORT).show()
        }
    }
}
