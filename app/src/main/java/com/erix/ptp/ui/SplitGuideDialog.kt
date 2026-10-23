package com.erix.ptp.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.erix.ptp.R

class SplitGuideDialog(private val ctx: Activity) {

    /**
     * @param fileName  nama file di TWRP (misal "apollo.zip")
     * @param fileSize  ukuran dalam byte
     * @param folderPath  path folder TWRP (misal "/sdcard/Download")
     */
    fun show(
        fileName: String,
        fileSize: Long,
        folderPath: String,
        onAlreadySplit: () -> Unit,
        onCancel: () -> Unit
    ) {
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_split_guide, null)
        val tvSteps = v.findViewById<TextView>(R.id.tvSteps)
        val tvCommand = v.findViewById<TextView>(R.id.tvCommand)
        val btnCopy = v.findViewById<Button>(R.id.btnCopyCmd)
        val btnAlready = v.findViewById<Button>(R.id.btnAlreadySplit)
        val btnCancel = v.findViewById<Button>(R.id.btnCancel)

        // Hitung jumlah parts (max 3.9GB per part)
        val maxPartBytes = 3900L * 1024 * 1024  // 3900 MB
        val totalParts = ((fileSize + maxPartBytes - 1) / maxPartBytes).toInt().coerceAtLeast(2)

        val fileSizeGb = "%.2f GB".format(fileSize.toDouble() / (1024.0*1024*1024))

        tvSteps.text = buildString {
            appendLine("File: $fileName")
            appendLine("Ukuran: $fileSizeGb")
            appendLine("Akan dipecah jadi: $totalParts bagian (maks 3.9 GB per bagian)")
            appendLine()
            appendLine("1. Buka TWRP → Advanced → Terminal")
            appendLine("2. Ketik perintah di bawah (atau salin)")
            appendLine("3. Tunggu proses selesai")
            appendLine("4. Kembali ke aplikasi ini")
        }

        // Bangun command dd
        val command = buildString {
            appendLine("cd \"$folderPath\"")
            for (i in 1..totalParts) {
                val skipMb = (i - 1) * 3900L
                if (i < totalParts) {
                    appendLine("dd if=\"$fileName\" of=\"$fileName.part$i\" bs=1M count=3900 skip=$skipMb")
                } else {
                    appendLine("dd if=\"$fileName\" of=\"$fileName.part$i\" bs=1M skip=$skipMb")
                }
            }
            append("sync")
        }
        tvCommand.text = command

        btnCopy.setOnClickListener {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("dd commands", command))
            Toast.makeText(ctx, "✓ Perintah dicopy ke clipboard", Toast.LENGTH_SHORT).show()
        }

        val dialog = AlertDialog.Builder(ctx, R.style.Theme_ErixDialog)
            .setView(v)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        btnAlready.setOnClickListener {
            dialog.dismiss()
            onAlreadySplit()
        }
        btnCancel.setOnClickListener {
            dialog.dismiss()
            onCancel()
        }

        dialog.show()
    }

    companion object {
        const val MAX_PTP_SIZE = 4L * 1024 * 1024 * 1024  // 4 GB
    }
}
