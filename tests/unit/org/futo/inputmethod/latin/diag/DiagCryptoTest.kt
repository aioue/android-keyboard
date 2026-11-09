package org.futo.inputmethod.latin.diag

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

class DiagCryptoTest {
    private fun decryptAll(priv: java.security.PrivateKey, data: ByteArray): List<String> {
        val out = ArrayList<String>()
        val bb = ByteBuffer.wrap(data)
        while (bb.hasRemaining()) {
            val n = bb.int
            val w = bb.short.toInt() and 0xffff
            val wrapped = ByteArray(w).also { bb.get(it) }
            val iv = ByteArray(12).also { bb.get(it) }
            val ct = ByteArray(n - 2 - w - 12).also { bb.get(it) }
            val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding")
            rsa.init(Cipher.UNWRAP_MODE, priv, OAEPParameterSpec(
                "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT))
            val aes = rsa.unwrap(wrapped, "AES", Cipher.SECRET_KEY)
            val gcm = Cipher.getInstance("AES/GCM/NoPadding")
            gcm.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes.encoded, "AES"), GCMParameterSpec(128, iv))
            gcm.updateAAD("FDL1".toByteArray())
            val plain = gcm.doFinal(ct)
            out += GZIPInputStream(ByteArrayInputStream(plain)).readBytes().toString(Charsets.UTF_8)
                .lines().filter { it.isNotEmpty() }
        }
        return out
    }

    @Test
    fun roundTripAndFixture() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(3072) }.generateKeyPair()
        val pub = DiagCrypto.parsePublicKey(kp.public.encoded)
        val batches = listOf(
            listOf("""{"t":1,"type":"commit","typed":"teh","committed":"the"}""",
                   """{"t":2,"type":"revert","typed":"teh","corrected":"the"}"""),
            listOf("""{"t":3,"type":"swipe","points":[[1,2,3]],"note":"café ☃"}"""),
        )
        val file = batches.map { b ->
            DiagCrypto.encodeFrame(pub, (b.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8))
        }.reduce { a, b -> a + b }

        assertEquals(batches.flatten(), decryptAll(kp.private, file))

        System.getProperty("diaglog.fixtureDir")?.let {
            val dir = File(it).apply { mkdirs() }
            File(dir, "fixture.fdl").writeBytes(file)
            File(dir, "fixture_priv.der").writeBytes(kp.private.encoded) // PKCS8
            File(dir, "fixture_expected.jsonl").writeText(batches.flatten().joinToString("\n") + "\n")
        }
    }
}
