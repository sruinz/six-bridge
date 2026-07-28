package dev.sixbridge.manager.conversion

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

fun interface ConversionBundleVerifier {
    fun verify(bundle: InputStream): Boolean
}

data class RestoredConversionJob(
    val token: String,
    val inputKind: OnDeviceInputKind,
    val inputSha256: String,
    val stage: ConversionJobStage,
    val approvedAdapterIds: Set<String>,
    val profileSha256: String?,
    val bundleSha256: String?,
)

internal data class ConversionWorkStoreHooks(
    val deleteTree: ((Path) -> Boolean)? = null,
    val listFiles: ((File) -> Array<File>?)? = null,
    val afterCreateClaim: () -> Unit = {},
    val beforeBundleSourceOpen: () -> Unit = {},
    val afterBundlePublished: () -> Unit = {},
    val afterBundleTransition: () -> Unit = {},
    val afterInputSnapshot: () -> Unit = {},
    val afterInputCopied: () -> Unit = {},
    val afterCopyChunk: (File) -> Unit = {},
    val afterBundleVerified: () -> Unit = {},
    val afterDiscardClaim: () -> Unit = {},
)

class ConversionWorkStore internal constructor(
    private val privateRoot: File,
    private val dao: ConversionJobDao,
    private val bundleVerifier: ConversionBundleVerifier,
    private val identityOwnerLifecycle: ConversionIdentityOwnerLifecycle,
    private val clock: () -> Long = { Clock.systemUTC().millis() },
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
    private val fileNameFactory: () -> String = { UUID.randomUUID().toString() },
    private val hooks: ConversionWorkStoreHooks = ConversionWorkStoreHooks(),
) {
    private val mutex = rootMutex(
        privateRoot.toPath().toAbsolutePath().normalize().toString(),
    )

    suspend fun create(
        artifact: OnDeviceInputArtifact,
    ): RestoredConversionJob = mutex.withLock {
        withContext(Dispatchers.IO) {
            validateArtifactBoundary(artifact)
            val root = requirePrivateRoot()
            val token = requireCanonicalUuid(tokenFactory(), "작업 token")
            val inputFileName = "${requireCanonicalUuid(fileNameFactory(), "입력 파일명")}.input"
            val discoveredCleanup = (
                listFiles(root)
                    ?: throw IllegalStateException("private 작업 저장소를 열거할 수 없습니다.")
                )
                .map(File::getName)
                .filter(::isCanonicalUuid)
                .toSet()
            var claimed = dao.claimCreate(
                ConversionJobEntity(
                    token = token,
                    generation = 0,
                    inputKind = artifact.kind.name,
                    inputSha256 = artifact.sha256,
                    inputFileName = inputFileName,
                    stage = ConversionJobStage.INPUT_READY.name,
                    approvedAdapterIdsJson = null,
                    profileSha256 = null,
                    bundleFileName = null,
                    bundleSha256 = null,
                    cleanupTokensJson = encodeCleanupTokens(discoveredCleanup),
                    updatedAtEpochMillis = now(),
                ),
            )
            hooks.afterCreateClaim()
            val tokenDirectory = directChild(root, token)
            try {
                Files.createDirectory(tokenDirectory.toPath())
            } catch (_: Exception) {
                throw IllegalStateException("private 작업 directory를 만들 수 없습니다.")
            }
            val inputFile = directChild(tokenDirectory, inputFileName)
            copyStableNew(
                source = artifact.privateFile,
                destination = inputFile,
                expectedSha256 = artifact.sha256,
                label = "Task 6 입력",
            )
            hooks.afterInputCopied()
            val verifiedInputState = verifyFileHash(inputFile, artifact.sha256)
            require(inputFile.setReadOnly()) {
                "private 입력을 불변 상태로 고정할 수 없습니다."
            }
            requireStableFile(inputFile, verifiedInputState, "입력 고정")
            val current = dao.get()
            check(current?.token == token && current.generation == claimed.generation) {
                "활성 작업이 바뀌었습니다."
            }
            claimed = drainCleanupTokens(root, claimed)
                ?: throw IllegalStateException("이전 private 작업 cleanup을 완료하지 못했습니다.")
            claimed.toRestored(tokenDirectory, inputAlreadyVerified = true)
                ?: throw IllegalStateException("새 작업을 복구할 수 없습니다.")
        }
    }

    suspend fun markAwaitingApproval(
        token: String,
        profileSha256: String,
    ): RestoredConversionJob = transition(
        token = token,
        expectedStage = ConversionJobStage.INPUT_READY,
        nextStage = ConversionJobStage.AWAITING_APPROVAL,
        approvedAdapterIds = null,
        profileSha256 = profileSha256,
        expectedProfileSha256 = null,
        bundleFileName = null,
        bundleSha256 = null,
    )

    suspend fun refreshAwaitingApproval(
        token: String,
        expectedProfileSha256: String,
        profileSha256: String,
    ): RestoredConversionJob = transition(
        token = token,
        expectedStage = ConversionJobStage.AWAITING_APPROVAL,
        nextStage = ConversionJobStage.AWAITING_APPROVAL,
        approvedAdapterIds = null,
        profileSha256 = profileSha256,
        expectedProfileSha256 = expectedProfileSha256,
        bundleFileName = null,
        bundleSha256 = null,
    )

    suspend fun markBuilding(
        token: String,
        approvedAdapterIds: Set<String>,
        profileSha256: String,
    ): RestoredConversionJob {
        require(approvedAdapterIds.all { it.isNotBlank() }) {
            "승인 adapter ID가 올바르지 않습니다."
        }
        return transition(
            token = token,
            expectedStage = ConversionJobStage.AWAITING_APPROVAL,
            nextStage = ConversionJobStage.BUILDING,
            approvedAdapterIds = approvedAdapterIds,
            profileSha256 = profileSha256,
            expectedProfileSha256 = profileSha256,
            bundleFileName = null,
            bundleSha256 = null,
        )
    }

    suspend fun markBundleReady(
        token: String,
        bundle: File,
        expectedSha256: String,
    ): RestoredConversionJob = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireSafeSha256(expectedSha256, "생성 bundle")
            val row = requireCurrent(token, ConversionJobStage.BUILDING)
            requireSafeSha256(row.profileSha256, "프로필")
            val root = requirePrivateRoot()
            val tokenDirectory = requireTokenDirectory(root, token)
            val baseName = requireCanonicalUuid(fileNameFactory(), "bundle 파일명")
            val temporary = directChild(tokenDirectory, ".$baseName.tmp")
            val bundleFileName = "$baseName.six"
            val published = directChild(tokenDirectory, bundleFileName)
            var databaseCommitted = false
            try {
                hooks.beforeBundleSourceOpen()
                copyStableNew(
                    source = bundle,
                    destination = temporary,
                    expectedSha256 = expectedSha256,
                    label = "생성 bundle",
                )
                publishVerifiedBundle(temporary, published, expectedSha256)
                hooks.afterBundlePublished()
                val updated = dao.transition(
                    token = token,
                    expectedStage = ConversionJobStage.BUILDING.name,
                    expectedGeneration = row.generation,
                    expectedProfileSha256 = row.profileSha256,
                    nextStage = ConversionJobStage.BUNDLE_READY.name,
                    approvedAdapterIdsJson = row.approvedAdapterIdsJson,
                    profileSha256 = row.profileSha256,
                    bundleFileName = bundleFileName,
                    bundleSha256 = expectedSha256,
                    updatedAtEpochMillis = now(),
                )
                check(updated == 1) { "활성 작업이 바뀌었습니다." }
                databaseCommitted = true
                hooks.afterBundleTransition()
                val stored = requireNotNull(dao.get())
                val restored = stored.toRestored(tokenDirectory)
                    ?: throw IllegalStateException("완료 작업을 복구할 수 없습니다.")
                identityOwnerLifecycle.finalizeBundleReady(
                    restored.token,
                    stored.generation,
                    requireNotNull(restored.bundleSha256),
                )
                restored
            } catch (error: Throwable) {
                if (!databaseCommitted) {
                    Files.deleteIfExists(temporary.toPath())
                }
                throw error
            }
        }
    }

    suspend fun restore(): RestoredConversionJob? = restoreInternal(
        preserveBuilding = false,
    )

    suspend fun restorePreservingBuilding(): RestoredConversionJob? = restoreInternal(
        preserveBuilding = true,
    )

    private suspend fun restoreInternal(
        preserveBuilding: Boolean,
    ): RestoredConversionJob? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val root = runCatching(::requirePrivateRoot).getOrNull() ?: return@withContext null
            var row = dao.get() ?: return@withContext null
            row = reconcileCleanupTokens(root, row) ?: return@withContext null
            if (isInvalidTimestamp(row.updatedAtEpochMillis)) {
                discardInvalid(root, row)
                return@withContext null
            }
            val stage = enumValueOrNull<ConversionJobStage>(row.stage)
            if (stage == null || !isCanonicalUuid(row.token)) {
                discardInvalid(root, row)
                return@withContext null
            }
            if (stage == ConversionJobStage.DISCARDING) {
                discardClaimed(root, row)
                return@withContext null
            }
            if (stage != ConversionJobStage.BUNDLE_READY && isExpired(row.updatedAtEpochMillis)) {
                discardInvalid(root, row)
                return@withContext null
            }
            row = drainCleanupTokens(root, row) ?: return@withContext null
            val tokenDirectory = runCatching { requireTokenDirectory(root, row.token) }.getOrNull()
            if (tokenDirectory == null || row.toRestored(tokenDirectory) == null) {
                discardInvalid(root, row)
                return@withContext null
            }
            if (stage == ConversionJobStage.BUILDING && !preserveBuilding) {
                if (!deleteUnreferencedChildren(tokenDirectory, row.inputFileName)) {
                    return@withContext null
                }
                val changed = dao.transition(
                    token = row.token,
                    expectedStage = ConversionJobStage.BUILDING.name,
                    expectedGeneration = row.generation,
                    expectedProfileSha256 = row.profileSha256,
                    nextStage = ConversionJobStage.INPUT_READY.name,
                    approvedAdapterIdsJson = null,
                    profileSha256 = null,
                    bundleFileName = null,
                    bundleSha256 = null,
                    updatedAtEpochMillis = now(),
                )
                if (changed != 1) return@withContext null
                row = requireNotNull(dao.get())
            }
            val restored = row.toRestored(tokenDirectory)
            if (restored == null) {
                discardInvalid(root, row)
                return@withContext null
            }
            if (restored.stage == ConversionJobStage.BUNDLE_READY) {
                identityOwnerLifecycle.finalizeBundleReady(
                    restored.token,
                    row.generation,
                    requireNotNull(restored.bundleSha256),
                )
            }
            restored
        }
    }

    suspend fun resetAwaitingApproval(
        token: String,
        profileSha256: String,
    ): RestoredConversionJob = transition(
        token = token,
        expectedStage = ConversionJobStage.BUILDING,
        nextStage = ConversionJobStage.AWAITING_APPROVAL,
        approvedAdapterIds = null,
        profileSha256 = profileSha256,
        expectedProfileSha256 = profileSha256,
        bundleFileName = null,
        bundleSha256 = null,
    )

    suspend fun discard(token: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isCanonicalUuid(token)) return@withContext false
            val row = dao.get()
            if (row?.token != token) return@withContext true
            val root = runCatching(::requirePrivateRoot).getOrNull() ?: return@withContext false
            val reconciled = reconcileCleanupTokens(root, row) ?: return@withContext false
            val claimed = claimDiscarding(reconciled) ?: return@withContext false
            hooks.afterDiscardClaim()
            discardClaimed(root, claimed)
        }
    }

    suspend fun <T> openVerifiedInput(
        token: String,
        consume: (InputStream) -> T,
    ): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val row = requireCurrent(token)
            val root = requirePrivateRoot()
            val tokenDirectory = requireTokenDirectory(root, token)
            val input = directChild(tokenDirectory, row.inputFileName)
            consumeVerifiedFile(
                file = input,
                expectedSha256 = row.inputSha256,
                beforeConsume = hooks.afterInputSnapshot,
                consume = consume,
            )
        }
    }

    suspend fun <T> withVerifiedInputFile(
        token: String,
        consume: (File) -> T,
    ): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val row = requireCurrent(token)
            val root = requirePrivateRoot()
            val tokenDirectory = requireTokenDirectory(root, token)
            val input = directChild(tokenDirectory, row.inputFileName)
            val state = verifyFileHash(input, row.inputSha256)
            hooks.afterInputSnapshot()
            requireStableFile(input, state, "Path 소비 직전")
            val result = consume(input)
            requireStableFile(input, state, "Path 소비")
            verifyFileHash(input, row.inputSha256)
            result
        }
    }

    suspend fun <T> openVerifiedBundle(
        token: String,
        consume: (InputStream) -> T,
    ): T = mutex.withLock {
        withContext(Dispatchers.IO) {
            val row = requireCurrent(token, ConversionJobStage.BUNDLE_READY)
            val name = row.bundleFileName
                ?: throw IllegalStateException("완료 bundle 파일명이 없습니다.")
            val expectedSha256 = row.bundleSha256
                ?: throw IllegalStateException("완료 bundle hash가 없습니다.")
            val root = requirePrivateRoot()
            val tokenDirectory = requireTokenDirectory(root, token)
            val bundle = directChild(tokenDirectory, name)
            consumeVerifiedBundle(bundle, expectedSha256, consume)
        }
    }

    private suspend fun transition(
        token: String,
        expectedStage: ConversionJobStage,
        nextStage: ConversionJobStage,
        approvedAdapterIds: Set<String>?,
        profileSha256: String?,
        expectedProfileSha256: String?,
        bundleFileName: String?,
        bundleSha256: String?,
    ): RestoredConversionJob = mutex.withLock {
        withContext(Dispatchers.IO) {
            val row = requireCurrent(token, expectedStage)
            requireSafeSha256(profileSha256, "프로필")
            expectedProfileSha256?.let { requireSafeSha256(it, "승인 프로필") }
            val encodedAdapterIds = approvedAdapterIds?.let(::encodeAdapterIds)
            val updated = dao.transition(
                token = token,
                expectedStage = expectedStage.name,
                expectedGeneration = row.generation,
                expectedProfileSha256 = expectedProfileSha256,
                nextStage = nextStage.name,
                approvedAdapterIdsJson = encodedAdapterIds,
                profileSha256 = profileSha256,
                bundleFileName = bundleFileName,
                bundleSha256 = bundleSha256,
                updatedAtEpochMillis = now(),
            )
            check(updated == 1) { "작업 stage 또는 generation이 바뀌었습니다." }
            val root = requirePrivateRoot()
            val tokenDirectory = requireTokenDirectory(root, row.token)
            requireNotNull(dao.get()).toRestored(tokenDirectory)
                ?: throw IllegalStateException("전환한 작업을 복구할 수 없습니다.")
        }
    }

    private suspend fun requireCurrent(
        token: String,
        stage: ConversionJobStage? = null,
    ): ConversionJobEntity {
        requireCanonicalUuid(token, "작업 token")
        val row = dao.get()
        check(row?.token == token) { "활성 작업이 일치하지 않습니다." }
        if (stage != null) {
            check(row.stage == stage.name) { "작업 단계를 전환할 수 없습니다." }
        }
        return row
    }

    private fun ConversionJobEntity.toRestored(
        tokenDirectory: File,
        inputAlreadyVerified: Boolean = false,
    ): RestoredConversionJob? {
        if (singletonId != ConversionJobEntity.SINGLETON_ID ||
            generation <= 0 ||
            !isCanonicalUuid(token)
        ) {
            return null
        }
        val cleanupTokens = decodeCleanupTokens(cleanupTokensJson) ?: return null
        if (cleanupTokens.any { !isCanonicalUuid(it) || it == token }) return null
        val inputKind = enumValueOrNull<OnDeviceInputKind>(inputKind) ?: return null
        val stage = enumValueOrNull<ConversionJobStage>(stage) ?: return null
        if (!isLowerHexSha256(inputSha256)) return null
        if (!isPrivateFileName(inputFileName, INPUT_SUFFIX)) return null
        val input = runCatching { directChild(tokenDirectory, inputFileName) }.getOrNull()
            ?: return null
        if (!isRegularDirectFile(tokenDirectory, input) ||
            !inputAlreadyVerified &&
            runCatching { verifyFileHash(input, inputSha256) }.isFailure
        ) {
            return null
        }
        val adapterIds = decodeAdapterIds(approvedAdapterIdsJson) ?: return null
        val restoredBundleSha256 = when (stage) {
            ConversionJobStage.INPUT_READY -> {
                if (approvedAdapterIdsJson != null || profileSha256 != null ||
                    bundleFileName != null || bundleSha256 != null
                ) {
                    return null
                }
                null
            }
            ConversionJobStage.AWAITING_APPROVAL -> {
                if (approvedAdapterIdsJson != null || !isLowerHexSha256(profileSha256) ||
                    bundleFileName != null || bundleSha256 != null
                ) {
                    return null
                }
                null
            }
            ConversionJobStage.BUILDING -> {
                if (approvedAdapterIdsJson == null || !isLowerHexSha256(profileSha256) ||
                    bundleFileName != null || bundleSha256 != null
                ) {
                    return null
                }
                null
            }
            ConversionJobStage.BUNDLE_READY -> {
                if (approvedAdapterIdsJson == null || !isLowerHexSha256(profileSha256) ||
                    !isLowerHexSha256(bundleSha256)
                ) {
                    return null
                }
                val name = bundleFileName ?: return null
                if (!isPrivateFileName(name, BUNDLE_SUFFIX)) return null
                val file = runCatching { directChild(tokenDirectory, name) }.getOrNull()
                    ?: return null
                if (!isRegularDirectFile(tokenDirectory, file)) return null
                if (runCatching {
                        verifyBundleFile(file, requireNotNull(bundleSha256))
                    }.isFailure
                ) return null
                bundleSha256
            }
            ConversionJobStage.DISCARDING -> return null
        }
        return RestoredConversionJob(
            token = token,
            inputKind = inputKind,
            inputSha256 = inputSha256,
            stage = stage,
            approvedAdapterIds = adapterIds,
            profileSha256 = profileSha256,
            bundleSha256 = restoredBundleSha256,
        )
    }

    private fun validateArtifactBoundary(artifact: OnDeviceInputArtifact) {
        requireCanonicalUuid(artifact.token, "입력 token")
        require(isLowerHexSha256(artifact.sha256)) { "입력 SHA-256이 올바르지 않습니다." }
        val input = artifact.privateFile
        require(input.name == TASK6_INPUT_FILE_NAME) { "Task 6 입력 파일명이 올바르지 않습니다." }
        val directory = input.parentFile ?: throw IllegalArgumentException("입력 경계가 없습니다.")
        require(directory.name == artifact.token) { "입력 token 경계가 일치하지 않습니다." }
        require(!Files.isSymbolicLink(directory.toPath())) {
            "입력 경계에 symbolic link가 있습니다."
        }
        require(isRegularDirectFile(directory, input)) { "입력이 token의 direct child가 아닙니다." }
    }

    private fun requirePrivateRoot(): File {
        if (!privateRoot.exists() && !privateRoot.mkdirs()) {
            throw IllegalStateException("private 작업 저장소를 만들 수 없습니다.")
        }
        require(privateRoot.isDirectory && !Files.isSymbolicLink(privateRoot.toPath())) {
            "private 작업 저장소 경계가 올바르지 않습니다."
        }
        return privateRoot.canonicalFile
    }

    private fun requireTokenDirectory(root: File, token: String): File {
        requireCanonicalUuid(token, "작업 token")
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

    private fun isRegularDirectFile(parent: File, file: File): Boolean = runCatching {
        file.absoluteFile.parentFile == parent.absoluteFile &&
            captureDirectFileState(file) != null
    }.getOrDefault(false)

    private fun captureDirectFileState(file: File): DirectFileState? {
        val path = file.toPath()
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) return null
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val fileKey = attributes.fileKey() ?: return null
        val linkCount = (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as? Number)
            ?.toLong() ?: return null
        if (linkCount != 1L) return null
        return DirectFileState(fileKey, attributes.size(), attributes.lastModifiedTime().toMillis())
    }

    private fun verifyFileHash(
        file: File,
        expectedSha256: String,
    ): DirectFileState =
        withStableFile(file) { channel, state ->
            require(hashChannel(channel) == expectedSha256) {
                "private 파일 hash가 일치하지 않습니다."
            }
            requireStableFile(file, state, "hash 검증")
            state
        }

    private fun verifyBundleFile(file: File, expectedSha256: String) {
        withStableFile(file) { channel, state ->
            verifyHashAndBundle(channel, file, state, expectedSha256)
        }
    }

    private fun publishVerifiedBundle(
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

    private fun <T> consumeVerifiedFile(
        file: File,
        expectedSha256: String,
        beforeConsume: () -> Unit = {},
        consume: (InputStream) -> T,
    ): T = withStableFile(file) { channel, state ->
        require(hashChannel(channel) == expectedSha256) {
            "private 파일 hash가 일치하지 않습니다."
        }
        requireStableFile(file, state, "hash 검증")
        beforeConsume()
        requireStableFile(file, state, "소비 직전")
        channel.position(0)
        val result = consume(BoundedChannelInputStream(channel, state.size))
        requireStableFile(file, state, "소비")
        result
    }

    private fun <T> consumeVerifiedBundle(
        file: File,
        expectedSha256: String,
        consume: (InputStream) -> T,
    ): T = withStableFile(file) { channel, state ->
        verifyHashAndBundle(channel, file, state, expectedSha256)
        requireStableFile(file, state, "bundle 소비 직전")
        channel.position(0)
        val result = consume(BoundedChannelInputStream(channel, state.size))
        requireStableFile(file, state, "bundle 소비")
        result
    }

    private fun verifyHashAndBundle(
        channel: FileChannel,
        file: File,
        state: DirectFileState,
        expectedSha256: String,
    ) {
        requireSafeSha256(expectedSha256, "private bundle")
        require(hashChannel(channel) == expectedSha256) {
            "private bundle hash가 일치하지 않습니다."
        }
        requireStableFile(file, state, "bundle hash 검증")
        channel.position(0)
        val stream = BoundedChannelInputStream(channel, state.size)
        require(bundleVerifier.verify(stream)) {
            "private bundle 재검증에 실패했습니다."
        }
        require(stream.remainingBytes == 0L) {
            "bundle verifier가 전체 snapshot을 소비하지 않았습니다."
        }
        hooks.afterBundleVerified()
        requireStableFile(file, state, "bundle 검증")
    }

    private inline fun <T> withStableFile(
        file: File,
        operation: (FileChannel, DirectFileState) -> T,
    ): T {
        val state = requireNotNull(captureDirectFileState(file)) {
            "private 파일이 regular 단일 link가 아닙니다."
        }
        return FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            requireStableFile(file, state, "open")
            operation(channel, state)
        }
    }

    private fun requireStableFile(
        file: File,
        expected: DirectFileState,
        operation: String,
    ) {
        require(captureDirectFileState(file) == expected) {
            "private 파일 identity가 $operation 중 변경되었습니다."
        }
    }

    private fun hashChannel(channel: FileChannel): String {
        channel.position(0)
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
        while (true) {
            val count = channel.read(buffer)
            if (count < 0) break
            require(count > 0) { "private hash 읽기가 진행되지 않습니다." }
            digest.update(buffer.array(), 0, count)
            buffer.clear()
        }
        return digest.digest().toHex()
    }

    private fun copyStableNew(
        source: File,
        destination: File,
        expectedSha256: String,
        label: String,
    ): DirectFileState {
        requireSafeSha256(expectedSha256, label)
        val before = requireNotNull(captureDirectFileState(source)) {
            "$label 파일이 regular 단일 link가 아닙니다."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var copiedState: DirectFileState? = null
        FileChannel.open(source.toPath(), READ, NOFOLLOW_LINKS).use { input ->
            require(captureDirectFileState(source) == before) {
                "$label identity가 open 중 변경되었습니다."
            }
            FileChannel.open(destination.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                val openedDestination = requireNotNull(captureDirectFileState(destination)) {
                    "$label private 복사가 regular 단일 link가 아닙니다."
                }
                val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count > 0) { "$label 읽기가 진행되지 않습니다." }
                    digest.update(buffer.array(), 0, count)
                    buffer.flip()
                    while (buffer.hasRemaining()) {
                        require(output.write(buffer) > 0) {
                            "$label private 복사가 진행되지 않습니다."
                        }
                    }
                    hooks.afterCopyChunk(destination)
                    buffer.clear()
                }
                output.force(true)
                val finalizedDestination =
                    requireNotNull(captureDirectFileState(destination)) {
                        "$label private 복사가 regular 단일 link가 아닙니다."
                    }
                require(finalizedDestination.fileKey == openedDestination.fileKey) {
                    "$label private 복사 identity가 변경되었습니다."
                }
                copiedState = finalizedDestination
            }
        }
        require(captureDirectFileState(source) == before) {
            "$label identity가 복사 중 변경되었습니다."
        }
        val copied = requireNotNull(copiedState)
        require(captureDirectFileState(destination) == copied) {
            "$label private 복사 identity가 close 중 변경되었습니다."
        }
        require(copied.size == before.size) {
            "$label private 복사 크기가 일치하지 않습니다."
        }
        require(digest.digest().toHex() == expectedSha256) {
            "$label hash가 예상값과 일치하지 않습니다."
        }
        return copied
    }

    private suspend fun drainCleanupTokens(
        root: File,
        initial: ConversionJobEntity,
    ): ConversionJobEntity? {
        var row = initial
        while (true) {
            val pending = decodeCleanupTokens(row.cleanupTokensJson) ?: return null
            val token = pending.firstOrNull() ?: return row
            if (!isCanonicalUuid(token) || token == row.token) return null
            identityOwnerLifecycle.abort(token)
            if (!deleteTokenDirectory(root, token)) return null
            val remaining = pending - token
            val changed = dao.updateCleanupTokens(
                token = row.token,
                expectedGeneration = row.generation,
                cleanupTokensJson = encodeCleanupTokens(remaining),
                updatedAtEpochMillis = now(),
            )
            if (changed != 1) return null
            row = dao.get() ?: return null
        }
    }

    private suspend fun reconcileCleanupTokens(
        root: File,
        row: ConversionJobEntity,
    ): ConversionJobEntity? {
        val decoded = decodeCleanupTokens(row.cleanupTokensJson)
        val canonical = if (decoded == null) {
            val children = listFiles(root) ?: return null
            children.map(File::getName)
                .filter(::isCanonicalUuid)
                .filterTo(sortedSetOf()) { it != row.token }
        } else {
            decoded.filterTo(sortedSetOf()) {
                isCanonicalUuid(it) && it != row.token
            }
        }
        if (decoded != null && decoded == canonical) return row
        val changed = dao.updateCleanupTokens(
            token = row.token,
            expectedGeneration = row.generation,
            cleanupTokensJson = encodeCleanupTokens(canonical),
            updatedAtEpochMillis = row.updatedAtEpochMillis,
        )
        return if (changed == 1) dao.get() else null
    }

    private suspend fun discardInvalid(root: File, row: ConversionJobEntity) {
        val claimed = claimDiscarding(row) ?: return
        hooks.afterDiscardClaim()
        discardClaimed(root, claimed)
    }

    private suspend fun claimDiscarding(row: ConversionJobEntity): ConversionJobEntity? {
        if (row.stage == ConversionJobStage.DISCARDING.name) return row
        val changed = dao.transition(
            token = row.token,
            expectedStage = row.stage,
            expectedGeneration = row.generation,
            expectedProfileSha256 = null,
            nextStage = ConversionJobStage.DISCARDING.name,
            approvedAdapterIdsJson = row.approvedAdapterIdsJson,
            profileSha256 = row.profileSha256,
            bundleFileName = row.bundleFileName,
            bundleSha256 = row.bundleSha256,
            updatedAtEpochMillis = row.updatedAtEpochMillis,
        )
        return if (changed == 1) dao.get() else null
    }

    private suspend fun discardClaimed(root: File, row: ConversionJobEntity): Boolean {
        require(row.stage == ConversionJobStage.DISCARDING.name) {
            "외부 작업 cleanup은 DISCARDING claim 뒤에만 실행할 수 있습니다."
        }
        if (!deleteOwnedTokenDirectories(root, row)) return false
        return dao.delete(row.token, row.generation) == 1
    }

    private suspend fun deleteOwnedTokenDirectories(root: File, row: ConversionJobEntity): Boolean {
        val cleanup = decodeCleanupTokens(row.cleanupTokensJson) ?: return false
        if (cleanup.any { !isCanonicalUuid(it) }) return false
        val tokens = if (isCanonicalUuid(row.token)) cleanup + row.token else cleanup
        if (tokens.any { !isCanonicalUuid(it) }) return false
        for (token in tokens) {
            identityOwnerLifecycle.abort(token)
            if (!deleteTokenDirectory(root, token)) return false
        }
        return true
    }

    private fun deleteUnreferencedChildren(
        tokenDirectory: File,
        inputFileName: String,
    ): Boolean {
        val children = listFiles(tokenDirectory) ?: return false
        return children
            .filter { it.name != inputFileName }
            .all { deleteTree(it.toPath()) }
    }

    private fun listFiles(directory: File): Array<File>? =
        hooks.listFiles?.invoke(directory) ?: if (hooks.listFiles == null) {
            directory.listFiles()
        } else {
            null
        }

    private fun deleteTokenDirectory(root: File, token: String): Boolean {
        if (!isCanonicalUuid(token)) return false
        return deleteTree(directChild(root, token).toPath())
    }

    private fun deleteTree(path: Path): Boolean {
        hooks.deleteTree?.let { return it(path) }
        if (!Files.exists(path, NOFOLLOW_LINKS)) return true
        return runCatching {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<Path>() {
                    override fun visitFile(
                        file: Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: Path,
                        error: IOException?,
                    ): FileVisitResult {
                        if (error != null) throw error
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            !Files.exists(path, NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    private fun encodeAdapterIds(ids: Set<String>): String =
        buildJsonArray {
            ids.toSortedSet().forEach { add(JsonPrimitive(it)) }
        }.toString()

    private fun decodeAdapterIds(encoded: String?): Set<String>? {
        if (encoded == null) return emptySet()
        val array = runCatching { Json.parseToJsonElement(encoded).jsonArray }.getOrNull()
            ?: return null
        val values = runCatching { array.map { it.jsonPrimitive.content } }.getOrNull()
            ?: return null
        if (values.any(String::isBlank) || values != values.distinct().sorted()) return null
        return values.toSet()
    }

    private fun isInvalidTimestamp(updatedAt: Long): Boolean {
        if (updatedAt < 0) return true
        val current = now()
        return updatedAt > current && updatedAt - current > MAX_CLOCK_SKEW_MILLIS
    }

    private fun isExpired(updatedAt: Long): Boolean {
        val current = now()
        return current >= updatedAt &&
            current - updatedAt >= INCOMPLETE_RETENTION_MILLIS
    }

    private fun now(): Long = clock().also {
        require(it >= 0) { "현재 시간이 올바르지 않습니다." }
    }

    private data class DirectFileState(
        val fileKey: Any,
        val size: Long,
        val modifiedAtMillis: Long,
    )

    companion object {
        const val INCOMPLETE_RETENTION_MILLIS = 24L * 60 * 60 * 1000
        const val MAX_CLOCK_SKEW_MILLIS = 5L * 60 * 1000
        private const val TASK6_INPUT_FILE_NAME = "input.bin"
        private const val INPUT_SUFFIX = ".input"
        private const val BUNDLE_SUFFIX = ".six"
        private const val STREAM_BUFFER_SIZE = 64 * 1024
        private val ROOT_MUTEXES = ConcurrentHashMap<String, Mutex>()

        private fun rootMutex(path: String): Mutex =
            ROOT_MUTEXES.computeIfAbsent(path) { Mutex() }
    }
}

private fun requireSafeSha256(value: String?, label: String) {
    require(isLowerHexSha256(value)) { "$label SHA-256이 올바르지 않습니다." }
}

private fun isLowerHexSha256(value: String?): Boolean =
    value != null && Regex("[0-9a-f]{64}").matches(value)

private fun isCanonicalUuid(value: String): Boolean =
    runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

private fun requireCanonicalUuid(value: String, label: String): String {
    require(isCanonicalUuid(value)) { "$label UUID가 올바르지 않습니다." }
    return value
}

private fun isPrivateFileName(value: String, suffix: String): Boolean =
    value.endsWith(suffix) &&
        isCanonicalUuid(value.removeSuffix(suffix))

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name == value }

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private class BoundedChannelInputStream(
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

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
            "stream buffer 범위가 올바르지 않습니다."
        }
        if (length == 0) return 0
        if (remainingBytes == 0L) return -1
        val requested = minOf(length.toLong(), remainingBytes).toInt()
        val count = channel.read(ByteBuffer.wrap(buffer, offset, requested))
        if (count < 0) return -1
        require(count > 0) { "private stream 읽기가 진행되지 않습니다." }
        remainingBytes -= count
        return count
    }

    override fun close() = Unit
}
