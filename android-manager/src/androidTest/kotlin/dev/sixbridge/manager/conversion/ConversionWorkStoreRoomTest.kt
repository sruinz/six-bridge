package dev.sixbridge.manager.conversion

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sixbridge.manager.catalog.ManagerDatabase
import dev.sixbridge.manager.packager.OnDeviceWrapperPackager
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversionWorkStoreRoomTest {
    private lateinit var database: ManagerDatabase
    private lateinit var workRoot: File
    private lateinit var sourceRoot: File
    private val publishedOutputs = mutableListOf<File>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, ManagerDatabase::class.java).build()
        workRoot = File(context.cacheDir, "conversion-work-room-${UUID.randomUUID()}").apply {
            check(mkdir())
        }
        sourceRoot = File(context.cacheDir, "conversion-source-room-${UUID.randomUUID()}").apply {
            check(mkdir())
        }
    }

    @After
    fun tearDown() {
        database.close()
        workRoot.deleteRecursively()
        sourceRoot.deleteRecursively()
        publishedOutputs.forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
        publishedOutputs.clear()
    }

    @Test
    fun multi_store_transitions_use_room_CAS_and_discard_is_idempotent() = runBlocking {
        val first = store(
            token = "00000000-0000-0000-0000-000000000001",
            file = "00000000-0000-0000-0000-000000000011",
        )
        val second = store(
            token = "00000000-0000-0000-0000-000000000002",
            file = "00000000-0000-0000-0000-000000000012",
        )

        val creates = coroutineScope {
            listOf(
                async(Dispatchers.Default) {
                    runCatching { first.create(artifact("first".encodeToByteArray())) }
                },
                async(Dispatchers.Default) {
                    runCatching { second.create(artifact("second".encodeToByteArray())) }
                },
            ).awaitAll()
        }
        assertEquals(2, creates.count { it.isSuccess })
        val active = requireNotNull(database.conversionJobDao().get())
        assertTrue(requireNotNull(decodeCleanupTokens(active.cleanupTokensJson)).isEmpty())
        assertEquals(1, workRoot.listFiles().orEmpty().count { it.isDirectory })
        assertNotNull(first.restore())

        val approvals = coroutineScope {
            listOf(first, second).mapIndexed { index, store ->
                async(Dispatchers.Default) {
                    runCatching {
                        store.markAwaitingApproval(
                            active.token,
                            if (index == 0) PROFILE_SHA else OTHER_PROFILE_SHA,
                        )
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, approvals.count { it.isSuccess })
        val approvedProfile = requireNotNull(database.conversionJobDao().get()).profileSha256

        val builds = coroutineScope {
            listOf(first, second).map { store ->
                async(Dispatchers.Default) {
                    runCatching {
                        store.markBuilding(active.token, setOf("adapter"), requireNotNull(approvedProfile))
                    }
                }
            }.awaitAll()
        }
        assertEquals(1, builds.count { it.isSuccess })

        val discards = coroutineScope {
            listOf(first, second).map { store ->
                async(Dispatchers.Default) { store.discard(active.token) }
            }.awaitAll()
        }
        assertEquals(listOf(true, true), discards.sorted())
        assertEquals(null, database.conversionJobDao().get())
        assertTrue(workRoot.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun room_claim_recovers_malformed_previous_ledger_from_root_owners() = runBlocking {
        val first = store(
            token = "00000000-0000-0000-0000-000000000001",
            file = "00000000-0000-0000-0000-000000000011",
        ).create(artifact("first".encodeToByteArray()))
        database.openHelper.writableDatabase.execSQL(
            """
            UPDATE conversion_job
            SET token = ?, cleanupTokensJson = ?
            WHERE singletonId = 1
            """.trimIndent(),
            arrayOf("not-a-canonical-token", "{broken"),
        )

        val second = store(
            token = "00000000-0000-0000-0000-000000000002",
            file = "00000000-0000-0000-0000-000000000012",
        ).create(artifact("second".encodeToByteArray()))

        val active = requireNotNull(database.conversionJobDao().get())
        assertEquals(second.token, active.token)
        assertTrue(requireNotNull(decodeCleanupTokens(active.cleanupTokensJson)).isEmpty())
        assertFalse(File(workRoot, first.token).exists())
        assertEquals(listOf(second.token), workRoot.listFiles().orEmpty().map(File::getName))
    }

    @Test
    fun room_cross_instance_expiry_invalid_and_discard_races_preserve_replacement_owner() =
        runBlocking {
            var active = store(
                token = "00000000-0000-0000-0000-000000000021",
                file = "00000000-0000-0000-0000-000000000031",
            ).create(artifact("expiry-old".encodeToByteArray()))
            database.openHelper.writableDatabase.execSQL(
                "UPDATE conversion_job SET updatedAtEpochMillis = 1 WHERE singletonId = 1",
            )
            val expiryReplacement = store(
                token = "00000000-0000-0000-0000-000000000022",
                file = "00000000-0000-0000-0000-000000000032",
            )
            coroutineScope {
                listOf(
                    async(Dispatchers.Default) { store(active.token, UUID.randomUUID().toString()).restore() },
                    async(Dispatchers.Default) {
                        expiryReplacement.create(artifact("expiry-new".encodeToByteArray()))
                    },
                ).awaitAll()
            }
            active = requireNotNull(expiryReplacement.restore())
            assertEquals("00000000-0000-0000-0000-000000000022", active.token)

            database.openHelper.writableDatabase.execSQL(
                "UPDATE conversion_job SET stage = 'BROKEN' WHERE singletonId = 1",
            )
            val invalidReplacement = store(
                token = "00000000-0000-0000-0000-000000000023",
                file = "00000000-0000-0000-0000-000000000033",
            )
            coroutineScope {
                listOf(
                    async(Dispatchers.Default) { expiryReplacement.restore() },
                    async(Dispatchers.Default) {
                        invalidReplacement.create(artifact("invalid-new".encodeToByteArray()))
                    },
                ).awaitAll()
            }
            active = requireNotNull(invalidReplacement.restore())
            assertEquals("00000000-0000-0000-0000-000000000023", active.token)

            val discardReplacement = store(
                token = "00000000-0000-0000-0000-000000000024",
                file = "00000000-0000-0000-0000-000000000034",
            )
            coroutineScope {
                listOf(
                    async(Dispatchers.Default) { invalidReplacement.discard(active.token) },
                    async(Dispatchers.Default) {
                        discardReplacement.create(artifact("discard-new".encodeToByteArray()))
                    },
                ).awaitAll()
            }
            active = requireNotNull(discardReplacement.restore())
            assertEquals("00000000-0000-0000-0000-000000000024", active.token)
            assertEquals(
                listOf(active.token),
                workRoot.listFiles().orEmpty().filter(File::isDirectory).map(File::getName),
            )
        }

    @Test
    fun bundle_ready_job_commit_crash_resumes_atomic_identity_commit_and_exact_cleanup() =
        runBlocking {
            val fixture = readyFixture(
                workHooks = ConversionWorkStoreHooks(
                    afterBundleTransition = { error("simulated crash after BUNDLE_READY commit") },
                ),
            )
            val expectedBundleSha256 = sha256(fixture.bundle.readBytes())

            expectFailure<IllegalStateException> {
                fixture.store.markBundleReady(
                    fixture.token,
                    fixture.bundle,
                    expectedBundleSha256,
                )
            }
            assertEquals(
                ConversionJobStage.BUNDLE_READY.name,
                requireNotNull(database.conversionJobDao().get()).stage,
            )
            assertEquals(
                ConversionIdentityLifecycleState.PROVISIONAL.name,
                database.conversionIdentityDao().get(fixture.identitySha256)?.lifecycleState,
            )
            assertTrue(fixture.output.isFile)

            val restored = requireNotNull(restartStore(fixture).restore())
            val active = requireNotNull(database.conversionIdentityDao().get(fixture.identitySha256))
            assertEquals(ConversionJobStage.BUNDLE_READY, restored.stage)
            assertEquals(ConversionIdentityLifecycleState.ACTIVE.name, active.lifecycleState)
            assertEquals(fixture.versionCode, active.lastSuccessfulVersionCode)
            assertEquals(null, active.provisionOwnerToken)
            assertEquals(null, database.conversionIdentityDao().getLease(fixture.identitySha256))
            assertFalse(fixture.output.exists())
            assertEquals(fixture.token, requireNotNull(restartStore(fixture).restore()).token)
        }

    @Test
    fun cleaning_committed_crash_resumes_output_cleanup_without_rewriting_active_identity() =
        runBlocking {
            val fixture = readyFixture(
                ledgerHooks = ConversionIdentityLedgerHooks(
                    afterBundleReadyCommit = { error("simulated crash after identity commit") },
                ),
            )

            expectFailure<IllegalStateException> {
                fixture.store.markBundleReady(
                    fixture.token,
                    fixture.bundle,
                    sha256(fixture.bundle.readBytes()),
                )
            }
            val committed = requireNotNull(database.conversionIdentityDao().get(fixture.identitySha256))
            val lease = requireNotNull(database.conversionIdentityDao().getLease(fixture.identitySha256))
            assertEquals(ConversionIdentityLifecycleState.ACTIVE.name, committed.lifecycleState)
            assertEquals(fixture.versionCode, committed.lastSuccessfulVersionCode)
            assertTrue(lease.state.startsWith("CLEANING_COMMITTED:"))
            assertTrue(fixture.output.isFile)

            assertEquals(ConversionJobStage.BUNDLE_READY, requireNotNull(restartStore(fixture).restore()).stage)
            assertEquals(
                committed,
                database.conversionIdentityDao().get(fixture.identitySha256),
            )
            assertEquals(null, database.conversionIdentityDao().getLease(fixture.identitySha256))
            assertFalse(fixture.output.exists())
        }

    @Test
    fun output_cleanup_crash_resumes_exact_lease_delete_for_existing_active_update() =
        runBlocking {
            val fixture = readyFixture(
                active = true,
                ledgerHooks = ConversionIdentityLedgerHooks(
                    afterCommittedOutputCleanup = { error("simulated crash after output cleanup") },
                ),
            )

            expectFailure<IllegalStateException> {
                fixture.store.markBundleReady(
                    fixture.token,
                    fixture.bundle,
                    sha256(fixture.bundle.readBytes()),
                )
            }
            val active = requireNotNull(database.conversionIdentityDao().get(fixture.identitySha256))
            assertEquals(ConversionIdentityLifecycleState.ACTIVE.name, active.lifecycleState)
            assertEquals(fixture.versionCode, active.lastSuccessfulVersionCode)
            assertEquals(null, active.provisionOwnerToken)
            assertTrue(
                requireNotNull(database.conversionIdentityDao().getLease(fixture.identitySha256))
                    .state.startsWith("CLEANING_COMMITTED:"),
            )
            assertFalse(fixture.output.exists())

            assertEquals(ConversionJobStage.BUNDLE_READY, requireNotNull(restartStore(fixture).restore()).stage)
            assertEquals(null, database.conversionIdentityDao().getLease(fixture.identitySha256))
            assertEquals(active, database.conversionIdentityDao().get(fixture.identitySha256))
        }

    @Test
    fun committed_cleanup_preserves_other_identity_output() = runBlocking {
        val fixture = readyFixture()
        val packager = OnDeviceWrapperPackager(ApplicationProvider.getApplicationContext())
        val otherOutput = packager.outputFile(UUID.randomUUID().toString()).apply {
            writeText("other identity output")
        }
        publishedOutputs += otherOutput

        fixture.store.markBundleReady(
            fixture.token,
            fixture.bundle,
            sha256(fixture.bundle.readBytes()),
        )

        assertFalse(fixture.output.exists())
        assertEquals("other identity output", otherOutput.readText())
    }

    @Test
    fun committed_cleanup_preserves_different_hash_at_exact_output_path() = runBlocking {
        val fixture = readyFixture()
        fixture.output.writeText("replacement bytes with a different hash")

        expectFailure<IllegalArgumentException> {
            fixture.store.markBundleReady(
                fixture.token,
                fixture.bundle,
                sha256(fixture.bundle.readBytes()),
            )
        }

        assertEquals("replacement bytes with a different hash", fixture.output.readText())
        assertEquals(
            ConversionIdentityLifecycleState.ACTIVE.name,
            database.conversionIdentityDao().get(fixture.identitySha256)?.lifecycleState,
        )
        assertTrue(
            requireNotNull(database.conversionIdentityDao().getLease(fixture.identitySha256))
                .state.startsWith("CLEANING_COMMITTED:"),
        )
        expectFailure<IllegalArgumentException> { restartStore(fixture).restore() }
        assertEquals("replacement bytes with a different hash", fixture.output.readText())
    }

    @Test
    fun committed_cleanup_preserves_symbolic_link_replacement_path() = runBlocking {
        val fixture = readyFixture()
        val replacement = File(sourceRoot, "replacement-wrapper.apk").apply {
            writeText("replacement target")
        }
        assertTrue(fixture.output.delete())
        Files.createSymbolicLink(fixture.output.toPath(), replacement.toPath())

        expectFailure<IllegalArgumentException> {
            fixture.store.markBundleReady(
                fixture.token,
                fixture.bundle,
                sha256(fixture.bundle.readBytes()),
            )
        }

        assertTrue(Files.isSymbolicLink(fixture.output.toPath()))
        assertEquals("replacement target", replacement.readText())
        assertTrue(
            requireNotNull(database.conversionIdentityDao().getLease(fixture.identitySha256))
                .state.startsWith("CLEANING_COMMITTED:"),
        )
    }

    private fun store(
        token: String,
        file: String,
        workHooks: ConversionWorkStoreHooks = ConversionWorkStoreHooks(),
        ledgerHooks: ConversionIdentityLedgerHooks = ConversionIdentityLedgerHooks(),
    ) = ConversionWorkStore(
        privateRoot = workRoot,
        dao = database.conversionJobDao(),
        bundleVerifier = ConversionBundleVerifier { input ->
            input.readBytes()
            true
        },
        identityOwnerLifecycle =
            ConversionIdentityLedger(database.conversionIdentityDao(), hooks = ledgerHooks)
                .ownerLifecycle(OnDeviceWrapperPackager(ApplicationProvider.getApplicationContext())),
        clock = { 1_800_000_000_000L },
        tokenFactory = { token },
        fileNameFactory = { file },
        hooks = workHooks,
    )

    private suspend fun readyFixture(
        active: Boolean = false,
        workHooks: ConversionWorkStoreHooks = ConversionWorkStoreHooks(),
        ledgerHooks: ConversionIdentityLedgerHooks = ConversionIdentityLedgerHooks(),
    ): ReadyFixture {
        val token = UUID.randomUUID().toString()
        val store = store(
            token = token,
            file = UUID.randomUUID().toString(),
            workHooks = workHooks,
            ledgerHooks = ledgerHooks,
        )
        val created = store.create(artifact("room success input".encodeToByteArray()))
        store.markAwaitingApproval(created.token, PROFILE_SHA)
        store.markBuilding(created.token, setOf("adapter"), PROFILE_SHA)
        val identitySha256 = sha256("identity:$token".encodeToByteArray())
        val versionCode = 7
        database.conversionIdentityDao().insert(
            ConversionIdentityEntity(
                identitySha256 = identitySha256,
                logicalSourceIdentity = "project:${identitySha256.take(32)}",
                profileMajorVersion = 30,
                mode = InstallationIdentityMode.PASSTHROUGH.name,
                displayName = "Room identity",
                extensionId = "a".repeat(32),
                packageName = "dev.sixbridge.ext.${"a".repeat(32)}",
                wrapperSignerSha256 = "b".repeat(64),
                crxKeyAlias = null,
                lastReservedVersionCode = versionCode,
                lifecycleState = if (active) {
                    ConversionIdentityLifecycleState.ACTIVE.name
                } else {
                    ConversionIdentityLifecycleState.PROVISIONAL.name
                },
                provisionOwnerToken = if (active) null else token,
                crxPublicKeySha256 = null,
                lastSuccessfulVersionCode = if (active) versionCode - 1 else null,
                lifecycleUpdatedAtEpochMillis = 1,
            ),
        )
        val wrapperBytes = "claim-owned wrapper:$token".encodeToByteArray()
        val packager = OnDeviceWrapperPackager(ApplicationProvider.getApplicationContext())
        val output = packager.outputFile(token).apply { writeBytes(wrapperBytes) }
        publishedOutputs += output
        val claimUuid = UUID.randomUUID()
        val packagedState = "PACKAGED:$claimUuid:${sha256(wrapperBytes)}:${encodeHex(output.name)}"
        database.conversionIdentityDao().insertLease(
            ConversionIdentityLeaseEntity(
                identitySha256 = identitySha256,
                crxKeyAlias = null,
                ownerToken = token,
                state = packagedState,
                createdAtEpochMillis = 1,
                extensionId = "a".repeat(32),
                packageName = "dev.sixbridge.ext.${"a".repeat(32)}",
                wrapperSignerSha256 = "b".repeat(64),
                crxPublicKeySha256 = null,
                reservedVersionCode = versionCode,
            ),
        )
        val bundle = File(sourceRoot, "$token.six").apply {
            writeText("verified room bundle:$token")
        }
        return ReadyFixture(
            token = token,
            identitySha256 = identitySha256,
            versionCode = versionCode,
            output = output,
            bundle = bundle,
            store = store,
        )
    }

    private fun restartStore(fixture: ReadyFixture): ConversionWorkStore = store(
        token = fixture.token,
        file = UUID.randomUUID().toString(),
    )

    private fun artifact(bytes: ByteArray): OnDeviceInputArtifact {
        val token = UUID.randomUUID().toString()
        val directory = File(sourceRoot, token).apply { check(mkdir()) }
        val input = File(directory, "input.bin").apply { writeBytes(bytes) }
        return OnDeviceInputArtifact(
            token = token,
            kind = OnDeviceInputKind.ZIP,
            privateFile = input,
            sha256 = sha256(bytes),
            expectedExtensionId = null,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun encodeHex(value: String): String =
        value.encodeToByteArray().joinToString("") { "%02x".format(it) }

    private suspend inline fun <reified T : Throwable> expectFailure(
        crossinline block: suspend () -> Unit,
    ): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("expected ${T::class.java.name}, got ${error::class.java.name}", error is T)
            @Suppress("UNCHECKED_CAST")
            return error as T
        }
        throw AssertionError("expected ${T::class.java.name}")
    }

    private data class ReadyFixture(
        val token: String,
        val identitySha256: String,
        val versionCode: Int,
        val output: File,
        val bundle: File,
        val store: ConversionWorkStore,
    )

    private companion object {
        const val PROFILE_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_PROFILE_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
