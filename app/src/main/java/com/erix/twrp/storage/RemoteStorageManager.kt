package com.erix.twrp.storage
import com.erix.twrp.adb.AdbManager
class RemoteStorageManager(private val adb: AdbManager) {
    suspend fun list(path: String) = adb.listDir(adb.normalizeRemotePath(path)).map { StorageItem(it.name, it.path, it.isDir, it.size) }
    suspend fun sizeOf(path: String) = adb.remoteSizeOrDirSize(path)
    suspend fun freeBytes(path: String) = adb.remoteFreeBytes(path)
    suspend fun isDir(path: String) = adb.remoteIsDir(path)
    suspend fun exists(path: String) = adb.remoteExists(path)
    suspend fun mkdirs(path: String) = adb.shell("mkdir -p ${q(path)} && echo OK").contains("OK")
    suspend fun delete(path: String) = adb.shell("rm -rf ${q(path)} && echo OK").contains("OK")
    private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
