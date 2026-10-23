package com.erix.ptp.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
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
import com.erix.ptp.transfer.ChunkedTransfer
import com.erix.ptp.ui.SplitGuideDialog
import java.io.FileOutputStream
import com.erix.ptp.transfer.TransferMode
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
    private val localMgr = LocalFileManager()
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
                if (side == "LOCAL") remoteFragment?.clearSelection() else localFragment?.clearSelection()
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
        showFolderPicker(true) { anyHandle ->
            val destHandle = anyHandle as Int
            val selected = selectedItems.mapNotNull { it as? FileEntry.Local }
            lifecycleScope.launch(Dispatchers.Main) {
                val files = withContext(Dispatchers.IO) {
                    val out = mutableListOf<File>()
                    for (item in selected) {
                        if (item.isDir) File(item.absolutePath).walkTopDown()
                            .filter { it.isFile }.forEach { out.add(it) }
                        else out.add(File(item.absolutePath))
                    }
                    out
                }
                if (files.isEmpty()) {
                    Toast.makeText(this@BrowserActivity, "Tidak ada file", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val info = withContext(Dispatchers.IO) { ptpMgr?.getStorageInfo() }
                val free = info?.freeBytes ?: -1L
                val total = info?.totalBytes ?: -1L

                if (files.size == 1 && files[0].length() >= ChunkedTransfer.THRESHOLD) {
                    val singleFile = files[0]
                    TransferModeDialog(this@BrowserActivity).show(
                        singleFile.name, singleFile.length(), free, total
                    ) { mode ->
                        if (mode == null) return@show
                        runUpload(files, destHandle, mode, free, total)
                    }
                } else {
                    runUpload(files, destHandle, TransferMode.DIRECT, free, total)
                }
            }
        }
    }

    private fun runUpload(files: List<File>, destHandle: Int, mode: TransferMode, free: Long, total: Long) {
        val totalSize = files.sumOf { it.length() }
        val dlg = TransferDialogV2(this@BrowserActivity)
        dlg.show()
        dlg.setStorage(total, free)
        dlg.setTotal(totalSize)
        val title = when (mode) {
            TransferMode.RESUMABLE -> "Transfer (Bisa Dilanjutkan)"
            TransferMode.DIRECT -> "Transfer (Sekali Jalan)"
        }
        dlg.setHeader(title, "${files.size} file, ${TransferDialogV2.fmt(totalSize)}")

        lifecycleScope.launch(Dispatchers.IO) {
            val engine = ChunkedTransfer(this@BrowserActivity, ptpMgr!!.transport(), ptpMgr!!.storageId())
            var doneBytes = 0L
            var failed = 0
            var cancelled = false
            var failReason = ""

            for ((idx, f) in files.withIndex()) {
                if (dlg.isCancelled()) { cancelled = true; break }
                val useChunk = mode == TransferMode.RESUMABLE && f.length() >= ChunkedTransfer.THRESHOLD

                val result: ChunkedTransfer.Result = if (useChunk) {
                    engine.uploadResumable(
                        localFile = f,
                        destHandle = destHandle,
                        onProgress = { sent, _ ->
                            dlg.updateProgress(doneBytes + sent, totalSize)
                        },
                        onLog = { line -> dlg.addLog(line) },
                        isCancelled = { dlg.isCancelled() }
                    )
                } else {
                    dlg.addLog("📤 [${idx+1}/${files.size}] ${f.name}")
                    engine.uploadDirect(
                        localFile = f,
                        destHandle = destHandle,
                        onProgress = { sent, _ ->
                            dlg.updateProgress(doneBytes + sent, totalSize)
                        },
                        onLog = { line -> dlg.addLog(line) },
                        isCancelled = { dlg.isCancelled() }
                    )
                }

                when (result) {
                    is ChunkedTransfer.Result.Success -> doneBytes += f.length()
                    is ChunkedTransfer.Result.Cancelled -> { cancelled = true; break }
                    is ChunkedTransfer.Result.Failed -> { failed++; failReason = result.reason; break }
                }
            }

            withContext(Dispatchers.Main) {
                when {
                    cancelled -> dlg.finishCancelled(files.size - failed, files.size)
                    failed > 0 -> dlg.finishFailed(files.size - failed, files.size, failReason)
                    else -> dlg.finishSuccess(files.size, files.size)
                }
                localFragment?.clearSelection()
                onSelection("LOCAL", emptyList())
            }
        }
    }

    private fun transferRemoteToLocal() {
        showFolderPicker(false) { anyPath ->
            val destPath = anyPath as String
            val selected = selectedItems.mapNotNull { it as? FileEntry.Remote }
            val mgr = ptpMgr ?: return@showFolderPicker

            // Cek dulu: ada file >4GB?
            val bigFiles = selected.filter { !it.isDir && it.size > SplitGuideDialog.MAX_PTP_SIZE }
            if (bigFiles.isNotEmpty()) {
                val big = bigFiles.first()
                // Cari parent path
                val parts = bigFiles.mapNotNull { bf ->
                    // Detect part files dengan prefix sama
                    val base = bf.name
                    selected.filter { it.name.startsWith("$base.part") }
                        .flatMap { it1 -> selected.filter { it2 -> it2.name.startsWith("$base.part") } }
                }
                // Simple: tampilkan guide untuk file pertama
                val folderPath = guessFolderPath(destPath, big)
                SplitGuideDialog(this@BrowserActivity).show(
                    fileName = big.name,
                    fileSize = big.size,
                    folderPath = folderPath,
                    onAlreadySplit = {
                        // Refresh list & download parts
                        DebugLogger.log("↻ User confirmed split, refreshing...")
                        lifecycleScope.launch(Dispatchers.Main) {
                            withContext(Dispatchers.IO) { kotlinx.coroutines.delay(500) }
                            downloadAfterSplit(big, destPath)
                        }
                    },
                    onCancel = { DebugLogger.log("Split dibatalkan user") }
                )
                return@showFolderPicker
            }

            // Semua file <4GB: download normal
            lifecycleScope.launch(Dispatchers.Main) {
                val items = withContext(Dispatchers.IO) {
                    val out = mutableListOf<FileEntry.Remote>()
                    for (item in selected) {
                        if (item.isDir) out.addAll(mgr.walkFolder(item.handle))
                        else out.add(item)
                    }
                    out
                }
                if (items.isEmpty()) {
                    Toast.makeText(this@BrowserActivity, "Tidak ada file", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val totalSize = items.sumOf { it.size }
                val stats = withContext(Dispatchers.IO) { localMgr.getStorageStats() }
                if (stats.usableBytes in 1 until totalSize) {
                    Toast.makeText(this@BrowserActivity,
                        "Storage HP kurang: butuh ${TransferDialogV2.fmt(totalSize)}, tersedia ${TransferDialogV2.fmt(stats.usableBytes)}",
                        Toast.LENGTH_LONG).show()
                    return@launch
                }
                downloadItems(items, destPath, stats.totalBytes, stats.usableBytes)
            }
        }
    }

    private fun guessFolderPath(destPath: String, item: FileEntry.Remote): String {
        // TWRP path biasanya /sdcard atau /data/media
        return "/sdcard"
    }

    private fun downloadAfterSplit(bigFile: FileEntry.Remote, destPath: String) {
        val mgr = ptpMgr ?: return
        lifecycleScope.launch(Dispatchers.Main) {
            try {
                // Refresh list folder yang berisi file
                val list = withContext(Dispatchers.IO) {
                    mgr.list(bigFile.parentHandle)
                }
                val partPrefix = "${bigFile.name}.part"
                val parts = list.filter { it.name.startsWith(partPrefix) && !it.isDir }
                    .sortedBy { it.name }

                if (parts.isEmpty()) {
                    Toast.makeText(this@BrowserActivity,
                        "Belum ada file .part. Pastikan sudah split di TWRP.", Toast.LENGTH_LONG).show()
                    return@launch
                }

                DebugLogger.log("Ditemukan ${parts.size} bagian: ${parts.joinToString { it.name }}")
                val totalBytes = parts.sumOf { it.size }
                val stats = withContext(Dispatchers.IO) { localMgr.getStorageStats() }
                if (stats.usableBytes < totalBytes) {
                    Toast.makeText(this@BrowserActivity,
                        "Storage HP kurang. Butuh ${TransferDialogV2.fmt(totalBytes)}, ada ${TransferDialogV2.fmt(stats.usableBytes)}",
                        Toast.LENGTH_LONG).show()
                    return@launch
                }

                // Download parts + join
                val finalFile = File(destPath, bigFile.name)
                val dlg = TransferDialogV2(this@BrowserActivity)
                dlg.show()
                dlg.setStorage(stats.totalBytes, stats.usableBytes)
                dlg.setTotal(totalBytes)
                dlg.setHeader("Download Bertahap", "${parts.size} bagian, ${TransferDialogV2.fmt(totalBytes)}")
                dlg.addLog("📦 Akan download ${parts.size} bagian lalu gabung otomatis")

                withContext(Dispatchers.IO) {
                    var doneBytes = 0L
                    var failed = false
                    var cancelled = false

                    // Buka final file dengan append mode
                    FileOutputStream(finalFile, false).use { out ->
                        for ((idx, part) in parts.withIndex()) {
                            if (dlg.isCancelled()) { cancelled = true; break }
                            val partFile = File(destPath, "tmp_${part.name}")
                            dlg.addLog("📥 [${idx+1}/${parts.size}] ${part.name} (${TransferDialogV2.fmt(part.size)})")
                            val ok = mgr.download(
                                handle = part.handle,
                                destFile = partFile,
                                onProgress = { r, _ ->
                                    dlg.updateProgress(doneBytes + r, totalBytes)
                                },
                                isCancelled = { dlg.isCancelled() }
                            )
                            if (!ok) { failed = true; partFile.delete(); break }
                            // Append ke final file
                            partFile.inputStream().use { it.copyTo(out) }
                            partFile.delete()
                            doneBytes += part.size
                            dlg.addLog("✓ ${part.name} → digabung (${TransferDialogV2.fmt(doneBytes)} / ${TransferDialogV2.fmt(totalBytes)})")
                        }
                    }

                    withContext(Dispatchers.Main) {
                        when {
                            cancelled -> {
                                dlg.finishCancelled(0, parts.size)
                                // Hapus final file setengah jadi
                                finalFile.delete()
                            }
                            failed -> dlg.finishFailed(0, parts.size, "salah satu bagian gagal")
                            else -> {
                                dlg.finishSuccess(parts.size, parts.size)
                                dlg.addLog("✅ Selesai: ${finalFile.absolutePath}")
                                Toast.makeText(this@BrowserActivity,
                                    "✓ Berhasil gabung: ${bigFile.name}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                DebugLogger.log("Split download error: ${e.message}")
                Toast.makeText(this@BrowserActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadItems(items: List<FileEntry.Remote>, destPath: String, total: Long, free: Long) {
        val mgr = ptpMgr ?: return
        
        // Cek apakah ada file >4GB (sentinel)
        val hasUnlimited = items.any { it.size >= 4294967295L }
        val totalSize = if (hasUnlimited) -1L else items.sumOf { it.size }
        
        val dlg = TransferDialogV2(this@BrowserActivity)
        dlg.show()
        dlg.setStorage(total, free)
        dlg.setTotal(totalSize)
        val title = if (hasUnlimited) "Download (>4 GB)" else "Download"
        val sizeText = if (hasUnlimited) "ukuran asli tak diketahui" else TransferDialogV2.fmt(totalSize)
        dlg.setHeader(title, "${items.size} file, $sizeText")

        lifecycleScope.launch(Dispatchers.IO) {
            var doneBytes = 0L
            var failed = 0
            var cancelled = false
            var failReason = ""
            for ((idx, item) in items.withIndex()) {
                if (dlg.isCancelled()) { cancelled = true; break }
                val destFile = File(destPath, item.name)
                dlg.addLog("📥 [${idx+1}/${items.size}] ${item.name}")
                val ok = mgr.download(
                    handle = item.handle,
                    destFile = destFile,
                    onProgress = { r, _ ->
                        dlg.updateProgress(doneBytes + r, totalSize)
                    },
                    isCancelled = { dlg.isCancelled() }
                )
                if (ok) doneBytes += if (item.size >= 4294967295L) 0L else item.size
                else { failed++; failReason = "download gagal"; break }
            }
            withContext(Dispatchers.Main) {
                when {
                    cancelled -> dlg.finishCancelled(items.size - failed, items.size)
                    failed > 0 -> dlg.finishFailed(items.size - failed, items.size, failReason)
                    else -> dlg.finishSuccess(items.size, items.size)
                }
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

        var currentHandle = -1
        val handleStack = mutableListOf<Int>()
        val nameStack = mutableListOf<String>()
        var currentPath = "/storage/emulated/0"
        val localMgr2 = LocalFileManager()

        fun refreshPath() {
            tvPath.text = if (isRemote) "/TWRP/" + nameStack.joinToString("/") else currentPath
        }

        fun loadFolders() {
            refreshPath()
            lifecycleScope.launch {
                try {
                    val folders = if (isRemote) {
                        ptpMgr!!.list(currentHandle).filter { it.isDir }
                    } else {
                        localMgr2.list(currentPath).filter { it.isDir }
                    }
                    rv.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                            val tv = TextView(ctx).apply {
                                setPadding(24, 24, 24, 24); textSize = 16f
                                setTextColor(0xFF000000.toInt())
                            }
                            return object : RecyclerView.ViewHolder(tv) {}
                        }
                        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                            val item = folders[position]
                            (holder.itemView as TextView).text = "📁 ${item.name}"
                            holder.itemView.setOnClickListener {
                                when (item) {
                                    is FileEntry.Local -> { currentPath = item.absolutePath; loadFolders() }
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
                } catch (e: Exception) { DebugLogger.log("Picker error: ${e.message}") }
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

    private fun formatSize(b: Long): String {
        if (b <= 0) return "0 B"
        var v = b.toDouble(); val u = listOf("B","KB","MB","GB"); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return if (i == 0) "${b} B" else "%.1f %s".format(v, u[i])
    }
}
