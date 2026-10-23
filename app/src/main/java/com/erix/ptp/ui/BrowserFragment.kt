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
import kotlinx.coroutines.launch

class BrowserFragment : Fragment() {

    private var _b: FragmentBrowserBinding? = null
    private val b get() = _b!!

    private lateinit var adapter: FileAdapter
    private val localMgr = LocalFileManager()
    private var adbMgr: AdbFileManager? = null

    private var isRemote: Boolean = false
    private var selectionCallback: ((Boolean, List<FileEntry>) -> Unit)? = null

    private data class NavSeg(val label: String, val path: String)
    private val navStack = mutableListOf<NavSeg>()

    fun setParams(remote: Boolean, callback: (Boolean, List<FileEntry>) -> Unit) {
        this.isRemote = remote
        this.selectionCallback = callback
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _b = FragmentBrowserBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = FileAdapter(
            onFolderClick = { entry -> enterFolder(entry) },
            onSelectionChanged = { reportSelection() }
        )
        b.rvFiles.layoutManager = LinearLayoutManager(requireContext())
        b.rvFiles.adapter = adapter

        if (navStack.isEmpty()) {
            if (isRemote) navStack.add(NavSeg("REMOTE", "/sdcard"))
            else          navStack.add(NavSeg("LOCAL", localMgr.rootPath()))
        }

        b.btnUp.setOnClickListener { goUp() }
        loadCurrent()
    }

    private fun currentSeg(): NavSeg = navStack.last()

    private fun loadCurrent() {
        viewLifecycleOwner.lifecycleScope.launch {
            b.tvPath.text = currentSeg().path
            val entries: List<FileEntry> = if (isRemote) {
                val t = AdbDeviceWatcher.transport
                if (t == null || !t.isConnected) {
                    DebugLogger.log("[R] ADB belum terhubung — colok device dulu")
                    emptyList()
                } else {
                    try {
                        val mgr = adbMgr ?: AdbFileManager(t).also { adbMgr = it }
                        val list = mgr.list(currentSeg().path)
                        DebugLogger.log("[R] ${list.size} item di ${currentSeg().path}")
                        list
                    } catch (e: Throwable) {
                        DebugLogger.log("[R] ❌ ${e.message}")
                        emptyList()
                    }
                }
            } else {
                try {
                    val list = localMgr.list(currentSeg().path)
                    DebugLogger.log("[L] ${list.size} item di ${currentSeg().path}")
                    list
                } catch (e: Throwable) {
                    DebugLogger.log("[L] ❌ ${e.message}")
                    emptyList()
                }
            }
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

    /** Return true kalau berhasil naik, false kalau sudah di root. */
    fun goUp(): Boolean {
        if (navStack.size > 1) {
            navStack.removeAt(navStack.size - 1)
            loadCurrent()
            return true
        }
        return false
    }

    fun clearSelection() = adapter.clearSelection()

    private fun reportSelection() {
        selectionCallback?.invoke(isRemote, adapter.getSelected())
    }

    override fun onDestroyView() {
        _b = null
        super.onDestroyView()
    }
}
