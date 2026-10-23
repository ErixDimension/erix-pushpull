package com.erix.ptp.transfer

import com.erix.ptp.util.FormatUtil

/**
 * ProgressTracker — gabung SpeedMeter + FormatUtil jadi label UI siap pakai.
 */
class ProgressTracker {
    private val speedMeter = SpeedMeter()
    private var total: Long = 0L
    private var current: Long = 0L

    fun start(expectedTotal: Long) {
        total = expectedTotal
        current = 0L
        speedMeter.reset()
    }

    fun update(bytes: Long, totalBytes: Long) {
        if (totalBytes > 0) total = totalBytes
        current = bytes
        speedMeter.update(bytes)
    }

    data class Snapshot(
        val progressPercent: Int,
        val percentText: String,
        val bytesText: String,
        val speedText: String,
        val remainingTimeText: String,
        val remainingBytesText: String
    )

    fun snapshot(): Snapshot {
        val percent = if (total > 0) ((current * 100) / total).toInt().coerceIn(0, 100) else 0
        val percentText = FormatUtil.formatPercent(current, total)
        val bytesText = "${FormatUtil.formatBytes(current)} / ${FormatUtil.formatBytes(total)}"
        val speed = speedMeter.currentSpeed()
        val speedText = FormatUtil.formatSpeed(speed)
        val remainingSec = speedMeter.estimateRemainingSeconds(current, total)
        val remainingTimeText = FormatUtil.formatDuration(remainingSec)
        val remainingBytes = (total - current).coerceAtLeast(0L)
        val remainingBytesText = FormatUtil.formatBytes(remainingBytes)
        return Snapshot(percent, percentText, bytesText, speedText, remainingTimeText, remainingBytesText)
    }
}
