package com.erix.ptp.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private val _logs = MutableStateFlow("")
    val logs = _logs
    private val _status = MutableStateFlow("Menunggu device...")
    val status = _status
    private var logFile: File? = null

    fun init(ctx: Context) {
        logFile = File(ctx.filesDir, "erix_log.txt").also {
            // Append, jangan overwrite (biar log run sebelumnya tetap ada)
            if (!it.exists()) it.createNewFile()
        }
    }

    fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val line = "[$time] $msg\n"
        _logs.value += line
        // Simpan ke file juga
        try {
            logFile?.appendText(line)
        } catch (_: Exception) {}
    }

    fun setStatus(s: String) { _status.value = s }

    fun clear() { _logs.value = "" }
}
