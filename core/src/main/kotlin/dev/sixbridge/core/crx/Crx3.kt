package dev.sixbridge.core.crx

import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.contract.crx.Crx3SigningKey
import dev.sixbridge.contract.crx.Crx3Writer
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.conversion.VerifiedCrxPayload
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlin.jvm.JvmSynthetic

class InvalidCrxException(message: String) : IllegalArgumentException(message)

class KeyPairCrx3SigningKey(private val keyPair: KeyPair) : Crx3SigningKey {
    override fun publicKeyDer(): ByteArray = keyPair.public.encoded.copyOf()

    override fun signSha256Rsa(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA").run {
        initSign(keyPair.private)
        update(payload)
        sign()
    }
}

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
    private val stableZipBytes = zipBytes
    private val stablePublicKey = publicKey.copyOf()

    val zipBytes: ByteArray
        get() = stableZipBytes.copyOf()

    val publicKey: ByteArray
        get() = stablePublicKey.copyOf()
}

object Crx3 {
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
        val created = Crx3Writer.create(zipBytes, KeyPairCrx3SigningKey(keyPair))
        return PackedCrx(
            extensionId = created.extensionId,
            bytes = created.bytes,
            zipBytes = created.zipBytes,
        )
    }

    fun parse(bytes: ByteArray): ParsedCrx {
        val verified = verify(bytes)
        return ParsedCrx(
            extensionId = verified.extensionId,
            zipBytes = bytes.copyOfRange(verified.zipOffset, verified.zipOffset + verified.zipLength),
            publicKey = verifiedPublicKey(bytes, verified),
            signaturesVerified = true,
        )
    }

    fun verifiedPayloadSha256(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): String {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            throw InvalidCrxException("CRX3 byte 범위가 올바르지 않습니다.")
        }
        val verified = verify(bytes, offset, length)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes, verified.zipOffset, verified.zipLength)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun verify(bytes: ByteArray): VerifiedCrx3 = try {
        Crx3Verifier.verify(bytes)
    } catch (error: IllegalArgumentException) {
        throw InvalidCrxException(error.message ?: "CRX3 검증에 실패했습니다.")
    }

    private fun verify(bytes: ByteArray, offset: Int, length: Int): VerifiedCrx3 = try {
        Crx3Verifier.verify(bytes, offset, length)
    } catch (error: IllegalArgumentException) {
        throw InvalidCrxException(error.message ?: "CRX3 검증에 실패했습니다.")
    }

    private fun verifiedPublicKey(bytes: ByteArray, verified: VerifiedCrx3): ByteArray {
        val headerLength = readUInt32(bytes, 8)
        val fields = Proto.parse(bytes.copyOfRange(12, 12 + headerLength))
        val proofs = fields[2].orEmpty() + fields[3].orEmpty()
        val publicKeys = proofs.map { encodedProof ->
            val proof = Proto.parse(encodedProof)
            val publicKey = requiredSingleField(proof, 1, "CRX3 proof public_key")
            requiredSingleField(proof, 2, "CRX3 proof signature")
            publicKey
        }
        return publicKeys.firstOrNull { publicKey ->
            MessageDigest.getInstance("SHA-256")
                .digest(publicKey)
                .joinToString("") { "%02x".format(it) } == verified.publicKeySha256
        }?.copyOf() ?: throw InvalidCrxException("검증된 CRX3 공개 키를 찾을 수 없습니다.")
    }

    private fun requiredSingleField(
        fields: Map<Int, List<ByteArray>>,
        field: Int,
        name: String,
    ): ByteArray {
        val values = fields[field] ?: throw InvalidCrxException("${name}가 없습니다.")
        if (values.size != 1) throw InvalidCrxException("${name}는 정확히 하나여야 합니다.")
        return values.single()
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

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private object Proto {
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

}
