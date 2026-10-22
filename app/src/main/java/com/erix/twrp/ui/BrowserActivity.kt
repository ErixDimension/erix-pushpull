package com.erix.twrp.ui
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.erix.twrp.adb.AdbManager
import com.erix.twrp.transfer.AdbRuntime
import com.flyfishxu.kadb.Kadb
import com.erix.twrp.databinding.ActivityBrowserBinding
import com.erix.twrp.storage.*
import com.erix.twrp.transfer.TransferService
import com.erix.twrp.util.BasketManager
import kotlinx.coroutines.launch
import java.io.File
class BrowserActivity : AppCompatActivity() {
    companion object { const val EXTRA_SIDE = "side"; const val EXTRA_PICK_MODE = "pick_mode"; const val EXTRA_TITLE = "title"; const val EXTRA_START_PATH = "start_path"; const val EXTRA_FLOOR = "floor"; const val EXTRA_ACTION = "action"; var pendingSource: StorageItem? = null; var pendingSourceSide: String? = null; var pendingAction: String? = null }
    private lateinit var b: ActivityBrowserBinding; private lateinit var adapter: BrowserAdapter; private lateinit var local: LocalStorageManager; private var remote: RemoteStorageManager? = null
    private var isLocal = true; private var pickMode = "ITEM"; private var action = ""; private var path = "/storage/emulated/0"; private var floor = "/storage/emulated/0"; private var items: List<StorageItem> = emptyList(); private var cursor = 0
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); b = ActivityBrowserBinding.inflate(layoutInflater); setContentView(b.root)
        isLocal = intent.getStringExtra(EXTRA_SIDE) == "LOCAL"; pickMode = intent.getStringExtra(EXTRA_PICK_MODE) ?: "ITEM"; action = intent.getStringExtra(EXTRA_ACTION) ?: ""
        path = intent.getStringExtra(EXTRA_START_PATH) ?: if (isLocal) "/storage/emulated/0" else "/sdcard/"; floor = intent.getStringExtra(EXTRA_FLOOR) ?: path
        local = LocalStorageManager(this); b.tvTitle.text = intent.getStringExtra(EXTRA_TITLE)
        val active = AdbRuntime.active; if (active != null) { remote = RemoteStorageManager(AdbManager(this, active.first as Kadb)) } else if (!isLocal) { finish(); return }
        adapter = BrowserAdapter(items) { onItemClick(it) }; b.rvItems.layoutManager = LinearLayoutManager(this); b.rvItems.adapter = adapter
        b.btnUp.setOnClickListener { moveCursor(-1) }; b.btnDown.setOnClickListener { moveCursor(1) }; b.btnSelect.setOnClickListener { onItemClick(cursor) }; b.btnBack.setOnClickListener { navigateBack() }; b.btnSelectMode.setOnClickListener { openSelectMode() }; b.btnBasket.setOnClickListener { openBasket() }; b.btnNewFolder.setOnClickListener { doNewFolder() }; b.btnExtract.setOnClickListener { doExtract(items[cursor]) }
        load()
    }
    private fun load() { lifecycleScope.launch { try { items = if (isLocal) local.list(path) else remote?.list(path) ?: emptyList(); cursor = cursor.coerceIn(0, maxOf(0, items.size - 1)); adapter.setItems(items); adapter.setCursor(cursor); b.tvPath.text = path } catch (e: Exception) { Toast.makeText(this@BrowserActivity, "Load error: ${e.message}", Toast.LENGTH_SHORT).show() } } }
    private fun moveCursor(d: Int) { if (items.isEmpty()) return; cursor = ((cursor + d) % items.size + items.size) % items.size; adapter.setCursor(cursor); b.rvItems.scrollToPosition(cursor) }
    private fun onItemClick(idx: Int) { val it = items.getOrNull(idx) ?: return; if (it.isDir) { path = it.path.trimEnd('/'); load(); return }; if (pickMode == "DIR") { Toast.makeText(this, "Bukan folder", Toast.LENGTH_SHORT).show(); return }; finishWithItem(it) }
    private fun navigateBack() { val p = path.trimEnd('/'); if (p == floor.trimEnd('/')) { finish(); return }; path = File(p).parent ?: "/"; load() }
    private fun openBasket() { if (BasketManager.items.isEmpty()) Toast.makeText(this, "Kosong", Toast.LENGTH_SHORT).show() else startActivity(Intent(this, BasketActivity::class.java)) }
    private fun openSelectMode() { Toast.makeText(this, "Multi-select clicked", Toast.LENGTH_SHORT).show() }
    private fun doNewFolder() { val input = EditText(this).apply { hint = "Nama" }; AlertDialog.Builder(this).setTitle("Folder Baru").setView(input).setPositiveButton("Buat") { _, _ -> lifecycleScope.launch { if (isLocal) local.mkdirs(path.trimEnd('/') + "/" + input.text) else remote?.mkdirs(path.trimEnd('/') + "/" + input.text); load() } }.show() }
    private fun doExtract(it: StorageItem) { Toast.makeText(this, "Extract: ${it.name}", Toast.LENGTH_SHORT).show() }
    private fun finishWithItem(it: StorageItem) {
        pendingSource = it; pendingSourceSide = if (isLocal) "LOCAL" else "REMOTE"; pendingAction = action; finish()
        when (action) {
            "push_source" -> {
                if (AdbRuntime.active == null) {
                    Toast.makeText(this, "Device B belum terhubung. Colok kabel USB dulu.", Toast.LENGTH_LONG).show()
                    return
                }
                startActivity(Intent(this, BrowserActivity::class.java).apply { putExtra(EXTRA_SIDE, "REMOTE"); putExtra(EXTRA_PICK_MODE, "DIR"); putExtra(EXTRA_TITLE, "DEST — B"); putExtra(EXTRA_ACTION, "push_dest"); putExtra(EXTRA_START_PATH, "/sdcard/") })
            }
            "pull_source" -> startActivity(Intent(this, BrowserActivity::class.java).apply { putExtra(EXTRA_SIDE, "LOCAL"); putExtra(EXTRA_PICK_MODE, "DIR"); putExtra(EXTRA_TITLE, "DEST — A"); putExtra(EXTRA_ACTION, "pull_dest"); putExtra(EXTRA_START_PATH, "/storage/emulated/0") })
            "push_dest" -> TransferService.start(this, "push", pendingSource!!.path, it.path)
            "pull_dest" -> TransferService.start(this, "pull", pendingSource!!.path, it.path)
        }
    }
}
