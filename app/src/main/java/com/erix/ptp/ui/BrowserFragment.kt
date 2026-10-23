package com.erix.ptp.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.erix.ptp.databinding.FragmentBrowserBinding
import com.erix.ptp.data.FileEntry
import com.erix.ptp.manager.LocalFileManager
import com.erix.ptp.manager.PtpFileManager
import kotlinx.coroutines.launch

class BrowserFragment(
    private val isRemote: Boolean,
    private val onSelectionChanged: (isRemote: Boolean, selected: List<FileEntry>) -> Unit
) : Fragment() {

    private var _b: FragmentBrowserBinding? = null
    private val b get() = _b!!
    private lateinit var adapter: FileAdapter
    private val localMgr = LocalFileManager()
    private var ptpMgr: PtpFileManager? = null

    private data class NavSeg(val name: String, val key: String)
    private val navStack = mutableListOf<NavSeg>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = FragmentBrowserBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = FileAdapter(
            onFolderClick = { entry -> if (entry.isDir) enterFolder(entry) },
            onSelectionChanged = { reportSelection() }
        )
        b.rvFiles.layoutManager = LinearLayoutManager(requireContext())
        b.rvFiles.adapter = adapter

        b.btnUp.setOnClickListener { goUp() }

        if (isRemote) {
            ptpMgr = BrowserActivity.ptpMgr
            navStack.add(NavSeg("TWRP Root", "-1"))
        } else {
            navStack.add(NavSeg("Internal", localMgr.rootPath()))
        }

        loadCurrent()
    }

    private fun currentSeg() = navStack.last()

    private fun loadCurrent() {
        val seg = currentSeg()
        b.tvPath.text = navStack.joinToString("/") { it.name }
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val entries: List<FileEntry> = if (isRemote) {
                    val mgr = ptpMgr ?: return@launch
                    mgr.list(seg.key.toInt())
                } else {
                    localMgr.list(seg.key)
                }
                adapter.setItems(entries)
                reportSelection()
            } catch (e: Exception) {
                adapter.setItems(emptyList())
            }
        }
    }

    private fun enterFolder(entry: FileEntry) {
        when (entry) {
            is FileEntry.Local -> navStack.add(NavSeg(entry.name, entry.absolutePath))
            is FileEntry.Remote -> navStack.add(NavSeg(entry.name, entry.handle.toString()))
        }
        loadCurrent()
    }

    private fun goUp() {
        if (navStack.size <= 1) return
        navStack.removeAt(navStack.size - 1)
        loadCurrent()
    }

    fun clearSelection() = adapter.clearSelection()

    private fun reportSelection() {
        onSelectionChanged(isRemote, adapter.getSelected())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }
}
