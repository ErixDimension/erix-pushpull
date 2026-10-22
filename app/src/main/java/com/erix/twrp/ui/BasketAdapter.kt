package com.erix.twrp.ui
import android.view.*
import androidx.recyclerview.widget.RecyclerView
import com.erix.twrp.databinding.ItemBrowserRowBinding
import com.erix.twrp.util.FormatUtil
class BasketAdapter(private var items: List<com.erix.twrp.util.BasketManager.Item> = emptyList()) : RecyclerView.Adapter<BasketAdapter.VH>() {
    inner class VH(val b: ItemBrowserRowBinding) : RecyclerView.ViewHolder(b.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(ItemBrowserRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: VH, position: Int) {
        val it = items[position]; holder.b.tvIcon.text = if (it.isDir) "📁" else "📄"; holder.b.tvName.text = it.name; holder.b.tvPath.text = it.path; holder.b.tvSize.text = FormatUtil.size(it.size)
        holder.b.root.setOnClickListener { com.erix.twrp.util.BasketManager.remove(it.path); setItems(com.erix.twrp.util.BasketManager.items) }
    }
    override fun getItemCount() = items.size
    fun setItems(n: List<com.erix.twrp.util.BasketManager.Item>) { items = n; notifyDataSetChanged() }
}
