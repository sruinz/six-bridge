package dev.sixbridge.manager.cleanup

import dev.sixbridge.manager.install.PackageInstallGateway
import dev.sixbridge.manager.install.PendingCleanupResult
import dev.sixbridge.manager.install.PendingInstall
import dev.sixbridge.manager.install.PendingInstallStore
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.util.UUID

class StartupCleanup(
    private val importRoot: File,
    private val installGateway: PackageInstallGateway,
    private val pendingStore: PendingInstallStore,
    private val clock: Clock,
) {
    suspend fun run() {
        val pending = pendingStore.load()
        val terminalAvailable = pendingStore.loadTerminalResult() != null
        val ownedSessions = runCatching { installGateway.ownedSessions().toSet() }.getOrNull()

        if (ownedSessions == null) {
            cleanupExpiredImports(pending?.importToken)
            return
        }

        val cleanup = if (terminalAvailable) {
            CleanupDecision(pending?.importToken, ownedSessions)
        } else {
            decidePendingCleanup(pending, ownedSessions)
        }

        cleanup.sessionsToRetain.let { retained ->
            if (!terminalAvailable) {
                ownedSessions.filterNot(retained::contains).forEach { sessionId ->
                    runCatching { installGateway.abandon(sessionId) }
                }
            }
        }
        cleanupExpiredImports(cleanup.protectedImportToken)
    }

    private fun decidePendingCleanup(
        pending: PendingInstall?,
        ownedSessions: Set<Int>,
    ): CleanupDecision {
        if (pending == null) return CleanupDecision(null, emptySet())
        val recoverableSessions = when {
            pending.sessionId in ownedSessions -> setOf(pending.sessionId)
            pending.sessionId < 0 && ownedSessions.size == 1 -> ownedSessions
            pending.sessionId < 0 && ownedSessions.size > 1 -> ownedSessions
            else -> emptySet()
        }
        if (recoverableSessions.isNotEmpty()) {
            runCatching { pendingStore.clearSessionMissingObservation(pending) }
            return CleanupDecision(pending.importToken, recoverableSessions)
        }

        val now = clock.millis()
        val missingSince = runCatching {
            pendingStore.firstSessionMissingAt(pending, now)
        }.getOrNull() ?: return CleanupDecision(pending.importToken, ownedSessions)
        if (now < missingSince || now - missingSince <= STALE_AFTER_MILLIS) {
            return CleanupDecision(pending.importToken, ownedSessions)
        }
        return when (runCatching { pendingStore.clearWithoutTerminal(pending) }
            .getOrDefault(PendingCleanupResult.Failed)) {
            PendingCleanupResult.Cleared -> CleanupDecision(null, emptySet())
            PendingCleanupResult.TerminalAvailable,
            PendingCleanupResult.Failed,
            -> CleanupDecision(pending.importToken, ownedSessions)
        }
    }

    private fun cleanupExpiredImports(protectedToken: String?) {
        val root = runCatching { importRoot.canonicalFile }.getOrNull() ?: return
        if (!root.isDirectory) return
        val cutoff = clock.millis() - STALE_AFTER_MILLIS
        root.listFiles()?.forEach { candidate ->
            if (candidate.name == protectedToken || !isExpiredTokenDirectory(candidate, root, cutoff)) {
                return@forEach
            }
            runCatching { deleteTree(candidate) }
        }
    }

    private fun isExpiredTokenDirectory(candidate: File, root: File, cutoff: Long): Boolean {
        if (!isCanonicalToken(candidate.name) || Files.isSymbolicLink(candidate.toPath())) return false
        if (!Files.isDirectory(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val canonicalCandidate = runCatching { candidate.canonicalFile }.getOrNull() ?: return false
        if (canonicalCandidate.parentFile != root || candidate.lastModified() >= cutoff) return false
        return true
    }

    private fun deleteTree(target: File) {
        val root = importRoot.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (canonicalTarget.parentFile != root || Files.isSymbolicLink(target.toPath())) return
        Files.walkFileTree(target.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: java.nio.file.Path,
                error: java.io.IOException?,
            ): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun isCanonicalToken(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value
    }.getOrDefault(false)

    private data class CleanupDecision(
        val protectedImportToken: String?,
        val sessionsToRetain: Set<Int>,
    )

    private companion object {
        const val STALE_AFTER_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
