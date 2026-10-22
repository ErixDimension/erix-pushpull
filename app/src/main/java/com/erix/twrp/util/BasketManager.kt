package com.erix.twrp.util
import com.erix.twrp.storage.StorageItem
object BasketManager {
    data class Item(val path: String, val name: String, val isDir: Boolean, val size: Long)
    var side: String? = null; private set
    var items: MutableList<Item> = mutableListOf(); private set
    fun has(path: String) = items.any { it.path == path }
    fun toggle(storage: StorageItem, isLocal: Boolean): Boolean {
        val newSide = if (isLocal) "local" else "remote"
        if (items.isNotEmpty() && side != null && side != newSide) return false
        val exist = items.find { it.path == storage.path }
        if (exist != null) items.remove(exist) else items.add(Item(storage.path, storage.name, storage.isDir, storage.size))
        side = if (items.isEmpty()) null else newSide
        return true
    }
    fun remove(path: String) { items.removeAll { it.path == path }; if (items.isEmpty()) side = null }
    fun clear() { items.clear(); side = null }
    fun totalSize() = items.sumOf { it.size }
}
