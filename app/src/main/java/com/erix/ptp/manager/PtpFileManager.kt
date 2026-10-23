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

    suspend fun upload(localFile: File, parentHandle: Int, onProgress: (Long, Long) -> Unit = {_,_->}): Boolean = withContext(Dispatchers.IO) {
        try {
            transport.pushToFolder(storageId, localFile, localFile.name, parentHandle) { s, t -> onProgress(s, t) }
        } catch (_: Exception) { false }
    }

    suspend fun download(handle: Int, destFile: File, onProgress: (Long, Long) -> Unit = {_,_->}): Boolean = withContext(Dispatchers.IO) {
        try {
            transport.pullByHandle(handle, destFile) { r, t -> onProgress(r, t) }
        } catch (_: Exception) { false }
    }

    /** Walk folder recursively, return semua file (bukan folder). */
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

    fun transport(): PtpUsbTransport = transport
    fun storageId(): Int = storageId

    fun rootHandle(): Int = PtpUsbTransport.PARENT_ROOT
}
