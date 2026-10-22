package com.erix.twrp.adb

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * Compatibility adapter based on observed Bugjaeger Java wrapper.
 *
 * This implements the observed request framing only.
 * It does not implement the native libadb-sixo.so API.
 */
class BugjaegerCompatClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5038,
    private val connectTimeoutMs: Int = 2500,
    private val readTimeoutMs: Int = 5000
) : Closeable {

    private var socket: Socket? = null

    companion object {
        private const val MAX_RESPONSE = 1024 * 1024

        /**
         * Observed Java wrapper:
         * 4 hexadecimal length chars + service + "sxtoken:" + token.
         *
         * The original length formula uses String.length.
         * Restricting this implementation to ASCII makes character
         * length equal to UTF-8 byte length for the framed payload.
         */
        fun buildRequest(service: String, token: String): ByteArray {
            require(service.isNotBlank()) { "Service tidak boleh kosong" }
            require(token.isNotBlank()) { "Token sxtoken belum diisi" }

            val payloadText = service + "sxtoken:" + token

            require(payloadText.all { it.code in 0x20..0x7e }) {
                "Service/token harus berupa karakter ASCII yang dapat dicetak"
            }

            val payload = payloadText.toByteArray(StandardCharsets.UTF_8)
            require(payload.size <= 0xffff) { "Request terlalu panjang" }

            val prefix = String.format(
                java.util.Locale.ROOT,
                "%04x",
                payload.size
            )

            return prefix.toByteArray(StandardCharsets.US_ASCII) + payload
        }
    }

    private fun readExact(input: BufferedInputStream, size: Int): ByteArray {
        require(size >= 0 && size <= MAX_RESPONSE) {
            "Ukuran respons tidak valid: $size"
        }

        val out = ByteArray(size)
        var offset = 0

        while (offset < size) {
            val count = input.read(out, offset, size - offset)
            if (count < 0) throw EOFException("Koneksi ditutup sebelum data lengkap")
            if (count == 0) continue
            offset += count
        }

        return out
    }

    private fun readProtocolString(input: BufferedInputStream): String {
        val prefix = String(
            readExact(input, 4),
            StandardCharsets.US_ASCII
        )

        if (!prefix.matches(Regex("[0-9a-fA-F]{4}"))) {
            throw IOException("Prefix panjang respons tidak valid")
        }

        val length = prefix.toInt(16)
        if (length > MAX_RESPONSE) {
            throw IOException("Respons terlalu besar: $length byte")
        }

        return String(
            readExact(input, length),
            StandardCharsets.UTF_8
        )
    }

    /**
     * One request per connection, matching the observed adbQuery pattern.
     * Does not log or return the token itself.
     */
    fun query(service: String, token: String): String {
        close()

        val s = Socket()
        socket = s

        try {
            s.connect(
                InetSocketAddress(host, port),
                connectTimeoutMs
            )
            s.soTimeout = readTimeoutMs

            val output = BufferedOutputStream(s.getOutputStream())
            val input = BufferedInputStream(s.getInputStream())

            output.write(buildRequest(service, token))
            output.flush()

            val status = String(
                readExact(input, 4),
                StandardCharsets.US_ASCII
            )

            when (status) {
                "OKAY" -> return readProtocolString(input)
                "FAIL" -> {
                    val message = try {
                        readProtocolString(input)
                    } catch (_: Exception) {
                        "Server mengirim FAIL tanpa pesan yang terbaca"
                    }
                    throw IOException("ADB FAIL: $message")
                }
                else -> throw IOException("Status respons tidak dikenal: $status")
            }
        } finally {
            close()
        }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: Exception) {
        } finally {
            socket = null
        }
    }
}
