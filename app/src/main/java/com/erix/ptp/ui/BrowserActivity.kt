package com.erix.ptp.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.erix.ptp.adb.AdbDeviceWatcher
import com.erix.ptp.data.FileEntry
import com.erix.ptp.databinding.ActivityBrowserBinding
import com.erix.ptp.manager.AdbTransferManager
import com.erix.ptp.manager.ConflictResolver
import com.erix.ptp.transfer.ProgressTracker
import com.erix.ptp.util.DebugLogger
import com.erix.ptp.util.FormatUtil
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch
import java.io.File

/**
 * BrowserActivity — dual-tab LOCAL / REMOTE dengan transfer push/pull.
 *
 * Alur transfer:
 *  - User pilih item di tab LOCAL → tap "Kirim ke Remote" → push ke folder REMOTE aktif
 *  - User pilih item di tab REMOTE → tap "Simpan ke Local" → pull ke folder LOCAL aktif
 *  - Dialog progress real-time (persen, bytes, kecepatan, sisa waktu, sisa data)
 *  - Setelah sukses → refresh tab tujuan
 */
class BrowserActivity : AppCompatActivity() {

    companion object {
        private const val PREFS = "browser_prefs"
        private const val KEY_LAST_TAB = "last_tab"
        private const val KEY_PATH_0 = "path_local"
        private const val KEY_PATH_1 = "path_remote"
        private const val DEFAULT_REMOTE_PATH = "/sdcard"
    }

    private lateinit var b: ActivityBrowserBinding
    private var selectedSide: String? = null
    private var selectedItems: List<FileEntry> = emptyList()
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    private var transferManager: AdbTransferManager? = null
    private var activeDialog: TransferDialog? = null
    @Volatile private var transferCancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        setTitle("Terang Bulan Browser")

        setupTabs()
        setupBottomBar()
        setupBackHandler()

        val lastTab = prefs.getInt(KEY_LAST_TAB, 0).coerceIn(0, 1)
        if (lastTab != 0) b.pager.setCurrentItem(lastTab, false)
    }

    private fun setupTabs() {
        val adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment {
                val remote = position == 1
                val lastPath = prefs.getString(
                    if (remote) KEY_PATH_1 else KEY_PATH_0, null
                )
                return BrowserFragment().apply {
                    setParams(remote) { isRemote, items -> onSelection(isRemote, items) }
                    if (lastPath != null) setInitialPath(lastPath)
                }
            }
        }
        b.pager.adapter = adapter
        TabLayoutMediator(b.tabs, b.pager) { tab, pos ->
            tab.text = if (pos == 0) "📱 LOCAL" else "🔧 REMOTE"
        }.attach()
    }

    private fun setupBottomBar() {
        b.btnSend.isEnabled = false
        b.btnSend.text = "🚀 Pilih file dulu"
        b.btnSend.setOnClickListener { dispatchTransfer() }
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val f = currentFragment()
                if (f != null && f.goUp()) {
                    // naik folder berhasil
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun currentFragment(): BrowserFragment? {
        val tag = "f${b.pager.currentItem}"
        return supportFragmentManager.findFragmentByTag(tag) as? BrowserFragment
    }

    private fun onSelection(isRemote: Boolean, items: List<FileEntry>) {
        selectedSide = if (isRemote) "remote" else "local"
        selectedItems = items

        if (items.isEmpty()) {
            b.tvSelection.text = "Belum ada item dipilih"
            b.btnSend.isEnabled = false
            b.btnSend.text = "🚀 Pilih file dulu"
            return
        }

        val total = items.sumOf { it.size }
        b.tvSelection.text = "${items.size} item (${FormatUtil.formatBytes(total)})"
        b.btnSend.isEnabled = true
        b.btnSend.text = if (isRemote) "⬇ Simpan ke Local" else "🚀 Kirim ke Remote"
    }

    private fun dispatchTransfer() {
        val t = AdbDeviceWatcher.transport
        if (t == null || !t.isConnected) {
            Toast.makeText(this, "ADB belum terhubung", Toast.LENGTH_SHORT).show()
            return
        }
        if (selectedItems.isEmpty()) return

        val manager = AdbTransferManager(t, ConflictResolver(t))
        transferManager = manager

        if (selectedSide == "local") {
            val files = selectedItems.mapNotNull { it as? FileEntry.Local }
            if (files.isNotEmpty()) startPush(manager, files)
        } else {
            val entries = selectedItems.filterIsInstance<FileEntry.Remote>()
            val files = entries.filter { !it.isDir }
            if (files.isNotEmpty()) startPull(manager, files)
        }
    }

    private fun startPush(manager: AdbTransferManager, files: List<FileEntry.Local>) {
        val remoteFrag = supportFragmentManager.findFragmentByTag("f1") as? BrowserFragment
        val remoteDir = remoteFrag?.getCurrentPath() ?: DEFAULT_REMOTE_PATH
        transferCancelled = false

        val dialog = TransferDialog(this) { transferCancelled = true }
        activeDialog = dialog
        dialog.setHeader("Mengirim ${files.size} file ke Remote")
        dialog.show()

        lifecycleScope.launch {
            var successCount = 0
            var failCount = 0
            var totalBytes = 0L
            val tracker = ProgressTracker()

            for ((idx, local) in files.withIndex()) {
                if (transferCancelled) break
                dialog.setHeader("Mengirim ${idx + 1} dari ${files.size}")
                dialog.setFilename(local.name)

                val result = manager.upload(
                    localFile = File(local.absolutePath),
                    remoteDir = remoteDir,
                    tracker = tracker
                ) { snap -> runOnUiThread { dialog.update(snap) } }

                if (result.success) {
                    successCount++
                    totalBytes += result.bytesTransferred
                    DebugLogger.log("✅ Push OK: ${local.name} → ${result.finalPath}")
                } else {
                    failCount++
                    DebugLogger.log("❌ Push FAIL: ${local.name}: ${result.errorMessage}")
                }
            }

            dialog.dismiss()
            activeDialog = null

            if (transferCancelled) {
                Toast.makeText(this@BrowserActivity,
                    "Transfer dibatalkan", Toast.LENGTH_SHORT).show()
            } else {
                val msg = if (failCount == 0) {
                    "✅ ${successCount} file terkirim (${FormatUtil.formatBytes(totalBytes)})"
                } else {
                    "Selesai: ${successCount} sukses, ${failCount} gagal"
                }
                Toast.makeText(this@BrowserActivity, msg, Toast.LENGTH_LONG).show()
                remoteFrag?.refresh()
            }
        }
    }

    private fun startPull(manager: AdbTransferManager, entries: List<FileEntry.Remote>) {
        val localFrag = supportFragmentManager.findFragmentByTag("f0") as? BrowserFragment
        val localDirPath = localFrag?.getCurrentPath() ?: "/sdcard/Download"
        val localDir = File(localDirPath)
        transferCancelled = false

        val dialog = TransferDialog(this) { transferCancelled = true }
        activeDialog = dialog
        dialog.setHeader("Menyimpan ${entries.size} file ke Local")
        dialog.show()

        lifecycleScope.launch {
            var successCount = 0
            var failCount = 0
            var totalBytes = 0L
            val tracker = ProgressTracker()

            for ((idx, entry) in entries.withIndex()) {
                if (transferCancelled) break
                dialog.setHeader("Menyimpan ${idx + 1} dari ${entries.size}")
                dialog.setFilename(entry.name)

                val result = manager.download(
                    remotePath = entry.fullPath,
                    localDir = localDir,
                    expectedSize = entry.size,
                    tracker = tracker
                ) { snap -> runOnUiThread { dialog.update(snap) } }

                if (result.success) {
                    successCount++
                    totalBytes += result.bytesTransferred
                    DebugLogger.log("✅ Pull OK: ${entry.fullPath} → ${result.finalPath}")
                } else {
                    failCount++
                    DebugLogger.log("❌ Pull FAIL: ${entry.name}: ${result.errorMessage}")
                }
            }

            dialog.dismiss()
            activeDialog = null

            if (transferCancelled) {
                Toast.makeText(this@BrowserActivity,
                    "Transfer dibatalkan", Toast.LENGTH_SHORT).show()
            } else {
                val msg = if (failCount == 0) {
                    "✅ ${successCount} file tersimpan (${FormatUtil.formatBytes(totalBytes)})"
                } else {
                    "Selesai: ${successCount} sukses, ${failCount} gagal"
                }
                Toast.makeText(this@BrowserActivity, msg, Toast.LENGTH_LONG).show()
                localFrag?.refresh()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        val editor = prefs.edit()
        editor.putInt(KEY_LAST_TAB, b.pager.currentItem)
        for (i in 0..1) {
            val frag = supportFragmentManager.findFragmentByTag("f$i") as? BrowserFragment
            frag?.getCurrentPath()?.let {
                editor.putString(if (i == 0) KEY_PATH_0 else KEY_PATH_1, it)
            }
        }
        editor.apply()
    }
}
