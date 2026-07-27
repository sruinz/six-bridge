package dev.sixbridge.manager.conversion

import android.content.Context
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.manager.packager.OnDeviceWrapperResult
import dev.sixbridge.manager.wrapper.AndroidArchiveInspector
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.WrapperVerificationResult
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

data class GeneratedBundle(
    val token: String,
    val file: File,
    val sha256: String,
    val suggestedFileName: String,
)

enum class GeneratedBundleStoreErrorCode {
    INVALID_INPUT,
    VERIFY_FAILED,
    NOT_FOUND,
    HASH_MISMATCH,
    PUBLISH_FAILED,
    COPY_FAILED,
    CLEANUP_FAILED,
}

class GeneratedBundleStoreException(
    val code: GeneratedBundleStoreErrorCode,
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

fun interface GeneratedWrapperVerifier {
    fun verify(
        wrapper: File,
        descriptor: ContractExtensionDescriptor,
        report: ContractCompatibilityReport,
    ): Boolean
}

internal data class GeneratedBundleStoreHooks(
    val afterBundleWritten: (File) -> Unit = {},
    val beforeFinalPublish: (File) -> Unit = {},
    val afterCopyChunk: () -> Unit = {},
    val deleteFile: ((File) -> Boolean)? = null,
    val deletePublishedFile: ((File) -> Boolean)? = null,
)

class GeneratedBundleStore internal constructor(
    filesDir: File,
    private val wrapperVerifier: GeneratedWrapperVerifier,
    private val hooks: GeneratedBundleStoreHooks = GeneratedBundleStoreHooks(),
) {
    constructor(context: Context) : this(
        filesDir = context.applicationContext.filesDir,
        wrapperVerifier = GeneratedWrapperVerifier { wrapper, descriptor, report ->
            AndroidWrapperVerifier(
                AndroidArchiveInspector(context.applicationContext.packageManager),
                File(context.applicationContext.filesDir, ROOT_DIRECTORY),
            ).verify(wrapper, descriptor, report) is WrapperVerificationResult.Success
        },
    )

    private val root = TrustedGeneratedRoot.create(filesDir)
    private val lock = rootLock(root.path.toString())
    private val published = mutableMapOf<String, PublishedBundle>()

    init {
        synchronized(lock) {
            cleanupOrphanTemporaryFiles()
        }
    }

    fun publish(token: String, result: OnDeviceWrapperResult): GeneratedBundle =
        synchronized(lock) {
            val canonicalToken = requireToken(token)
            root.verifyUnchanged()
            validateResult(result)
            val output = directChild(root.path, "$canonicalToken.six")
            recoverExistingPublish(canonicalToken, output.toFile(), result)?.let {
                return@synchronized it
            }
            rejectPublishTarget(output)
            var temporary: File? = null
            var incompletePublished: File? = null
            var primaryFailure: Throwable? = null
            try {
                temporary = Files.createTempFile(
                    root.path,
                    ".$canonicalToken.",
                    ".tmp",
                ).toFile()
                writeBundle(temporary, result)
                hooks.afterBundleWritten(temporary)
                val verified = verifyBundleFile(
                    temporary,
                    result.descriptor,
                    result.compatibility,
                )
                root.verifyUnchanged()
                hooks.beforeFinalPublish(output.toFile())
                root.verifyUnchanged()
                rejectPublishTarget(output)
                val temporaryState = requireDirectFile(temporary, "temporary bundle")
                require(temporary.setReadOnly()) {
                    "생성 bundle을 불변 상태로 고정할 수 없습니다."
                }
                Files.move(temporary.toPath(), output, ATOMIC_MOVE)
                temporary = null
                incompletePublished = output.toFile()
                requireStableFile(output.toFile(), temporaryState, "bundle publish")
                forceDirectory(root.path)
                val publishedState = requireDirectFile(output.toFile(), "published bundle")
                require(
                    publishedState.fileKey == temporaryState.fileKey &&
                        publishedState.size == temporaryState.size,
                ) {
                    "생성 bundle identity가 publish 중 변경되었습니다."
                }
                val record = PublishedBundle(
                    sha256 = verified.sha256,
                    state = publishedState,
                    suggestedFileName =
                        "${result.extensionId}-${result.versionCode}.six",
                    extension = verified.extension,
                    compatibility = verified.compatibility,
                )
                published[canonicalToken] = record
                incompletePublished = null
                GeneratedBundle(
                    token = canonicalToken,
                    file = output.toFile(),
                    sha256 = record.sha256,
                    suggestedFileName = record.suggestedFileName,
                )
            } catch (error: Throwable) {
                primaryFailure = error
                throw error.asStoreFailure()
            } finally {
                val temporaryCleanupFailed =
                    temporary?.let { !deleteOwnedFile(it) } == true
                val publishedCleanupFailed =
                    incompletePublished?.let { !deleteOwnedFile(it) } == true
                val cleanupFailed = temporaryCleanupFailed || publishedCleanupFailed
                if (cleanupFailed) {
                    throw GeneratedBundleStoreException(
                        GeneratedBundleStoreErrorCode.CLEANUP_FAILED,
                        "생성 bundle temporary 파일을 정리할 수 없습니다.",
                        primaryFailure,
                    )
                }
            }
        }

    fun open(token: String): InputStream = synchronized(lock) {
        val (file, record) = requirePublished(token)
        val channel = try {
            FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS)
        } catch (error: Exception) {
            throw GeneratedBundleStoreException(
                GeneratedBundleStoreErrorCode.NOT_FOUND,
                "생성 bundle을 열 수 없습니다.",
                error,
            )
        }
        try {
            requireStableFile(file, record.state, "open")
            require(hashChannel(channel) == record.sha256) {
                "생성 bundle hash가 게시 proof와 일치하지 않습니다."
            }
            requireStableFile(file, record.state, "open hash 검증")
            channel.position(0)
            StableBundleInputStream(
                channel = channel,
                file = file,
                root = root,
                expectedState = record.state,
                expectedSha256 = record.sha256,
            )
        } catch (error: Throwable) {
            channel.close()
            throw error.asStoreFailure(GeneratedBundleStoreErrorCode.HASH_MISMATCH)
        }
    }

    fun copyTo(token: String, output: OutputStream): String = synchronized(lock) {
        val (file, record) = requirePublished(token)
        try {
            withStableChannel(file, record.state) { channel ->
                require(hashChannel(channel) == record.sha256) {
                    "생성 bundle hash가 게시 proof와 일치하지 않습니다."
                }
                requireStableFile(file, record.state, "copy hash 검증")
                channel.position(0)
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
                var remaining = record.state.size
                while (remaining > 0L) {
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                    val count = channel.read(buffer)
                    require(count > 0) { "생성 bundle 복사가 진행되지 않습니다." }
                    output.write(buffer.array(), 0, count)
                    digest.update(buffer.array(), 0, count)
                    remaining -= count
                    hooks.afterCopyChunk()
                    requireStableFile(file, record.state, "copy")
                }
                require(channel.read(ByteBuffer.allocate(1)) < 0) {
                    "생성 bundle snapshot 범위가 변경되었습니다."
                }
                val outputSha256 = digest.digest().toHex()
                require(outputSha256 == record.sha256) {
                    "저장 copy hash가 내부 bundle hash와 일치하지 않습니다."
                }
                outputSha256
            }
        } catch (error: Throwable) {
            throw error.asStoreFailure(GeneratedBundleStoreErrorCode.COPY_FAILED)
        }
    }

    fun discard(token: String): Boolean = synchronized(lock) {
        if (!isToken(token)) return@synchronized false
        val file = directChild(root.path, "$token.six").toFile()
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return@synchronized false
        val record = published[token] ?: runCatching {
            recoverPublished(token, file).record
        }.getOrElse {
            return@synchronized false
        }
        if (runCatching {
                root.verifyUnchanged()
                requireStableFile(file, record.state, "discard")
            }.isFailure
        ) {
            return@synchronized false
        }
        val deleted = hooks.deletePublishedFile?.let {
            it(file) && !Files.exists(file.toPath(), NOFOLLOW_LINKS)
        } ?: deleteOwnedFile(file)
        if (!deleted) return@synchronized false
        published.remove(token)
        true
        }

    private fun writeBundle(
        temporary: File,
        result: OnDeviceWrapperResult,
    ) {
        require(SHA256.matches(result.wrapperSha256)) {
            "생성 wrapper SHA-256이 올바르지 않습니다."
        }
        val wrapperState = requireRegularFile(result.outputApk, "생성 wrapper")
        require(wrapperState.size in 1..SixBundleContract.MAX_WRAPPER_SIZE) {
            "생성 wrapper 크기가 제한을 초과합니다."
        }
        withStableChannel(result.outputApk, wrapperState) { channel ->
            require(hashChannel(channel) == result.wrapperSha256) {
                "생성 wrapper hash가 일치하지 않습니다."
            }
        }
        Files.newOutputStream(
            temporary.toPath(),
            WRITE,
            TRUNCATE_EXISTING,
            NOFOLLOW_LINKS,
        ).use { outputStream ->
            SixBundleContract.write(
                output = outputStream,
                wrapperApk = result.outputApk.toPath(),
                extension = result.descriptor,
                compatibility = result.compatibility,
            )
        }
        requireStableFile(result.outputApk, wrapperState, "SixBundle 기록")
        forceFile(temporary.toPath())
    }

    private fun verifyBundleFile(
        bundle: File,
        expectedDescriptor: ContractExtensionDescriptor? = null,
        expectedReport: ContractCompatibilityReport? = null,
    ): VerifiedBundle {
        val state = requireDirectFile(bundle, "temporary bundle")
        preflightBundleLimits(bundle, state)
        val extractedPath = directChild(
            root.path,
            ".wrapper.${UUID.randomUUID()}.apk",
        )
        var primaryFailure: Throwable? = null
        try {
            val verified = withStableChannel(bundle, state) { channel ->
                val sha256 = hashChannel(channel)
                requireStableFile(bundle, state, "bundle hash 검증")
                channel.position(0)
                val bounded = GeneratedBoundedChannelInputStream(channel, state.size)
                val contents = SixBundleContract.read(bounded, extractedPath)
                require(bounded.remaining == 0L) {
                    "SixBundle reader가 전체 bundle snapshot을 소비하지 않았습니다."
                }
                requireStableFile(bundle, state, "SixBundle 재검증")
                VerifiedContents(
                    sha256 = sha256,
                    extension = contents.extension,
                    compatibility = contents.compatibility,
                )
            }
            if (expectedDescriptor != null || expectedReport != null) {
                require(
                    verified.extension == expectedDescriptor &&
                        verified.compatibility == expectedReport,
                ) {
                    "SixBundle 재검증 결과가 package 결과와 일치하지 않습니다."
                }
            }
            require(
                wrapperVerifier.verify(
                    extractedPath.toFile(),
                    verified.extension,
                    verified.compatibility,
                ),
            ) {
                "생성 bundle wrapper 재검증에 실패했습니다."
            }
            return VerifiedBundle(
                sha256 = verified.sha256,
                extension = verified.extension,
                compatibility = verified.compatibility,
            )
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            val extracted = extractedPath.toFile()
            if (Files.exists(extractedPath, NOFOLLOW_LINKS) && !deleteOwnedFile(extracted)) {
                throw GeneratedBundleStoreException(
                    GeneratedBundleStoreErrorCode.CLEANUP_FAILED,
                    "wrapper 검증 temporary 파일을 정리할 수 없습니다.",
                    primaryFailure,
                )
            }
        }
    }

    private fun validateResult(result: OnDeviceWrapperResult) {
        require(
            result.extensionId == result.descriptor.extensionId &&
                result.packageName == result.descriptor.wrapperPackage &&
                result.versionCode == result.descriptor.wrapperVersionCode &&
                result.signerSha256 == result.descriptor.signerSha256 &&
                result.crxSha256 == result.descriptor.crxSha256 &&
                result.wrapperSha256 == result.descriptor.wrapperSha256 &&
                result.compatibility.convertedExtensionId == result.extensionId,
        ) {
            "생성 wrapper 결과의 descriptor 또는 report identity가 일치하지 않습니다."
        }
    }

    private fun requirePublished(token: String): Pair<File, PublishedBundle> {
        val canonicalToken = requireToken(token)
        root.verifyUnchanged()
        val file = directChild(root.path, "$canonicalToken.six").toFile()
        val record = published[canonicalToken] ?: recoverPublished(canonicalToken, file).record
        return file to record
    }

    private fun recoverExistingPublish(
        token: String,
        file: File,
        result: OnDeviceWrapperResult,
    ): GeneratedBundle? {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return null
        val recovered = try {
            recoverPublished(token, file)
        } catch (error: Throwable) {
            throw error.asStoreFailure(GeneratedBundleStoreErrorCode.VERIFY_FAILED)
        }
        require(
            recovered.record.extension == result.descriptor &&
                recovered.record.compatibility == result.compatibility &&
                recovered.record.extension.wrapperSha256 == result.wrapperSha256,
        ) {
            "같은 token에 다른 생성 bundle이 이미 게시되었습니다."
        }
        return GeneratedBundle(
            token = token,
            file = file,
            sha256 = recovered.record.sha256,
            suggestedFileName = recovered.record.suggestedFileName,
        )
    }

    private fun recoverPublished(token: String, file: File): RecoveredPublishedBundle {
        val verified = verifyBundleFile(file)
        if (file.canWrite()) {
            require(file.setReadOnly()) { "복구한 생성 bundle을 불변 상태로 고정할 수 없습니다." }
            forceDirectory(root.path)
        }
        val state = requireDirectFile(file, "recovered bundle")
        val record = PublishedBundle(
            sha256 = verified.sha256,
            state = state,
            suggestedFileName =
                "${verified.extension.extensionId}-${verified.extension.wrapperVersionCode}.six",
            extension = verified.extension,
            compatibility = verified.compatibility,
        )
        published[token] = record
        return RecoveredPublishedBundle(record)
    }

    private fun cleanupOrphanTemporaryFiles() {
        root.verifyUnchanged()
        Files.newDirectoryStream(root.path).use { entries ->
            entries.forEach { path ->
                if (!ORPHAN_TEMP_NAME.matches(path.fileName.toString()) &&
                    !ORPHAN_WRAPPER_NAME.matches(path.fileName.toString())
                ) {
                    return@forEach
                }
                val file = path.toFile()
                requireDirectFile(file, "orphan temporary bundle")
                if (!deleteOwnedFile(file)) {
                    throw GeneratedBundleStoreException(
                        GeneratedBundleStoreErrorCode.CLEANUP_FAILED,
                        "중단된 생성 bundle temporary 파일을 정리할 수 없습니다.",
                    )
                }
            }
        }
        forceDirectory(root.path)
    }

    private fun preflightBundleLimits(bundle: File, state: DirectFileState) {
        require(state.size in 1..SixBundleContract.MAX_ARCHIVE_SIZE) {
            "생성 SixBundle archive 크기가 온디바이스 안전 상한을 초과합니다."
        }
        ZipFile(bundle).use { zip ->
            val wrapper = zip.getEntry(SixBundleContract.WRAPPER_ENTRY)
                ?: throw IllegalArgumentException("생성 SixBundle wrapper.apk가 없습니다.")
            require(wrapper.size in 1..SixBundleContract.MAX_WRAPPER_SIZE) {
                "생성 wrapper 크기가 온디바이스 안전 상한을 초과합니다."
            }
        }
    }

    private fun rejectPublishTarget(output: Path) {
        require(output.parent == root.path) {
            "생성 bundle은 private root의 direct child여야 합니다."
        }
        require(!Files.exists(output, NOFOLLOW_LINKS)) {
            "생성 bundle output이 이미 존재합니다."
        }
    }

    private fun <T> withStableChannel(
        file: File,
        state: DirectFileState,
        block: (FileChannel) -> T,
    ): T = FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
        requireStableFile(file, state, "channel open")
        block(channel)
    }

    private fun deleteOwnedFile(file: File): Boolean {
        if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return true
        hooks.deleteFile?.let { return it(file) && !Files.exists(file.toPath(), NOFOLLOW_LINKS) }
        return runCatching {
            Files.deleteIfExists(file.toPath())
            !Files.exists(file.toPath(), NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    private fun requireDirectFile(file: File, label: String): DirectFileState =
        captureDirectFile(file) ?: throw GeneratedBundleStoreException(
            GeneratedBundleStoreErrorCode.INVALID_INPUT,
            "$label 파일이 regular 단일 link가 아닙니다.",
        )

    private fun requireRegularFile(file: File, label: String): DirectFileState =
        captureRegularFile(file) ?: throw GeneratedBundleStoreException(
            GeneratedBundleStoreErrorCode.INVALID_INPUT,
            "$label 파일이 regular 단일 link가 아닙니다.",
        )

    private fun requireStableFile(
        file: File,
        expected: DirectFileState,
        operation: String,
    ) {
        require(captureRegularFile(file) == expected) {
            "생성 bundle identity가 $operation 중 변경되었습니다."
        }
    }

    private fun captureDirectFile(file: File): DirectFileState? = runCatching {
        val path = file.toPath()
        if (path.parent != root.path) return null
        captureRegularFile(file)
    }.getOrNull()

    private fun captureRegularFile(file: File): DirectFileState? = runCatching {
        val path = file.toPath()
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return null
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        val fileKey = attributes.fileKey() ?: return null
        val linkCount = (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as? Number)
            ?.toLong() ?: return null
        if (linkCount != 1L) return null
        DirectFileState(
            fileKey = fileKey,
            size = attributes.size(),
            modifiedAtMillis = attributes.lastModifiedTime().toMillis(),
        )
    }.getOrNull()

    private fun hashChannel(channel: FileChannel): String {
        channel.position(0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
        while (true) {
            val count = channel.read(buffer)
            if (count < 0) break
            require(count > 0) { "생성 bundle hash 읽기가 진행되지 않습니다." }
            digest.update(buffer.array(), 0, count)
            buffer.clear()
        }
        return digest.digest().toHex()
    }

    private fun directChild(parent: Path, name: String): Path {
        require(
            name.isNotEmpty() && name != "." && name != ".." &&
                '/' !in name && '\\' !in name && ':' !in name,
        ) {
            "생성 bundle private 파일명이 올바르지 않습니다."
        }
        val child = parent.resolve(name).toAbsolutePath().normalize()
        require(child.parent == parent) { "생성 bundle direct child 경계를 벗어났습니다." }
        return child
    }

    private fun requireToken(token: String): String {
        require(isToken(token)) { "생성 bundle token UUID가 올바르지 않습니다." }
        return token
    }

    private data class PublishedBundle(
        val sha256: String,
        val state: DirectFileState,
        val suggestedFileName: String,
        val extension: ContractExtensionDescriptor,
        val compatibility: ContractCompatibilityReport,
    )

    private data class VerifiedBundle(
        val sha256: String,
        val extension: ContractExtensionDescriptor,
        val compatibility: ContractCompatibilityReport,
    )

    private data class RecoveredPublishedBundle(
        val record: PublishedBundle,
    )

    private data class VerifiedContents(
        val sha256: String,
        val extension: ContractExtensionDescriptor,
        val compatibility: ContractCompatibilityReport,
    )

    private data class DirectFileState(
        val fileKey: Any,
        val size: Long,
        val modifiedAtMillis: Long,
    )

    private class StableBundleInputStream(
        private val channel: FileChannel,
        private val file: File,
        private val root: TrustedGeneratedRoot,
        private val expectedState: DirectFileState,
        private val expectedSha256: String,
    ) : InputStream() {
        private val single = ByteArray(1)
        private val digest = MessageDigest.getInstance("SHA-256")
        private var remaining = expectedState.size
        private var closed = false
        private var verified = false

        override fun read(): Int {
            val count = read(single, 0, 1)
            return if (count < 0) -1 else single[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
                "stream buffer 범위가 올바르지 않습니다."
            }
            check(!closed) { "생성 bundle stream이 닫혔습니다." }
            if (length == 0) return 0
            if (remaining == 0L) {
                verifyComplete()
                return -1
            }
            verifyStable()
            val requested = minOf(length.toLong(), remaining).toInt()
            val count = channel.read(ByteBuffer.wrap(buffer, offset, requested))
            if (count < 0) throw IOException("생성 bundle stream이 잘렸습니다.")
            if (count == 0) throw IOException("생성 bundle stream 읽기가 진행되지 않습니다.")
            digest.update(buffer, offset, count)
            remaining -= count
            verifyStable()
            if (remaining == 0L) verifyComplete()
            return count
        }

        override fun close() {
            if (closed) return
            var verificationFailure: Throwable? = null
            try {
                while (remaining > 0L) {
                    val buffer = ByteArray(STREAM_BUFFER_SIZE)
                    val count = read(buffer)
                    if (count < 0) break
                }
                verifyComplete()
            } catch (error: Throwable) {
                verificationFailure = error
            }
            closed = true
            channel.close()
            if (verificationFailure != null) {
                throw IOException("생성 bundle stream identity가 변경되었습니다.", verificationFailure)
            }
        }

        private fun verifyStable() {
            root.verifyUnchanged()
            require(readDirectFileState(file, root.path) == expectedState) {
                "생성 bundle stream identity가 변경되었습니다."
            }
        }

        private fun verifyComplete() {
            if (verified) return
            verifyStable()
            if (remaining != 0L || digest.digest().toHex() != expectedSha256) {
                throw IOException("생성 bundle stream hash가 게시 proof와 일치하지 않습니다.")
            }
            verified = true
        }
    }

    private data class TrustedGeneratedRoot(
        val path: Path,
        val realPath: Path,
        val fileKey: Any,
    ) {
        fun verifyUnchanged() {
            require(
                Files.isDirectory(path, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(path),
            ) {
                "생성 bundle private root가 변경되었습니다."
            }
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            require(
                attributes.fileKey() == fileKey &&
                    path.toRealPath(NOFOLLOW_LINKS) == realPath,
            ) {
                "생성 bundle private root가 변경되었습니다."
            }
        }

        companion object {
            fun create(filesDir: File): TrustedGeneratedRoot {
                val files = filesDir.toPath().toAbsolutePath().normalize()
                require(
                    Files.isDirectory(files, NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(files),
                ) {
                    "앱 private filesDir가 올바르지 않습니다."
                }
                val root = files.resolve(ROOT_DIRECTORY)
                if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectory(root)
                require(
                    Files.isDirectory(root, NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(root),
                ) {
                    "생성 bundle private root가 올바르지 않습니다."
                }
                val attributes = Files.readAttributes(
                    root,
                    BasicFileAttributes::class.java,
                    NOFOLLOW_LINKS,
                )
                val fileKey = attributes.fileKey()
                    ?: throw IllegalArgumentException("생성 bundle private root fileKey가 없습니다.")
                return TrustedGeneratedRoot(
                    path = root,
                    realPath = root.toRealPath(NOFOLLOW_LINKS),
                    fileKey = fileKey,
                )
            }
        }
    }

    companion object {
        private const val ROOT_DIRECTORY = "generated-bundles"
        private const val STREAM_BUFFER_SIZE = 64 * 1024
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val ORPHAN_TEMP_NAME = Regex(
            """\.[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\..+\.tmp""",
        )
        private val ORPHAN_WRAPPER_NAME = Regex(
            """\.wrapper\.[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\.apk""",
        )
        private val ROOT_LOCKS = ConcurrentHashMap<String, Any>()

        private fun rootLock(path: String): Any =
            ROOT_LOCKS.computeIfAbsent(path) { Any() }

        private fun isToken(token: String): Boolean =
            runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)

        private fun forceFile(path: Path) {
            FileChannel.open(path, WRITE).use { it.force(true) }
        }

        private fun forceDirectory(path: Path) {
            FileChannel.open(path, READ).use { it.force(true) }
        }

        private fun Throwable.asStoreFailure(
            fallback: GeneratedBundleStoreErrorCode =
                GeneratedBundleStoreErrorCode.PUBLISH_FAILED,
        ): GeneratedBundleStoreException = when (this) {
            is GeneratedBundleStoreException -> this
            else -> GeneratedBundleStoreException(
                fallback,
                message ?: "생성 bundle 작업에 실패했습니다.",
                this,
            )
        }

        private fun readDirectFileState(
            file: File,
            root: Path,
        ): DirectFileState? = runCatching {
            val path = file.toPath()
            if (path.parent != root ||
                !Files.isRegularFile(path, NOFOLLOW_LINKS) ||
                Files.isSymbolicLink(path)
            ) {
                return null
            }
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            val fileKey = attributes.fileKey() ?: return null
            val linkCount = (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as? Number)
                ?.toLong() ?: return null
            if (linkCount != 1L) return null
            DirectFileState(
                fileKey,
                attributes.size(),
                attributes.lastModifiedTime().toMillis(),
            )
        }.getOrNull()
    }
}

private class GeneratedBoundedChannelInputStream(
    private val channel: FileChannel,
    size: Long,
) : InputStream() {
    private val single = ByteArray(1)
    var remaining: Long = size
        private set

    override fun read(): Int {
        val count = read(single, 0, 1)
        return if (count < 0) -1 else single[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
            "stream buffer 범위가 올바르지 않습니다."
        }
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val requested = minOf(length.toLong(), remaining).toInt()
        val count = channel.read(ByteBuffer.wrap(buffer, offset, requested))
        if (count < 0) return -1
        require(count > 0) { "bounded bundle stream 읽기가 진행되지 않습니다." }
        remaining -= count
        return count
    }

    override fun close() = Unit
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
