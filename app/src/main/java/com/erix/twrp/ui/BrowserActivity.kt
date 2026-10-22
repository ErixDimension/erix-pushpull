package com.erix.twrp.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.erix.twrp.adb.AdbManager
import com.erix.twrp.adb.AdbDeviceWatcher
import com.erix.twrp.databinding.ActivityBrowserBinding
import com.erix.twrp.storage.*
import com.flyfishxu.kadb.Kadb
import kotlinx.coroutines.launch
import java.io.File

class BrowserActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_SIDE = "side"
        const val EXTRA_START_PATH = "start_path"
    }
    private lateinit var b: ActivityBrowserBinding
    private lateinit var adapter: BrowserAdapter
    private lateinit var local: LocalStorageManager
    private var remote: RemoteStorageManager? = null
    private var isLocal = true
    private var path = "/storage/emulated/0"
    private var items: List<StorageItem> = emptyList()
    private var cursor = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(b.root)
        isLocal = intent.getStringExtra(EXTRA_SIDE) == "LOCAL"
        path = intent.getStringExtra(EXTRA_START_PATH) ?: "/"
        local = LocalStorageManager(this)
        
        if (!isLocal) {
            val kadb = AdbDeviceWatcher.kadb.value
            if (kadb != null) {
                remote = RemoteStorageManager(AdbManager(this, kadb))
            } else {
                finish()
                return
            }
        }
        
        adapter = BrowserAdapter(items) { onItemClick(it) }
        b.rvItems.layoutManager = LinearLayoutManager(this)
        b.rvItems.adapter = adapter
        b.btnUp.setOnClickListener { moveCursor(-1) }
        b.btnDown.setOnClickListener { moveCursor(1) }
        b.btnSelect.setOnClickListener { onItemClick(cursor) }
        b.btnBack.setOnClickListener { finish() }
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            try {
                items = if (isLocal) local.list(path) else remote?.list(path) ?: emptyList()
                cursor = 0
                adapter.setItems(items)
                adapter.setCursor(cursor)
                b.tvPath.text = path
            } catch (e: Exception) {
                Toast.makeText(this@BrowserActivity, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun moveCursor(d: Int) {
        if (items.isEmpty()) return
        cursor = ((cursor + d) % items.size + items.size) % items.size
        adapter.setCursor(cursor)
        b.rvItems.scrollToPosition(cursor)
    }

    private fun onItemClick(idx: Int) {
        val it = items.getOrNull(idx) ?: return
        if (it.isDir) {
            path = it.path
            load()
        } else {
            Toast.makeText(this, "File: ${it.name}", Toast.LENGTH_SHORT).show()
        }
    }
}
