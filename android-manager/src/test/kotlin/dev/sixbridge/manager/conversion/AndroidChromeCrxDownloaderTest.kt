package dev.sixbridge.manager.conversion

import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.input.ChromeWebStoreAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.LockSupport
import javax.net.ssl.HttpsURLConnection
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test

class AndroidChromeCrxDownloaderTest {
    @Test
    fun `허용 host는 exact Google과 label 경계 gvt1 subdomain뿐이다`() {
        val policy = ChromeDownloadHostPolicy()

        assertTrue(policy.allows("clients2.google.com"))
        assertTrue(policy.allows("clients2.googleusercontent.com"))
        assertTrue(policy.allows("redirector.gvt1.com"))
        assertTrue(policy.allows("r3---sn.example.gvt1.com"))
        assertFalse(policy.allows("gvt1.com"))
        assertFalse(policy.allows("clients2.googleusercontent.com.evil.example"))
        assertFalse(policy.allows("evilgvt1.com"))
    }

    @Test
    fun `유효 CRX3를 streaming하고 요청 ID hash byte count를 증명한다`() = runTest {
        val packed = validCrx()
        val transport = FakeTransport(
            response(200, packed.bytes, "application/x-chrome-extension"),
        )
        val output = destination()
        val address = address(packed.extensionId)

        val proof = AndroidChromeCrxDownloader(transport).download(address, output)

        assertContentEquals(packed.bytes, Files.readAllBytes(output))
        assertEquals(packed.extensionId, proof.extensionId)
        assertEquals(packed.bytes.size.toLong(), proof.byteCount)
        assertEquals(sha256(packed.bytes), proof.sha256)
        assertEquals(15_000, transport.requests.single().connectTimeoutMillis)
        assertEquals(60_000, transport.requests.single().readTimeoutMillis)
        assertEquals("https", transport.requests.single().uri.scheme)
        assertEquals("clients2.google.com", transport.requests.single().uri.host)
    }

    @Test
    fun `기존 OutputStream 공개 API도 검증된 CRX와 증명을 반환한다`() = runTest {
        val packed = validCrx()
        val transport = FakeTransport(
            response(200, packed.bytes, "application/x-chrome-extension"),
        )
        val output = ByteArrayOutputStream()

        val proof = AndroidChromeCrxDownloader(transport).download(
            address(packed.extensionId),
            output,
        )

        assertContentEquals(packed.bytes, output.toByteArray())
        assertEquals(packed.extensionId, proof.extensionId)
        assertEquals(packed.bytes.size.toLong(), proof.byteCount)
        assertEquals(sha256(packed.bytes), proof.sha256)
    }

    @Test
    fun `redirect 6회째 응답을 거부한다`() = runTest {
        val responses = (1..6).map { index ->
            redirect("https://r$index.gvt1.com/crx")
        }
        val transport = FakeTransport(*responses.toTypedArray())

        val error = assertFailsWith<ChromeDownloadException> {
            AndroidChromeCrxDownloader(transport).download(
                address("a".repeat(32)),
                destination(),
            )
        }

        assertEquals(ChromeDownloadErrorCode.TOO_MANY_REDIRECTS, error.code)
        assertEquals(6, transport.requests.size)
    }

    @Test
    fun `HTTPS에서 HTTP로 내리는 redirect를 거부한다`() = runTest {
        val transport = FakeTransport(redirect("http://clients2.google.com/crx"))

        val error = assertFailsWith<ChromeDownloadException> {
            AndroidChromeCrxDownloader(transport).download(
                address("a".repeat(32)),
                destination(),
            )
        }

        assertEquals(ChromeDownloadErrorCode.REDIRECT_REJECTED, error.code)
    }

    @Test
    fun `Google처럼 보이는 공격 redirect host를 거부한다`() = runTest {
        val transport = FakeTransport(
            redirect("https://clients2.googleusercontent.com.evil.example/crx"),
        )

        val error = assertFailsWith<ChromeDownloadException> {
            AndroidChromeCrxDownloader(transport).download(
                address("a".repeat(32)),
                destination(),
            )
        }

        assertEquals(ChromeDownloadErrorCode.REDIRECT_REJECTED, error.code)
    }

    @Test
    fun `HTML 응답을 CRX로 저장하지 않는다`() = runTest {
        val output = destination()
        val transport = FakeTransport(
            response(200, "<html>login</html>".encodeToByteArray(), "text/html"),
        )

        val error = assertFailsWith<ChromeDownloadException> {
            AndroidChromeCrxDownloader(transport).download(
                address("a".repeat(32)),
                output,
            )
        }

        assertEquals(ChromeDownloadErrorCode.HTML_RESPONSE, error.code)
        assertFalse(Files.exists(output))
    }

    @Test
    fun `128 MiB 초과 응답을 bounded streaming으로 거부한다`() = runTest {
        val transport = FakeTransport(
            ChromeDownloadResponse(
                statusCode = 200,
                contentType = "application/x-chrome-extension",
                location = null,
                body = GeneratedInputStream(134_217_729L),
            ),
        )

        val error = assertFailsWith<ChromeDownloadException> {
            AndroidChromeCrxDownloader(transport).download(
                address("a".repeat(32)),
                destination(),
            )
        }

        assertEquals(ChromeDownloadErrorCode.RESPONSE_TOO_LARGE, error.code)
    }

    @Test
    fun `정확히 128 MiB 응답은 크기 제한을 통과한다`() = runTest {
        val transport = FakeTransport(
            ChromeDownloadResponse(
                statusCode = 200,
                contentType = "application/x-chrome-extension",
                location = null,
                body = GeneratedInputStream(134_217_728L),
            ),
        )

        val error = assertFailsWith<ChromeDownloadException> {
            AndroidChromeCrxDownloader(transport).download(
                address("a".repeat(32)),
                destination(),
            )
        }

        assertEquals(ChromeDownloadErrorCode.INVALID_CRX3, error.code)
    }

    @Test
    fun `서명은 유효하지만 요청 ID가 다른 CRX를 거부한다`() = runTest {
        val packed = validCrx()
        val requestedId = if (packed.extensionId.first() == 'a') {
            "b" + packed.extensionId.drop(1)
        } else {
            "a" + packed.extensionId.drop(1)
        }
        val transport = FakeTransport(
            response(200, packed.bytes, "application/x-chrome-extension"),
        )

        val error = assertFailsWith<ChromeDownloadException> {
            AndroidChromeCrxDownloader(transport).download(
                address(requestedId),
                destination(),
            )
        }

        assertEquals(ChromeDownloadErrorCode.EXTENSION_ID_MISMATCH, error.code)
        assertFalse(error.message.orEmpty().contains(packed.extensionId))
        assertFalse(error.message.orEmpty().contains(requestedId))
    }

    @Test
    fun `호출 취소는 blocking Chrome body를 닫는다`() = runTest {
        val body = CancellationBlockingInputStream()
        val downloader = AndroidChromeCrxDownloader(
            FakeTransport(
                ChromeDownloadResponse(
                    statusCode = 200,
                    contentType = "application/x-chrome-extension",
                    location = null,
                    body = body,
                ),
            ),
        )
        val job = launch {
            downloader.download(address("a".repeat(32)), destination())
        }
        body.started.await()

        job.cancelAndJoin()

        assertTrue(body.closed.get())
    }

    @Test
    fun `부분 write 실패 뒤 destination 삭제 실패는 structured cleanup 오류다`() = runTest {
        val parent = Files.createTempDirectory("chrome-download-cleanup-failure")
        val destination = parent.resolve("input.crx")
        val writable = Files.getPosixFilePermissions(parent)
        val locked = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val reports = CopyOnWriteArrayList<ChromeDownloadException>()
        val body = object : InputStream() {
            private var first = true

            override fun read(): Int = error("bulk read만 사용해야 합니다.")

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                if (first) {
                    first = false
                    buffer[offset] = 'C'.code.toByte()
                    Files.setPosixFilePermissions(parent, locked)
                    return 1
                }
                throw IOException("private-source")
            }
        }
        try {
            val error = assertFailsWith<ChromeDownloadException> {
                AndroidChromeCrxDownloader(
                    transport = FakeTransport(
                        ChromeDownloadResponse(
                            statusCode = 200,
                            contentType = "application/x-chrome-extension",
                            location = null,
                            body = body,
                        ),
                    ),
                    cleanupFailureReporter =
                        ChromeDownloadCleanupFailureReporter {
                            reports += it
                            throw IllegalStateException("private-reporter")
                        },
                ).download(address("a".repeat(32)), destination)
            }

            assertEquals(ChromeDownloadErrorCode.CLEANUP_FAILED, error.code)
            assertTrue(reports.single() === error)
            assertNull(error.cause)
            assertTrue(error.suppressed.isEmpty())
            assertFalse(error.message.orEmpty().contains(destination.toString()))
            assertTrue(Files.exists(destination))
        } finally {
            Files.setPosixFilePermissions(parent, writable)
            Files.deleteIfExists(destination)
            Files.deleteIfExists(parent)
        }
    }

    @Test
    fun `같은 IO dispatcher cancel은 cleanup 실패에도 cancellation이다`() = runBlocking {
        val secret = "customer-secret-cancel-cleanup"
        val parent = Files.createTempDirectory(secret)
        val destination = parent.resolve("input.crx")
        val body = CancellationDeleteFailureInputStream(destination)
        val reports = CopyOnWriteArrayList<ChromeDownloadException>()
        val io = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "chrome-download-shared-io")
        }.asCoroutineDispatcher()
        try {
            val completion = CompletableDeferred<Throwable?>()
            val job = launch(io) {
                AndroidChromeCrxDownloader(
                    transport = FakeTransport(
                        ChromeDownloadResponse(
                            statusCode = 200,
                            contentType = "application/x-chrome-extension",
                            location = null,
                            body = body,
                        ),
                    ),
                    ioDispatcher = io,
                    cleanupFailureReporter =
                        ChromeDownloadCleanupFailureReporter {
                            reports += it
                        },
                ).download(address("a".repeat(32)), destination)
            }
            job.invokeOnCompletion { completion.complete(it) }
            body.started.await()

            job.cancel()
            job.join()
            val cancellation = completion.await()

            assertTrue(cancellation is CancellationException)
            assertTrue(cancellation.suppressed.isEmpty())
            assertFalse(cancellation.message.orEmpty().contains(secret))
            assertFalse(cancellation.stackTraceToString().contains(secret))
            assertTrue(Files.exists(destination))
            assertEquals(1, reports.size)
            val cleanup = reports.single()
            assertEquals(ChromeDownloadErrorCode.CLEANUP_FAILED, cleanup.code)
            assertNull(cleanup.cause)
            assertTrue(cleanup.suppressed.isEmpty())
            assertFalse(cleanup.message.orEmpty().contains(destination.toString()))
            assertFalse(cleanup.stackTraceToString().contains(secret))
        } finally {
            io.close()
            Files.deleteIfExists(destination.resolve("blocker"))
            Files.deleteIfExists(destination)
            Files.deleteIfExists(parent)
        }
    }

    @Test
    fun `public downloader transport copy verification close는 주입 IO dispatcher에서만 실행한다`() = runTest {
        val packed = validCrx()
        val threads = CopyOnWriteArrayList<String>()
        val io = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "chrome-download-io")
        }.asCoroutineDispatcher()
        try {
            val body = object : ByteArrayInputStream(packed.bytes) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    threads += Thread.currentThread().name
                    return super.read(buffer, offset, length)
                }
            }
            val transport = ChromeDownloadTransport {
                threads += Thread.currentThread().name
                ChromeDownloadResponse(
                    statusCode = 200,
                    contentType = "application/x-chrome-extension",
                    location = null,
                    body = body,
                    closeAction = { threads += Thread.currentThread().name },
                )
            }
            val downloader = AndroidChromeCrxDownloader(
                transport = transport,
                ioDispatcher = io,
                crxVerifier = CrxPathVerifier { path, maximumBytes ->
                    threads += Thread.currentThread().name
                    Crx3Verifier.verify(path, maximumBytes)
                },
            )

            downloader.download(address(packed.extensionId), destination())

            assertTrue(threads.isNotEmpty())
            assertTrue(threads.all { it == "chrome-download-io" })
        } finally {
            io.close()
        }
    }

    @Test
    fun `HTTPS header 대기 취소는 connection과 cancelled response body를 닫는다`() = runTest {
        val connection = HeaderBlockingHttpsConnection()
        val transport = HttpsChromeDownloadTransport(
            HttpsConnectionFactory { connection },
        )
        val io = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "https-header-io")
        }.asCoroutineDispatcher()
        val downloader = AndroidChromeCrxDownloader(
            transport = transport,
            ioDispatcher = io,
        )
        try {
            val job = launch {
                downloader.download(address("a".repeat(32)), destination())
            }
            connection.started.await()

            job.cancelAndJoin()

            assertTrue(connection.disconnected.get())
            assertTrue(connection.bodyClosed.get())
        } finally {
            io.close()
        }
    }

    private fun validCrx() = Crx3.pack(
        Files.createTempDirectory("chrome-download-extension").also {
            it.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Download","version":"1"}""",
            )
        },
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair(),
    )

    private fun address(extensionId: String): ChromeWebStoreAddress =
        ChromeWebStoreAddress.parse(
            "https://chromewebstore.google.com/detail/name/$extensionId",
        )

    private fun destination() =
        Files.createTempDirectory("chrome-download-output").resolve("input.crx")

    private fun redirect(location: String) = ChromeDownloadResponse(
        statusCode = 302,
        contentType = null,
        location = location,
        body = ByteArrayInputStream(ByteArray(0)),
    )

    private fun response(
        status: Int,
        bytes: ByteArray,
        contentType: String?,
    ) = ChromeDownloadResponse(
        statusCode = status,
        contentType = contentType,
        location = null,
        body = ByteArrayInputStream(bytes),
    )

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private class FakeTransport(
        vararg responses: ChromeDownloadResponse,
    ) : ChromeDownloadTransport {
        private val responses = ArrayDeque(responses.toList())
        val requests = mutableListOf<ChromeDownloadRequest>()

        override suspend fun execute(request: ChromeDownloadRequest): ChromeDownloadResponse {
            requests += request
            return responses.removeFirst()
        }
    }

    private class GeneratedInputStream(
        private val size: Long,
    ) : InputStream() {
        private var position = 0L

        override fun read(): Int =
            if (position++ < size) 0 else -1

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position >= size) return -1
            val count = minOf(length.toLong(), size - position).toInt()
            buffer.fill(0, offset, offset + count)
            position += count
            return count
        }
    }

    private class CancellationBlockingInputStream : InputStream() {
        val started = CompletableDeferred<Unit>()
        val closed = AtomicBoolean()

        override fun read(): Int = error("bulk read만 사용해야 합니다.")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            started.complete(Unit)
            while (!closed.get()) {
                LockSupport.parkNanos(1_000_000)
            }
            throw IOException("closed")
        }

        override fun close() {
            closed.set(true)
        }
    }

    private class CancellationDeleteFailureInputStream(
        private val destination: java.nio.file.Path,
    ) : InputStream() {
        val started = CompletableDeferred<Unit>()
        private val closed = AtomicBoolean()
        private var first = true

        override fun read(): Int = error("bulk read만 사용해야 합니다.")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (first) {
                first = false
                buffer[offset] = 'C'.code.toByte()
                Files.delete(destination)
                Files.createDirectory(destination)
                Files.write(
                    destination.resolve("blocker"),
                    byteArrayOf('x'.code.toByte()),
                )
                return 1
            }
            started.complete(Unit)
            while (!closed.get()) {
                LockSupport.parkNanos(1_000_000)
            }
            throw IOException("closed")
        }

        override fun close() {
            closed.set(true)
        }
    }

    private class HeaderBlockingHttpsConnection :
        HttpsURLConnection(URL("https://clients2.google.com/test")) {
        val started = CompletableDeferred<Unit>()
        val disconnected = AtomicBoolean()
        val bodyClosed = AtomicBoolean()
        private val body = object : ByteArrayInputStream(ByteArray(0)) {
            override fun close() {
                bodyClosed.set(true)
                super.close()
            }
        }

        override fun getResponseCode(): Int {
            started.complete(Unit)
            while (!disconnected.get()) {
                LockSupport.parkNanos(1_000_000)
            }
            return 200
        }

        override fun getInputStream(): InputStream = body

        override fun getContentType(): String = "application/x-chrome-extension"

        override fun disconnect() {
            disconnected.set(true)
        }

        override fun usingProxy(): Boolean = false

        override fun connect() = Unit

        override fun getCipherSuite(): String = "TLS_TEST"

        override fun getLocalCertificates(): Array<java.security.cert.Certificate>? = null

        override fun getServerCertificates(): Array<java.security.cert.Certificate> = emptyArray()
    }
}
