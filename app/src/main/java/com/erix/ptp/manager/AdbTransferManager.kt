package com.erix.ptp.manager

import com.erix.ptp.ptp.AdbUsbTransport
import com.erix.ptp.transfer.ProgressTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * AdbTransferManager — orkestrasi upload/download dengan progress callback.
 */
class AdbTransferManager(
    private val transport: AdbUsbTransport,
    private val conflictResolver: ConflictResolver
) {
    data class Result(
        val success: Boolean,
        val finalPath: String,
        val bytesTransferred: Long,
        val errorMessage: String? = null
    )

    suspend fun upload(
        localFile: File,
        remoteDir: String,
        tracker: ProgressTracker,
        onProgress: (ProgressTracker.Snapshot) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        try {
            if (!localFile.exists()) {
                return@withContext Result(false, "", 0L, "File lokal tidak ada: ${localFile.name}")
            }
            val finalPath = conflictResolver.resolveRemote(remoteDir, localFile.name)
            val totalSize = localFile.length()
            tracker.start(totalSize)
            onProgress(tracker.snapshot())
            val r = transport.push(localFile, finalPath) { sent, total ->
                tracker.update(sent, total)
                onProgress(tracker.snapshot())
            }
            if (r.isSuccess) Result(true, finalPath, totalSize)
            else Result(false, "", 0L, r.exceptionOrNull()?.message ?: "Upload gagal")
        } catch (e: Throwable) {
            Result(false, "", 0L, e.message ?: "Upload error")
        }
    }

    suspend fun download(
        remotePath: String,
        localDir: File,
        expectedSize: Long,
        tracker: ProgressTracker,
        onProgress: (ProgressTracker.Snapshot) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        try {
            localDir.mkdirs()
            val filename = remotePath.substringAfterLast('/').ifEmpty { "file" }
            val localFile = conflictResolver.resolveLocal(localDir, filename)
            tracker.start(expectedSize)
            onProgress(tracker.snapshot())
            val r = transport.pull(remotePath, localFile, expectedSize) { received, total ->
                tracker.update(received, total)
                onProgress(tracker.snapshot())
            }
            if (r.isSuccess) Result(true, localFile.absolutePath, localFile.length())
            else Result(false, "", 0L, r.exceptionOrNull()?.message ?: "Download gagal")
        } catch (e: Throwable) {
            Result(false, "", 0L, e.message ?: "Download error")
        }
    }
}
