package dev.sixbridge.manager.importer

import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractBundleContents
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.ArchiveActivity
import dev.sixbridge.manager.wrapper.ArchiveFacts
import dev.sixbridge.manager.wrapper.ArchiveInspector
import dev.sixbridge.manager.wrapper.ArchiveIntentFilter
import dev.sixbridge.manager.wrapper.ArchiveProvider
import dev.sixbridge.manager.wrapper.createTestCrx
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BundleImportServiceTest {
    @Test
    fun `returns SOURCE_UNREADABLE and removes the token directory when the URI cannot be read`() =
        runBlocking {
            val fixture = fixture()
            val cache = Files.createTempDirectory("six-import-unreadable").toFile()
            val source = BundleSource { throw IOException("private source detail") }
            val service = service(source, fixture.facts, cache)

            val failure = assertIs<ImportResult.Failure>(service.importRequest(Any()))

            assertEquals(ImportFailureCode.SOURCE_UNREADABLE, failure.reason.code)
            assertImportsEmpty(cache)
        }

    @Test
    fun `returns SOURCE_TOO_LARGE without retaining a partial source`() = runBlocking {
        val fixture = fixture()
        val cache = Files.createTempDirectory("six-import-large").toFile()
        val source = BundleSource { ByteArrayInputStream(ByteArray(9)) }
        val service = service(source, fixture.facts, cache, maximumSourceBytes = 8)

        val failure = assertIs<ImportResult.Failure>(service.importRequest("opaque request"))

        assertEquals(ImportFailureCode.SOURCE_TOO_LARGE, failure.reason.code)
        assertImportsEmpty(cache)
    }

    @Test
    fun `returns INVALID_BUNDLE for an unsupported descriptor schema`() = runBlocking {
        val fixture = fixture()
        val archive = rawBundle(
            fixture.wrapperBytes,
            fixture.descriptor.copy(schemaVersion = 2),
            fixture.report,
        )
        val cache = Files.createTempDirectory("six-import-schema").toFile()
        val service = service(
            BundleSource { ByteArrayInputStream(archive) },
            fixture.facts,
            cache,
        )

        val failure = assertIs<ImportResult.Failure>(service.importRequest(42))

        assertEquals(ImportFailureCode.INVALID_BUNDLE, failure.reason.code)
        assertImportsEmpty(cache)
    }

    @Test
    fun `returns WRAPPER_HASH_MISMATCH when bundle bytes contradict the descriptor`() = runBlocking {
        val fixture = fixture()
        val wrong = fixture.descriptor.copy(wrapperSha256 = "0".repeat(64))
        val archive = rawBundle(fixture.wrapperBytes, wrong, fixture.report)
        val cache = Files.createTempDirectory("six-import-wrapper-hash").toFile()
        val service = service(
            BundleSource { ByteArrayInputStream(archive) },
            fixture.facts,
            cache,
        )

        val failure = assertIs<ImportResult.Failure>(service.importRequest(Unit))

        assertEquals(ImportFailureCode.WRAPPER_HASH_MISMATCH, failure.reason.code)
        assertImportsEmpty(cache)
    }

    @Test
    fun `keeps only wrapper APK after a successful import and discard removes it`() = runBlocking {
        val fixture = fixture()
        val archive = validBundle(fixture)
        val cache = Files.createTempDirectory("six-import-success").toFile()
        var inspectedName = ""
        val service = service(
            BundleSource { ByteArrayInputStream(archive) },
            fixture.facts,
            cache,
            inspector = ArchiveInspector { apk ->
                inspectedName = apk.name
                fixture.facts
            },
        )

        val success = assertIs<ImportResult.Success>(service.importRequest("opaque"))
        val tokenDirectory = requireNotNull(success.value.wrapperFile.parentFile)

        assertEquals(fixture.descriptor, success.value.descriptor)
        assertEquals(fixture.descriptor.wrapperSha256, success.value.wrapperSha256)
        assertEquals(fixture.descriptor.wrapperSha256, success.value.verifiedApk.wrapperSha256)
        assertEquals("wrapper.candidate.apk", inspectedName)
        assertTrue(success.value.wrapperFile.isFile)
        assertFalse(tokenDirectory.resolve("source.six").exists())
        assertEquals(setOf("wrapper.apk"), tokenDirectory.list()?.toSet())
        service.discard(success.value.token)
        assertFalse(tokenDirectory.exists())
    }

    @Test
    fun `opens and verifies the bundle on the configured IO dispatcher`() = runBlocking {
        val fixture = fixture()
        val archive = validBundle(fixture)
        val cache = Files.createTempDirectory("six-import-dispatcher").toFile()
        var sourceThread = ""
        Executors.newSingleThreadExecutor { task -> Thread(task, "six-import-io-test") }
            .asCoroutineDispatcher()
            .use { dispatcher ->
                val service = service(
                    source = BundleSource {
                        sourceThread = Thread.currentThread().name
                        ByteArrayInputStream(archive)
                    },
                    facts = fixture.facts,
                    cache = cache,
                    ioDispatcher = dispatcher,
                )

                assertIs<ImportResult.Success>(service.importRequest("opaque"))
            }

        assertTrue(sourceThread.startsWith("six-import-io-test"))
    }

    @Test
    fun `removes source and wrapper when APK verification fails`() = runBlocking {
        val fixture = fixture()
        val archive = validBundle(fixture)
        val cache = Files.createTempDirectory("six-import-verify-failure").toFile()
        val service = service(
            source = BundleSource { ByteArrayInputStream(archive) },
            facts = fixture.facts.copy(packageName = "dev.sixbridge.ext.${"p".repeat(32)}"),
            cache = cache,
        )

        val failure = assertIs<ImportResult.Failure>(service.importRequest("opaque"))

        assertEquals(ImportFailureCode.PACKAGE_MISMATCH, failure.reason.code)
        assertImportsEmpty(cache)
    }

    @Test
    fun `rejects a final wrapper changed immediately after atomic publish`() = runBlocking {
        val fixture = fixture()
        val archive = validBundle(fixture)
        val cache = Files.createTempDirectory("six-import-publish-mutation").toFile()
        val service = service(
            source = BundleSource { ByteArrayInputStream(archive) },
            facts = fixture.facts,
            cache = cache,
            afterPublish = { it.writeBytes("mutated after publish".encodeToByteArray()) },
        )

        val failure = assertIs<ImportResult.Failure>(service.importRequest("opaque"))

        assertEquals(ImportFailureCode.WRAPPER_HASH_MISMATCH, failure.reason.code)
        assertImportsEmpty(cache)
    }

    @Test
    fun `cancellation after token creation removes the owned directory`() = runBlocking {
        val fixture = fixture()
        val cache = Files.createTempDirectory("six-import-cancel").toFile()
        val service = service(
            source = BundleSource { throw CancellationException("test cancellation") },
            facts = fixture.facts,
            cache = cache,
        )
        var cancelled = false

        try {
            service.importRequest("opaque")
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertImportsEmpty(cache)
    }

    @Test
    fun `cancelling a launched import closes a blocking source and removes the token directory`() =
        runBlocking {
            val fixture = fixture()
            val cache = Files.createTempDirectory("six-import-blocking-cancel").toFile()
            val blocking = BlockingInputStream()
            val service = service(
                source = BundleSource { blocking },
                facts = fixture.facts,
                cache = cache,
            )
            val job = launch(Dispatchers.Default) {
                service.importRequest("opaque")
            }
            assertTrue(blocking.started.await(5, TimeUnit.SECONDS))

            job.cancel(CancellationException("actual test cancellation"))

            assertTrue(blocking.closed.await(500, TimeUnit.MILLISECONDS))
            withTimeout(5_000) { job.join() }
            assertTrue(job.isCancelled)
            assertImportsEmpty(cache)
        }

    @Test
    fun `rejects an imports root symlink even when it resolves inside the cache`() = runBlocking {
        val fixture = fixture()
        val archive = validBundle(fixture)
        val cache = Files.createTempDirectory("six-import-root-link")
        Files.createSymbolicLink(cache.resolve("imports"), cache)
        val service = service(
            source = BundleSource { ByteArrayInputStream(archive) },
            facts = fixture.facts,
            cache = cache.toFile(),
        )

        val failure = assertIs<ImportResult.Failure>(service.importRequest("opaque"))

        assertEquals(ImportFailureCode.CACHE_BOUNDARY_INVALID, failure.reason.code)
        assertEquals(setOf("imports"), cache.toFile().list().orEmpty().toSet())
    }

    private fun service(
        source: BundleSource,
        facts: ArchiveFacts,
        cache: java.io.File,
        maximumSourceBytes: Long = SixBundleContract.MAX_ARCHIVE_SIZE,
        ioDispatcher: CoroutineDispatcher? = null,
        inspector: ArchiveInspector = ArchiveInspector { facts },
        afterPublish: (java.io.File) -> Unit = {},
    ): BundleImportService = BundleImportService(
        source = source,
        verifier = AndroidWrapperVerifier(inspector),
        cacheDirectory = cache,
        maximumSourceBytes = maximumSourceBytes,
        ioDispatcher = ioDispatcher,
        afterPublish = afterPublish,
    )

    private fun assertImportsEmpty(cache: java.io.File) {
        val imports = cache.resolve("imports")
        assertTrue(!imports.exists() || imports.list().orEmpty().isEmpty())
    }

    private fun validBundle(fixture: ImportFixture): ByteArray = ByteArrayOutputStream().also { output ->
        SixBundleContract.write(
            output,
            ContractBundleContents(fixture.wrapperBytes, fixture.descriptor, fixture.report),
        )
    }.toByteArray()

    private fun rawBundle(
        wrapper: ByteArray,
        descriptor: ContractExtensionDescriptor,
        report: ContractCompatibilityReport,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            fun add(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            add(SixBundleContract.WRAPPER_ENTRY, wrapper)
            add(
                SixBundleContract.EXTENSION_ENTRY,
                BundleJson.encodeExtension(descriptor).encodeToByteArray(),
            )
            add(
                SixBundleContract.COMPATIBILITY_ENTRY,
                BundleJson.encodeCompatibility(report).encodeToByteArray(),
            )
        }
    }.toByteArray()

    private fun fixture(): ImportFixture {
        val crxBytes = createTestCrx().bytes
        val crx = Crx3Verifier.verify(crxBytes)
        val wrapper = "test wrapper bytes".encodeToByteArray()
        val packageName = "dev.sixbridge.ext.${crx.extensionId}"
        val signer = "1".repeat(64)
        val descriptor = ContractExtensionDescriptor(
            schemaVersion = 1,
            extensionId = crx.extensionId,
            name = "Fixture",
            version = "1.0",
            sourceKind = "CRX3",
            crxSha256 = crx.crxSha256,
            wrapperSha256 = sha256(wrapper),
            wrapperPackage = packageName,
            wrapperVersionCode = 7,
            signerSha256 = signer,
        )
        val report = ContractCompatibilityReport(
            schemaVersion = 3,
            status = ContractCompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = descriptor.name,
            extensionVersion = descriptor.version,
            manifestKeyPresent = false,
            manifestKeyExtensionId = null,
            declaredPermissions = emptyList(),
            usedApis = emptyList(),
            unsupportedApis = emptyList(),
            issues = emptyList(),
            conversionState = "converted",
            adapterDecisions = emptyList<ContractAdapterDecision>(),
            originalExtensionId = null,
            convertedExtensionId = descriptor.extensionId,
            sourceSha256 = "2".repeat(64),
            browserProfileSha256 = "3".repeat(64),
        )
        val filter = ArchiveIntentFilter(
            actions = setOf("android.intent.action.VIEW"),
            categories = setOf(
                "android.intent.category.DEFAULT",
                "android.intent.category.BROWSABLE",
            ),
            schemes = setOf("six"),
            hosts = setOf("ex"),
        )
        val facts = ArchiveFacts(
            packageName = packageName,
            versionCode = descriptor.wrapperVersionCode.toLong(),
            signerSha256 = signer,
            metadata = mapOf("extension-id" to descriptor.extensionId),
            activities = listOf(
                ArchiveActivity(
                    "dev.sixbridge.runtime.SixDiscoveryActivity",
                    exported = true,
                    listOf(filter),
                ),
            ),
            providers = listOf(
                ArchiveProvider(
                    name = "dev.sixbridge.runtime.ExtensionProvider",
                    authority = "$packageName.contentprovider",
                    exported = true,
                ),
            ),
            crxBytes = crxBytes,
        )
        return ImportFixture(wrapper, descriptor, report, facts)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class ImportFixture(
        val wrapperBytes: ByteArray,
        val descriptor: ContractExtensionDescriptor,
        val report: ContractCompatibilityReport,
        val facts: ArchiveFacts,
    )

    private class BlockingInputStream : InputStream() {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        private val isClosed = AtomicBoolean()
        private val release = CountDownLatch(1)

        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 0xff
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            started.countDown()
            release.await(2, TimeUnit.SECONDS)
            if (isClosed.get()) throw IOException("closed")
            return -1
        }

        override fun close() {
            if (isClosed.compareAndSet(false, true)) {
                closed.countDown()
                release.countDown()
            }
        }
    }
}
