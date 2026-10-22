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

        // Tampilkan log realtime
        lifecycleScope.launch {
            DebugLogger.logs.collect { logText ->
                b.tvLog.text = logText
            }
        }

        // Tampilkan status device
        lifecycleScope.launch {
            DebugLogger.status.collect { status ->
                b.tvStatus.text = status
            }
        }

        // Tombol Copy Log ke Clipboard
        b.btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val logText = b.tvLog.text.toString()
            if (logText.isBlank()) {
                Toast.makeText(this, "Log masih kosong", Toast.LENGTH_SHORT).show()
            } else {
                clipboard.setPrimaryClip(ClipData.newPlainText("ERIX Log", logText))
                Toast.makeText(this, "✓ Log dicopy ke clipboard!", Toast.LENGTH_SHORT).show()
            }
        }

        // Tombol Clear
        b.btnClear.setOnClickListener {
            DebugLogger.clear()
            Toast.makeText(this, "Log dibersihkan", Toast.LENGTH_SHORT).show()
        }

        // Tombol Exit
        b.btnExit.setOnClickListener { finish() }
    }
}
