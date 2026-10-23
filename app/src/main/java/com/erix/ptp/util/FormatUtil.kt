package com.erix.ptp.util

import java.util.Locale

/**
 * FormatUtil — format angka untuk UI, TANPA singkatan.
 * User biasa tidak paham "MB", "KB/s", "ETA", jadi ditulis lengkap.
 *
 * Contoh:
 *   1234567      → "1,2 Megabyte"
 *   2621440      → "2,5 Megabyte per detik"
 *   200          → "3 menit 20 detik"
 *   45.6         → "45,6%"
 */
object FormatUtil {

    private val UNITS = listOf(
        "Byte",
        "Kilobyte",
        "Megabyte",
        "Gigabyte",
        "Terabyte"
    )

    /**
     * Format byte ke string ramah user.
     * Contoh: 0 → "0 Byte", 1536 → "1,5 Kilobyte", 5242880 → "5,0 Megabyte"
     */
    fun formatBytes(bytes: Long): String {
        if (bytes < 0) return "Tidak diketahui"
        if (bytes == 0L) return "0 Byte"
        var value = bytes.toDouble()
        var unitIdx = 0
        while (value >= 1024 && unitIdx < UNITS.size - 1) {
            value /= 1024.0
            unitIdx++
        }
        val formatted = if (unitIdx == 0) {
            // Byte: tampilkan tanpa desimal
            value.toLong().toString()
        } else {
            String.format(Locale.getDefault(), "%.1f", value).replace('.', ',')
        }
        return "$formatted ${UNITS[unitIdx]}"
    }

    /**
     * Format kecepatan transfer.
     * Contoh: 2621440 → "2,5 Megabyte per detik"
     */
    fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "Menghitung..."
        return "${formatBytes(bytesPerSecond)} per detik"
    }

    /**
     * Format durasi sisa transfer.
     * Contoh: 20 → "20 detik"
     *         200 → "3 menit 20 detik"
     *         3700 → "1 jam 1 menit"
     */
    fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "Selesai"
        if (seconds < 60) return "$seconds detik"
        if (seconds < 3600) {
            val menit = seconds / 60
            val sisaDetik = seconds % 60
            return if (sisaDetik == 0L) "$menit menit"
            else "$menit menit $sisaDetik detik"
        }
        val jam = seconds / 3600
        val menit = (seconds % 3600) / 60
        return if (menit == 0L) "$jam jam"
        else "$jam jam $menit menit"
    }

    /**
     * Format persentase dengan 1 desimal.
     * Contoh: (45, 100) → "45,0%"
     *         (0, 0)    → "0,0%"
     */
    fun formatPercent(current: Long, total: Long): String {
        if (total <= 0) return "0,0%"
        val pct = (current * 100.0 / total).coerceIn(0.0, 100.0)
        return String.format(Locale.getDefault(), "%.1f%%", pct).replace('.', ',')
    }
}
