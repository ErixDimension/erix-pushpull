package com.erix.ptp.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.erix.ptp.R
import com.erix.ptp.databinding.ActivityBrowserBinding
import com.erix.ptp.data.FileEntry
import com.erix.ptp.manager.LocalFileManager
import com.erix.ptp.manager.PtpFileManager
import com.erix.ptp.ptp.PtpWatcher
import com.erix.ptp.util.DebugLogger
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class BrowserActivity : AppCompatActivity() {
    companion object {
        var ptpMgr: PtpFileManager? = null
    }

    private lateinit var b: ActivityBrowserBinding
    private lateinit var localFragment: BrowserFragment
    private lateinit var remoteFragment: BrowserFragment

    // Selection state (hanya satu sisi yang aktif)
    private var selectedSide: String? = null  // "LOCAL" or "REMOTE"
    private var selectedItems: List<FileEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Init ptpMgr
        val transport = PtpWatcher.transport
        val storageId = PtpWatcher.storageId
        if (transport != null && storageId != -1) {
            ptpMgr = PtpFileManager(transport, storageId)
        } else {
            Toast.makeText(this, "PTP belum siap. Colok kabel dulu.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        setupTabs()
        setupBottomBar()
    }

    private fun setupTabs() {
        val adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment {
                return if (position == 0) {
                    BrowserFragment(isRemote = false) { side, items -> onSelection("LOCAL", items) }.also { localFragment = it }
                } else {
                    BrowserFragment(isRemote = true) { side, items -> onSelection("REMOTE", items) }.also { remoteFragment = it }
                }
            }
        }
        b.pager.adapter = adapter
        TabLayoutMediator(b.tabs, b.pager) { tab, pos ->
            tab.text = if (pos == 0) "📱 LOCAL (HP Host)" else "🔧 REMOTE (TWRP)"
        }.attach()
    }

    private fun setupBottomBar() {
        b.btnSend.setOnClickListener { startTransfer() }
    }

    private fun onSelection(side: String, items: List<FileEntry>) {
        if (items.isEmpty()) {
            if (selectedSide == side) {
                selectedSide = null
                selectedItems = emptyList()
            }
        } else {
            // Kalau ada yang dipilih di sisi baru, bersihkan sisi lain
            if (selectedSide != null && selectedSide != side) {
                if (side == "LOCAL") remoteFragment.clearSelection()
                else localFragment.clearSelection()
            }
            selectedSide = side
            selectedItems = items
        }
        updateBottomBar()
    }

    private fun updateBottomBar() {
        val total = selectedItems.sumOf { it.size }
        if (selectedItems.isEmpty()) {
            b.tvSelection.text = "Belum ada item dipilih"
            b.btnSend.isEnabled = false
            b.btnSend.text = "🚀 Pilih file dulu"
        } else {
            b.tvSelection.text = "${selectedItems.size} item dipilih (${formatSize(total)})"
            b.btnSend.isEnabled = true
            b.btnSend.text = when (selectedSide) {
                "LOCAL" -> "🚀 KIRIM ke REMOTE (${selectedItems.size} file)"
                "REMOTE" -> "🚀 KIRIM ke LOCAL (${selectedItems.size} file)"
                else -> "🚀 KIRIM"
            }
        }
    }

    private fun startTransfer() {
        if (selectedItems.isEmpty()) return
        when (selectedSide) {
            "LOCAL" -> transferLocalToRemote()
            "REMOTE" -> transferRemoteToLocal()
        }
    }

    private fun transferLocalToRemote() {
        showFolderPicker(isRemote = true) { destHandle ->
            val files = selectedItems.mapNotNull { it as? FileEntry.Local }
            executeTransfer(files.size) { progress, total ->
                doPushFiles(files, destHandle, progress, total)
            }
        }
    }

    private fun transferRemoteToLocal() {
        showFolderPicker(isRemote = false) { destPath ->
            val items = selectedItems.mapNotNull { it as? FileEntry.Remote }
            executeTransfer(items.size) { progress, total ->
                doPullFiles(items, destPath, progress, total)
            }
        }
    }

    private fun doPushFiles(files: List<FileEntry.Local>, destHandle: Int, progress: (Int, Int, String) -> Unit, total: Int) {
        val mgr = ptpMgr ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            var done = 0
            for (file in files) {
                val src = File(file.absolutePath)
                withContext(Dispatchers.Main) { progress(done, total, "Upload ${file.name}...") }
                val ok = mgr.upload(src, destHandle) { _, _ -> }
                if (ok) done++
            }
            withContext(Dispatchers.Main) {
                progress(total, total, "Selesai: $done/$total OK")
                Toast.makeText(this@BrowserActivity, "✓ Selesai: $done/$total", Toast.LENGTH_LONG).show()
                localFragment.clearSelection()
                onSelection("LOCAL", emptyList())
            }
        }
    }

    private fun doPullFiles(items: List<FileEntry.Remote>, destPath: String, progress: (Int, Int, String) -> Unit, total: Int) {
        val mgr = ptpMgr ?: return
        val localMgr = LocalFileManager()
        lifecycleScope.launch(Dispatchers.IO) {
            var done = 0
            for (item in items) {
                val destFile = File(destPath, item.name)
                withContext(Dispatchers.Main) { progress(done, total, "Download ${item.name}...") }
                val ok = mgr.download(item.handle, destFile) { _, _ -> }
                if (ok) done++
            }
            withContext(Dispatchers.Main) {
                progress(total, total, "Selesai: $done/$total OK")
                Toast.makeText(this@BrowserActivity, "✓ Selesai: $done/$total", Toast.LENGTH_LONG).show()
                remoteFragment.clearSelection()
                onSelection("REMOTE", emptyList())
            }
        }
    }

    // ─── Folder picker (simple dialog with list of folders) ───
    private fun showFolderPicker(isRemote: Boolean, onPick: (Any) -> Unit) {
        val ctx = this
        val dialogView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val tvPath = TextView(ctx).apply {
            text = if (isRemote) "TWRP Root" else "/storage/emulated/0"
            setTextColor(0xFF00E5FF.toInt())
            textSize = 12f
            setPadding(0, 0, 0, 8)
        }
        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 800)
        }
        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Pilih Folder Tujuan")
            .setView(dialogView)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Pilih Folder Ini") { _, _ -> }
            .create()

        // State
        var currentHandle = PtpUsbTransport_ParentRoot
        var currentPath = "/storage/emulated/0"
        val localMgr = LocalFileManager()

        fun loadFolders() {
            tvPath.text = if (isRemote) "Handle: $currentHandle" else currentPath
            lifecycleScope.launch {
                try {
                    val folders = if (isRemote) {
                        ptpMgr!!.list(currentHandle).filter { it.isDir }
                    } else {
                        localMgr.list(currentPath).filter { it.isDir }
                    }
                    val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                            val tv = TextView(ctx).apply {
                                setPadding(24, 24, 24, 24)
                                textSize = 16f
                                setTextColor(0xFFFFFFFF.toInt())
                            }
                            return object : RecyclerView.ViewHolder(tv) {}
                        }
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                            val item = folders[position]
                            (holder.itemView as TextView).text = "📁 ${item.name}"
                            holder.itemView.setOnClickListener {
                                when (item) {
                                    is FileEntry.Local -> { currentPath = item.absolutePath; loadFolders() }
                                    is FileEntry.Remote -> { currentHandle = item.handle; loadFolders() }
                                }
                            }
                        }
                        override fun getItemCount() = folders.size
                    }
                    rv.adapter = adapter
                } catch (e: Exception) {
                    DebugLogger.log("Picker load error: ${e.message}")
                }
            }
        }

        // Up button — rekonstruksi sederhana
        val btnUp = Button(ctx).apply {
            text = "↑ Naik"
            setOnClickListener {
                if (isRemote) {
                    // Untuk remote, tetap di root (belum track parent stack)
                    currentHandle = PtpUsbTransport_ParentRoot
                    loadFolders()
                } else {
                    val parent = File(currentPath).parentFile
                    if (parent != null) { currentPath = parent.absolutePath; loadFolders() }
                }
            }
        }
        dialogView.addView(tvPath)
        dialogView.addView(btnUp)
        dialogView.addView(rv)

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                if (isRemote) onPick(currentHandle) else onPick(currentPath)
            }
        }
        loadFolders()
        dialog.show()
    }

    private fun executeTransfer(fileCount: Int, transferAction: (progress: (Int, Int, String) -> Unit, total: Int) -> Unit) {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val tvMsg = TextView(this).apply { text = "Memulai..."; textSize = 14f }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        dialogView.addView(tvMsg)
        dialogView.addView(bar)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Transfer ($fileCount file)")
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialog.show()

        val progress: (Int, Int, String) -> Unit = { done, total, msg ->
            runOnUiThread {
                tvMsg.text = msg
                bar.progress = if (total > 0) (done * 100 / total) else 0
            }
        }
        transferAction(progress, fileCount)
        runOnUiThread {
            dialog.dismiss()
        }
    }

    private fun formatSize(b: Long): String {
        if (b <= 0) return "0 B"
        var v = b.toDouble(); val u = listOf("B","KB","MB","GB"); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return if (i == 0) "${b} B" else "%.1f %s".format(v, u[i])
    }

    private val PtpUsbTransport_ParentRoot: Int = -1
}
