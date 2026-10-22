package com.erix.twrp.ui
import android.view.*
import androidx.recyclerview.widget.RecyclerView
import com.erix.twrp.databinding.ItemBrowserRowBinding
import com.erix.twrp.storage.StorageItem
class BrowserAdapter(private var items: List<StorageItem> = emptyList(), private val onClick: (Int) -> Unit) : RecyclerView.Adapter<BrowserAdapter.VH>() {
    private var cursor = 0
    inner class VH(val b: ItemBrowserRowBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemBrowserRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.b.tvIcon.text = if (item.isDir) "📁" else "📄"
        holder.b.tvName.text = item.name; holder.b.tvPath.text = item.path
        holder.b.root.setBackgroundColor(if (position == cursor) 0xFFD70087.toInt() else 0)
        holder.b.root.setOnClickListener { cursor = position; notifyDataSetChanged(); onClick(position) }
    }
    override fun getItemCount() = items.size
    fun setItems(n: List<StorageItem>) { items = n; notifyDataSetChanged() }
    fun setCursor(p: Int) { cursor = p; notifyDataSetChanged() }
}
