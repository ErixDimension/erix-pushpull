package com.erix.ptp.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.erix.ptp.data.FileEntry
import com.erix.ptp.databinding.ActivityBrowserBinding
import com.google.android.material.tabs.TabLayoutMediator

class BrowserActivity : AppCompatActivity() {

    private lateinit var b: ActivityBrowserBinding
    private var selectedSide: String? = null
    private var selectedItems: List<FileEntry> = emptyList()
    private val fragments = arrayOfNulls<BrowserFragment>(2)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        setTitle("Terang Bulan Browser")

        setupTabs()
        setupBottomBar()
        setupBackHandler()
    }

    private fun setupTabs() {
        val adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = 2
            override fun createFragment(position: Int): Fragment {
                val remote = position == 1
                val f = BrowserFragment().apply {
                    setParams(remote) { isRemote, items -> onSelection(isRemote, items) }
                }
                fragments[position] = f
                return f
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
        b.btnSend.setOnClickListener {
            Toast.makeText(this, "Transfer akan hadir di Stage 2c", Toast.LENGTH_SHORT).show()
        }
    }

    /** Back button: naik folder dulu, baru keluar activity. */
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = fragments[b.pager.currentItem]
                if (current != null && current.goUp()) {
                    // Berhasil naik folder — diabaikan
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
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
        b.tvSelection.text = "${items.size} item (${formatSize(total)})"
        b.btnSend.isEnabled = true
        b.btnSend.text = if (isRemote) "⬇ Simpan ke Local" else "🚀 Kirim ke Remote"
    }

    private fun formatSize(v: Long): String {
        if (v <= 0) return "0 B"
        var x = v.toDouble()
        val u = listOf("B", "KB", "MB", "GB", "TB")
        var i = 0
        while (x >= 1024 && i < u.size - 1) { x /= 1024; i++ }
        return if (i == 0) "$v B" else "%.1f %s".format(x, u[i])
    }
}
