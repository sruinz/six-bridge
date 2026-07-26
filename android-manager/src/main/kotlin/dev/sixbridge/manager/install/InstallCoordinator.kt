package dev.sixbridge.manager.install

import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.importer.ImportedBundle
import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class InstallCoordinator(
    private val policy: InstallPolicy,
    private val gateway: PackageInstallGateway,
    private val pendingStore: PendingInstallStore,
    private val installedWrappers: InstalledWrapperRepository,
    private val catalog: ManagerCatalog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retainedWrapperSha256: suspend (File) -> String = { file ->
        withContext(Dispatchers.IO) { sha256(file) }
    },
) {
    private val mutex = Mutex()
    private var activeBundle: ImportedBundle? = null
    private var volatileConfirmation: Pair<Int, String>? = null
    private val mutableState = MutableStateFlow<InstallState>(restoreState())

    val state: StateFlow<InstallState> = mutableState.asStateFlow()

    suspend fun request(bundle: ImportedBundle, partialConfirmed: Boolean) = mutex.withLock {
        val existing = pendingStore.load()
        if (activeBundle != null || existing != null) {
            discardCompetingImport(bundle.token, existing?.importToken ?: activeBundle?.token)
            return@withLock
        }
        val installed = try {
            installedWrappers.find(bundle.verifiedApk.packageName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            finishUnclaimed(
                bundle,
                InstallState.Failed("INSTALLED_QUERY_FAILED", "설치 상태를 확인하지 못했습니다."),
            )
            return@withLock
        }
        when (val decision = policy.decide(bundle.report, bundle.verifiedApk, installed)) {
            is InstallDecision.Denied -> finishUnclaimed(
                bundle,
                InstallState.Failed(
                    code = decision.reason.name,
                    message = "설치 정책이 래퍼를 거부했습니다.",
                ),
            )

            is InstallDecision.Allowed -> {
                if (decision.requiresPartialConfirmation && !partialConfirmed) {
                    finishUnclaimed(
                        bundle,
                        InstallState.Failed(
                            code = "PARTIAL_CONFIRMATION_REQUIRED",
                            message = "부분 호환 설치 확인이 필요합니다.",
                        ),
                    )
                    return@withLock
                }
                activeBundle = bundle
                if (!gateway.canRequestPackageInstalls()) {
                    mutableState.value = InstallState.UnknownSourcesPermissionRequired
                    return@withLock
                }
                try {
                    createAndCommit(bundle)
                } catch (error: CancellationException) {
                    if (pendingStore.load() == null) activeBundle = null
                    throw error
                } catch (error: Exception) {
                    val unclaimed = try {
                        pendingStore.load() == null
                    } catch (_: Exception) {
                        false
                    }
                    if (unclaimed) activeBundle = null
                    throw error
                }
            }
        }
    }

    suspend fun resumeAfterUnknownSourcesSettings() = mutex.withLock {
        if (mutableState.value != InstallState.UnknownSourcesPermissionRequired) return@withLock
        val bundle = activeBundle ?: run {
            mutableState.value = InstallState.Failed(
                "IMPORT_NOT_AVAILABLE",
                "설치할 임시 래퍼를 다시 가져와야 합니다.",
            )
            return@withLock
        }
        if (!gateway.canRequestPackageInstalls()) return@withLock
        try {
            createAndCommit(bundle)
        } catch (error: CancellationException) {
            if (pendingStore.load() == null) activeBundle = null
            throw error
        }
    }

    suspend fun restore(bundle: ImportedBundle? = null) = mutex.withLock {
        val pending = pendingStore.load()
        if (bundle != null &&
            pending?.importToken == bundle.token &&
            pending.packageName == bundle.verifiedApk.packageName &&
            matchesRetainedWrapper(bundle)
        ) {
            activeBundle = bundle
        }
        restoreTerminalResultLocked()
        if (pendingStore.loadTerminalResult() == null) {
            restoreMissingSessionLocked()
        }
    }

    suspend fun onPackageInstallerResult(result: PackageInstallResult): Boolean = mutex.withLock {
        if (result is PackageInstallResult.PendingUserAction) {
            val pending = pendingStore.load()
            val accepted = pending?.sessionId == result.sessionId &&
                !pendingStore.isConfirmationDelivered(result.sessionId) &&
                volatileConfirmation != (result.sessionId to pending.importToken)
            if (accepted) {
                mutableState.value = InstallState.AwaitingUserApproval(result.sessionId)
            }
            return@withLock accepted
        }
        volatileConfirmation = null
        val accepted = pendingStore.load()?.sessionId == result.sessionId
        runCatching { pendingStore.recordTerminalResult(result, clock()) }
        restoreTerminalResultLocked()
        accepted
    }

    suspend fun markConfirmationDelivered(sessionId: Int): Boolean = mutex.withLock {
        val pending = pendingStore.load()
        if (pending?.sessionId != sessionId) return@withLock false
        val persisted = pendingStore.markConfirmationDelivered(sessionId)
        if (!persisted && !pendingStore.isConfirmationDelivered(sessionId)) {
            volatileConfirmation = sessionId to pending.importToken
        }
        persisted
    }

    suspend fun cancel(): Boolean = mutex.withLock {
        restoreTerminalResultLocked()
        if (pendingStore.loadTerminalResult() != null) return@withLock false
        if (mutableState.value.isTerminal() &&
            pendingStore.load() == null &&
            activeBundle == null
        ) {
            return@withLock cleanupComplete()
        }
        val pending = pendingStore.load()
        if (pending != null && pending.sessionId >= 0) {
            finishSession(
                pending = pending,
                next = InstallState.Cancelled,
                abandon = true,
                inbox = null,
            )
            return@withLock cleanupComplete()
        }
        if (pending != null) {
            val owned = try {
                gateway.ownedSessions()
            } catch (_: Exception) {
                mutableState.value = InstallState.Failed(
                    "SESSION_OWNERSHIP_QUERY_FAILED",
                    "설치 session 소유 상태를 확인하지 못했습니다.",
                )
                return@withLock false
            }
            if (owned.size == 1) {
                val recovered = recoverUnassignedSession(pending, owned.single())
                if (recovered == null) {
                    mutableState.value = sessionRecoveryFailed()
                    return@withLock false
                }
                finishSession(
                    pending = recovered,
                    next = InstallState.Cancelled,
                    abandon = true,
                    inbox = null,
                )
                return@withLock cleanupComplete()
            }
            if (owned.isNotEmpty()) {
                mutableState.value = sessionRecoveryFailed()
                return@withLock false
            }
            finishSession(
                pending = pending,
                next = InstallState.Cancelled,
                abandon = false,
                inbox = null,
            )
            return@withLock cleanupComplete()
        }
        val bundle = activeBundle ?: return@withLock true
        val discarded = pendingStore.discardImportSafely(bundle.token)
        mutableState.value = if (discarded) {
            activeBundle = null
            InstallState.Cancelled
        } else {
            cleanupFailed()
        }
        cleanupComplete()
    }

    fun unknownSourcesSettingsIntent() = gateway.unknownSourcesSettingsIntent()

    private suspend fun createAndCommit(bundle: ImportedBundle) {
        if (!matchesRetainedWrapper(bundle)) {
            finishUnclaimed(
                bundle,
                InstallState.Failed(
                    "WRAPPER_HASH_MISMATCH",
                    "검증 후 보관된 래퍼가 변경되었습니다.",
                ),
            )
            return
        }
        val claim = pendingStore.tryClaim(
            packageName = bundle.verifiedApk.packageName,
            importToken = bundle.token,
            startedAtEpochMillis = clock(),
        )
        if (claim == null) {
            activeBundle = null
            val existing = pendingStore.load()
            discardCompetingImport(bundle.token, existing?.importToken)
            mutableState.value = existing?.toState() ?: InstallState.Idle
            return
        }

        val sessionId = try {
            gateway.createSession(bundle.verifiedApk.packageName)
        } catch (error: CancellationException) {
            finishInterruptedCreate(claim)
            throw error
        } catch (error: PackageInstallSessionException) {
            releaseClaim(
                claim,
                bundle.token,
                InstallState.Failed(error.code, error.message),
            )
            return
        } catch (_: Exception) {
            releaseClaim(
                claim,
                bundle.token,
                InstallState.Failed("SESSION_CREATE_FAILED", "설치 session을 만들지 못했습니다."),
            )
            return
        }

        val pending = pendingStore.assignSession(claim, sessionId)
        if (pending == null) {
            handleAssignmentFailure(claim, sessionId)
            return
        }
        mutableState.value = InstallState.WritingSession(sessionId)
        try {
            gateway.writeSession(sessionId, bundle.wrapperFile)
        } catch (error: CancellationException) {
            finishSession(pending, InstallState.Cancelled, abandon = true, inbox = null)
            throw error
        } catch (error: PackageInstallSessionException) {
            finishSession(
                pending = pending,
                next = InstallState.Failed(error.code, error.message),
                abandon = true,
                inbox = null,
            )
            return
        } catch (_: Exception) {
            finishSession(
                pending = pending,
                next = InstallState.Failed(
                    "WRITE_FAILED",
                    "설치 session에 APK를 기록하지 못했습니다.",
                ),
                abandon = true,
                inbox = null,
            )
            return
        }
        try {
            gateway.commit(sessionId)
            mutableState.value = InstallState.AwaitingUserApproval(sessionId)
        } catch (error: CancellationException) {
            finishSession(pending, InstallState.Cancelled, abandon = true, inbox = null)
            throw error
        } catch (_: Exception) {
            finishSession(
                pending = pending,
                next = InstallState.Failed(
                    "SESSION_COMMIT_FAILED",
                    "설치 session을 제출하지 못했습니다.",
                ),
                abandon = true,
                inbox = null,
            )
        }
    }

    private suspend fun handleAssignmentFailure(
        claim: PendingInstall,
        sessionId: Int,
    ) {
        if (!abandonConfirmed(sessionId)) {
            val recovered = runCatching {
                pendingStore.recoverSession(claim, sessionId)
            }.getOrNull()
            mutableState.value = if (recovered != null) {
                abandonFailed()
            } else {
                sessionRecoveryFailed()
            }
            return
        }
        val observed = runCatching { pendingStore.load() }.getOrNull()
        val expected = when (observed) {
            claim -> claim
            claim.copy(sessionId = sessionId) -> observed
            else -> null
        }
        if (expected == null) {
            mutableState.value = cleanupFailed()
            return
        }
        when (runCatching { pendingStore.clearWithoutTerminal(expected) }
            .getOrDefault(PendingCleanupResult.Failed)
        ) {
            PendingCleanupResult.Cleared -> {
                activeBundle = null
                mutableState.value = InstallState.Failed(
                    "INSTALL_CLAIM_LOST",
                    "설치 claim을 확인하지 못했습니다.",
                )
            }
            PendingCleanupResult.TerminalAvailable -> restoreTerminalResultLocked()
            PendingCleanupResult.Failed -> mutableState.value = cleanupFailed()
        }
    }

    private suspend fun finishInterruptedCreate(claim: PendingInstall) {
        val owned = try {
            gateway.ownedSessions()
        } catch (_: Exception) {
            mutableState.value = sessionRecoveryFailed()
            return
        }
        if (owned.isEmpty()) {
            releaseClaim(claim, claim.importToken, InstallState.Cancelled)
            return
        }
        if (owned.size != 1) {
            mutableState.value = sessionRecoveryFailed()
            return
        }
        val recovered = recoverUnassignedSession(claim, owned.single())
        if (recovered == null) {
            mutableState.value = sessionRecoveryFailed()
            return
        }
        finishSession(
            pending = recovered,
            next = InstallState.Cancelled,
            abandon = true,
            inbox = null,
        )
    }

    private suspend fun restoreTerminalResultLocked() {
        val inbox = pendingStore.loadTerminalResult() ?: return
        val pending = pendingStore.load()
        if (pending == null) {
            finishCompletedTerminalCleanup(inbox)
            return
        }
        if (pending.sessionId != inbox.result.sessionId) {
            pendingStore.clearTerminalResult(inbox)
            return
        }
        when (val result = inbox.result) {
            is PackageInstallResult.Success -> verifyAndRecord(pending, inbox)
            is PackageInstallResult.Cancelled -> finishSession(
                pending = pending,
                next = InstallState.Cancelled,
                abandon = true,
                inbox = inbox,
            )
            is PackageInstallResult.Failure -> finishSession(
                pending = pending,
                next = InstallState.Failed(result.code, result.message),
                abandon = true,
                inbox = inbox,
            )
            is PackageInstallResult.PendingUserAction -> Unit
        }
    }

    private suspend fun restoreMissingSessionLocked() {
        var pending = pendingStore.load() ?: return
        if (pendingStore.loadTerminalResult() != null) return
        val owned = try {
            gateway.ownedSessions()
        } catch (_: Exception) {
            return
        }
        if (pending.sessionId < 0 && owned.size == 1) {
            val recovered = recoverUnassignedSession(pending, owned.single())
            if (recovered == null) {
                runCatching { pendingStore.clearSessionMissingObservation(pending) }
                return
            }
            pending = recovered
            mutableState.value = pending.toState()
        }
        if (pending.isStillOwned(owned)) {
            val reset = runCatching {
                pendingStore.clearSessionMissingObservation(pending)
            }.getOrDefault(false)
            if (!reset) mutableState.value = missingObservationResetFailed()
            return
        }
        val now = clock()
        val missingSince = try {
            pendingStore.firstSessionMissingAt(pending, now)
        } catch (_: Exception) {
            return
        }
        if (isWithinGrace(missingSince, now)) return
        if (pendingStore.loadTerminalResult() != null) return
        val stillOwned = try {
            gateway.ownedSessions()
        } catch (_: Exception) {
            return
        }
        if (pending.isStillOwned(stillOwned)) return
        when (runCatching { pendingStore.clearWithoutTerminal(pending) }
            .getOrDefault(PendingCleanupResult.Failed)
        ) {
            PendingCleanupResult.Cleared -> {
                activeBundle = null
                mutableState.value = InstallState.Idle
            }
            PendingCleanupResult.TerminalAvailable -> restoreTerminalResultLocked()
            PendingCleanupResult.Failed -> mutableState.value = cleanupFailed()
        }
    }

    private fun finishCompletedTerminalCleanup(inbox: PendingInstallResult) {
        if (inbox.result is PackageInstallResult.Success && !inbox.processed) {
            mutableState.value = InstallState.Failed(
                "TERMINAL_RECOVERY_FAILED",
                "검증 완료 표지가 없는 설치 결과를 복구하지 못했습니다.",
            )
            return
        }
        if (!pendingStore.clearTerminalResult(inbox)) {
            mutableState.value = cleanupFailed()
            return
        }
        activeBundle = null
        mutableState.value = when (val result = inbox.result) {
            is PackageInstallResult.Success -> {
                if (inbox.packageName.isBlank()) {
                    InstallState.Failed(
                        "TERMINAL_RECOVERY_FAILED",
                        "완료된 설치 결과를 복구하지 못했습니다.",
                    )
                } else {
                    InstallState.Installed(inbox.packageName)
                }
            }
            is PackageInstallResult.Cancelled -> InstallState.Cancelled
            is PackageInstallResult.Failure -> InstallState.Failed(result.code, result.message)
            is PackageInstallResult.PendingUserAction -> return
        }
    }

    private suspend fun verifyAndRecord(
        pending: PendingInstall,
        inbox: PendingInstallResult,
    ) {
        val bundle = activeBundle
        if (bundle == null) {
            mutableState.value = InstallState.Failed(
                "IMPORT_NOT_AVAILABLE",
                "설치 결과를 검증할 임시 래퍼를 복원하지 못했습니다.",
            )
            return
        }
        val installed = try {
            installedWrappers.find(pending.packageName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (!isExpectedInstall(pending, installed, bundle)) {
            finishSession(
                pending = pending,
                next = InstallState.Failed(
                    "POST_INSTALL_VERIFICATION_FAILED",
                    "설치된 래퍼를 검증할 수 없습니다.",
                ),
                abandon = true,
                inbox = inbox,
            )
            return
        }
        val verified = requireNotNull(installed)
        val record = CatalogRecord(
            packageName = verified.packageName,
            extensionId = verified.extensionId,
            name = verified.name,
            iconPng = null,
            installedVersionCode = verified.versionCode,
            signerSha256 = verified.signerSha256,
            compatibilityStatus = requireNotNull(bundle).report.status.name,
            reportJson = BundleJson.encodeCompatibility(bundle.report),
            lastVerifiedAtEpochMillis = clock(),
        )
        try {
            catalog.replaceVerified(record)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            finishSession(
                pending = pending,
                next = InstallState.Failed(
                    "CATALOG_UPDATE_FAILED",
                    "설치 catalog를 갱신하지 못했습니다.",
                ),
                abandon = true,
                inbox = inbox,
            )
            return
        }
        finishSession(
            pending = pending,
            next = InstallState.Installed(verified.packageName),
            abandon = false,
            inbox = inbox,
        )
    }

    private fun isExpectedInstall(
        pending: PendingInstall,
        installed: InstalledWrapper?,
        bundle: ImportedBundle?,
    ): Boolean {
        if (installed == null ||
            bundle == null ||
            installed.packageName != pending.packageName ||
            installed.versionCode <= 0 ||
            installed.signerSha256.isBlank()
        ) {
            return false
        }
        return installed.extensionId == bundle.verifiedApk.extensionId &&
            installed.versionCode == bundle.verifiedApk.versionCode &&
            installed.signerSha256 == bundle.verifiedApk.signerSha256
    }

    private suspend fun matchesRetainedWrapper(bundle: ImportedBundle): Boolean {
        if (bundle.wrapperSha256 != bundle.verifiedApk.wrapperSha256 ||
            bundle.wrapperSha256 != bundle.descriptor.wrapperSha256
        ) {
            return false
        }
        return retainedWrapperSha256(bundle.wrapperFile) == bundle.wrapperSha256
    }

    private fun restoreState(): InstallState {
        return pendingStore.load()?.toState() ?: InstallState.Idle
    }

    private fun finishUnclaimed(bundle: ImportedBundle, next: InstallState) {
        activeBundle = null
        mutableState.value = if (pendingStore.discardImportSafely(bundle.token)) next else cleanupFailed()
    }

    private fun releaseClaim(
        claim: PendingInstall,
        importToken: String,
        next: InstallState,
    ) {
        val importDiscarded = pendingStore.discardImportSafely(importToken)
        val ledgerCleared = if (importDiscarded) {
            runCatching { pendingStore.clear(claim) }.getOrDefault(false)
        } else {
            false
        }
        if (ledgerCleared && importDiscarded) activeBundle = null
        mutableState.value = if (ledgerCleared && importDiscarded) next else cleanupFailed()
    }

    private suspend fun finishSession(
        pending: PendingInstall,
        next: InstallState,
        abandon: Boolean,
        inbox: PendingInstallResult?,
    ) {
        if (abandon && !abandonConfirmed(pending.sessionId)) {
            mutableState.value = abandonFailed()
            return
        }
        if (inbox == null) {
            when (runCatching { pendingStore.clearWithoutTerminal(pending) }
                .getOrDefault(PendingCleanupResult.Failed)
            ) {
                PendingCleanupResult.Cleared -> {
                    activeBundle = null
                    mutableState.value = next
                }
                PendingCleanupResult.TerminalAvailable -> restoreTerminalResultLocked()
                PendingCleanupResult.Failed -> mutableState.value = cleanupFailed()
            }
            return
        }
        val cleanupInbox = if (next is InstallState.Installed) {
            pendingStore.markTerminalProcessed(inbox) ?: run {
                mutableState.value = cleanupFailed()
                return
            }
        } else {
            inbox
        }
        val importDiscarded = pendingStore.discardImportSafely(pending.importToken)
        if (!importDiscarded) {
            mutableState.value = cleanupFailed()
            return
        }
        val ledgerCleared = runCatching { pendingStore.clear(pending) }.getOrDefault(false)
        if (!ledgerCleared || !importDiscarded) {
            mutableState.value = cleanupFailed()
            return
        }
        if (!pendingStore.clearTerminalResult(cleanupInbox)) {
            mutableState.value = cleanupFailed()
            return
        }
        activeBundle = null
        mutableState.value = next
    }

    private fun abandonConfirmed(sessionId: Int): Boolean {
        if (sessionId < 0) return true
        try {
            gateway.abandon(sessionId)
            return true
        } catch (_: Exception) {
            return try {
                sessionId !in gateway.ownedSessions()
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun recoverUnassignedSession(
        pending: PendingInstall,
        sessionId: Int,
    ): PendingInstall? {
        if (pending.sessionId >= 0) return pending.takeIf { it.sessionId == sessionId }
        return runCatching {
            pendingStore.recoverSession(pending, sessionId)
        }.getOrNull()
    }

    private fun discardCompetingImport(importToken: String, activeToken: String?) {
        if (importToken != activeToken) pendingStore.discardImportSafely(importToken)
    }

    private fun PendingInstallStore.discardImportSafely(importToken: String): Boolean =
        try {
            discardImport(importToken)
            true
        } catch (_: Exception) {
            false
        }

    private fun cleanupComplete(): Boolean {
        val complete = activeBundle == null &&
            pendingStore.load() == null &&
            pendingStore.loadTerminalResult() == null
        if (complete) volatileConfirmation = null
        return complete
    }

    private fun PendingInstall.toState(): InstallState =
        if (sessionId >= 0) InstallState.AwaitingUserApproval(sessionId)
        else InstallState.WritingSession(sessionId)

    private fun PendingInstall.isStillOwned(ownedSessions: List<Int>): Boolean =
        if (sessionId < 0) ownedSessions.isNotEmpty() else sessionId in ownedSessions

    private fun InstallState.isTerminal(): Boolean =
        this is InstallState.Installed ||
            this is InstallState.Cancelled ||
            this is InstallState.Failed

    private fun isWithinGrace(basis: Long, now: Long): Boolean =
        now < basis || now - basis <= COMPLETED_SESSION_GRACE_MILLIS

    private fun cleanupFailed() = InstallState.Failed(
        "CLEANUP_FAILED",
        "설치 임시 데이터를 정리하지 못했습니다.",
    )

    private fun abandonFailed() = InstallState.Failed(
        "ABANDON_FAILED",
        "설치 session을 정리하지 못했습니다.",
    )

    private fun sessionRecoveryFailed() = InstallState.Failed(
        "SESSION_RECOVERY_FAILED",
        "설치 session ID를 복구 저장하지 못했습니다.",
    )

    private fun missingObservationResetFailed() = InstallState.Failed(
        "MISSING_OBSERVATION_RESET_FAILED",
        "session 복구 시각을 초기화하지 못했습니다.",
    )

    private companion object {
        const val COMPLETED_SESSION_GRACE_MILLIS = 24 * 60 * 60 * 1_000L

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
