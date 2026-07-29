package dev.sixbridge.manager.cleanup

import dev.sixbridge.manager.install.PackageInstallGateway
import dev.sixbridge.manager.install.PendingCleanupResult
import dev.sixbridge.manager.install.PendingInstall
import dev.sixbridge.manager.install.PendingInstallStore
import dev.sixbridge.manager.install.RecoveryRecordSnapshot
import dev.sixbridge.manager.install.SystemInstallHandoffStore
import java.io.File
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class StartupCleanup(
    private val importRoot: File,
    private val installGateway: PackageInstallGateway,
    private val pendingStore: PendingInstallStore,
    private val clock: Clock,
    private val systemInstallHandoffStore: SystemInstallHandoffStore? = null,
) {
    suspend fun run() {
        val context = currentCoroutineContext()
        context.ensureActive()
        val pending = when (val snapshot = pendingStore.pendingInstallSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> return
        }
        context.ensureActive()
        val terminal = when (val snapshot = pendingStore.terminalResultSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> return
        }
        context.ensureActive()
        val finalReceipt = when (val snapshot = pendingStore.finalReceiptSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> return
        }
        context.ensureActive()
        val normalHandoff = when (val snapshot = pendingStore.normalHandoffSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> return
        }
        context.ensureActive()
        val systemInstallHandoff = when (
            val snapshot = systemInstallHandoffStore?.snapshot()
                ?: RecoveryRecordSnapshot.Absent
        ) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> return
        }
        val durableProtectedTokens = setOfNotNull(
            terminal?.importToken,
            finalReceipt?.importToken,
            normalHandoff?.importToken,
            systemInstallHandoff?.importToken,
        )
        val terminalAvailable = terminal?.importToken != null || finalReceipt != null
        context.ensureActive()
        val ownedSessions = bestEffort { installGateway.ownedSessions().toSet() }

        if (ownedSessions == null) {
            cleanupExpiredImports(
                durableProtectedTokens + setOfNotNull(pending?.importToken),
                context,
            )
            return
        }

        val cleanup = if (terminalAvailable) {
            CleanupDecision(
                setOfNotNull(pending?.importToken),
                ownedSessions,
            )
        } else {
            decidePendingCleanup(pending, ownedSessions, context)
        }

        if (!terminalAvailable) {
            ownedSessions.filterNot(cleanup.sessionsToRetain::contains).forEach { sessionId ->
                context.ensureActive()
                bestEffort { installGateway.abandon(sessionId) }
            }
        }
        context.ensureActive()
        cleanupExpiredImports(
            durableProtectedTokens + cleanup.protectedImportTokens,
            context,
        )
    }

    private fun decidePendingCleanup(
        pending: PendingInstall?,
        ownedSessions: Set<Int>,
        context: CoroutineContext,
    ): CleanupDecision {
        context.ensureActive()
        if (pending == null) return CleanupDecision(emptySet(), emptySet())
        val recoverableSessions = when {
            pending.sessionId in ownedSessions -> setOf(pending.sessionId)
            pending.sessionId < 0 && ownedSessions.size == 1 -> ownedSessions
            pending.sessionId < 0 && ownedSessions.size > 1 -> ownedSessions
            else -> emptySet()
        }
        if (recoverableSessions.isNotEmpty()) {
            bestEffort { pendingStore.clearSessionMissingObservation(pending) }
            return CleanupDecision(setOf(pending.importToken), recoverableSessions)
        }

        val now = clock.millis()
        val missingSince = bestEffort {
            pendingStore.firstSessionMissingAt(pending, now)
        } ?: return CleanupDecision(setOf(pending.importToken), ownedSessions)
        if (now < missingSince || now - missingSince <= STALE_AFTER_MILLIS) {
            return CleanupDecision(setOf(pending.importToken), ownedSessions)
        }
        return when (bestEffort { pendingStore.clearWithoutTerminal(pending) }
            ?: PendingCleanupResult.Failed) {
            PendingCleanupResult.Cleared -> CleanupDecision(emptySet(), emptySet())
            PendingCleanupResult.TerminalAvailable,
            PendingCleanupResult.Failed,
            -> CleanupDecision(setOf(pending.importToken), ownedSessions)
        }
    }

    private fun cleanupExpiredImports(
        protectedTokens: Set<String>,
        context: CoroutineContext,
    ) {
        context.ensureActive()
        if (Files.isSymbolicLink(importRoot.toPath())) return
        val root = bestEffort { importRoot.canonicalFile } ?: return
        context.ensureActive()
        if (!root.isDirectory) return
        val cutoff = clock.millis() - STALE_AFTER_MILLIS
        root.listFiles()?.forEach { candidate ->
            context.ensureActive()
            if (candidate.name in protectedTokens ||
                !isExpiredTokenDirectory(candidate, root, cutoff)
            ) {
                return@forEach
            }
            context.ensureActive()
            bestEffort { deleteTree(candidate, context) }
        }
    }

    private fun isExpiredTokenDirectory(candidate: File, root: File, cutoff: Long): Boolean {
        if (!isCanonicalToken(candidate.name) || Files.isSymbolicLink(candidate.toPath())) return false
        if (!Files.isDirectory(candidate.toPath(), LinkOption.NOFOLLOW_LINKS)) return false
        val canonicalCandidate = bestEffort { candidate.canonicalFile } ?: return false
        if (canonicalCandidate.parentFile != root || candidate.lastModified() >= cutoff) return false
        return true
    }

    private fun deleteTree(target: File, context: CoroutineContext) {
        context.ensureActive()
        if (Files.isSymbolicLink(importRoot.toPath())) return
        val root = importRoot.canonicalFile
        val canonicalTarget = target.canonicalFile
        if (canonicalTarget.parentFile != root || Files.isSymbolicLink(target.toPath())) return
        Files.walkFileTree(target.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult {
                context.ensureActive()
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(
                directory: java.nio.file.Path,
                error: java.io.IOException?,
            ): FileVisitResult {
                context.ensureActive()
                if (error != null) throw error
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }

    private fun isCanonicalToken(value: String): Boolean = bestEffort {
        UUID.fromString(value).toString() == value
    } ?: false

    private inline fun <T> bestEffort(block: () -> T): T? = try {
        block()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private data class CleanupDecision(
        val protectedImportTokens: Set<String>,
        val sessionsToRetain: Set<Int>,
    )

    private companion object {
        const val STALE_AFTER_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
