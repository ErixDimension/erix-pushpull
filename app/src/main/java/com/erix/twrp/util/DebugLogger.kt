package com.erix.twrp.util
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DebugLogger {
    private val _logs = MutableStateFlow<String>("")
    val logs: StateFlow<String> = _logs
    
    fun log(msg: String) {
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        _logs.value += "[$time] $msg\n"
    }
    
    fun clear() { _logs.value = "" }
}
