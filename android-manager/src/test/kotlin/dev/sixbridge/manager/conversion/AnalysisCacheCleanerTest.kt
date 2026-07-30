package dev.sixbridge.manager.conversion

import java.io.IOException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AnalysisCacheCleanerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `exact analysis prefix 디렉터리만 정리한다`() = runTest {
        val cache = temporaryFolder.newFolder("cache").toPath()
        Files.createDirectories(cache.resolve("sixbridge-analysis-dead/canonical"))
        Files.write(
            cache.resolve("sixbridge-analysis-dead/canonical/a.js"),
            byteArrayOf(1),
        )
        Files.createDirectories(cache.resolve("sixbridge-conversion-keep"))
        Files.write(cache.resolve("sixbridge-analysis-file"), "keep".encodeToByteArray())
        Files.createDirectories(cache.resolve("prefix-sixbridge-analysis-keep"))
        Files.createDirectories(cache.resolve("sixbridge-analysis"))

        val result = AnalysisCacheCleaner(cache).clean()

        assertEquals(AnalysisCacheCleanupResult(deleted = 1, failed = 0), result)
        assertFalse(Files.exists(cache.resolve("sixbridge-analysis-dead")))
        assertTrue(Files.exists(cache.resolve("sixbridge-conversion-keep")))
        assertTrue(Files.exists(cache.resolve("sixbridge-analysis-file")))
        assertTrue(Files.exists(cache.resolve("prefix-sixbridge-analysis-keep")))
        assertTrue(Files.exists(cache.resolve("sixbridge-analysis")))
    }

    @Test
    fun `analysis symlink는 따라가지 않고 cache 밖 대상을 보존한다`() = runTest {
        val cache = temporaryFolder.newFolder("symlink-cache").toPath()
        val outside = temporaryFolder.newFolder("outside").toPath()
        Files.write(outside.resolve("proof.txt"), "keep".encodeToByteArray())
        val directLink = cache.resolve("sixbridge-analysis-direct-link")
        val nestedTarget = cache.resolve("sixbridge-analysis-with-link")
        Files.createDirectories(nestedTarget)
        try {
            Files.createSymbolicLink(directLink, outside)
            Files.createSymbolicLink(nestedTarget.resolve("outside-link"), outside)
        } catch (error: UnsupportedOperationException) {
            assumeNoException(error)
            return@runTest
        } catch (error: IOException) {
            assumeNoException(error)
            return@runTest
        } catch (error: SecurityException) {
            assumeNoException(error)
            return@runTest
        }

        val result = AnalysisCacheCleaner(cache).clean()

        assertEquals(AnalysisCacheCleanupResult(deleted = 1, failed = 0), result)
        assertTrue(Files.isSymbolicLink(directLink))
        assertFalse(Files.exists(nestedTarget))
        assertEquals(
            "keep",
            Files.readAllBytes(outside.resolve("proof.txt")).decodeToString(),
        )
    }

    @Test
    fun `직계 자식 검사는 64개로 제한한다`() = runTest {
        val cache = temporaryFolder.newFolder("bounded-cache").toPath()
        repeat(65) { index ->
            Files.createDirectories(
                cache.resolve("sixbridge-analysis-${index.toString().padStart(2, '0')}"),
            )
        }

        val result = AnalysisCacheCleaner(cache).clean()

        assertEquals(AnalysisCacheCleanupResult(deleted = 64, failed = 0), result)
        Files.newDirectoryStream(cache).use { remaining ->
            assertEquals(1, remaining.count())
        }
    }

    @Test
    fun `분석 prefix가 아닌 다른 private cache는 보존한다`() = runTest {
        val cache = temporaryFolder.newFolder("other-cache").toPath()
        listOf(
            "imports",
            "wrapper-verifier",
            "extension-icons",
            "recovery-bitwarden-20260730",
            "sixbridge-conversion-live",
        ).forEach { Files.createDirectories(cache.resolve(it)) }

        val result = AnalysisCacheCleaner(cache).clean()

        assertEquals(AnalysisCacheCleanupResult(deleted = 0, failed = 0), result)
        listOf(
            "imports",
            "wrapper-verifier",
            "extension-icons",
            "recovery-bitwarden-20260730",
            "sixbridge-conversion-live",
        ).forEach { assertTrue(Files.isDirectory(cache.resolve(it))) }
    }
}
