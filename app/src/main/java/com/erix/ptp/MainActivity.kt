package com.erix.ptp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.erix.ptp.databinding.ActivityMainBinding
import com.erix.ptp.ptp.PtpWatcher
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Mulai Watcher
        PtpWatcher.start(this)

        // Tampilkan log
        lifecycleScope.launch {
            DebugLogger.logs.collect { logText ->
                b.tvLog.text = logText
            }
        }
        lifecycleScope.launch {
            DebugLogger.status.collect { status ->
                b.tvStatus.text = status
            }
        }

        // Tombol Copy
        b.btnCopy.setOnClickListener {
            val text = b.tvLog.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "Log kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ERIX Log", text))
            Toast.makeText(this, "✓ Log dicopy!", Toast.LENGTH_SHORT).show()
        }

        // Tombol Clear
        b.btnClear.setOnClickListener {
            DebugLogger.clear()
            Toast.makeText(this, "Log dibersihkan", Toast.LENGTH_SHORT).show()
        }

        // Sembunyikan tombol-tombol lama (jika ada di layout) agar tidak crash
        try {
            b.btnBrowser.visibility = android.view.View.GONE
            b.btnTest.visibility = android.view.View.GONE
            b.btnPush.visibility = android.view.View.GONE
            b.btnPull.visibility = android.view.View.GONE
            b.btnExit.visibility = android.view.View.GONE
        } catch (_: Exception) {}
    }
}
