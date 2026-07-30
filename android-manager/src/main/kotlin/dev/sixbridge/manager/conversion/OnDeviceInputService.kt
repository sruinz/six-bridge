package dev.sixbridge.manager.conversion

import android.content.ContentResolver
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.core.compat.AnalysisArchiveKind
import dev.sixbridge.core.compat.AnalysisBudgetExceededException
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisInputMaterializer
import dev.sixbridge.core.compat.AnalysisSafetyPolicy
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.draft.SixDraftProbe
import dev.sixbridge.core.input.ChromeWebStoreAddress
import dev.sixbridge.core.input.ExtensionZip
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
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
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val NOFOLLOW_LINKS = LinkOption.NOFOLLOW_LINKS

class OnDeviceInputService(
    private val privateRoot: File,
    private val safInputOpener: SafInputOpener,
    private val chromeDownloader: ChromeCrxDownloader = AndroidChromeCrxDownloader(),
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val workspaceDeleter: (Path) -> Boolean = ::deleteInputTree,
) : ConversionInputPort {
    private val leaseMutex = Mutex()
    private val activeLeases = mutableMapOf<String, ActiveInputLease>()

    constructor(
        privateRoot: File,
        contentResolver: ContentResolver,
        chromeDownloader: ChromeCrxDownloader = AndroidChromeCrxDownloader(),
    ) : this(
        privateRoot = privateRoot,
        safInputOpener = SafInputOpener { request ->
            contentResolver.openInputStream(request.uri)
        },
        chromeDownloader = chromeDownloader,
    )

    override suspend fun acquire(request: OnDeviceInputRequest): OnDeviceInputArtifact =
        acquire(request, AnalysisControl(AnalysisSafetyPolicy.mobile))

    override suspend fun acquire(
        request: OnDeviceInputRequest,
        control: AnalysisControl,
    ): OnDeviceInputArtifact =
        leaseMutex.withLock {
            acquireLocked(request, control)
        }

    override suspend fun release(artifact: OnDeviceInputArtifact): Boolean =
        leaseMutex.withLock {
            val activeLease = activeLeases[artifact.token] ?: return@withLock false
            if (!activeLease.matches(artifact)) return@withLock false
            try {
                val workspace = exactWorkspace(artifact) ?: return@withLock false
                withContext(NonCancellable + ioDispatcher) {
                    workspaceDeleter(workspace)
                }
            } finally {
                if (activeLeases[artifact.token] == activeLease) {
                    activeLeases.remove(artifact.token)
                }
            }
        }

    override suspend fun reclaimOrphans(): Boolean = leaseMutex.withLock {
        withContext(NonCancellable + ioDispatcher) {
            reclaimOrphansOnIo()
        }
    }

    private suspend fun acquireLocked(
        request: OnDeviceInputRequest,
        control: AnalysisControl,
    ): OnDeviceInputArtifact {
        control.checkCancelled()
        val ownedWorkspace = AtomicReference<Path?>()
        var primaryFailure: Throwable? = null
        return try {
            val artifact = withContext(ioDispatcher) {
                currentCoroutineContext().ensureActive()
                control.checkCancelled()
                val workspace = createWorkspace()
                ownedWorkspace.set(workspace)
                acquireOnIo(request, workspace, control)
            }
            activeLeases[artifact.token] = ActiveInputLease.from(artifact)
            ownedWorkspace.set(null)
            artifact
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            ownedWorkspace.getAndSet(null)?.let { workspace ->
                val cleaned = withContext(NonCancellable + ioDispatcher) {
                    workspaceDeleter(workspace)
                }
                if (!cleaned && primaryFailure !is CancellationException) {
                    inputFailure(
                        OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                        "private 입력 artifact를 정리할 수 없습니다.",
                    )
                }
            }
        }
    }

    private fun exactWorkspace(artifact: OnDeviceInputArtifact): Path? {
        if (!isCanonicalToken(artifact.token)) return null
        val root = canonicalRoot() ?: return null
        if (Files.isSymbolicLink(root)) return null
        val workspace = root.resolve(artifact.token)
        if (workspace.parent != root || Files.isSymbolicLink(workspace)) return null
        val expectedInput = workspace.resolve(INPUT_FILE_NAME)
        val suppliedInput = runCatching {
            artifact.privateFile.canonicalFile.toPath()
        }.getOrNull() ?: return null
        val canonicalExpected = runCatching {
            expectedInput.toFile().canonicalFile.toPath()
        }.getOrNull() ?: return null
        if (suppliedInput != canonicalExpected || canonicalExpected != expectedInput) return null
        if (
            Files.exists(workspace, LinkOption.NOFOLLOW_LINKS) &&
            (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) ||
                runCatching { workspace.toFile().canonicalFile.toPath() != workspace }
                    .getOrDefault(true))
        ) {
            return null
        }
        if (
            Files.exists(expectedInput, LinkOption.NOFOLLOW_LINKS) &&
            Files.isSymbolicLink(expectedInput)
        ) {
            return null
        }
        return workspace
    }

    private fun reclaimOrphansOnIo(): Boolean {
        val root = canonicalRoot() ?: return false
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return true
        if (
            Files.isSymbolicLink(root) ||
            !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
        ) {
            return false
        }
        val children = runCatching {
            Files.newDirectoryStream(root).use { stream -> stream.toList() }
        }.getOrElse { return false }
        var cleaned = true
        children.forEach { child ->
            val token = child.fileName.toString()
            if (
                token !in activeLeases &&
                isCanonicalToken(token) &&
                !Files.isSymbolicLink(child) &&
                Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) &&
                child.parent == root &&
                runCatching { child.toFile().canonicalFile.toPath() == child }
                    .getOrDefault(false)
            ) {
                if (!workspaceDeleter(child)) cleaned = false
            }
        }
        return cleaned
    }

    private fun canonicalRoot(): Path? = runCatching {
        if (Files.isSymbolicLink(privateRoot.toPath())) return null
        privateRoot.canonicalFile.toPath()
    }.getOrNull()

    private suspend fun acquireOnIo(
        request: OnDeviceInputRequest,
        workspace: Path,
        control: AnalysisControl,
    ): OnDeviceInputArtifact {
        return try {
            control.checkCancelled()
            val privateFile = workspace.resolve(INPUT_FILE_NAME).toFile()
            val acquisition = when (request) {
                is OnDeviceInputRequest.Saf -> copySaf(request, privateFile, control)
                is OnDeviceInputRequest.ChromeStore -> downloadChrome(request, privateFile)
            }
            control.checkCancelled()
            val classified = classify(
                privateFile,
                acquisition.expectedExtensionId,
                control,
            )
            if (!privateFile.setReadOnly()) {
                inputFailure(
                    OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                    "private 입력 파일을 불변 상태로 고정할 수 없습니다.",
                )
            }
            OnDeviceInputArtifact(
                token = workspace.fileName.toString(),
                kind = if (
                    request is OnDeviceInputRequest.ChromeStore &&
                    classified.kind == OnDeviceInputKind.CRX3
                ) {
                    OnDeviceInputKind.CHROME_WEB_STORE
                } else {
                    classified.kind
                },
                privateFile = privateFile,
                sha256 = acquisition.sha256,
                expectedExtensionId = classified.extensionId,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: OnDeviceInputException) {
            throw error
        } catch (_: Exception) {
            inputFailure(
                OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                "private 입력 artifact를 만들 수 없습니다.",
            )
        }
    }

    private data class ActiveInputLease(
        val artifact: OnDeviceInputArtifact,
        val kind: OnDeviceInputKind,
        val privatePath: Path,
        val sha256: String,
        val expectedExtensionId: String?,
    ) {
        fun matches(artifact: OnDeviceInputArtifact): Boolean =
            this.artifact === artifact &&
                kind == artifact.kind &&
                privatePath == artifact.privateFile.absoluteFile.toPath().normalize() &&
                sha256 == artifact.sha256 &&
                expectedExtensionId == artifact.expectedExtensionId

        companion object {
            fun from(artifact: OnDeviceInputArtifact) = ActiveInputLease(
                artifact = artifact,
                kind = artifact.kind,
                privatePath = artifact.privateFile.absoluteFile.toPath().normalize(),
                sha256 = artifact.sha256,
                expectedExtensionId = artifact.expectedExtensionId,
            )
        }
    }

    private fun createWorkspace(): Path {
        val token = tokenFactory()
        if (!isCanonicalToken(token)) {
            inputFailure(
                OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                "private 입력 token을 만들 수 없습니다.",
            )
        }
        val root = try {
            if (!privateRoot.exists() && !privateRoot.mkdirs()) {
                inputFailure(
                    OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                    "private 입력 저장소를 만들 수 없습니다.",
                )
            }
            if (!privateRoot.isDirectory || Files.isSymbolicLink(privateRoot.toPath())) {
                inputFailure(
                    OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                    "private 입력 저장소를 사용할 수 없습니다.",
                )
            }
            privateRoot.canonicalFile
        } catch (_: IOException) {
            inputFailure(
                OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                "private 입력 저장소를 확인할 수 없습니다.",
            )
        }
        val workspace = File(root, token)
        if (workspace.parentFile != root) {
            inputFailure(
                OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                "private 입력 token 경계가 올바르지 않습니다.",
            )
        }
        try {
            Files.createDirectory(workspace.toPath())
        } catch (_: Exception) {
            inputFailure(
                OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                "private 입력 token을 등록할 수 없습니다.",
            )
        }
        return workspace.toPath()
    }

    private suspend fun copySaf(
        request: OnDeviceInputRequest.Saf,
        destination: File,
        control: AnalysisControl,
    ): AcquiredBytes {
        control.checkCancelled()
        val input = try {
            safInputOpener.open(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            inputFailure(
                OnDeviceInputErrorCode.SOURCE_UNAVAILABLE,
                "선택한 SAF 입력을 열 수 없습니다.",
            )
        } ?: inputFailure(
            OnDeviceInputErrorCode.SOURCE_UNAVAILABLE,
            "선택한 SAF 입력을 열 수 없습니다.",
        )
        var primaryFailure: Throwable? = null
        val acquired = try {
            writeNew(destination) { channel ->
                copyBounded(input, channel, control)
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            try {
                input.close()
            } catch (error: CancellationException) {
                if (primaryFailure == null) throw error
            } catch (error: Error) {
                throw error
            } catch (_: Exception) {
                if (primaryFailure == null) {
                    inputFailure(
                        OnDeviceInputErrorCode.SOURCE_READ_FAILED,
                        "SAF 입력을 닫을 수 없습니다.",
                    )
                }
            }
        }
        if (sha256(destination.toPath()) != acquired.sha256) {
            inputFailure(
                OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                "fsync된 SAF 입력 hash가 일치하지 않습니다.",
            )
        }
        return acquired
    }

    private suspend fun downloadChrome(
        request: OnDeviceInputRequest.ChromeStore,
        destination: File,
    ): AcquiredBytes {
        val address = try {
            ChromeWebStoreAddress.parse(request.pageUrl)
        } catch (_: Exception) {
            inputFailure(
                OnDeviceInputErrorCode.URL,
                "Chrome 웹 스토어 상세 주소를 확인할 수 없습니다.",
            )
        }
        val proof = try {
            chromeDownloader.download(address, destination.toPath())
        } catch (error: CancellationException) {
            throw error
        } catch (error: ChromeDownloadException) {
            inputFailure(
                error.code.toInputErrorCode(),
                "Chrome CRX3를 다운로드하거나 검증할 수 없습니다.",
                error.code,
            )
        } catch (_: Exception) {
            inputFailure(
                OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                "Chrome CRX3 private 파일을 만들 수 없습니다.",
            )
        }
        val actualHash = sha256(destination.toPath())
        if (proof.byteCount != destination.length() || proof.sha256 != actualHash) {
            inputFailure(
                OnDeviceInputErrorCode.INPUT_INVALID,
                "Chrome CRX3 artifact proof가 일치하지 않습니다.",
            )
        }
        return AcquiredBytes(
            sha256 = proof.sha256,
            expectedExtensionId = proof.extensionId,
        )
    }

    private suspend fun copyBounded(
        input: InputStream,
        channel: FileChannel,
        control: AnalysisControl,
    ): AcquiredBytes {
        val digest = MessageDigest.getInstance("SHA-256")
        val output = Channels.newOutputStream(channel)
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            control.checkCancelled()
            currentCoroutineContext().ensureActive()
            val count = try {
                readCancellable(input, buffer)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                inputFailure(
                    OnDeviceInputErrorCode.SOURCE_READ_FAILED,
                    "SAF 입력을 읽을 수 없습니다.",
                )
            }
            currentCoroutineContext().ensureActive()
            control.checkCancelled()
            if (count < 0) break
            if (count == 0) {
                inputFailure(
                    OnDeviceInputErrorCode.SOURCE_READ_FAILED,
                    "SAF 입력 읽기가 진행되지 않습니다.",
                )
            }
            if (total > MAX_INPUT_BYTES - count) {
                inputFailure(
                    OnDeviceInputErrorCode.INPUT_TOO_LARGE,
                    "입력 크기가 제한을 초과했습니다.",
                )
            }
            output.write(buffer, 0, count)
            digest.update(buffer, 0, count)
            total += count
        }
        channel.force(true)
        return AcquiredBytes(
            sha256 = digest.digest().toHex(),
            expectedExtensionId = null,
        )
    }

    private suspend fun readCancellable(input: InputStream, buffer: ByteArray): Int =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                runCatching(input::close)
            }
            try {
                continuation.resumeWith(Result.success(input.read(buffer)))
            } catch (error: Throwable) {
                continuation.resumeWith(Result.failure(error))
            }
        }

    private suspend fun <T> writeNew(
        destination: File,
        block: suspend (FileChannel) -> T,
    ): T = try {
        FileChannel.open(
            destination.toPath(),
            CREATE_NEW,
            WRITE,
            NOFOLLOW_LINKS,
        ).use { channel ->
            block(channel)
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: OnDeviceInputException) {
        throw error
    } catch (error: ChromeDownloadException) {
        throw error
    } catch (_: Exception) {
        inputFailure(
            OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
            "private 입력 파일을 만들 수 없습니다.",
        )
    }

    private fun classify(
        file: File,
        expectedExtensionId: String?,
        control: AnalysisControl,
    ): ClassifiedInput {
        control.checkCancelled()
        val magic = try {
            readMagic(file.toPath())
        } catch (_: Exception) {
            inputFailure(
                OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                "private 입력 파일을 검증할 수 없습니다.",
            )
        }
        if (magic.contentEquals(CRX3_MAGIC)) {
            if (!withinMobileBudget(
                    file.toPath(),
                    AnalysisArchiveKind.CRX3,
                    OnDeviceInputErrorCode.INVALID_CRX3,
                    control,
                )
            ) {
                return ClassifiedInput(OnDeviceInputKind.CRX3, expectedExtensionId)
            }
            val verified = try {
                Crx3Verifier.verify(
                    file.toPath(),
                    0L,
                    file.length(),
                    MAX_INPUT_BYTES,
                    control::checkCancelled,
                )
            } catch (_: IllegalArgumentException) {
                inputFailure(
                    OnDeviceInputErrorCode.INVALID_CRX3,
                    "CRX3 서명 또는 구조가 올바르지 않습니다.",
                )
            }
            if (expectedExtensionId != null && verified.extensionId != expectedExtensionId) {
                inputFailure(
                    OnDeviceInputErrorCode.INVALID_CRX3,
                    "CRX3 확장 신원이 요청과 일치하지 않습니다.",
                )
            }
            validateCrxPayload(
                file.toPath(),
                verified.zipOffset.toLong(),
                verified.zipLength.toLong(),
                requireNotNull(file.parentFile).toPath(),
                control,
            )
            return ClassifiedInput(OnDeviceInputKind.CRX3, verified.extensionId)
        }
        if (!magic.contentEquals(ZIP_MAGIC)) {
            inputFailure(
                OnDeviceInputErrorCode.UNSUPPORTED_FORMAT,
                "지원하지 않는 입력 형식입니다.",
            )
        }
        when (
            val probe = SixDraft.probe(
                file.toPath(),
                MAX_INPUT_BYTES,
                control,
            )
        ) {
            SixDraftProbe.MalformedDraft -> {
                inputFailure(
                    OnDeviceInputErrorCode.INVALID_DRAFT,
                    "SixDraft 서명 또는 구조가 올바르지 않습니다.",
                )
            }
            SixDraftProbe.NotDraft -> Unit
            is SixDraftProbe.Valid -> {
                if (probe.schemaVersion !in 2..3) {
                    inputFailure(
                        OnDeviceInputErrorCode.INVALID_DRAFT,
                        "지원하지 않는 SixDraft schema입니다.",
                    )
                }
                return ClassifiedInput(
                    OnDeviceInputKind.SIX_DRAFT,
                    probe.originalExtensionId,
                )
            }
        }
        if (!withinMobileBudget(
                file.toPath(),
                AnalysisArchiveKind.ZIP,
                OnDeviceInputErrorCode.INVALID_ZIP,
                control,
            )
        ) {
            return ClassifiedInput(OnDeviceInputKind.ZIP, null)
        }
        validateExtensionZip(
            file.toPath(),
            requireNotNull(file.parentFile).toPath(),
            OnDeviceInputErrorCode.INVALID_ZIP,
            control,
        )
        return ClassifiedInput(OnDeviceInputKind.ZIP, null)
    }

    private fun withinMobileBudget(
        path: Path,
        kind: AnalysisArchiveKind,
        invalidCode: OnDeviceInputErrorCode,
        control: AnalysisControl,
    ): Boolean = try {
        AnalysisInputMaterializer.preflightArchive(
            path,
            kind,
            control,
        )
        true
    } catch (_: AnalysisBudgetExceededException) {
        false
    } catch (_: IllegalArgumentException) {
        inputFailure(invalidCode, "입력 archive 구조가 올바르지 않습니다.")
    }

    private fun validateExtensionZip(
        zipPath: Path,
        workspace: Path,
        errorCode: OnDeviceInputErrorCode,
        control: AnalysisControl,
    ) {
        val validationRoot = workspace.resolve(VALIDATION_DIRECTORY)
        var primaryFailure: Throwable? = null
        try {
            try {
                ExtensionZip.extract(zipPath, validationRoot, control)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                inputFailure(errorCode, "확장 ZIP 구조가 올바르지 않습니다.")
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            cleanupValidationPath(
                validationRoot,
                "ZIP 검증 임시 파일을 정리할 수 없습니다.",
                primaryFailure,
            )
        }
    }

    private fun validateCrxPayload(
        crxPath: Path,
        payloadOffset: Long,
        payloadLength: Long,
        workspace: Path,
        control: AnalysisControl,
    ) {
        val payloadPath = workspace.resolve(CRX_PAYLOAD_FILE)
        var primaryFailure: Throwable? = null
        try {
            try {
                FileChannel.open(crxPath, READ, NOFOLLOW_LINKS).use { source ->
                    FileChannel.open(payloadPath, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                        val buffer = java.nio.ByteBuffer.allocate(BUFFER_SIZE)
                        var position = payloadOffset
                        var remaining = payloadLength
                        while (remaining > 0) {
                            control.checkCancelled()
                            buffer.clear()
                            buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                            val count = source.read(buffer, position)
                            require(count > 0) {
                                "CRX3 ZIP payload를 완전히 읽지 못했습니다."
                            }
                            buffer.flip()
                            while (buffer.hasRemaining()) output.write(buffer)
                            control.checkCancelled()
                            position += count
                            remaining -= count
                        }
                        output.force(true)
                    }
                }
                validateExtensionZip(
                    payloadPath,
                    workspace,
                    OnDeviceInputErrorCode.INVALID_CRX3,
                    control,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: OnDeviceInputException) {
                throw error
            } catch (_: Exception) {
                inputFailure(
                    OnDeviceInputErrorCode.INVALID_CRX3,
                    "CRX3 ZIP payload 구조가 올바르지 않습니다.",
                )
            }
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            cleanupValidationPath(
                payloadPath,
                "CRX3 검증 임시 파일을 정리할 수 없습니다.",
                primaryFailure,
            )
        }
    }

    private fun cleanupValidationPath(
        path: Path,
        failureMessage: String,
        primaryFailure: Throwable?,
    ) {
        val cleanupFailure = try {
            if (workspaceDeleter(path)) {
                null
            } else {
                OnDeviceInputException(
                    OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                    failureMessage,
                )
            }
        } catch (error: Exception) {
            error
        }
        if (cleanupFailure != null) {
            if (primaryFailure is CancellationException) {
                primaryFailure.addSuppressed(cleanupFailure)
            } else {
                throw cleanupFailure
            }
        }
    }

    private fun readMagic(path: Path): ByteArray =
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(4)
            while (buffer.hasRemaining()) {
                val count = channel.read(buffer)
                if (count < 0) break
                require(count > 0) { "입력 magic 읽기가 진행되지 않습니다." }
            }
            buffer.array().copyOf(buffer.position())
        }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = java.nio.ByteBuffer.allocate(BUFFER_SIZE)
            while (true) {
                val count = channel.read(buffer)
                if (count < 0) break
                if (count == 0) {
                    inputFailure(
                        OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                        "private 입력 hash 읽기가 진행되지 않습니다.",
                    )
                }
                digest.update(buffer.array(), 0, count)
                buffer.clear()
            }
        }
        return digest.digest().toHex()
    }

    private fun isCanonicalToken(token: String): Boolean =
        runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun ChromeDownloadErrorCode.toInputErrorCode(): OnDeviceInputErrorCode =
        when (this) {
            ChromeDownloadErrorCode.NETWORK_FAILED,
            ChromeDownloadErrorCode.HTTP_FAILED,
            ChromeDownloadErrorCode.REDIRECT_REJECTED,
            ChromeDownloadErrorCode.TOO_MANY_REDIRECTS,
            -> OnDeviceInputErrorCode.NETWORK
            ChromeDownloadErrorCode.HTML_RESPONSE,
            ChromeDownloadErrorCode.RESPONSE_TOO_LARGE,
            ChromeDownloadErrorCode.INVALID_CRX3,
            ChromeDownloadErrorCode.EXTENSION_ID_MISMATCH,
            -> OnDeviceInputErrorCode.INPUT_INVALID
            ChromeDownloadErrorCode.CLEANUP_FAILED ->
                OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED
        }

    private fun inputFailure(
        code: OnDeviceInputErrorCode,
        message: String,
        chromeDownloadErrorCode: ChromeDownloadErrorCode? = null,
    ): Nothing = throw OnDeviceInputException(
        code,
        message,
        chromeDownloadErrorCode,
    )

    private data class AcquiredBytes(
        val sha256: String,
        val expectedExtensionId: String?,
    )

    private data class ClassifiedInput(
        val kind: OnDeviceInputKind,
        val extensionId: String?,
    )

    companion object {
        const val MAX_INPUT_BYTES = 128L * 1024 * 1024
        private const val INPUT_FILE_NAME = "input.bin"
        private const val VALIDATION_DIRECTORY = ".validation"
        private const val CRX_PAYLOAD_FILE = ".payload.zip"
        private const val BUFFER_SIZE = 64 * 1024
        private val CRX3_MAGIC = "Cr24".encodeToByteArray()
        private val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
    }
}

private fun deleteInputTree(path: Path): Boolean {
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
