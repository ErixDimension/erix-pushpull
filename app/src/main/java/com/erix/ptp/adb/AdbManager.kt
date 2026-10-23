package com.erix.ptp.adb

import dadb.Dadb
import com.erix.ptp.util.DebugLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AdbManager(private val dadb: Dadb) {

    suspend fun shell(cmd: String): String = withContext(Dispatchers.IO) {
        try {
            dadb.shell(cmd).output
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    suspend fun push(localFile: File, remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            dadb.push(localFile, remotePath)
            true
        } catch (e: Exception) {
            DebugLogger.log("Push gagal: ${e.message}")
            false
        }
    }

    suspend fun pull(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            dadb.pull(localFile, remotePath)
            true
        } catch (e: Exception) {
            DebugLogger.log("Pull gagal: ${e.message}")
            false
        }
    }
}
