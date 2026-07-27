package dev.sixbridge.manager.conversion

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sixbridge.manager.catalog.ManagerDatabase
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

    private fun store(
        token: String,
        file: String,
    ) = ConversionWorkStore(
        privateRoot = workRoot,
        dao = database.conversionJobDao(),
        bundleVerifier = ConversionBundleVerifier { true },
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
