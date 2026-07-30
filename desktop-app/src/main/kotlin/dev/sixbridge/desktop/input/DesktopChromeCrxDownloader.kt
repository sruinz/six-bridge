package dev.sixbridge.desktop.input

import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.core.input.ChromeCrxDownloader
import dev.sixbridge.core.input.ChromeWebStoreAddress
import dev.sixbridge.core.input.UnsupportedInputException
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class DesktopDownloadResponse(
    val statusCode: Int,
    val contentType: String?,
    val contentLength: Long?,
    val location: String?,
    val body: InputStream,
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            body.close()
        }
    }
}

fun interface DesktopDownloadTransport {
    fun execute(uri: URI): DesktopDownloadResponse
}

class DesktopChromeCrxDownloader private constructor(
    private val transport: DesktopDownloadTransport,
    private val bodyReadTimeout: Duration,
    private val deadlineScheduler: ScheduledExecutorService,
) : ChromeCrxDownloader {
    constructor() : this(JavaHttpDownloadTransport())

    constructor(transport: DesktopDownloadTransport) : this(
        transport = transport,
        bodyReadTimeout = DEFAULT_BODY_READ_TIMEOUT,
        deadlineScheduler = DEFAULT_DEADLINE_SCHEDULER,
    )

    override fun download(address: ChromeWebStoreAddress): ByteArray {
        return try {
            downloadVerified(address)
        } catch (_: CancellationException) {
            throw downloadCancelled()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw downloadCancelled()
        } catch (_: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw downloadCancelled()
        } catch (error: UnsupportedInputException) {
            throw error
        } catch (_: Exception) {
            throw UnsupportedInputException("Chrome CRX3 다운로드 통신에 실패했습니다.")
        }
    }

    private fun downloadVerified(address: ChromeWebStoreAddress): ByteArray {
        var uri = updateUri(address)
        var redirectCount = 0
        while (true) {
            requireAllowedUri(uri)
            val response = executeTransport(uri)
            try {
                throwIfInterrupted()
                if (response.statusCode in REDIRECT_STATUS_CODES) {
                    if (redirectCount >= MAX_REDIRECTS) {
                        throw UnsupportedInputException(
                            "Chrome CRX3 다운로드 redirect 횟수가 제한을 초과했습니다.",
                        )
                    }
                    val location = response.location ?: throw UnsupportedInputException(
                        "Chrome CRX3 다운로드 redirect 위치가 없습니다.",
                    )
                    uri = resolveLocation(uri, location)
                    requireAllowedUri(uri)
                    redirectCount++
                    continue
                }
                if (response.statusCode !in 200..299) {
                    throw UnsupportedInputException(
                        "Chrome CRX3 다운로드가 HTTP ${response.statusCode}로 실패했습니다.",
                    )
                }
                val bytes = readBounded(response)
                rejectHtml(response.contentType, bytes)
                throwIfInterrupted()
                val verified = try {
                    Crx3Verifier.verify(bytes)
                } catch (_: IllegalArgumentException) {
                    throw UnsupportedInputException(
                        "다운로드 응답이 검증된 CRX3가 아닙니다.",
                    )
                }
                throwIfInterrupted()
                if (verified.extensionId != address.extensionId) {
                    throw UnsupportedInputException(
                        "다운로드한 CRX3가 요청한 확장과 일치하지 않습니다.",
                    )
                }
                return bytes
            } finally {
                closeResponse(response)
            }
        }
    }

    private fun readBounded(response: DesktopDownloadResponse): ByteArray {
        val deadline = BodyReadDeadline(response)
        val deadlineTask = scheduleDeadline(deadline)
        try {
            return readBounded(response, deadline)
        } finally {
            deadlineTask.cancel(false)
        }
    }

    private fun readBounded(
        response: DesktopDownloadResponse,
        deadline: BodyReadDeadline,
    ): ByteArray {
        val declaredLength = response.contentLength
        if (declaredLength != null && declaredLength !in 0..MAX_DOWNLOAD_BYTES) {
            throw UnsupportedInputException(
                "Chrome CRX3 다운로드 크기가 허용 범위를 벗어났습니다.",
            )
        }
        val initialCapacity = minOf(
            declaredLength ?: DEFAULT_INITIAL_CAPACITY.toLong(),
            DEFAULT_INITIAL_CAPACITY.toLong(),
        ).toInt()
        val output = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var total = 0L
        while (true) {
            throwIfInterrupted()
            val remainingThroughBoundary = MAX_DOWNLOAD_BYTES - total + 1
            val readLimit = minOf(buffer.size.toLong(), remainingThroughBoundary).toInt()
            val count = readResponseBody(response, buffer, readLimit, deadline)
            throwIfDeadlineExpired(deadline)
            if (count < 0) break
            if (count == 0) {
                throw UnsupportedInputException(
                    "Chrome CRX3 다운로드 body 읽기가 진행되지 않습니다.",
                )
            }
            total += count
            if (total > MAX_DOWNLOAD_BYTES) {
                throw UnsupportedInputException(
                    "Chrome CRX3 다운로드 크기가 제한을 초과했습니다.",
                )
            }
            if (declaredLength != null && total > declaredLength) {
                throw UnsupportedInputException(
                    "Chrome CRX3 다운로드 Content-Length가 실제 body와 다릅니다.",
                )
            }
            output.write(buffer, 0, count)
        }
        throwIfInterrupted()
        if (declaredLength != null && declaredLength != total) {
            throw UnsupportedInputException(
                "Chrome CRX3 다운로드 Content-Length가 실제 body와 다릅니다.",
            )
        }
        if (!deadline.complete()) {
            throw downloadTimedOut()
        }
        return output.toByteArray()
    }

    private fun readResponseBody(
        response: DesktopDownloadResponse,
        buffer: ByteArray,
        readLimit: Int,
        deadline: BodyReadDeadline,
    ): Int {
        return try {
            response.body.read(buffer, 0, readLimit)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw downloadCancelled()
        } catch (_: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw downloadCancelled()
        } catch (_: CancellationException) {
            throw downloadCancelled()
        } catch (_: Exception) {
            if (Thread.currentThread().isInterrupted) {
                throw downloadCancelled()
            }
            throwIfDeadlineExpired(deadline)
            throw UnsupportedInputException(
                "Chrome CRX3 다운로드 body를 읽지 못했습니다.",
            )
        }
    }

    private fun scheduleDeadline(deadline: BodyReadDeadline): ScheduledFuture<*> {
        return try {
            deadlineScheduler.schedule(
                deadline,
                bodyReadTimeout.toNanos(),
                TimeUnit.NANOSECONDS,
            )
        } catch (_: CancellationException) {
            throw downloadCancelled()
        } catch (_: Exception) {
            if (Thread.currentThread().isInterrupted) {
                throw downloadCancelled()
            }
            throw UnsupportedInputException(
                "Chrome CRX3 다운로드 제한 시간을 설정하지 못했습니다.",
            )
        }
    }

    private fun throwIfDeadlineExpired(deadline: BodyReadDeadline) {
        if (deadline.isExpired()) {
            throw downloadTimedOut()
        }
    }

    private fun executeTransport(uri: URI): DesktopDownloadResponse {
        return try {
            transport.execute(uri)
        } catch (error: InterruptedException) {
            throw error
        } catch (error: InterruptedIOException) {
            throw error
        } catch (_: CancellationException) {
            throw downloadCancelled()
        } catch (_: Exception) {
            if (Thread.currentThread().isInterrupted) {
                throw downloadCancelled()
            }
            throw UnsupportedInputException(
                "Chrome CRX3 다운로드 통신에 실패했습니다.",
            )
        }
    }

    private fun rejectHtml(contentType: String?, bytes: ByteArray) {
        val mediaType = contentType
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase(Locale.ROOT)
        if (mediaType == "text/html" || mediaType == "application/xhtml+xml") {
            throw UnsupportedInputException("Chrome CRX3 다운로드 응답이 HTML입니다.")
        }
        var offset = 0
        while (offset < bytes.size && isHtmlWhitespace(bytes[offset])) offset++
        if (hasUtf8BomAt(bytes, offset)) offset += UTF8_BOM.size
        while (offset < bytes.size && isHtmlWhitespace(bytes[offset])) offset++
        val prefix = String(
            bytes,
            offset,
            minOf(HTML_PREFIX_BYTES, bytes.size - offset),
            StandardCharsets.US_ASCII,
        ).lowercase(Locale.ROOT)
        if (prefix.startsWith("<html") || prefix.startsWith("<!doctype html")) {
            throw UnsupportedInputException("Chrome CRX3 다운로드 응답이 HTML입니다.")
        }
    }

    private fun isHtmlWhitespace(value: Byte): Boolean =
        value == ' '.code.toByte() ||
            value == '\t'.code.toByte() ||
            value == '\r'.code.toByte() ||
            value == '\n'.code.toByte() ||
            value == '\u000c'.code.toByte()

    private fun hasUtf8BomAt(bytes: ByteArray, offset: Int): Boolean =
        offset <= bytes.size - UTF8_BOM.size &&
            bytes[offset] == UTF8_BOM[0] &&
            bytes[offset + 1] == UTF8_BOM[1] &&
            bytes[offset + 2] == UTF8_BOM[2]

    private fun requireAllowedUri(uri: URI) {
        if (!allows(uri)) {
            throw UnsupportedInputException(
                "Chrome CRX3 다운로드 redirect 대상이 허용되지 않습니다.",
            )
        }
    }

    private fun allows(uri: URI): Boolean {
        if (
            uri.isOpaque ||
            uri.scheme != "https" ||
            uri.userInfo != null ||
            uri.fragment != null ||
            uri.port !in setOf(-1, 443)
        ) {
            return false
        }
        val originalHost = uri.host ?: return false
        if (
            originalHost.endsWith('.') ||
            originalHost.any { character ->
                character.code !in 0x21..0x7e ||
                    !(character.isLetterOrDigit() || character == '-' || character == '.')
            }
        ) {
            return false
        }
        val labels = originalHost.split('.')
        if (
            labels.any { label ->
                label.isEmpty() ||
                    label.startsWith('-') ||
                    label.endsWith('-') ||
                    label.startsWith("xn--", ignoreCase = true)
            }
        ) {
            return false
        }
        val host = originalHost.lowercase(Locale.ROOT)
        return host in EXACT_HOSTS ||
            (host.endsWith(GVT1_SUFFIX) && host.length > GVT1_SUFFIX.length)
    }

    private fun resolveLocation(current: URI, location: String): URI {
        return try {
            current.resolve(URI(location))
        } catch (_: IllegalArgumentException) {
            throw UnsupportedInputException(
                "Chrome CRX3 다운로드 redirect 위치가 올바르지 않습니다.",
            )
        }
    }

    private fun closeResponse(response: DesktopDownloadResponse) {
        try {
            response.close()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw downloadCancelled()
        } catch (_: InterruptedIOException) {
            Thread.currentThread().interrupt()
            throw downloadCancelled()
        } catch (_: CancellationException) {
            throw downloadCancelled()
        } catch (_: Exception) {
            if (Thread.currentThread().isInterrupted) {
                throw downloadCancelled()
            }
            throw UnsupportedInputException(
                "Chrome CRX3 다운로드 응답을 안전하게 닫지 못했습니다.",
            )
        }
    }

    private fun throwIfInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw downloadCancelled()
        }
    }

    private fun downloadCancelled(): CancellationException =
        CancellationException("Chrome CRX3 다운로드가 취소되었습니다.")

    private fun downloadTimedOut(): UnsupportedInputException =
        UnsupportedInputException("Chrome CRX3 다운로드 body 시간이 제한을 초과했습니다.")

    private fun updateUri(address: ChromeWebStoreAddress): URI = URI(
        "https://clients2.google.com/service/update2/crx" +
            "?response=redirect&prodversion=130.0&acceptformat=crx3" +
            "&x=id%3D${address.extensionId}%26uc",
    )

    companion object {
        internal fun testing(
            transport: DesktopDownloadTransport,
            bodyReadTimeout: Duration,
            deadlineScheduler: ScheduledExecutorService,
        ): DesktopChromeCrxDownloader {
            require(!bodyReadTimeout.isZero && !bodyReadTimeout.isNegative)
            return DesktopChromeCrxDownloader(
                transport = transport,
                bodyReadTimeout = bodyReadTimeout,
                deadlineScheduler = deadlineScheduler,
            )
        }

        private const val MAX_REDIRECTS = 5
        private const val MAX_DOWNLOAD_BYTES = 134_217_728L
        private const val READ_BUFFER_SIZE = 64 * 1024
        private const val DEFAULT_INITIAL_CAPACITY = 8 * 1024
        private const val HTML_PREFIX_BYTES = 64
        private val UTF8_BOM = byteArrayOf(
            0xef.toByte(),
            0xbb.toByte(),
            0xbf.toByte(),
        )
        private val EXACT_HOSTS = setOf(
            "clients2.google.com",
            "clients2.googleusercontent.com",
            "redirector.gvt1.com",
        )
        private const val GVT1_SUFFIX = ".gvt1.com"
        private val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        private val DEFAULT_BODY_READ_TIMEOUT = Duration.ofSeconds(60)
        private val DEFAULT_DEADLINE_SCHEDULER: ScheduledExecutorService =
            ScheduledThreadPoolExecutor(1) { runnable ->
                Thread(runnable, "sixbridge-crx-download-deadline").apply {
                    isDaemon = true
                }
            }.apply {
                removeOnCancelPolicy = true
            }
    }

    private class BodyReadDeadline(
        private val response: DesktopDownloadResponse,
    ) : Runnable {
        private val state = AtomicReference(BodyReadState.ACTIVE)

        override fun run() {
            if (state.compareAndSet(BodyReadState.ACTIVE, BodyReadState.EXPIRED)) {
                try {
                    response.close()
                } catch (_: Exception) {
                    // read 경계가 고정 timeout 오류로 변환하며 close 오류는 노출하지 않는다.
                }
            }
        }

        fun isExpired(): Boolean = state.get() == BodyReadState.EXPIRED

        fun complete(): Boolean =
            state.compareAndSet(BodyReadState.ACTIVE, BodyReadState.COMPLETED)
    }

    private enum class BodyReadState {
        ACTIVE,
        EXPIRED,
        COMPLETED,
    }
}

private class JavaHttpDownloadTransport(
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
) : DesktopDownloadTransport {
    override fun execute(uri: URI): DesktopDownloadResponse {
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("User-Agent", "SIX-Bridge/0.1")
            .GET()
            .build()
        val response = client.send(
            request,
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        return try {
            DesktopDownloadResponse(
                statusCode = response.statusCode(),
                contentType = response.headers().firstValue("Content-Type").orElse(null),
                contentLength = singleLongHeader(response, "Content-Length"),
                location = singleHeader(response, "Location"),
                body = response.body(),
            )
        } catch (_: Exception) {
            try {
                response.body().close()
            } catch (_: Exception) {
                // metadata 실패가 우선이며 body close는 best effort다.
            }
            throw IOException("Chrome CRX3 응답 metadata를 읽을 수 없습니다.")
        }
    }

    private fun singleLongHeader(
        response: HttpResponse<InputStream>,
        name: String,
    ): Long? = singleHeader(response, name)?.toLongOrNull()
        ?: if (response.headers().allValues(name).isEmpty()) {
            null
        } else {
            throw IOException("Chrome CRX3 숫자 header가 올바르지 않습니다.")
        }

    private fun singleHeader(
        response: HttpResponse<InputStream>,
        name: String,
    ): String? {
        val values = response.headers().allValues(name)
        if (values.isEmpty()) return null
        if (values.size != 1) {
            throw IOException("Chrome CRX3 response header가 중복되었습니다.")
        }
        return values.single()
    }
}
