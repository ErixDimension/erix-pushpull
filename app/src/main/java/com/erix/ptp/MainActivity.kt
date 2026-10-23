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

        PtpWatcher.start(this)

        lifecycleScope.launch {
            DebugLogger.logs.collect { b.tvLog.text = it }
        }
        lifecycleScope.launch {
            DebugLogger.status.collect { b.tvStatus.text = it }
        }

        b.btnCopy.setOnClickListener {
            val text = b.tvLog.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "Log kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("ERIX Log", text))
            Toast.makeText(this, "✓ Log dicopy", Toast.LENGTH_SHORT).show()
        }

        b.btnClear.setOnClickListener {
            DebugLogger.clear()
        }
    }
}
