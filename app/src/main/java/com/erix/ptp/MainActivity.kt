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
    ) { grants -> grants.forEach { (k, v) -> DebugLogger.log("Izin $k: $v") } }

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
            val t = PtpWatcher.transport ?: run {
                Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    DebugLogger.log("═══════════════════════════════")
                    DebugLogger.log("  MTP PROPERTY DIAGNOSTIC")
                    DebugLogger.log("═══════════════════════════════")

                    // 1. Cek ObjectSize property supported
                    DebugLogger.log("")
                    DebugLogger.log("→ Test GetObjectPropDesc(0xDC04)...")
                    val dataType = t.getObjectPropDescDataType(0xDC04)
                    DebugLogger.log("  Hasil: ${if (dataType == null) "TIDAK SUPPORT" else "DataType=0x${dataType.toString(16)}"}")

                    // 2. List root & tampilkan size compressed vs propSize
                    DebugLogger.log("")
                    DebugLogger.log("→ List root + baca propSize setiap file...")
                    val entries = t.listRoot(sid)
                    DebugLogger.log("  Total: ${entries.size} item")
                    DebugLogger.log("")
                    DebugLogger.log("  Format: name | compressedSize | propSize")
                    DebugLogger.log("  ─────────────────────────────────────")

                    var mismatchCount = 0
                    var sentinelCount = 0
                    entries.take(50).forEach {
                        if (!it.isDir) {
                            val sentinel = if (it.size == 0xFFFFFFFFL) " ⚠SENTINEL" else ""
                            val delta = if (it.propSize != null && it.propSize != it.size) " ≠" else ""
                            if (it.size == 0xFFFFFFFFL) sentinelCount++
                            if (it.propSize != null && it.propSize != it.size) mismatchCount++
                            DebugLogger.log("  ${it.name}")
                            DebugLogger.log("    compressed=${it.size}${sentinel}  prop=${it.propSize ?: "null"}${delta}")
                        }
                    }

                    DebugLogger.log("")
                    DebugLogger.log("═══════════════════════════════")
                    DebugLogger.log("  KESIMPULAN:")
                    DebugLogger.log("═══════════════════════════════")
                    DebugLogger.log("  ObjectSize property: ${if (dataType != null) "✅ SUPPORT (0xDC04)" else "❌ TIDAK SUPPORT"}")
                    DebugLogger.log("  File sentinel 0xFFFFFFFF: $sentinelCount")
                    DebugLogger.log("  File mismatch (compressed ≠ prop): $mismatchCount")
                    DebugLogger.log("")
                    if (dataType != null) {
                        DebugLogger.log("  → MTP workaround SUPPORTED")
                        DebugLogger.log("  → File >4GB BISA didownload tanpa split")
                        DebugLogger.log("  → Lanjut v69 implement full streaming")
                    } else {
                        DebugLogger.log("  → MTP workaround TIDAK SUPPORT")
                        DebugLogger.log("  → File >4GB harus pakai split guide")
                    }
                } catch (e: Exception) {
                    DebugLogger.log("❌ Diagnostic error: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        b.btnPush.setOnClickListener {
            val t = PtpWatcher.transport ?: run {
                Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    val f = File(dir, "erix_push_test.txt")
                    f.writeText("ERIX v64 ${System.currentTimeMillis()}\n")
                    DebugLogger.log("PUSH ${f.length()}b")
                    val ok = t.pushToFolder(
                        storageId = sid,
                        localFile = f,
                        remoteName = "erix_push_test.txt",
                        parentHandle = -1,
                        onProgress = { s, tot -> DebugLogger.log("  $s/$tot") },
                        isCancelled = { false }
                    )
                    DebugLogger.log(if (ok) "✓ PUSH OK" else "✗ PUSH gagal")
                } catch (e: Exception) {
                    DebugLogger.log("❌ PUSH: ${e.message}")
                }
            }
        }

        b.btnPull.setOnClickListener {
            val t = PtpWatcher.transport ?: run {
                Toast.makeText(this, "Belum konek", Toast.LENGTH_SHORT).show(); return@setOnClickListener
            }
            val sid = PtpWatcher.storageId
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val list = t.listRoot(sid)
                    val target = list.firstOrNull { !it.isDir } ?: run {
                        DebugLogger.log("Tidak ada file"); return@launch
                    }
                    DebugLogger.log("PULL ${target.name}...")
                    val dir = getExternalFilesDir(null) ?: filesDir
                    val dst = File(dir, "erix_pull_${target.name}")
                    val ok = t.pullByHandle(
                        handle = target.handle,
                        destFile = dst,
                        onProgress = { r, tot -> DebugLogger.log("  $r/$tot") },
                        isCancelled = { false }
                    )
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            } catch (_: Exception) {}
        }
    }
}
