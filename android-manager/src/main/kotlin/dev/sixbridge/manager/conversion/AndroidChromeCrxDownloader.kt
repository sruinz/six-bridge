package dev.sixbridge.manager.conversion

import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.core.input.ChromeWebStoreAddress
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
}

class ChromeDownloadException(
    val code: ChromeDownloadErrorCode,
    message: String,
) : IllegalArgumentException(message)

class AndroidChromeCrxDownloader(
    private val transport: ChromeDownloadTransport = HttpsChromeDownloadTransport(),
    private val hostPolicy: ChromeDownloadHostPolicy = ChromeDownloadHostPolicy(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun download(
        address: ChromeWebStoreAddress,
        output: OutputStream,
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
                val buffered = withContext(ioDispatcher) {
                    readBounded(response.body, output)
                }
                if (looksLikeHtml(buffered.bytes, buffered.size)) {
                    fail(
                        ChromeDownloadErrorCode.HTML_RESPONSE,
                        "Chrome CRX 대신 HTML 응답을 받았습니다.",
                    )
                }
                val verified = try {
                    Crx3Verifier.verify(buffered.bytes, 0, buffered.size)
                } catch (_: IllegalArgumentException) {
                    fail(
                        ChromeDownloadErrorCode.INVALID_CRX3,
                        "다운로드한 CRX3를 검증할 수 없습니다.",
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
                    byteCount = buffered.size.toLong(),
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

    private suspend fun readBounded(
        input: InputStream,
        output: OutputStream,
    ): BufferedDownload {
        val bytes = RetainedByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = try {
                readCancellable(input, buffer)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
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
            output.write(buffer, 0, count)
            bytes.write(buffer, 0, count)
            total += count
        }
        return BufferedDownload(bytes.backingArray(), bytes.size())
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

    private fun isHtml(contentType: String?): Boolean {
        val normalized = contentType?.substringBefore(';')?.trim()?.lowercase()
        return normalized == "text/html" || normalized == "application/xhtml+xml"
    }

    private fun looksLikeHtml(bytes: ByteArray, size: Int): Boolean {
        val prefix = bytes.copyOfRange(0, minOf(size, 256))
            .decodeToString()
            .trimStart()
            .lowercase()
        return prefix.startsWith("<!doctype html") || prefix.startsWith("<html")
    }

    private fun fail(code: ChromeDownloadErrorCode, message: String): Nothing =
        throw ChromeDownloadException(code, message)

    private data class BufferedDownload(
        val bytes: ByteArray,
        val size: Int,
    )

    private class RetainedByteArrayOutputStream : ByteArrayOutputStream() {
        fun backingArray(): ByteArray = buf
    }

    companion object {
        const val MAX_DOWNLOAD_BYTES = 128L * 1024 * 1024
        private const val MAX_REDIRECTS = 5
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 60_000
        private const val BUFFER_SIZE = 64 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        private fun updateUri(address: ChromeWebStoreAddress): URI = URI(
            "https://clients2.google.com/service/update2/crx" +
                "?response=redirect&prodversion=130.0&acceptformat=crx3" +
                "&x=id%3D${address.extensionId}%26uc",
        )
    }
}

private class HttpsChromeDownloadTransport : ChromeDownloadTransport {
    override suspend fun execute(request: ChromeDownloadRequest): ChromeDownloadResponse =
        withContext(Dispatchers.IO) {
            val connection = request.uri.toURL().openConnection() as? HttpsURLConnection
                ?: throw IllegalArgumentException("HTTPS 연결이 아닙니다.")
            connection.instanceFollowRedirects = false
            connection.connectTimeout = request.connectTimeoutMillis
            connection.readTimeout = request.readTimeoutMillis
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", "SIX-Bridge/0.1")
            val statusCode = connection.responseCode
            val body = if (statusCode in 200..399) {
                connection.inputStream
            } else {
                connection.errorStream ?: InputStream.nullInputStream()
            }
            ChromeDownloadResponse(
                statusCode = statusCode,
                contentType = connection.contentType,
                location = connection.getHeaderField("Location"),
                body = body,
                closeAction = connection::disconnect,
            )
        }
}
