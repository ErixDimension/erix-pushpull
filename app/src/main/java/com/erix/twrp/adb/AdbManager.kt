package com.erix.twrp.adb
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*

class AdbManager(private val context: Context, private val transport: AdbUsbTransport) {
    suspend fun shell(cmd: String): String = withContext(Dispatchers.IO) {
        val id = transport.openService("shell:$cmd")
        try { String(transport.readAll(id), Charsets.UTF_8).trim() } finally { transport.closeStream(id) }
    }
    suspend fun remoteSize(path: String): Long = shell("stat -c %s '${q(path)}' 2>/dev/null").trim().toLongOrNull() ?: 0L
    suspend fun remoteIsDir(path: String): Boolean = "Y" in shell("[ -d ${q(path)} ] && echo Y || echo N")
    suspend fun remoteExists(path: String): Boolean = "Y" in shell("[ -e ${q(path)} ] && echo Y || echo N")
    suspend fun remoteSizeOrDirSize(path: String): Long = shell("if [ -d ${q(path)} ]; then du -sb ${q(path)} 2>/dev/null | cut -f1; elif [ -f ${q(path)} ]; then stat -c %s ${q(path)} 2>/dev/null; else echo 0; fi").lines().mapNotNull { it.trim().toLongOrNull() }.firstOrNull() ?: 0L
    suspend fun remoteFreeBytes(path: String): Long { val n = shell("df -k ${q(path)} 2>/dev/null | tail -1").split(Regex("\\s+")).mapNotNull { it.toLongOrNull() }; return if (n.size >= 3) n[2] * 1024 else 0L }
    
    data class RemoteEntry(val name: String, val path: String, val isDir: Boolean, val size: Long)
    suspend fun listDir(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val norm = normalizeRemotePath(path); val out = shell("ls -ln ${q(norm)}"); val res = mutableListOf<RemoteEntry>()
        for (line in out.lines()) { if (line.isBlank() || line.startsWith("total")) continue; val p = line.trim().split(Regex("\\s+")); if (p.size < 7) continue; val isDir = p[0].startsWith("d"); val size = if (!isDir) p[4].toLongOrNull() ?: 0L else 0L; val name = if (p.size >= 8) p.subList(7, p.size).joinToString(" ") else p.last(); if (name.isEmpty()) continue; res.add(RemoteEntry(name, (norm.trimEnd('/') + "/" + name), isDir, size)) }
        res.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }
    fun normalizeRemotePath(p: String): String { var s = p.replace(Regex("/+"), "/"); if (!s.endsWith("/")) s += "/"; return s }
    
    suspend fun push(localFile: File, remotePath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val id = transport.openService("sync:")
            transport.writeStream(id, ("SEND" + "%08x".format(remotePath.length) + remotePath + ",33206").toByteArray())
            val buf = ByteArray(65536); val file = RandomAccessFile(localFile, "r"); var sent = 0L; val total = localFile.length()
            while (sent < total) { val n = file.read(buf, 0, minOf(buf.size.toLong(), total - sent).toInt()); if (n <= 0) break; transport.writeStream(id, "DATA".toByteArray() + "%04x".format(n).toByteArray()); transport.writeStream(id, buf.copyOfRange(0, n)); sent += n }
            file.close(); transport.writeStream(id, "DONE".toByteArray() + "%04x".format((localFile.lastModified() / 1000).toInt()).toByteArray())
            val resp = String(transport.readAll(id), Charsets.UTF_8); transport.closeStream(id)
            !resp.contains("FAIL")
        } catch (e: Exception) { false }
    }
    suspend fun pull(remotePath: String, localFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val id = transport.openService("sync:")
            transport.writeStream(id, ("RECV" + "%08x".format(remotePath.length) + remotePath).toByteArray())
            val file = localFile.outputStream(); var received = 0L
            while (true) { val data = transport.readAll(id); if (data.isEmpty()) break; var off = 0; while (off + 8 <= data.size) { val hdr = String(data, off, 4, Charsets.US_ASCII); val len = String(data, off + 4, 4, Charsets.US_ASCII).toIntOrNull(16) ?: break; off += 8; when (hdr) { "DATA" -> { if (off + len > data.size) break; file.write(data, off, len); off += len; received += len }; "DONE" -> { file.flush(); transport.closeStream(id); return@withContext true }; "FAIL" -> { transport.closeStream(id); return@withContext false }; else -> break } }; if (off < data.size) break }
            transport.closeStream(id); true
        } catch (e: Exception) { false }
    }
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
