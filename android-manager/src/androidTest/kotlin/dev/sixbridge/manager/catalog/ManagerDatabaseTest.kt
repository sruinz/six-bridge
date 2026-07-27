package dev.sixbridge.manager.catalog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.manager.conversion.ConversionIdentityEntity
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagerDatabaseTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ManagerDatabase::class.java,
    )

    private lateinit var database: ManagerDatabase
    private lateinit var catalog: RoomManagerCatalog
    private val queries = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ManagerDatabase::class.java,
        ).setQueryCallback(
            { query, _ -> queries += query },
            Executor { command -> command.run() },
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
    fun ensure_identity_read_stays_inside_room_transaction_before_baseline_write() = runBlocking {
        val discovered = record("dev.sixbridge.ext.transaction")
        queries.clear()

        catalog.ensureDiscoveredIdentity(discovered)

        val selectIndex = queries.indexOfFirst { query ->
            query.startsWith("SELECT * FROM catalog_entries WHERE packageName")
        }
        val transactionIndex = queries.indexOfFirst { query ->
            query.startsWith("BEGIN")
        }
        assertTrue("identity SELECT must be recorded", selectIndex >= 0)
        assertTrue("transaction must begin before identity SELECT", transactionIndex in 0 until selectIndex)
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

        listOf(
            current.copy(extensionId = "different"),
            current.copy(installedVersionCode = current.installedVersionCode + 1),
            current.copy(signerSha256 = "b".repeat(64)),
        ).forEach { changed ->
            database.clearAllTables()
            catalog.replaceVerified(current)
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
    fun persist_same_identity_updates_only_icon_and_preserves_latest_verified_columns() = runBlocking {
        val discovered = record("dev.sixbridge.ext.persist")
        val latest = discovered.copy(
            name = "Latest",
            iconPng = byteArrayOf(1, 2, 3),
            compatibilityStatus = "BLOCKED",
            reportJson = "{\"status\":\"BLOCKED\"}",
            lastVerifiedAtEpochMillis = 999,
        )
        catalog.replaceVerified(latest)

        val persisted = requireNotNull(
            catalog.persistIconIfIdentity(discovered.identity(), byteArrayOf(7, 8, 9)),
        )

        assertEquals("Latest", persisted.name)
        assertArrayEquals(byteArrayOf(7, 8, 9), persisted.iconPng)
        assertEquals("BLOCKED", persisted.compatibilityStatus)
        assertEquals("{\"status\":\"BLOCKED\"}", persisted.reportJson)
        assertEquals(999, persisted.lastVerifiedAtEpochMillis)
    }

    @Test
    fun persist_changed_identity_rejects_each_identity_field_without_changing_current_row() = runBlocking {
        val current = record("dev.sixbridge.ext.persist-changed")

        listOf(
            current.copy(extensionId = "different"),
            current.copy(installedVersionCode = current.installedVersionCode + 1),
            current.copy(signerSha256 = "b".repeat(64)),
        ).forEach { stale ->
            database.clearAllTables()
            catalog.replaceVerified(current)

            assertNull(catalog.persistIconIfIdentity(stale.identity(), byteArrayOf(9)))

            val stored = requireNotNull(catalog.get(current.packageName))
            assertEquals(current.name, stored.name)
            assertArrayEquals(current.iconPng, stored.iconPng)
            assertEquals(current.compatibilityStatus, stored.compatibilityStatus)
            assertEquals(current.reportJson, stored.reportJson)
            assertEquals(current.lastVerifiedAtEpochMillis, stored.lastVerifiedAtEpochMillis)
        }
    }

    @Test
    fun concurrent_ensure_and_replace_preserve_latest_verified_columns() = runBlocking {
        repeat(20) { iteration ->
            database.clearAllTables()
            val discovered = record("dev.sixbridge.ext.concurrent-$iteration").copy(
                name = "Discovered",
                iconPng = null,
                compatibilityStatus = "UNKNOWN",
                reportJson = null,
                lastVerifiedAtEpochMillis = 0,
            )
            val latest = discovered.copy(
                name = "Latest",
                iconPng = byteArrayOf(1, 2, 3),
                compatibilityStatus = "BLOCKED",
                reportJson = "{\"status\":\"BLOCKED\"}",
                lastVerifiedAtEpochMillis = 999,
            )
            val barrier = CyclicBarrier(2)

            val ensure = async(Dispatchers.Default) {
                barrier.await()
                catalog.ensureDiscoveredIdentity(discovered)
            }
            val replace = async(Dispatchers.Default) {
                barrier.await()
                catalog.replaceVerified(latest)
            }
            ensure.await()
            replace.await()

            val stored = requireNotNull(catalog.get(discovered.packageName))
            assertEquals("Latest", stored.name)
            assertArrayEquals(byteArrayOf(1, 2, 3), stored.iconPng)
            assertEquals("BLOCKED", stored.compatibilityStatus)
            assertEquals("{\"status\":\"BLOCKED\"}", stored.reportJson)
            assertEquals(999, stored.lastVerifiedAtEpochMillis)
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

    @Test
    fun conversion_identity_room_transaction_serializes_concurrent_reservations() =
        runBlocking {
            val dao = database.conversionIdentityDao()
            val entity = ConversionIdentityEntity(
                identitySha256 = "1".repeat(64),
                logicalSourceIdentity = "project:room-transaction",
                profileMajorVersion = 30,
                mode = "CONVERTED",
                displayName = "Room transaction",
                extensionId = "a".repeat(32),
                packageName = "dev.sixbridge.ext.${"a".repeat(32)}",
                wrapperSignerSha256 = "2".repeat(64),
                crxKeyAlias = "sixbridge-crx-room",
                lastReservedVersionCode = 0,
            )

            val reserved = coroutineScope {
                List(8) {
                    async(Dispatchers.Default) {
                        dao.reserve(entity, installedVersionCode = null)
                            .lastReservedVersionCode
                    }
                }.awaitAll()
            }

            assertEquals((1..8).toSet(), reserved.toSet())
        }

    @Test
    fun migration_1_to_2_preserves_catalog_and_creates_conversion_ledgers() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(MIGRATION_DATABASE)
        migrationHelper.createDatabase(MIGRATION_DATABASE, 1).apply {
            execSQL(
                """
                INSERT INTO catalog_entries (
                    packageName, extensionId, name, iconPng, installedVersionCode,
                    signerSha256, compatibilityStatus, reportJson,
                    lastVerifiedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    "dev.sixbridge.ext.migrated",
                    "migrated",
                    "Migrated extension",
                    byteArrayOf(1, 2, 3),
                    7L,
                    "a".repeat(64),
                    "COMPATIBLE",
                    """{"status":"COMPATIBLE"}""",
                    1234L,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MIGRATION_DATABASE,
            2,
            true,
            ManagerDatabase.MIGRATION_1_2,
        )
        migrated.query(
            "SELECT packageName, installedVersionCode FROM catalog_entries",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("dev.sixbridge.ext.migrated", cursor.getString(0))
            assertEquals(7L, cursor.getLong(1))
            assertFalse(cursor.moveToNext())
        }
        migrated.query(
            """
            SELECT name FROM sqlite_master
            WHERE type = 'table'
              AND name IN ('conversion_identities', 'conversion_job')
            ORDER BY name
            """.trimIndent(),
        ).use { cursor ->
            val tables = buildList {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
            assertEquals(listOf("conversion_identities", "conversion_job"), tables)
        }
        migrated.close()
        context.deleteDatabase(MIGRATION_DATABASE)
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

    private companion object {
        const val MIGRATION_DATABASE = "manager-migration-1-2.db"
    }
}
