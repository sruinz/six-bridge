package dev.sixbridge.core.crx

import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.conversion.VerifiedCrxPayload
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.jvm.JvmSynthetic

class InvalidCrxException(message: String) : IllegalArgumentException(message)

data class PackedCrx(
    val extensionId: String,
    val bytes: ByteArray,
    val zipBytes: ByteArray,
)

class ParsedCrx internal constructor(
    val extensionId: String,
    zipBytes: ByteArray,
    publicKey: ByteArray,
    val signaturesVerified: Boolean,
) {
    private val stableZipBytes = zipBytes.copyOf()
    private val stablePublicKey = publicKey.copyOf()

    val zipBytes: ByteArray
        get() = stableZipBytes.copyOf()

    val publicKey: ByteArray
        get() = stablePublicKey.copyOf()
}

object Crx3 {
    private val SIGNED_DATA_CONTEXT =
        "CRX3 SignedData\u0000".toByteArray(StandardCharsets.US_ASCII)

    fun pack(sourceRoot: Path, keyPair: KeyPair): PackedCrx {
        val zipBytes = ExtensionZip.createBytes(sourceRoot, keyPair.public.encoded)
        return packZip(zipBytes, keyPair)
    }

    @JvmSynthetic
    internal fun packVerified(
        payload: VerifiedCrxPayload,
        keyPair: KeyPair,
    ): PackedCrx {
        val zipBytes = payload.bytes
        return packZip(zipBytes, keyPair).also { packed ->
            check(packed.zipBytes.contentEquals(zipBytes)) {
                "verified CRX payload bytes가 packing 중 변경되었습니다."
            }
        }
    }

    private fun packZip(zipBytes: ByteArray, keyPair: KeyPair): PackedCrx {
        val crxIdBytes = MessageDigest.getInstance("SHA-256")
            .digest(keyPair.public.encoded)
            .copyOfRange(0, 16)
        val signedHeaderData = Proto.message(
            Proto.bytes(field = 1, value = crxIdBytes),
        )
        val signatureInput = signatureInput(signedHeaderData, zipBytes)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(keyPair.private)
            update(signatureInput)
            sign()
        }
        val proof = Proto.message(
            Proto.bytes(field = 1, value = keyPair.public.encoded),
            Proto.bytes(field = 2, value = signature),
        )
        val header = Proto.message(
            Proto.bytes(field = 2, value = proof),
            Proto.bytes(field = 10_000, value = signedHeaderData),
        )
        val output = ByteArrayOutputStream()
        output.write(MAGIC)
        output.write(uint32(VERSION))
        output.write(uint32(header.size))
        output.write(header)
        output.write(zipBytes)

        return PackedCrx(
            extensionId = extensionId(keyPair.public.encoded),
            bytes = output.toByteArray(),
            zipBytes = zipBytes,
        )
    }

    fun parse(bytes: ByteArray): ParsedCrx {
        if (bytes.size < 12 || !bytes.copyOfRange(0, 4).contentEquals(MAGIC)) {
            throw InvalidCrxException("CRX3 magic header가 없습니다.")
        }
        val version = readUInt32(bytes, 4)
        if (version != VERSION) {
            throw InvalidCrxException("지원하지 않는 CRX 버전입니다: $version")
        }
        val headerLength = readUInt32(bytes, 8)
        if (headerLength < 0 || 12L + headerLength > bytes.size) {
            throw InvalidCrxException("CRX3 헤더 길이가 손상되었습니다.")
        }
        val headerEnd = 12 + headerLength
        val header = bytes.copyOfRange(12, headerEnd)
        val zipBytes = bytes.copyOfRange(headerEnd, bytes.size)
        if (zipBytes.size < 4 || zipBytes[0] != 'P'.code.toByte() || zipBytes[1] != 'K'.code.toByte()) {
            throw InvalidCrxException("CRX3 ZIP payload가 없습니다.")
        }

        val fields = Proto.parse(header)
        val signedHeaderData = fields[10_000]?.firstOrNull()
            ?: throw InvalidCrxException("CRX3 signed_header_data가 없습니다.")
        val signedFields = Proto.parse(signedHeaderData)
        val crxId = signedFields[1]?.firstOrNull()
            ?: throw InvalidCrxException("CRX3 crx_id가 없습니다.")
        if (crxId.size != 16) {
            throw InvalidCrxException("CRX3 crx_id 길이가 올바르지 않습니다.")
        }

        val input = signatureInput(signedHeaderData, zipBytes)
        val proofs = buildList {
            fields[2].orEmpty().forEach { add(ProofKind.RSA to it) }
            fields[3].orEmpty().forEach { add(ProofKind.ECDSA to it) }
        }
        val verifiedPublicKey = proofs.firstNotNullOfOrNull { (kind, encodedProof) ->
            verifyProof(kind, encodedProof, crxId, input)
        }
        if (verifiedPublicKey == null) {
            throw InvalidCrxException("CRX3 서명을 검증할 수 없습니다.")
        }

        return ParsedCrx(
            extensionId = extensionIdFromCrxId(crxId),
            zipBytes = zipBytes,
            publicKey = verifiedPublicKey,
            signaturesVerified = true,
        )
    }

    fun extensionId(publicKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKey)
        return extensionIdFromCrxId(hash.copyOfRange(0, 16))
    }

    private fun extensionIdFromCrxId(crxId: ByteArray): String = buildString(32) {
        crxId.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(('a'.code + (unsigned ushr 4)).toChar())
            append(('a'.code + (unsigned and 0x0f)).toChar())
        }
    }

    private fun verifyProof(
        kind: ProofKind,
        encodedProof: ByteArray,
        expectedCrxId: ByteArray,
        signatureInput: ByteArray,
    ): ByteArray? = runCatching {
        val proof = Proto.parse(encodedProof)
        val publicKeyBytes = proof[1]?.firstOrNull() ?: return null
        val signatureBytes = proof[2]?.firstOrNull() ?: return null
        val actualCrxId = MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBytes)
            .copyOfRange(0, 16)
        if (!actualCrxId.contentEquals(expectedCrxId)) return null
        val publicKey = readPublicKey(kind, publicKeyBytes)
        val verified = Signature.getInstance(kind.signatureAlgorithm).run {
            initVerify(publicKey)
            update(signatureInput)
            verify(signatureBytes)
        }
        publicKeyBytes.takeIf { verified }?.copyOf()
    }.getOrNull()

    private fun readPublicKey(kind: ProofKind, encoded: ByteArray): PublicKey =
        KeyFactory.getInstance(kind.keyAlgorithm)
            .generatePublic(X509EncodedKeySpec(encoded))

    private fun signatureInput(signedHeaderData: ByteArray, zipBytes: ByteArray) =
        ByteArrayOutputStream().run {
            write(SIGNED_DATA_CONTEXT)
            write(uint32(signedHeaderData.size))
            write(signedHeaderData)
            write(zipBytes)
            toByteArray()
        }

    private fun uint32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private enum class ProofKind(
        val keyAlgorithm: String,
        val signatureAlgorithm: String,
    ) {
        RSA("RSA", "SHA256withRSA"),
        ECDSA("EC", "SHA256withECDSA"),
    }

    private object Proto {
        fun bytes(field: Int, value: ByteArray): ByteArray {
            val output = ByteArrayOutputStream()
            writeVarInt(output, (field.toLong() shl 3) or 2)
            writeVarInt(output, value.size.toLong())
            output.write(value)
            return output.toByteArray()
        }

        fun message(vararg fields: ByteArray): ByteArray =
            ByteArrayOutputStream().run {
                fields.forEach(::write)
                toByteArray()
            }

        fun parse(message: ByteArray): Map<Int, List<ByteArray>> {
            val result = linkedMapOf<Int, MutableList<ByteArray>>()
            var offset = 0
            while (offset < message.size) {
                val (tag, afterTag) = readVarInt(message, offset)
                offset = afterTag
                val field = (tag ushr 3).toInt()
                val wireType = (tag and 7).toInt()
                when (wireType) {
                    0 -> {
                        val (value, next) = readVarInt(message, offset)
                        offset = next
                        result.getOrPut(field) { mutableListOf() } += encodeVarInt(value)
                    }
                    1 -> offset = checkedOffset(message, offset, 8)
                    2 -> {
                        val (length, afterLength) = readVarInt(message, offset)
                        if (length > Int.MAX_VALUE) {
                            throw InvalidCrxException("protobuf 필드가 너무 큽니다.")
                        }
                        val end = checkedOffset(message, afterLength, length.toInt())
                        result.getOrPut(field) { mutableListOf() } +=
                            message.copyOfRange(afterLength, end)
                        offset = end
                    }
                    5 -> offset = checkedOffset(message, offset, 4)
                    else -> throw InvalidCrxException("지원하지 않는 protobuf wire type: $wireType")
                }
            }
            return result
        }

        private fun checkedOffset(bytes: ByteArray, offset: Int, length: Int): Int {
            val end = offset.toLong() + length
            if (length < 0 || end > bytes.size) {
                throw InvalidCrxException("protobuf 필드가 손상되었습니다.")
            }
            return end.toInt()
        }

        private fun readVarInt(bytes: ByteArray, start: Int): Pair<Long, Int> {
            var value = 0L
            var shift = 0
            var offset = start
            while (offset < bytes.size && shift < 64) {
                val byte = bytes[offset++].toInt() and 0xff
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return value to offset
                shift += 7
            }
            throw InvalidCrxException("protobuf varint가 손상되었습니다.")
        }

        private fun writeVarInt(output: ByteArrayOutputStream, value: Long) {
            var current = value
            while (true) {
                if (current and 0x7f.inv().toLong() == 0L) {
                    output.write(current.toInt())
                    return
                }
                output.write((current.toInt() and 0x7f) or 0x80)
                current = current ushr 7
            }
        }

        private fun encodeVarInt(value: Long) =
            ByteArrayOutputStream().run {
                writeVarInt(this, value)
                toByteArray()
            }
    }

    private val MAGIC = byteArrayOf('C'.code.toByte(), 'r'.code.toByte(), '2'.code.toByte(), '4'.code.toByte())
    private const val VERSION = 3
}
