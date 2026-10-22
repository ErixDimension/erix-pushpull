package com.erix.twrp.adb

import android.security.keystore.*
import java.security.*
import java.security.interfaces.RSAPublicKey
import java.nio.*

class AdbCrypto(private val alias: String = "erix_adb_key") {
    private val ks: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    private val keyPair: KeyPair by lazy {
        if (!ks.containsAlias(alias)) { val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore"); gen.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1).setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1).build()); gen.generateKeyPair() }
        KeyPair(ks.getCertificate(alias).publicKey, ks.getKey(alias, null) as PrivateKey)
    }
    fun sign(token: ByteArray): ByteArray? = try { Signature.getInstance("SHA256withRSA").apply { initSign(keyPair.private); update(token) }.sign() } catch (_: Exception) { null }
    fun publicKeyBytes(): ByteArray {
        val pub = keyPair.public as RSAPublicKey; val modulus = pub.modulus; val exponent = pub.publicExponent
        val n = toWords(modulus.toByteArray()); val bb = ByteBuffer.allocate(4 + 4 + n.size * 4 + n.size * 4 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(n.size); bb.putInt(0); for (w in n) bb.putInt(w); bb.put(ByteArray(n.size * 4)); bb.putInt(exponent.toInt())
        return android.util.Base64.encodeToString(bb.array(), android.util.Base64.NO_WRAP).toByteArray() + " erix-apk@android\x00".toByteArray()
    }
    private fun toWords(bytes: ByteArray): IntArray { val be = bytes.reversedArray(); val p = be.copyOf(((be.size + 3) / 4) * 4); val bb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN); val w = IntArray(p.size / 4); for (i in w.indices) w[i] = bb.int; return w }
}
