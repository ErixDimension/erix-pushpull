package com.erix.ptp.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.Button
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import com.erix.ptp.R
import com.erix.ptp.transfer.TransferMode

class TransferModeDialog(private val ctx: Activity) {

    fun show(
        fileName: String,
        fileSize: Long,
        storageFree: Long,
        storageTotal: Long,
        onSelect: (TransferMode?) -> Unit
    ) {
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_transfer_mode, null)
        val tvIcon = v.findViewById<TextView>(R.id.tvStorageIcon)
        val tvText = v.findViewById<TextView>(R.id.tvStorageText)
        val bar = v.findViewById<ProgressBar>(R.id.storageBar)
        val tvFile = v.findViewById<TextView>(R.id.tvFileInfo)
        val rg = v.findViewById<RadioGroup>(R.id.rgMode)
        val rbResumable = v.findViewById<RadioButton>(R.id.rbResumable)
        val rbDirect = v.findViewById<RadioButton>(R.id.rbDirect)
        val tvResInfo = v.findViewById<TextView>(R.id.tvResumableInfo)
        val tvDirInfo = v.findViewById<TextView>(R.id.tvDirectInfo)
        val tvSuggest = v.findViewById<TextView>(R.id.tvSuggestion)
        val btnStart = v.findViewById<Button>(R.id.btnStart)
        val btnCancel = v.findViewById<Button>(R.id.btnCancel)

        val fmt = { b: Long -> fmtSize(b) }

        // Storage banner
        if (storageTotal > 0) {
            val used = storageTotal - storageFree
            val pct = ((used * 100) / storageTotal).toInt()
            bar.progress = pct
            when {
                storageFree >= 2 * fileSize -> {
                    tvIcon.text = "✓"; tvIcon.setTextColor(0xFF00E5FF.toInt())
                    tvText.text = "Sisa tempat: ${fmt(storageFree)} bebas dari ${fmt(storageTotal)} (${pct}% terpakai)"
                    bar.progressTintList = android.content.res.ColorStateList.valueOf(0xFF00E5FF.toInt())
                }
                storageFree >= fileSize -> {
                    tvIcon.text = "⚠"; tvIcon.setTextColor(0xFFFFC107.toInt())
                    tvText.text = "Sisa tempat: ${fmt(storageFree)} bebas dari ${fmt(storageTotal)} (${pct}% terpakai)"
                    bar.progressTintList = android.content.res.ColorStateList.valueOf(0xFFFFC107.toInt())
                }
                else -> {
                    tvIcon.text = "⛔"; tvIcon.setTextColor(0xFFF44336.toInt())
                    tvText.text = "Sisa tempat TIDAK CUKUP: ${fmt(storageFree)} dari ${fmt(storageTotal)}"
                    bar.progressTintList = android.content.res.ColorStateList.valueOf(0xFFF44336.toInt())
                }
            }
        } else {
            tvIcon.text = "?"; tvText.text = "Storage tidak diketahui (TWRP tanpa info)"
            bar.progress = 0
        }

        tvFile.text = "$fileName (${fmt(fileSize)})"

        // Kondisi mode
        val canResumable = storageFree >= 2 * fileSize
        val canDirect = storageFree >= fileSize

        if (!canResumable) {
            rbResumable.isEnabled = false
            rbResumable.alpha = 0.4f
            tvResInfo.text = if (storageTotal > 0)
                "⛔ Storage hanya ${fmt(storageFree)}, butuh ${fmt(2*fileSize)}"
            else "⚠ Storage tidak diketahui, coba mode langsung"
            tvResInfo.setTextColor(0xFFF44336.toInt())
        }
        if (!canDirect) {
            rbDirect.isEnabled = false
            rbDirect.alpha = 0.4f
            tvDirInfo.text = "⛔ Storage hanya ${fmt(storageFree)}, butuh ${fmt(fileSize)}"
            tvDirInfo.setTextColor(0xFFF44336.toInt())
        }

        // Auto select
        if (canResumable) {
            rbResumable.isChecked = true
            btnStart.isEnabled = true
        } else if (canDirect) {
            rbDirect.isChecked = true
            btnStart.isEnabled = true
        } else {
            // Tidak ada yang bisa dipilih
            btnStart.visibility = android.view.View.GONE
            tvSuggest.visibility = android.view.View.VISIBLE
            tvSuggest.text = "💡 Saran:\n• Hapus file tidak perlu dulu di TWRP\n• Atau kirim ke folder lain dengan storage lebih besar"
        }

        rg.setOnCheckedChangeListener { _, _ ->
            btnStart.isEnabled = rbResumable.isChecked || rbDirect.isChecked
        }

        val dialog = AlertDialog.Builder(ctx, R.style.Theme_ErixDialog)
            .setView(v).setCancelable(false).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnCancel.setOnClickListener { dialog.dismiss(); onSelect(null) }
        btnStart.setOnClickListener {
            val mode = when {
                rbResumable.isChecked -> TransferMode.RESUMABLE
                rbDirect.isChecked -> TransferMode.DIRECT
                else -> null
            }
            dialog.dismiss()
            onSelect(mode)
        }

        dialog.show()
    }

    private fun fmtSize(b: Long): String = when {
        b < 1024L -> "$b B"
        b < 1024L*1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L*1024*1024 -> "%.1f MB".format(b / (1024.0*1024))
        else -> "%.2f GB".format(b / (1024.0*1024*1024))
    }
}
