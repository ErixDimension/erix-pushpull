package com.erix.ptp.ui

import android.app.Dialog
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import com.erix.ptp.R
import com.erix.ptp.transfer.ProgressTracker

/**
 * Dialog progress transfer. Menampilkan persentase, kecepatan, sisa waktu,
 * dan sisa data — semua label lengkap tanpa singkatan.
 *
 * Pemakaian:
 *   val dialog = TransferDialog(context) { cancelRequested = true }
 *   dialog.show()
 *   dialog.setHeader("Mengirim 1 dari 3")
 *   dialog.setFilename("video.mp4")
 *   dialog.update(snapshot)  // dari ProgressTracker
 *   dialog.dismiss()
 */
class TransferDialog(
    context: Context,
    private val onCancel: () -> Unit
) : Dialog(context, R.style.Theme_ErixDialog) {

    private val tvTitle: TextView
    private val tvFilename: TextView
    private val progressBar: ProgressBar
    private val tvPercent: TextView
    private val tvBytes: TextView
    private val tvSpeed: TextView
    private val tvRemainingTime: TextView
    private val tvRemainingBytes: TextView

    init {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_transfer, null)
        setContentView(view)
        setCancelable(false)
        window?.setLayout(
            (context.resources.displayMetrics.widthPixels * 0.9).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        tvTitle = view.findViewById(R.id.tvTitle)
        tvFilename = view.findViewById(R.id.tvFilename)
        progressBar = view.findViewById(R.id.progressBar)
        tvPercent = view.findViewById(R.id.tvPercent)
        tvBytes = view.findViewById(R.id.tvBytes)
        tvSpeed = view.findViewById(R.id.tvSpeed)
        tvRemainingTime = view.findViewById(R.id.tvRemainingTime)
        tvRemainingBytes = view.findViewById(R.id.tvRemainingBytes)

        view.findViewById<Button>(R.id.btnCancel).setOnClickListener {
            onCancel()
            dismiss()
        }
    }

    fun setHeader(text: String) {
        tvTitle.text = text
    }

    fun setFilename(name: String) {
        tvFilename.text = name
    }

    fun update(snap: ProgressTracker.Snapshot) {
        progressBar.progress = snap.progressPercent
        tvPercent.text = snap.percentText
        tvBytes.text = snap.bytesText
        tvSpeed.text = "Kecepatan: ${snap.speedText}"
        tvRemainingTime.text = "Sisa waktu: ${snap.remainingTimeText}"
        tvRemainingBytes.text = "Sisa data: ${snap.remainingBytesText}"
    }

    fun markCompleted() {
        progressBar.progress = 100
        tvPercent.text = "Selesai"
        tvSpeed.text = "Kecepatan: Selesai"
        tvRemainingTime.text = "Sisa waktu: Selesai"
        tvRemainingBytes.text = "Sisa data: 0 Byte"
    }

    fun markFailed(reason: String) {
        tvPercent.text = "Gagal"
        tvSpeed.text = "Alasan: $reason"
    }
}
