package dev.sixbridge.manager

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversionDocumentIoTest {
    private val resolver: ContentResolver =
        ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver

    @Test
    fun existing_longer_document_is_truncated_and_matches_generated_bytes() {
        val name = "truncate"
        val expected = "new-six".encodeToByteArray()
        seed(name, "old-six-with-tail".encodeToByteArray())

        val result = writeGeneratedDocument(
            resolver = resolver,
            uri = uri(name),
            expectedHash = "matching-hash",
        ) { output ->
            output.write(expected)
            "matching-hash"
        }

        assertEquals(GeneratedDocumentSaveResult.Success, result)
        assertArrayEquals(expected, read(name))
    }

    @Test
    fun null_descriptor_reports_cleanup_failure() {
        val result = writeGeneratedDocument(
            resolver = resolver,
            uri = uri("null"),
            expectedHash = "matching-hash",
        ) { "matching-hash" }

        assertEquals(GeneratedDocumentSaveResult.Failure(cleanupFailed = true), result)
    }

    @Test
    fun mid_write_failure_deletes_partial_document() {
        val name = "mid-write"
        val result = writeGeneratedDocument(
            resolver = resolver,
            uri = uri(name),
            expectedHash = "matching-hash",
        ) { output ->
            repeat(1_024) {
                output.write(ByteArray(8_192))
            }
            "matching-hash"
        }

        assertEquals(GeneratedDocumentSaveResult.Failure(cleanupFailed = false), result)
        assertFalse(exists(name))
    }

    @Test
    fun provider_close_error_is_reported_as_save_failure() {
        val result = writeGeneratedDocument(
            resolver = resolver,
            uri = uri("close-error"),
            expectedHash = "matching-hash",
        ) { output ->
            output.write("six".encodeToByteArray())
            Thread.sleep(50)
            "matching-hash"
        }

        assertEquals(GeneratedDocumentSaveResult.Failure(cleanupFailed = false), result)
    }

    @Test
    fun hash_mismatch_deletes_written_document() {
        val name = "hash-mismatch"
        val result = writeGeneratedDocument(
            resolver = resolver,
            uri = uri(name),
            expectedHash = "expected-hash",
        ) { output ->
            output.write("partial".encodeToByteArray())
            "other-hash"
        }

        assertEquals(GeneratedDocumentSaveResult.Failure(cleanupFailed = false), result)
        assertFalse(exists(name))
    }

    @Test
    fun copy_failure_and_delete_failure_are_distinguished() {
        val name = "delete-fail"
        seed(name, "old".encodeToByteArray())

        val result = writeGeneratedDocument(
            resolver = resolver,
            uri = uri(name),
            expectedHash = "expected-hash",
        ) { output ->
            output.write("partial".encodeToByteArray())
            throw IOException("copy failed")
        }

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
