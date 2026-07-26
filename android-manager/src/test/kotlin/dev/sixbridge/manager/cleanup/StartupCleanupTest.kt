package dev.sixbridge.manager.cleanup

import android.content.Intent
import dev.sixbridge.manager.install.PackageInstallGateway
import dev.sixbridge.manager.install.PackageInstallResult
import dev.sixbridge.manager.install.PendingCleanupResult
import dev.sixbridge.manager.install.PendingInstall
import dev.sixbridge.manager.install.PendingInstallResult
import dev.sixbridge.manager.install.PendingInstallStore
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
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
        )
        val store = FakePendingStore(record = pending, terminal = terminal)
        val pendingDirectory = tokenDirectory(pending.importToken, now - DAY_MILLIS - 1)

        cleanup(store = store).run()

        assertEquals(pending, store.record)
        assertEquals(terminal, store.terminal)
        assertTrue(pendingDirectory.exists())
    }

    @Test
    fun `abandons only owned sessions that are not the recoverable pending session`() = runBlocking {
        val pending = pending(sessionId = 41, startedAt = now)
        val gateway = FakeGateway(owned = mutableListOf(41, 77))

        cleanup(gateway = gateway, store = FakePendingStore(record = pending)).run()

        assertEquals(listOf(77), gateway.abandoned)
        assertNotNull(gateway.owned.singleOrNull { it == 41 })
    }

    private fun cleanup(
        gateway: FakeGateway = FakeGateway(),
        store: FakePendingStore = FakePendingStore(),
    ) = StartupCleanup(
        importRoot = imports,
        installGateway = gateway,
        pendingStore = store,
        clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC),
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

    private class FakeGateway(
        val owned: MutableList<Int> = mutableListOf(),
    ) : PackageInstallGateway {
        val abandoned = mutableListOf<Int>()

        override fun canRequestPackageInstalls(): Boolean = true
        override fun unknownSourcesSettingsIntent(): Intent = Intent()
        override suspend fun createSession(packageName: String): Int = error("not used")
        override suspend fun writeSession(sessionId: Int, apk: File) = Unit
        override fun commit(sessionId: Int) = Unit
        override fun abandon(sessionId: Int) {
            abandoned += sessionId
            owned -= sessionId
        }

        override fun ownedSessions(): List<Int> = owned.toList()
    }

    private class FakePendingStore(
        var record: PendingInstall? = null,
        var terminal: PendingInstallResult? = null,
        var missingObservedAt: Long? = null,
    ) : PendingInstallStore {
        override fun load(): PendingInstall? = record
        override fun tryClaim(packageName: String, importToken: String, startedAtEpochMillis: Long): PendingInstall? = null
        override fun assignSession(claim: PendingInstall, sessionId: Int): PendingInstall? = null
        override fun recoverSession(claim: PendingInstall, sessionId: Int): PendingInstall? = null
        override fun clear(expected: PendingInstall): Boolean = false
        override fun clearWithoutTerminal(expected: PendingInstall): PendingCleanupResult {
            if (record != expected) return PendingCleanupResult.Failed
            if (terminal != null) return PendingCleanupResult.TerminalAvailable
            record = null
            return PendingCleanupResult.Cleared
        }

        override fun discardImport(importToken: String) = Unit
        override fun loadTerminalResult(): PendingInstallResult? = terminal
        override fun recordTerminalResult(result: PackageInstallResult, terminalAtEpochMillis: Long): Boolean = false
        override fun markTerminalProcessed(expected: PendingInstallResult): PendingInstallResult? = null
        override fun clearTerminalResult(expected: PendingInstallResult): Boolean = false
        override fun firstSessionMissingAt(pending: PendingInstall, observedAtEpochMillis: Long): Long =
            missingObservedAt ?: observedAtEpochMillis.also { missingObservedAt = it }

        override fun clearSessionMissingObservation(pending: PendingInstall): Boolean {
            missingObservedAt = null
            return true
        }
    }

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1_000L
        const val EXPIRED_TOKEN = "123e4567-e89b-12d3-a456-426614174000"
        const val RECENT_TOKEN = "123e4567-e89b-12d3-a456-426614174001"
        const val PACKAGE_NAME = "dev.sixbridge.ext.aabbccddeeffgghhiijjkkllmmnnoopp"
    }
}
