package com.erix.ptp.manager

import com.erix.ptp.ptp.AdbUsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ConflictResolver — auto-rename kalau file sudah ada.
 * video.mp4 → video (1).mp4 → video (2).mp4
 */
class ConflictResolver(private val transport: AdbUsbTransport) {

    private fun split(name: String): Pair<String, String> {
        val idx = name.lastIndexOf('.')
        return if (idx > 0) name.substring(0, idx) to name.substring(idx)
        else name to ""
    }

    suspend fun resolveRemote(remoteDir: String, filename: String): String = withContext(Dispatchers.IO) {
        val normalized = remoteDir.trimEnd('/').ifEmpty { "/" }
        val original = "$normalized/$filename"
        if (!existsRemote(original)) return@withContext original
        val (stem, ext) = split(filename)
        for (n in 1..99) {
            val candidate = "$normalized/$stem ($n)$ext"
            if (!existsRemote(candidate)) return@withContext candidate
        }
        "$normalized/$stem (${System.currentTimeMillis()})$ext"
    }

    private suspend fun existsRemote(path: String): Boolean {
        val out = try {
            transport.exec("ls '$path' 2>/dev/null")
        } catch (_: Throwable) { return false }
        return out.isNotBlank() && !out.contains("No such file")
    }

    fun resolveLocal(dir: File, filename: String): File {
        val original = File(dir, filename)
        if (!original.exists()) return original
        val (stem, ext) = split(filename)
        for (n in 1..99) {
            val candidate = File(dir, "$stem ($n)$ext")
            if (!candidate.exists()) return candidate
        }
        return File(dir, "$stem (${System.currentTimeMillis()})$ext")
    }
}
