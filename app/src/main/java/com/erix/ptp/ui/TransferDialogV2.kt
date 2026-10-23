package com.erix.ptp.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.erix.ptp.R
import java.util.concurrent.atomic.AtomicBoolean

class TransferDialogV2(private val ctx: Activity) {

    private var dialog: AlertDialog? = null
    private var tvTitle: TextView? = null
    private var tvFile: TextView? = null
    private var bar: ProgressBar? = null
    private var tvPercent: TextView? = null
    private var tvStatus: TextView? = null
    private var tvSpeed: TextView? = null
    private var tvEta: TextView? = null
    private var tvBytes: TextView? = null
    private var tvLog: TextView? = null
    private var scrollLog: ScrollView? = null
    private var btnCancel: Button? = null
    private var tvStorageText: TextView? = null
    private var tvStorageIcon: TextView? = null
    private var storageBar: ProgressBar? = null

    private val cancelled = AtomicBoolean(false)
    private val logBuf = StringBuilder()
    private var totalBytes = 0L
    private var lastBytes = 0L
    private var lastTime = 0L
    private var emaSpeed = 0.0
    private var lastUiUpdate = 0L
    private var finished = false
    private var storageTotal = 0L
    private var storageFree = 0L

    @Volatile var doneCount = 0
    @Volatile var doneBytes = 0L

    fun isCancelled() = cancelled.get()

    fun show() {
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_transfer_v2, null)
        tvTitle = v.findViewById(R.id.tvTitle)
        tvFile = v.findViewById(R.id.tvFile)
        bar = v.findViewById(R.id.progressBar)
        tvPercent = v.findViewById(R.id.tvPercent)
        tvStatus = v.findViewById(R.id.tvStatus)
        tvSpeed = v.findViewById(R.id.tvSpeed)
        tvEta = v.findViewById(R.id.tvEta)
        tvBytes = v.findViewById(R.id.tvBytes)
        tvLog = v.findViewById(R.id.tvLog)
        scrollLog = v.findViewById(R.id.scrollLog)
        btnCancel = v.findViewById(R.id.btnCancel)
        tvStorageText = v.findViewById(R.id.tvStorageText)
        tvStorageIcon = v.findViewById(R.id.tvStorageIcon)
        storageBar = v.findViewById(R.id.storageBar)

        btnCancel?.setOnClickListener {
            if (finished) dialog?.dismiss()
            else { cancelled.set(true); btnCancel?.text = "Membatalkan..."; btnCancel?.isEnabled = false }
        }

        dialog = AlertDialog.Builder(ctx, R.style.Theme_ErixDialog)
            .setView(v).setCancelable(false).create()
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog?.show()
    }

    fun setStorage(total: Long, free: Long) {
        storageTotal = total; storageFree = free
        ctx.runOnUiThread {
            if (total > 0) {
                val used = total - free
                val pct = ((used * 100) / total).toInt()
                storageBar?.progress = pct
                when {
                    free >= 2L * 1024 * 1024 * 1024 -> {
                        tvStorageIcon?.text = "✓"; tvStorageIcon?.setTextColor(0xFF00E5FF.toInt())
                    }
                    free >= 1024L * 1024 * 1024 -> {
                        tvStorageIcon?.text = "⚠"; tvStorageIcon?.setTextColor(0xFFFFC107.toInt())
                    }
                    else -> {
                        tvStorageIcon?.text = "⛔"; tvStorageIcon?.setTextColor(0xFFF44336.toInt())
                    }
                }
                tvStorageText?.text = "Sisa tempat: ${fmt(free)} bebas dari ${fmt(total)}"
            }
        }
    }

    fun setHeader(title: String, subtitle: String) {
        ctx.runOnUiThread { tvTitle?.text = title; tvFile?.text = subtitle }
    }

    fun setTotal(bytes: Long) {
        totalBytes = bytes; lastBytes = 0; lastTime = 0; lastUiUpdate = 0; emaSpeed = 0.0
        ctx.runOnUiThread { updateBytes(0) }
    }

    fun addLog(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        synchronized(logBuf) {
            logBuf.append("[$ts] ").append(line).append("\n")
            if (logBuf.length > 20000) logBuf.delete(0, logBuf.length - 15000)
        }
        ctx.runOnUiThread {
            tvLog?.text = logBuf.toString()
            scrollLog?.post { scrollLog?.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    fun updateProgress(current: Long, total: Long) {
        if (finished) return
        val now = System.currentTimeMillis()
        if (now - lastUiUpdate < 250 && current < total) return
        lastUiUpdate = now

        if (lastTime > 0L) {
            val dt = (now - lastTime) / 1000.0
            if (dt > 0.3) {
                val dBytes = (current - lastBytes).coerceAtLeast(0)
                val instant = dBytes / dt
                emaSpeed = if (emaSpeed <= 0.0) instant else emaSpeed * 0.7 + instant * 0.3
                lastBytes = current
                lastTime = now
            }
        } else { lastTime = now; lastBytes = current }

        ctx.runOnUiThread {
            val pct = if (total > 0) ((current * 100) / total).toInt() else 0
            bar?.progress = pct
            tvPercent?.text = "$pct%"
            updateBytes(current)
            tvSpeed?.text = "Kecepatan: " + fmtSpeed(emaSpeed)
            if (emaSpeed > 0 && current < total) {
                val remain = total - current
                tvEta?.text = "Sisa: " + fmtEta((remain / emaSpeed).toLong())
            } else if (current >= total) {
                tvEta?.text = "Selesai"
            } else tvEta?.text = ""
        }
    }

    fun finishSuccess(filesDone: Int, filesTotal: Int) {
        if (finished) return; finished = true
        ctx.runOnUiThread {
            tvStatus?.text = "✓ Sukses ($filesDone/$filesTotal)"
            tvStatus?.setTextColor(0xFF4CAF50.toInt())
            bar?.progress = 100; tvPercent?.text = "100%"
            btnCancel?.text = "Tutup"; btnCancel?.isEnabled = true
            btnCancel?.setOnClickListener { dialog?.dismiss() }
        }
    }

    fun finishFailed(filesDone: Int, filesTotal: Int, reason: String) {
        if (finished) return; finished = true
        ctx.runOnUiThread {
            tvStatus?.text = "✗ Gagal: $reason"
            tvStatus?.setTextColor(0xFFF44336.toInt())
            btnCancel?.text = "Tutup"; btnCancel?.isEnabled = true
            btnCancel?.setOnClickListener { dialog?.dismiss() }
        }
    }

    fun finishCancelled(filesDone: Int, filesTotal: Int) {
        if (finished) return; finished = true
        ctx.runOnUiThread {
            tvStatus?.text = "⊘ Dibatalkan ($filesDone/$filesTotal)"
            tvStatus?.setTextColor(0xFFFFC107.toInt())
            btnCancel?.text = "Tutup"; btnCancel?.isEnabled = true
            btnCancel?.setOnClickListener { dialog?.dismiss() }
        }
    }

    private fun updateBytes(current: Long) {
        val remain = (totalBytes - current).coerceAtLeast(0)
        tvBytes?.text = "${fmt(current)} / ${fmt(totalBytes)}  (${fmt(remain)} sisa)"
    }

    companion object {
        fun fmt(b: Long): String = when {
            b < 1024L -> "$b B"
            b < 1024L*1024 -> "%.1f KB".format(b / 1024.0)
            b < 1024L*1024*1024 -> "%.1f MB".format(b / (1024.0*1024))
            else -> "%.2f GB".format(b / (1024.0*1024*1024))
        }
        fun fmtSpeed(bps: Double): String = when {
            bps < 1024 -> "%.0f B/detik".format(bps)
            bps < 1024*1024 -> "%.1f KB/detik".format(bps / 1024.0)
            else -> "%.2f MB/detik".format(bps / (1024.0*1024))
        }
        fun fmtEta(sec: Long): String = when {
            sec < 60 -> "$sec detik"
            sec < 3600 -> "${sec/60} menit ${sec%60} detik"
            else -> "${sec/3600} jam ${(sec%3600)/60} menit"
        }
    }
}
