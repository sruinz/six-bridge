package dev.sixbridge.manager.catalog

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.manager.conversion.ConversionBundleVerifier
import dev.sixbridge.manager.conversion.ConversionIdentityEntity
import dev.sixbridge.manager.conversion.ConversionIdentityKeyInspector
import dev.sixbridge.manager.conversion.ConversionIdentityKeyProof
import dev.sixbridge.manager.conversion.ConversionIdentityLeaseEntity
import dev.sixbridge.manager.conversion.ConversionIdentityLedger
import dev.sixbridge.manager.conversion.ConversionIdentityLifecycleState
import dev.sixbridge.manager.conversion.ConversionIdentityManagement
import dev.sixbridge.manager.conversion.ConversionIdentityReservation
import dev.sixbridge.manager.conversion.ConversionPackageBinding
import dev.sixbridge.manager.conversion.ConversionJobEntity
import dev.sixbridge.manager.conversion.ConversionJobStage
import dev.sixbridge.manager.conversion.ConversionWorkStore
import dev.sixbridge.manager.conversion.CurrentBundleReadyProof
import dev.sixbridge.manager.conversion.CurrentBundleReadyProofReader
import dev.sixbridge.manager.conversion.DefaultConversionIdentityPort
import dev.sixbridge.manager.conversion.InstallationIdentityKey
import dev.sixbridge.manager.conversion.InstallationIdentityMode
import dev.sixbridge.manager.conversion.LogicalSourceIdentity
import dev.sixbridge.manager.conversion.encodeCleanupTokens
import dev.sixbridge.manager.packager.OnDeviceWrapperPackager
import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    fun conversion_identity_room_transaction_allows_only_one_active_reservation() =
        runBlocking {
            val dao = database.conversionIdentityDao()
            val entity = ConversionIdentityEntity(
                identitySha256 = "1".repeat(64),
                logicalSourceIdentity = "project:${"1".repeat(32)}",
                profileMajorVersion = 30,
                mode = "PASSTHROUGH",
                displayName = "Room transaction",
                extensionId = "a".repeat(32),
                packageName = "dev.sixbridge.ext.${"a".repeat(32)}",
                wrapperSignerSha256 = "2".repeat(64),
                crxKeyAlias = null,
                lastReservedVersionCode = 0,
                lifecycleState = "PROVISIONAL",
                provisionOwnerToken = null,
                crxPublicKeySha256 = null,
                lastSuccessfulVersionCode = null,
                lifecycleUpdatedAtEpochMillis = 0,
            )

            val reserved = coroutineScope {
                List(8) {
                    async(Dispatchers.Default) {
                        runCatching {
                            dao.reserve(
                                requested = entity,
                                installedVersionCode = null,
                                readyOwnerToken = null,
                                reservationOwnerToken = "room-owner-$it",
                                createdAtEpochMillis = it.toLong(),
                                crxPublicKeySha256 = null,
                            )
                        }
                    }
                }.awaitAll()
            }

            assertEquals(1, reserved.count { it.isSuccess })
            assertEquals(1, reserved.single { it.isSuccess }.getOrThrow().lastReservedVersionCode)
        }

    @Test
    fun legacy_reconciliation_commits_exact_installed_proof_and_converted_key_in_real_room() =
        runBlocking {
            val dao = database.conversionIdentityDao()
            val converted = ConversionIdentityEntity(
                identitySha256 = "a".repeat(64),
                logicalSourceIdentity = "project:${"a".repeat(32)}",
                profileMajorVersion = 30,
                mode = InstallationIdentityMode.CONVERTED.name,
                displayName = "Converted legacy",
                extensionId = "a".repeat(32),
                packageName = "dev.sixbridge.ext.${"a".repeat(32)}",
                wrapperSignerSha256 = "c".repeat(64),
                crxKeyAlias = "sixbridge.crx.rsa.v1.${"a".repeat(64)}",
                lastReservedVersionCode = 17,
                lifecycleState = ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name,
                provisionOwnerToken = null,
                crxPublicKeySha256 = null,
                lastSuccessfulVersionCode = null,
                lifecycleUpdatedAtEpochMillis = 0,
            )
            val passthrough = converted.copy(
                identitySha256 = "b".repeat(64),
                logicalSourceIdentity = "store:${"b".repeat(32)}",
                mode = InstallationIdentityMode.PASSTHROUGH.name,
                displayName = "Passthrough legacy",
                extensionId = "b".repeat(32),
                packageName = "dev.sixbridge.ext.${"b".repeat(32)}",
                crxKeyAlias = null,
                lastReservedVersionCode = 23,
            )
            dao.insert(converted)
            dao.insert(passthrough)
            val installed = listOf(
                InstalledWrapper(
                    packageName = converted.packageName,
                    extensionId = converted.extensionId,
                    name = converted.displayName,
                    versionCode = 29,
                    signerSha256 = converted.wrapperSignerSha256,
                ),
                InstalledWrapper(
                    packageName = passthrough.packageName,
                    extensionId = passthrough.extensionId,
                    name = passthrough.displayName,
                    versionCode = 19,
                    signerSha256 = passthrough.wrapperSignerSha256,
                ),
            )
            val management = ConversionIdentityManagement(
                dao = dao,
                installedWrappers = object : InstalledWrapperRepository {
                    override suspend fun findAll() = installed
                    override suspend fun find(packageName: String) =
                        installed.singleOrNull { it.packageName == packageName }
                },
                currentBundleReadyProofReader = CurrentBundleReadyProofReader {
                    CurrentBundleReadyProof.Absent
                },
                keyInspector = object : ConversionIdentityKeyInspector {
                    override suspend fun <T> withIdentityLock(
                        identitySha256: String,
                        block: suspend () -> T,
                    ): T = block()

                    override fun inspect(identitySha256: String) =
                        if (identitySha256 == converted.identitySha256) {
                            ConversionIdentityKeyProof.Verified(
                                alias = requireNotNull(converted.crxKeyAlias),
                                extensionId = converted.extensionId,
                                publicKeySha256 = "d".repeat(64),
                            )
                        } else {
                            ConversionIdentityKeyProof.Absent
                        }
                },
                currentTimeMillis = { 1234L },
            )

            val result = management.reconcileLegacyIdentities()

            assertEquals(setOf(converted.identitySha256, passthrough.identitySha256), result.promotedIdentitySha256)
            requireNotNull(dao.get(converted.identitySha256)).also { active ->
                assertEquals(ConversionIdentityLifecycleState.ACTIVE.name, active.lifecycleState)
                assertEquals(29, active.lastSuccessfulVersionCode)
                assertEquals(29, active.lastReservedVersionCode)
                assertEquals("d".repeat(64), active.crxPublicKeySha256)
            }
            requireNotNull(dao.get(passthrough.identitySha256)).also { active ->
                assertEquals(ConversionIdentityLifecycleState.ACTIVE.name, active.lifecycleState)
                assertEquals(19, active.lastSuccessfulVersionCode)
                assertEquals(23, active.lastReservedVersionCode)
                assertNull(active.crxPublicKeySha256)
            }
            Unit
        }

    @Test
    fun existing_target_query_awaits_legacy_reconciliation_before_reading_active_rows() =
        runBlocking {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val dao = database.conversionIdentityDao()
            val active = ConversionIdentityEntity(
                identitySha256 = "e".repeat(64),
                logicalSourceIdentity = "project:${"e".repeat(32)}",
                profileMajorVersion = 30,
                mode = InstallationIdentityMode.CONVERTED.name,
                displayName = "Active target",
                extensionId = "e".repeat(32),
                packageName = "dev.sixbridge.ext.${"e".repeat(32)}",
                wrapperSignerSha256 = "f".repeat(64),
                crxKeyAlias = "sixbridge.crx.rsa.v1.${"e".repeat(64)}",
                lastReservedVersionCode = 3,
                lifecycleState = ConversionIdentityLifecycleState.ACTIVE.name,
                provisionOwnerToken = null,
                crxPublicKeySha256 = "d".repeat(64),
                lastSuccessfulVersionCode = 3,
                lifecycleUpdatedAtEpochMillis = 1,
            )
            dao.insert(active)
            val port = DefaultConversionIdentityPort(
                ledger = ConversionIdentityLedger(dao),
                packager = OnDeviceWrapperPackager(
                    ApplicationProvider.getApplicationContext(),
                ),
                reconcileLegacyIdentities = {
                    entered.complete(Unit)
                    release.await()
                },
            )

            val query = async { port.existingTargets(30) }
            entered.await()
            assertFalse(query.isCompleted)
            release.complete(Unit)

            assertEquals(listOf(active.identitySha256), query.await().map { it.identitySha256 })
        }

    @Test
    fun conversion_identity_unique_binding_rejects_cross_profile_and_mode_races() =
        runBlocking {
            val dao = database.conversionIdentityDao()
            val base = ConversionIdentityEntity(
                identitySha256 = "1".repeat(64),
                logicalSourceIdentity = "project:${"1".repeat(32)}",
                profileMajorVersion = 30,
                mode = "PASSTHROUGH",
                displayName = "First",
                extensionId = "a".repeat(32),
                packageName = "dev.sixbridge.ext.${"a".repeat(32)}",
                wrapperSignerSha256 = "2".repeat(64),
                crxKeyAlias = null,
                lastReservedVersionCode = 0,
                lifecycleState = "PROVISIONAL",
                provisionOwnerToken = null,
                crxPublicKeySha256 = null,
                lastSuccessfulVersionCode = null,
                lifecycleUpdatedAtEpochMillis = 0,
            )
            val competing = base.copy(
                identitySha256 = "3".repeat(64),
                logicalSourceIdentity = "project:${"3".repeat(32)}",
                profileMajorVersion = 31,
                displayName = "Competing",
            )

            val results = coroutineScope {
                listOf(base, competing).mapIndexed { index, candidate ->
                    async(Dispatchers.Default) {
                        runCatching {
                            dao.reserve(
                                requested = candidate,
                                installedVersionCode = null,
                                readyOwnerToken = null,
                                reservationOwnerToken = "unique-owner-$index",
                                createdAtEpochMillis = index.toLong(),
                                crxPublicKeySha256 = null,
                            )
                        }
                    }
                }.awaitAll()
            }

            assertEquals(1, results.count { it.isSuccess })
            val winnerIndex = results.indexOfFirst { it.isSuccess }
            val winner = results[winnerIndex].getOrThrow()
            val winnerRequest = listOf(base, competing)[winnerIndex]
            assertEquals(
                winnerRequest.copy(
                    lastReservedVersionCode = 1,
                    provisionOwnerToken = "unique-owner-$winnerIndex",
                    lifecycleUpdatedAtEpochMillis = winnerIndex.toLong(),
                ),
                winner,
            )

            val passthrough = base.copy(
                identitySha256 = "4".repeat(64),
                logicalSourceIdentity = "store:${"b".repeat(32)}",
                mode = "PASSTHROUGH",
                crxKeyAlias = null,
            )
            assertTrue(
                runCatching {
                    dao.reserve(
                        requested = passthrough,
                        installedVersionCode = null,
                        readyOwnerToken = null,
                        reservationOwnerToken = "collision-owner",
                        createdAtEpochMillis = 2,
                        crxPublicKeySha256 = null,
                    )
                }.isFailure,
            )
        }

    @Test
    fun conversion_identity_provision_owner_unique_index_allows_nulls_and_rejects_duplicate_value() =
        runBlocking {
            val dao = database.conversionIdentityDao()

            dao.insert(v5Identity(discriminator = "7", provisionOwnerToken = null))
            dao.insert(v5Identity(discriminator = "8", provisionOwnerToken = null))

            assertEquals(2, dao.listByModeAndProfile("CONVERTED", 30).size)

            dao.insert(
                v5Identity(
                    discriminator = "9",
                    provisionOwnerToken = "00000000-0000-4000-8000-000000000090",
                ),
            )
            val duplicate = runCatching {
                dao.insert(
                    v5Identity(
                        discriminator = "a",
                        provisionOwnerToken = "00000000-0000-4000-8000-000000000090",
                    ),
                )
            }.exceptionOrNull()
            assertTrue(duplicate is SQLiteConstraintException)
        }

    @Test
    fun conversion_identity_claim_rejects_old_version_and_forged_owner() = runBlocking {
        val ledger = ConversionIdentityLedger(database.conversionIdentityDao())
        val firstOwner = "00000000-0000-4000-8000-000000000001"
        val secondOwner = "00000000-0000-4000-8000-000000000002"
        val key = InstallationIdentityKey.create(
            LogicalSourceIdentity.storeExtensionId("a".repeat(32)),
            30,
            InstallationIdentityMode.PASSTHROUGH,
        )
        val request = ConversionIdentityReservation(
            identityKey = key,
            displayName = "opaque capability",
            packageBinding = ConversionPackageBinding(
                extensionId = "a".repeat(32),
                packageName = "dev.sixbridge.ext.${"a".repeat(32)}",
                wrapperSignerSha256 = "b".repeat(64),
                crxKeyAlias = null,
                crxPublicKeySha256 = null,
            ),
            installedVersionCode = null,
        )

        val dao = database.conversionIdentityDao()
        val first = ledger.reserve(request, firstOwner)
        assertTrue(ledger.releaseFailedAttempt(firstOwner))
        assertTrue(runCatching { ledger.reserve(request, secondOwner) }.isFailure)
        val second = ledger.reserve(request, firstOwner)
        val stored = requireNotNull(dao.get(key.identitySha256))

        assertTrue(
            runCatching {
                dao.claimForPackaging(
                    requested = stored,
                    versionCode = first.versionCode,
                    ownerToken = firstOwner,
                    crxPublicKeySha256 = null,
                    packagingState = "PACKAGING:11111111-1111-4111-8111-111111111111",
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                dao.claimForPackaging(
                    requested = stored,
                    versionCode = second.versionCode,
                    ownerToken = secondOwner,
                    crxPublicKeySha256 = null,
                    packagingState = "PACKAGING:22222222-2222-4222-8222-222222222222",
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                ledger.recoverNonPublishedByOwnerTokenForTests(
                    "00000000-0000-4000-8000-000000000099",
                )
            }.isFailure,
        )
        dao.claimForPackaging(
            requested = stored,
            versionCode = second.versionCode,
            ownerToken = firstOwner,
            crxPublicKeySha256 = null,
            packagingState = "PACKAGING:33333333-3333-4333-8333-333333333333",
        )
        assertTrue(ledger.releaseFailedAttempt(firstOwner))
    }

    @Test
    fun conversion_identity_ready_reserve_and_cleanup_race_has_one_durable_winner() =
        runBlocking {
            val dao = database.conversionIdentityDao()
            val identitySha256 = "5".repeat(64)
            val alias = "sixbridge.crx.rsa.v1.$identitySha256"
            dao.insertLease(
                ConversionIdentityLeaseEntity(
                    identitySha256 = identitySha256,
                    crxKeyAlias = alias,
                    ownerToken = "00000000-0000-4000-8000-000000000010",
                    state = "READY",
                    createdAtEpochMillis = 1,
                    extensionId = "c".repeat(32),
                    packageName = "dev.sixbridge.ext.${"c".repeat(32)}",
                    wrapperSignerSha256 = "d".repeat(64),
                    crxPublicKeySha256 = "e".repeat(64),
                    reservedVersionCode = null,
                ),
            )
            val requested = ConversionIdentityEntity(
                identitySha256 = identitySha256,
                logicalSourceIdentity = "project:${"5".repeat(32)}",
                profileMajorVersion = 30,
                mode = "CONVERTED",
                displayName = "reserve-cleanup race",
                extensionId = "c".repeat(32),
                packageName = "dev.sixbridge.ext.${"c".repeat(32)}",
                wrapperSignerSha256 = "d".repeat(64),
                crxKeyAlias = alias,
                lastReservedVersionCode = 0,
                lifecycleState = "PROVISIONAL",
                provisionOwnerToken = null,
                crxPublicKeySha256 = "e".repeat(64),
                lastSuccessfulVersionCode = null,
                lifecycleUpdatedAtEpochMillis = 0,
            )

            val outcomes = coroutineScope {
                listOf(
                    async(Dispatchers.Default) {
                        runCatching {
                            dao.reserve(
                                requested = requested,
                                installedVersionCode = null,
                                readyOwnerToken = "00000000-0000-4000-8000-000000000010",
                                reservationOwnerToken = "00000000-0000-4000-8000-000000000011",
                                createdAtEpochMillis = 2,
                                crxPublicKeySha256 = "e".repeat(64),
                            )
                        }
                    },
                    async(Dispatchers.Default) {
                        runCatching {
                            dao.claimCleanup(
                                identitySha256,
                                "00000000-0000-4000-8000-000000000010",
                            )
                        }
                    },
                ).awaitAll()
            }

            assertEquals(1, outcomes.count { it.isSuccess })
            val identity = dao.get(identitySha256)
            val lease = requireNotNull(dao.getLease(identitySha256))
            if (identity == null) {
                assertEquals("CLEANING", lease.state)
                assertEquals("00000000-0000-4000-8000-000000000010", lease.ownerToken)
            } else {
                assertEquals("RESERVED", lease.state)
                assertEquals("00000000-0000-4000-8000-000000000011", lease.ownerToken)
                assertEquals(1, identity.lastReservedVersionCode)
            }
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
    fun migration_3_to_4_preserves_identity_and_creates_empty_durable_lease_ledger() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(MIGRATION_V3_DATABASE)
        migrationHelper.createDatabase(MIGRATION_V3_DATABASE, 3).apply {
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
                    "Fold7 preserved identity",
                    "a".repeat(32),
                    "dev.sixbridge.ext.${"a".repeat(32)}",
                    "2".repeat(64),
                    "sixbridge-crx-v3",
                    17,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MIGRATION_V3_DATABASE,
            4,
            true,
            ManagerDatabase.MIGRATION_3_4,
        )
        migrated.query(
            """
            SELECT displayName, crxKeyAlias, lastReservedVersionCode
            FROM conversion_identities WHERE identitySha256 = ?
            """.trimIndent(),
            arrayOf("1".repeat(64)),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Fold7 preserved identity", cursor.getString(0))
            assertEquals("sixbridge-crx-v3", cursor.getString(1))
            assertEquals(17, cursor.getInt(2))
            assertFalse(cursor.moveToNext())
        }
        migrated.query("SELECT COUNT(*) FROM conversion_identity_leases").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(0, cursor.getInt(0))
        }
        migrated.close()
        context.deleteDatabase(MIGRATION_V3_DATABASE)
    }

    @Test
    fun migration_4_to_5_preserves_identity_lease_and_job_with_unknown_lifecycle() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(MIGRATION_V4_DATABASE)
        migrationHelper.createDatabase(MIGRATION_V4_DATABASE, 4).apply {
            listOf(
                arrayOf<Any?>(
                    "1".repeat(64),
                    "project:${"1".repeat(32)}",
                    30,
                    "CONVERTED",
                    "Converted legacy identity",
                    "a".repeat(32),
                    "dev.sixbridge.ext.${"a".repeat(32)}",
                    "2".repeat(64),
                    "sixbridge.crx.rsa.v1.${"1".repeat(64)}",
                    17,
                ),
                arrayOf<Any?>(
                    "3".repeat(64),
                    "store:${"b".repeat(32)}",
                    31,
                    "PASSTHROUGH",
                    "Passthrough legacy identity",
                    "b".repeat(32),
                    "dev.sixbridge.ext.${"b".repeat(32)}",
                    "4".repeat(64),
                    null,
                    23,
                ),
            ).forEach { identity ->
                execSQL(
                    """
                    INSERT INTO conversion_identities (
                        identitySha256, logicalSourceIdentity, profileMajorVersion, mode,
                        displayName, extensionId, packageName, wrapperSignerSha256,
                        crxKeyAlias, lastReservedVersionCode
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    identity,
                )
            }
            execSQL(
                """
                INSERT INTO conversion_identity_leases (
                    identitySha256, crxKeyAlias, ownerToken, state, createdAtEpochMillis,
                    extensionId, packageName, wrapperSignerSha256, crxPublicKeySha256,
                    reservedVersionCode
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    "1".repeat(64),
                    "sixbridge.crx.rsa.v1.${"1".repeat(64)}",
                    "00000000-0000-4000-8000-000000000041",
                    "RESERVED",
                    4100L,
                    "a".repeat(32),
                    "dev.sixbridge.ext.${"a".repeat(32)}",
                    "2".repeat(64),
                    "5".repeat(64),
                    17,
                ),
            )
            execSQL(
                """
                INSERT INTO conversion_job (
                    singletonId, token, generation, inputKind, inputSha256, inputFileName,
                    stage, approvedAdapterIdsJson, profileSha256, bundleFileName,
                    bundleSha256, cleanupTokensJson, updatedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any?>(
                    1,
                    "00000000-0000-4000-8000-000000000041",
                    9L,
                    "ZIP",
                    "6".repeat(64),
                    "00000000-0000-4000-8000-000000000041.input",
                    "BUILDING",
                    "[\"adapter-one\"]",
                    "7".repeat(64),
                    null,
                    null,
                    "[]",
                    4200L,
                ),
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            MIGRATION_V4_DATABASE,
            5,
            true,
            ManagerDatabase.MIGRATION_4_5,
        )
        migrated.query(
            """
            SELECT identitySha256, logicalSourceIdentity, profileMajorVersion, mode,
                   displayName, extensionId, packageName, wrapperSignerSha256,
                   crxKeyAlias, lastReservedVersionCode, lifecycleState,
                   provisionOwnerToken, crxPublicKeySha256, lastSuccessfulVersionCode,
                   lifecycleUpdatedAtEpochMillis
            FROM conversion_identities
            ORDER BY identitySha256
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("1".repeat(64), cursor.getString(0))
            assertEquals("project:${"1".repeat(32)}", cursor.getString(1))
            assertEquals(30, cursor.getInt(2))
            assertEquals("CONVERTED", cursor.getString(3))
            assertEquals("Converted legacy identity", cursor.getString(4))
            assertEquals("a".repeat(32), cursor.getString(5))
            assertEquals("dev.sixbridge.ext.${"a".repeat(32)}", cursor.getString(6))
            assertEquals("2".repeat(64), cursor.getString(7))
            assertEquals("sixbridge.crx.rsa.v1.${"1".repeat(64)}", cursor.getString(8))
            assertEquals(17, cursor.getInt(9))
            assertEquals("LEGACY_UNKNOWN", cursor.getString(10))
            assertTrue(cursor.isNull(11))
            assertTrue(cursor.isNull(12))
            assertTrue(cursor.isNull(13))
            assertEquals(0L, cursor.getLong(14))

            assertTrue(cursor.moveToNext())
            assertEquals("3".repeat(64), cursor.getString(0))
            assertEquals("store:${"b".repeat(32)}", cursor.getString(1))
            assertEquals(31, cursor.getInt(2))
            assertEquals("PASSTHROUGH", cursor.getString(3))
            assertEquals("Passthrough legacy identity", cursor.getString(4))
            assertEquals("b".repeat(32), cursor.getString(5))
            assertEquals("dev.sixbridge.ext.${"b".repeat(32)}", cursor.getString(6))
            assertEquals("4".repeat(64), cursor.getString(7))
            assertTrue(cursor.isNull(8))
            assertEquals(23, cursor.getInt(9))
            assertEquals("LEGACY_UNKNOWN", cursor.getString(10))
            assertTrue(cursor.isNull(11))
            assertTrue(cursor.isNull(12))
            assertTrue(cursor.isNull(13))
            assertEquals(0L, cursor.getLong(14))
            assertFalse(cursor.moveToNext())
        }
        migrated.query(
            """
            SELECT crxKeyAlias, ownerToken, state, createdAtEpochMillis, extensionId,
                   packageName, wrapperSignerSha256, crxPublicKeySha256, reservedVersionCode
            FROM conversion_identity_leases WHERE identitySha256 = ?
            """.trimIndent(),
            arrayOf("1".repeat(64)),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("sixbridge.crx.rsa.v1.${"1".repeat(64)}", cursor.getString(0))
            assertEquals("00000000-0000-4000-8000-000000000041", cursor.getString(1))
            assertEquals("RESERVED", cursor.getString(2))
            assertEquals(4100L, cursor.getLong(3))
            assertEquals("a".repeat(32), cursor.getString(4))
            assertEquals("dev.sixbridge.ext.${"a".repeat(32)}", cursor.getString(5))
            assertEquals("2".repeat(64), cursor.getString(6))
            assertEquals("5".repeat(64), cursor.getString(7))
            assertEquals(17, cursor.getInt(8))
            assertFalse(cursor.moveToNext())
        }
        migrated.query(
            """
            SELECT token, generation, inputKind, inputSha256, inputFileName, stage,
                   approvedAdapterIdsJson, profileSha256, bundleFileName, bundleSha256,
                   cleanupTokensJson, updatedAtEpochMillis
            FROM conversion_job WHERE singletonId = 1
            """.trimIndent(),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("00000000-0000-4000-8000-000000000041", cursor.getString(0))
            assertEquals(9L, cursor.getLong(1))
            assertEquals("ZIP", cursor.getString(2))
            assertEquals("6".repeat(64), cursor.getString(3))
            assertEquals("00000000-0000-4000-8000-000000000041.input", cursor.getString(4))
            assertEquals("BUILDING", cursor.getString(5))
            assertEquals("[\"adapter-one\"]", cursor.getString(6))
            assertEquals("7".repeat(64), cursor.getString(7))
            assertTrue(cursor.isNull(8))
            assertTrue(cursor.isNull(9))
            assertEquals("[]", cursor.getString(10))
            assertEquals(4200L, cursor.getLong(11))
            assertFalse(cursor.moveToNext())
        }
        migrated.close()
        context.deleteDatabase(MIGRATION_V4_DATABASE)
    }

    @Test
    fun migration_2_to_3_first_restore_preserves_input_for_every_old_job_stage() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val now = System.currentTimeMillis()
        val inputBytes = "old v2 private input".encodeToByteArray()
        val inputSha256 = sha256(inputBytes)
        val fixtures = listOf(
            OldJobFixture(
                stage = ConversionJobStage.INPUT_READY,
                approvedAdapterIdsJson = null,
                profileSha256 = null,
                bundleFileName = null,
                expectedRestoredStage = ConversionJobStage.INPUT_READY,
            ),
            OldJobFixture(
                stage = ConversionJobStage.AWAITING_APPROVAL,
                approvedAdapterIdsJson = null,
                profileSha256 = "4".repeat(64),
                bundleFileName = null,
                expectedRestoredStage = ConversionJobStage.AWAITING_APPROVAL,
            ),
            OldJobFixture(
                stage = ConversionJobStage.BUILDING,
                approvedAdapterIdsJson = "[]",
                profileSha256 = "4".repeat(64),
                bundleFileName = null,
                expectedRestoredStage = ConversionJobStage.INPUT_READY,
            ),
            OldJobFixture(
                stage = ConversionJobStage.BUNDLE_READY,
                approvedAdapterIdsJson = "[]",
                profileSha256 = "4".repeat(64),
                bundleFileName = "00000000-0000-0000-0000-000000000020.six",
                expectedRestoredStage = ConversionJobStage.INPUT_READY,
            ),
        )

        fixtures.forEachIndexed { index, fixture ->
            val databaseName = "manager-migration-stage-$index.db"
            val workRoot = File(context.cacheDir, "manager-migration-stage-$index").apply {
                deleteRecursively()
                check(mkdir())
            }
            val token = "00000000-0000-0000-0000-00000000000${index + 1}"
            val inputFileName = "00000000-0000-0000-0000-000000000010.input"
            val tokenDirectory = File(workRoot, token).apply { check(mkdir()) }
            val inputFile = File(tokenDirectory, inputFileName).apply {
                writeBytes(inputBytes)
            }
            fixture.bundleFileName?.let { name ->
                File(tokenDirectory, name).writeText("old v2 completed bundle")
            }
            context.deleteDatabase(databaseName)
            migrationHelper.createDatabase(databaseName, 2).apply {
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
                        token,
                        "ZIP",
                        inputSha256,
                        inputFileName,
                        fixture.stage.name,
                        fixture.approvedAdapterIdsJson,
                        fixture.profileSha256,
                        fixture.bundleFileName,
                        if (fixture.stage == ConversionJobStage.BUNDLE_READY) 1L else now,
                    ),
                )
                close()
            }
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                3,
                true,
                ManagerDatabase.MIGRATION_2_3,
            ).close()
            val migratedDatabase = Room.databaseBuilder(
                context,
                ManagerDatabase::class.java,
                databaseName,
            ).addMigrations(
                ManagerDatabase.MIGRATION_2_3,
                ManagerDatabase.MIGRATION_3_4,
                ManagerDatabase.MIGRATION_4_5,
            )
                .allowMainThreadQueries()
                .build()
            try {
                val restored = ConversionWorkStore(
                    privateRoot = workRoot,
                    dao = migratedDatabase.conversionJobDao(),
                    bundleVerifier = ConversionBundleVerifier { input ->
                        input.readBytes()
                        true
                    },
                    identityOwnerLifecycle =
                        ConversionIdentityLedger(migratedDatabase.conversionIdentityDao())
                            .ownerLifecycle(OnDeviceWrapperPackager(context)),
                    clock = { now },
                ).restore()

                assertNotNull("old ${fixture.stage} restore", restored)
                assertEquals(fixture.expectedRestoredStage, requireNotNull(restored).stage)
                assertTrue(inputFile.isFile)
                assertArrayEquals(inputBytes, inputFile.readBytes())
                val row = requireNotNull(migratedDatabase.conversionJobDao().get())
                assertNull(row.bundleFileName)
                assertNull(row.bundleSha256)
                if (fixture.expectedRestoredStage == ConversionJobStage.INPUT_READY) {
                    assertNull(row.approvedAdapterIdsJson)
                    assertNull(row.profileSha256)
                } else {
                    assertEquals("4".repeat(64), row.profileSha256)
                }
                fixture.bundleFileName?.let { name ->
                    assertFalse(File(tokenDirectory, name).exists())
                }
            } finally {
                migratedDatabase.close()
                workRoot.deleteRecursively()
                context.deleteDatabase(databaseName)
            }
        }
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

    private fun v5Identity(
        discriminator: String,
        provisionOwnerToken: String?,
    ) = ConversionIdentityEntity(
        identitySha256 = discriminator.repeat(64),
        logicalSourceIdentity = "project:${discriminator.repeat(32)}",
        profileMajorVersion = 30,
        mode = "CONVERTED",
        displayName = "Unique owner $discriminator",
        extensionId = discriminator.repeat(32),
        packageName = "dev.sixbridge.ext.${discriminator.repeat(32)}",
        wrapperSignerSha256 = "f".repeat(64),
        crxKeyAlias = "sixbridge.crx.rsa.v1.${discriminator.repeat(64)}",
        lastReservedVersionCode = 1,
        lifecycleState = "PROVISIONAL",
        provisionOwnerToken = provisionOwnerToken,
        crxPublicKeySha256 = discriminator.repeat(64),
        lastSuccessfulVersionCode = null,
        lifecycleUpdatedAtEpochMillis = 100L,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class OldJobFixture(
        val stage: ConversionJobStage,
        val approvedAdapterIdsJson: String?,
        val profileSha256: String?,
        val bundleFileName: String?,
        val expectedRestoredStage: ConversionJobStage,
    )

    private companion object {
        const val MIGRATION_DATABASE = "manager-migration-1-2.db"
        const val MIGRATION_V2_DATABASE = "manager-migration-2-3.db"
        const val MIGRATION_V2_COLLISION_DATABASE = "manager-migration-2-3-collision.db"
        const val MIGRATION_V3_DATABASE = "manager-migration-3-4.db"
        const val MIGRATION_V4_DATABASE = "manager-migration-4-5.db"
    }
}
