package com.erix.ptp.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.erix.ptp.R
import com.erix.ptp.data.FileEntry

class FileAdapter(
    private val onFolderClick: (FileEntry) -> Unit,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<FileAdapter.VH>() {

    private data class Item(val entry: FileEntry, var selected: Boolean)

    private val items = mutableListOf<Item>()

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val root: View = v.findViewById(R.id.rowRoot)
        val icon: TextView = v.findViewById(R.id.tvIcon)
        val name: TextView = v.findViewById(R.id.tvName)
        val size: TextView = v.findViewById(R.id.tvSize)
        val check: CheckBox = v.findViewById(R.id.cbSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val entry = item.entry

        holder.icon.text = when {
            entry.isDir -> "📁"
            entry.name.endsWith(".zip", true) -> "📦"
            entry.name.endsWith(".apk", true) -> "📱"
            entry.name.endsWith(".jpg", true) || entry.name.endsWith(".png", true) -> "🖼"
            else -> "📄"
        }
        holder.name.text = entry.name
        holder.size.text = when {
            entry.isDir -> if (item.selected) "✓ Folder terpilih" else "Folder (tahan untuk pilih)"
            else -> formatSize(entry.size)
        }

        // Folder: checkbox hanya muncul kalau sudah selected via long-press
        // File: checkbox selalu muncul
        if (entry.isDir) {
            holder.check.visibility = if (item.selected) View.VISIBLE else View.GONE
        } else {
            holder.check.visibility = View.VISIBLE
        }
        holder.check.isChecked = item.selected

        // Klik biasa: folder masuk, file toggle checkbox
        holder.root.setOnClickListener {
            if (entry.isDir) onFolderClick(entry)
            else {
                item.selected = !item.selected
                notifyItemChanged(position)
                onSelectionChanged()
            }
        }

        // Long-press: folder toggle checkbox, file juga toggle
        holder.root.setOnLongClickListener {
            item.selected = !item.selected
            notifyItemChanged(position)
            onSelectionChanged()
            true
        }
    }

    override fun getItemCount() = items.size

    fun setItems(entries: List<FileEntry>) {
        items.clear()
        entries.forEach { items.add(Item(it, false)) }
        notifyDataSetChanged()
    }

    fun getSelected(): List<FileEntry> = items.filter { it.selected }.map { it.entry }

    fun clearSelection() {
        var changed = false
        for (i in items.indices) {
            if (items[i].selected) { items[i].selected = false; changed = true }
        }
        if (changed) notifyDataSetChanged()
    }

    private fun formatSize(b: Long): String {
        if (b <= 0) return "0 B"
        var v = b.toDouble(); val u = listOf("B","KB","MB","GB"); var i = 0
        while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
        return if (i == 0) "${b} B" else "%.1f %s".format(v, u[i])
    }
}
