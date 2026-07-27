package dev.sixbridge.manager.conversion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
    fun verify(bundle: File): Boolean
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
    val afterCreateClaim: () -> Unit = {},
    val beforeBundleSourceOpen: () -> Unit = {},
    val afterBundlePublished: () -> Unit = {},
    val afterBundleTransition: () -> Unit = {},
    val afterInputSnapshot: () -> Unit = {},
)

class ConversionWorkStore internal constructor(
    private val privateRoot: File,
    private val dao: ConversionJobDao,
    private val bundleVerifier: ConversionBundleVerifier,
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
            val inputBytes = validateAndReadArtifact(artifact)
            val root = requirePrivateRoot()
            val token = requireCanonicalUuid(tokenFactory(), "작업 token")
            val inputFileName = "${requireCanonicalUuid(fileNameFactory(), "입력 파일명")}.input"
            val discoveredCleanup = root.listFiles().orEmpty()
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
            writeNew(inputBytes, inputFile)
            require(readVerifiedBytes(inputFile, artifact.sha256).contentEquals(inputBytes)) {
                "복사한 private 입력 hash가 일치하지 않습니다."
            }
            require(inputFile.setReadOnly()) {
                "private 입력을 불변 상태로 고정할 수 없습니다."
            }
            val current = dao.get()
            check(current?.token == token && current.generation == claimed.generation) {
                "활성 작업이 바뀌었습니다."
            }
            claimed = drainCleanupTokens(root, claimed)
                ?: throw IllegalStateException("이전 private 작업 cleanup을 완료하지 못했습니다.")
            claimed.toRestored(tokenDirectory)
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
                val copiedSha256 = copyAndHashNew(bundle, temporary)
                require(copiedSha256 == expectedSha256) {
                    "생성 bundle hash가 예상값과 일치하지 않습니다."
                }
                require(bundleVerifier.verify(temporary)) {
                    "복사한 private bundle 재검증에 실패했습니다."
                }
                require(readVerifiedBytes(temporary, expectedSha256).isNotEmpty()) {
                    "복사한 private bundle이 비어 있습니다."
                }
                Files.move(temporary.toPath(), published.toPath(), ATOMIC_MOVE)
                require(published.setReadOnly()) {
                    "private bundle을 불변 상태로 고정할 수 없습니다."
                }
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
                stored.toRestored(tokenDirectory)
                    ?: throw IllegalStateException("완료 작업을 복구할 수 없습니다.")
            } catch (error: Throwable) {
                if (!databaseCommitted) {
                    Files.deleteIfExists(temporary.toPath())
                }
                throw error
            }
        }
    }

    suspend fun restore(): RestoredConversionJob? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val root = runCatching(::requirePrivateRoot).getOrNull() ?: return@withContext null
            var row = dao.get() ?: return@withContext null
            if (isInvalidTimestamp(row.updatedAtEpochMillis)) {
                discardInvalid(root, row)
                return@withContext null
            }
            val stage = enumValueOrNull<ConversionJobStage>(row.stage)
            if (stage == null || !isCanonicalUuid(row.token)) {
                discardInvalid(root, row)
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
            if (stage == ConversionJobStage.BUILDING) {
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
            restored
        }
    }

    suspend fun discard(token: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isCanonicalUuid(token)) return@withContext false
            val row = dao.get()
            if (row?.token != token) return@withContext false
            val root = runCatching(::requirePrivateRoot).getOrNull() ?: return@withContext false
            if (!deleteOwnedTokenDirectories(root, row)) return@withContext false
            dao.delete(token, row.generation) == 1
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
            val bytes = readVerifiedBytes(input, row.inputSha256)
            hooks.afterInputSnapshot()
            ByteArrayInputStream(bytes).use(consume)
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
            require(bundleVerifier.verify(bundle)) { "완료 bundle 재검증에 실패했습니다." }
            val bytes = readVerifiedBytes(bundle, expectedSha256)
            ByteArrayInputStream(bytes).use(consume)
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
            runCatching { readVerifiedBytes(input, inputSha256) }.isFailure
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
                if (runCatching { readVerifiedBytes(file, requireNotNull(bundleSha256)) }.isFailure) {
                    return null
                }
                if (!runCatching { bundleVerifier.verify(file) }.getOrDefault(false)) return null
                bundleSha256
            }
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

    private fun validateAndReadArtifact(artifact: OnDeviceInputArtifact): ByteArray {
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
        return readVerifiedBytes(input, artifact.sha256)
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

    private fun readVerifiedBytes(file: File, expectedSha256: String): ByteArray {
        requireSafeSha256(expectedSha256, "private 파일")
        val before = requireNotNull(captureDirectFileState(file)) {
            "private 파일이 regular 단일 link가 아닙니다."
        }
        val bytes = FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            require(captureDirectFileState(file) == before) {
                "private 파일 identity가 open 중 변경되었습니다."
            }
            val output = ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                require(count > 0) { "private 파일 읽기가 진행되지 않습니다." }
                output.write(buffer.array(), 0, count)
                buffer.clear()
            }
            output.toByteArray()
        }
        require(captureDirectFileState(file) == before) {
            "private 파일 identity가 검증 중 변경되었습니다."
        }
        require(sha256(bytes) == expectedSha256) {
            "private 파일 hash가 일치하지 않습니다."
        }
        return bytes
    }

    private fun writeNew(bytes: ByteArray, destination: File) {
        FileChannel.open(destination.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) {
                require(output.write(buffer) > 0) { "private 파일 쓰기가 진행되지 않습니다." }
            }
            output.force(true)
        }
    }

    private fun copyAndHashNew(source: File, destination: File): String {
        val before = requireNotNull(captureDirectFileState(source)) {
            "생성 bundle이 regular 단일 link가 아닙니다."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(source.toPath(), READ, NOFOLLOW_LINKS).use { input ->
            require(captureDirectFileState(source) == before) {
                "생성 bundle identity가 open 중 변경되었습니다."
            }
            FileChannel.open(destination.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                val buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count > 0) { "생성 bundle 읽기가 진행되지 않습니다." }
                    digest.update(buffer.array(), 0, count)
                    buffer.flip()
                    while (buffer.hasRemaining()) {
                        require(output.write(buffer) > 0) {
                            "private bundle 복사가 진행되지 않습니다."
                        }
                    }
                    buffer.clear()
                }
                output.force(true)
            }
        }
        require(captureDirectFileState(source) == before) {
            "생성 bundle identity가 복사 중 변경되었습니다."
        }
        return digest.digest().toHex()
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

    private suspend fun discardInvalid(root: File, row: ConversionJobEntity) {
        if (!isCanonicalUuid(row.token)) {
            dao.delete(row.token, row.generation)
            return
        }
        if (deleteOwnedTokenDirectories(root, row)) {
            dao.delete(row.token, row.generation)
        }
    }

    private fun deleteOwnedTokenDirectories(root: File, row: ConversionJobEntity): Boolean {
        val cleanup = decodeCleanupTokens(row.cleanupTokensJson) ?: return false
        val tokens = cleanup + row.token
        if (tokens.any { !isCanonicalUuid(it) }) return false
        return tokens.all { deleteTokenDirectory(root, it) }
    }

    private fun deleteUnreferencedChildren(
        tokenDirectory: File,
        inputFileName: String,
    ): Boolean = tokenDirectory.listFiles().orEmpty()
        .filter { it.name != inputFileName }
        .all { deleteTree(it.toPath()) }

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

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
