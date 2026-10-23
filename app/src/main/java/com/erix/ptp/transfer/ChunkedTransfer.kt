package com.erix.ptp.transfer

import android.content.Context
import com.erix.ptp.ptp.PtpUsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

class ChunkedTransfer(
    private val ctx: Context,
    private val transport: PtpUsbTransport,
    private val storageId: Int
) {
    companion object {
        const val CHUNK_SIZE = 64L * 1024 * 1024
        const val THRESHOLD = 100L * 1024 * 1024
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 5000L
    }

    sealed class Result {
        data class Success(val chunks: Int) : Result()
        data class Cancelled(val bytesSent: Long) : Result()
        data class Failed(val bytesSent: Long, val reason: String) : Result()
    }

    suspend fun uploadResumable(
        localFile: File,
        destHandle: Int,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean
    ): Result = withContext(Dispatchers.IO) {
        val totalSize = localFile.length()
        val chunkSize = CHUNK_SIZE
        val totalChunks = ((totalSize + chunkSize - 1) / chunkSize).toInt()

        onLog("Mulai upload: ${localFile.name} (${fmt(totalSize)}, $totalChunks bagian)")

        val prev = ResumeState.load(ctx, localFile.name)
        val uploaded = if (prev != null && prev.fileSize == totalSize && prev.destHandle == destHandle) {
            onLog("↻ Resume: ${prev.uploadedChunks.size}/${prev.totalChunks} bagian sudah")
            prev.uploadedChunks.toMutableSet()
        } else {
            mutableSetOf<Int>()
        }

        var uploadedBytes = uploaded.sumOf { idx ->
            val start = idx.toLong() * chunkSize
            minOf(chunkSize, totalSize - start)
        }

        for (chunkIdx in 0 until totalChunks) {
            if (isCancelled()) {
                onLog("⊘ Dibatalkan")
                return@withContext Result.Cancelled(uploadedBytes)
            }
            if (chunkIdx in uploaded) {
                onLog("⏭ Bagian ${chunkIdx + 1}/$totalChunks sudah ada")
                continue
            }

            val start = chunkIdx.toLong() * chunkSize
            val end = minOf(start + chunkSize, totalSize)
            val len = end - start
            val suffix = "%03d".format(chunkIdx + 1)
            val remoteName = "${localFile.name}.$suffix"

            val tempChunk = File(ctx.cacheDir, "erix_chunk_${localFile.name}_$suffix")
            try {
                RandomAccessFile(localFile, "r").use { src ->
                    src.seek(start)
                    tempChunk.outputStream().use { out ->
                        val buf = ByteArray(8 * 1024 * 1024)
                        var rem = len
                        while (rem > 0) {
                            val n = src.read(buf, 0, minOf(buf.size.toLong(), rem).toInt())
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            rem -= n
                        }
                    }
                }

                var retry = 0
                var ok = false
                while (retry <= MAX_RETRIES && !ok) {
                    try {
                        onLog("📤 Bagian ${chunkIdx + 1}/$totalChunks (${fmt(len)})...")
                        transport.pushToFolder(
                            storageId = storageId,
                            localFile = tempChunk,
                            remoteName = remoteName,
                            parentHandle = destHandle,
                            onProgress = { sent, _ -> onProgress(uploadedBytes + sent, totalSize) },
                            isCancelled = { isCancelled() }
                        )
                        ok = true
                    } catch (e: Exception) {
                        retry++
                        if (retry > MAX_RETRIES) {
                            onLog("❌ Bagian ${chunkIdx + 1} gagal")
                            return@withContext Result.Failed(uploadedBytes, e.message ?: "gagal")
                        }
                        onLog("⚠ Coba lagi ${retry}/$MAX_RETRIES")
                        delay(RETRY_DELAY_MS)
                    }
                }

                uploaded.add(chunkIdx)
                uploadedBytes += len
                onProgress(uploadedBytes, totalSize)

                ResumeState.save(ctx, ResumeState(
                    action = "upload",
                    localPath = localFile.absolutePath,
                    fileName = localFile.name,
                    fileSize = totalSize,
                    chunkSize = chunkSize,
                    totalChunks = totalChunks,
                    uploadedChunks = uploaded.toSet(),
                    destHandle = destHandle,
                    timestamp = System.currentTimeMillis()
                ))
                onLog("✓ Bagian ${chunkIdx + 1}/$totalChunks (${fmt(uploadedBytes)} / ${fmt(totalSize)})")
            } finally {
                tempChunk.delete()
            }
        }

        ResumeState.clear(ctx, localFile.name)
        onLog("✅ Selesai! $totalChunks bagian terkirim.")
        Result.Success(totalChunks)
    }

    suspend fun uploadDirect(
        localFile: File,
        destHandle: Int,
        onProgress: (Long, Long) -> Unit,
        onLog: (String) -> Unit,
        isCancelled: () -> Boolean
    ): Result = withContext(Dispatchers.IO) {
        onLog("Mulai upload langsung: ${localFile.name} (${fmt(localFile.length())})")
        try {
            transport.pushToFolder(
                storageId = storageId,
                localFile = localFile,
                remoteName = localFile.name,
                parentHandle = destHandle,
                onProgress = { sent, total ->
                    if (isCancelled()) throw Exception("Dibatalkan user")
                    onProgress(sent, total)
                },
                isCancelled = { isCancelled() }
            )
            onLog("✅ Upload selesai")
            Result.Success(1)
        } catch (e: Exception) {
            onLog("❌ Gagal: ${e.message}")
            Result.Failed(0, e.message ?: "gagal")
        }
    }

    private fun fmt(b: Long): String = when {
        b < 1024L -> "$b B"
        b < 1024L*1024 -> "%.1f KB".format(b / 1024.0)
        b < 1024L*1024*1024 -> "%.1f MB".format(b / (1024.0*1024))
        else -> "%.2f GB".format(b / (1024.0*1024*1024))
    }
}
