package dev.sixbridge.manager.catalog

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagerDatabaseTest {
    private lateinit var database: ManagerDatabase
    private lateinit var catalog: RoomManagerCatalog

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ManagerDatabase::class.java,
        ).allowMainThreadQueries().build()
        catalog = RoomManagerCatalog(database.catalogEntryDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `replaces records by package primary key and round trips nullable fields`() = runBlocking {
        val first = record(
            packageName = "dev.sixbridge.ext.one",
            iconPng = byteArrayOf(1, 2, 3),
            reportJson = "{\"compatibility\":\"compatible\"}",
        )
        catalog.replaceVerified(first)
        val initiallyStored = requireNotNull(catalog.get(first.packageName))
        assertArrayEquals(byteArrayOf(1, 2, 3), initiallyStored.iconPng)
        assertEquals("{\"compatibility\":\"compatible\"}", initiallyStored.reportJson)

        val replacement = first.copy(
            name = "Updated extension",
            iconPng = null,
            reportJson = null,
            installedVersionCode = 8,
        )
        catalog.replaceVerified(replacement)
        catalog.replaceVerified(record(packageName = "dev.sixbridge.ext.two"))
        catalog.replaceVerified(record(packageName = "dev.sixbridge.ext.stale-one"))
        catalog.replaceVerified(record(packageName = "dev.sixbridge.ext.stale-two"))

        val stored = requireNotNull(catalog.get(first.packageName))
        assertEquals("Updated extension", stored.name)
        assertEquals(8, stored.installedVersionCode)
        assertNull(stored.iconPng)
        assertNull(stored.reportJson)
        assertEquals(
            listOf(
                "dev.sixbridge.ext.one",
                "dev.sixbridge.ext.stale-one",
                "dev.sixbridge.ext.stale-two",
                "dev.sixbridge.ext.two",
            ),
            catalog.observe().first().map(CatalogRecord::packageName),
        )

        catalog.remove(first.packageName)
        assertNull(catalog.get(first.packageName))

        catalog.removeAll(
            setOf("dev.sixbridge.ext.stale-one", "dev.sixbridge.ext.stale-two"),
        )
        assertEquals(
            listOf("dev.sixbridge.ext.two"),
            catalog.observe().first().map(CatalogRecord::packageName),
        )

        catalog.removeAll(emptySet())
        assertEquals(
            listOf("dev.sixbridge.ext.two"),
            catalog.observe().first().map(CatalogRecord::packageName),
        )

    }

    @Test
    fun ensure_absent_identity_inserts_unknown_baseline() = runBlocking {
        val discovered = record(
            packageName = "dev.sixbridge.ext.discovered",
            iconPng = null,
            reportJson = null,
        ).copy(
            compatibilityStatus = "UNKNOWN",
            lastVerifiedAtEpochMillis = 0,
        )

        val ensured = catalog.ensureDiscoveredIdentity(discovered)

        assertEquals(discovered, ensured)
        assertEquals(discovered, catalog.get(discovered.packageName))
    }

    @Test
    fun ensure_same_identity_preserves_latest_verified_columns() = runBlocking {
        val discovered = record("dev.sixbridge.ext.atomic").copy(
            name = "Discovered",
            iconPng = null,
            compatibilityStatus = "UNKNOWN",
            reportJson = null,
            lastVerifiedAtEpochMillis = 0,
        )
        val latest = discovered.copy(
            name = "Latest",
            compatibilityStatus = "BLOCKED",
            reportJson = "{\"status\":\"BLOCKED\"}",
            lastVerifiedAtEpochMillis = 999,
        )
        catalog.replaceVerified(latest)

        val stored = catalog.ensureDiscoveredIdentity(discovered)
        assertEquals("Latest", stored.name)
        assertArrayEquals(latest.iconPng, stored.iconPng)
        assertEquals("BLOCKED", stored.compatibilityStatus)
        assertEquals("{\"status\":\"BLOCKED\"}", stored.reportJson)
        assertEquals(999, stored.lastVerifiedAtEpochMillis)
    }

    @Test
    fun ensure_changed_identity_replaces_with_unknown_baseline() = runBlocking {
        val current = record("dev.sixbridge.ext.changed")
        catalog.replaceVerified(current)

        listOf(
            current.copy(extensionId = "different"),
            current.copy(installedVersionCode = current.installedVersionCode + 1),
            current.copy(signerSha256 = "b".repeat(64)),
        ).forEach { changed ->
            val discovered = changed.copy(
                name = "Discovered",
                iconPng = null,
                compatibilityStatus = "UNKNOWN",
                reportJson = null,
                lastVerifiedAtEpochMillis = 0,
            )

            val ensured = catalog.ensureDiscoveredIdentity(discovered)

            assertEquals(discovered, ensured)
            assertEquals(discovered, catalog.get(current.packageName))
        }
    }

    @Test
    fun delete_followed_by_late_icon_update_does_not_reinsert_row() = runBlocking {
        val discovered = record(packageName = "dev.sixbridge.ext.deleted")

        catalog.ensureDiscoveredIdentity(discovered)
        catalog.remove(discovered.packageName)

        assertNull(catalog.persistIconIfIdentity(discovered.identity(), byteArrayOf(3, 2, 1)))
        assertNull(catalog.get(discovered.packageName))
    }

    private fun record(
        packageName: String,
        iconPng: ByteArray? = byteArrayOf(4, 5, 6),
        reportJson: String? = "{\"compatibility\":\"partial\"}",
    ) = CatalogRecord(
        packageName = packageName,
        extensionId = packageName.substringAfterLast('.'),
        name = "Catalog extension",
        iconPng = iconPng,
        installedVersionCode = 7,
        signerSha256 = "a".repeat(64),
        compatibilityStatus = "PARTIAL",
        reportJson = reportJson,
        lastVerifiedAtEpochMillis = 1234,
    )
}
