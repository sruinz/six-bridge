package dev.sixbridge.contract.crx

import dev.sixbridge.contract.bundle.SixBundleContract
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Crx3WriterTest {
    @Test
    fun `동일 ZIP과 키는 동일 CRX3와 확장 ID를 만든다`() {
        val first = Crx3Writer.create(MINIMAL_ZIP, testKey)
        val second = Crx3Writer.create(MINIMAL_ZIP, testKey)

        assertContentEquals(first.bytes, second.bytes)
        assertEquals(first.extensionId, second.extensionId)
        assertContentEquals(MINIMAL_ZIP, first.zipBytes)
        assertEquals(EXPECTED_EXTENSION_ID, first.extensionId)
        assertEquals(first.extensionId, Crx3Verifier.verify(first.bytes).extensionId)
    }

    @Test
    fun `signer가 반환한 서명이 공개 키와 맞지 않으면 거부한다`() {
        val wrongSigner = object : Crx3SigningKey {
            override fun publicKeyDer(): ByteArray = testKey.publicKeyDer()

            override fun signSha256Rsa(payload: ByteArray): ByteArray = ByteArray(256)
        }

        assertFailsWith<IllegalArgumentException> {
            Crx3Writer.create(MINIMAL_ZIP, wrongSigner)
        }
    }

    @Test
    fun `빈 ZIP을 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            Crx3Writer.create(ByteArray(0), testKey)
        }
    }

    @Test
    fun `local file ZIP magic이 아닌 입력을 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            Crx3Writer.create(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 5, 6), testKey)
        }
    }

    @Test
    fun `128 MiB를 초과한 ZIP을 거부한다`() {
        val oversizedZip = ByteArray(SixBundleContract.MAX_WRAPPER_SIZE.toInt() + 1).also {
            it[0] = 'P'.code.toByte()
            it[1] = 'K'.code.toByte()
            it[2] = 3
            it[3] = 4
        }

        assertFailsWith<IllegalArgumentException> {
            Crx3Writer.create(oversizedZip, testKey)
        }
    }

    @Test
    fun `RSA가 아닌 공개 키를 거부한다`() {
        val ecPair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val nonRsaSigner = object : Crx3SigningKey {
            override fun publicKeyDer(): ByteArray = ecPair.public.encoded

            override fun signSha256Rsa(payload: ByteArray): ByteArray = ByteArray(64)
        }

        assertFailsWith<IllegalArgumentException> {
            Crx3Writer.create(MINIMAL_ZIP, nonRsaSigner)
        }
    }

    @Test
    fun `서명 중 공개 키가 바뀌는 signer를 거부한다`() {
        val changedPublicKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair().public.encoded
        var publicKeyCalls = 0
        val changingSigner = object : Crx3SigningKey {
            override fun publicKeyDer(): ByteArray =
                if (publicKeyCalls++ == 0) testKey.publicKeyDer() else changedPublicKey

            override fun signSha256Rsa(payload: ByteArray): ByteArray = testKey.signSha256Rsa(payload)
        }

        assertFailsWith<IllegalArgumentException> {
            Crx3Writer.create(MINIMAL_ZIP, changingSigner)
        }
        assertEquals(2, publicKeyCalls)
    }

    @Test
    fun `다른 RSA 개인 키로 만든 서명을 거부한다`() {
        val otherPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val differentRsaSigner = object : Crx3SigningKey {
            override fun publicKeyDer(): ByteArray = testKey.publicKeyDer()

            override fun signSha256Rsa(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run {
                initSign(otherPair.private)
                update(payload)
                sign()
            }
        }

        assertFailsWith<IllegalArgumentException> {
            Crx3Writer.create(MINIMAL_ZIP, differentRsaSigner)
        }
    }

    private class FixedRsaSigningKey(
        private val privateKey: PrivateKey,
        private val publicKey: PublicKey,
    ) : Crx3SigningKey {
        override fun publicKeyDer(): ByteArray = publicKey.encoded.copyOf()

        override fun signSha256Rsa(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(payload)
            sign()
        }
    }

    private companion object {
        val MINIMAL_ZIP = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 0, 0, 0)
        const val EXPECTED_EXTENSION_ID = "googbaoeegncgdedoeoohegmgmicafgd"

        val testKey: Crx3SigningKey by lazy {
            val factory = KeyFactory.getInstance("RSA")
            FixedRsaSigningKey(
                privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(RSA_PKCS8))),
                publicKey = factory.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(RSA_X509))),
            )
        }

        const val RSA_PKCS8 =
            "MIIEvQIBADANBgkqhkiG9w0BAQEFAASCBKcwggSjAgEAAoIBAQCWZc+lmFaBbhsHJTTXinX7rHTtVazLrcIoJQduvk0AMwngw8uZiDt29M8a2BrWk7wg15pF7YDgc5YG+bg43/XCjJnLoeL/wJ8sxbi10bLy4QHbk+scsnSVQFeteJjiWmD5PtN7vAV71tTY7u7hWc+nta1oC2R8f+3lfVz4ZOf9NJmOKl1XhPSO0LkFotw0RlvnK/Du4BEVHKSZPNk8o2Su7EZxM0kIfIr5GFDPqKqk5Wo+sjHVtEAbODvp7Y8gVH8jrqo2GJjoUi10GiREvzMYritvJaLxT4A7u9cY9EFLeliS/Qg9XRdv74s8TPKSi616VUZAGTu//CthAcPNydK1AgMBAAECggEAOJjGq/vobduX+zfiq0wVOyj6dYfct2dDneTVZ7TgeDc8u+MinrvYGNtmhqUVcHN3A+ruyOroKU8GAcj+rRSGwPg1Nh7NNE5x+CDN8kogK+tFzjJbc1iqq7HjbMnpyqCVRqaAYltuutwtDBfaOF6aWPPy9ij3XyyrQ0XKc0VR0gRhzKW+uJHvPYxiZHZUwfBsJwyQ+Yt7hyY6pE+t2YngDehaa9VHHxXCYcdp2wzOKgr6u45AIRJCpG1fmztpceme+eMB+jitXJEQ2AnN7bamZIuubTD004qPxSPUN2vc0vCz2E3GBBCMVqXinhiYzRTkgdwz/k/PkQV6FGuRRe36TwKBgQDSW7jRYcjYhd1s65kIvxQg2T+eeLK2IpevrAbK1s38MsvGY8h2qQ3tTmWoOHbW5+ivXjBM+xogaAp3vRKgoiAMMQKZj5qsW/jpfdS2UkA51yaSwOjwD1UfPSH7G5FgPXFL1cha/l7xvKVUPea0pSqXbsEi5TLyC7m9V9CCcADtUwKBgQC3B5ldw9n5oVRU0+sT6RFYcNqZCPU2rC+lBRpW8TiMPdI4YCu6AxTQAT06CuZ5un6XzAW654rCTtQk1s8Usuoc5DEnwmRfWZcxu8Bt39jmnHl0KiVdMdwI1tQZOt9EUnNVi5ZnAWF9x2d+kfS3KQAudOirlB3r5o+oLmipl+E21wKBgQC5J7y8p7pWQzXMCm+wECgSZcamoQyEXlxvZxavcXTrmHZekDPBpjm49/looRKRRJ09xsTy7U5o2crk7Ao6U5mrwXPNYGY/cdDdDEGvnSQlBShRofUmYAiHr5J6aSCDunE/09M/rIg7dNV9GeM4e+zKT9U19tHp1MJPU70XIqjeOwKBgDDmwN1ChKyjpAp+DGLndJDyphUZRZrz6k7mzCDKllcx9UbGuygZxqfEjP1DCE0hvM0F38oOLw63TQsCsZVQMCjD9zYuQ+8N2s2Y4lL46dTDndehoxYBSk66RzTue9SfSt1iTC79OXUHn+k7Sezd8YR4C+o7yrrKgTtoGuKlB7FFAoGAUF9XKZFRq3JT+uBQ9Apni0tJbyraf2PBbLkMGyVGv2rQ9NpIi6qdcF8EGq6357rp3FS7JQdQIKDSHqZoQdWWLmXXIwY4KU2n91shAFpPQ7pyDUmsq721numkiDzSvTkO8BBvuqsKm5taCN8ou1mNbC6KIarLGpLQ882I0R6UMGY="
        const val RSA_X509 =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAlmXPpZhWgW4bByU014p1+6x07VWsy63CKCUHbr5NADMJ4MPLmYg7dvTPGtga1pO8INeaRe2A4HOWBvm4ON/1woyZy6Hi/8CfLMW4tdGy8uEB25PrHLJ0lUBXrXiY4lpg+T7Te7wFe9bU2O7u4VnPp7WtaAtkfH/t5X1c+GTn/TSZjipdV4T0jtC5BaLcNEZb5yvw7uARFRykmTzZPKNkruxGcTNJCHyK+RhQz6iqpOVqPrIx1bRAGzg76e2PIFR/I66qNhiY6FItdBokRL8zGK4rbyWi8U+AO7vXGPRBS3pYkv0IPV0Xb++LPEzykoutelVGQBk7v/wrYQHDzcnStQIDAQAB"
    }
}
