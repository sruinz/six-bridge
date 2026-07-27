package dev.sixbridge.contract.crx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECFieldFp
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec

data class VerifiedCrx3(
    val extensionId: String,
    val publicKeySha256: String,
    val crxSha256: String,
    val zipOffset: Int,
    val zipLength: Int,
    val zipSha256: String = "",
)

object Crx3Verifier {
    private val signedDataContext = "CRX3 SignedData\u0000".toByteArray(StandardCharsets.US_ASCII)

    fun verify(bytes: ByteArray): VerifiedCrx3 = verify(bytes, 0, bytes.size)

    fun verify(path: Path, maximumBytes: Long): VerifiedCrx3 =
        verify(path, maximumBytes) {}

    internal fun verify(
        path: Path,
        maximumBytes: Long,
        afterVerification: () -> Unit,
    ): VerifiedCrx3 = verifyStablePath(path) { channel ->
        verifyPathRange(
            channel = channel,
            offset = 0L,
            length = channel.size(),
            maximumBytes = maximumBytes,
            afterInitialVerification = afterVerification,
        )
    }

    fun verify(
        path: Path,
        offset: Long,
        length: Long,
        maximumBytes: Long,
    ): VerifiedCrx3 =
        verifyStablePath(path) { channel ->
            verifyPathRange(channel, offset, length, maximumBytes)
        }

    fun verify(bytes: ByteArray, offset: Int, length: Int): VerifiedCrx3 {
        if (offset < 0 || length < 0 || offset > bytes.size - length) {
            invalid("CRX3 byte 범위가 올바르지 않습니다.")
        }
        val end = offset + length
        if (length < 12 ||
            bytes[offset] != MAGIC[0] ||
            bytes[offset + 1] != MAGIC[1] ||
            bytes[offset + 2] != MAGIC[2] ||
            bytes[offset + 3] != MAGIC[3]
        ) {
            invalid("CRX3 magic header가 없습니다.")
        }
        val version = readUInt32(bytes, offset + 4)
        if (version != VERSION) invalid("지원하지 않는 CRX 버전입니다: $version")

        val headerLength = readUInt32(bytes, offset + 8)
        if (headerLength < 0 || headerLength > MAX_HEADER_SIZE || 12L + headerLength > length) {
            invalid("CRX3 헤더 길이가 손상되었습니다.")
        }
        val headerStart = offset + 12
        val headerEnd = headerStart + headerLength
        if (end - headerEnd < 4 ||
            bytes[headerEnd] != 'P'.code.toByte() ||
            bytes[headerEnd + 1] != 'K'.code.toByte() ||
            bytes[headerEnd + 2] != 3.toByte() ||
            bytes[headerEnd + 3] != 4.toByte()
        ) {
            invalid("CRX3 ZIP payload가 없습니다.")
        }

        val fields = Proto.parse(bytes.copyOfRange(headerStart, headerEnd))
        val signedHeaderData = requiredSingleField(fields, 10_000, "CRX3 signed_header_data")
        val crxId = requiredSingleField(Proto.parse(signedHeaderData), 1, "CRX3 crx_id")
        if (crxId.size != 16) invalid("CRX3 crx_id 길이가 올바르지 않습니다.")

        val proof = buildList {
            fields[2].orEmpty().forEach { add(readProof(ProofKind.RSA, it)) }
            fields[3].orEmpty().forEach { add(readProof(ProofKind.ECDSA, it)) }
        }.firstNotNullOfOrNull { (kind, encodedProof) ->
            verifyProof(kind, encodedProof.publicKey, encodedProof.signature, crxId, signedHeaderData, bytes, headerEnd, end - headerEnd)
        } ?: invalid("CRX3 서명을 검증할 수 없습니다.")

        return VerifiedCrx3(
            extensionId = extensionIdFromCrxId(crxId),
            publicKeySha256 = sha256(proof),
            crxSha256 = sha256(bytes, offset, length),
            zipOffset = headerEnd,
            zipLength = end - headerEnd,
            zipSha256 = sha256(bytes, headerEnd, end - headerEnd),
        )
    }

    private fun verifyPathRange(
        channel: FileChannel,
        offset: Long,
        length: Long,
        maximumBytes: Long,
        afterInitialVerification: () -> Unit = {},
    ): VerifiedCrx3 {
        val channelSize = channel.size()
        if (maximumBytes < 0L ||
            offset < 0L ||
            length < 0L ||
            length > maximumBytes ||
            offset > channelSize - length ||
            length > Int.MAX_VALUE
        ) {
            invalid("CRX3 파일 범위가 올바르지 않습니다.")
        }
        if (length < 12L) invalid("CRX3 magic header가 없습니다.")
        val prefix = readRange(channel, offset, 12)
        if (!prefix.copyOfRange(0, 4).contentEquals(MAGIC)) {
            invalid("CRX3 magic header가 없습니다.")
        }
        val version = readUInt32(prefix, 4)
        if (version != VERSION) invalid("지원하지 않는 CRX 버전입니다: $version")
        val headerLength = readUInt32(prefix, 8)
        if (headerLength < 0 ||
            headerLength > MAX_HEADER_SIZE ||
            12L + headerLength > length
        ) {
            invalid("CRX3 헤더 길이가 손상되었습니다.")
        }
        val headerStart = offset + 12L
        val headerEnd = headerStart + headerLength
        if (headerEnd > Int.MAX_VALUE) {
            invalid("CRX3 ZIP offset이 지원 범위를 초과합니다.")
        }
        val zipLength = length - 12L - headerLength
        if (zipLength < 4L ||
            !readRange(channel, headerEnd, 4).contentEquals(ZIP_MAGIC)
        ) {
            invalid("CRX3 ZIP payload가 없습니다.")
        }
        val header = readRange(channel, headerStart, headerLength)
        val fields = Proto.parse(header)
        val signedHeaderData = requiredSingleField(
            fields,
            10_000,
            "CRX3 signed_header_data",
        )
        val crxId = requiredSingleField(
            Proto.parse(signedHeaderData),
            1,
            "CRX3 crx_id",
        )
        if (crxId.size != 16) invalid("CRX3 crx_id 길이가 올바르지 않습니다.")
        val selected = buildList {
            fields[2].orEmpty().forEach { add(readProof(ProofKind.RSA, it)) }
            fields[3].orEmpty().forEach { add(readProof(ProofKind.ECDSA, it)) }
        }.firstNotNullOfOrNull { (kind, proof) ->
            verifyProof(
                kind,
                proof.publicKey,
                proof.signature,
                crxId,
                signedHeaderData,
                channel,
                headerEnd,
                zipLength,
            )?.let { SelectedProof(kind, proof, it) }
        } ?: invalid("CRX3 서명을 검증할 수 없습니다.")
        afterInitialVerification()
        if (!readRange(channel, offset, prefix.size).contentEquals(prefix) ||
            !readRange(channel, headerStart, headerLength).contentEquals(header)
        ) {
            invalid("CRX3 파일이 검증 중 변경되었습니다.")
        }
        val bound = verifyProofAndHashes(
            selected = selected,
            expectedCrxId = crxId,
            signedHeaderData = signedHeaderData,
            prefix = prefix,
            header = header,
            channel = channel,
            zipOffset = headerEnd,
            zipLength = zipLength,
        ) ?: invalid("CRX3 파일이 검증 중 변경되었습니다.")
        if (channel.size() != channelSize) {
            invalid("CRX3 파일이 검증 중 변경되었습니다.")
        }
        return VerifiedCrx3(
            extensionId = extensionIdFromCrxId(crxId),
            publicKeySha256 = sha256(bound.publicKey),
            crxSha256 = bound.crxSha256,
            zipOffset = headerEnd.toInt(),
            zipLength = zipLength.toInt(),
            zipSha256 = bound.zipSha256,
        )
    }

    private fun verifyProofAndHashes(
        selected: SelectedProof,
        expectedCrxId: ByteArray,
        signedHeaderData: ByteArray,
        prefix: ByteArray,
        header: ByteArray,
        channel: FileChannel,
        zipOffset: Long,
        zipLength: Long,
    ): BoundPathProof? = runCatching {
        val actualCrxId = MessageDigest.getInstance("SHA-256")
            .digest(selected.proof.publicKey)
            .copyOfRange(0, 16)
        if (!actualCrxId.contentEquals(expectedCrxId)) return null
        val publicKey = readPublicKey(selected.kind, selected.proof.publicKey)
        val signature = Signature.getInstance(selected.kind.signatureAlgorithm).apply {
            initVerify(publicKey)
            update(signedDataContext)
            update(uint32(signedHeaderData.size))
            update(signedHeaderData)
        }
        val crxDigest = MessageDigest.getInstance("SHA-256").apply {
            update(prefix)
            update(header)
        }
        val zipDigest = MessageDigest.getInstance("SHA-256")
        updateRange(channel, zipOffset, zipLength) { buffer ->
            val bytes = buffer.array()
            val count = buffer.remaining()
            signature.update(bytes, 0, count)
            crxDigest.update(bytes, 0, count)
            zipDigest.update(bytes, 0, count)
        }
        if (!signature.verify(selected.proof.signature)) return null
        BoundPathProof(
            publicKey = selected.publicKey,
            crxSha256 = crxDigest.digest().toHex(),
            zipSha256 = zipDigest.digest().toHex(),
        )
    }.getOrNull()

    private inline fun verifyStablePath(
        path: Path,
        verify: (FileChannel) -> VerifiedCrx3,
    ): VerifiedCrx3 {
        val before = readPathState(path)
        val verified = FileChannel.open(path, READ, NOFOLLOW_LINKS).use(verify)
        if (readPathState(path) != before) {
            invalid("CRX3 파일이 검증 중 변경되었습니다.")
        }
        return verified
    }

    private fun readPathState(path: Path): PathState {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        if (!attributes.isRegularFile || attributes.isSymbolicLink) {
            invalid("CRX3 경로는 일반 파일이어야 합니다.")
        }
        return PathState(
            fileKey = attributes.fileKey(),
            size = attributes.size(),
            modifiedMillis = attributes.lastModifiedTime().toMillis(),
        )
    }

    private fun verifyProof(
        kind: ProofKind,
        publicKeyBytes: ByteArray,
        signatureBytes: ByteArray,
        expectedCrxId: ByteArray,
        signedHeaderData: ByteArray,
        source: ByteArray,
        zipOffset: Int,
        zipLength: Int,
    ): ByteArray? = runCatching {
        val actualCrxId = MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBytes)
            .copyOfRange(0, 16)
        if (!actualCrxId.contentEquals(expectedCrxId)) return null
        val publicKey = readPublicKey(kind, publicKeyBytes)
        val verified = Signature.getInstance(kind.signatureAlgorithm).run {
            initVerify(publicKey)
            update(signedDataContext)
            update(uint32(signedHeaderData.size))
            update(signedHeaderData)
            update(source, zipOffset, zipLength)
            verify(signatureBytes)
        }
        publicKeyBytes.takeIf { verified }?.copyOf()
    }.getOrNull()

    private fun verifyProof(
        kind: ProofKind,
        publicKeyBytes: ByteArray,
        signatureBytes: ByteArray,
        expectedCrxId: ByteArray,
        signedHeaderData: ByteArray,
        channel: FileChannel,
        zipOffset: Long,
        zipLength: Long,
    ): ByteArray? = runCatching {
        val actualCrxId = MessageDigest.getInstance("SHA-256")
            .digest(publicKeyBytes)
            .copyOfRange(0, 16)
        if (!actualCrxId.contentEquals(expectedCrxId)) return null
        val publicKey = readPublicKey(kind, publicKeyBytes)
        val verified = Signature.getInstance(kind.signatureAlgorithm).run {
            initVerify(publicKey)
            update(signedDataContext)
            update(uint32(signedHeaderData.size))
            update(signedHeaderData)
            updateRange(channel, zipOffset, zipLength) { update(it) }
            verify(signatureBytes)
        }
        publicKeyBytes.takeIf { verified }?.copyOf()
    }.getOrNull()

    private fun readProof(kind: ProofKind, encodedProof: ByteArray): Pair<ProofKind, Proof> {
        val fields = Proto.parse(encodedProof)
        return kind to Proof(
            publicKey = requiredSingleField(fields, 1, "CRX3 proof public_key"),
            signature = requiredSingleField(fields, 2, "CRX3 proof signature"),
        )
    }

    private fun requiredSingleField(
        fields: Map<Int, List<ByteArray>>,
        field: Int,
        name: String,
    ): ByteArray {
        val values = fields[field] ?: invalid("${name}가 없습니다.")
        if (values.size != 1) invalid("${name}는 정확히 하나여야 합니다.")
        return values.single()
    }

    private fun readPublicKey(kind: ProofKind, encoded: ByteArray): PublicKey =
        KeyFactory.getInstance(kind.keyAlgorithm).generatePublic(X509EncodedKeySpec(encoded)).also { publicKey ->
            if (kind == ProofKind.ECDSA && !usesNistP256(publicKey)) {
                invalid("CRX3 ECDSA 공개 키가 NIST P-256이 아닙니다.")
            }
        }

    private fun usesNistP256(publicKey: PublicKey): Boolean {
        val actual = (publicKey as? ECPublicKey)?.params ?: return false
        val expected = NIST_P256_PARAMETERS
        val actualField = actual.curve.field as? ECFieldFp ?: return false
        val expectedField = expected.curve.field as ECFieldFp
        return actualField.p == expectedField.p &&
            actual.curve.a == expected.curve.a &&
            actual.curve.b == expected.curve.b &&
            actual.generator.affineX == expected.generator.affineX &&
            actual.generator.affineY == expected.generator.affineY &&
            actual.order == expected.order &&
            actual.cofactor == expected.cofactor
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

    private fun sha256(bytes: ByteArray, offset: Int, length: Int): String =
        MessageDigest.getInstance("SHA-256").run {
            update(bytes, offset, length)
            digest().joinToString("") { "%02x".format(it) }
        }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun readRange(
        channel: FileChannel,
        offset: Long,
        length: Int,
    ): ByteArray {
        val result = ByteArray(length)
        val buffer = ByteBuffer.wrap(result)
        var position = offset
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer, position)
            if (count <= 0) invalid("CRX3 파일 범위를 완전히 읽지 못했습니다.")
            position += count
        }
        return result
    }

    private inline fun updateRange(
        channel: FileChannel,
        offset: Long,
        length: Long,
        update: (ByteBuffer) -> Unit,
    ) {
        val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
        var position = offset
        var remaining = length
        while (remaining > 0L) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
            val count = channel.read(buffer, position)
            if (count <= 0) invalid("CRX3 파일 범위를 완전히 읽지 못했습니다.")
            buffer.flip()
            update(buffer)
            position += count
            remaining -= count
        }
    }

    private fun readUInt32(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun uint32(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

    private fun invalid(message: String): Nothing = throw IllegalArgumentException(message)

    private enum class ProofKind(
        val keyAlgorithm: String,
        val signatureAlgorithm: String,
    ) {
        RSA("RSA", "SHA256withRSA"),
        ECDSA("EC", "SHA256withECDSA"),
    }

    private data class Proof(
        val publicKey: ByteArray,
        val signature: ByteArray,
    )

    private data class SelectedProof(
        val kind: ProofKind,
        val proof: Proof,
        val publicKey: ByteArray,
    )

    private data class BoundPathProof(
        val publicKey: ByteArray,
        val crxSha256: String,
        val zipSha256: String,
    )

    private data class PathState(
        val fileKey: Any?,
        val size: Long,
        val modifiedMillis: Long,
    )

    private object Proto {
        fun parse(message: ByteArray): Map<Int, List<ByteArray>> {
            val result = linkedMapOf<Int, MutableList<ByteArray>>()
            var offset = 0
            while (offset < message.size) {
                val (tag, afterTag) = readVarInt(message, offset)
                offset = afterTag
                val field = tag ushr 3
                if (field !in 1L..MAX_FIELD_NUMBER.toLong()) {
                    invalid("protobuf field number가 올바르지 않습니다.")
                }
                when ((tag and 7).toInt()) {
                    0 -> {
                        val (value, next) = readVarInt(message, offset)
                        offset = next
                        result.getOrPut(field.toInt()) { mutableListOf() } += encodeVarInt(value)
                    }
                    1 -> offset = checkedOffset(message, offset, 8)
                    2 -> {
                        val (length, afterLength) = readVarInt(message, offset)
                        if (length < 0 || length > Int.MAX_VALUE) invalid("protobuf 필드가 너무 큽니다.")
                        val end = checkedOffset(message, afterLength, length.toInt())
                        result.getOrPut(field.toInt()) { mutableListOf() } += message.copyOfRange(afterLength, end)
                        offset = end
                    }
                    5 -> offset = checkedOffset(message, offset, 4)
                    else -> invalid("지원하지 않는 protobuf wire type: ${tag and 7}")
                }
            }
            return result
        }

        private fun checkedOffset(bytes: ByteArray, offset: Int, length: Int): Int {
            val end = offset.toLong() + length
            if (length < 0 || end > bytes.size) invalid("protobuf 필드가 손상되었습니다.")
            return end.toInt()
        }

        private fun readVarInt(bytes: ByteArray, start: Int): Pair<Long, Int> {
            var value = 0L
            var offset = start
            repeat(10) { index ->
                if (offset >= bytes.size) invalid("protobuf varint가 손상되었습니다.")
                val byte = bytes[offset++].toInt() and 0xff
                if (index == 9 && byte > 1) invalid("protobuf varint가 overflow했습니다.")
                value = value or ((byte and 0x7f).toLong() shl (index * 7))
                if (byte and 0x80 == 0) return value to offset
            }
            invalid("protobuf varint가 overflow했습니다.")
        }

        private fun encodeVarInt(value: Long): ByteArray = ByteArrayOutputStream().run {
            writeVarInt(value)
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
    private const val MAX_FIELD_NUMBER = 536_870_911
    private const val VERSION = 3
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'r'.code.toByte(), '2'.code.toByte(), '4'.code.toByte())
    private val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
    private const val STREAM_BUFFER_SIZE = 64 * 1024
    private val NIST_P256_PARAMETERS: ECParameterSpec = AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec("secp256r1"))
        getParameterSpec(ECParameterSpec::class.java)
    }
}
