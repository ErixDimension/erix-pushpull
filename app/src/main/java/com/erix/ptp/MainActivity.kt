package com.erix.ptp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.erix.ptp.databinding.ActivityMainBinding
import com.erix.ptp.ptp.PtpWatcher
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        PtpWatcher.start(this)

        lifecycleScope.launch { DebugLogger.logs.collect { b.tvLog.text = it } }
        lifecycleScope.launch { DebugLogger.status.collect { b.tvStatus.text = it } }

        b.btnTest.setOnClickListener {
            val t = PtpWatcher.transport ?: run { Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    DebugLogger.log("=== LIST ROOT ===")
                    val entries = t.listRoot(sid)
                    DebugLogger.log("Total: ${entries.size} file")
                    entries.take(20).forEach { DebugLogger.log("  [${it.handle}] ${it.name} (${it.size}b)") }
                    DebugLogger.log("✓ List OK")
                } catch (e: Exception) { DebugLogger.log("❌ List: ${e.message}") }
            }
        }

        b.btnPush.setOnClickListener {
            val t = PtpWatcher.transport ?: run { Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val f = File(Environment.getExternalStorageDirectory(), "erix_push_test.txt")
                    f.writeText("ERIX v50 PUSH ${System.currentTimeMillis()}\n")
                    DebugLogger.log("PUSH ${f.length()} bytes...")
                    t.push(sid, f, "erix_push_test.txt") { s -> DebugLogger.log("  $s/${f.length()}") }
                    DebugLogger.log("✓ PUSH OK")
                } catch (e: Exception) { DebugLogger.log("❌ PUSH: ${e.message}") }
            }
        }

        b.btnPull.setOnClickListener {
            val t = PtpWatcher.transport ?: run { Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val list = t.listRoot(sid)
                    if (list.isEmpty()) { DebugLogger.log("Tidak ada file"); return@launch }
                    val f = list.first()
                    DebugLogger.log("PULL ${f.name} (${f.size}b)...")
                    val dst = File(Environment.getExternalStorageDirectory(), "erix_${f.name}")
                    t.pull(sid, f.name, dst) { r -> DebugLogger.log("  $r/${f.size}") }
                    DebugLogger.log("✓ PULL OK: ${dst.absolutePath}")
                } catch (e: Exception) { DebugLogger.log("❌ PULL: ${e.message}") }
            }
        }

        b.btnCopy.setOnClickListener {
            val txt = b.tvLog.text.toString()
            if (txt.isBlank()) { Toast.makeText(this, "Kosong", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("ERIX PTP", txt))
            Toast.makeText(this, "✓ Dicopy", Toast.LENGTH_SHORT).show()
        }

        b.btnClear.setOnClickListener { DebugLogger.clear() }
    }
}
