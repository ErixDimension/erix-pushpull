package com.erix.twrp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.erix.twrp.adb.BugjaegerCompatClient
import com.erix.twrp.databinding.ActivityMainBinding
import com.erix.twrp.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        lifecycleScope.launch {
            DebugLogger.logs.collect { b.tvLog.text = it }
        }

        lifecycleScope.launch {
            DebugLogger.status.collect { b.tvStatus.text = it }
        }

        b.btnBugjaeger.setOnClickListener {
            val service = b.etService.text.toString().trim()
            val token = b.etToken.text.toString()

            if (service.isBlank() || token.isBlank()) {
                Toast.makeText(
                    this,
                    "Isi service dan token terlebih dahulu",
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }

            b.btnBugjaeger.isEnabled = false
            DebugLogger.setStatus("Menghubungkan ke 127.0.0.1:5038...")
            DebugLogger.log("[BUGJAEGER] Memulai diagnostik")
            DebugLogger.log("[BUGJAEGER] Service: $service")
            DebugLogger.log("[BUGJAEGER] Token disembunyikan")

            lifecycleScope.launch {
                try {
                    val response = withContext(Dispatchers.IO) {
                        BugjaegerCompatClient().query(service, token)
                    }

                    DebugLogger.log("[BUGJAEGER] Respons diterima (${response.length} karakter)")
                    DebugLogger.log("[BUGJAEGER] Respons: ${response.take(1200)}")
                    DebugLogger.setStatus("Respons berhasil dibaca")
                } catch (e: Exception) {
                    val message = e.message ?: e.javaClass.simpleName
                    DebugLogger.log("[BUGJAEGER] Gagal: $message")
                    DebugLogger.setStatus("Gagal: $message")
                } finally {
                    b.btnBugjaeger.isEnabled = true
                }
            }
        }

        b.btnCopy.setOnClickListener {
            val text = b.tvLog.text.toString()
            if (text.isBlank()) {
                Toast.makeText(this, "Log kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ERIX Log", text))
            Toast.makeText(this, "Log disalin", Toast.LENGTH_SHORT).show()
        }

        b.btnClear.setOnClickListener {
            DebugLogger.clear()
            Toast.makeText(this, "Log dibersihkan", Toast.LENGTH_SHORT).show()
        }

        DebugLogger.log("ERIX v49 siap")
        DebugLogger.log("Adapter protokol Bugjaeger ditambahkan")
        DebugLogger.log("Endpoint diagnostik: 127.0.0.1:5038")
        DebugLogger.log("USB bridge belum digabung dengan jalur TCP")
    }
}
