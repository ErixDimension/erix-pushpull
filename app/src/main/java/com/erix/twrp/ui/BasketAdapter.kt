package com.erix.twrp.ui
import android.view.*
import androidx.recyclerview.widget.RecyclerView
import com.erix.twrp.databinding.ItemBrowserRowBinding
import com.erix.twrp.util.FormatUtil
class BasketAdapter(private var items: List<com.erix.twrp.util.BasketManager.Item> = emptyList()) : RecyclerView.Adapter<BasketAdapter.VH>() {
    inner class VH(val b: ItemBrowserRowBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemBrowserRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]; holder.b.tvIcon.text = if (item.isDir) "📁" else "📄"; holder.b.tvName.text = item.name; holder.b.tvPath.text = item.path; holder.b.tvSize.text = FormatUtil.size(item.size)
        holder.b.root.setOnClickListener { com.erix.twrp.util.BasketManager.remove(item.path); setItems(com.erix.twrp.util.BasketManager.items) }
    }
    override fun getItemCount() = items.size
    fun setItems(n: List<com.erix.twrp.util.BasketManager.Item>) { items = n; notifyDataSetChanged() }
}
