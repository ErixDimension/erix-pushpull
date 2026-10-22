package com.erix.twrp.adb
import android.content.Context
import dadb.Dadb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AdbManager(private val context: Context, private val dadb: Dadb) {

    suspend fun shell(cmd: String): String = withContext(Dispatchers.IO) {
        dadb.shell(cmd).output
    }

    suspend fun remoteSize(path: String): Long = withContext(Dispatchers.IO) {
        shell("stat -c %s '${q(path)}' 2>/dev/null").trim().toLongOrNull() ?: 0L
    }

    suspend fun remoteIsDir(path: String): Boolean = withContext(Dispatchers.IO) {
        "Y" in shell("[ -d ${q(path)} ] && echo Y || echo N")
    }
    
    suspend fun remoteExists(path: String): Boolean = withContext(Dispatchers.IO) {
        "Y" in shell("[ -e ${q(path)} ] && echo Y || echo N")
    }

    suspend fun remoteSizeOrDirSize(path: String): Long = withContext(Dispatchers.IO) {
        shell("if [ -d ${q(path)} ]; then du -sb ${q(path)} 2>/dev/null | cut -f1; elif [ -f ${q(path)} ]; then stat -c %s ${q(path)} 2>/dev/null; else echo 0; fi")
            .lines().mapNotNull { it.trim().toLongOrNull() }.firstOrNull() ?: 0L
    }
    
    suspend fun remoteFreeBytes(path: String): Long = withContext(Dispatchers.IO) {
        val n = shell("df -k ${q(path)} 2>/dev/null | tail -1").split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }
        if (n.size >= 3) n[2] * 1024 else 0L
    }

    data class RemoteEntry(val name: String, val path: String, val isDir: Boolean, val size: Long)

    suspend fun listDir(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val norm = normalizeRemotePath(path)
        val out = shell("ls -ln ${q(norm)}")
        val result = mutableListOf<RemoteEntry>()
        for (line in out.lines()) {
            if (line.isBlank() || line.startsWith("total")) continue
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 7) continue
            val isDir = parts[0].startsWith("d")
            val size = if (!isDir) parts[4].toLongOrNull() ?: 0L else 0L
            val name = if (parts.size >= 8) parts.subList(7, parts.size).joinToString(" ") else parts.last()
            if (name.isEmpty()) continue
            val remote = (norm.trimEnd('/') + "/" + name)
            result.add(RemoteEntry(name, remote, isDir, size))
        }
        result.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    fun normalizeRemotePath(p: String): String {
        var s = p.replace(Regex("/+"), "/")
        if (!s.endsWith("/")) s += "/"
        return s
    }

    suspend fun push(localFile: File, remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            dadb.push(localFile, remotePath)
            true
        } catch (e: Exception) { false }
    }

    suspend fun pull(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            dadb.pull(localFile, remotePath)
            true
        } catch (e: Exception) { false }
    }

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
