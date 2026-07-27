package dev.sixbridge.manager.conversion

import android.content.ContentResolver
import dev.sixbridge.contract.crx.Crx3Verifier
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
import kotlinx.coroutines.withContext

private val NOFOLLOW_LINKS = LinkOption.NOFOLLOW_LINKS

class OnDeviceInputService(
    private val privateRoot: File,
    private val safInputOpener: SafInputOpener,
    private val chromeDownloader: ChromeCrxDownloader = AndroidChromeCrxDownloader(),
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
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

    suspend fun acquire(request: OnDeviceInputRequest): OnDeviceInputArtifact {
        val ownedWorkspace = AtomicReference<Path?>()
        return try {
            val artifact = withContext(ioDispatcher) {
                currentCoroutineContext().ensureActive()
                val workspace = createWorkspace()
                ownedWorkspace.set(workspace)
                acquireOnIo(request, workspace)
            }
            ownedWorkspace.set(null)
            artifact
        } finally {
            ownedWorkspace.getAndSet(null)?.let { workspace ->
                val cleaned = withContext(NonCancellable + ioDispatcher) {
                    deleteTree(workspace)
                }
                if (!cleaned) {
                    inputFailure(
                        OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                        "private 입력 artifact를 정리할 수 없습니다.",
                    )
                }
            }
        }
    }

    private suspend fun acquireOnIo(
        request: OnDeviceInputRequest,
        workspace: Path,
    ): OnDeviceInputArtifact {
        return try {
            val privateFile = workspace.resolve(INPUT_FILE_NAME).toFile()
            val acquisition = when (request) {
                is OnDeviceInputRequest.Saf -> copySaf(request, privateFile)
                is OnDeviceInputRequest.ChromeStore -> downloadChrome(request, privateFile)
            }
            val classified = classify(privateFile, acquisition.expectedExtensionId)
            if (!privateFile.setReadOnly()) {
                inputFailure(
                    OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                    "private 입력 파일을 불변 상태로 고정할 수 없습니다.",
                )
            }
            OnDeviceInputArtifact(
                token = workspace.fileName.toString(),
                kind = classified.kind,
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
    ): AcquiredBytes {
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
                copyBounded(input, channel)
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
    ): AcquiredBytes {
        val digest = MessageDigest.getInstance("SHA-256")
        val output = Channels.newOutputStream(channel)
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
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
    ): ClassifiedInput {
        val magic = try {
            readMagic(file.toPath())
        } catch (_: Exception) {
            inputFailure(
                OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                "private 입력 파일을 검증할 수 없습니다.",
            )
        }
        if (magic.contentEquals(CRX3_MAGIC)) {
            val verified = try {
                Crx3Verifier.verify(file.toPath(), MAX_INPUT_BYTES)
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
            )
            return ClassifiedInput(OnDeviceInputKind.CRX3, verified.extensionId)
        }
        if (!magic.contentEquals(ZIP_MAGIC)) {
            inputFailure(
                OnDeviceInputErrorCode.UNSUPPORTED_FORMAT,
                "지원하지 않는 입력 형식입니다.",
            )
        }
        when (val probe = SixDraft.probe(file.toPath(), MAX_INPUT_BYTES)) {
            SixDraftProbe.MalformedDraft -> {
                inputFailure(
                    OnDeviceInputErrorCode.INVALID_DRAFT,
                    "SixDraft 서명 또는 구조가 올바르지 않습니다.",
                )
            }
            SixDraftProbe.NotDraft -> Unit
            is SixDraftProbe.Valid -> {
                if (probe.schemaVersion != 2) {
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
        validateExtensionZip(
            file.toPath(),
            requireNotNull(file.parentFile).toPath(),
            OnDeviceInputErrorCode.INVALID_ZIP,
        )
        return ClassifiedInput(OnDeviceInputKind.ZIP, null)
    }

    private fun validateExtensionZip(
        zipPath: Path,
        workspace: Path,
        errorCode: OnDeviceInputErrorCode,
    ) {
        val validationRoot = workspace.resolve(VALIDATION_DIRECTORY)
        try {
            ExtensionZip.extract(zipPath, validationRoot)
        } catch (_: Exception) {
            inputFailure(errorCode, "확장 ZIP 구조가 올바르지 않습니다.")
        } finally {
            if (!deleteTree(validationRoot)) {
                inputFailure(
                    OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                    "ZIP 검증 임시 파일을 정리할 수 없습니다.",
                )
            }
        }
    }

    private fun validateCrxPayload(
        crxPath: Path,
        payloadOffset: Long,
        payloadLength: Long,
        workspace: Path,
    ) {
        val payloadPath = workspace.resolve(CRX_PAYLOAD_FILE)
        try {
            FileChannel.open(crxPath, READ, NOFOLLOW_LINKS).use { source ->
                FileChannel.open(payloadPath, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                    val buffer = java.nio.ByteBuffer.allocate(BUFFER_SIZE)
                    var position = payloadOffset
                    var remaining = payloadLength
                    while (remaining > 0) {
                        buffer.clear()
                        buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                        val count = source.read(buffer, position)
                        require(count > 0) { "CRX3 ZIP payload를 완전히 읽지 못했습니다." }
                        buffer.flip()
                        while (buffer.hasRemaining()) output.write(buffer)
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
            )
        } catch (error: OnDeviceInputException) {
            throw error
        } catch (_: Exception) {
            inputFailure(
                OnDeviceInputErrorCode.INVALID_CRX3,
                "CRX3 ZIP payload 구조가 올바르지 않습니다.",
            )
        } finally {
            try {
                Files.deleteIfExists(payloadPath)
            } catch (_: Exception) {
                inputFailure(
                    OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
                    "CRX3 검증 임시 파일을 정리할 수 없습니다.",
                )
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
