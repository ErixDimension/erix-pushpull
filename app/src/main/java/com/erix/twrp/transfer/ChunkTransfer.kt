package com.erix.twrp.transfer
import com.erix.twrp.adb.AdbManager
import com.erix.twrp.storage.LocalStorageManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
class ChunkTransfer(private val adb: AdbManager, private val local: LocalStorageManager, private val resume: ResumeManager, private val serial: String) {
    companion object { const val CHUNK_SIZE = 128L * 1024 * 1024; const val CHUNK_RETRY = 5; const val PART_SUFFIX = ".erix_part"; const val CHUNK_SUFFIX = ".erix_chunk" }
    fun push(src: String, dstDir: String): Flow<TransferEvent> = flow {
        val srcFile = File(src); val name = srcFile.name; val total = srcFile.length(); val finalPath = if (dstDir.endsWith("/")) dstDir.trimEnd('/') + "/" + name else dstDir; val partPath = finalPath + PART_SUFFIX
        val srcId = Identity.fromLocal(src); val dstId = Identity.fromRemote(finalPath)
        var committed = 0L; var transferId = UUID.randomUUID().toString().replace("-", "")
        val cp = resume.load("push", serial)
        if (cp != null && cp.engine == "chunk" && cp.source == src && cp.destination == dstDir && cp.chunkSize == CHUNK_SIZE && cp.sourceIdentity != null && Identity.matches(cp.sourceIdentity, srcId)) { committed = cp.completedBytes; transferId = cp.transferId ?: transferId }
        val actualPartSize = adb.remoteSize(partPath)
        if (committed >= total && actualPartSize >= total) committed = total else { committed = minOf(committed, actualPartSize); committed = (committed / CHUNK_SIZE) * CHUNK_SIZE }
        if (actualPartSize != committed) truncateRemote(partPath, committed)
        if (committed == 0L) { val free = adb.remoteFreeBytes(dstDir); val need = total + minOf(CHUNK_SIZE, total); if (free > 0 && free < need) { emit(TransferEvent.Failure(0, "Ruang tidak cukup")); return@flow } }
        var lastSampleTime = System.currentTimeMillis(); var lastSampleBytes = committed; var speedBps = 0L; var attempt = 0
        val raf = RandomAccessFile(srcFile, "r")
        try {
            while (committed < total) {
                val len = minOf(CHUNK_SIZE, total - committed)
                when (val res = doPushChunk(raf, committed, len, partPath)) {
                    is ChunkResult.Ok -> { committed += len; attempt = 0; resume.save("push", serial, src, dstDir, total, committed, srcId, dstId, partPath, finalPath, transferId, CHUNK_SIZE); val now = System.currentTimeMillis(); val dt = now - lastSampleTime; if (dt >= 500) { val inst = ((committed - lastSampleBytes) * 1000L) / dt; speedBps = if (speedBps <= 0) inst else (speedBps * 6 + inst * 4) / 10; lastSampleBytes = committed; lastSampleTime = now }; emit(TransferEvent.Progress(committed, total, speedBps, if (speedBps > 0) (total - committed) / speedBps else 0)) }
                    is ChunkResult.Disconnect -> { resume.save("push", serial, src, dstDir, total, committed, srcId, dstId, partPath, finalPath, transferId, CHUNK_SIZE); emit(TransferEvent.Disconnected(committed)); val ns = waitForReconnect(); if (ns != serial) { emit(TransferEvent.Failure(committed, "Serial berubah")); return@flow }; emit(TransferEvent.Reconnected(ns)) }
                    is ChunkResult.Fail -> { attempt++; if (attempt > CHUNK_RETRY) { emit(TransferEvent.Failure(committed, "Chunk gagal")); return@flow }; emit(TransferEvent.ChunkRetry(attempt, CHUNK_RETRY, res.why)); delay(2000L * attempt) }
                }
            }
        } finally { raf.close() }
        if (!finalizePush(partPath, finalPath, total)) { emit(TransferEvent.Failure(committed, "finalize gagal")); return@flow }
        resume.clear("push", serial); emit(TransferEvent.Progress(total, total, 0, 0)); emit(TransferEvent.Success(finalPath, total))
    }
    fun pull(srcPath: String, dstDir: String): Flow<TransferEvent> = flow {
        val name = srcPath.trimEnd('/').substringAfterLast('/'); val total = adb.remoteSize(srcPath); val finalPath = File(dstDir, name).absolutePath; val partPath = finalPath + PART_SUFFIX
        val srcId = Identity.fromRemote(srcPath, total); val dstId = Identity.fromLocal(dstDir)
        var committed = 0L; var transferId = UUID.randomUUID().toString().replace("-", "")
        val cp = resume.load("pull", serial)
        if (cp != null && cp.engine == "chunk" && cp.source == srcPath && cp.destination == dstDir && cp.chunkSize == CHUNK_SIZE && cp.sourceIdentity != null && Identity.matches(cp.sourceIdentity, srcId)) { committed = cp.completedBytes; transferId = cp.transferId ?: transferId }
        val partFile = File(partPath)
        if (partFile.exists()) { committed = minOf(committed, partFile.length()); committed = (committed / CHUNK_SIZE) * CHUNK_SIZE; if (partFile.length() != committed) RandomAccessFile(partFile, "rw").use { it.setLength(committed) } } else { committed = 0; File(partPath).parentFile?.mkdirs(); File(partPath).createNewFile() }
        var lastSampleTime = System.currentTimeMillis(); var lastSampleBytes = committed; var speedBps = 0L; var attempt = 0
        while (committed < total) {
            val len = minOf(CHUNK_SIZE, total - committed)
            when (val res = doPullChunk(srcPath, committed, len, partPath)) {
                is ChunkResult.Ok -> { committed += len; attempt = 0; resume.save("pull", serial, srcPath, dstDir, total, committed, srcId, dstId, partPath, finalPath, transferId, CHUNK_SIZE); val now = System.currentTimeMillis(); val dt = now - lastSampleTime; if (dt >= 500) { val inst = ((committed - lastSampleBytes) * 1000L) / dt; speedBps = if (speedBps <= 0) inst else (speedBps * 6 + inst * 4) / 10; lastSampleBytes = committed; lastSampleTime = now }; emit(TransferEvent.Progress(committed, total, speedBps, if (speedBps > 0) (total - committed) / speedBps else 0)) }
                is ChunkResult.Disconnect -> { resume.save("pull", serial, srcPath, dstDir, total, committed, srcId, dstId, partPath, finalPath, transferId, CHUNK_SIZE); emit(TransferEvent.Disconnected(committed)); val ns = waitForReconnect(); if (ns != serial) { emit(TransferEvent.Failure(committed, "Serial berubah")); return@flow }; emit(TransferEvent.Reconnected(ns)) }
                is ChunkResult.Fail -> { attempt++; if (attempt > CHUNK_RETRY) { emit(TransferEvent.Failure(committed, "Chunk gagal")); return@flow }; emit(TransferEvent.ChunkRetry(attempt, CHUNK_RETRY, res.why)); delay(2000L * attempt) }
            }
        }
        val finalFile = File(finalPath); if (finalFile.exists()) { val unique = local.uniqueName(dstDir, name); File(partPath).renameTo(unique) } else File(partPath).renameTo(finalFile)
        resume.clear("pull", serial); emit(TransferEvent.Progress(total, total, 0, 0)); emit(TransferEvent.Success(finalPath, total))
    }
    sealed class ChunkResult { data object Ok : ChunkResult(); data object Disconnect : ChunkResult(); data class Fail(val why: String) : ChunkResult() }
    private suspend fun doPushChunk(raf: RandomAccessFile, offset: Long, len: Long, partPath: String): ChunkResult {
        val devChunk = partPath + CHUNK_SUFFIX; val localChunk = File.createTempFile("erix_chunk", ".tmp")
        try {
            raf.seek(offset); localChunk.outputStream().use { out -> val buf = ByteArray(8 * 1024 * 1024); var rem = len; while (rem > 0) { val n = raf.read(buf, 0, minOf(buf.size.toLong(), rem).toInt()); if (n <= 0) return ChunkResult.Fail("EOF"); out.write(buf, 0, n); rem -= n } }
            adb.shell("rm -f '${q(devChunk)}'"); if (!adb.push(localChunk, devChunk)) return ChunkResult.Fail("Push chunk gagal")
            if (adb.remoteSize(devChunk) != len) return ChunkResult.Fail("Size mismatch")
            adb.shell("cat '${q(devChunk)}' >> '${q(partPath)}' && rm -f '${q(devChunk)}'")
            if (adb.remoteSize(partPath) != offset + len) return ChunkResult.Fail("Merge gagal")
            return ChunkResult.Ok
        } finally { localChunk.delete() }
    }
    private suspend fun doPullChunk(srcPath: String, offset: Long, len: Long, partPath: String): ChunkResult {
        val devChunk = if (srcPath.startsWith("/sdcard") || srcPath.startsWith("/storage/emulated") || srcPath.startsWith("/data/media")) "/sdcard/.erix_chunk" else srcPath.substringBeforeLast('/') + "/.erix_chunk"
        val localChunk = File(File(partPath).parentFile, ".erix_chunk.tmp"); val skipMb = offset / (1024 * 1024); val countMb = (len + (1024 * 1024) - 1) / (1024 * 1024)
        adb.shell("rm -f '${q(devChunk)}'; dd if='${q(srcPath)}' of='${q(devChunk)}' bs=1M skip=$skipMb count=$countMb 2>/dev/null")
        if (adb.remoteSize(devChunk) != len) { adb.shell("rm -f '${q(devChunk)}'"); return ChunkResult.Fail("dd chunk size mismatch") }
        if (!adb.pull(devChunk, localChunk)) return ChunkResult.Fail("Pull chunk gagal")
        if (localChunk.length() != len) return ChunkResult.Fail("Local chunk size mismatch")
        
        // --- PERBAIKAN: FileOutputStream untuk append (karena appendBytes tidak ada di Kotlin) ---
        java.io.FileOutputStream(partPath, true).use { it.write(localChunk.readBytes()) }
        
        localChunk.delete(); adb.shell("rm -f '${q(devChunk)}'")
        if (File(partPath).length() != offset + len) return ChunkResult.Fail("Append gagal")
        return ChunkResult.Ok
    }
    private suspend fun truncateRemote(path: String, size: Long) { if (size <= 0) adb.shell(": > '${q(path)}'") else adb.shell("truncate -s $size '${q(path)}' 2>/dev/null || dd if=/dev/null of='${q(path)}' bs=1048576 seek=${size / (1024 * 1024)} 2>/dev/null") }
    private suspend fun finalizePush(partPath: String, finalPath: String, total: Long): Boolean {
        val dir = finalPath.substringBeforeLast('/'); val base = finalPath.substringAfterLast('/'); val stem = base.substringBeforeLast('.', base); val ext = if (base.contains('.')) "." + base.substringAfterLast('.') else ""
        val candidates = mutableListOf(finalPath); for (n in 2..10) candidates.add("$dir/$stem ($n)$ext")
        for (cand in candidates) { val out = adb.shell("mv -f '${q(partPath)}' '${q(cand)}' 2>&1; echo ERIX_RC=$?"); if ("ERIX_RC=0" in out) { if (adb.remoteSize(cand) == total) return true }; if ("Permission denied" !in adb.shell("ls -ld '${q(cand)}' 2>&1")) break }
        return false
    }
    private suspend fun waitForReconnect(): String = com.erix.twrp.adb.AdbDeviceWatcher.awaitDevice().deviceName ?: "-"
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
