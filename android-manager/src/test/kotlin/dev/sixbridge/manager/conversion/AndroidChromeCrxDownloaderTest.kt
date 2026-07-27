package dev.sixbridge.manager.conversion

import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.input.ChromeWebStoreAddress
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.io.path.writeText
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
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
        val output = ByteArrayOutputStream()
        val address = address(packed.extensionId)

        val proof = AndroidChromeCrxDownloader(transport).download(address, output)

        assertContentEquals(packed.bytes, output.toByteArray())
        assertEquals(packed.extensionId, proof.extensionId)
        assertEquals(packed.bytes.size.toLong(), proof.byteCount)
        assertEquals(sha256(packed.bytes), proof.sha256)
        assertEquals(15_000, transport.requests.single().connectTimeoutMillis)
        assertEquals(60_000, transport.requests.single().readTimeoutMillis)
        assertEquals("https", transport.requests.single().uri.scheme)
        assertEquals("clients2.google.com", transport.requests.single().uri.host)
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
                ByteArrayOutputStream(),
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
                ByteArrayOutputStream(),
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
                ByteArrayOutputStream(),
            )
        }

        assertEquals(ChromeDownloadErrorCode.REDIRECT_REJECTED, error.code)
    }

    @Test
    fun `HTML 응답을 CRX로 저장하지 않는다`() = runTest {
        val output = ByteArrayOutputStream()
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
        assertEquals(0, output.size())
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
                OutputStream.nullOutputStream(),
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
                OutputStream.nullOutputStream(),
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
                ByteArrayOutputStream(),
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
            downloader.download(address("a".repeat(32)), OutputStream.nullOutputStream())
        }
        body.started.await()

        job.cancelAndJoin()

        assertTrue(body.closed.get())
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
}
