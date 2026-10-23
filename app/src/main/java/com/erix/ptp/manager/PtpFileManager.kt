package com.erix.ptp.manager

import com.erix.ptp.data.FileEntry
import com.erix.ptp.ptp.PtpUsbTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PtpFileManager(
    private val transport: PtpUsbTransport,
    private val storageId: Int
) {
    fun transport(): PtpUsbTransport = transport
    fun storageId(): Int = storageId
    fun rootHandle(): Int = PtpUsbTransport.PARENT_ROOT

    data class StorageInfo(val totalBytes: Long, val freeBytes: Long, val description: String)

    suspend fun getStorageInfo(): StorageInfo? = withContext(Dispatchers.IO) {
        try {
            val info = transport.getStorageInfo(storageId)
            StorageInfo(info.totalBytes, info.freeBytes, info.description)
        } catch (_: Exception) { null }
    }

    suspend fun list(parentHandle: Int): List<FileEntry.Remote> = withContext(Dispatchers.IO) {
        transport.listDir(storageId, parentHandle).map { obj ->
            FileEntry.Remote(
                name = obj.name,
                handle = obj.handle,
                parentHandle = obj.parentHandle,
                size = obj.size,
                isDir = obj.isDir
            )
        }.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    suspend fun upload(
        localFile: File,
        parentHandle: Int,
        onProgress: (Long, Long) -> Unit = {_,_->},
        isCancelled: () -> Boolean = { false }
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            transport.pushToFolder(storageId, localFile, localFile.name, parentHandle,
                { s, t -> onProgress(s, t) }, isCancelled)
        } catch (_: Exception) { false }
    }

    suspend fun download(
        handle: Int,
        destFile: File,
        onProgress: (Long, Long) -> Unit = {_,_->},
        isCancelled: () -> Boolean = { false }
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            transport.pullByHandle(handle, destFile,
                { r, t -> onProgress(r, t) }, isCancelled)
        } catch (_: Exception) { false }
    }

    suspend fun walkFolder(folderHandle: Int): List<FileEntry.Remote> = withContext(Dispatchers.IO) {
        val result = mutableListOf<FileEntry.Remote>()
        val queue = mutableListOf(folderHandle)
        var safety = 0
        while (queue.isNotEmpty() && safety < 10000) {
            safety++
            val h = queue.removeAt(0)
            try {
                val children = list(h)
                for (c in children) {
                    if (c.isDir) queue.add(c.handle)
                    else result.add(c)
                }
            } catch (_: Exception) {}
        }
        result
    }
}
