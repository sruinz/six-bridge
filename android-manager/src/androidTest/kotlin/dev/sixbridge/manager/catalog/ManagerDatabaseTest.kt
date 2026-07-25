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
