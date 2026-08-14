package dev.sixbridge.manager.conversion

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class VerifiedIdentityProof(
    val packageName: String,
    val extensionId: String,
    val wrapperSignerSha256: String,
    val versionCode: Int,
)

sealed interface CurrentBundleReadyProof {
    data object Absent : CurrentBundleReadyProof

    data class Verified(
        val ownerToken: String,
        val generation: Long,
        val bundleSha256: String,
        val proof: VerifiedIdentityProof,
    ) : CurrentBundleReadyProof

    data class Unreadable(
        val reason: String,
    ) : CurrentBundleReadyProof
}

fun interface CurrentBundleReadyProofReader {
    suspend fun read(): CurrentBundleReadyProof
}

internal fun interface CurrentBundleIdentityProofParser {
    fun read(bundle: InputStream): VerifiedIdentityProof
}

internal class StoredCurrentBundleReadyProofReader(
    privateRoot: File,
    private val dao: ConversionJobDao,
    bundleVerifier: ConversionBundleVerifier,
    private val proofParser: CurrentBundleIdentityProofParser,
) : CurrentBundleReadyProofReader {
    private val mutex = Mutex()
    private val storage = PrivateConversionBundleStorage(
        privateRoot = privateRoot,
        bundleVerifier = bundleVerifier,
    )

    override suspend fun read(): CurrentBundleReadyProof = mutex.withLock {
        withContext(Dispatchers.IO) {
            val row = dao.get()
            if (row?.stage != ConversionJobStage.BUNDLE_READY.name) {
                return@withContext CurrentBundleReadyProof.Absent
            }
            try {
                val proof = storage.consumeStored(row, proofParser::read)
                require(dao.get() == row) {
                    "BUNDLE_READY job proof가 읽기 중 변경되었습니다."
                }
                validateProof(proof)
                CurrentBundleReadyProof.Verified(
                    ownerToken = row.token,
                    generation = row.generation,
                    bundleSha256 = requireNotNull(row.bundleSha256),
                    proof = proof,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                CurrentBundleReadyProof.Unreadable(
                    error.message ?: "현재 BUNDLE_READY proof를 읽을 수 없습니다.",
                )
            }
        }
    }

    private fun validateProof(proof: VerifiedIdentityProof) {
        require(EXTENSION_ID.matches(proof.extensionId)) {
            "bundle extension ID proof가 올바르지 않습니다."
        }
        require(proof.packageName == "dev.sixbridge.ext.${proof.extensionId}") {
            "bundle package proof가 extension ID와 일치하지 않습니다."
        }
        require(SHA256.matches(proof.wrapperSignerSha256)) {
            "bundle wrapper signer proof가 올바르지 않습니다."
        }
        require(proof.versionCode > 0) {
            "bundle versionCode proof가 올바르지 않습니다."
        }
    }

    private companion object {
        val EXTENSION_ID = Regex("[a-p]{32}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal object ConversionPrivateRootLocks {
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    fun mutex(privateRoot: File): Mutex = mutexes.computeIfAbsent(
        privateRoot.toPath().toAbsolutePath().normalize().toString(),
    ) { Mutex() }
}

internal class PrivateConversionBundleStorage(
    private val privateRoot: File,
    private val bundleVerifier: ConversionBundleVerifier,
    private val afterBundleVerified: () -> Unit = {},
) {
    fun verifyStored(row: ConversionJobEntity) {
        requireStoredBundleRow(row)
        val root = requireRoot()
        val tokenDirectory = requireTokenDirectory(root, row.token)
        val bundle = directChild(tokenDirectory, requireNotNull(row.bundleFileName))
        withStableFile(bundle) { channel, state ->
            verifyHashAndBundle(
                channel = channel,
                file = bundle,
                state = state,
                expectedSha256 = requireNotNull(row.bundleSha256),
            )
        }
    }

    fun <T> consumeStored(
        row: ConversionJobEntity,
        consume: (InputStream) -> T,
    ): T {
        requireStoredBundleRow(row)
        val root = requireRoot()
        val tokenDirectory = requireTokenDirectory(root, row.token)
        val bundle = directChild(tokenDirectory, requireNotNull(row.bundleFileName))
        return withStableFile(bundle) { channel, state ->
            verifyHashAndBundle(
                channel = channel,
                file = bundle,
                state = state,
                expectedSha256 = requireNotNull(row.bundleSha256),
            )
            requireStableFile(bundle, state, "bundle 소비 직전")
            channel.position(0)
            val stream = PrivateBoundedChannelInputStream(channel, state.size)
            val result = consume(stream)
            require(stream.remainingBytes == 0L) {
                "bundle proof reader가 전체 snapshot을 소비하지 않았습니다."
            }
            requireStableFile(bundle, state, "bundle 소비")
            result
        }
    }

    fun publishVerified(
        temporary: File,
        published: File,
        expectedSha256: String,
    ) {
        withStableFile(temporary) { channel, state ->
            verifyHashAndBundle(channel, temporary, state, expectedSha256)
            Files.move(temporary.toPath(), published.toPath(), ATOMIC_MOVE)
            requireStableFile(published, state, "bundle publish")
            require(published.setReadOnly()) {
                "private bundle을 불변 상태로 고정할 수 없습니다."
            }
            requireStableFile(published, state, "bundle 고정")
        }
    }

    private fun requireStoredBundleRow(row: ConversionJobEntity) {
        require(row.singletonId == ConversionJobEntity.SINGLETON_ID && row.generation > 0) {
            "BUNDLE_READY job snapshot이 올바르지 않습니다."
        }
        require(isCanonicalUuid(row.token)) { "BUNDLE_READY job token이 올바르지 않습니다." }
        require(row.stage == ConversionJobStage.BUNDLE_READY.name) {
            "job이 BUNDLE_READY가 아닙니다."
        }
        require(isBundleName(row.bundleFileName)) {
            "BUNDLE_READY private 파일명이 올바르지 않습니다."
        }
        require(SHA256.matches(row.bundleSha256.orEmpty())) {
            "BUNDLE_READY bundle SHA-256이 올바르지 않습니다."
        }
    }

    private fun requireRoot(): File {
        require(privateRoot.isDirectory && !Files.isSymbolicLink(privateRoot.toPath())) {
            "private 작업 저장소 경계가 올바르지 않습니다."
        }
        return privateRoot.canonicalFile
    }

    private fun requireTokenDirectory(root: File, token: String): File {
        val directory = directChild(root, token)
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) {
            "작업 token directory가 올바르지 않습니다."
        }
        return directory
    }

    private fun directChild(parent: File, name: String): File {
        require(name.isNotEmpty() && name != "." && name != ".." &&
            '/' !in name && '\\' !in name && ':' !in name
        ) { "private 파일명이 올바르지 않습니다." }
        val child = File(parent, name)
        require(child.absoluteFile.parentFile == parent.absoluteFile) {
            "private direct child 경계를 벗어났습니다."
        }
        return child
    }

    private inline fun <T> withStableFile(
        file: File,
        operation: (FileChannel, PrivateDirectFileState) -> T,
    ): T {
        val state = requireNotNull(captureDirectFileState(file)) {
            "private bundle이 regular 단일 link가 아닙니다."
        }
        return FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            requireStableFile(file, state, "open")
            operation(channel, state)
        }
    }

    private fun verifyHashAndBundle(
        channel: FileChannel,
        file: File,
        state: PrivateDirectFileState,
        expectedSha256: String,
    ) {
        require(SHA256.matches(expectedSha256)) { "private bundle SHA-256이 올바르지 않습니다." }
        require(hashChannel(channel) == expectedSha256) {
            "private bundle hash가 일치하지 않습니다."
        }
        requireStableFile(file, state, "bundle hash 검증")
        channel.position(0)
        val stream = PrivateBoundedChannelInputStream(channel, state.size)
        require(bundleVerifier.verify(stream)) {
            "private bundle 재검증에 실패했습니다."
        }
        require(stream.remainingBytes == 0L) {
            "bundle verifier가 전체 snapshot을 소비하지 않았습니다."
        }
        afterBundleVerified()
        requireStableFile(file, state, "bundle 검증")
    }

    private fun captureDirectFileState(file: File): PrivateDirectFileState? {
        val path = file.toPath()
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return null
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val fileKey = attributes.fileKey() ?: return null
        val linkCount = (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as? Number)
            ?.toLong() ?: return null
        if (linkCount != 1L) return null
        return PrivateDirectFileState(
            fileKey = fileKey,
            size = attributes.size(),
            modifiedAtMillis = attributes.lastModifiedTime().toMillis(),
        )
    }

    private fun requireStableFile(
        file: File,
        expected: PrivateDirectFileState,
        operation: String,
    ) {
        require(captureDirectFileState(file) == expected) {
            "private bundle identity가 $operation 중 변경되었습니다."
        }
    }

    private fun hashChannel(channel: FileChannel): String {
        channel.position(0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
        while (true) {
            val count = channel.read(buffer)
            if (count < 0) break
            require(count > 0) { "private bundle hash 읽기가 진행되지 않습니다." }
            digest.update(buffer.array(), 0, count)
            buffer.clear()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val STREAM_BUFFER_SIZE = 64 * 1024
        val SHA256 = Regex("[0-9a-f]{64}")

        fun isCanonicalUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

        fun isBundleName(value: String?): Boolean = value != null &&
            value.endsWith(".six") &&
            isCanonicalUuid(value.removeSuffix(".six"))
    }
}

private data class PrivateDirectFileState(
    val fileKey: Any,
    val size: Long,
    val modifiedAtMillis: Long,
)

private class PrivateBoundedChannelInputStream(
    private val channel: FileChannel,
    size: Long,
) : InputStream() {
    private val singleByte = ByteArray(1)
    var remainingBytes: Long = size
        private set

    override fun read(): Int {
        val count = read(singleByte, 0, 1)
        return if (count < 0) -1 else singleByte[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
        if (length == 0) return 0
        if (remainingBytes == 0L) return -1
        val requested = minOf(length.toLong(), remainingBytes).toInt()
        val count = channel.read(ByteBuffer.wrap(buffer, offset, requested))
        require(count > 0) { "private bundle snapshot 읽기가 진행되지 않습니다." }
        remainingBytes -= count
        return count
    }

    override fun close() = Unit
}
