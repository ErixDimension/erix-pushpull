package com.erix.ptp.transfer

/**
 * SpeedMeter — hitung kecepatan transfer realtime via moving average.
 *
 * Cara kerja:
 * - Simpan sample (timestamp, bytes) di sliding window (default 3 detik)
 * - Setiap update, buang sample yang terlalu lama
 * - Kecepatan = (lastBytes - firstBytes) / (lastTime - firstTime) * 1000
 *
 * Thread-safe: semua method @Synchronized.
 */
class SpeedMeter(
    private val windowMs: Long = 3000L,
    private val minSamples: Int = 2
) {
    private data class Sample(val timestamp: Long, val bytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var lastSpeed: Long = 0L

    @Synchronized
    fun reset() {
        samples.clear()
        lastSpeed = 0L
    }

    @Synchronized
    fun update(currentBytes: Long): Long {
        val now = System.currentTimeMillis()
        samples.addLast(Sample(now, currentBytes))

        // Buang sample lama yang di luar windowMs
        while (samples.size > minSamples) {
            val first = samples.firstOrNull() ?: break
            if (now - first.timestamp > windowMs) {
                samples.removeFirst()
            } else break
        }

        val first = samples.firstOrNull()
        val last = samples.lastOrNull()
        if (first == null || last == null || first === last) {
            lastSpeed = 0L
            return 0L
        }

        val deltaBytes = last.bytes - first.bytes
        val deltaMs = last.timestamp - first.timestamp
        lastSpeed = if (deltaMs > 0 && deltaBytes > 0) {
            (deltaBytes * 1000L) / deltaMs
        } else 0L
        return lastSpeed
    }

    fun currentSpeed(): Long = lastSpeed

    fun estimateRemainingSeconds(currentBytes: Long, totalBytes: Long): Long {
        if (totalBytes <= 0) return 0L
        val remaining = totalBytes - currentBytes
        if (remaining <= 0) return 0L
        val speed = lastSpeed
        if (speed <= 0) return 0L
        return remaining / speed
    }
}
