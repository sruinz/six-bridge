package dev.sixbridge.manager.catalog

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.manager.conversion.ConversionIdentityEntity
import dev.sixbridge.manager.conversion.ConversionJobEntity
import dev.sixbridge.manager.conversion.ConversionJobStage
import dev.sixbridge.manager.conversion.encodeCleanupTokens
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
import org.junit.Assert.assertThrows
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
                logicalSourceIdentity = "project:${"1".repeat(32)}",
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
    fun conversion_identity_unique_binding_rejects_cross_profile_and_mode_races() =
        runBlocking {
            val dao = database.conversionIdentityDao()
            val base = ConversionIdentityEntity(
                identitySha256 = "1".repeat(64),
                logicalSourceIdentity = "project:${"1".repeat(32)}",
                profileMajorVersion = 30,
                mode = "CONVERTED",
                displayName = "First",
                extensionId = "a".repeat(32),
                packageName = "dev.sixbridge.ext.${"a".repeat(32)}",
                wrapperSignerSha256 = "2".repeat(64),
                crxKeyAlias = "sixbridge-crx-room-unique",
                lastReservedVersionCode = 0,
            )
            val competing = base.copy(
                identitySha256 = "3".repeat(64),
                logicalSourceIdentity = "project:${"3".repeat(32)}",
                profileMajorVersion = 31,
                displayName = "Competing",
            )

            val results = coroutineScope {
                listOf(base, competing).map { candidate ->
                    async(Dispatchers.Default) {
                        runCatching { dao.reserve(candidate, null) }
                    }
                }.awaitAll()
            }

            assertEquals(1, results.count { it.isSuccess })
            val winner = results.firstNotNullOfOrNull { it.getOrNull() }
            assertTrue(winner == base.copy(lastReservedVersionCode = 1) ||
                winner == competing.copy(lastReservedVersionCode = 1))

            val passthrough = base.copy(
                identitySha256 = "4".repeat(64),
                logicalSourceIdentity = "store:${"b".repeat(32)}",
                mode = "PASSTHROUGH",
                crxKeyAlias = null,
            )
            assertTrue(runCatching { dao.reserve(passthrough, null) }.isFailure)
        }

    @Test
    fun conversion_job_generation_CAS_and_singleton_claim_survive_multi_dao_races() =
        runBlocking {
            val firstDao = database.conversionJobDao()
            val secondDao = database.conversionJobDao()
            val candidate = conversionJob(
                token = "00000000-0000-0000-0000-000000000001",
                singletonId = 99,
            )
            val claimed = firstDao.claimCreate(candidate)
            assertEquals(1, claimed.singletonId)
            assertEquals(1L, claimed.generation)

            val transitions = coroutineScope {
                listOf("a".repeat(64), "b".repeat(64)).mapIndexed { index, profile ->
                    async(Dispatchers.Default) {
                        (if (index == 0) firstDao else secondDao).transition(
                            token = claimed.token,
                            expectedStage = ConversionJobStage.INPUT_READY.name,
                            expectedGeneration = claimed.generation,
                            expectedProfileSha256 = null,
                            nextStage = ConversionJobStage.AWAITING_APPROVAL.name,
                            approvedAdapterIdsJson = null,
                            profileSha256 = profile,
                            bundleFileName = null,
                            bundleSha256 = null,
                            updatedAtEpochMillis = 2,
                        )
                    }
                }.awaitAll()
            }

            assertEquals(listOf(0, 1), transitions.sorted())
            assertEquals(2L, requireNotNull(firstDao.get()).generation)

            val replacement = conversionJob(
                token = "00000000-0000-0000-0000-000000000002",
            )
            val secondClaim = secondDao.claimCreate(replacement)
            assertEquals(3L, secondClaim.generation)
            assertTrue(secondClaim.cleanupTokensJson.contains(claimed.token))
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
                arrayOf<Any?>(
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

    @Test
    fun migration_2_to_3_preserves_old_catalog_identity_and_job_with_safe_defaults() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(MIGRATION_V2_DATABASE)
        migrationHelper.createDatabase(MIGRATION_V2_DATABASE, 2).apply {
            execSQL(
                """
                INSERT INTO catalog_entries (
                    packageName, extensionId, name, iconPng, installedVersionCode,
                    signerSha256, compatibilityStatus, reportJson,
                    lastVerifiedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "dev.sixbridge.ext.oldv2",
                    "oldv2",
                    "Old v2",
                    byteArrayOf(7, 8, 9),
                    12L,
                    "a".repeat(64),
                    "PARTIAL",
                    """{"status":"PARTIAL"}""",
                    5678L,
                ),
            )
            execSQL(
                """
                INSERT INTO conversion_identities (
                    identitySha256, logicalSourceIdentity, profileMajorVersion, mode,
                    displayName, extensionId, packageName, wrapperSignerSha256,
                    crxKeyAlias, lastReservedVersionCode
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "1".repeat(64),
                    "project:${"1".repeat(32)}",
                    30,
                    "CONVERTED",
                    "Old identity",
                    "a".repeat(32),
                    "dev.sixbridge.ext.${"a".repeat(32)}",
                    "2".repeat(64),
                    "sixbridge-crx-old-v2",
                    9,
                ),
            )
            execSQL(
                """
                INSERT INTO conversion_job (
                    singletonId, token, inputKind, inputSha256, inputFileName, stage,
                    approvedAdapterIdsJson, profileSha256, bundleFileName,
                    updatedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    1,
                    "00000000-0000-0000-0000-000000000001",
                    "ZIP",
                    "3".repeat(64),
                    "00000000-0000-0000-0000-000000000010.input",
                    "AWAITING_APPROVAL",
                    null,
                    "4".repeat(64),
                    null,
                    9999L,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MIGRATION_V2_DATABASE,
            3,
            true,
            ManagerDatabase.MIGRATION_2_3,
        )
        migrated.query(
            "SELECT name, installedVersionCode FROM catalog_entries WHERE packageName = ?",
            arrayOf("dev.sixbridge.ext.oldv2"),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Old v2", cursor.getString(0))
            assertEquals(12L, cursor.getLong(1))
        }
        migrated.query(
            """
            SELECT logicalSourceIdentity, lastReservedVersionCode
            FROM conversion_identities WHERE identitySha256 = ?
            """.trimIndent(),
            arrayOf("1".repeat(64)),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("project:${"1".repeat(32)}", cursor.getString(0))
            assertEquals(9, cursor.getInt(1))
        }
        migrated.query(
            """
            SELECT token, generation, stage, bundleSha256, cleanupTokensJson
            FROM conversion_job WHERE singletonId = 1
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("00000000-0000-0000-0000-000000000001", cursor.getString(0))
            assertEquals(1L, cursor.getLong(1))
            assertEquals("AWAITING_APPROVAL", cursor.getString(2))
            assertTrue(cursor.isNull(3))
            assertEquals("[]", cursor.getString(4))
        }
        migrated.close()
        context.deleteDatabase(MIGRATION_V2_DATABASE)
    }

    @Test
    fun migration_2_to_3_fails_closed_when_old_bindings_collide() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(MIGRATION_V2_COLLISION_DATABASE)
        migrationHelper.createDatabase(MIGRATION_V2_COLLISION_DATABASE, 2).apply {
            repeat(2) { index ->
                execSQL(
                    """
                    INSERT INTO conversion_identities (
                        identitySha256, logicalSourceIdentity, profileMajorVersion, mode,
                        displayName, extensionId, packageName, wrapperSignerSha256,
                        crxKeyAlias, lastReservedVersionCode
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    arrayOf<Any?>(
                        (index + 1).toString().repeat(64),
                        "project:${(index + 1).toString().repeat(32)}",
                        30 + index,
                        "CONVERTED",
                        "Collision $index",
                        "a".repeat(32),
                        "dev.sixbridge.ext.${"a".repeat(32)}",
                        "2".repeat(64),
                        "sixbridge-crx-collision",
                        index,
                    ),
                )
            }
            close()
        }

        assertThrows(IllegalStateException::class.java) {
            migrationHelper.runMigrationsAndValidate(
                MIGRATION_V2_COLLISION_DATABASE,
                3,
                true,
                ManagerDatabase.MIGRATION_2_3,
            )
        }
        android.database.sqlite.SQLiteDatabase.openDatabase(
            context.getDatabasePath(MIGRATION_V2_COLLISION_DATABASE).path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { unchanged ->
            unchanged.rawQuery("PRAGMA user_version", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
            unchanged.rawQuery(
                "SELECT COUNT(*) FROM conversion_identities",
                null,
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(2, cursor.getInt(0))
            }
        }
        context.deleteDatabase(MIGRATION_V2_COLLISION_DATABASE)
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

    private fun conversionJob(
        token: String,
        singletonId: Int = 1,
    ) = ConversionJobEntity(
        singletonId = singletonId,
        token = token,
        generation = 0,
        inputKind = "ZIP",
        inputSha256 = "a".repeat(64),
        inputFileName = "00000000-0000-0000-0000-000000000010.input",
        stage = ConversionJobStage.INPUT_READY.name,
        approvedAdapterIdsJson = null,
        profileSha256 = null,
        bundleFileName = null,
        bundleSha256 = null,
        cleanupTokensJson = encodeCleanupTokens(emptySet()),
        updatedAtEpochMillis = 1,
    )

    private companion object {
        const val MIGRATION_DATABASE = "manager-migration-1-2.db"
        const val MIGRATION_V2_DATABASE = "manager-migration-2-3.db"
        const val MIGRATION_V2_COLLISION_DATABASE = "manager-migration-2-3-collision.db"
    }
}
