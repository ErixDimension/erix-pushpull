package com.erix.twrp.adb
import android.security.keystore.*
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
class AdbCrypto(private val alias: String = "erix_adb_key") {
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val keyPair: KeyPair by lazy {
        if (!ks.containsAlias(alias)) { 
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore")
            gen.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .build())
            gen.generateKeyPair() 
        }
        KeyPair(ks.getCertificate(alias).publicKey, ks.getKey(alias, null) as PrivateKey)
    }
    fun sign(token: ByteArray): ByteArray? = try { 
        Signature.getInstance("SHA256withRSA").apply { initSign(keyPair.private); update(token) }.sign() 
    } catch (_: Exception) { null }
    fun publicKeyBytes(): ByteArray {
        val pub = keyPair.public as RSAPublicKey
        var modulusBytes = pub.modulus.toByteArray()
        if (modulusBytes.size > 1 && modulusBytes[0] == 0.toByte()) modulusBytes = modulusBytes.copyOfRange(1, modulusBytes.size)
        val nWords = (modulusBytes.size + 3) / 4
        val paddedMod = ByteArray(nWords * 4)
        for (i in modulusBytes.indices) paddedMod[i] = modulusBytes[modulusBytes.size - 1 - i]
        val n0 = (paddedMod[0].toLong() and 0xFF) or ((paddedMod[1].toLong() and 0xFF) shl 8) or ((paddedMod[2].toLong() and 0xFF) shl 16) or ((paddedMod[3].toLong() and 0xFF) shl 24)
        var x = n0; for (i in 0 until 5) { x *= (2 - n0 * x) }
        val n0inv = (-x).toInt()
        val bigN = BigInteger(1, modulusBytes); val bigR = BigInteger.ONE.shiftLeft(nWords * 32)
        var rrBytes = bigR.multiply(bigR).mod(bigN).toByteArray()
        if (rrBytes[0] == 0.toByte()) rrBytes = rrBytes.copyOfRange(1, rrBytes.size)
        val rrPadded = ByteArray(nWords * 4); for (i in rrBytes.indices) rrPadded[i] = rrBytes[rrBytes.size - 1 - i]
        val bb = ByteBuffer.allocate(4 + 4 + nWords * 4 + nWords * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(nWords); bb.putInt(n0inv); bb.put(paddedMod); bb.put(rrPadded); bb.putInt(pub.publicExponent.toInt())
        return android.util.Base64.encodeToString(bb.array(), android.util.Base64.NO_WRAP).toByteArray(Charsets.UTF_8) + " erix@android\u0000".toByteArray(Charsets.UTF_8)
    }
}
