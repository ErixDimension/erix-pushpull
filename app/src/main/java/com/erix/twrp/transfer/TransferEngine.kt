package com.erix.twrp.transfer
import com.erix.twrp.adb.AdbManager
import com.erix.twrp.storage.LocalStorageManager
import com.erix.twrp.storage.RemoteStorageManager
import kotlinx.coroutines.flow.Flow
class TransferEngine(private val adb: AdbManager, private val local: LocalStorageManager, private val remote: RemoteStorageManager, private val resume: ResumeManager, private val serial: String) {
    companion object { const val SMALL_FILE_THRESHOLD = 5L * 1024 * 1024 }
    fun transfer(action: String, src: String, dst: String): Flow<TransferEvent> {
        val isDir: Boolean; val size: Long
        if (action == "push") { val f = java.io.File(src); isDir = f.isDirectory; size = local.sizeOf(src) } else { isDir = adb.remoteIsDir(src); size = adb.remoteSizeOrDirSize(src) }
        if (isDir || size < SMALL_FILE_THRESHOLD) { val st = SimpleTransfer(adb, local, serial); return if (action == "push") st.push(src, dst) else st.pull(src, dst) }
        val ct = ChunkTransfer(adb, local, resume, serial); return if (action == "push") ct.push(src, dst) else ct.pull(src, dst)
    }
}
