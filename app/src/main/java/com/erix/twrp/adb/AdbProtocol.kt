package com.erix.twrp.adb
object AdbProtocol {
    const val A_CNXN = 0x4e584e43.toInt()
    const val A_AUTH = 0x48545541.toInt()
    const val A_OPEN = 0x4e45504f.toInt()
    const val A_OKAY = 0x59414b4f.toInt()
    const val A_CLSE = 0x45534c43.toInt()
    const val A_WRTE = 0x45545257.toInt()
    const val A_VERSION_MIN = 0x01000000
    const val A_VERSION_SKIP_CHECKSUM = 0x01000001
    const val A_MAX_PAYLOAD = 4096
    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3
}
