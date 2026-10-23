package com.erix.ptp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.erix.ptp.adb.AdbDeviceWatcher
import com.erix.ptp.data.FileEntry
import com.erix.ptp.databinding.FragmentBrowserBinding
import com.erix.ptp.manager.AdbFileManager
import com.erix.ptp.manager.LocalFileManager
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class BrowserFragment : Fragment() {
    private var _b: FragmentBrowserBinding? = null
    private val b get() = _b

    private lateinit var adapter: FileAdapter
    private val localMgr = LocalFileManager()
    private var adbMgr: AdbFileManager? = null

    private var isRemote: Boolean = false
    private var selectionCallback: ((Boolean, List<FileEntry>) -> Unit)? = null
    private var initialPath: String? = null
    private var loadJob: Job? = null // Mencegah spam loading

    private data class NavSeg(val label: String, val path: String)
    private val navStack = mutableListOf<NavSeg>()

    fun setParams(remote: Boolean, callback: (Boolean, List<FileEntry>) -> Unit) {
        this.isRemote = remote
        this.selectionCallback = callback
    }

    fun setInitialPath(path: String) { this.initialPath = path }
    fun getCurrentPath(): String? = navStack.lastOrNull()?.path
    fun isRemoteSide(): Boolean = isRemote

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val vb = FragmentBrowserBinding.inflate(inflater, container, false)
        _b = vb
        return vb.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val vb = _b ?: return

        adapter = FileAdapter(
            onFolderClick = { entry -> enterFolder(entry) },
            onSelectionChanged = { reportSelection() }
        )
        vb.rvFiles.layoutManager = LinearLayoutManager(requireContext())
        vb.rvFiles.adapter = adapter

        if (navStack.isEmpty()) {
            val startPath = initialPath ?: if (isRemote) "/sdcard" else localMgr.rootPath()
            navStack.add(NavSeg(startPath.substringAfterLast('/').ifEmpty { startPath }, startPath))
        }

        vb.btnUp.setOnClickListener { goUp() }
        loadCurrent()
    }

    private fun currentSeg(): NavSeg = navStack.last()

    private fun loadCurrent() {
        val vb = _b ?: return
        val path = currentSeg().path
        
        // Batalkan request sebelumnya jika masih berjalan
        loadJob?.cancel()
        
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            vb.tvPath.text = path
            val entries: List<FileEntry> = if (isRemote) {
                val t = AdbDeviceWatcher.transport
                if (t == null || !t.isConnected) {
                    DebugLogger.log("[R] ADB belum terhubung")
                    emptyList()
                } else {
                    try {
                        val mgr = adbMgr ?: AdbFileManager(t).also { adbMgr = it }
                        val list = mgr.list(path)
                        DebugLogger.log("[R] ${list.size} item di $path")
                        list
                    } catch (e: Throwable) {
                        DebugLogger.log("[R] ❌ ${e.message}")
                        emptyList()
                    }
                }
            } else {
                try {
                    val list = localMgr.list(path)
                    DebugLogger.log("[L] ${list.size} item di $path")
                    list
                } catch (e: Throwable) {
                    DebugLogger.log("[L] ❌ ${e.message}")
                    emptyList()
                }
            }
            val vb2 = _b ?: return@launch
            adapter.setItems(entries)
        }
    }

    private fun enterFolder(entry: FileEntry) {
        when (entry) {
            is FileEntry.Local  -> navStack.add(NavSeg(entry.name, entry.absolutePath))
            is FileEntry.Remote -> navStack.add(NavSeg(entry.name, entry.fullPath))
        }
        loadCurrent()
    }

    fun goUp(): Boolean {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.size - 1)
            loadCurrent()
            return true
        }
        return false
    }

    fun refresh() { loadCurrent() }
    fun clearSelection() { _b?.let { adapter.clearSelection() } }

    private fun reportSelection() {
        selectionCallback?.invoke(isRemote, adapter.getSelected())
    }

    override fun onDestroyView() {
        loadJob?.cancel()
        _b = null
        super.onDestroyView()
    }
}
