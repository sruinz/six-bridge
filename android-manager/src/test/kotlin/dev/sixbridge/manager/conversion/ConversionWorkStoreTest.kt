package dev.sixbridge.manager.conversion

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    private lateinit var identityDao: FakeOwnerIdentityDao
    private var now = 1_800_000_000_000L
    private var tokenSequence = 0
    private var fileSequence = 0

    @Before
    fun setUp() {
        root = createTempDirectory("conversion-work-store").toFile()
        sourceRoot = createTempDirectory("conversion-input-source").toFile()
        dao = FakeConversionJobDao()
        identityDao = FakeOwnerIdentityDao(dao)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
        sourceRoot.deleteRecursively()
    }

    @Test
    fun `create는 외부 경로 없이 private 입력을 인수하고 verified bytes로만 소비시킨다`() =
        runTest {
            val bytes = "private input".encodeToByteArray()
            val artifact = artifact(bytes)
            val sourcePath = artifact.privateFile.absolutePath
            val store = store()

            val created = store.create(artifact)
            val row = requireNotNull(dao.get())
            val consumed = store.openVerifiedInput(created.token, InputStreamReader::read)

            assertEquals(ConversionJobStage.INPUT_READY, created.stage)
            assertArrayEquals(bytes, consumed)
            assertFalse(row.toString().contains(sourcePath))
            assertFalse(row.toString().contains("content:"))
            assertTrue(privateInput(row).isFile)
        }

    @Test
    fun `검증된 private 입력을 production Path 경계로 제공하고 소비 뒤 다시 hash 검증한다`() =
        runTest {
            val bytes = "private path input".encodeToByteArray()
            val store = store()
            val created = store.create(artifact(bytes))

            val consumed = store.withVerifiedInputFile(created.token) { input ->
                assertTrue(input.isFile)
                input.readBytes()
            }

            assertArrayEquals(bytes, consumed)
        }

    @Test
    fun `입력 hash 뒤 path 교체는 caller 전에 fail closed 한다`() = runTest {
        lateinit var row: ConversionJobEntity
        val expected = "verified input".encodeToByteArray()
        var consumed = false
        val store = store(
            hooks = ConversionWorkStoreHooks(
                afterInputSnapshot = {
                    val replacement = File(sourceRoot, "replacement").apply {
                        writeText("attacker replacement")
                    }
                    Files.move(replacement.toPath(), privateInput(row).toPath(), REPLACE_EXISTING)
                },
            ),
        )
        val created = store.create(artifact(expected))
        row = requireNotNull(dao.get())

        assertFailsWith<IllegalArgumentException> {
            store.openVerifiedInput(created.token) {
                consumed = true
                it.readBytes()
            }
        }

        assertFalse(consumed)
        assertEquals("attacker replacement", privateInput(row).readText())
    }

    @Test
    fun `입력 streaming 복사 뒤 path 교체는 create 반환 전에 막는다`() = runTest {
        val store = store(
            hooks = ConversionWorkStoreHooks(
                afterInputCopied = {
                    val copied = requireNotNull(
                        root.walkTopDown().firstOrNull { it.name.endsWith(".input") },
                    )
                    val replacement = File(sourceRoot, "attacker-input").apply {
                        writeText("attacker replacement")
                    }
                    Files.move(replacement.toPath(), copied.toPath(), REPLACE_EXISTING)
                },
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            store.create(artifact("verified input".encodeToByteArray()))
        }

        val row = requireNotNull(dao.get())
        assertEquals(ConversionJobStage.INPUT_READY.name, row.stage)
        assertEquals("attacker replacement", privateInput(row).readText())
    }

    @Test
    fun `입력 복사 뒤 same inode same size 변조는 create 반환 전에 막는다`() = runTest {
        val expected = "verified input content".encodeToByteArray()
        val attacker = "attacker input content".encodeToByteArray()
        assertEquals(expected.size, attacker.size)
        val store = store(
            hooks = ConversionWorkStoreHooks(
                afterInputCopied = {
                    val copied = requireNotNull(
                        root.walkTopDown().firstOrNull { it.name.endsWith(".input") },
                    )
                    val modifiedAt = Files.getLastModifiedTime(copied.toPath())
                    FileChannel.open(copied.toPath(), WRITE).use { channel ->
                        val bytes = ByteBuffer.wrap(attacker)
                        while (bytes.hasRemaining()) channel.write(bytes)
                        channel.force(true)
                    }
                    Files.setLastModifiedTime(copied.toPath(), modifiedAt)
                },
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            store.create(artifact(expected))
        }

        assertEquals(attacker.decodeToString(), privateInput(requireNotNull(dao.get())).readText())
    }

    @Test
    fun `입력 streaming 복사 중 same inode 앞부분 변조는 create 반환 전에 막는다`() =
        runTest {
            val expected = ByteArray(192 * 1024) { index -> (index % 251).toByte() }
            var mutated = false
            val store = store(
                hooks = ConversionWorkStoreHooks(
                    afterCopyChunk = { destination ->
                        if (!mutated && destination.name.endsWith(".input")) {
                            val modifiedAt = Files.getLastModifiedTime(destination.toPath())
                            FileChannel.open(destination.toPath(), WRITE).use { attacker ->
                                val bytes = ByteBuffer.wrap(ByteArray(4096) { 0x5a })
                                while (bytes.hasRemaining()) attacker.write(bytes)
                                attacker.force(true)
                            }
                            Files.setLastModifiedTime(destination.toPath(), modifiedAt)
                            mutated = true
                        }
                    },
                ),
            )

            assertFailsWith<IllegalArgumentException> {
                store.create(artifact(expected))
            }

            assertTrue(mutated)
            assertFalse(privateInput(requireNotNull(dao.get())).readBytes().contentEquals(expected))
        }

    @Test
    fun `승인 profile과 다른 profile로 BUILDING 전환하지 않고 기존 승인을 보존한다`() =
        runTest {
            val store = store()
            val created = store.create(artifact("input".encodeToByteArray()))
            store.markAwaitingApproval(created.token, PROFILE_SHA)

            assertFailsWith<IllegalStateException> {
                store.markBuilding(created.token, setOf("adapter"), OTHER_PROFILE_SHA)
            }

            val row = requireNotNull(dao.get())
            assertEquals(ConversionJobStage.AWAITING_APPROVAL.name, row.stage)
            assertEquals(PROFILE_SHA, row.profileSha256)
        }

    @Test
    fun `서로 다른 store의 stale transition은 stage generation CAS에서 하나만 성공한다`() =
        runTest {
            val creator = store()
            val created = creator.create(artifact("input".encodeToByteArray()))
            val stores = listOf(store(), store())

            val results = coroutineScope {
                stores.mapIndexed { index, candidate ->
                    async {
                        runCatching {
                            candidate.markAwaitingApproval(
                                created.token,
                                if (index == 0) PROFILE_SHA else OTHER_PROFILE_SHA,
                            )
                        }
                    }
                }.awaitAll()
            }

            assertEquals(1, results.count(Result<RestoredConversionJob>::isSuccess))
            assertEquals(2L, requireNotNull(dao.get()).generation)
        }

    @Test
    fun `create handoff cleanup 실패는 cleanup owner를 DB에 남겨 다음 restore가 재시도한다`() =
        runTest {
            val first = store().create(artifact("first".encodeToByteArray()))
            val failing = store(
                hooks = ConversionWorkStoreHooks(
                    deleteTree = { path -> path.fileName.toString() != first.token },
                ),
            )

            assertFailsWith<IllegalStateException> {
                failing.create(artifact("second".encodeToByteArray()))
            }
            val pending = requireNotNull(dao.get())
            assertTrue(requireNotNull(decodeCleanupTokens(pending.cleanupTokensJson)).contains(first.token))
            assertTrue(File(root, first.token).exists())

            val restored = requireNotNull(store().restore())
            assertEquals(pending.token, restored.token)
            assertFalse(File(root, first.token).exists())
            assertTrue(requireNotNull(decodeCleanupTokens(requireNotNull(dao.get()).cleanupTokensJson)).isEmpty())
        }

    @Test
    fun `create handoff는 이전 identity owner를 abort한 뒤 token directory를 지운다`() =
        runTest {
            val first = store().create(artifact("first".encodeToByteArray()))
            identityDao.addReserved(first.token)

            store().create(artifact("second".encodeToByteArray()))

            assertEquals(listOf("abort:${first.token}"), identityDao.events)
            assertFalse(File(root, first.token).exists())
        }

    @Test
    fun `identity abort 실패는 cleanup token과 directory를 보존한다`() = runTest {
        val first = store().create(artifact("first".encodeToByteArray()))
        identityDao.addReserved(first.token)
        identityDao.failDeleteOwner = first.token

        assertFailsWith<IllegalStateException> {
            store().create(artifact("second".encodeToByteArray()))
        }

        val row = requireNotNull(dao.get())
        assertTrue(
            requireNotNull(decodeCleanupTokens(row.cleanupTokensJson)).contains(first.token),
        )
        assertTrue(File(root, first.token).isDirectory)
    }

    @Test
    fun `비정상 active token도 cleanup owner 삭제 실패 전에는 DB 행을 유지한다`() = runTest {
        val created = store().create(artifact("owned secret".encodeToByteArray()))
        val ownedDirectory = File(root, created.token)
        val corrupted = requireNotNull(dao.get()).copy(
            token = "not-a-safe-token",
            cleanupTokensJson = encodeCleanupTokens(setOf(created.token)),
        )
        dao.force(corrupted)
        val failing = store(
            hooks = ConversionWorkStoreHooks(
                deleteTree = { path -> path.fileName.toString() != created.token },
            ),
        )

        assertNull(failing.restore())
        val discarding = requireNotNull(dao.get())
        assertEquals(corrupted.copy(
            generation = corrupted.generation + 1,
            stage = ConversionJobStage.DISCARDING.name,
        ), discarding)
        assertTrue(ownedDirectory.isDirectory)

        assertNull(store().restore())
        assertNull(dao.get())
        assertFalse(ownedDirectory.exists())
    }

    @Test
    fun `DISCARDING claim 뒤 사망하면 외부 cleanup 전 상태를 남기고 restore가 재개한다`() =
        runTest {
            val created = store().create(artifact("discard-owned".encodeToByteArray()))
            identityDao.addReserved(created.token)
            val interrupted = store(
                hooks = ConversionWorkStoreHooks(
                    afterDiscardClaim = { error("simulated process death") },
                ),
            )

            assertFailsWith<IllegalStateException> {
                interrupted.discard(created.token)
            }

            val claimed = requireNotNull(dao.get())
            assertEquals(ConversionJobStage.DISCARDING.name, claimed.stage)
            assertTrue(File(root, created.token).isDirectory)
            assertTrue(identityDao.events.isEmpty())

            assertNull(store().restore())
            assertNull(dao.get())
            assertFalse(File(root, created.token).exists())
            assertEquals(listOf("abort:${created.token}"), identityDao.events)
        }

    @Test
    fun `DISCARDING claim은 stale BUILDING bundle publish를 차단한다`() = runTest {
        val buildStore = readyToBuildStore()
        val token = requireNotNull(dao.get()).token
        val interrupted = store(
            hooks = ConversionWorkStoreHooks(
                afterDiscardClaim = { error("simulated process death") },
            ),
        )
        assertFailsWith<IllegalStateException> {
            interrupted.discard(token)
        }
        val bundle = File(sourceRoot, "stale.six").apply { writeText("verified bundle stale") }

        assertFailsWith<IllegalStateException> {
            buildStore.markBundleReady(token, bundle, sha256(bundle.readBytes()))
        }
        assertEquals(ConversionJobStage.DISCARDING.name, dao.get()?.stage)
        assertNull(dao.get()?.bundleFileName)
    }

    @Test
    fun `decode 가능한 noncanonical cleanup 항목은 격리하고 canonical owner를 계속 정리한다`() =
        runTest {
            val store = store()
            val created = store.create(artifact("active".encodeToByteArray()))
            val ownedToken = "00000000-0000-0000-0000-000000000099"
            val ownedDirectory = File(root, ownedToken).apply {
                check(mkdir())
                File(this, "owned").writeText("owned")
            }
            val corrupted = requireNotNull(dao.get()).copy(
                cleanupTokensJson = encodeCleanupTokens(
                    listOf("not-a-canonical-token", ownedToken),
                ),
            )
            dao.force(corrupted)

            val restored = requireNotNull(store.restore())

            assertEquals(created.token, restored.token)
            assertFalse(ownedDirectory.exists())
            assertTrue(
                requireNotNull(
                    decodeCleanupTokens(requireNotNull(dao.get()).cleanupTokensJson),
                ).isEmpty(),
            )
        }

    @Test
    fun `malformed cleanup JSON은 root canonical owner로 복구하고 active 작업을 유지한다`() =
        runTest {
            val store = store()
            val created = store.create(artifact("active".encodeToByteArray()))
            val orphanToken = "00000000-0000-0000-0000-000000000098"
            val orphan = File(root, orphanToken).apply {
                check(mkdir())
                File(this, "owned").writeText("owned")
            }
            dao.force(requireNotNull(dao.get()).copy(cleanupTokensJson = "{broken"))

            val restored = requireNotNull(store.restore())

            assertEquals(created.token, restored.token)
            assertFalse(orphan.exists())
            assertTrue(
                requireNotNull(
                    decodeCleanupTokens(requireNotNull(dao.get()).cleanupTokensJson),
                ).isEmpty(),
            )
        }

    @Test
    fun `malformed cleanup 정규화는 만료 시각을 갱신해 작업을 되살리지 않는다`() = runTest {
        val store = store()
        val created = store.create(artifact("expired".encodeToByteArray()))
        dao.force(
            requireNotNull(dao.get()).copy(
                cleanupTokensJson = "{broken",
                updatedAtEpochMillis = now - ConversionWorkStore.INCOMPLETE_RETENTION_MILLIS,
            ),
        )

        assertNull(store.restore())

        assertNull(dao.get())
        assertFalse(File(root, created.token).exists())
    }

    @Test
    fun `malformed 이전 행 뒤 반복 create는 root owner를 인계하고 invalid token을 전파하지 않는다`() =
        runTest {
            val firstStore = store()
            val first = firstStore.create(artifact("first".encodeToByteArray()))
            dao.force(
                requireNotNull(dao.get()).copy(
                    token = "not-a-canonical-token",
                    cleanupTokensJson = "{broken",
                ),
            )

            val second = store().create(artifact("second".encodeToByteArray()))
            val secondRow = requireNotNull(dao.get())
            assertEquals(second.token, secondRow.token)
            assertTrue(requireNotNull(decodeCleanupTokens(secondRow.cleanupTokensJson)).isEmpty())
            assertFalse(File(root, first.token).exists())
            assertEquals(listOf(second.token), root.listFiles().orEmpty().map(File::getName))

            val third = store().create(artifact("third".encodeToByteArray()))
            assertEquals(third.token, requireNotNull(firstStore.restore()).token)
            assertEquals(listOf(third.token), root.listFiles().orEmpty().map(File::getName))
        }

    @Test
    fun `root directory 열거 실패는 새 DB claim 전에 중단한다`() = runTest {
        val store = store(
            hooks = ConversionWorkStoreHooks(
                listFiles = { directory ->
                    if (directory.canonicalFile == root.canonicalFile) null
                    else directory.listFiles()
                },
            ),
        )

        assertFailsWith<IllegalStateException> {
            store.create(artifact("input".encodeToByteArray()))
        }

        assertNull(dao.get())
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `DAO create commit 직후 cancellation은 active owner row를 잃지 않는다`() = runTest {
        val store = store(
            hooks = ConversionWorkStoreHooks(
                afterCreateClaim = { throw CancellationException("simulated death") },
            ),
        )

        assertFailsWith<CancellationException> {
            store.create(artifact("input".encodeToByteArray()))
        }

        val row = requireNotNull(dao.get())
        assertTrue(isUuid(row.token))
        assertEquals(1L, row.generation)
    }

    @Test
    fun `BUILDING crash artifact cleanup 실패는 stage를 내리지 않고 재시도 가능하게 둔다`() =
        runTest {
            val base = store()
            val created = base.create(artifact("input".encodeToByteArray()))
            base.markAwaitingApproval(created.token, PROFILE_SHA)
            base.markBuilding(created.token, emptySet(), PROFILE_SHA)
            val orphan = File(File(root, created.token), "orphan.six").apply {
                writeText("orphan")
            }
            val failing = store(
                hooks = ConversionWorkStoreHooks(
                    deleteTree = { path -> path.fileName.toString() != orphan.name },
                ),
            )

            assertNull(failing.restore())
            assertEquals(ConversionJobStage.BUILDING.name, requireNotNull(dao.get()).stage)
            assertTrue(orphan.exists())

            val recovered = requireNotNull(store().restore())
            assertEquals(ConversionJobStage.INPUT_READY, recovered.stage)
            assertFalse(orphan.exists())
        }

    @Test
    fun `coordinator 복구 조회는 BUILDING을 보존하고 명시 reset만 승인 단계로 내린다`() =
        runTest {
            val store = store()
            val created = store.create(artifact("input".encodeToByteArray()))
            store.markAwaitingApproval(created.token, PROFILE_SHA)
            store.markBuilding(created.token, setOf("adapter"), PROFILE_SHA)

            val preserved = requireNotNull(store.restorePreservingBuilding())

            assertEquals(ConversionJobStage.BUILDING, preserved.stage)
            assertEquals(ConversionJobStage.BUILDING.name, requireNotNull(dao.get()).stage)

            val reset = store.resetAwaitingApproval(created.token, PROFILE_SHA)

            assertEquals(ConversionJobStage.AWAITING_APPROVAL, reset.stage)
            assertEquals(emptySet<String>(), reset.approvedAdapterIds)
            assertEquals(PROFILE_SHA, reset.profileSha256)
        }

    @Test
    fun `BUILDING child 열거 실패는 stage와 orphan owner를 그대로 보존한다`() = runTest {
        val base = store()
        val created = base.create(artifact("input".encodeToByteArray()))
        base.markAwaitingApproval(created.token, PROFILE_SHA)
        base.markBuilding(created.token, emptySet(), PROFILE_SHA)
        val tokenDirectory = File(root, created.token)
        val orphan = File(tokenDirectory, "orphan.six").apply { writeText("orphan") }
        val failing = store(
            hooks = ConversionWorkStoreHooks(
                listFiles = { directory ->
                    if (directory.canonicalFile == tokenDirectory.canonicalFile) null
                    else directory.listFiles()
                },
            ),
        )

        assertNull(failing.restore())

        assertEquals(ConversionJobStage.BUILDING.name, requireNotNull(dao.get()).stage)
        assertTrue(orphan.isFile)
    }

    @Test
    fun `bundle source가 open 전에 다른 valid bytes로 바뀌면 expected hash가 차단한다`() =
        runTest {
            val original = File(sourceRoot, "bundle.six").apply { writeText("bundle-a") }
            val expected = sha256(original.readBytes())
            val replacement = File(sourceRoot, "replacement.six").apply { writeText("bundle-b") }
            val store = readyToBuildStore(
                hooks = ConversionWorkStoreHooks(
                    beforeBundleSourceOpen = {
                        Files.move(replacement.toPath(), original.toPath(), REPLACE_EXISTING)
                    },
                ),
            )
            val token = requireNotNull(dao.get()).token

            assertFailsWith<IllegalArgumentException> {
                store.markBundleReady(token, original, expected)
            }

            assertEquals(ConversionJobStage.BUILDING.name, requireNotNull(dao.get()).stage)
        }

    @Test
    fun `bundle publish 직후 crash는 BUILDING 복구가 모든 미참조 child를 지운다`() = runTest {
        val bundle = File(sourceRoot, "bundle.six").apply { writeText("verified bundle") }
        val store = readyToBuildStore(
            hooks = ConversionWorkStoreHooks(
                afterBundlePublished = { throw IllegalStateException("simulated crash") },
            ),
        )
        val token = requireNotNull(dao.get()).token

        assertFailsWith<IllegalStateException> {
            store.markBundleReady(token, bundle, sha256(bundle.readBytes()))
        }
        assertEquals(2, File(root, token).listFiles().orEmpty().size)

        val restored = requireNotNull(this@ConversionWorkStoreTest.store().restore())
        assertEquals(ConversionJobStage.INPUT_READY, restored.stage)
        assertEquals(1, File(root, token).listFiles().orEmpty().size)
    }

    @Test
    fun `BUNDLE_READY DB commit 뒤 cancellation은 published file과 row를 유지한다`() = runTest {
        val bundle = File(sourceRoot, "bundle.six").apply { writeText("verified bundle") }
        val expected = sha256(bundle.readBytes())
        val store = readyToBuildStore(
            hooks = ConversionWorkStoreHooks(
                afterBundleTransition = { throw CancellationException("simulated cancellation") },
            ),
        )
        val token = requireNotNull(dao.get()).token

        assertFailsWith<CancellationException> {
            store.markBundleReady(token, bundle, expected)
        }

        val row = requireNotNull(dao.get())
        assertEquals(ConversionJobStage.BUNDLE_READY.name, row.stage)
        assertEquals(expected, row.bundleSha256)
        assertTrue(privateBundle(row).isFile)
        val restored = requireNotNull(this@ConversionWorkStoreTest.store().restore())
        assertEquals(ConversionJobStage.BUNDLE_READY, restored.stage)
        assertEquals(expected, restored.bundleSha256)
    }

    @Test
    fun `identity lease finalize는 durable BUNDLE_READY 뒤에만 호출한다`() = runTest {
        val store = store()
        val created = store.create(artifact("input".encodeToByteArray()))
        identityDao.addPackaged(created.token)
        store.markAwaitingApproval(created.token, PROFILE_SHA)
        store.markBuilding(created.token, emptySet(), PROFILE_SHA)
        assertTrue(identityDao.events.isEmpty())
        val bundle = File(sourceRoot, "owned-ready.six").apply {
            writeText("verified bundle identity lifecycle")
        }
        val expected = sha256(bundle.readBytes())

        store.markBundleReady(created.token, bundle, expected)

        assertEquals(listOf("finalize:${created.token}:$expected"), identityDao.events)
    }

    @Test
    fun `verified bundle open은 DB hash에 묶인 bytes를 반환한다`() = runTest {
        val bundle = File(sourceRoot, "bundle.six").apply { writeText("verified bundle") }
        val expected = bundle.readBytes()
        val store = readyToBuildStore()
        val token = requireNotNull(dao.get()).token
        store.markBundleReady(token, bundle, sha256(expected))

        val consumed = store.openVerifiedBundle(token, InputStreamReader::read)

        assertArrayEquals(expected, consumed)
    }

    @Test
    fun `bundle verifier 뒤 path 교체는 caller 전에 막고 verifier는 no-follow handle만 읽는다`() =
        runTest {
            var swapAfterVerification = false
            var verified = byteArrayOf()
            var consumed = false
            lateinit var row: ConversionJobEntity
            val store = store(
                hooks = ConversionWorkStoreHooks(
                    afterBundleVerified = {
                        if (swapAfterVerification) {
                            val replacement = File(sourceRoot, "attacker-bundle").apply {
                                writeText("attacker bundle")
                            }
                            Files.move(
                                replacement.toPath(),
                                privateBundle(row).toPath(),
                                REPLACE_EXISTING,
                            )
                        }
                    },
                ),
                bundleVerifier = ConversionBundleVerifier { input ->
                    assertTrue(input.markSupported().not())
                    verified = input.readBytes()
                    verified.decodeToString().startsWith("verified bundle")
                },
            )
            val created = store.create(artifact("input".encodeToByteArray()))
            store.markAwaitingApproval(created.token, PROFILE_SHA)
            store.markBuilding(created.token, emptySet(), PROFILE_SHA)
            val bundle = File(sourceRoot, "bundle.six").apply {
                writeText("verified bundle from stable handle")
            }
            store.markBundleReady(created.token, bundle, sha256(bundle.readBytes()))
            row = requireNotNull(dao.get())
            swapAfterVerification = true

            assertFailsWith<IllegalArgumentException> {
                store.openVerifiedBundle(created.token) {
                    consumed = true
                    it.readBytes()
                }
            }

            assertEquals("verified bundle from stable handle", verified.decodeToString())
            assertFalse(consumed)
        }

    @Test
    fun `bundle verifier 뒤 임시 path 교체는 publish와 DB 전환 전에 막는다`() = runTest {
        var replaceTemporary = false
        val store = store(
            hooks = ConversionWorkStoreHooks(
                afterBundleVerified = {
                    if (replaceTemporary) {
                        val temporary = requireNotNull(
                            root.walkTopDown().firstOrNull { it.name.endsWith(".tmp") },
                        )
                        val replacement = File(sourceRoot, "attacker-temporary").apply {
                            writeText("attacker bundle")
                        }
                        Files.move(
                            replacement.toPath(),
                            temporary.toPath(),
                            REPLACE_EXISTING,
                        )
                    }
                },
            ),
        )
        val created = store.create(artifact("input".encodeToByteArray()))
        store.markAwaitingApproval(created.token, PROFILE_SHA)
        store.markBuilding(created.token, emptySet(), PROFILE_SHA)
        val bundle = File(sourceRoot, "bundle.six").apply {
            writeText("verified bundle before publish")
        }
        replaceTemporary = true

        assertFailsWith<IllegalArgumentException> {
            store.markBundleReady(created.token, bundle, sha256(bundle.readBytes()))
        }

        assertEquals(ConversionJobStage.BUILDING.name, requireNotNull(dao.get()).stage)
        assertFalse(File(root, created.token).listFiles().orEmpty().any {
            it.name.endsWith(".six")
        })
    }

    @Test
    fun `128 MiB 입력은 전체 ByteArray 없이 streaming 인수하고 소비한다`() = runTest {
        val baseline = usedHeap()
        var heapAtCopy = Long.MAX_VALUE
        val artifact = largeZeroArtifact(OnDeviceInputService.MAX_INPUT_BYTES)
        val store = store(
            hooks = ConversionWorkStoreHooks(
                afterInputCopied = { heapAtCopy = usedHeap() },
            ),
        )

        val created = store.create(artifact)
        val consumed = store.openVerifiedInput(created.token) { input ->
            streamingDigest(input)
        }

        assertEquals(artifact.sha256, consumed.first)
        assertEquals(OnDeviceInputService.MAX_INPUT_BYTES, consumed.second)
        assertTrue(
            "streaming copy 중 heap 증가가 64 MiB를 넘었습니다: ${heapAtCopy - baseline}",
            heapAtCopy - baseline < 64L * 1024 * 1024,
        )
    }

    @Test
    fun `hard link input과 bundle은 private 경계에 들어오지 못한다`() = runTest {
        val artifact = artifact("input".encodeToByteArray())
        val artifactAlias = File(sourceRoot, "artifact-alias")
        Files.createLink(artifactAlias.toPath(), artifact.privateFile.toPath())
        assertFailsWith<IllegalArgumentException> {
            store().create(artifact)
        }

        artifactAlias.delete()
        val buildStore = readyToBuildStore()
        val bundle = File(sourceRoot, "bundle.six").apply { writeText("verified bundle") }
        val bundleAlias = File(sourceRoot, "bundle-alias.six")
        Files.createLink(bundleAlias.toPath(), bundle.toPath())
        assertFailsWith<IllegalArgumentException> {
            buildStore.markBundleReady(
                requireNotNull(dao.get()).token,
                bundle,
                sha256(bundle.readBytes()),
            )
        }
    }

    @Test
    fun `private input 또는 bundle에 hard link가 생기면 restore가 fail closed 한다`() = runTest {
        val inputStore = store()
        inputStore.create(artifact("input".encodeToByteArray()))
        var row = requireNotNull(dao.get())
        Files.createLink(File(sourceRoot, "input-leak").toPath(), privateInput(row).toPath())
        assertNull(inputStore.restore())

        dao = FakeConversionJobDao()
        identityDao = FakeOwnerIdentityDao(dao)
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        val bundleStore = readyToBuildStore()
        val bundle = File(sourceRoot, "bundle.six").apply { writeText("verified bundle") }
        bundleStore.markBundleReady(
            requireNotNull(dao.get()).token,
            bundle,
            sha256(bundle.readBytes()),
        )
        row = requireNotNull(dao.get())
        Files.createLink(File(sourceRoot, "bundle-leak").toPath(), privateBundle(row).toPath())
        assertNull(bundleStore.restore())
    }

    @Test
    fun `delete 실패는 discard와 invalid restore에서 owner row를 보존한다`() = runTest {
        val base = store()
        val created = base.create(artifact("input".encodeToByteArray()))
        val failing = store(
            hooks = ConversionWorkStoreHooks(deleteTree = { false }),
        )

        assertFalse(failing.discard(created.token))
        assertNotNull(dao.get())
        privateInput(requireNotNull(dao.get())).apply {
            setWritable(true)
            writeText("tampered")
        }
        assertNull(failing.restore())
        assertNotNull(dao.get())
    }

    @Test
    fun `미래 음수 retention 경계와 작은 clock rollback을 구분한다`() = runTest {
        val base = store()
        val created = base.create(artifact("input".encodeToByteArray()))
        val original = requireNotNull(dao.get())

        dao.force(original.copy(updatedAtEpochMillis = now + 1))
        assertEquals(created.token, requireNotNull(base.restore()).token)

        dao.force(requireNotNull(dao.get()).copy(updatedAtEpochMillis = Long.MAX_VALUE))
        assertNull(base.restore())
        assertNull(dao.get())

        val second = store().create(artifact("second".encodeToByteArray()))
        dao.force(requireNotNull(dao.get()).copy(updatedAtEpochMillis = -1))
        assertNull(store().restore())
        assertNull(dao.get())

        val third = store().create(artifact("third".encodeToByteArray()))
        dao.force(
            requireNotNull(dao.get()).copy(
                updatedAtEpochMillis = now - ConversionWorkStore.INCOMPLETE_RETENTION_MILLIS,
            ),
        )
        assertNull(store().restore())
        assertNull(dao.get())
        assertTrue(second.token != third.token)
    }

    private suspend fun readyToBuildStore(
        hooks: ConversionWorkStoreHooks = ConversionWorkStoreHooks(),
    ): ConversionWorkStore {
        val store = store(hooks = hooks)
        val created = store.create(artifact("input".encodeToByteArray()))
        store.markAwaitingApproval(created.token, PROFILE_SHA)
        store.markBuilding(created.token, emptySet(), PROFILE_SHA)
        return store
    }

    private fun store(
        hooks: ConversionWorkStoreHooks = ConversionWorkStoreHooks(),
        bundleVerifier: ConversionBundleVerifier = ConversionBundleVerifier { input ->
            input.bufferedReader().readText().startsWith("verified bundle")
        },
    ) = ConversionWorkStore(
        privateRoot = root,
        dao = dao,
        bundleVerifier = bundleVerifier,
        identityOwnerLifecycle =
            ConversionIdentityLedger(identityDao).ownerLifecycleForReservationTests(),
        clock = { now },
        tokenFactory = {
            tokenSequence += 1
            UUID(0, tokenSequence.toLong()).toString()
        },
        fileNameFactory = {
            fileSequence += 1
            UUID(1, fileSequence.toLong()).toString()
        },
        hooks = hooks,
    )

    private fun artifact(bytes: ByteArray): OnDeviceInputArtifact {
        val token = UUID.randomUUID().toString()
        val directory = File(sourceRoot, token).apply { mkdir() }
        val input = File(directory, "input.bin").apply { writeBytes(bytes) }
        return OnDeviceInputArtifact(
            token = token,
            kind = OnDeviceInputKind.ZIP,
            sha256 = sha256(bytes),
            privateFile = input,
            expectedExtensionId = null,
        )
    }

    private fun largeZeroArtifact(size: Long): OnDeviceInputArtifact {
        val token = UUID.randomUUID().toString()
        val directory = File(sourceRoot, token).apply { check(mkdir()) }
        val input = File(directory, "input.bin")
        val digest = MessageDigest.getInstance("SHA-256")
        val block = ByteArray(64 * 1024)
        FileChannel.open(input.toPath(), CREATE_NEW, WRITE).use { channel ->
            var remaining = size
            while (remaining > 0) {
                val count = minOf(block.size.toLong(), remaining).toInt()
                val buffer = ByteBuffer.wrap(block, 0, count)
                while (buffer.hasRemaining()) channel.write(buffer)
                digest.update(block, 0, count)
                remaining -= count
            }
            channel.force(true)
        }
        return OnDeviceInputArtifact(
            token = token,
            kind = OnDeviceInputKind.ZIP,
            privateFile = input,
            sha256 = digest.digest().toHex(),
            expectedExtensionId = null,
        )
    }

    private fun streamingDigest(input: InputStream): Pair<String, Long> {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            total += count
        }
        return digest.digest().toHex() to total
    }

    private fun usedHeap(): Long =
        Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun privateInput(row: ConversionJobEntity): File =
        File(File(root, row.token), row.inputFileName)

    private fun privateBundle(row: ConversionJobEntity): File =
        File(File(root, row.token), requireNotNull(row.bundleFileName))

    private class FakeOwnerIdentityDao(
        private val jobDao: ConversionJobDao,
    ) : ConversionIdentityDao {
        private val identities = linkedMapOf<String, ConversionIdentityEntity>()
        private val leases = linkedMapOf<String, ConversionIdentityLeaseEntity>()
        val events = mutableListOf<String>()
        var failDeleteOwner: String? = null

        fun addReserved(ownerToken: String) =
            addLease(ownerToken, ConversionIdentityLeaseState.RESERVED.name)

        fun addPackaged(ownerToken: String) = addLease(
            ownerToken,
            "PACKAGED:11111111-1111-4111-8111-111111111111:" +
                "${"c".repeat(64)}:777261707065722e61706b",
        )

        private fun addLease(ownerToken: String, state: String) {
            val identitySha256 = MessageDigest.getInstance("SHA-256")
                .digest(ownerToken.encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            leases[identitySha256] = ConversionIdentityLeaseEntity(
                identitySha256 = identitySha256,
                crxKeyAlias = null,
                ownerToken = ownerToken,
                state = state,
                createdAtEpochMillis = 1,
                extensionId = "a".repeat(32),
                packageName = "dev.sixbridge.ext.${"a".repeat(32)}",
                wrapperSignerSha256 = "b".repeat(64),
                crxPublicKeySha256 = null,
                reservedVersionCode = 1,
            )
        }

        override suspend fun get(identitySha256: String) = identities[identitySha256]
        override suspend fun findByExtensionId(extensionId: String) =
            identities.values.firstOrNull { it.extensionId == extensionId }
        override suspend fun findByPackageName(packageName: String) =
            identities.values.firstOrNull { it.packageName == packageName }
        override suspend fun findByCrxKeyAlias(crxKeyAlias: String) =
            identities.values.firstOrNull { it.crxKeyAlias == crxKeyAlias }
        override suspend fun insert(entity: ConversionIdentityEntity) {
            identities[entity.identitySha256] = entity
        }
        override suspend fun update(entity: ConversionIdentityEntity) {
            identities[entity.identitySha256] = entity
        }
        override suspend fun getLease(identitySha256: String) = leases[identitySha256]
        override suspend fun getLeaseByOwnerToken(ownerToken: String) =
            leases.values.singleOrNull { it.ownerToken == ownerToken }
        override suspend fun getJobForIdentityFinalization() = jobDao.get()
        override suspend fun insertLease(entity: ConversionIdentityLeaseEntity) {
            leases[entity.identitySha256] = entity
        }
        override suspend fun markLeaseReady(
            identitySha256: String,
            ownerToken: String,
            extensionId: String,
            packageName: String,
            wrapperSignerSha256: String,
            crxPublicKeySha256: String,
        ): Int = 0
        override suspend fun transitionLease(
            identitySha256: String,
            ownerToken: String,
            expectedState: String,
            nextState: String,
        ): Int {
            val current = leases[identitySha256]
            if (current?.ownerToken != ownerToken || current.state != expectedState) return 0
            leases[identitySha256] = current.copy(state = nextState)
            return 1
        }
        override suspend fun consumeReadyLease(
            identitySha256: String,
            readyOwnerToken: String,
            reservationOwnerToken: String,
            reservedVersionCode: Int,
        ): Int = 0
        override suspend fun deleteLease(
            identitySha256: String,
            ownerToken: String,
            state: String,
        ): Int {
            if (failDeleteOwner == ownerToken) error("abort failure")
            val current = leases[identitySha256]
            if (current?.ownerToken != ownerToken || current.state != state) return 0
            leases.remove(identitySha256)
            events += if (state.startsWith("PACKAGED:")) {
                "finalize:$ownerToken:${jobDao.get()?.bundleSha256}"
            } else {
                "abort:$ownerToken"
            }
            return 1
        }
        override suspend fun listByModeAndProfile(
            mode: String,
            profileMajorVersion: Int,
        ): List<ConversionIdentityEntity> = emptyList()
    }

    private class FakeConversionJobDao : ConversionJobDao() {
        private var row: ConversionJobEntity? = null

        override suspend fun get(): ConversionJobEntity? = synchronized(this) { row }

        protected override suspend fun replaceSingletonInternal(entity: ConversionJobEntity) {
            synchronized(this) { row = entity.copy(singletonId = 1) }
        }

        override suspend fun claimCreate(candidate: ConversionJobEntity): ConversionJobEntity =
            synchronized(this) {
                val previous = row
                val cleanup = buildSet {
                    addAll(
                        requireNotNull(decodeCleanupTokens(candidate.cleanupTokensJson))
                            .filter(::isUuid),
                    )
                    previous?.let {
                        decodeCleanupTokens(it.cleanupTokensJson)
                            ?.filter(::isUuid)
                            ?.let(::addAll)
                        if (isUuid(it.token)) add(it.token)
                    }
                    remove(candidate.token)
                }
                candidate.copy(
                    singletonId = 1,
                    generation = (previous?.generation ?: 0) + 1,
                    cleanupTokensJson = encodeCleanupTokens(cleanup),
                ).also { row = it }
            }

        override suspend fun transition(
            token: String,
            expectedStage: String,
            expectedGeneration: Long,
            expectedProfileSha256: String?,
            nextStage: String,
            approvedAdapterIdsJson: String?,
            profileSha256: String?,
            bundleFileName: String?,
            bundleSha256: String?,
            updatedAtEpochMillis: Long,
        ): Int = synchronized(this) {
            val current = row ?: return@synchronized 0
            if (current.token != token ||
                current.stage != expectedStage ||
                current.generation != expectedGeneration ||
                expectedProfileSha256 != null &&
                current.profileSha256 != expectedProfileSha256
            ) {
                return@synchronized 0
            }
            row = current.copy(
                stage = nextStage,
                approvedAdapterIdsJson = approvedAdapterIdsJson,
                profileSha256 = profileSha256,
                bundleFileName = bundleFileName,
                bundleSha256 = bundleSha256,
                updatedAtEpochMillis = updatedAtEpochMillis,
                generation = current.generation + 1,
            )
            1
        }

        override suspend fun updateCleanupTokens(
            token: String,
            expectedGeneration: Long,
            cleanupTokensJson: String,
            updatedAtEpochMillis: Long,
        ): Int = synchronized(this) {
            val current = row ?: return@synchronized 0
            if (current.token != token || current.generation != expectedGeneration) {
                return@synchronized 0
            }
            row = current.copy(
                cleanupTokensJson = cleanupTokensJson,
                updatedAtEpochMillis = updatedAtEpochMillis,
                generation = current.generation + 1,
            )
            1
        }

        override suspend fun delete(token: String, expectedGeneration: Long): Int =
            synchronized(this) {
                val current = row ?: return@synchronized 0
                if (current.token != token || current.generation != expectedGeneration) {
                    return@synchronized 0
                }
                row = null
                1
            }

        fun force(entity: ConversionJobEntity) {
            synchronized(this) { row = entity }
        }
    }

    private fun interface InputStreamReader {
        fun read(input: java.io.InputStream): ByteArray

        companion object {
            fun read(input: java.io.InputStream): ByteArray = input.readBytes()
        }
    }

    private companion object {
        const val PROFILE_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_PROFILE_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        fun isUuid(value: String): Boolean =
            runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }
