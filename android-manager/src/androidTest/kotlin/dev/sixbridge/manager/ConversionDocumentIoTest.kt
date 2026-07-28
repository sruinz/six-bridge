package dev.sixbridge.manager

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
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
}
