package dev.sixbridge.desktop.input

import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.input.ChromeWebStoreAddress
import dev.sixbridge.core.input.UnsupportedInputException
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URI
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopChromeCrxDownloaderTest {
    @Test
    fun `고정 Chrome update endpoint에서 서명과 요청 ID가 일치하는 CRX3만 반환한다`() {
        val fixture = response(VALID_CRX.bytes, "application/x-chrome-extension")
        val transport = FakeTransport(fixture)

        val downloaded = DesktopChromeCrxDownloader(transport)
            .download(address(VALID_CRX.extensionId))

        assertContentEquals(VALID_CRX.bytes, downloaded)
        assertEquals(
            "https://clients2.google.com/service/update2/crx" +
                "?response=redirect&prodversion=130.0&acceptformat=crx3" +
                "&x=id%3D${VALID_CRX.extensionId}%26uc",
            transport.requested.single().toASCIIString(),
        )
        assertTrue(fixture.closed.get())
    }

    @Test
    fun `상대 Location과 대소문자 gvt1 subdomain을 resolve하고 매 hop을 다시 검증한다`() {
        val first = redirect("/downloads/one")
        val second = redirect("https://R3---SN.GVT1.COM/downloads/two")
        val final = response(VALID_CRX.bytes, null)
        val transport = FakeTransport(first, second, final)

        val downloaded = DesktopChromeCrxDownloader(transport)
            .download(address(VALID_CRX.extensionId))

        assertContentEquals(VALID_CRX.bytes, downloaded)
        assertEquals("clients2.google.com", transport.requested[1].host)
        assertEquals("R3---SN.GVT1.COM", transport.requested[2].host)
        assertTrue(listOf(first, second, final).all { it.closed.get() })
    }

    @Test
    fun `userinfo fragment 비표준 port와 공격 host redirect를 거부한다`() {
        val locations = listOf(
            "http://clients2.google.com/crx",
            "https://user@clients2.google.com/crx",
            "https://clients2.google.com/crx#fragment",
            "https://clients2.google.com:444/crx",
            "https://clients2.google.com.evil.example/crx",
            "https://gvt1.com/crx",
            "https://evilgvt1.com/crx",
            "https://xn--pwned.gvt1.com/crx",
            "https://r3.gvt1.com./crx",
            "https://[::1]/crx",
            "https://googl\u0435.com/crx",
            "//evil.example/crx",
        )

        locations.forEach { location ->
            val fixture = redirect(location)
            val error = assertFailsWith<UnsupportedInputException>(location) {
                DesktopChromeCrxDownloader(FakeTransport(fixture))
                    .download(address(VALID_CRX.extensionId))
            }

            assertTrue(fixture.closed.get(), location)
            assertFalse(error.message.orEmpty().contains(VALID_CRX.extensionId), location)
            assertFalse(error.message.orEmpty().contains(location), location)
        }
    }

    @Test
    fun `redirect는 정확히 다섯 번까지만 허용하고 모든 response를 닫는다`() {
        val allowedRedirects = (1..5).map { redirect("/allowed-$it") }
        val final = response(VALID_CRX.bytes, null)
        val allowedTransport = FakeTransport(*(allowedRedirects + final).toTypedArray())

        assertContentEquals(
            VALID_CRX.bytes,
            DesktopChromeCrxDownloader(allowedTransport)
                .download(address(VALID_CRX.extensionId)),
        )
        assertEquals(6, allowedTransport.requested.size)
        assertTrue((allowedRedirects + final).all { it.closed.get() })

        val excessiveRedirects = (1..6).map { redirect("/excessive-$it") }
        val excessiveTransport = FakeTransport(*excessiveRedirects.toTypedArray())

        assertFailsWith<UnsupportedInputException> {
            DesktopChromeCrxDownloader(excessiveTransport)
                .download(address(VALID_CRX.extensionId))
        }
        assertEquals(6, excessiveTransport.requested.size)
        assertTrue(excessiveRedirects.all { it.closed.get() })
    }

    @Test
    fun `Content-Length 음수 초과와 실제 body 길이 불일치를 거부한다`() {
        listOf(
            response(VALID_CRX.bytes, null, contentLength = -1),
            response(VALID_CRX.bytes, null, contentLength = MAX_DOWNLOAD_BYTES + 1),
            response(VALID_CRX.bytes, null, contentLength = VALID_CRX.bytes.size.toLong() - 1),
            response(VALID_CRX.bytes, null, contentLength = VALID_CRX.bytes.size.toLong() + 1),
        ).forEach { fixture ->
            assertFailsWith<UnsupportedInputException> {
                DesktopChromeCrxDownloader(FakeTransport(fixture))
                    .download(address(VALID_CRX.extensionId))
            }
            assertTrue(fixture.closed.get())
        }
    }

    @Test
    fun `chunked body는 128MiB 다음 byte에서 중단하고 부분 bytes를 반환하지 않는다`() {
        val body = GeneratedInputStream(MAX_DOWNLOAD_BYTES + READ_BUFFER_SIZE)
        val fixture = response(body = body, contentType = null, contentLength = null)

        assertFailsWith<UnsupportedInputException> {
            DesktopChromeCrxDownloader(FakeTransport(fixture))
                .download(address(VALID_CRX.extensionId))
        }

        assertEquals(MAX_DOWNLOAD_BYTES + 1, body.bytesRead)
        assertTrue(fixture.closed.get())
    }

    @Test
    fun `HTML content type과 BOM whitespace 대소문자 HTML prefix를 거부한다`() {
        val htmlWithPrefix = byteArrayOf(
            0xef.toByte(),
            0xbb.toByte(),
            0xbf.toByte(),
        ) + " \r\n\t<!DoCtYpE hTmL><html/>".encodeToByteArray()
        listOf(
            response(VALID_CRX.bytes, " Text/HTML; Charset=UTF-8"),
            response(htmlWithPrefix, "application/octet-stream"),
            response("\n\t<HtMl><body/></hTmL>".encodeToByteArray(), null),
        ).forEach { fixture ->
            assertFailsWith<UnsupportedInputException> {
                DesktopChromeCrxDownloader(FakeTransport(fixture))
                    .download(address(VALID_CRX.extensionId))
            }
            assertTrue(fixture.closed.get())
        }
    }

    @Test
    fun `CRX magic만 있는 값과 요청 ID가 다른 유효 CRX3를 거부한다`() {
        val magicOnly = response(
            "Cr24-not-a-signed-crx3".encodeToByteArray(),
            "application/x-chrome-extension",
        )
        assertFailsWith<UnsupportedInputException> {
            DesktopChromeCrxDownloader(FakeTransport(magicOnly))
                .download(address(VALID_CRX.extensionId))
        }
        assertTrue(magicOnly.closed.get())

        val requestedId = if (VALID_CRX.extensionId == "a".repeat(32)) {
            "b".repeat(32)
        } else {
            "a".repeat(32)
        }
        val mismatch = response(VALID_CRX.bytes, "application/x-chrome-extension")
        val error = assertFailsWith<UnsupportedInputException> {
            DesktopChromeCrxDownloader(FakeTransport(mismatch))
                .download(address(requestedId))
        }
        assertTrue(mismatch.closed.get())
        assertFalse(error.message.orEmpty().contains(requestedId))
        assertFalse(error.message.orEmpty().contains(VALID_CRX.extensionId))
    }

    @Test
    fun `HTTP와 malformed Location 및 transport 오류는 URL query와 ID를 노출하지 않는다`() {
        val requestedId = VALID_CRX.extensionId
        val fixtures = listOf(
            FakeTransport(response("error".encodeToByteArray(), null, statusCode = 503)),
            FakeTransport(redirect("https://clients2.google.com/%")),
            FakeTransport(
                failure = IOException(
                    "secret https://example.test/?x=id%3D$requestedId",
                ),
            ),
            FakeTransport(
                failure = UnsupportedInputException(
                    "secret https://example.test/?x=id%3D$requestedId",
                ),
            ),
        )

        fixtures.forEach { transport ->
            val error = assertFailsWith<UnsupportedInputException> {
                DesktopChromeCrxDownloader(transport).download(address(requestedId))
            }
            val message = error.message.orEmpty()
            assertFalse(message.contains(requestedId))
            assertFalse(message.contains("response=redirect"))
            assertFalse(message.contains("x=id"))
            assertFalse(message.contains("example.test"))
        }
    }

    @Test
    fun `body read interrupt는 response를 닫고 interrupt를 보존하며 bytes를 반환하지 않는다`() {
        val body = BlockingInputStream()
        val fixture = response(body = body, contentType = null, contentLength = null)
        val result = AtomicReference<ByteArray?>()
        val failure = AtomicReference<Throwable?>()
        val interruptedAtExit = AtomicBoolean(false)
        val thread = Thread {
            try {
                result.set(
                    DesktopChromeCrxDownloader(FakeTransport(fixture))
                        .download(address(VALID_CRX.extensionId)),
                )
            } catch (error: Throwable) {
                failure.set(error)
            } finally {
                interruptedAtExit.set(Thread.currentThread().isInterrupted)
            }
        }

        thread.start()
        assertTrue(body.started.await(3, TimeUnit.SECONDS))
        thread.interrupt()
        thread.join(3_000)

        assertFalse(thread.isAlive)
        assertNull(result.get())
        assertTrue(assertNotNull(failure.get()) is CancellationException)
        assertTrue(fixture.closed.get())
        assertTrue(body.closed.get())
        assertTrue(interruptedAtExit.get())
    }

    private fun address(extensionId: String): ChromeWebStoreAddress =
        ChromeWebStoreAddress.parse(
            "https://chromewebstore.google.com/detail/test/$extensionId",
        )

    private fun redirect(location: String): ResponseFixture =
        response(
            bytes = byteArrayOf(),
            contentType = null,
            statusCode = 302,
            location = location,
        )

    private fun response(
        bytes: ByteArray,
        contentType: String?,
        contentLength: Long? = bytes.size.toLong(),
        statusCode: Int = 200,
        location: String? = null,
    ): ResponseFixture = response(
        body = ByteArrayInputStream(bytes),
        contentType = contentType,
        contentLength = contentLength,
        statusCode = statusCode,
        location = location,
    )

    private fun response(
        body: InputStream,
        contentType: String?,
        contentLength: Long?,
        statusCode: Int = 200,
        location: String? = null,
    ): ResponseFixture {
        val closed = AtomicBoolean(false)
        val tracked = object : InputStream() {
            override fun read(): Int = body.read()

            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                body.read(target, offset, length)

            override fun close() {
                closed.set(true)
                body.close()
            }
        }
        return ResponseFixture(
            response = DesktopDownloadResponse(
                statusCode = statusCode,
                contentType = contentType,
                contentLength = contentLength,
                location = location,
                body = tracked,
            ),
            closed = closed,
        )
    }

    private data class ResponseFixture(
        val response: DesktopDownloadResponse,
        val closed: AtomicBoolean,
    )

    private class FakeTransport(
        vararg fixtures: ResponseFixture,
        private val failure: Exception? = null,
    ) : DesktopDownloadTransport {
        private val responses = ArrayDeque(fixtures.toList())
        val requested = mutableListOf<URI>()

        override fun execute(uri: URI): DesktopDownloadResponse {
            requested += uri
            failure?.let { throw it }
            return responses.removeFirst().response
        }
    }

    private class GeneratedInputStream(
        byteCount: Long,
    ) : InputStream() {
        private var remaining = byteCount
        var bytesRead = 0L
            private set

        override fun read(): Int {
            if (remaining == 0L) return -1
            remaining--
            bytesRead++
            return 0
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (remaining == 0L) return -1
            val count = minOf(length.toLong(), remaining).toInt()
            target.fill(0, offset, offset + count)
            remaining -= count
            bytesRead += count
            return count
        }
    }

    private class BlockingInputStream : InputStream() {
        val started = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        private val release = CountDownLatch(1)

        override fun read(): Int {
            started.countDown()
            try {
                release.await()
            } catch (_: InterruptedException) {
                throw InterruptedIOException("blocked read interrupted")
            }
            return -1
        }

        override fun close() {
            closed.set(true)
            release.countDown()
        }
    }

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 134_217_728L
        private const val READ_BUFFER_SIZE = 64 * 1024
        private val VALID_CRX by lazy {
            val source = Files.createTempDirectory("desktop-crx-download-fixture")
            source.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Fixture","version":"1"}""",
            )
            Crx3.pack(
                source,
                KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                    .generateKeyPair(),
            )
        }
    }
}
