package dev.sixbridge.contract.crx

import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec

interface Crx3SigningKey {
    fun publicKeyDer(): ByteArray

    fun signSha256Rsa(payload: ByteArray): ByteArray

    fun signSha256Rsa(payload: InputStream): ByteArray =
        payload.use { signSha256Rsa(it.readBytes()) }
}

data class CreatedCrx3(
    val extensionId: String,
    val bytes: ByteArray,
    val zipBytes: ByteArray,
)

data class CreatedCrx3File(
    val extensionId: String,
    val path: Path,
    val crxSha256: String,
    val zipSha256: String,
)

object Crx3Writer {
    fun create(zipBytes: ByteArray, signingKey: Crx3SigningKey): CreatedCrx3 {
        val stableZipBytes = zipBytes.copyOf()
        require(stableZipBytes.size.toLong() in 4..MAX_ZIP_SIZE) {
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
        val sourceSha256 = sha256(stableZipBytes)
        val signature = signingKey.signSha256Rsa(
            SequenceInputStream(
                ByteArrayInputStream(signedPayloadPrefix(signedHeaderData)),
                ByteArrayInputStream(stableZipBytes),
            ),
        ).copyOf()
        require(signature.size in 1..MAX_SIGNATURE_SIZE) { "CRX3 서명 크기가 올바르지 않습니다." }
        require(publicKeyDer.contentEquals(signingKey.publicKeyDer())) {
            "CRX3 서명 중 공개 키가 변경되었습니다."
        }
        require(sourceSha256 == sha256(stableZipBytes)) {
            "CRX3 ZIP payload가 서명 중 변경되었습니다."
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

        val bytes = ByteArray(12 + header.size + stableZipBytes.size)
        var offset = 0
        fun append(value: ByteArray) {
            value.copyInto(bytes, destinationOffset = offset)
            offset += value.size
        }
        append(CRX_MAGIC)
        append(uint32(CRX_VERSION))
        append(uint32(header.size))
        append(header)
        append(stableZipBytes)
        require(sourceSha256 == sha256(stableZipBytes)) {
            "CRX3 ZIP payload가 기록 중 변경되었습니다."
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

    fun create(
        zipPath: Path,
        outputPath: Path,
        signingKey: Crx3SigningKey,
    ): CreatedCrx3File {
        val source = zipPath.toAbsolutePath().normalize()
        val output = outputPath.toAbsolutePath().normalize()
        require(source != output) { "CRX3 source ZIP과 output은 서로 달라야 합니다." }
        val sourceState = readStableState(source)
        require(sourceState.size in 4..MAX_ZIP_SIZE) {
            "CRX3 ZIP payload 크기가 올바르지 않습니다."
        }
        require(readPrefix(source, ZIP_MAGIC.size).contentEquals(ZIP_MAGIC)) {
            "CRX3 ZIP payload가 local file header로 시작하지 않습니다."
        }
        require(Files.isRegularFile(output, NOFOLLOW_LINKS) && !Files.isSymbolicLink(output)) {
            "CRX3 output 경로가 올바르지 않습니다."
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
        val signedPrefix = signedPayloadPrefix(signedHeaderData)
        val signature = signingPayload(source, signedPrefix).use(signingKey::signSha256Rsa).copyOf()
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
        require(readStableState(source) == sourceState) { "CRX3 source ZIP이 서명 중 변경되었습니다." }
        val sourceSha256 = sha256(source)

        FileChannel.open(output, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { channel ->
            val stream = java.nio.channels.Channels.newOutputStream(channel)
            stream.write(CRX_MAGIC)
            stream.write(uint32(CRX_VERSION))
            stream.write(uint32(header.size))
            stream.write(header)
            Files.newInputStream(source, NOFOLLOW_LINKS).use { input ->
                copyBounded(input, stream, sourceState.size)
            }
            stream.flush()
            channel.force(true)
        }
        require(readStableState(source) == sourceState) { "CRX3 source ZIP이 기록 중 변경되었습니다." }
        val verified = Crx3Verifier.verify(output, MAX_CRX_SIZE)
        require(
            verified.extensionId == extensionId &&
                verified.publicKeySha256 == sha256(publicKeyDer) &&
                verified.zipLength.toLong() == sourceState.size &&
                verified.zipSha256 == sourceSha256,
        ) {
            "생성된 CRX3 file proof가 일치하지 않습니다."
        }
        return CreatedCrx3File(
            extensionId = extensionId,
            path = output,
            crxSha256 = verified.crxSha256,
            zipSha256 = verified.zipSha256,
        )
    }

    private fun signedPayloadPrefix(signedHeaderData: ByteArray): ByteArray =
        ByteArrayOutputStream().run {
            write(SIGNED_DATA_CONTEXT)
            write(uint32(signedHeaderData.size))
            write(signedHeaderData)
            toByteArray()
        }

    private fun signingPayload(source: Path, prefix: ByteArray): InputStream =
        SequenceInputStream(
            ByteArrayInputStream(prefix),
            Files.newInputStream(source, NOFOLLOW_LINKS),
        )

    private fun copyBounded(input: InputStream, output: java.io.OutputStream, expected: Long) {
        val buffer = ByteArray(STREAM_BUFFER_SIZE)
        var copied = 0L
        while (copied < expected) {
            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), expected - copied).toInt())
            require(count > 0) { "CRX3 source ZIP이 잘렸습니다." }
            output.write(buffer, 0, count)
            copied += count
        }
        require(input.read() < 0) { "CRX3 source ZIP 크기가 변경되었습니다." }
    }

    private fun readPrefix(path: Path, size: Int): ByteArray =
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            ByteArray(size).also { bytes ->
                var offset = 0
                while (offset < bytes.size) {
                    val count = input.read(bytes, offset, bytes.size - offset)
                    require(count > 0) { "CRX3 source ZIP prefix가 잘렸습니다." }
                    offset += count
                }
            }
        }

    private fun readStableState(path: Path): StablePathState {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink) {
            "CRX3 source ZIP은 regular file이어야 합니다."
        }
        return StablePathState(
            fileKey = attributes.fileKey(),
            size = attributes.size(),
            modifiedMillis = attributes.lastModifiedTime().toMillis(),
        )
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

    private fun sha256(path: Path): String =
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count > 0) { "CRX3 source ZIP hash 읽기가 진행되지 않습니다." }
                digest.update(buffer, 0, count)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

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

    private data class StablePathState(
        val fileKey: Any?,
        val size: Long,
        val modifiedMillis: Long,
    )

    private const val MAX_HEADER_SIZE = 1024 * 1024
    private const val MAX_SIGNATURE_SIZE = MAX_HEADER_SIZE
    private const val MAX_ZIP_SIZE = 128L * 1024 * 1024
    private const val MAX_CRX_SIZE = MAX_ZIP_SIZE + MAX_HEADER_SIZE + 12L
    private const val CRX_VERSION = 3
    private const val STREAM_BUFFER_SIZE = 64 * 1024
    private const val RSA_PROOF_FIELD = 2
    private const val SIGNED_HEADER_DATA_FIELD = 10_000
    private val CRX_MAGIC = byteArrayOf('C'.code.toByte(), 'r'.code.toByte(), '2'.code.toByte(), '4'.code.toByte())
    private val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
    private val SIGNED_DATA_CONTEXT = "CRX3 SignedData\u0000".toByteArray(StandardCharsets.US_ASCII)
}
