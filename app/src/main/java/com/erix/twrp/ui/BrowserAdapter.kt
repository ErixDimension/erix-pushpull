package com.erix.twrp.ui
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.erix.twrp.databinding.ItemBrowserRowBinding
import com.erix.twrp.storage.StorageItem
import com.erix.twrp.util.BasketManager
import com.erix.twrp.util.FormatUtil
class BrowserAdapter(private var items: List<StorageItem> = emptyList(), private val onClick: (Int) -> Unit) : RecyclerView.Adapter<BrowserAdapter.VH>() {
    private var cursor = 0
    inner class VH(val b: ItemBrowserRowBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemBrowserRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.b.tvIcon.text = when { item.isDir -> "📁"; item.isArchive -> "📦"; else -> "📄" }
        holder.b.tvName.text = item.name; holder.b.tvPath.text = item.path; holder.b.tvSize.text = if (item.isDir) "—" else FormatUtil.size(item.size); holder.b.tvMark.text = if (BasketManager.has(item.path)) "✓" else ""
        holder.b.root.setBackgroundColor(when { position == cursor -> 0xFFD70087.toInt(); BasketManager.has(item.path) -> 0xFF2A2A2A.toInt(); else -> 0 })
        holder.b.root.setOnClickListener { cursor = position; notifyDataSetChanged(); onClick(position) }
    }
    override fun getItemCount() = items.size
    fun setItems(n: List<StorageItem>) { items = n; notifyDataSetChanged() }
    fun setCursor(p: Int) { cursor = p; notifyDataSetChanged() }
}
