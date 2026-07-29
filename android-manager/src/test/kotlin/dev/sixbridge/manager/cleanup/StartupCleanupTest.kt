package dev.sixbridge.manager.cleanup

import android.content.Intent
import dev.sixbridge.manager.install.PackageInstallGateway
import dev.sixbridge.manager.install.FinalInstallReceipt
import dev.sixbridge.manager.install.FinalReceiptOrigin
import dev.sixbridge.manager.install.FinalReceiptStage
import dev.sixbridge.manager.install.ExpectedInstalledWrapper
import dev.sixbridge.manager.install.InstallSessionSnapshot
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.install.NormalHandoffStage
import dev.sixbridge.manager.install.NormalInstallHandoff
import dev.sixbridge.manager.install.PackageInstallResult
import dev.sixbridge.manager.install.PendingCleanupResult
import dev.sixbridge.manager.install.PendingInstall
import dev.sixbridge.manager.install.PendingInstallResult
import dev.sixbridge.manager.install.PendingInstallStore
import dev.sixbridge.manager.install.RecoveryRecordSnapshot
import dev.sixbridge.manager.install.PendingSystemInstall
import dev.sixbridge.manager.install.SystemInstallHandoffStore
import dev.sixbridge.manager.install.SystemInstallStage
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupCleanupTest {
    private val root = Files.createTempDirectory("startup-cleanup-").toFile()
    private val imports = File(root, "imports").apply { mkdirs() }
    private val now = 200_000_000L

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `deletes only expired token directories and preserves recent review import`() = runBlocking {
        val expired = tokenDirectory(EXPIRED_TOKEN, now - DAY_MILLIS - 1)
        val recent = tokenDirectory(RECENT_TOKEN, now - DAY_MILLIS)

        cleanup().run()

        assertFalse(expired.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun `does not follow an import directory symlink outside cache imports`() = runBlocking {
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "keep.txt").writeText("keep")
        val link = File(imports, EXPIRED_TOKEN)
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        link.setLastModified(now - DAY_MILLIS - 1)

        cleanup().run()

        assertTrue(outside.exists())
        assertTrue(File(outside, "keep.txt").exists())
        assertTrue(link.exists())
    }

    @Test
    fun `does not traverse an import root symlink outside cache imports`() = runBlocking {
        val outsideImports = File(root, "outside-imports").apply { mkdirs() }
        val expired = File(outsideImports, EXPIRED_TOKEN).apply {
            mkdirs()
            setLastModified(now - DAY_MILLIS - 1)
        }
        val linkedImports = File(root, "linked-imports")
        Files.createSymbolicLink(linkedImports.toPath(), outsideImports.toPath())

        cleanup(importRoot = linkedImports).run()

        assertTrue(expired.exists())
    }

    @Test
    fun `preserves pending import and its owned session for durable recovery`() = runBlocking {
        val pending = pending(sessionId = 41, startedAt = now - DAY_MILLIS - 1)
        val store = FakePendingStore(record = pending)
        val pendingDirectory = tokenDirectory(pending.importToken, now - DAY_MILLIS - 1)
        val gateway = FakeGateway(owned = mutableListOf(41))

        cleanup(gateway = gateway, store = store).run()

        assertTrue(pendingDirectory.exists())
        assertEquals(pending, store.record)
        assertTrue(gateway.abandoned.isEmpty())
    }

    @Test
    fun `clears an expired orphan pending only when no terminal result exists`() = runBlocking {
        val pending = pending(sessionId = 41, startedAt = now - DAY_MILLIS - 1)
        val store = FakePendingStore(
            record = pending,
            missingObservedAt = now - DAY_MILLIS - 1,
        )
        val pendingDirectory = tokenDirectory(pending.importToken, now - DAY_MILLIS - 1)

        cleanup(store = store).run()

        assertNull(store.record)
        assertFalse(pendingDirectory.exists())
    }

    @Test
    fun `preserves durable terminal result instead of clearing missing pending session`() = runBlocking {
        val pending = pending(sessionId = 41, startedAt = now - DAY_MILLIS - 1)
        val terminal = PendingInstallResult(
            result = PackageInstallResult.Success(41),
            terminalAtEpochMillis = now,
            packageName = PACKAGE_NAME,
            importToken = pending.importToken,
        )
        val store = FakePendingStore(record = pending, terminal = terminal)
        val pendingDirectory = tokenDirectory(pending.importToken, now - DAY_MILLIS - 1)

        cleanup(store = store).run()

        assertEquals(pending, store.record)
        assertEquals(terminal, store.terminal)
        assertTrue(pendingDirectory.exists())
    }

    @Test
    fun `pending 없는 durable terminal의 immutable import token을 startup cleanup에서 보호한다`() =
        runBlocking {
            val terminal = PendingInstallResult(
                result = PackageInstallResult.Cancelled(41),
                terminalAtEpochMillis = now,
                packageName = PACKAGE_NAME,
                importToken = EXPIRED_TOKEN,
            )
            val store = FakePendingStore(terminal = terminal)
            val terminalImport = tokenDirectory(EXPIRED_TOKEN, now - DAY_MILLIS - 1)

            cleanup(store = store).run()

            assertTrue(terminalImport.exists())
            assertEquals(terminal, store.terminal)
        }

    @Test
    fun `session 조회 실패에도 pending 없는 durable terminal import를 보호한다`() =
        runBlocking {
            val terminal = PendingInstallResult(
                result = PackageInstallResult.Cancelled(41),
                terminalAtEpochMillis = now,
                packageName = PACKAGE_NAME,
                importToken = EXPIRED_TOKEN,
            )
            val store = FakePendingStore(terminal = terminal)
            val terminalImport = tokenDirectory(EXPIRED_TOKEN, now - DAY_MILLIS - 1)
            val gateway = FakeGateway(
                ownedFailure = IllegalStateException("session query failed"),
            )

            cleanup(gateway = gateway, store = store).run()

            assertTrue(terminalImport.exists())
            assertEquals(terminal, store.terminal)
        }

    @Test
    fun `startup cleanup은 final handoff pending raw의 모든 import token을 보호한다`() =
        runBlocking {
            val pending = PendingInstall(41, PACKAGE_NAME, EXPIRED_TOKEN, now)
            val terminal = PendingInstallResult(
                result = PackageInstallResult.Cancelled(42),
                terminalAtEpochMillis = now,
                packageName = PACKAGE_NAME,
                importToken = OTHER_TOKEN,
            )
            val finalReceipt = FinalInstallReceipt(
                importToken = FINAL_TOKEN,
                sessionId = null,
                packageName = PACKAGE_NAME,
                state = InstallState.Cancelled,
                stage = FinalReceiptStage.READY,
                origin = FinalReceiptOrigin.PROGRAM_CANCEL,
                rawTerminalAtEpochMillis = null,
                terminalAtEpochMillis = now,
            )
            val handoff = NormalInstallHandoff(
                importToken = HANDOFF_TOKEN,
                sessionId = null,
                packageName = PACKAGE_NAME,
                state = InstallState.Cancelled,
                stage = NormalHandoffStage.OUTCOME_READY,
            )
            val store = FakePendingStore(
                record = pending,
                terminal = terminal,
                finalReceipt = finalReceipt,
                normalHandoff = handoff,
            )
            val protected = listOf(EXPIRED_TOKEN, OTHER_TOKEN, FINAL_TOKEN, HANDOFF_TOKEN)
                .map { tokenDirectory(it, now - DAY_MILLIS - 1) }
            val unprotected = tokenDirectory(UNPROTECTED_TOKEN, now - DAY_MILLIS - 1)

            cleanup(store = store).run()

            assertTrue(protected.all(File::exists))
            assertFalse(unprotected.exists())
        }

    @Test
    fun `legacy PREPARED와 LAUNCHED는 import를 보존하고 record만 CAS 정리한다`() =
        runBlocking {
            listOf(
                SystemInstallStage.PREPARED,
                SystemInstallStage.LAUNCHED,
            ).forEach { stage ->
                val handoff = systemInstallHandoff(stage)
                val handoffStore = FakeSystemInstallHandoffStore(handoff)
                val handoffImport = tokenDirectory(HANDOFF_TOKEN, now - DAY_MILLIS - 1)

                cleanup(systemInstallStore = handoffStore).run()

                assertTrue(handoffImport.exists())
                assertEquals(RecoveryRecordSnapshot.Absent, handoffStore.snapshot())
                assertEquals(1, handoffStore.mutationCalls)
                handoffImport.deleteRecursively()
            }
        }

    @Test
    fun `legacy EXPORTED는 수동 설치 확인을 위해 record와 import를 보존한다`() =
        runBlocking {
            val handoff = systemInstallHandoff(SystemInstallStage.EXPORTED)
            val handoffStore = FakeSystemInstallHandoffStore(handoff)
            val handoffImport = tokenDirectory(HANDOFF_TOKEN, now - DAY_MILLIS - 1)

            cleanup(systemInstallStore = handoffStore).run()

            assertTrue(handoffImport.exists())
            assertEquals(
                RecoveryRecordSnapshot.Present(handoff),
                handoffStore.snapshot(),
            )
            assertEquals(0, handoffStore.mutationCalls)
        }

    @Test
    fun `손상된 system handoff marker가 있으면 startup cleanup은 아무 소유권도 정리하지 않는다`() =
        runBlocking {
            val expired = tokenDirectory(EXPIRED_TOKEN, now - DAY_MILLIS - 1)
            val gateway = FakeGateway(owned = mutableListOf(41))
            val handoffStore = FakeSystemInstallHandoffStore(
                snapshotOverride = RecoveryRecordSnapshot.Invalid,
            )

            cleanup(gateway = gateway, systemInstallStore = handoffStore).run()

            assertTrue(expired.exists())
            assertTrue(gateway.abandoned.isEmpty())
            assertEquals(0, handoffStore.mutationCalls)
        }

    @Test
    fun `손상된 final 또는 normal handoff marker가 있으면 startup cleanup은 아무 소유권도 정리하지 않는다`() =
        runBlocking {
            listOf(
                RecoveryRecordSnapshot.Invalid to RecoveryRecordSnapshot.Absent,
                RecoveryRecordSnapshot.Absent to RecoveryRecordSnapshot.Invalid,
            ).forEach { (finalSnapshot, handoffSnapshot) ->
                val expired = tokenDirectory(EXPIRED_TOKEN, now - DAY_MILLIS - 1)
                val gateway = FakeGateway(owned = mutableListOf(41))
                val store = FakePendingStore(
                    finalSnapshot = finalSnapshot,
                    normalHandoffSnapshot = handoffSnapshot,
                )

                cleanup(gateway = gateway, store = store).run()

                assertTrue(expired.exists())
                assertTrue(gateway.abandoned.isEmpty())
                expired.deleteRecursively()
            }
        }

    @Test
    fun `손상된 pending 또는 raw marker가 있으면 startup cleanup은 아무 소유권도 정리하지 않는다`() =
        runBlocking {
            listOf(
                RecoveryRecordSnapshot.Invalid to RecoveryRecordSnapshot.Absent,
                RecoveryRecordSnapshot.Absent to RecoveryRecordSnapshot.Invalid,
            ).forEach { (pendingSnapshot, terminalSnapshot) ->
                val expired = tokenDirectory(EXPIRED_TOKEN, now - DAY_MILLIS - 1)
                val gateway = FakeGateway(owned = mutableListOf(41))
                val store = FakePendingStore(
                    pendingSnapshot = pendingSnapshot,
                    terminalSnapshot = terminalSnapshot,
                )

                cleanup(gateway = gateway, store = store).run()

                assertTrue(expired.exists())
                assertTrue(gateway.abandoned.isEmpty())
                expired.deleteRecursively()
            }
        }

    @Test
    fun `abandons only owned sessions that are not the recoverable pending session`() = runBlocking {
        val pending = pending(sessionId = 41, startedAt = now)
        val gateway = FakeGateway(owned = mutableListOf(41, 77))

        cleanup(gateway = gateway, store = FakePendingStore(record = pending)).run()

        assertEquals(listOf(77), gateway.abandoned)
        assertNotNull(gateway.owned.singleOrNull { it == 41 })
    }

    @Test
    fun `cancellation while loading pending leaves terminal session and files untouched`() = runBlocking {
        val expired = tokenDirectory(EXPIRED_TOKEN, now - DAY_MILLIS - 1)
        val store = FakePendingStore(loadFailure = CancellationException("pending cancelled"))
        val gateway = FakeGateway(owned = mutableListOf(77))

        assertFailsWith<CancellationException> { cleanup(gateway = gateway, store = store).run() }

        assertEquals(0, store.terminalLoads)
        assertEquals(0, gateway.ownedRequests)
        assertTrue(expired.exists())
        assertTrue(gateway.abandoned.isEmpty())
    }

    @Test
    fun `cancellation while loading terminal result leaves pending session and files untouched`() = runBlocking {
        val pending = pending(sessionId = 41, startedAt = now)
        val expired = tokenDirectory(OTHER_TOKEN, now - DAY_MILLIS - 1)
        val store = FakePendingStore(record = pending, terminalLoadFailure = CancellationException("terminal cancelled"))
        val gateway = FakeGateway(owned = mutableListOf(41, 77))

        assertFailsWith<CancellationException> { cleanup(gateway = gateway, store = store).run() }

        assertEquals(pending, store.record)
        assertEquals(0, gateway.ownedRequests)
        assertTrue(expired.exists())
        assertTrue(gateway.abandoned.isEmpty())
    }

    @Test
    fun `cancellation while checking orphan pending leaves durable state untouched`() = runBlocking {
        val pending = pending(sessionId = 41, startedAt = now - DAY_MILLIS - 1)
        val pendingDirectory = tokenDirectory(pending.importToken, now - DAY_MILLIS - 1)
        val store = FakePendingStore(
            record = pending,
            missingObservationFailure = CancellationException("missing observation cancelled"),
        )

        assertFailsWith<CancellationException> { cleanup(store = store).run() }

        assertEquals(pending, store.record)
        assertTrue(pendingDirectory.exists())
        assertEquals(0, store.clearWithoutTerminalCalls)
    }

    @Test
    fun `cancellation while querying owned sessions does not start file cleanup`() = runBlocking {
        val expired = tokenDirectory(EXPIRED_TOKEN, now - DAY_MILLIS - 1)
        val gateway = FakeGateway(ownedFailure = CancellationException("owned sessions cancelled"))

        assertFailsWith<CancellationException> { cleanup(gateway = gateway).run() }

        assertTrue(expired.exists())
        assertTrue(gateway.abandoned.isEmpty())
    }

    @Test
    fun `cancellation while abandoning a session stops later session and file cleanup`() = runBlocking {
        val pending = pending(sessionId = 41, startedAt = now)
        val expired = tokenDirectory(OTHER_TOKEN, now - DAY_MILLIS - 1)
        val gateway = FakeGateway(
            owned = mutableListOf(41, 77, 78),
            abandonFailure = CancellationException("abandon cancelled"),
        )

        assertFailsWith<CancellationException> {
            cleanup(gateway = gateway, store = FakePendingStore(record = pending)).run()
        }

        assertEquals(listOf(77), gateway.abandoned)
        assertTrue(gateway.owned.contains(78))
        assertTrue(expired.exists())
    }

    @Test
    fun `cancellation before expired file deletion leaves directory untouched`() = runBlocking {
        val expired = tokenDirectory(EXPIRED_TOKEN, now - DAY_MILLIS - 1)
        lateinit var cleanupJob: Job
        val cancellingCandidate = CancellingTimestampFile(expired.path) { cleanupJob.cancel() }
        val cancellingRoot = ListedImportRoot(imports.canonicalPath, cancellingCandidate)
        cleanupJob = launch(start = CoroutineStart.LAZY) {
            cleanup(importRoot = cancellingRoot).run()
        }

        cleanupJob.start()
        cleanupJob.join()

        assertTrue(cleanupJob.isCancelled)
        assertTrue(expired.exists())
    }

    private fun cleanup(
        gateway: FakeGateway = FakeGateway(),
        store: FakePendingStore = FakePendingStore(),
        systemInstallStore: SystemInstallHandoffStore = FakeSystemInstallHandoffStore(),
        importRoot: File = imports,
    ) = StartupCleanup(
        importRoot = importRoot,
        installGateway = gateway,
        pendingStore = store,
        clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
        systemInstallHandoffStore = systemInstallStore,
    )

    private fun tokenDirectory(token: String, timestamp: Long): File =
        File(imports, token).apply {
            mkdirs()
            setLastModified(timestamp)
        }

    private fun pending(sessionId: Int, startedAt: Long) = PendingInstall(
        sessionId = sessionId,
        packageName = PACKAGE_NAME,
        importToken = EXPIRED_TOKEN,
        startedAtEpochMillis = startedAt,
    )

    private fun systemInstallHandoff(stage: SystemInstallStage) = PendingSystemInstall(
        importToken = HANDOFF_TOKEN,
        expected = ExpectedInstalledWrapper(
            packageName = PACKAGE_NAME,
            extensionId = EXTENSION_ID,
            versionCode = 2,
            signerSha256 = "a".repeat(64),
        ),
        wrapperSha256 = "b".repeat(64),
        previous = null,
        startedAtEpochMillis = now,
        stage = stage,
    )

    private class FakeGateway(
        val owned: MutableList<Int> = mutableListOf(),
        var ownedFailure: Throwable? = null,
        var abandonFailure: Throwable? = null,
    ) : PackageInstallGateway {
        val abandoned = mutableListOf<Int>()
        var ownedRequests = 0

        override fun canRequestPackageInstalls(): Boolean = true
        override fun unknownSourcesSettingsIntent(): Intent = Intent()
        override suspend fun createSession(packageName: String): Int = error("not used")
        override suspend fun writeSession(sessionId: Int, apk: File) = Unit
        override fun commit(sessionId: Int) = Unit
        override fun abandon(sessionId: Int) {
            abandoned += sessionId
            abandonFailure?.let { throw it }
            owned -= sessionId
        }

        override fun ownedSessions(): List<Int> {
            ownedRequests += 1
            ownedFailure?.let { throw it }
            return owned.toList()
        }

        override fun sessionSnapshot(sessionId: Int): InstallSessionSnapshot? =
            sessionId.takeIf(owned::contains)?.let {
                InstallSessionSnapshot(it, PACKAGE_NAME, ownedByCaller = true)
            }
    }

    private class FakePendingStore(
        var record: PendingInstall? = null,
        var terminal: PendingInstallResult? = null,
        var finalReceipt: FinalInstallReceipt? = null,
        var normalHandoff: NormalInstallHandoff? = null,
        var missingObservedAt: Long? = null,
        var loadFailure: Throwable? = null,
        var terminalLoadFailure: Throwable? = null,
        var missingObservationFailure: Throwable? = null,
        var finalSnapshot: RecoveryRecordSnapshot<FinalInstallReceipt>? = null,
        var normalHandoffSnapshot: RecoveryRecordSnapshot<NormalInstallHandoff>? = null,
        var pendingSnapshot: RecoveryRecordSnapshot<PendingInstall>? = null,
        var terminalSnapshot: RecoveryRecordSnapshot<PendingInstallResult>? = null,
    ) : PendingInstallStore {
        var terminalLoads = 0
        var clearWithoutTerminalCalls = 0

        override fun load(): PendingInstall? {
            loadFailure?.let { throw it }
            return record
        }
        override fun pendingInstallSnapshot(): RecoveryRecordSnapshot<PendingInstall> =
            pendingSnapshot ?: super.pendingInstallSnapshot()
        override fun tryClaim(packageName: String, importToken: String, startedAtEpochMillis: Long): PendingInstall? = null
        override fun assignSession(claim: PendingInstall, sessionId: Int): PendingInstall? = null
        override fun recoverSession(claim: PendingInstall, sessionId: Int): PendingInstall? = null
        override fun clear(expected: PendingInstall): Boolean = false
        override fun clearWithoutTerminal(expected: PendingInstall): PendingCleanupResult {
            clearWithoutTerminalCalls += 1
            if (record != expected) return PendingCleanupResult.Failed
            if (terminal != null) return PendingCleanupResult.TerminalAvailable
            record = null
            return PendingCleanupResult.Cleared
        }

        override fun discardImport(importToken: String) = Unit
        override fun loadTerminalResult(): PendingInstallResult? {
            terminalLoads += 1
            terminalLoadFailure?.let { throw it }
            return terminal
        }
        override fun terminalResultSnapshot(): RecoveryRecordSnapshot<PendingInstallResult> =
            terminalSnapshot ?: super.terminalResultSnapshot()
        override fun recordTerminalResult(result: PackageInstallResult, terminalAtEpochMillis: Long): Boolean = false
        override fun markTerminalProcessed(expected: PendingInstallResult): PendingInstallResult? = null
        override fun clearTerminalResult(expected: PendingInstallResult): Boolean = false
        override fun loadFinalReceipt(): FinalInstallReceipt? = finalReceipt
        override fun finalReceiptSnapshot(): RecoveryRecordSnapshot<FinalInstallReceipt> =
            finalSnapshot ?: super.finalReceiptSnapshot()
        override fun loadNormalHandoff(): NormalInstallHandoff? = normalHandoff
        override fun normalHandoffSnapshot(): RecoveryRecordSnapshot<NormalInstallHandoff> =
            normalHandoffSnapshot ?: super.normalHandoffSnapshot()
        override fun firstSessionMissingAt(pending: PendingInstall, observedAtEpochMillis: Long): Long =
            missingObservationFailure?.let { throw it }
                ?: missingObservedAt ?: observedAtEpochMillis.also { missingObservedAt = it }

        override fun clearSessionMissingObservation(pending: PendingInstall): Boolean {
            missingObservedAt = null
            return true
        }
    }

    private class FakeSystemInstallHandoffStore(
        private var record: PendingSystemInstall? = null,
        private val snapshotOverride: RecoveryRecordSnapshot<PendingSystemInstall>? = null,
    ) : SystemInstallHandoffStore {
        var mutationCalls = 0

        override fun snapshot(): RecoveryRecordSnapshot<PendingSystemInstall> =
            snapshotOverride
                ?: record?.let { RecoveryRecordSnapshot.Present(it) }
                ?: RecoveryRecordSnapshot.Absent

        override fun tryPrepare(record: PendingSystemInstall): Boolean {
            mutationCalls += 1
            return false
        }

        override fun markLaunched(expected: PendingSystemInstall): PendingSystemInstall? {
            mutationCalls += 1
            return null
        }

        override fun markExported(expected: PendingSystemInstall): PendingSystemInstall? {
            mutationCalls += 1
            return null
        }

        override fun clear(expected: PendingSystemInstall): Boolean {
            mutationCalls += 1
            if (record != expected) return false
            record = null
            return true
        }
    }

    private class CancellingTimestampFile(
        path: String,
        private val cancel: () -> Unit,
    ) : File(path) {
        override fun lastModified(): Long {
            cancel()
            return super.lastModified()
        }
    }

    private class ListedImportRoot(
        path: String,
        private val candidate: File,
    ) : File(path) {
        override fun getCanonicalFile(): File = this
        override fun listFiles(): Array<File> = arrayOf(candidate)
    }

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1_000L
        const val EXPIRED_TOKEN = "123e4567-e89b-12d3-a456-426614174000"
        const val RECENT_TOKEN = "123e4567-e89b-12d3-a456-426614174001"
        const val OTHER_TOKEN = "123e4567-e89b-12d3-a456-426614174002"
        const val FINAL_TOKEN = "123e4567-e89b-12d3-a456-426614174003"
        const val HANDOFF_TOKEN = "123e4567-e89b-12d3-a456-426614174004"
        const val UNPROTECTED_TOKEN = "123e4567-e89b-12d3-a456-426614174005"
        const val EXTENSION_ID = "aabbccddeeffgghhiijjkkllmmnnoopp"
        const val PACKAGE_NAME = "dev.sixbridge.ext.aabbccddeeffgghhiijjkkllmmnnoopp"
    }
}
