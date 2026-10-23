package com.erix.ptp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.erix.ptp.databinding.ActivityMainBinding
import com.erix.ptp.ptp.PtpWatcher
import com.erix.ptp.ui.BrowserActivity
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

        b.btnBrowser.setOnClickListener {
            if (PtpWatcher.transport == null) {
                Toast.makeText(this, "Colok kabel dulu", Toast.LENGTH_SHORT).show()
            } else {
                startActivity(Intent(this, BrowserActivity::class.java))
            }
        }

        b.btnTest.setOnClickListener {
            val t = PtpWatcher.transport
            if (t == null) { Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    DebugLogger.log("=== LIST ROOT ===")
                    val entries = t.listRoot(sid)
                    DebugLogger.log("Total: ${entries.size} file")
                    entries.take(20).forEach {
                        DebugLogger.log("  ${if (it.isDir) "[D]" else "[F]"} ${it.name} (${it.size}b)")
                    }
                    DebugLogger.log("✓ List OK")
                } catch (e: Exception) {
                    DebugLogger.log("❌ List: ${e.message}")
                    try { t.recover(); DebugLogger.log("↻ Recovered") } catch (_: Exception) {}
                }
            }
        }

        b.btnPush.setOnClickListener {
            val t = PtpWatcher.transport
            if (t == null) { Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    dir.mkdirs()
                    val f = File(dir, "erix_push_test.txt")
                    f.writeText("ERIX v53 PUSH ${System.currentTimeMillis()}\n")
                    DebugLogger.log("PUSH ${f.length()}b: ${f.absolutePath}")
                    val ok = t.pushToFolder(sid, f, "erix_push_test.txt", -1) { s, total ->
                        DebugLogger.log("  $s/$total")
                    }
                    DebugLogger.log(if (ok) "✓ PUSH OK" else "✗ PUSH gagal")
                } catch (e: Exception) {
                    DebugLogger.log("❌ PUSH: ${e.message}")
                    try { t.recover() } catch (_: Exception) {}
                }
            }
        }

        b.btnPull.setOnClickListener {
            val t = PtpWatcher.transport
            if (t == null) { Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val list = t.listRoot(sid)
                    val target = list.firstOrNull { !it.isDir }
                    if (target == null) { DebugLogger.log("Tidak ada file"); return@launch }
                    DebugLogger.log("PULL ${target.name} (${target.size}b)...")
                    val dir = getExternalFilesDir(null) ?: filesDir
                    dir.mkdirs()
                    val dst = File(dir, "erix_pull_${target.name}")
                    val ok = t.pullByHandle(target.handle, dst) { r, total ->
                        DebugLogger.log("  $r/$total")
                    }
                    DebugLogger.log(if (ok) "✓ PULL OK: ${dst.absolutePath}" else "✗ PULL gagal")
                } catch (e: Exception) {
                    DebugLogger.log("❌ PULL: ${e.message}")
                    try { t.recover() } catch (_: Exception) {}
                }
            }
        }

        b.btnCopy.setOnClickListener {
            val txt = b.tvLog.text.toString()
            if (txt.isBlank()) { Toast.makeText(this, "Kosong", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("ERIX", txt))
            Toast.makeText(this, "✓ Dicopy", Toast.LENGTH_SHORT).show()
        }

        b.btnClear.setOnClickListener { DebugLogger.clear() }
    }
}
