package com.erix.ptp.ui

import android.app.AlertDialog
import android.os.Bundle
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
    private var localFragment: BrowserFragment? = null
    private var remoteFragment: BrowserFragment? = null
    private var selectedSide: String? = null
    private var selectedItems: List<FileEntry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)

        val transport = PtpWatcher.transport
        val storageId = PtpWatcher.storageId
        if (transport == null || storageId == -1) {
            Toast.makeText(this, "PTP belum siap", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        ptpMgr = PtpFileManager(transport, storageId)

        setupTabs()
        b.btnSend.setOnClickListener { startTransfer() }
        updateBottomBar()
    }

    private fun setupTabs() {
        val adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment {
                val frag = BrowserFragment()
                if (position == 0) {
                    frag.setParams(false) { _, items -> onSelection("LOCAL", items) }
                    localFragment = frag
                } else {
                    frag.setParams(true) { _, items -> onSelection("REMOTE", items) }
                    remoteFragment = frag
                }
                return frag
            }
        }
        b.pager.adapter = adapter
        TabLayoutMediator(b.tabs, b.pager) { tab, pos ->
            tab.text = if (pos == 0) "📱 LOCAL" else "🔧 REMOTE (TWRP)"
        }.attach()
    }

    private fun onSelection(side: String, items: List<FileEntry>) {
        if (items.isEmpty()) {
            if (selectedSide == side) {
                selectedSide = null
                selectedItems = emptyList()
            }
        } else {
            if (selectedSide != null && selectedSide != side) {
                if (side == "LOCAL") remoteFragment?.clearSelection()
                else localFragment?.clearSelection()
            }
            selectedSide = side
            selectedItems = items
        }
        updateBottomBar()
    }

    private fun updateBottomBar() {
        if (selectedItems.isEmpty()) {
            b.tvSelection.text = "Belum ada item dipilih (tahan folder untuk memilih)"
            b.btnSend.isEnabled = false
            b.btnSend.text = "🚀 Pilih file dulu"
        } else {
            val total = selectedItems.sumOf { it.size }
            b.tvSelection.text = "${selectedItems.size} item (${formatSize(total)})"
            b.btnSend.isEnabled = true
            b.btnSend.text = when (selectedSide) {
                "LOCAL" -> "🚀 KIRIM ke REMOTE (${selectedItems.size})"
                "REMOTE" -> "🚀 KIRIM ke LOCAL (${selectedItems.size})"
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
        showFolderPicker(isRemote = true) { anyHandle ->
            val destHandle = anyHandle as Int
            val selected = selectedItems.mapNotNull { it as? FileEntry.Local }
            val files = mutableListOf<File>()
            lifecycleScope.launch(Dispatchers.IO) {
                for (item in selected) {
                    if (item.isDir) {
                        File(item.absolutePath).walkTopDown().filter { it.isFile }.forEach { files.add(it) }
                    } else {
                        files.add(File(item.absolutePath))
                    }
                }
                withContext(Dispatchers.Main) {
                    executeTransfer(files.size) { progress, total ->
                        doPushFiles(files, destHandle, progress, total)
                    }
                }
            }
        }
    }

    private fun transferRemoteToLocal() {
        showFolderPicker(isRemote = false) { anyPath ->
            val destPath = anyPath as String
            val selected = selectedItems.mapNotNull { it as? FileEntry.Remote }
            val mgr = ptpMgr ?: return@showFolderPicker
            lifecycleScope.launch(Dispatchers.IO) {
                val items = mutableListOf<FileEntry.Remote>()
                for (item in selected) {
                    if (item.isDir) {
                        items.addAll(mgr.walkFolder(item.handle))
                    } else {
                        items.add(item)
                    }
                }
                withContext(Dispatchers.Main) {
                    executeTransfer(items.size) { progress, total ->
                        doPullFiles(items, destPath, progress, total)
                    }
                }
            }
        }
    }

    private fun doPushFiles(files: List<File>, destHandle: Int, progress: (Int, Int, String) -> Unit, total: Int) {
        val mgr = ptpMgr ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            var done = 0
            for (f in files) {
                withContext(Dispatchers.Main) { progress(done, total, "Upload ${f.name}...") }
                if (mgr.upload(f, destHandle) { _, _ -> }) done++
            }
            withContext(Dispatchers.Main) {
                progress(total, total, "Selesai: $done/$total")
                Toast.makeText(this@BrowserActivity, "✓ Selesai: $done/$total", Toast.LENGTH_LONG).show()
                localFragment?.clearSelection()
                onSelection("LOCAL", emptyList())
            }
        }
    }

    private fun doPullFiles(items: List<FileEntry.Remote>, destPath: String, progress: (Int, Int, String) -> Unit, total: Int) {
        val mgr = ptpMgr ?: return
        val localMgr = LocalFileManager()
        lifecycleScope.launch(Dispatchers.IO) {
            var done = 0
            for (it in items) {
                val destFile = File(destPath, it.name)
                withContext(Dispatchers.Main) { progress(done, total, "Download ${it.name}...") }
                if (mgr.download(it.handle, destFile) { _, _ -> }) done++
            }
            withContext(Dispatchers.Main) {
                progress(total, total, "Selesai: $done/$total")
                Toast.makeText(this@BrowserActivity, "✓ Selesai: $done/$total", Toast.LENGTH_LONG).show()
                remoteFragment?.clearSelection()
                onSelection("REMOTE", emptyList())
            }
        }
    }

    private fun showFolderPicker(isRemote: Boolean, onPick: (Any) -> Unit) {
        val ctx = this
        val dialogView = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val tvPath = TextView(ctx).apply {
            setTextColor(0xFF00A0A0.toInt()); textSize = 12f; setPadding(0, 0, 0, 8)
        }
        val btnUp = Button(ctx).apply { text = "↑ Naik" }
        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1200)
        }
        dialogView.addView(tvPath)
        dialogView.addView(btnUp)
        dialogView.addView(rv)

        // State untuk REMOTE
        var currentHandle = -1
        val handleStack = mutableListOf<Int>()       // untuk "Up"
        val nameStack = mutableListOf<String>()      // untuk display path

        // State untuk LOCAL
        var currentPath = "/storage/emulated/0"

        val localMgr = LocalFileManager()

        fun refreshPath() {
            tvPath.text = if (isRemote) {
                "/TWRP/" + nameStack.joinToString("/")
            } else {
                currentPath
            }
        }

        fun loadFolders() {
            refreshPath()
            lifecycleScope.launch {
                try {
                    val folders = if (isRemote) {
                        ptpMgr!!.list(currentHandle).filter { it.isDir }
                    } else {
                        localMgr.list(currentPath).filter { it.isDir }
                    }
                    rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                            val tv = TextView(ctx).apply {
                                setPadding(24, 24, 24, 24); textSize = 16f
                                setTextColor(0xFF000000.toInt())  // HITAM (background putih)
                            }
                            return object : RecyclerView.ViewHolder(tv) {}
                        }
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                            val item = folders[position]
                            (holder.itemView as TextView).text = "📁 ${item.name}"
                            holder.itemView.setOnClickListener {
                                when (item) {
                                    is FileEntry.Local -> {
                                        currentPath = item.absolutePath
                                        loadFolders()
                                    }
                                    is FileEntry.Remote -> {
                                        handleStack.add(currentHandle)
                                        nameStack.add(item.name)
                                        currentHandle = item.handle
                                        loadFolders()
                                    }
                                }
                            }
                        }
                        override fun getItemCount() = folders.size
                    }
                } catch (e: Exception) {
                    DebugLogger.log("Picker error: ${e.message}")
                }
            }
        }

        btnUp.setOnClickListener {
            if (isRemote) {
                if (handleStack.isNotEmpty()) {
                    currentHandle = handleStack.removeAt(handleStack.size - 1)
                    nameStack.removeAt(nameStack.size - 1)
                    loadFolders()
                }
            } else {
                val parent = File(currentPath).parentFile
                if (parent != null) { currentPath = parent.absolutePath; loadFolders() }
            }
        }

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Pilih Folder Tujuan")
            .setView(dialogView)
            .setNegativeButton("Batal", null)
            .setPositiveButton("Pilih Folder Ini") { _, _ ->
                if (isRemote) onPick(currentHandle) else onPick(currentPath)
            }
            .create()

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
            max = 100; progress = 0
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
                if (done >= total && total > 0) {
                    dialog.dismiss()
                }
            }
        }
        transferAction(progress, fileCount)
    }

    private fun formatSize(b: Long): String {
        if (b <= 0) return "0 B"
        var v = b.toDouble(); val u = listOf("B","KB","MB","GB"); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return if (i == 0) "${b} B" else "%.1f %s".format(v, u[i])
    }
}
