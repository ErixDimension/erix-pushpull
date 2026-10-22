package com.erix.twrp.storage
import com.erix.twrp.adb.AdbManager
class RemoteStorageManager(private val adb: AdbManager) {
    suspend fun list(path: String) = adb.listDir(adb.normalizeRemotePath(path)).map { StorageItem(it.name, it.path, it.isDir, it.size) }
    suspend fun mkdirs(path: String) = adb.shell("mkdir -p '${path}' && echo OK").contains("OK")
}
