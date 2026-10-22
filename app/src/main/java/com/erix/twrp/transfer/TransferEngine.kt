package com.erix.twrp.transfer
import com.erix.twrp.adb.AdbManager
import com.erix.twrp.storage.LocalStorageManager
import com.erix.twrp.storage.RemoteStorageManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
class TransferEngine(private val adb: AdbManager, private val local: LocalStorageManager, private val remote: RemoteStorageManager, private val resume: ResumeManager, private val serial: String) {
    companion object { const val SMALL_FILE_THRESHOLD = 5L * 1024 * 1024 }
    
    fun transfer(action: String, src: String, dst: String): Flow<TransferEvent> = flow {
        val isDir: Boolean
        val size: Long
        if (action == "push") {
            val f = java.io.File(src)
            isDir = f.isDirectory
            size = local.sizeOf(src)
        } else {
            isDir = adb.remoteIsDir(src)
            size = adb.remoteSizeOrDirSize(src)
        }
        
        if (isDir || size < SMALL_FILE_THRESHOLD) {
            val st = SimpleTransfer(adb, local, serial)
            if (action == "push") emitAll(st.push(src, dst)) else emitAll(st.pull(src, dst))
        } else {
            val ct = ChunkTransfer(adb, local, resume, serial)
            if (action == "push") emitAll(ct.push(src, dst)) else emitAll(ct.pull(src, dst))
        }
    }
}
