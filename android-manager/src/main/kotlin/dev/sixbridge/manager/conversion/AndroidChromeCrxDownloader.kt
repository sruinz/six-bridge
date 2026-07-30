package dev.sixbridge.manager.conversion

import android.util.Log
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.core.input.ChromeWebStoreAddress
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

data class ChromeDownloadRequest(
    val uri: URI,
    val connectTimeoutMillis: Int,
    val readTimeoutMillis: Int,
)

class ChromeDownloadResponse(
    val statusCode: Int,
    val contentType: String?,
    val location: String?,
    val body: InputStream,
    val contentLength: Long? = null,
    private val closeAction: () -> Unit = {},
) : Closeable {
    override fun close() {
        runCatching(body::close)
        runCatching(closeAction)
    }
}

fun interface ChromeDownloadTransport {
    suspend fun execute(request: ChromeDownloadRequest): ChromeDownloadResponse
}

fun interface ChromeCrxDownloader {
    suspend fun download(
        address: ChromeWebStoreAddress,
        destination: Path,
    ): DownloadProof
}

fun interface CrxPathVerifier {
    fun verify(path: Path, maximumBytes: Long): VerifiedCrx3
}

class ChromeDownloadHostPolicy {
    fun allows(host: String?): Boolean {
        val normalized = host?.lowercase()?.trimEnd('.') ?: return false
        return normalized == "clients2.google.com" ||
            normalized == "clients2.googleusercontent.com" ||
            normalized == "redirector.gvt1.com" ||
            normalized.endsWith(".gvt1.com")
    }
}

enum class ChromeDownloadErrorCode {
    NETWORK_FAILED,
    HTTP_FAILED,
    REDIRECT_REJECTED,
    TOO_MANY_REDIRECTS,
    HTML_RESPONSE,
    RESPONSE_TOO_LARGE,
    INVALID_CRX3,
    EXTENSION_ID_MISMATCH,
    CLEANUP_FAILED,
}

class ChromeDownloadException(
    val code: ChromeDownloadErrorCode,
    message: String,
) : IllegalArgumentException(message)

fun interface ChromeDownloadCleanupFailureReporter {
    fun report(error: ChromeDownloadException)
}

class AndroidChromeCrxDownloader(
    private val transport: ChromeDownloadTransport = HttpsChromeDownloadTransport(),
    private val hostPolicy: ChromeDownloadHostPolicy = ChromeDownloadHostPolicy(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val crxVerifier: CrxPathVerifier =
        CrxPathVerifier(Crx3Verifier::verify),
    private val cleanupFailureReporter: ChromeDownloadCleanupFailureReporter =
        ChromeDownloadCleanupFailureReporter {
            Log.e(CLEANUP_LOG_TAG, "Chrome CRX destination cleanup failed.")
        },
) : ChromeCrxDownloader {
    suspend fun download(
        address: ChromeWebStoreAddress,
        output: OutputStream,
    ): DownloadProof = withContext(ioDispatcher) {
        val temporaryDirectory = Files.createTempDirectory(
            "sixbridge-chrome-download-",
        )
        val temporary = temporaryDirectory.resolve("download.crx")
        try {
            val proof = download(address, temporary)
            copyVerified(temporary, output, proof.sha256)
            proof
        } finally {
            withContext(NonCancellable + ioDispatcher) {
                Files.deleteIfExists(temporary)
                Files.deleteIfExists(temporaryDirectory)
            }
        }
    }

    override suspend fun download(
        address: ChromeWebStoreAddress,
        destination: Path,
    ): DownloadProof {
        val created = AtomicBoolean()
        var delivered = false
        var primaryFailure: Throwable? = null
        return try {
            val proof = withContext(ioDispatcher) {
                downloadOnIo(address, destination, created)
            }
            delivered = true
            proof
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            if (!delivered && created.get()) {
                val cleanupError = withContext(NonCancellable + ioDispatcher) {
                    val cleanupFailed = try {
                        Files.deleteIfExists(destination)
                        Files.exists(destination, NOFOLLOW_LINKS)
                    } catch (_: Exception) {
                        true
                    }
                    if (!cleanupFailed) return@withContext null
                    val cleanupError = ChromeDownloadException(
                        ChromeDownloadErrorCode.CLEANUP_FAILED,
                        "Chrome CRX 임시 입력을 정리할 수 없습니다.",
                    )
                    runCatching {
                        cleanupFailureReporter.report(cleanupError)
                    }
                    cleanupError
                }
                if (cleanupError != null &&
                    primaryFailure !is CancellationException
                ) {
                    throw cleanupError
                }
            }
        }
    }

    private suspend fun copyVerified(
        source: Path,
        output: OutputStream,
        expectedSha256: String,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_SIZE)
        FileChannel.open(source, READ, NOFOLLOW_LINKS).use { input ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val bytes = ByteBuffer.wrap(buffer)
                val count = input.read(bytes)
                if (count < 0) break
                if (count == 0) {
                    fail(
                        ChromeDownloadErrorCode.NETWORK_FAILED,
                        "검증된 Chrome CRX 복사가 진행되지 않습니다.",
                    )
                }
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().toHex() != expectedSha256) {
            fail(
                ChromeDownloadErrorCode.INVALID_CRX3,
                "검증된 Chrome CRX가 복사 중 변경되었습니다.",
            )
        }
    }

    private suspend fun downloadOnIo(
        address: ChromeWebStoreAddress,
        destination: Path,
        created: AtomicBoolean,
    ): DownloadProof {
        var uri = updateUri(address)
        var redirects = 0
        while (true) {
            requireAllowedUri(uri)
            val response = execute(uri)
            response.use {
                if (response.statusCode in REDIRECT_CODES) {
                    if (redirects >= MAX_REDIRECTS) {
                        fail(
                            ChromeDownloadErrorCode.TOO_MANY_REDIRECTS,
                            "Chrome CRX redirect 횟수가 제한을 초과했습니다.",
                        )
                    }
                    uri = redirectUri(uri, response.location)
                    redirects += 1
                    continue
                }
                if (response.statusCode !in 200..299) {
                    fail(
                        ChromeDownloadErrorCode.HTTP_FAILED,
                        "Chrome CRX 다운로드 HTTP 응답이 올바르지 않습니다.",
                    )
                }
                if (isHtml(response.contentType)) {
                    fail(
                        ChromeDownloadErrorCode.HTML_RESPONSE,
                        "Chrome CRX 대신 HTML 응답을 받았습니다.",
                    )
                }
                response.contentLength?.let { declared ->
                    if (declared < 0L || declared > MAX_DOWNLOAD_BYTES) {
                        fail(
                            ChromeDownloadErrorCode.RESPONSE_TOO_LARGE,
                            "Chrome CRX 응답 크기가 제한을 초과했습니다.",
                        )
                    }
                }
                val downloaded = writeBounded(response.body, destination, created)
                if (looksLikeHtml(downloaded.prefix)) {
                    fail(
                        ChromeDownloadErrorCode.HTML_RESPONSE,
                        "Chrome CRX 대신 HTML 응답을 받았습니다.",
                    )
                }
                val verified = try {
                    crxVerifier.verify(destination, MAX_DOWNLOAD_BYTES)
                } catch (_: IllegalArgumentException) {
                    fail(
                        ChromeDownloadErrorCode.INVALID_CRX3,
                        "다운로드한 CRX3를 검증할 수 없습니다.",
                    )
                }
                if (verified.crxSha256 != downloaded.sha256) {
                    fail(
                        ChromeDownloadErrorCode.INVALID_CRX3,
                        "다운로드한 CRX3 hash를 검증할 수 없습니다.",
                    )
                }
                if (verified.extensionId != address.extensionId) {
                    fail(
                        ChromeDownloadErrorCode.EXTENSION_ID_MISMATCH,
                        "다운로드한 CRX3가 요청한 확장과 일치하지 않습니다.",
                    )
                }
                return DownloadProof(
                    extensionId = verified.extensionId,
                    byteCount = downloaded.size,
                    sha256 = verified.crxSha256,
                )
            }
        }
    }

    private suspend fun execute(uri: URI): ChromeDownloadResponse = try {
        transport.execute(
            ChromeDownloadRequest(
                uri = uri,
                connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS,
                readTimeoutMillis = READ_TIMEOUT_MILLIS,
            ),
        )
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        fail(
            ChromeDownloadErrorCode.NETWORK_FAILED,
            "Chrome CRX 다운로드 연결에 실패했습니다.",
        )
    }

    private fun redirectUri(current: URI, location: String?): URI {
        if (location.isNullOrBlank()) {
            fail(
                ChromeDownloadErrorCode.REDIRECT_REJECTED,
                "Chrome CRX redirect 응답이 올바르지 않습니다.",
            )
        }
        val redirected = try {
            current.resolve(location)
        } catch (_: IllegalArgumentException) {
            fail(
                ChromeDownloadErrorCode.REDIRECT_REJECTED,
                "Chrome CRX redirect 응답이 올바르지 않습니다.",
            )
        }
        requireAllowedUri(redirected)
        return redirected
    }

    private fun requireAllowedUri(uri: URI) {
        if (uri.scheme != "https" ||
            uri.userInfo != null ||
            uri.fragment != null ||
            uri.port !in setOf(-1, 443) ||
            !hostPolicy.allows(uri.host)
        ) {
            fail(
                ChromeDownloadErrorCode.REDIRECT_REJECTED,
                "허용되지 않은 Chrome CRX 다운로드 위치입니다.",
            )
        }
    }

    private suspend fun writeBounded(
        input: InputStream,
        destination: Path,
        created: AtomicBoolean,
    ): StreamedDownload =
        FileChannel.open(
            destination,
            CREATE_NEW,
            READ,
            WRITE,
            NOFOLLOW_LINKS,
        ).use { output ->
            created.set(true)
            val digest = MessageDigest.getInstance("SHA-256")
            val prefix = ByteArray(HTML_PREFIX_BYTES)
            var prefixSize = 0
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = try {
                    readCancellable(input, buffer)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    currentCoroutineContext().ensureActive()
                    fail(
                        ChromeDownloadErrorCode.NETWORK_FAILED,
                        "Chrome CRX 응답을 읽을 수 없습니다.",
                    )
                }
                if (count < 0) break
                if (count == 0) {
                    fail(
                        ChromeDownloadErrorCode.NETWORK_FAILED,
                        "Chrome CRX 응답 읽기가 진행되지 않습니다.",
                    )
                }
                if (total > MAX_DOWNLOAD_BYTES - count) {
                    fail(
                        ChromeDownloadErrorCode.RESPONSE_TOO_LARGE,
                        "Chrome CRX 응답 크기가 제한을 초과했습니다.",
                    )
                }
                if (prefixSize < prefix.size) {
                    val copied = minOf(count, prefix.size - prefixSize)
                    buffer.copyInto(prefix, prefixSize, 0, copied)
                    prefixSize += copied
                }
                val bytes = ByteBuffer.wrap(buffer, 0, count)
                while (bytes.hasRemaining()) {
                    output.write(bytes)
                }
                digest.update(buffer, 0, count)
                total += count
            }
            output.force(true)
            StreamedDownload(
                prefix = prefix.copyOf(prefixSize),
                size = total,
                sha256 = digest.digest().toHex(),
            )
        }

    @OptIn(InternalCoroutinesApi::class)
    private suspend fun readCancellable(
        input: InputStream,
        buffer: ByteArray,
    ): Int {
        val cancellationHandle = currentCoroutineContext()[Job]
            ?.invokeOnCompletion(
                onCancelling = true,
                invokeImmediately = true,
            ) {
                runCatching(input::close)
            }
        return try {
            input.read(buffer)
        } finally {
            cancellationHandle?.dispose()
        }
    }

    private fun isHtml(contentType: String?): Boolean {
        val normalized = contentType?.substringBefore(';')?.trim()?.lowercase()
        return normalized == "text/html" || normalized == "application/xhtml+xml"
    }

    private fun looksLikeHtml(bytes: ByteArray): Boolean {
        val prefix = bytes
            .decodeToString()
            .trimStart()
            .lowercase()
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html")
    }

    private fun fail(code: ChromeDownloadErrorCode, message: String): Nothing =
        throw ChromeDownloadException(code, message)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private data class StreamedDownload(
        val prefix: ByteArray,
        val size: Long,
        val sha256: String,
    )

    companion object {
        const val MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val HTML_PREFIX_BYTES = 256
        private const val CLEANUP_LOG_TAG = "SIX-Bridge"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private fun updateUri(address: ChromeWebStoreAddress): URI = URI(
            "https://clients2.google.com/service/update2/crx" +
                "?response=redirect&prodversion=130.0&acceptformat=crx3" +
                "&x=id%3D${address.extensionId}%26uc",
        )
    }
}

internal fun interface HttpsConnectionFactory {
    fun open(uri: URI): HttpsURLConnection
}

internal class HttpsChromeDownloadTransport(
    private val connectionFactory: HttpsConnectionFactory =
        HttpsConnectionFactory { uri ->
            uri.toURL().openConnection() as? HttpsURLConnection
                ?: throw IllegalArgumentException("HTTPS 연결이 아닙니다.")
        },
) : ChromeDownloadTransport {
    override suspend fun execute(request: ChromeDownloadRequest): ChromeDownloadResponse =
        suspendCancellableCoroutine { continuation ->
            var connection: HttpsURLConnection? = null
            try {
                val opened = connectionFactory.open(request.uri)
                connection = opened
                continuation.invokeOnCancellation {
                    runCatching(opened::disconnect)
                }
                opened.instanceFollowRedirects = false
                opened.connectTimeout = request.connectTimeoutMillis
                opened.readTimeout = request.readTimeoutMillis
                opened.requestMethod = "GET"
                opened.setRequestProperty("User-Agent", "SIX-Bridge/0.1")
                val statusCode = opened.responseCode
                val body = if (statusCode in 200..399) {
                    opened.inputStream
                } else {
                    opened.errorStream ?: InputStream.nullInputStream()
                }
                val response = ChromeDownloadResponse(
                    statusCode = statusCode,
                    contentType = opened.contentType,
                    location = opened.getHeaderField("Location"),
                    body = body,
                    contentLength = opened.contentLengthLong
                        .takeIf { it >= 0L },
                    closeAction = opened::disconnect,
                )
                continuation.resume(response) { _, cancelledResponse, _ ->
                    cancelledResponse.close()
                }
            } catch (error: Throwable) {
                runCatching { connection?.disconnect() }
                if (continuation.isActive) {
                    continuation.resumeWith(Result.failure(error))
                } else if (error is Error) {
                    throw error
                }
            }
        }
}
