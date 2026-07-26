package dev.sixbridge.manager.icon

import dev.sixbridge.manager.catalog.CatalogIdentity
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.catalog.identity
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
        val record = record(iconPng = null)

        val loaded = requireNotNull(enricher.load(record))
        val result = enricher.persist(record, loaded)

        assertContentEquals(
            PNG,
            (result as IconPersistenceResult.Stored).record.iconPng,
        )
        assertEquals(record().identity(), catalog.persisted.single())
        assertEquals(listOf(PACKAGE), source.packages)
    }

    @Test
    fun `기존 icon이 있으면 source를 열거나 catalog를 쓰지 않는다`() = runTest {
        val source = RecordingIconSource { PNG }
        val catalog = RecordingCatalog()
        val enricher = CatalogIconEnricher(catalog, source)

        val result = enricher.load(record(iconPng = EXISTING_PNG))

        assertContentEquals(EXISTING_PNG, result)
        assertEquals(emptyList(), source.packages)
        assertEquals(emptyList(), catalog.persistAttempts)
    }

    @Test
    fun `같은 identity의 null 결과는 process에서 한 번만 조회한다`() = runTest {
        val source = RecordingIconSource { null }
        val catalog = RecordingCatalog()
        val enricher = CatalogIconEnricher(catalog, source)

        val first = enricher.load(record())
        val second = enricher.load(record())

        assertNull(first)
        assertNull(second)
        assertEquals(listOf(PACKAGE), source.packages)
        assertEquals(emptyList(), catalog.persistAttempts)
    }

    @Test
    fun `양성 cache는 Room 저장 실패 뒤에도 source와 저장을 반복하지 않는다`() = runTest {
        val source = RecordingIconSource { PNG }
        val catalog = RecordingCatalog(
            persistFailure = IllegalStateException("Room unavailable"),
        )
        val enricher = CatalogIconEnricher(catalog, source)

        val record = record()
        val bytes = requireNotNull(enricher.load(record))

        assertEquals(IconPersistenceResult.WriteFailed, enricher.persist(record, bytes))
        assertContentEquals(PNG, enricher.mergeCached(record).iconPng)
        assertEquals(IconPersistenceResult.WriteFailed, enricher.persist(record, bytes))
        assertEquals(1, catalog.persistAttempts.size)
        assertEquals(listOf(PACKAGE), source.packages)
    }

    @Test
    fun `같은 identity 저장 성공도 process에서 다시 쓰지 않는다`() = runTest {
        val source = RecordingIconSource { PNG }
        val catalog = RecordingCatalog()
        val enricher = CatalogIconEnricher(catalog, source)
        val record = record()
        val bytes = requireNotNull(enricher.load(record))

        assertContentEquals(
            PNG,
            (enricher.persist(record, bytes) as IconPersistenceResult.Stored).record.iconPng,
        )
        assertContentEquals(
            PNG,
            (enricher.persist(record, bytes) as IconPersistenceResult.Stored).record.iconPng,
        )
        assertEquals(1, catalog.persistAttempts.size)
        assertEquals(listOf(PACKAGE), source.packages)
    }

    @Test
    fun `retainOnly는 제거되거나 바뀐 identity의 bytes와 실패 상태를 폐기한다`() = runTest {
        val source = RecordingIconSource { PNG }
        val catalog = RecordingCatalog(
            persistFailure = IllegalStateException("Room unavailable"),
        )
        val enricher = CatalogIconEnricher(catalog, source)
        val removed = record()
        val changed = record(installedVersionCode = 2)
        val bytes = requireNotNull(enricher.load(removed))

        assertEquals(IconPersistenceResult.WriteFailed, enricher.persist(removed, bytes))
        enricher.retainOnly(setOf(changed.identity()))

        assertNull(enricher.mergeCached(removed).iconPng)
        val reloaded = requireNotNull(enricher.load(removed))
        assertEquals(IconPersistenceResult.WriteFailed, enricher.persist(removed, reloaded))
        assertEquals(listOf(PACKAGE, PACKAGE), source.packages)
        assertEquals(2, catalog.persistAttempts.size)
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

        val results = identities.map { identity -> enricher.load(identity) }

        assertEquals(listOf(1, 2, 3, 4, 1), results.map { it!!.single().toInt() })
        assertEquals(List(4) { PACKAGE }, source.packages)
        assertEquals(0, catalog.persistAttempts.size)
    }

    @Test
    fun `replaceVerified 일반 예외는 현재 목록의 추출 성공 icon을 막지 않는다`() = runTest {
        val catalog = RecordingCatalog(
            persistFailure = IllegalArgumentException("write failed"),
        )
        val enricher = CatalogIconEnricher(catalog, RecordingIconSource { PNG })
        val loaded = requireNotNull(enricher.load(record()))

        val result = enricher.persist(record(), loaded)

        assertEquals(IconPersistenceResult.WriteFailed, result)
        assertEquals(1, catalog.persistAttempts.size)
    }

    @Test
    fun `새 enricher process cache는 이전 저장 실패 identity를 다시 시도한다`() = runTest {
        val source = RecordingIconSource { PNG }
        val catalog = RecordingCatalog(
            persistFailure = IllegalStateException("Room unavailable"),
        )

        val firstEnricher = CatalogIconEnricher(catalog, source)
        val firstBytes = requireNotNull(firstEnricher.load(record()))
        assertEquals(
            IconPersistenceResult.WriteFailed,
            firstEnricher.persist(record(), firstBytes),
        )
        val secondEnricher = CatalogIconEnricher(catalog, source)
        val secondBytes = requireNotNull(secondEnricher.load(record()))
        assertEquals(
            IconPersistenceResult.WriteFailed,
            secondEnricher.persist(record(), secondBytes),
        )

        assertEquals(listOf(PACKAGE, PACKAGE), source.packages)
        assertEquals(2, catalog.persistAttempts.size)
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
            RecordingCatalog(persistFailure = catalogCancellation),
            RecordingIconSource { PNG },
        )

        assertFailsWith<CancellationException> {
            sourceEnricher.load(record())
        }
        val loaded = requireNotNull(catalogEnricher.load(record()))
        assertFailsWith<CancellationException> {
            catalogEnricher.persist(record(), loaded)
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
        private val persistFailure: Exception? = null,
    ) : ManagerCatalog {
        val persistAttempts = mutableListOf<CatalogIdentity>()
        val persisted = mutableListOf<CatalogIdentity>()

        override fun observe(): Flow<List<CatalogRecord>> = flowOf(emptyList())

        override suspend fun replaceVerified(record: CatalogRecord) = Unit

        override suspend fun ensureDiscoveredIdentity(discovered: CatalogRecord): CatalogRecord = discovered

        override suspend fun persistIconIfIdentity(
            identity: CatalogIdentity,
            iconPng: ByteArray,
        ): CatalogRecord {
            persistAttempts += identity
            persistFailure?.let { throw it }
            return CatalogRecord(
                packageName = identity.packageName,
                extensionId = identity.extensionId,
                name = "Example",
                iconPng = iconPng,
                installedVersionCode = identity.installedVersionCode,
                signerSha256 = identity.signerSha256,
                compatibilityStatus = "COMPATIBLE",
                reportJson = "{\"status\":\"COMPATIBLE\"}",
                lastVerifiedAtEpochMillis = 99,
            ).also { persisted += identity }
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
