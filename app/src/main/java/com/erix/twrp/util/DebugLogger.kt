package com.erix.twrp.util
import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
object DebugLogger {
    private val _logs = MutableStateFlow<String>("")
    val logs = _logs
    fun log(msg: String) { val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()); _logs.value += "[$time] $msg\n" }
}
