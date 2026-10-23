package com.erix.ptp.ptp
import android.security.keystore.*
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.interfaces.RSAPublicKey

class AdbCrypto(private val alias: String = "erix_adb_key") {
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val keyPair: KeyPair by lazy {
        if (!ks.containsAlias(alias)) {
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            gen.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build())
            gen.generateKeyPair()
        }
        KeyPair(ks.getCertificate(alias).publicKey, ks.getKey(alias, null) as PrivateKey)
    }
    fun sign(token: ByteArray): ByteArray? = try {
        Signature.getInstance("SHA256withRSA").apply { initSign(keyPair.private); update(token) }.sign()
    } catch (_: Exception) { null }
    
    fun publicKeyBytes(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        var modBytes = pub.modulus.toByteArray()
        if (modBytes.size > 1 && modBytes[0] == 0.toByte()) modBytes = modBytes.copyOfRange(1, modBytes.size)
        val nWords = (modBytes.size + 3) / 4
        val modPad = ByteArray(nWords * 4)
        for (i in modBytes.indices) modPad[i] = modBytes[modBytes.size - 1 - i]
        val n0 = (modPad[0].toLong() and 0xFF) or ((modPad[1].toLong() and 0xFF) shl 8) or ((modPad[2].toLong() and 0xFF) shl 16) or ((modPad[3].toLong() and 0xFF) shl 24)
        var x = n0; for (i in 0 until 5) { x *= (2 - n0 * x) }
        val n0inv = (-x).toInt()
        val bigN = BigInteger(1, modBytes); val bigR = BigInteger.ONE.shiftLeft(nWords * 32)
        var rrBytes = bigR.multiply(bigR).mod(bigN).toByteArray(); if (rrBytes[0] == 0.toByte()) rrBytes = rrBytes.copyOfRange(1, rrBytes.size)
        val rrPad = ByteArray(nWords * 4); for (i in rrBytes.indices) rrPad[i] = rrBytes[rrBytes.size - 1 - i]
        val bb = ByteBuffer.allocate(4 + 4 + nWords * 4 + nWords * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(nWords); bb.putInt(n0inv); bb.put(modPad); bb.put(rrPad); bb.putInt(pub.publicExponent.toInt())
        return android.util.Base64.encodeToString(bb.array(), android.util.Base64.NO_WRAP).toByteArray() + " erix@android\u0000".toByteArray()
    }
}
