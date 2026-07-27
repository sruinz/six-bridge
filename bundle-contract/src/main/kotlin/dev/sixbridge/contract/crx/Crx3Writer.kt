package dev.sixbridge.contract.crx

import dev.sixbridge.contract.bundle.SixBundleContract
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec

interface Crx3SigningKey {
    fun publicKeyDer(): ByteArray

    fun signSha256Rsa(payload: ByteArray): ByteArray
}

data class CreatedCrx3(
    val extensionId: String,
    val bytes: ByteArray,
    val zipBytes: ByteArray,
)

object Crx3Writer {
    fun create(zipBytes: ByteArray, signingKey: Crx3SigningKey): CreatedCrx3 {
        val stableZipBytes = zipBytes.copyOf()
        require(stableZipBytes.size.toLong() in 4..SixBundleContract.MAX_WRAPPER_SIZE) {
            "CRX3 ZIP payload 크기가 올바르지 않습니다."
        }
        require(stableZipBytes.copyOfRange(0, 4).contentEquals(ZIP_MAGIC)) {
            "CRX3 ZIP payload가 local file header로 시작하지 않습니다."
        }

        val publicKeyDer = signingKey.publicKeyDer().copyOf()
        require(publicKeyDer.size in 1..MAX_HEADER_SIZE) { "CRX3 공개 키 크기가 올바르지 않습니다." }
        val publicKey = try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(publicKeyDer))
        } catch (error: Exception) {
            throw IllegalArgumentException("CRX3 RSA 공개 키를 읽을 수 없습니다.", error)
        }
        require(publicKey.algorithm == "RSA") { "CRX3 공개 키는 RSA여야 합니다." }

        val crxId = MessageDigest.getInstance("SHA-256").digest(publicKeyDer).copyOfRange(0, 16)
        val extensionId = extensionIdFromCrxId(crxId)
        val signedHeaderData = Proto.message(Proto.bytes(field = 1, value = crxId))
        val signedPayload = signedPayload(signedHeaderData, stableZipBytes)
        val signature = signingKey.signSha256Rsa(signedPayload).copyOf()
        require(signature.size in 1..MAX_SIGNATURE_SIZE) { "CRX3 서명 크기가 올바르지 않습니다." }
        require(publicKeyDer.contentEquals(signingKey.publicKeyDer())) {
            "CRX3 서명 중 공개 키가 변경되었습니다."
        }

        val proof = Proto.message(
            Proto.bytes(field = 1, value = publicKeyDer),
            Proto.bytes(field = 2, value = signature),
        )
        val header = Proto.message(
            Proto.bytes(field = RSA_PROOF_FIELD, value = proof),
            Proto.bytes(field = SIGNED_HEADER_DATA_FIELD, value = signedHeaderData),
        )
        require(header.size <= MAX_HEADER_SIZE) { "CRX3 헤더 크기가 제한을 초과합니다." }

        val bytes = ByteArrayOutputStream(12 + header.size + stableZipBytes.size).run {
            write(CRX_MAGIC)
            write(uint32(CRX_VERSION))
            write(uint32(header.size))
            write(header)
            write(stableZipBytes)
            toByteArray()
        }
        val verified = Crx3Verifier.verify(bytes)
        require(verified.extensionId == extensionId) { "CRX3 확장 ID 검증 결과가 일치하지 않습니다." }
        require(verified.publicKeySha256 == sha256(publicKeyDer)) { "CRX3 공개 키 검증 결과가 일치하지 않습니다." }
        require(verified.zipOffset == 12 + header.size && verified.zipLength == stableZipBytes.size) {
            "CRX3 ZIP 범위 검증 결과가 일치하지 않습니다."
        }

        return CreatedCrx3(
            extensionId = extensionId,
            bytes = bytes,
            zipBytes = stableZipBytes.copyOf(),
        )
    }

    private fun signedPayload(signedHeaderData: ByteArray, zipBytes: ByteArray): ByteArray =
        ByteArrayOutputStream().run {
            write(SIGNED_DATA_CONTEXT)
            write(uint32(signedHeaderData.size))
            write(signedHeaderData)
            write(zipBytes)
            toByteArray()
        }

    private fun extensionIdFromCrxId(crxId: ByteArray): String = buildString(32) {
        crxId.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(('a'.code + (unsigned ushr 4)).toChar())
            append(('a'.code + (unsigned and 0x0f)).toChar())
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun uint32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

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

    private const val MAX_HEADER_SIZE = 1024 * 1024
    private const val MAX_SIGNATURE_SIZE = MAX_HEADER_SIZE
    private const val CRX_VERSION = 3
    private const val RSA_PROOF_FIELD = 2
    private const val SIGNED_HEADER_DATA_FIELD = 10_000
    private val CRX_MAGIC = byteArrayOf('C'.code.toByte(), 'r'.code.toByte(), '2'.code.toByte(), '4'.code.toByte())
    private val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
    private val SIGNED_DATA_CONTEXT = "CRX3 SignedData\u0000".toByteArray(StandardCharsets.US_ASCII)
}
