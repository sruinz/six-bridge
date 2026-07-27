package dev.sixbridge.manager.conversion

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
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
    val inputFile: File,
    val stage: ConversionJobStage,
    val approvedAdapterIds: Set<String>,
    val profileSha256: String?,
    val bundleFile: File?,
)

class ConversionWorkStore(
    private val privateRoot: File,
    private val dao: ConversionJobDao,
    private val bundleVerifier: ConversionBundleVerifier,
    private val clock: () -> Long = { Clock.systemUTC().millis() },
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
    private val fileNameFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()

    suspend fun create(
        artifact: OnDeviceInputArtifact,
    ): RestoredConversionJob = mutex.withLock {
        withContext(Dispatchers.IO) {
            validateArtifact(artifact)
            val root = requirePrivateRoot()
            val token = requireCanonicalUuid(tokenFactory(), "작업 token")
            val tokenDirectory = directChild(root, token)
            try {
                Files.createDirectory(tokenDirectory.toPath())
            } catch (_: Exception) {
                throw IllegalStateException("private 작업 directory를 만들 수 없습니다.")
            }
            val inputFileName = "${requireCanonicalUuid(fileNameFactory(), "입력 파일명")}.input"
            val inputFile = directChild(tokenDirectory, inputFileName)
            try {
                copyNew(artifact.privateFile, inputFile)
                require(sha256(inputFile) == artifact.sha256) {
                    "복사한 private 입력 hash가 일치하지 않습니다."
                }
                require(inputFile.setReadOnly()) {
                    "private 입력을 불변 상태로 고정할 수 없습니다."
                }
                val previous = dao.get()
                val entity = ConversionJobEntity(
                    token = token,
                    inputKind = artifact.kind.name,
                    inputSha256 = artifact.sha256,
                    inputFileName = inputFileName,
                    stage = ConversionJobStage.INPUT_READY.name,
                    approvedAdapterIdsJson = null,
                    profileSha256 = null,
                    bundleFileName = null,
                    updatedAtEpochMillis = now(),
                )
                dao.replace(entity)
                if (previous != null && previous.token != token) {
                    deleteTokenDirectory(root, previous.token)
                }
                cleanupOrphanTokenDirectories(root, token)
                entity.toRestored(tokenDirectory)
                    ?: throw IllegalStateException("새 작업을 복구할 수 없습니다.")
            } catch (error: Throwable) {
                deleteTree(tokenDirectory.toPath())
                throw error
            }
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
        bundleFileName = null,
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
            bundleFileName = null,
        )
    }

    suspend fun markBundleReady(
        token: String,
        bundle: File,
    ): RestoredConversionJob = mutex.withLock {
        withContext(Dispatchers.IO) {
            val row = requireCurrent(token, ConversionJobStage.BUILDING)
            requireSafeSha256(row.profileSha256, "프로필")
            require(bundle.isFile && !Files.isSymbolicLink(bundle.toPath())) {
                "검증할 bundle 파일이 올바르지 않습니다."
            }
            require(bundleVerifier.verify(bundle)) { "생성 bundle 검증에 실패했습니다." }
            val root = requirePrivateRoot()
            val tokenDirectory = requireTokenDirectory(root, token)
            val bundleFileName =
                "${requireCanonicalUuid(fileNameFactory(), "bundle 파일명")}.six"
            val privateBundle = directChild(tokenDirectory, bundleFileName)
            try {
                copyNew(bundle, privateBundle)
                require(bundleVerifier.verify(privateBundle)) {
                    "복사한 private bundle 재검증에 실패했습니다."
                }
                require(privateBundle.setReadOnly()) {
                    "private bundle을 불변 상태로 고정할 수 없습니다."
                }
                val updated = dao.transition(
                    token = token,
                    stage = ConversionJobStage.BUNDLE_READY.name,
                    approvedAdapterIdsJson = row.approvedAdapterIdsJson,
                    profileSha256 = row.profileSha256,
                    bundleFileName = bundleFileName,
                    updatedAtEpochMillis = now(),
                )
                check(updated == 1) { "활성 작업이 바뀌었습니다." }
                requireNotNull(dao.get()).toRestored(tokenDirectory)
                    ?: throw IllegalStateException("완료 작업을 복구할 수 없습니다.")
            } catch (error: Throwable) {
                Files.deleteIfExists(privateBundle.toPath())
                throw error
            }
        }
    }

    suspend fun restore(): RestoredConversionJob? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val root = runCatching(::requirePrivateRoot).getOrNull() ?: return@withContext null
            var row = dao.get()
            if (row == null) {
                cleanupOrphanTokenDirectories(root, null)
                return@withContext null
            }
            val stage = enumValueOrNull<ConversionJobStage>(row.stage)
            if (stage == null || !isCanonicalUuid(row.token)) {
                discardInvalid(root, row.token)
                return@withContext null
            }
            if (stage != ConversionJobStage.BUNDLE_READY && isExpired(row.updatedAtEpochMillis)) {
                discardInvalid(root, row.token)
                cleanupOrphanTokenDirectories(root, null)
                return@withContext null
            }
            val tokenDirectory = runCatching { requireTokenDirectory(root, row.token) }.getOrNull()
            if (tokenDirectory == null || row.toRestored(tokenDirectory) == null) {
                discardInvalid(root, row.token)
                return@withContext null
            }
            if (stage == ConversionJobStage.BUILDING) {
                row.bundleFileName?.let { safeFileName ->
                    if (isPrivateFileName(safeFileName, BUNDLE_SUFFIX)) {
                        Files.deleteIfExists(directChild(tokenDirectory, safeFileName).toPath())
                    }
                }
                val changed = dao.transition(
                    token = row.token,
                    stage = ConversionJobStage.INPUT_READY.name,
                    approvedAdapterIdsJson = null,
                    profileSha256 = null,
                    bundleFileName = null,
                    updatedAtEpochMillis = now(),
                )
                if (changed != 1) return@withContext null
                row = requireNotNull(dao.get())
            }
            val restored = row.toRestored(tokenDirectory)
            if (restored == null) {
                discardInvalid(root, row.token)
                return@withContext null
            }
            cleanupOrphanTokenDirectories(root, row.token)
            restored
        }
    }

    suspend fun discard(token: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!isCanonicalUuid(token)) return@withContext false
            val row = dao.get()
            if (row?.token != token) return@withContext false
            val root = runCatching(::requirePrivateRoot).getOrNull() ?: return@withContext false
            if (!deleteTokenDirectory(root, token)) return@withContext false
            dao.delete(token) == 1
        }
    }

    private suspend fun transition(
        token: String,
        expectedStage: ConversionJobStage,
        nextStage: ConversionJobStage,
        approvedAdapterIds: Set<String>?,
        profileSha256: String?,
        bundleFileName: String?,
    ): RestoredConversionJob = mutex.withLock {
        withContext(Dispatchers.IO) {
            val row = requireCurrent(token, expectedStage)
            requireSafeSha256(profileSha256, "프로필")
            val encodedAdapterIds = approvedAdapterIds?.let(::encodeAdapterIds)
            val updated = dao.transition(
                token = token,
                stage = nextStage.name,
                approvedAdapterIdsJson = encodedAdapterIds,
                profileSha256 = profileSha256,
                bundleFileName = bundleFileName,
                updatedAtEpochMillis = now(),
            )
            check(updated == 1) { "활성 작업이 바뀌었습니다." }
            val root = requirePrivateRoot()
            val tokenDirectory = requireTokenDirectory(root, row.token)
            requireNotNull(dao.get()).toRestored(tokenDirectory)
                ?: throw IllegalStateException("전환한 작업을 복구할 수 없습니다.")
        }
    }

    private suspend fun requireCurrent(
        token: String,
        stage: ConversionJobStage,
    ): ConversionJobEntity {
        requireCanonicalUuid(token, "작업 token")
        val row = dao.get()
        check(row?.token == token) { "활성 작업이 일치하지 않습니다." }
        check(row.stage == stage.name) { "작업 단계를 전환할 수 없습니다." }
        return row
    }

    private fun ConversionJobEntity.toRestored(
        tokenDirectory: File,
    ): RestoredConversionJob? {
        if (singletonId != 1 || !isCanonicalUuid(token)) return null
        val inputKind = enumValueOrNull<OnDeviceInputKind>(inputKind) ?: return null
        val stage = enumValueOrNull<ConversionJobStage>(stage) ?: return null
        if (!isLowerHexSha256(inputSha256)) return null
        if (!isPrivateFileName(inputFileName, INPUT_SUFFIX)) return null
        val input = runCatching { directChild(tokenDirectory, inputFileName) }.getOrNull()
            ?: return null
        if (!isRegularDirectFile(tokenDirectory, input) || sha256(input) != inputSha256) return null
        val adapterIds = decodeAdapterIds(approvedAdapterIdsJson) ?: return null
        val bundle = when (stage) {
            ConversionJobStage.INPUT_READY -> {
                if (approvedAdapterIdsJson != null || profileSha256 != null || bundleFileName != null) {
                    return null
                }
                null
            }
            ConversionJobStage.AWAITING_APPROVAL -> {
                if (approvedAdapterIdsJson != null || !isLowerHexSha256(profileSha256) ||
                    bundleFileName != null
                ) {
                    return null
                }
                null
            }
            ConversionJobStage.BUILDING -> {
                if (approvedAdapterIdsJson == null || !isLowerHexSha256(profileSha256) ||
                    bundleFileName != null
                ) {
                    return null
                }
                null
            }
            ConversionJobStage.BUNDLE_READY -> {
                if (approvedAdapterIdsJson == null || !isLowerHexSha256(profileSha256)) return null
                val name = bundleFileName ?: return null
                if (!isPrivateFileName(name, BUNDLE_SUFFIX)) return null
                val file = runCatching { directChild(tokenDirectory, name) }.getOrNull()
                    ?: return null
                if (!isRegularDirectFile(tokenDirectory, file)) return null
                if (!runCatching { bundleVerifier.verify(file) }.getOrDefault(false)) return null
                file
            }
        }
        return RestoredConversionJob(
            token = token,
            inputKind = inputKind,
            inputSha256 = inputSha256,
            inputFile = input,
            stage = stage,
            approvedAdapterIds = adapterIds,
            profileSha256 = profileSha256,
            bundleFile = bundle,
        )
    }

    private fun validateArtifact(artifact: OnDeviceInputArtifact) {
        requireCanonicalUuid(artifact.token, "입력 token")
        require(isLowerHexSha256(artifact.sha256)) { "입력 SHA-256이 올바르지 않습니다." }
        val input = artifact.privateFile
        require(input.name == TASK6_INPUT_FILE_NAME) { "Task 6 입력 파일명이 올바르지 않습니다." }
        val directory = input.parentFile ?: throw IllegalArgumentException("입력 경계가 없습니다.")
        require(directory.name == artifact.token) { "입력 token 경계가 일치하지 않습니다." }
        require(!Files.isSymbolicLink(directory.toPath()) && !Files.isSymbolicLink(input.toPath())) {
            "입력 경계에 symbolic link가 있습니다."
        }
        require(isRegularDirectFile(directory, input)) { "입력이 token의 direct child가 아닙니다." }
        require(sha256(input) == artifact.sha256) { "Task 6 입력 hash가 일치하지 않습니다." }
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
            file.isFile &&
            !Files.isSymbolicLink(file.toPath()) &&
            file.canonicalFile.parentFile == parent.canonicalFile
    }.getOrDefault(false)

    private fun copyNew(source: File, destination: File) {
        FileChannel.open(source.toPath(), READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            FileChannel.open(
                destination.toPath(),
                CREATE_NEW,
                WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { output ->
                var position = 0L
                while (position < input.size()) {
                    val written = input.transferTo(position, input.size() - position, output)
                    require(written > 0) { "private 파일 복사가 진행되지 않습니다." }
                    position += written
                }
                output.force(true)
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(file.toPath(), READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                require(count > 0) { "private hash 읽기가 진행되지 않습니다." }
                digest.update(buffer.array(), 0, count)
                buffer.clear()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun discardInvalid(root: File, token: String) {
        if (isCanonicalUuid(token)) deleteTokenDirectory(root, token)
        dao.delete(token)
    }

    private fun deleteTokenDirectory(root: File, token: String): Boolean {
        if (!isCanonicalUuid(token)) return false
        return deleteTree(directChild(root, token).toPath())
    }

    private fun cleanupOrphanTokenDirectories(root: File, activeToken: String?) {
        root.listFiles().orEmpty().forEach { child ->
            if (child.name != activeToken) deleteTree(child.toPath())
        }
    }

    private fun deleteTree(path: Path): Boolean {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
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
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
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

    private fun isExpired(updatedAt: Long): Boolean {
        val current = now()
        return updatedAt < 0 || current >= updatedAt &&
            current - updatedAt >= INCOMPLETE_RETENTION_MILLIS
    }

    private fun now(): Long = clock().also {
        require(it >= 0) { "현재 시간이 올바르지 않습니다." }
    }

    companion object {
        const val INCOMPLETE_RETENTION_MILLIS = 24L * 60 * 60 * 1000
        private const val TASK6_INPUT_FILE_NAME = "input.bin"
        private const val INPUT_SUFFIX = ".input"
        private const val BUNDLE_SUFFIX = ".six"
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
