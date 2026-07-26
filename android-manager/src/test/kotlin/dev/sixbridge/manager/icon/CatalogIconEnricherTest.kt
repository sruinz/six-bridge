package dev.sixbridge.manager.icon

import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.ManagerCatalog
import java.util.concurrent.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CatalogIconEnricherTest {
    @Test
    fun `icon 없는 record만 읽어 현재 목록과 catalog를 보강한다`() = runTest {
        val source = RecordingIconSource { PNG }
        val catalog = RecordingCatalog()
        val enricher = CatalogIconEnricher(catalog, source)

        val result = enricher.enrich(listOf(record(iconPng = null)))

        assertContentEquals(PNG, result.single().iconPng)
        assertContentEquals(PNG, catalog.replaced.single().iconPng)
        assertEquals(listOf(PACKAGE), source.packages)
    }

    @Test
    fun `기존 icon이 있으면 source를 열거나 catalog를 쓰지 않는다`() = runTest {
        val source = RecordingIconSource { PNG }
        val catalog = RecordingCatalog()
        val enricher = CatalogIconEnricher(catalog, source)

        val result = enricher.enrich(listOf(record(iconPng = EXISTING_PNG)))

        assertContentEquals(EXISTING_PNG, result.single().iconPng)
        assertEquals(emptyList(), source.packages)
        assertEquals(emptyList(), catalog.replaceAttempts)
    }

    @Test
    fun `같은 identity의 null 결과는 process에서 한 번만 조회한다`() = runTest {
        val source = RecordingIconSource { null }
        val catalog = RecordingCatalog()
        val enricher = CatalogIconEnricher(catalog, source)

        val first = enricher.enrich(listOf(record()))
        val second = enricher.enrich(listOf(record()))

        assertNull(first.single().iconPng)
        assertNull(second.single().iconPng)
        assertEquals(listOf(PACKAGE), source.packages)
        assertEquals(emptyList(), catalog.replaceAttempts)
    }

    @Test
    fun `양성 cache는 Room 저장 실패 뒤에도 source와 저장을 반복하지 않는다`() = runTest {
        val source = RecordingIconSource { PNG }
        val catalog = RecordingCatalog(
            replaceFailure = IllegalStateException("Room unavailable"),
        )
        val enricher = CatalogIconEnricher(catalog, source)

        val first = enricher.enrich(listOf(record()))
        val second = enricher.enrich(listOf(record()))

        assertContentEquals(PNG, first.single().iconPng)
        assertContentEquals(PNG, second.single().iconPng)
        assertEquals(listOf(PACKAGE), source.packages)
        assertEquals(1, catalog.replaceAttempts.size)
    }

    @Test
    fun `extensionId versionCode signer 변경은 각각 새 identity로 조회한다`() = runTest {
        val source = RecordingIconSource { byteArrayOf(packages.size.toByte()) }
        val catalog = RecordingCatalog()
        val enricher = CatalogIconEnricher(catalog, source)
        val identities = listOf(
            record(),
            record(extensionId = "bbcdefghijklmnopabcdefghijklmnop"),
            record(installedVersionCode = 2),
            record(signerSha256 = "b".repeat(64)),
            record(),
        )

        val results = identities.map { identity ->
            enricher.enrich(listOf(identity)).single()
        }

        assertEquals(listOf(1, 2, 3, 4, 1), results.map { it.iconPng!!.single().toInt() })
        assertEquals(List(4) { PACKAGE }, source.packages)
        assertEquals(4, catalog.replaceAttempts.size)
    }

    @Test
    fun `replaceVerified 일반 예외는 현재 목록의 추출 성공 icon을 막지 않는다`() = runTest {
        val catalog = RecordingCatalog(
            replaceFailure = IllegalArgumentException("write failed"),
        )
        val enricher = CatalogIconEnricher(catalog, RecordingIconSource { PNG })

        val result = enricher.enrich(listOf(record()))

        assertContentEquals(PNG, result.single().iconPng)
        assertEquals(1, catalog.replaceAttempts.size)
    }

    @Test
    fun `새 enricher process cache는 이전 저장 실패 identity를 다시 시도한다`() = runTest {
        val source = RecordingIconSource { PNG }
        val catalog = RecordingCatalog(
            replaceFailure = IllegalStateException("Room unavailable"),
        )

        CatalogIconEnricher(catalog, source).enrich(listOf(record()))
        CatalogIconEnricher(catalog, source).enrich(listOf(record()))

        assertEquals(listOf(PACKAGE, PACKAGE), source.packages)
        assertEquals(2, catalog.replaceAttempts.size)
    }

    @Test
    fun `source와 catalog cancellation은 재전파한다`() = runTest {
        val sourceCancellation = CancellationException("source cancelled")
        val sourceEnricher = CatalogIconEnricher(
            RecordingCatalog(),
            RecordingIconSource { throw sourceCancellation },
        )
        val catalogCancellation = CancellationException("catalog cancelled")
        val catalogEnricher = CatalogIconEnricher(
            RecordingCatalog(replaceFailure = catalogCancellation),
            RecordingIconSource { PNG },
        )

        assertFailsWith<CancellationException> {
            sourceEnricher.enrich(listOf(record()))
        }
        assertFailsWith<CancellationException> {
            catalogEnricher.enrich(listOf(record()))
        }
    }

    private fun record(
        extensionId: String = EXTENSION_ID,
        iconPng: ByteArray? = null,
        installedVersionCode: Long = 1,
        signerSha256: String = "a".repeat(64),
    ) = CatalogRecord(
        packageName = PACKAGE,
        extensionId = extensionId,
        name = "Example",
        iconPng = iconPng,
        installedVersionCode = installedVersionCode,
        signerSha256 = signerSha256,
        compatibilityStatus = "COMPATIBLE",
        reportJson = "{\"status\":\"COMPATIBLE\"}",
        lastVerifiedAtEpochMillis = 99,
    )

    private class RecordingIconSource(
        private val loadResult: RecordingIconSource.() -> ByteArray?,
    ) : InstalledExtensionIconSource {
        val packages = mutableListOf<String>()

        override suspend fun load(packageName: String): ByteArray? {
            packages += packageName
            return loadResult()
        }
    }

    private class RecordingCatalog(
        private val replaceFailure: Exception? = null,
    ) : ManagerCatalog {
        val replaceAttempts = mutableListOf<CatalogRecord>()
        val replaced = mutableListOf<CatalogRecord>()

        override fun observe(): Flow<List<CatalogRecord>> = flowOf(emptyList())

        override suspend fun replaceVerified(record: CatalogRecord) {
            replaceAttempts += record
            replaceFailure?.let { throw it }
            replaced += record
        }

        override suspend fun remove(packageName: String) = Unit

        override suspend fun removeAll(packageNames: Set<String>) = Unit

        override suspend fun get(packageName: String): CatalogRecord? = null
    }

    private companion object {
        const val PACKAGE =
            "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop"
        const val EXTENSION_ID = "abcdefghijklmnopabcdefghijklmnop"
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        val EXISTING_PNG = byteArrayOf(1, 2, 3, 4)
    }
}
