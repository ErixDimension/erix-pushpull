package com.erix.ptp

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

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        grants.forEach { (k, v) -> DebugLogger.log("Izin $k: $v") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        requestStoragePermission()
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
            val t = PtpWatcher.transport ?: run { Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    DebugLogger.log("=== LIST ROOT ===")
                    val entries = t.listRoot(sid)
                    DebugLogger.log("Total: ${entries.size} item")
                    entries.take(20).forEach {
                        DebugLogger.log("  ${if (it.isDir) "[D]" else "[F]"} ${it.name} (${it.size}b)")
                    }
                    DebugLogger.log("✓ List OK")
                } catch (e: Exception) {
                    DebugLogger.log("❌ List: ${e.message}")
                }
            }
        }

        b.btnPush.setOnClickListener {
            val t = PtpWatcher.transport ?: run { Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    val f = File(dir, "erix_push_test.txt")
                    f.writeText("ERIX v55 ${System.currentTimeMillis()}\n")
                    DebugLogger.log("PUSH ${f.length()}b")
                    val ok = t.pushToFolder(sid, f, "erix_push_test.txt", -1) { s, total ->
                        DebugLogger.log("  $s/$total")
                    }
                    DebugLogger.log(if (ok) "✓ PUSH OK" else "✗ PUSH gagal")
                } catch (e: Exception) {
                    DebugLogger.log("❌ PUSH: ${e.message}")
                }
            }
        }

        b.btnPull.setOnClickListener {
            val t = PtpWatcher.transport ?: run { Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val list = t.listRoot(sid)
                    val target = list.firstOrNull { !it.isDir } ?: run { DebugLogger.log("Tidak ada file"); return@launch }
                    DebugLogger.log("PULL ${target.name}...")
                    val dir = getExternalFilesDir(null) ?: filesDir
                    val dst = File(dir, "erix_pull_${target.name}")
                    val ok = t.pullByHandle(target.handle, dst) { r, total -> DebugLogger.log("  $r/$total") }
                    DebugLogger.log(if (ok) "✓ PULL OK" else "✗ PULL gagal")
                } catch (e: Exception) {
                    DebugLogger.log("❌ PULL: ${e.message}")
                }
            }
        }

        b.btnCopy.setOnClickListener {
            val txt = b.tvLog.text.toString()
            if (txt.isNotBlank()) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("ERIX", txt))
                Toast.makeText(this, "✓ Dicopy", Toast.LENGTH_SHORT).show()
            }
        }

        b.btnClear.setOnClickListener { DebugLogger.clear() }
    }

    private fun requestStoragePermission() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())

        // Android 11+ : MANAGE_EXTERNAL_STORAGE (All Files Access)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                DebugLogger.log("MANAGE_EXTERNAL_STORAGE belum aktif, request...")
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    })
                } catch (_: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (_: Exception) {}
                }
            } else {
                DebugLogger.log("✓ MANAGE_EXTERNAL_STORAGE sudah aktif")
            }
        }
    }
}
