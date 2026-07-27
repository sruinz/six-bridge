package dev.sixbridge.manager.conversion

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sixbridge.manager.catalog.ManagerDatabase
import dev.sixbridge.manager.packager.OnDeviceWrapperPackager
import java.io.File
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
    }

    @Test
    fun multi_store_create_approve_build_and_discard_use_room_CAS() = runBlocking {
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
        assertEquals(listOf(false, true), discards.sorted())
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

    private fun store(
        token: String,
        file: String,
    ) = ConversionWorkStore(
        privateRoot = workRoot,
        dao = database.conversionJobDao(),
        bundleVerifier = ConversionBundleVerifier { true },
        identityOwnerLifecycle =
            ConversionIdentityLedger(database.conversionIdentityDao())
                .ownerLifecycle(OnDeviceWrapperPackager(ApplicationProvider.getApplicationContext())),
        clock = { 1_800_000_000_000L },
        tokenFactory = { token },
        fileNameFactory = { file },
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

    private companion object {
        const val PROFILE_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_PROFILE_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
