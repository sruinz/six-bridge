package dev.sixbridge.manager.conversion

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.io.path.createTempDirectory
import kotlin.test.assertFailsWith

class ConversionWorkStoreTest {
    private lateinit var root: File
    private lateinit var sourceRoot: File
    private lateinit var dao: FakeConversionJobDao
    private var now = 1_800_000_000_000L
    private var tokenSequence = 0
    private var fileSequence = 0

    @Before
    fun setUp() {
        root = createTempDirectory("conversion-work-store").toFile()
        sourceRoot = createTempDirectory("conversion-input-source").toFile()
        dao = FakeConversionJobDao()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        sourceRoot.deleteRecursively()
    }

    @Test
    fun `create는 Task6 artifact를 private UUID 파일로 인수하고 외부 경로를 저장하지 않는다`() =
        runTest {
            val artifact = artifact("private input".encodeToByteArray())
            val sourcePath = artifact.privateFile.absolutePath
            val store = store()

            val created = store.create(artifact)
            val row = requireNotNull(dao.get())

            assertEquals(ConversionJobStage.INPUT_READY, created.stage)
            assertEquals(artifact.sha256, created.inputSha256)
            assertTrue(created.inputFile.isFile)
            assertTrue(
                requireNotNull(created.inputFile.parentFile).canonicalFile.parentFile ==
                    root.canonicalFile,
            )
            assertTrue(created.inputFile.name.endsWith(".input"))
            UUID.fromString(created.inputFile.name.removeSuffix(".input"))
            assertFalse(row.toString().contains(sourcePath))
            assertFalse(row.toString().contains("content://"))
            assertFalse(row.inputFileName == artifact.privateFile.name)
        }

    @Test
    fun `INPUT_READY와 AWAITING_APPROVAL을 hash 재검증 후 복구한다`() = runTest {
        val store = store()
        val input = store.create(artifact("input".encodeToByteArray()))

        assertEquals(
            ConversionJobStage.INPUT_READY,
            requireNotNull(store.restore()).stage,
        )

        store.markAwaitingApproval(input.token, PROFILE_SHA)
        val approval = requireNotNull(store.restore())
        assertEquals(ConversionJobStage.AWAITING_APPROVAL, approval.stage)
        assertEquals(PROFILE_SHA, approval.profileSha256)
    }

    @Test
    fun `process death 뒤 BUILDING은 INPUT_READY로 내리고 자동 서명 상태를 지운다`() =
        runTest {
            val store = store()
            val input = store.create(artifact("input".encodeToByteArray()))
            store.markAwaitingApproval(input.token, PROFILE_SHA)
            store.markBuilding(
                token = input.token,
                approvedAdapterIds = setOf("adapter-b", "adapter-a"),
                profileSha256 = PROFILE_SHA,
            )

            val restored = requireNotNull(store.restore())
            val row = requireNotNull(dao.get())

            assertEquals(ConversionJobStage.INPUT_READY, restored.stage)
            assertTrue(restored.approvedAdapterIds.isEmpty())
            assertNull(restored.profileSha256)
            assertNull(restored.bundleFile)
            assertNull(row.approvedAdapterIdsJson)
            assertNull(row.profileSha256)
            assertNull(row.bundleFileName)
        }

    @Test
    fun `BUNDLE_READY는 bundle verifier를 다시 통과해야 복구한다`() = runTest {
        var verifierCalls = 0
        val store = store(
            bundleVerifier = ConversionBundleVerifier { file ->
                verifierCalls += 1
                file.readText() == "verified bundle"
            },
        )
        val input = store.create(artifact("input".encodeToByteArray()))
        store.markAwaitingApproval(input.token, PROFILE_SHA)
        store.markBuilding(input.token, emptySet(), PROFILE_SHA)
        val candidate = File(sourceRoot, "outside-user-name.six").apply {
            writeText("verified bundle")
        }

        val ready = store.markBundleReady(input.token, candidate)
        val restored = requireNotNull(store.restore())

        assertEquals(ConversionJobStage.BUNDLE_READY, ready.stage)
        assertEquals(ConversionJobStage.BUNDLE_READY, restored.stage)
        assertTrue(requireNotNull(restored.bundleFile).name.endsWith(".six"))
        assertTrue(verifierCalls >= 2)
        assertFalse(requireNotNull(dao.get()).toString().contains(candidate.absolutePath))
    }

    @Test
    fun `BUNDLE_READY 재검증 실패는 작업과 private 파일을 폐기한다`() = runTest {
        var valid = true
        val store = store(
            bundleVerifier = ConversionBundleVerifier { valid },
        )
        val input = store.create(artifact("input".encodeToByteArray()))
        store.markAwaitingApproval(input.token, PROFILE_SHA)
        store.markBuilding(input.token, emptySet(), PROFILE_SHA)
        val candidate = File(sourceRoot, "bundle.six").apply { writeText("bundle") }
        val ready = store.markBundleReady(input.token, candidate)
        val tokenDirectory = requireNotNull(ready.inputFile.parentFile)
        valid = false

        assertNull(store.restore())
        assertNull(dao.get())
        assertFalse(tokenDirectory.exists())
    }

    @Test
    fun `input hash가 바뀌면 작업과 token 전체를 폐기한다`() = runTest {
        val store = store()
        val created = store.create(artifact("input".encodeToByteArray()))
        created.inputFile.setWritable(true)
        created.inputFile.writeText("tampered")
        val tokenDirectory = requireNotNull(created.inputFile.parentFile)

        assertNull(store.restore())
        assertNull(dao.get())
        assertFalse(tokenDirectory.exists())
    }

    @Test
    fun `direct child를 symlink로 바꾸면 외부 파일을 따라가지 않고 폐기한다`() =
        runTest {
            val store = store()
            val created = store.create(artifact("input".encodeToByteArray()))
            val outside = File(sourceRoot, "outside").apply { writeText("input") }
            val inputPath = created.inputFile.toPath()
            Files.delete(inputPath)
            Files.createSymbolicLink(inputPath, outside.toPath())

            assertNull(store.restore())
            assertTrue(outside.isFile)
            assertEquals("input", outside.readText())
        }

    @Test
    fun `경로를 포함한 DB 파일명은 외부 파일을 삭제하지 않고 fail closed 한다`() =
        runTest {
            val outside = File(root.parentFile, "conversion-work-outside").apply {
                writeText("keep")
            }
            try {
                dao.replace(
                    ConversionJobEntity(
                        token = UUID.randomUUID().toString(),
                        inputKind = OnDeviceInputKind.ZIP.name,
                        inputSha256 = sha256("keep".encodeToByteArray()),
                        inputFileName = "../${outside.name}",
                        stage = ConversionJobStage.INPUT_READY.name,
                        approvedAdapterIdsJson = null,
                        profileSha256 = null,
                        bundleFileName = null,
                        updatedAtEpochMillis = now,
                    ),
                )

                assertNull(store().restore())
                assertEquals("keep", outside.readText())
            } finally {
                outside.delete()
            }
        }

    @Test
    fun `24시간 지난 미완료 작업과 파일을 정리한다`() = runTest {
        val store = store()
        val created = store.create(artifact("input".encodeToByteArray()))
        val tokenDirectory = requireNotNull(created.inputFile.parentFile)
        now += ConversionWorkStore.INCOMPLETE_RETENTION_MILLIS + 1

        assertNull(store.restore())
        assertNull(dao.get())
        assertFalse(tokenDirectory.exists())
    }

    @Test
    fun `새 create는 이전 활성 작업을 폐기하고 하나만 유지한다`() = runTest {
        val store = store()
        val first = store.create(artifact("first".encodeToByteArray()))
        val firstDirectory = requireNotNull(first.inputFile.parentFile)
        val second = store.create(artifact("second".encodeToByteArray()))

        assertFalse(firstDirectory.exists())
        assertEquals(second.token, requireNotNull(dao.get()).token)
        assertEquals(1, root.listFiles().orEmpty().count(File::isDirectory))
    }

    @Test
    fun `다른 token으로 stage를 바꾸거나 discard할 수 없다`() = runTest {
        val store = store()
        val created = store.create(artifact("input".encodeToByteArray()))

        assertFailsWith<IllegalStateException> {
            store.markAwaitingApproval(UUID.randomUUID().toString(), PROFILE_SHA)
        }
        assertFalse(store.discard(UUID.randomUUID().toString()))
        assertEquals(created.token, requireNotNull(store.restore()).token)
    }

    private fun store(
        bundleVerifier: ConversionBundleVerifier = ConversionBundleVerifier { true },
    ) = ConversionWorkStore(
        privateRoot = root,
        dao = dao,
        bundleVerifier = bundleVerifier,
        clock = { now },
        tokenFactory = {
            tokenSequence += 1
            UUID(0, tokenSequence.toLong()).toString()
        },
        fileNameFactory = {
            fileSequence += 1
            UUID(1, fileSequence.toLong()).toString()
        },
    )

    private fun artifact(bytes: ByteArray): OnDeviceInputArtifact {
        val token = UUID.randomUUID().toString()
        val directory = File(sourceRoot, token).apply { check(mkdir()) }
        val file = File(directory, "input.bin").apply {
            writeBytes(bytes)
            setReadOnly()
        }
        return OnDeviceInputArtifact(
            token = token,
            kind = OnDeviceInputKind.ZIP,
            privateFile = file,
            sha256 = sha256(bytes),
            expectedExtensionId = null,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private class FakeConversionJobDao : ConversionJobDao {
        private var row: ConversionJobEntity? = null

        override suspend fun get(): ConversionJobEntity? = row

        override suspend fun replace(entity: ConversionJobEntity) {
            row = entity
        }

        override suspend fun transition(
            token: String,
            stage: String,
            approvedAdapterIdsJson: String?,
            profileSha256: String?,
            bundleFileName: String?,
            updatedAtEpochMillis: Long,
        ): Int {
            val current = row
            if (current == null || current.token != token) return 0
            row = current.copy(
                stage = stage,
                approvedAdapterIdsJson = approvedAdapterIdsJson,
                profileSha256 = profileSha256,
                bundleFileName = bundleFileName,
                updatedAtEpochMillis = updatedAtEpochMillis,
            )
            return 1
        }

        override suspend fun delete(token: String): Int {
            if (row?.token != token) return 0
            row = null
            return 1
        }
    }

    private companion object {
        val PROFILE_SHA = "a".repeat(64)
    }
}
