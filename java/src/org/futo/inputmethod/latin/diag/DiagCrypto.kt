package org.futo.inputmethod.latin.diag

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.security.KeyFactory
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Frame encoder for the diagnostic log (format in tools/diaglog/SPEC.md).
 * Pure javax.crypto with no Android dependency so it runs in a JVM unit test.
 * Only the public key lives on the phone: the phone can write the log but never read it back.
 */
object DiagCrypto {
    private val AAD = "FDL1".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    // Android's (and the JDK's) default OAEP MGF1 digest is SHA-1 even when the main digest is
    // SHA-256, which the Mac decryptor would not match. Spell out every parameter.
    private val OAEP = OAEPParameterSpec(
        "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
    )

    fun parsePublicKey(spkiDer: ByteArray): PublicKey =
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(spkiDer))

    fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    /** Encodes one self-contained frame: gzip(jsonLines) under a fresh AES-256-GCM key and IV. */
    fun encodeFrame(publicKey: PublicKey, jsonLines: ByteArray): ByteArray {
        val keyGen = KeyGenerator.getInstance("AES").apply { init(256, random) }
        val aesKey = keyGen.generateKey()
        val iv = ByteArray(12).also { random.nextBytes(it) }

        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
        rsa.init(Cipher.WRAP_MODE, publicKey, OAEP)
        val wrapped = rsa.wrap(aesKey)

        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey.encoded, "AES"), GCMParameterSpec(128, iv))
        gcm.updateAAD(AAD)
        val ct = gcm.doFinal(gzip(jsonLines))

        val n = 2 + wrapped.size + iv.size + ct.size
        return ByteBuffer.allocate(4 + n)
            .putInt(n)
            .putShort(wrapped.size.toShort())
            .put(wrapped).put(iv).put(ct)
            .array()
    }
}
