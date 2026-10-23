package com.erix.ptp.ui

import android.content.Context
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

    companion object {
        private const val PREFS = "browser_prefs"
        private const val KEY_LAST_TAB = "last_tab"
        private const val KEY_PATH_0 = "path_local"
        private const val KEY_PATH_1 = "path_remote"
    }

    private lateinit var b: ActivityBrowserBinding
    private var selectedSide: String? = null
    private var selectedItems: List<FileEntry> = emptyList()
    private val prefs by lazy { getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        setTitle("Terang Bulan Browser")

        setupTabs()
        setupBottomBar()
        setupBackHandler()

        // Restore last tab
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
        b.btnSend.setOnClickListener {
            Toast.makeText(this, "Transfer akan hadir di Stage 2c", Toast.LENGTH_SHORT).show()
        }
    }

    /** Tag default FragmentStateAdapter = "f<position>" */
    private fun currentFragment(): BrowserFragment? {
        val tag = "f${b.pager.currentItem}"
        return supportFragmentManager.findFragmentByTag(tag) as? BrowserFragment
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val f = currentFragment()
                if (f != null && f.goUp()) {
                    // Berhasil naik folder
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        // Simpan state: tab aktif + path per tab
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
