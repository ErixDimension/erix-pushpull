package com.erix.twrp.adb

import com.erix.twrp.util.DebugLogger
import kotlinx.coroutines.*
import java.io.*
import java.net.*

class ErixAdbServer(private val bridge: AdbUsbBridge) {
    private var serverSocket: ServerSocket? = null

    fun start() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                serverSocket = ServerSocket(5037, 50, InetAddress.getByName("127.0.0.1"))
                DebugLogger.log("[SERVER] Berjalan di localhost:5037")
                while (true) {
                    val client = serverSocket?.accept() ?: break
                    DebugLogger.log("[SERVER] Client terhubung!")
                    handleClient(client)
                }
            } catch (e: Exception) {
                DebugLogger.log("[SERVER] Error: ${e.message}")
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            
            while (true) {
                // Baca 4 byte hex (panjang payload)
                val lenBuf = ByteArray(4)
                if (input.read(lenBuf) != 4) break
                val len = String(lenBuf).toInt(16)
                
                // Baca payload
                val payloadBuf = ByteArray(len)
                if (input.read(payloadBuf) != len) break
                val service = String(payloadBuf)
                DebugLogger.log("[SERVER] Request: $service")

                // Sederhana: kita teruskan ke USB
                output.write("OKAY".toByteArray())
                output.write(("0000").toByteArray()) // Dummy len
                output.flush()
            }
        } catch (e: Exception) {
            DebugLogger.log("[SERVER] Client putus: ${e.message}")
        } finally {
            socket.close()
        }
    }

    fun stop() { try { serverSocket?.close() } catch (_: Exception) {} }
}
