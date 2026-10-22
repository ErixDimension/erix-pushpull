package com.erix.twrp.transfer
import com.erix.twrp.adb.AdbManager
import com.erix.twrp.storage.LocalStorageManager
import kotlinx.coroutines.flow.*
import java.io.File
class SimpleTransfer(private val adb: AdbManager, private val local: LocalStorageManager, private val serial: String) {
    fun push(src: String, dstDir: String): Flow<TransferEvent> = flow {
        val total = local.sizeOf(src); val srcFile = File(src); val isDir = srcFile.isDirectory; val name = srcFile.name
        emit(TransferEvent.Progress(0, total, 0, 0))
        try {
            if (isDir) {
                adb.shell("mkdir -p '${dstDir.trimEnd('/')}/$name'")
                srcFile.walkTopDown().forEach { f -> if (f.isFile) { val rel = f.relativeTo(srcFile).path; adb.shell("mkdir -p '${dstDir.trimEnd('/')}/$name/${rel.substringBeforeLast('/')}'"); adb.push(f, "${dstDir.trimEnd('/')}/$name/$rel") } }
            } else { adb.push(srcFile, dstDir.trimEnd('/') + "/" + name) }
            emit(TransferEvent.Progress(total, total, 0, 0)); emit(TransferEvent.Success(dstDir, total))
        } catch (e: Exception) { emit(TransferEvent.Failure(0, e.message ?: "error")) }
    }
    fun pull(srcPath: String, dstDir: String): Flow<TransferEvent> = flow {
        try {
            val total = adb.remoteSize(srcPath); val name = srcPath.trimEnd('/').substringAfterLast('/'); val localPath = File(dstDir, name).absolutePath
            emit(TransferEvent.Progress(0, total, 0, 0))
            if (!adb.pull(srcPath, File(localPath))) { emit(TransferEvent.Failure(0, "pull failed")); return@flow }
            emit(TransferEvent.Progress(total, total, 0, 0)); emit(TransferEvent.Success(localPath, total))
        } catch (e: Exception) { emit(TransferEvent.Failure(0, e.message ?: "error")) }
    }
}
