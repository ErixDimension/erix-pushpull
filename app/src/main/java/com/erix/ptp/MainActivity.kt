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
            val t = PtpWatcher.transport
            if (t == null) { Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    DebugLogger.log("=== LIST ROOT ===")
                    val entries = t.listRoot(sid)
                    DebugLogger.log("Total: ${entries.size} file")
                    entries.take(20).forEach { DebugLogger.log("  [${it.handle}] ${it.name} (${it.size}b)") }
                    DebugLogger.log("✓ List OK")
                } catch (e: Exception) {
                    DebugLogger.log("❌ List: ${e.message}")
                    try { t.recover(); DebugLogger.log("↻ Session dipulihkan") } catch (_: Exception) {}
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
                    f.writeText("ERIX v51 PUSH ${System.currentTimeMillis()}\n")
                    DebugLogger.log("PUSH ${f.length()} bytes dari ${f.absolutePath}")
                    t.push(sid, f, "erix_push_test.txt") { s -> DebugLogger.log("  $s/${f.length()}") }
                    DebugLogger.log("✓ PUSH OK")
                } catch (e: Exception) {
                    DebugLogger.log("❌ PUSH: ${e.message}")
                    try { t.recover(); DebugLogger.log("↻ Session dipulihkan") } catch (_: Exception) {}
                }
            }
        }

        b.btnPull.setOnClickListener {
            val t = PtpWatcher.transport
            if (t == null) { Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val targetName = "erix_push_test.txt"  // File yang kita push sebelumnya
                    DebugLogger.log("PULL target: $targetName")
                    DebugLogger.log("(kalau belum push, klik PUSH dulu)")

                    val list = t.listRoot(sid)
                    DebugLogger.log("Total item di root: ${list.size}")
                    list.take(10).forEach { e ->
                        DebugLogger.log("  ${if (e.isDir) "📁" else "📄"} ${e.name} (${e.size}b)")
                    }

                    val target = list.firstOrNull { it.name == targetName }
                    if (target == null) {
                        DebugLogger.log("❌ File '$targetName' tidak ditemukan")
                        DebugLogger.log("   Klik PUSH dulu untuk upload file test")
                        return@launch
                    }
                    if (target.isDir) {
                        DebugLogger.log("❌ '$targetName' adalah folder")
                        return@launch
                    }

                    DebugLogger.log("PULL ${target.name} (${target.size}b)...")
                    val dir = getExternalFilesDir(null) ?: filesDir
                    dir.mkdirs()
                    val dst = File(dir, "erix_pull_${target.name}")
                    t.pull(sid, target.name, dst) { r -> DebugLogger.log("  $r/${target.size}") }
                    DebugLogger.log("✓ PULL OK: ${dst.absolutePath}")
                    DebugLogger.log("   Isi file: ${dst.readText().trim()}")

                    // Bandingkan dengan asli
                    val orig = File(dir, "erix_push_test.txt")
                    if (orig.exists()) {
                        val original = orig.readText()
                        val pulled = dst.readText()
                        if (original == pulled) DebugLogger.log("✓✓✓ ROUND-TRIP IDENTIK! PTP SUKSES PENUH! ✓✓✓")
                        else DebugLogger.log("⚠ File beda: original=${original.length}b, pulled=${pulled.length}b")
                    }
                } catch (e: Exception) {
                    DebugLogger.log("❌ PULL: ${e.message}")
                    try { t.recover(); DebugLogger.log("↻ Session dipulihkan") } catch (_: Exception) {}
                }
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
