package com.erix.twrp.util
object FormatUtil {
    fun size(b: Long): String {
        if (b <= 0) return "0 B"; var v = b.toDouble(); val units = listOf("B", "KB", "MB", "GB", "TB"); var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return if (i == 0) "${b} B" else "%.2f %s".format(v, units[i])
    }
}
