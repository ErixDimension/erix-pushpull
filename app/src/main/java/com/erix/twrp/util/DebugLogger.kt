package com.erix.twrp.util

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private val _logs = MutableStateFlow("")
    val logs = _logs

    private val _status = MutableStateFlow("Menunggu device...")
    val status = _status

    fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        _logs.value += "[$time] $msg\n"
    }

    fun setStatus(s: String) { _status.value = s }

    fun clear() { _logs.value = "" }
}
