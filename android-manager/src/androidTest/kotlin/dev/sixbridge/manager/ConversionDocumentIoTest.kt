package dev.sixbridge.manager

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.manager.app.ManagerRepository
import dev.sixbridge.manager.conversion.GeneratedBundleStore
import dev.sixbridge.manager.conversion.GeneratedBundleStoreHooks
import dev.sixbridge.manager.conversion.GeneratedWrapperVerifier
import dev.sixbridge.manager.packager.OnDeviceWrapperResult
import dev.sixbridge.manager.ui.ManagerEffect
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConversionDocumentIoTest {
    private val resolver: ContentResolver =
        ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    @Test
    fun generated_save_copy_gate_does_not_block_main_progress() {
        val copyStarted = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val saveResult = CompletableDeferred<GeneratedDocumentSaveResult>()
        val mainMarker = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            saveResult.complete(
                writeGeneratedDocument(
                    resolver = resolver,
                    uri = uri("main-progress"),
                    expectedHash = "matching-hash",
                    copy = { output ->
                        copyStarted.countDown()
                        check(releaseCopy.await(5, TimeUnit.SECONDS))
                        output.write("six".encodeToByteArray())
                        "matching-hash"
                    },
                ),
            )
        }

        try {
            assertTrue(copyStarted.await(5, TimeUnit.SECONDS))
            scope.launch { mainMarker.complete(Unit) }
            runBlocking {
                withTimeout(1_000) {
                    mainMarker.await()
                }
            }
        } finally {
            releaseCopy.countDown()
            runBlocking {
                withTimeout(5_000) {
                    saveResult.await()
                }
            }
            scope.cancel()
        }

        assertEquals(
            GeneratedDocumentSaveResult.Success,
            runBlocking { saveResult.await() },
        )
    }

    @Test
    fun generated_save_cancellation_stops_before_next_chunk_and_deletes_destination() {
        val name = "cancel-delete-success"
        val firstChunkWritten = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val saveResult = CompletableDeferred<Any>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val saveJob = scope.launch {
            try {
                saveResult.complete(
                    writeGeneratedDocument(
                        resolver = resolver,
                        uri = uri(name),
                        expectedHash = "matching-hash",
                        copy = { output ->
                            output.write("first".encodeToByteArray())
                            firstChunkWritten.countDown()
                            check(releaseCopy.await(5, TimeUnit.SECONDS))
                            output.write("second".encodeToByteArray())
                            "matching-hash"
                        },
                    ),
                )
            } catch (error: Throwable) {
                saveResult.complete(error)
            }
        }

        assertTrue(firstChunkWritten.await(5, TimeUnit.SECONDS))
        saveJob.cancel()
        releaseCopy.countDown()
        val result = runBlocking {
            withTimeout(5_000) {
                saveResult.await()
            }
        }
        scope.cancel()

        assertEquals(
            GeneratedDocumentSaveResult.Cancelled(cleanupFailed = false),
            result,
        )
        assertFalse(exists(name))
    }

    @Test
    fun generated_save_cancellation_reports_delete_failure_after_cleanup_attempt() {
        val name = "cancel-delete-fail"
        val firstChunkWritten = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val saveResult = CompletableDeferred<Any>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val saveJob = scope.launch {
            try {
                saveResult.complete(
                    writeGeneratedDocument(
                        resolver = resolver,
                        uri = uri(name),
                        expectedHash = "matching-hash",
                        copy = { output ->
                            output.write("first".encodeToByteArray())
                            firstChunkWritten.countDown()
                            check(releaseCopy.await(5, TimeUnit.SECONDS))
                            output.write("second".encodeToByteArray())
                            "matching-hash"
                        },
                    ),
                )
            } catch (error: Throwable) {
                saveResult.complete(error)
            }
        }

        assertTrue(firstChunkWritten.await(5, TimeUnit.SECONDS))
        saveJob.cancel()
        releaseCopy.countDown()
        val result = runBlocking {
            withTimeout(5_000) {
                saveResult.await()
            }
        }
        scope.cancel()

        assertEquals(
            GeneratedDocumentSaveResult.Cancelled(cleanupFailed = true),
            result,
        )
        assertTrue(exists(name))
    }

    @Test
    fun actual_store_repository_helper_cancellation_returns_cancelled_and_deletes_destination() {
        val name = "actual-store-cancel"
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val filesDir = File(context.cacheDir, "actual-store-${UUID.randomUUID()}").apply {
            check(mkdir())
        }
        val firstChunkCopied = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        val store = GeneratedBundleStore(
            filesDir = filesDir,
            wrapperVerifier = GeneratedWrapperVerifier { _, _, _ -> true },
            hooks = GeneratedBundleStoreHooks(
                afterCopyChunk = {
                    firstChunkCopied.countDown()
                    check(releaseCopy.await(5, TimeUnit.SECONDS))
                },
            ),
        )
        val generated = store.publish(
            ACTUAL_STORE_TOKEN,
            generatedResult(filesDir),
        )
        val repository = Proxy.newProxyInstance(
            ManagerRepository::class.java.classLoader,
            arrayOf(ManagerRepository::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "copyGeneratedConversion" -> store.copyTo(
                    arguments[0] as String,
                    arguments[1] as OutputStream,
                )
                else -> error("계측 copy 경계에서 ${method.name}은 사용하지 않습니다.")
            }
        } as ManagerRepository
        val result = CompletableDeferred<Any>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val saveJob = scope.launch {
            try {
                result.complete(
                    writeGeneratedDocument(
                        resolver = resolver,
                        uri = uri(name),
                        expectedHash = generated.sha256,
                        copy = { output ->
                            repository.copyGeneratedConversion(ACTUAL_STORE_TOKEN, output)
                        },
                    ),
                )
            } catch (error: Throwable) {
                result.complete(error)
            }
        }

        try {
            assertTrue(firstChunkCopied.await(5, TimeUnit.SECONDS))
            saveJob.cancel()
        } finally {
            releaseCopy.countDown()
        }
        val completed = runBlocking {
            withTimeout(5_000) {
                result.await()
            }
        }
        scope.cancel()
        filesDir.deleteRecursively()

        assertEquals(
            GeneratedDocumentSaveResult.Cancelled(cleanupFailed = false),
            completed,
        )
        assertFalse(exists(name))
    }

    @Test
    fun existing_longer_document_is_truncated_and_matches_generated_bytes() = runBlocking {
        val name = "truncate"
        val expected = "new-six".encodeToByteArray()
        seed(name, "old-six-with-tail".encodeToByteArray())

        val result = writeGeneratedDocument(
            resolver = resolver,
            uri = uri(name),
            expectedHash = "matching-hash",
            copy = { output ->
                output.write(expected)
                "matching-hash"
            },
        )

        assertEquals(GeneratedDocumentSaveResult.Success, result)
        assertArrayEquals(expected, read(name))
    }

    @Test
    fun system_install_apk_fallback_copies_exact_bytes_and_verifies_sha256() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "smart-context-menu-wrapper.apk").apply {
            writeBytes(ByteArray(256 * 1_024) { index -> (index % 251).toByte() })
        }
        val expected = sha256(source.readBytes())

        try {
            val result = writeGeneratedDocument(
                resolver = resolver,
                uri = uri("system-install-apk"),
                expectedHash = expected,
                copy = { output -> copyFileAndSha256(source, output) },
            )

            assertEquals(GeneratedDocumentSaveResult.Success, result)
            assertArrayEquals(source.readBytes(), read("system-install-apk"))
        } finally {
            source.delete()
        }
    }

    @Test
    fun system_install_apk_fallback_hash_mismatch_deletes_destination() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = File(context.cacheDir, "system-install-mismatch.apk").apply {
            writeBytes("verified-wrapper".encodeToByteArray())
        }

        try {
            val result = writeGeneratedDocument(
                resolver = resolver,
                uri = uri("system-install-mismatch"),
                expectedHash = "0".repeat(64),
                copy = { output -> copyFileAndSha256(source, output) },
            )

            assertEquals(
                GeneratedDocumentSaveResult.Failure(cleanupFailed = false),
                result,
            )
            assertFalse(exists("system-install-mismatch"))
        } finally {
            source.delete()
        }
    }

    @Test
    fun system_install_copy_handoff_survives_recreation_until_exact_completion_ack() =
        runBlocking {
            val effect = ManagerEffect.CreateSystemInstallApkDocument(
                importToken = TOKEN,
                source = File("/retained/$TOKEN/wrapper.apk"),
                expectedSha256 = "a".repeat(64),
                suggestedFileName = "smart-context-wrapper.apk",
            )
            val destination = uri("system-install-recreated")
            val source = SystemInstallApkDocumentHandoff().apply {
                begin(effect)
                selectDestination(destination)
            }
            val saved = Bundle().also(source::save)
            val recreated = SystemInstallApkDocumentHandoff.restore(saved)

            val completion = recreated.prepareCompletion {
                GeneratedDocumentSaveResult.Success
            }
            assertNotNull(completion)
            val exactCompletion = requireNotNull(completion)

            assertEquals(effect, recreated.pending?.effect)
            assertEquals(destination, recreated.pending?.destination)
            assertEquals(TOKEN, exactCompletion.action.importToken)
            assertEquals(destination, exactCompletion.action.uri)
            assertTrue(recreated.acknowledge(exactCompletion))
            assertNull(recreated.pending)
        }

    @Test
    fun system_install_copy_cancellation_preserves_exact_resume_and_stale_completion_is_rejected() =
        runBlocking {
            val first = ManagerEffect.CreateSystemInstallApkDocument(
                importToken = TOKEN,
                source = File("/retained/$TOKEN/wrapper.apk"),
                expectedSha256 = "a".repeat(64),
                suggestedFileName = "first-wrapper.apk",
            )
            val second = first.copy(
                importToken = SECOND_TOKEN,
                source = File("/retained/$SECOND_TOKEN/wrapper.apk"),
                expectedSha256 = "b".repeat(64),
                suggestedFileName = "second-wrapper.apk",
            )
            val handoff = SystemInstallApkDocumentHandoff().apply {
                begin(first)
                selectDestination(uri("system-install-cancelled"))
            }

            try {
                handoff.prepareCompletion { throw CancellationException("rotation") }
                fail("copy cancellation이 전달되어야 합니다.")
            } catch (_: CancellationException) {
                // Expected: the exact handoff remains pending for recreation.
            }
            val saved = Bundle().also(handoff::save)
            assertEquals(first, SystemInstallApkDocumentHandoff.restore(saved).pending?.effect)

            val stale = handoff.prepareCompletion {
                GeneratedDocumentSaveResult.Failure(cleanupFailed = false)
            }
            assertNotNull(stale)
            handoff.begin(second)

            assertFalse(handoff.acknowledge(requireNotNull(stale)))
            assertEquals(second, handoff.pending?.effect)
        }

    @Test
    fun null_descriptor_reports_cleanup_failure() = runBlocking {
        val result = writeGeneratedDocument(
            resolver = resolver,
            uri = uri("null"),
            expectedHash = "matching-hash",
            copy = { "matching-hash" },
        )

        assertEquals(GeneratedDocumentSaveResult.Failure(cleanupFailed = true), result)
    }

    @Test
    fun mid_write_failure_deletes_partial_document() = runBlocking {
        val name = "mid-write"
        val result = writeGeneratedDocument(
            resolver = resolver,
            uri = uri(name),
            expectedHash = "matching-hash",
            copy = { output ->
                repeat(1_024) {
                    output.write(ByteArray(8_192))
                }
                "matching-hash"
            },
        )

        assertEquals(GeneratedDocumentSaveResult.Failure(cleanupFailed = false), result)
        assertFalse(exists(name))
    }

    @Test
    fun provider_close_error_is_reported_as_save_failure() = runBlocking {
        val result = writeGeneratedDocument(
            resolver = resolver,
            uri = uri("close-error"),
            expectedHash = "matching-hash",
            copy = { output ->
                output.write("six".encodeToByteArray())
                Thread.sleep(50)
                "matching-hash"
            },
        )

        assertEquals(GeneratedDocumentSaveResult.Failure(cleanupFailed = false), result)
    }

    @Test
    fun hash_mismatch_deletes_written_document() = runBlocking {
        val name = "hash-mismatch"
        val result = writeGeneratedDocument(
            resolver = resolver,
            uri = uri(name),
            expectedHash = "expected-hash",
            copy = { output ->
                output.write("partial".encodeToByteArray())
                "other-hash"
            },
        )

        assertEquals(GeneratedDocumentSaveResult.Failure(cleanupFailed = false), result)
        assertFalse(exists(name))
    }

    @Test
    fun copy_failure_and_delete_failure_are_distinguished() = runBlocking {
        val name = "delete-fail"
        seed(name, "old".encodeToByteArray())

        val result = writeGeneratedDocument(
            resolver = resolver,
            uri = uri(name),
            expectedHash = "expected-hash",
            copy = { output ->
                output.write("partial".encodeToByteArray())
                throw IOException("copy failed")
            },
        )

        assertEquals(GeneratedDocumentSaveResult.Failure(cleanupFailed = true), result)
        assertTrue(exists(name))
    }

    private fun uri(name: String): Uri =
        TestConversionDocumentProvider.uri(name)

    private fun seed(name: String, bytes: ByteArray) {
        resolver.call(
            uri(name),
            "seed",
            name,
            Bundle().apply { putByteArray("bytes", bytes) },
        )
    }

    private fun read(name: String): ByteArray =
        requireNotNull(resolver.call(uri(name), "read", name, null)?.getByteArray("bytes"))

    private fun exists(name: String): Boolean =
        resolver.call(uri(name), "exists", name, null)?.getBoolean("exists") == true

    private fun generatedResult(filesDir: File): OnDeviceWrapperResult {
        val wrapper = File(filesDir, "wrapper.apk").apply {
            writeBytes(ByteArray(192 * 1_024) { index -> (index % 251).toByte() })
        }
        val wrapperSha256 = sha256(wrapper.readBytes())
        val descriptor = ContractExtensionDescriptor(
            schemaVersion = 1,
            extensionId = "abcdefghijklmnopabcdefghijklmnop",
            name = "Cancellation",
            version = "1.0",
            sourceKind = "ZIP",
            crxSha256 = "a".repeat(64),
            wrapperSha256 = wrapperSha256,
            wrapperPackage = "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop",
            wrapperVersionCode = 1,
            signerSha256 = "b".repeat(64),
        )
        return OnDeviceWrapperResult(
            extensionId = descriptor.extensionId,
            packageName = descriptor.wrapperPackage,
            versionCode = descriptor.wrapperVersionCode,
            signerSha256 = descriptor.signerSha256,
            crxSha256 = descriptor.crxSha256,
            wrapperSha256 = wrapperSha256,
            outputApk = wrapper,
            descriptor = descriptor,
            compatibility = ContractCompatibilityReport(
                schemaVersion = 4,
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
                conversionState = "passthrough",
                adapterDecisions = emptyList(),
                originalExtensionId = descriptor.extensionId,
                convertedExtensionId = descriptor.extensionId,
                sourceSha256 = "c".repeat(64),
                browserProfileSha256 = "d".repeat(64),
                originalSourceSha256 = "e".repeat(64),
                planSha256 = "f".repeat(64),
            ),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val ACTUAL_STORE_TOKEN = "11111111-1111-4111-8111-111111111111"
        const val TOKEN = "11111111-1111-1111-1111-111111111111"
        const val SECOND_TOKEN = "22222222-2222-2222-2222-222222222222"
    }
}
