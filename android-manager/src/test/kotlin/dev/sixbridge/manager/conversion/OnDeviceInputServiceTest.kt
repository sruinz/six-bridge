package dev.sixbridge.manager.conversion

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.draft.DraftConversionPlan
import dev.sixbridge.core.draft.DraftInputMetadata
import dev.sixbridge.core.draft.DraftMetadata
import dev.sixbridge.core.draft.DraftSourceKind
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.draft.SixDraftPayload
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

class OnDeviceInputServiceTest {
    private val root = Files.createTempDirectory("on-device-input-test").toFile()

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `SAF ZIP은 UUID private artifact로 복사하고 원본 식별자를 버린다`() = runTest {
        val source = extensionZip()
        val secret = "content://private.provider/고객-비밀.zip"
        val service = service(ByteArrayInputStream(source))

        val artifact = service.acquire(safRequest())

        assertEquals(OnDeviceInputKind.ZIP, artifact.kind)
        assertEquals(UUID.fromString(artifact.token).toString(), artifact.token)
        assertEquals("input.bin", artifact.privateFile.name)
        assertEquals(artifact.token, requireNotNull(artifact.privateFile.parentFile).name)
        assertEquals(sha256(source), artifact.sha256)
        assertNull(artifact.expectedExtensionId)
        assertFalse(artifact.toString().contains(secret))
    }

    @Test
    fun `SAF CRX3는 서명과 내부 extension ZIP을 검증한다`() = runTest {
        val crx = validCrx()

        val artifact = service(ByteArrayInputStream(crx.bytes))
            .acquire(safRequest())

        assertEquals(OnDeviceInputKind.CRX3, artifact.kind)
        assertEquals(crx.extensionId, artifact.expectedExtensionId)
    }

    @Test
    fun `SAF schema v2 SixDraft를 strict reader로 판별한다`() = runTest {
        val draft = validDraftV2()

        val artifact = service(ByteArrayInputStream(draft))
            .acquire(safRequest())

        assertEquals(OnDeviceInputKind.SIX_DRAFT, artifact.kind)
        assertNull(artifact.expectedExtensionId)
    }

    @Test
    fun `각 draft marker 하나만 가진 정상 extension은 ZIP으로 판별한다`() = runTest {
        val markers = listOf(
            "draft.json",
            "conversion-plan.json",
            "compatibility-before.json",
            "compatibility-after.json",
            "expected.json",
            "draft-signature.json",
        )

        markers.forEach { marker ->
            val source = Files.createTempDirectory("on-device-overlap-extension")
            val zip = try {
                source.resolve("manifest.json").writeText(
                    """{"manifest_version":3,"name":"Overlap","version":"1"}""",
                )
                source.resolve(marker).writeText("""{"ordinary":true}""")
                ExtensionZip.createBytes(source)
            } finally {
                source.toFile().deleteRecursively()
            }

            val artifact = service(ByteArrayInputStream(zip)).acquire(safRequest())
            assertEquals(OnDeviceInputKind.ZIP, artifact.kind, marker)
            artifact.privateFile.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun `손상된 SixDraft를 일반 ZIP으로 낮추지 않고 전체 token을 정리한다`() = runTest {
        val draft = validDraftV2().copyOf().also {
            val signatureName = "draft-signature.json".encodeToByteArray()
            val offset = it.indexOf(signatureName)
            check(offset >= 0)
            it[offset] = 'x'.code.toByte()
        }
        val service = service(ByteArrayInputStream(draft))

        val error = assertFailsWith<OnDeviceInputException> {
            service.acquire(safRequest())
        }

        assertEquals(OnDeviceInputErrorCode.INVALID_DRAFT, error.code)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `signature가 제거되고 manifest가 추가된 draft도 INVALID_DRAFT다`() = runTest {
        val source = unsignedDraftWithManifest(validDraftV2())
        val service = service(ByteArrayInputStream(source))

        val error = assertFailsWith<OnDeviceInputException> {
            service.acquire(safRequest())
        }

        assertEquals(OnDeviceInputErrorCode.INVALID_DRAFT, error.code)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `SAF stream read 실패는 부분 파일과 민감한 provider 오류를 남기지 않는다`() = runTest {
        val secret = "content://private.provider/customer-secret.zip"
        val service = service(
            object : InputStream() {
                override fun read(): Int = throw IOException(secret)
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw IOException(secret)
            },
        )

        val error = assertFailsWith<OnDeviceInputException> {
            service.acquire(safRequest())
        }

        assertEquals(OnDeviceInputErrorCode.SOURCE_READ_FAILED, error.code)
        assertFalse(error.toString().contains(secret))
        assertNull(error.cause)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `SAF read와 close 실패의 raw secret을 cause와 suppressed에 남기지 않는다`() = runTest {
        val readSecret = "content://private.provider/read-secret.zip"
        val closeSecret = "content://private.provider/close-secret.zip"
        val service = service(
            object : InputStream() {
                override fun read(): Int = throw IOException(readSecret)
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw IOException(readSecret)

                override fun close() {
                    throw IOException(closeSecret)
                }
            },
        )

        val error = assertFailsWith<OnDeviceInputException> {
            service.acquire(safRequest())
        }

        assertEquals(OnDeviceInputErrorCode.SOURCE_READ_FAILED, error.code)
        assertNull(error.cause)
        assertTrue(error.suppressed.isEmpty())
        assertFalse(error.stackTraceToString().contains(readSecret))
        assertFalse(error.stackTraceToString().contains(closeSecret))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `진행 없는 0 byte SAF stream을 거부하고 부분 파일을 정리한다`() = runTest {
        val service = service(
            object : InputStream() {
                override fun read(): Int = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
            },
        )

        val error = assertFailsWith<OnDeviceInputException> {
            service.acquire(safRequest())
        }

        assertEquals(OnDeviceInputErrorCode.SOURCE_READ_FAILED, error.code)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `SAF 취소는 CancellationException을 보존하고 token을 정리한다`() = runTest {
        val service = service(
            object : InputStream() {
                override fun read(): Int = throw CancellationException("cancelled")
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                    throw CancellationException("cancelled")
            },
        )

        assertFailsWith<CancellationException> {
            service.acquire(safRequest())
        }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `IO 성공 뒤 caller resume 전 취소하면 전달되지 않은 token을 정리한다`() = runBlocking {
        val callerExecutor = Executors.newSingleThreadExecutor()
        val ioExecutor = Executors.newSingleThreadExecutor()
        val caller = callerExecutor.asCoroutineDispatcher()
        val io = ioExecutor.asCoroutineDispatcher()
        val ioEntered = CountDownLatch(1)
        val allowIo = CountDownLatch(1)
        val blockerEntered = CountDownLatch(1)
        val releaseCaller = CountDownLatch(1)
        val token = "01234567-89ab-cdef-0123-456789abcdef"
        try {
            val zip = extensionZip()
            val service = OnDeviceInputService(
                privateRoot = root,
                safInputOpener = SafInputOpener {
                    ioEntered.countDown()
                    check(allowIo.await(5, TimeUnit.SECONDS))
                    ByteArrayInputStream(zip)
                },
                tokenFactory = { token },
                ioDispatcher = io,
            )
            val scope = CoroutineScope(SupervisorJob() + caller)
            val deferred = scope.async {
                service.acquire(safRequest())
            }
            assertTrue(ioEntered.await(5, TimeUnit.SECONDS))
            callerExecutor.execute {
                blockerEntered.countDown()
                releaseCaller.await(5, TimeUnit.SECONDS)
            }
            assertTrue(blockerEntered.await(5, TimeUnit.SECONDS))
            allowIo.countDown()
            ioExecutor.submit {}.get(5, TimeUnit.SECONDS)

            deferred.cancel()
            releaseCaller.countDown()
            deferred.cancelAndJoin()

            assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {
            releaseCaller.countDown()
            caller.close()
            io.close()
        }
    }

    @Test
    fun `128 MiB를 초과하는 SAF stream을 bounded copy로 거부한다`() = runTest {
        val service = service(GeneratedInputStream(134_217_729L))

        val error = assertFailsWith<OnDeviceInputException> {
            service.acquire(safRequest())
        }

        assertEquals(OnDeviceInputErrorCode.INPUT_TOO_LARGE, error.code)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `정확히 128 MiB SAF stream은 크기 제한을 통과한다`() = runTest {
        val service = service(GeneratedInputStream(134_217_728L))

        val error = assertFailsWith<OnDeviceInputException> {
            service.acquire(safRequest())
        }

        assertEquals(OnDeviceInputErrorCode.UNSUPPORTED_FORMAT, error.code)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `SAF request 문자열도 URI를 노출하지 않는다`() {
        val request = safRequest()

        assertEquals("Saf(uri=<redacted>)", request.toString())
    }

    @Test
    fun `Chrome 상세 주소는 검증된 CRX3 private artifact만 남긴다`() = runTest {
        val crx = validCrx()
        val pageUrl =
            "https://chromewebstore.google.com/detail/customer-secret/${crx.extensionId}"
        val downloader = AndroidChromeCrxDownloader(
            ChromeDownloadTransport {
                ChromeDownloadResponse(
                    statusCode = 200,
                    contentType = "application/x-chrome-extension",
                    location = null,
                    body = ByteArrayInputStream(crx.bytes),
                )
            },
        )
        val service = OnDeviceInputService(
            privateRoot = root,
            safInputOpener = SafInputOpener { null },
            chromeDownloader = downloader,
        )

        val artifact = service.acquire(OnDeviceInputRequest.ChromeStore(pageUrl))

        assertEquals(OnDeviceInputKind.CRX3, artifact.kind)
        assertEquals(crx.extensionId, artifact.expectedExtensionId)
        assertEquals(sha256(crx.bytes), artifact.sha256)
        assertFalse(artifact.toString().contains(pageUrl))
        assertEquals("ChromeStore(pageUrl=<redacted>)", OnDeviceInputRequest.ChromeStore(pageUrl).toString())
    }

    @Test
    fun `Chrome CRX ID 불일치 실패도 URL 없이 token 전체를 정리한다`() = runTest {
        val crx = validCrx()
        val requestedId = if (crx.extensionId.first() == 'a') {
            "b" + crx.extensionId.drop(1)
        } else {
            "a" + crx.extensionId.drop(1)
        }
        val pageUrl =
            "https://chromewebstore.google.com/detail/customer-secret/$requestedId"
        val service = OnDeviceInputService(
            privateRoot = root,
            safInputOpener = SafInputOpener { null },
            chromeDownloader = AndroidChromeCrxDownloader(
                ChromeDownloadTransport {
                    ChromeDownloadResponse(
                        statusCode = 200,
                        contentType = "application/x-chrome-extension",
                        location = null,
                        body = ByteArrayInputStream(crx.bytes),
                    )
                },
            ),
        )

        val error = assertFailsWith<OnDeviceInputException> {
            service.acquire(OnDeviceInputRequest.ChromeStore(pageUrl))
        }

        assertEquals(OnDeviceInputErrorCode.INPUT_INVALID, error.code)
        assertEquals(
            ChromeDownloadErrorCode.EXTENSION_ID_MISMATCH,
            error.chromeDownloadErrorCode,
        )
        assertFalse(error.toString().contains(pageUrl))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `모든 structured Chrome code를 URL NETWORK INPUT_INVALID로 결정 매핑한다`() = runTest {
        val mappings = mapOf(
            ChromeDownloadErrorCode.NETWORK_FAILED to OnDeviceInputErrorCode.NETWORK,
            ChromeDownloadErrorCode.HTTP_FAILED to OnDeviceInputErrorCode.NETWORK,
            ChromeDownloadErrorCode.REDIRECT_REJECTED to OnDeviceInputErrorCode.NETWORK,
            ChromeDownloadErrorCode.TOO_MANY_REDIRECTS to OnDeviceInputErrorCode.NETWORK,
            ChromeDownloadErrorCode.HTML_RESPONSE to OnDeviceInputErrorCode.INPUT_INVALID,
            ChromeDownloadErrorCode.RESPONSE_TOO_LARGE to OnDeviceInputErrorCode.INPUT_INVALID,
            ChromeDownloadErrorCode.INVALID_CRX3 to OnDeviceInputErrorCode.INPUT_INVALID,
            ChromeDownloadErrorCode.EXTENSION_ID_MISMATCH to OnDeviceInputErrorCode.INPUT_INVALID,
            ChromeDownloadErrorCode.CLEANUP_FAILED to
                OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
        )
        val pageUrl =
            "https://chromewebstore.google.com/detail/name/${"a".repeat(32)}"

        mappings.forEach { (downloadCode, expectedCode) ->
            val service = OnDeviceInputService(
                privateRoot = root,
                safInputOpener = SafInputOpener { null },
                chromeDownloader = ChromeCrxDownloader { _, _ ->
                    throw ChromeDownloadException(downloadCode, "safe")
                },
            )

            val error = assertFailsWith<OnDeviceInputException> {
                service.acquire(OnDeviceInputRequest.ChromeStore(pageUrl))
            }

            assertEquals(expectedCode, error.code, downloadCode.name)
            assertEquals(downloadCode, error.chromeDownloadErrorCode)
            assertNull(error.cause)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }

    @Test
    fun `잘못된 Chrome 상세 주소는 URL code로 분류하고 원문을 숨긴다`() = runTest {
        val secret = "https://evil.example/customer-secret"
        val service = OnDeviceInputService(
            privateRoot = root,
            safInputOpener = SafInputOpener { null },
            chromeDownloader = ChromeCrxDownloader { _, _ ->
                error("호출되면 안 됩니다.")
            },
        )

        val error = assertFailsWith<OnDeviceInputException> {
            service.acquire(OnDeviceInputRequest.ChromeStore(secret))
        }

        assertEquals(OnDeviceInputErrorCode.URL, error.code)
        assertNull(error.chromeDownloadErrorCode)
        assertFalse(error.stackTraceToString().contains(secret))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun service(input: InputStream): OnDeviceInputService =
        OnDeviceInputService(
            privateRoot = root,
            safInputOpener = SafInputOpener { input },
        )

    private fun safRequest(): OnDeviceInputRequest.Saf {
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(OnDeviceInputRequest.Saf::class.java)
            as OnDeviceInputRequest.Saf
    }

    private fun extensionZip(): ByteArray {
        val source = Files.createTempDirectory("on-device-extension")
        return try {
            source.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Input","version":"1"}""",
            )
            ExtensionZip.createBytes(source)
        } finally {
            source.toFile().deleteRecursively()
        }
    }

    private fun validCrx() = Files.createTempDirectory("on-device-crx-extension").let { source ->
        try {
            source.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"CRX","version":"1"}""",
            )
            Crx3.pack(
                source,
                KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                    .generateKeyPair(),
            )
        } finally {
            source.toFile().deleteRecursively()
        }
    }

    private fun validDraftV2(): ByteArray {
        val source = extensionZip()
        val sourceHash = sha256(source)
        val profileHash = "2".repeat(64)
        val report = CompatibilityReport(
            status = CompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = "Draft",
            extensionVersion = "1",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            conversionState = ConversionState.PASSTHROUGH,
            sourceSha256 = sourceHash,
            browserProfileSha256 = profileHash,
        )
        val identity = "on-device-input-test"
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.createV2(
                coreVersion = "test",
                adapterVersions = emptyMap(),
                sourceIdentity = identity,
            ),
            input = DraftInputMetadata(
                inputKind = ExtensionInputKind.ZIP,
                originalSourceSha256 = sourceHash,
                browserProfileSha256 = profileHash,
                originalExtensionId = null,
                originalExtensionVersion = "1",
                sourceIdentity = identity,
            ),
            sourceKind = DraftSourceKind.ZIP,
            source = source,
            plan = DraftConversionPlan.create(report, emptyList()),
            compatibilityBefore = report,
            compatibilityAfter = report,
        )
        val signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        return SixDraft.createBytes(payload, signingKey)
    }

    private fun unsignedDraftWithManifest(draft: ByteArray): ByteArray {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(draft)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name != "draft-signature.json") {
                    entries[entry.name] = zip.readBytes()
                }
            }
        }
        entries["manifest.json"] =
            """{"manifest_version":3,"name":"Unsigned Draft","version":"1"}"""
                .encodeToByteArray()
        return ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.toSortedMap().forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

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

    private fun ByteArray.indexOf(needle: ByteArray): Int =
        indices.firstOrNull { start ->
            start <= size - needle.size &&
                needle.indices.all { offset -> this[start + offset] == needle[offset] }
        } ?: -1
}
