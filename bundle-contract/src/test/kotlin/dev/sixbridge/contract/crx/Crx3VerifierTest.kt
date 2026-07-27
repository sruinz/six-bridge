package dev.sixbridge.contract.crx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Crx3VerifierTest {
    @Test
    fun `verifies RSA proof and returns CRX identity and ZIP boundary`() {
        val packed = crx3("RSA")

        val verified = Crx3Verifier.verify(packed.bytes)

        assertEquals(extensionId(packed.keyPair), verified.extensionId)
        assertEquals(sha256(packed.keyPair.public.encoded), verified.publicKeySha256)
        assertEquals(sha256(packed.bytes), verified.crxSha256)
        assertEquals(packed.zipOffset, verified.zipOffset)
        assertEquals(packed.zipBytes.size, verified.zipLength)
        assertEquals(sha256(packed.zipBytes), verified.zipSha256)
    }

    @Test
    fun `verifies ECDSA proof`() {
        val packed = crx3("EC")

        assertEquals(extensionId(packed.keyPair), Crx3Verifier.verify(packed.bytes).extensionId)
    }

    @Test
    fun `rejects an ECDSA proof not using NIST P-256`() {
        val bytes = crx3("EC", ecKeySize = 384).bytes

        assertInvalid(bytes)
    }

    @Test
    fun `verifies a CRX range without changing its ZIP boundary`() {
        val packed = crx3("RSA")
        val prefix = ByteArray(7) { 9 }
        val container = prefix + packed.bytes + ByteArray(5) { 8 }

        val verified = Crx3Verifier.verify(container, prefix.size, packed.bytes.size)

        assertEquals(prefix.size + packed.zipOffset, verified.zipOffset)
        assertEquals(packed.zipBytes.size, verified.zipLength)
        assertEquals(sha256(packed.bytes), verified.crxSha256)
        assertEquals(sha256(packed.zipBytes), verified.zipSha256)
    }

    @Test
    fun `rejects missing CRX magic`() {
        val bytes = crx3("RSA").bytes.copyOf().also { it[0] = 'X'.code.toByte() }

        assertInvalid(bytes)
    }

    @Test
    fun `rejects an unsupported version`() {
        val bytes = crx3("RSA").bytes.copyOf().also { it[4] = 2 }

        assertInvalid(bytes)
    }

    @Test
    fun `rejects a header that exceeds the CRX bytes`() {
        val bytes = crx3("RSA").bytes.copyOf()
        bytes.writeUInt32(8, bytes.size)

        assertInvalid(bytes)
    }

    @Test
    fun `rejects malformed protobuf lengths`() {
        val bytes = byteArrayOf(
            'C'.code.toByte(), 'r'.code.toByte(), '2'.code.toByte(), '4'.code.toByte(),
            3, 0, 0, 0,
            1, 0, 0, 0,
            0x80.toByte(),
            'P'.code.toByte(), 'K'.code.toByte(), 3, 4,
        )

        assertInvalid(bytes)
    }

    @Test
    fun `rejects a missing signed header data`() {
        val packed = crx3("RSA")
        val header = Proto.message(Proto.bytes(2, packed.proof))
        val bytes = crx(header, packed.zipBytes)

        assertInvalid(bytes)
    }

    @Test
    fun `rejects duplicated signed header data`() {
        val packed = crx3("RSA")
        val header = Proto.message(
            Proto.bytes(2, packed.proof),
            Proto.bytes(10_000, packed.signedHeaderData),
            Proto.bytes(10_000, packed.signedHeaderData),
        )

        assertInvalid(crx(header, packed.zipBytes), "signed_header_data는 정확히 하나")
    }

    @Test
    fun `rejects duplicated CRX ID`() {
        val packed = crx3("RSA")
        val duplicatedSignedHeaderData = Proto.message(
            Proto.bytes(1, packed.crxId),
            Proto.bytes(1, packed.crxId),
        )
        val header = Proto.message(
            Proto.bytes(2, packed.proof),
            Proto.bytes(10_000, duplicatedSignedHeaderData),
        )

        assertInvalid(crx(header, packed.zipBytes), "crx_id는 정확히 하나")
    }

    @Test
    fun `rejects duplicated proof public key`() {
        val packed = crx3("RSA")
        val duplicatedProof = Proto.message(
            Proto.bytes(1, packed.publicKey),
            Proto.bytes(1, packed.publicKey),
            Proto.bytes(2, packed.signature),
        )
        val header = Proto.message(
            Proto.bytes(2, duplicatedProof),
            Proto.bytes(10_000, packed.signedHeaderData),
        )

        assertInvalid(crx(header, packed.zipBytes), "proof public_key는 정확히 하나")
    }

    @Test
    fun `rejects duplicated proof signature`() {
        val packed = crx3("RSA")
        val duplicatedProof = Proto.message(
            Proto.bytes(1, packed.publicKey),
            Proto.bytes(2, packed.signature),
            Proto.bytes(2, packed.signature),
        )
        val header = Proto.message(
            Proto.bytes(2, duplicatedProof),
            Proto.bytes(10_000, packed.signedHeaderData),
        )

        assertInvalid(crx(header, packed.zipBytes), "proof signature는 정확히 하나")
    }

    @Test
    fun `rejects protobuf field number zero`() {
        assertInvalid(crx(byteArrayOf(2), zipBytes()), "protobuf field number가 올바르지 않습니다")
    }

    @Test
    fun `rejects protobuf field number above maximum`() {
        assertInvalid(
            crx(byteArrayOf(0x82.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x10), zipBytes()),
            "protobuf field number가 올바르지 않습니다",
        )
    }

    @Test
    fun `rejects a ten byte overflow protobuf tag`() {
        assertInvalid(
            crx(ByteArray(9) { 0x80.toByte() } + byteArrayOf(2), zipBytes()),
            "protobuf varint가 overflow했습니다",
        )
    }

    @Test
    fun `rejects a ten byte overflow protobuf length`() {
        assertInvalid(
            crx(byteArrayOf(10) + ByteArray(9) { 0x80.toByte() } + byteArrayOf(2), zipBytes()),
            "protobuf varint가 overflow했습니다",
        )
    }

    @Test
    fun `rejects a modified signed payload`() {
        val bytes = crx3("RSA").bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }

        assertInvalid(bytes)
    }

    @Test
    fun `rejects a non local file ZIP boundary`() {
        val bytes = crx3(
            "RSA",
            byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 5, 6, 9, 8, 7),
        ).bytes

        assertInvalid(bytes)
    }

    private fun assertInvalid(bytes: ByteArray, message: String? = null) {
        val error = assertFailsWith<IllegalArgumentException> { Crx3Verifier.verify(bytes) }
        message?.let { assertContains(error.message.orEmpty(), it) }
    }

    private fun crx3(
        keyAlgorithm: String,
        zipBytes: ByteArray = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4, 9, 8, 7),
        ecKeySize: Int = 256,
    ): PackedFixture {
        val keyPair = KeyPairGenerator.getInstance(keyAlgorithm).apply {
            initialize(if (keyAlgorithm == "RSA") 2048 else ecKeySize)
        }.generateKeyPair()
        val crxId = MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded).copyOfRange(0, 16)
        val signedHeaderData = Proto.message(Proto.bytes(1, crxId))
        val signature = Signature.getInstance(if (keyAlgorithm == "RSA") "SHA256withRSA" else "SHA256withECDSA").run {
            initSign(keyPair.private)
            update("CRX3 SignedData\u0000".toByteArray(StandardCharsets.US_ASCII))
            update(uint32(signedHeaderData.size))
            update(signedHeaderData)
            update(zipBytes)
            sign()
        }
        val proof = Proto.message(
            Proto.bytes(1, keyPair.public.encoded),
            Proto.bytes(2, signature),
        )
        val header = Proto.message(
            Proto.bytes(if (keyAlgorithm == "RSA") 2 else 3, proof),
            Proto.bytes(10_000, signedHeaderData),
        )
        val bytes = crx(header, zipBytes)
        return PackedFixture(
            keyPair = keyPair,
            proof = proof,
            publicKey = keyPair.public.encoded,
            signature = signature,
            crxId = crxId,
            signedHeaderData = signedHeaderData,
            zipBytes = zipBytes,
            bytes = bytes,
            zipOffset = 12 + header.size,
        )
    }

    private fun zipBytes(): ByteArray = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)

    private fun crx(header: ByteArray, zipBytes: ByteArray): ByteArray =
        byteArrayOf('C'.code.toByte(), 'r'.code.toByte(), '2'.code.toByte(), '4'.code.toByte()) +
            uint32(3) + uint32(header.size) + header + zipBytes

    private fun extensionId(keyPair: KeyPair): String =
        MessageDigest.getInstance("SHA-256").digest(keyPair.public.encoded).take(16)
            .joinToString("") { byte ->
                val value = byte.toInt() and 0xff
                "${('a'.code + (value ushr 4)).toChar()}${('a'.code + (value and 15)).toChar()}"
            }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun ByteArray.writeUInt32(offset: Int, value: Int) {
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun uint32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private data class PackedFixture(
        val keyPair: KeyPair,
        val proof: ByteArray,
        val publicKey: ByteArray,
        val signature: ByteArray,
        val crxId: ByteArray,
        val signedHeaderData: ByteArray,
        val zipBytes: ByteArray,
        val bytes: ByteArray,
        val zipOffset: Int,
    )

    private object Proto {
        fun bytes(field: Int, value: ByteArray): ByteArray = ByteArrayOutputStream().run {
            writeVarInt((field.toLong() shl 3) or 2)
            writeVarInt(value.size.toLong())
            write(value)
            toByteArray()
        }

        fun message(vararg fields: ByteArray): ByteArray = ByteArrayOutputStream().run {
            fields.forEach(::write)
            toByteArray()
        }

        private fun ByteArrayOutputStream.writeVarInt(value: Long) {
            var current = value
            while (true) {
                if (current and 0x7f.inv().toLong() == 0L) {
                    write(current.toInt())
                    return
                }
                write((current.toInt() and 0x7f) or 0x80)
                current = current ushr 7
            }
        }
    }
}
