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

    internal suspend fun currentRecoverySnapshot(): InstallRecoverySnapshot = mutex.withLock {
        InstallRecoverySnapshot(
            pending = pendingStore.load(),
            activeBundle = activeBundle,
        )
    }

    suspend fun request(
        bundle: ImportedBundle,
        partialConfirmed: Boolean,
    ): InstallState = mutex.withLock {
        when (val final = pendingStore.finalReceiptSnapshot()) {
            is RecoveryRecordSnapshot.Present -> {
                discardCompetingImport(bundle.token, final.value.importToken)
                restoreTerminalResultLocked()
                return@withLock mutableState.value
            }
            RecoveryRecordSnapshot.Invalid -> {
                mutableState.value = cleanupFailed()
                return@withLock mutableState.value
            }
            RecoveryRecordSnapshot.Absent -> Unit
        }
        if (pendingStore.normalHandoffSnapshot() == RecoveryRecordSnapshot.Invalid) {
            mutableState.value = cleanupFailed()
            return@withLock mutableState.value
        }
        val existing = when (val snapshot = pendingStore.pendingInstallSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> {
                mutableState.value = cleanupFailed()
                return@withLock mutableState.value
            }
        }
        if (activeBundle != null || existing != null) {
            discardCompetingImport(bundle.token, existing?.importToken ?: activeBundle?.token)
            return@withLock mutableState.value
        }
        when (val snapshot = pendingStore.terminalResultSnapshot()) {
            is RecoveryRecordSnapshot.Present -> {
                discardCompetingImport(bundle.token, snapshot.value.importToken)
                restoreTerminalResultLocked()
                return@withLock mutableState.value
            }
            RecoveryRecordSnapshot.Invalid -> {
                mutableState.value = cleanupFailed()
                return@withLock mutableState.value
            }
            RecoveryRecordSnapshot.Absent -> Unit
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
            return@withLock mutableState.value
        }
        when (val decision = policy.decide(bundle.report, bundle.verifiedApk, installed)) {
            is InstallDecision.Denied -> {
                finishUnclaimed(
                    bundle,
                    InstallState.Failed(
                        code = decision.reason.name,
                        message = "설치 정책이 래퍼를 거부했습니다.",
                    ),
                )
                mutableState.value
            }

            is InstallDecision.Allowed -> {
                if (decision.requiresPartialConfirmation && !partialConfirmed) {
                    finishUnclaimed(
                        bundle,
                        InstallState.Failed(
                            code = "PARTIAL_CONFIRMATION_REQUIRED",
                            message = "부분 호환 설치 확인이 필요합니다.",
                        ),
                    )
                    return@withLock mutableState.value
                }
                activeBundle = bundle
                if (!gateway.canRequestPackageInstalls()) {
                    mutableState.value = InstallState.UnknownSourcesPermissionRequired
                    return@withLock mutableState.value
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
                mutableState.value
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
        val receipt = pendingStore.loadFinalReceipt()
        if (bundle != null &&
            ((pending?.importToken == bundle.token &&
                pending.packageName == bundle.verifiedApk.packageName) ||
                (receipt?.importToken == bundle.token &&
                    receipt.packageName == bundle.verifiedApk.packageName)) &&
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
        val pendingMatch = pendingStore.load()?.sessionId == result.sessionId
        val accepted = runCatching {
            pendingStore.recordTerminalResult(result, clock())
        }.getOrDefault(false)
        restoreTerminalResultLocked()
        accepted || pendingMatch
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

    suspend fun terminalForOwner(
        expectedImportToken: String,
        expectedSessionId: Int?,
    ): InstallTerminalOwnerResult = mutex.withLock {
        restoreTerminalResultLocked()
        val receipt = pendingStore.loadFinalReceipt()
            ?: return InstallTerminalOwnerResult.OwnerMismatch
        if (receipt.stage == FinalReceiptStage.CANCELLING ||
            receipt.importToken != expectedImportToken ||
            mutableState.value != receipt.state
        ) {
            return InstallTerminalOwnerResult.OwnerMismatch
        }
        if (expectedSessionId == null) {
            return receipt.sessionId?.let {
                InstallTerminalOwnerResult.OwnerSessionAssigned(
                    sessionId = it,
                    state = receipt.state,
                )
            } ?: InstallTerminalOwnerResult.Owned(receipt.state)
        }
        return if (expectedSessionId == receipt.sessionId) {
            InstallTerminalOwnerResult.Owned(receipt.state)
        } else {
            InstallTerminalOwnerResult.OwnerMismatch
        }
    }

    suspend fun currentFinalReceiptForOwner(
        expectedImportToken: String,
        expectedSessionId: Int?,
        expectedPackageName: String,
    ): FinalReceiptOwnerResult = mutex.withLock {
        restoreTerminalResultLocked()
        val receipt = when (val snapshot = pendingStore.finalReceiptSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Invalid -> return FinalReceiptOwnerResult.OwnerMismatch
            RecoveryRecordSnapshot.Absent -> {
                val rawAbsent =
                    pendingStore.terminalResultSnapshot() == RecoveryRecordSnapshot.Absent
                val pendingAbsent =
                    pendingStore.pendingInstallSnapshot() == RecoveryRecordSnapshot.Absent
                return if (rawAbsent && pendingAbsent) {
                    FinalReceiptOwnerResult.Absent
                } else {
                    FinalReceiptOwnerResult.OwnerMismatch
                }
            }
        }
        if (receipt.importToken != expectedImportToken ||
            receipt.packageName != expectedPackageName
        ) {
            return FinalReceiptOwnerResult.OwnerMismatch
        }
        if (expectedSessionId == null) {
            return receipt.sessionId?.let {
                FinalReceiptOwnerResult.OwnerSessionAssigned(it)
            } ?: FinalReceiptOwnerResult.Owned(receipt)
        }
        return if (receipt.sessionId == expectedSessionId) {
            FinalReceiptOwnerResult.Owned(receipt)
        } else {
            FinalReceiptOwnerResult.OwnerMismatch
        }
    }

    suspend fun rawReadyTerminalForOwner(
        expectedImportToken: String,
        expectedSessionId: Int?,
        expectedPackageName: String,
    ): FinalInstallReceipt? = mutex.withLock {
        restoreTerminalResultLocked()
        pendingStore.loadFinalReceipt()?.takeIf { receipt ->
            receipt.origin == FinalReceiptOrigin.RAW_TERMINAL &&
                receipt.stage == FinalReceiptStage.READY &&
                receipt.importToken == expectedImportToken &&
                receipt.sessionId == expectedSessionId &&
                receipt.packageName == expectedPackageName
        }
    }

    suspend fun acknowledgeTerminal(
        expectedImportToken: String,
        expectedSessionId: Int?,
        expectedState: InstallState,
    ): Boolean = mutex.withLock {
        restoreTerminalResultLocked()
        val receipt = pendingStore.loadFinalReceipt() ?: return@withLock false
        if (receipt.importToken != expectedImportToken ||
            receipt.sessionId != expectedSessionId ||
            receipt.state != expectedState ||
            receipt.stage == FinalReceiptStage.CANCELLING ||
            mutableState.value != expectedState
        ) {
            return@withLock false
        }
        val raw = pendingStore.loadTerminalResult()
        pendingStore.acknowledgeFinalReceipt(receipt, raw) != null
    }

    suspend fun finalizeTerminal(
        expectedImportToken: String,
        expectedSessionId: Int?,
        expectedState: InstallState,
    ): Boolean = mutex.withLock {
        val receipt = when (val snapshot = pendingStore.finalReceiptSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> return@withLock true
            RecoveryRecordSnapshot.Invalid -> return@withLock false
        }
        if (receipt.importToken != expectedImportToken ||
            receipt.sessionId != expectedSessionId ||
            receipt.state != expectedState ||
            receipt.stage != FinalReceiptStage.ACKNOWLEDGED
        ) {
            return@withLock false
        }
        pendingStore.finalizeFinalReceipt(receipt).also { finalized ->
            if (finalized) activeBundle = null
        }
    }

    suspend fun cancel(
        expectedImportToken: String,
        expectedSessionId: Int?,
    ): InstallCleanupResult = mutex.withLock {
        if (pendingStore.normalHandoffSnapshot() != RecoveryRecordSnapshot.Absent) {
            return@withLock InstallCleanupResult.CleanupFailed
        }
        when (val snapshot = pendingStore.finalReceiptSnapshot()) {
            is RecoveryRecordSnapshot.Present -> {
                val receipt = snapshot.value
                if (receipt.importToken != expectedImportToken) {
                    return@withLock InstallCleanupResult.OwnerMismatch
                }
                if (expectedSessionId == null && receipt.sessionId != null) {
                    return@withLock InstallCleanupResult.OwnerSessionAssigned(receipt.sessionId)
                }
                if (expectedSessionId != receipt.sessionId) {
                    return@withLock InstallCleanupResult.OwnerMismatch
                }
                restoreTerminalResultLocked()
                return@withLock InstallCleanupResult.CleanupFailed
            }
            RecoveryRecordSnapshot.Invalid ->
                return@withLock InstallCleanupResult.CleanupFailed
            RecoveryRecordSnapshot.Absent -> Unit
        }
        val pending = try {
            pendingStore.load()
        } catch (_: Exception) {
            return@withLock InstallCleanupResult.CleanupFailed
        }
        if (pending != null) {
            if (pending.importToken != expectedImportToken) {
                return@withLock InstallCleanupResult.OwnerMismatch
            }
            if (expectedSessionId == null && pending.sessionId >= 0) {
                return@withLock InstallCleanupResult.OwnerSessionAssigned(pending.sessionId)
            }
            if (expectedSessionId != null && pending.sessionId != expectedSessionId) {
                return@withLock InstallCleanupResult.OwnerMismatch
            }
            if (pendingStore.loadTerminalResult() != null) {
                restoreTerminalResultLocked()
                return@withLock cleanupResult()
            }
            if (pending.sessionId >= 0) {
                return@withLock beginProgramCancel(pending)
            }
            val owned = try {
                gateway.ownedSessions()
            } catch (_: Exception) {
                mutableState.value = InstallState.Failed(
                    "SESSION_OWNERSHIP_QUERY_FAILED",
                    "설치 session 소유 상태를 확인하지 못했습니다.",
                )
                return@withLock InstallCleanupResult.CleanupFailed
            }
            if (owned.size == 1) {
                val recovered = recoverUnassignedSession(pending, owned.single())
                    ?: return@withLock InstallCleanupResult.CleanupFailed
                return@withLock InstallCleanupResult.OwnerSessionAssigned(recovered.sessionId)
            }
            if (owned.isNotEmpty()) {
                return@withLock InstallCleanupResult.CleanupFailed
            }
            return@withLock beginProgramCancel(pending)
        }

        val terminalResult = pendingStore.loadTerminalResult()
        if (terminalResult != null) {
            if (terminalResult.importToken == null ||
                terminalResult.importToken != expectedImportToken
            ) {
                return@withLock InstallCleanupResult.OwnerMismatch
            }
            if (expectedSessionId == null) {
                return@withLock InstallCleanupResult.OwnerSessionAssigned(
                    terminalResult.result.sessionId,
                )
            }
            if (terminalResult.result.sessionId != expectedSessionId) {
                return@withLock InstallCleanupResult.OwnerMismatch
            }
            restoreTerminalResultLocked()
            return@withLock InstallCleanupResult.CleanupFailed
        }
        val bundle = activeBundle ?: return@withLock InstallCleanupResult.OwnerMismatch
        if (bundle.token != expectedImportToken) {
            return@withLock InstallCleanupResult.OwnerMismatch
        }
        if (expectedSessionId != null) {
            return@withLock InstallCleanupResult.OwnerMismatch
        }
        val cancelling = programCancelReceipt(
            importToken = bundle.token,
            sessionId = null,
            packageName = bundle.verifiedApk.packageName,
            stage = FinalReceiptStage.CANCELLING,
        )
        if (!pendingStore.createFinalReceipt(null, null, cancelling)) {
            mutableState.value = cleanupFailed()
            return@withLock InstallCleanupResult.CleanupFailed
        }
        activeBundle = null
        settleProgramCancellation(cancelling, pending = null)
        InstallCleanupResult.CleanupFailed
    }

    fun unknownSourcesSettingsIntent() = gateway.unknownSourcesSettingsIntent()

    private suspend fun beginProgramCancel(
        pending: PendingInstall,
    ): InstallCleanupResult {
        val receipt = programCancelReceipt(
            importToken = pending.importToken,
            sessionId = pending.sessionId.takeIf { it >= 0 },
            packageName = pending.packageName,
            stage = FinalReceiptStage.CANCELLING,
        )
        if (!pendingStore.createFinalReceipt(pending, null, receipt)) {
            restoreTerminalResultLocked()
            return InstallCleanupResult.CleanupFailed
        }
        settleProgramCancellation(receipt, pending)
        return InstallCleanupResult.CleanupFailed
    }

    private suspend fun settleProgramCancellation(
        receipt: FinalInstallReceipt,
        pending: PendingInstall?,
    ) {
        if (receipt.origin != FinalReceiptOrigin.PROGRAM_CANCEL ||
            receipt.stage != FinalReceiptStage.CANCELLING
        ) {
            return
        }
        pendingStore.loadTerminalResult()?.let { raw ->
            reconcileRawWithFinal(receipt, pending, raw)
            return
        }
        if (!settleOwnedSessionForCancellation(receipt)) {
            mutableState.value = cleanupFailed()
            return
        }
        if (pending != null &&
            !pendingStore.clearPendingPreservingImport(pending)
        ) {
            mutableState.value = cleanupFailed()
            return
        }
        pendingStore.loadTerminalResult()?.let { raw ->
            reconcileRawWithFinal(receipt, pending = null, inbox = raw)
            return
        }
        val ready = receipt.copy(
            stage = FinalReceiptStage.READY,
            terminalAtEpochMillis = maxOf(receipt.terminalAtEpochMillis, clock()),
        )
        if (!pendingStore.replaceFinalReceipt(receipt, null, ready)) {
            mutableState.value = cleanupFailed()
            return
        }
        pendingStore.loadTerminalResult()?.let { raw ->
            reconcileRawWithFinal(ready, pending = null, inbox = raw)
            return
        }
        publishDurableReceipt(ready, pending = null)
    }

    private fun settleOwnedSessionForCancellation(
        receipt: FinalInstallReceipt,
    ): Boolean {
        val sessionId = receipt.sessionId ?: return true
        val before = try {
            gateway.sessionSnapshot(sessionId)
        } catch (_: Exception) {
            return false
        }
        if (before == null) return true
        if (!before.ownedByCaller ||
            before.sessionId != sessionId ||
            before.packageName != receipt.packageName
        ) {
            return false
        }
        try {
            gateway.abandon(sessionId)
        } catch (_: Exception) {
            return false
        }
        val after = try {
            gateway.sessionSnapshot(sessionId)
        } catch (_: Exception) {
            return false
        }
        return after == null
    }

    private fun programCancelReceipt(
        importToken: String,
        sessionId: Int?,
        packageName: String,
        stage: FinalReceiptStage,
    ) = FinalInstallReceipt(
        importToken = importToken,
        sessionId = sessionId,
        packageName = packageName,
        state = InstallState.Cancelled,
        stage = stage,
        origin = FinalReceiptOrigin.PROGRAM_CANCEL,
        rawTerminalAtEpochMillis = null,
        terminalAtEpochMillis = clock(),
    )

    private fun rawReceipt(
        importToken: String,
        sessionId: Int,
        packageName: String,
        inbox: PendingInstallResult,
        state: InstallState,
    ) = FinalInstallReceipt(
        importToken = importToken,
        sessionId = sessionId,
        packageName = packageName,
        state = state,
        stage = FinalReceiptStage.READY,
        origin = FinalReceiptOrigin.RAW_TERMINAL,
        rawTerminalAtEpochMillis = inbox.terminalAtEpochMillis,
        terminalAtEpochMillis = maxOf(inbox.terminalAtEpochMillis, clock()),
    )

    private fun rawReceipt(
        existing: FinalInstallReceipt,
        inbox: PendingInstallResult,
        state: InstallState,
    ): FinalInstallReceipt = rawReceipt(
        importToken = existing.importToken,
        sessionId = inbox.result.sessionId,
        packageName = existing.packageName,
        inbox = inbox,
        state = state,
    )

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
        val receipt = when (val snapshot = pendingStore.finalReceiptSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> {
                mutableState.value = cleanupFailed()
                return
            }
        }
        var inbox = when (val snapshot = pendingStore.terminalResultSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> {
                mutableState.value = cleanupFailed()
                return
            }
        }
        val pending = when (val snapshot = pendingStore.pendingInstallSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> {
                mutableState.value = cleanupFailed()
                return
            }
        }
        if (inbox?.importToken == null && inbox != null) {
            if (!pendingStore.quarantineTerminalResult(inbox, pending, receipt)) {
                mutableState.value = cleanupFailed()
                return
            }
            inbox = null
        }
        if (receipt != null) {
            if (receipt.stage == FinalReceiptStage.CANCELLING) {
                if (inbox != null) {
                    reconcileRawWithFinal(receipt, pending, inbox)
                } else {
                    settleProgramCancellation(receipt, pending)
                }
                return
            }
            if (receipt.origin == FinalReceiptOrigin.PROGRAM_CANCEL &&
                receipt.stage == FinalReceiptStage.READY &&
                inbox != null
            ) {
                reconcileRawWithFinal(receipt, pending, inbox)
                return
            }
            publishDurableReceipt(receipt, pending)
            return
        }
        if (inbox == null) return
        if (pending == null) {
            val next = inbox.toLegacyTerminalState()
            if (next == null) {
                mutableState.value = InstallState.Failed(
                    "TERMINAL_RECOVERY_FAILED",
                    "검증 완료 표지가 없는 설치 결과를 복구하지 못했습니다.",
                )
                return
            }
            persistRawReceipt(pending = null, inbox = inbox, next = next)
            return
        }
        if (pending.sessionId != inbox.result.sessionId ||
            inbox.importToken != pending.importToken ||
            inbox.packageName != pending.packageName
        ) {
            return
        }
        when (val result = inbox.result) {
            is PackageInstallResult.Success ->
                resolveSuccessfulRaw(pending, inbox)?.let { next ->
                    persistRawReceipt(pending, inbox, next)
                }
            is PackageInstallResult.Cancelled ->
                persistRawReceipt(pending, inbox, InstallState.Cancelled)
            is PackageInstallResult.Failure ->
                persistRawReceipt(
                    pending,
                    inbox,
                    InstallState.Failed(result.code, result.message),
                )
            is PackageInstallResult.PendingUserAction -> Unit
        }
    }

    private suspend fun reconcileRawWithFinal(
        receipt: FinalInstallReceipt,
        pending: PendingInstall?,
        inbox: PendingInstallResult,
    ) {
        if (receipt.stage == FinalReceiptStage.ACKNOWLEDGED ||
            inbox.importToken != receipt.importToken ||
            inbox.packageName != receipt.packageName ||
            receipt.sessionId != null &&
            receipt.sessionId != inbox.result.sessionId ||
            pending != null &&
            (pending.importToken != receipt.importToken ||
                pending.packageName != receipt.packageName ||
                pending.sessionId != inbox.result.sessionId)
        ) {
            return
        }
        val next = when (val result = inbox.result) {
            is PackageInstallResult.Success -> {
                val rawPending = pending ?: PendingInstall(
                    sessionId = inbox.result.sessionId,
                    packageName = receipt.packageName,
                    importToken = receipt.importToken,
                    startedAtEpochMillis = receipt.terminalAtEpochMillis,
                )
                resolveSuccessfulRaw(rawPending, inbox)
            }
            is PackageInstallResult.Cancelled -> InstallState.Cancelled
            is PackageInstallResult.Failure -> InstallState.Failed(result.code, result.message)
            is PackageInstallResult.PendingUserAction -> null
        } ?: return
        val replacement = rawReceipt(receipt, inbox, next)
        if (!pendingStore.replaceFinalReceipt(receipt, inbox, replacement)) {
            mutableState.value = cleanupFailed()
            return
        }
        publishDurableReceipt(replacement, pending)
    }

    private suspend fun persistRawReceipt(
        pending: PendingInstall?,
        inbox: PendingInstallResult,
        next: InstallState,
    ) {
        val receipt = rawReceipt(
            importToken = requireNotNull(inbox.importToken),
            sessionId = inbox.result.sessionId,
            packageName = inbox.packageName,
            inbox = inbox,
            state = next,
        )
        if (!pendingStore.createFinalReceipt(pending, inbox, receipt)) {
            mutableState.value = cleanupFailed()
            return
        }
        publishDurableReceipt(receipt, pending)
    }

    private fun publishDurableReceipt(
        receipt: FinalInstallReceipt,
        pending: PendingInstall?,
    ) {
        if (pending != null) {
            if (pending.importToken != receipt.importToken ||
                pending.packageName != receipt.packageName ||
                receipt.sessionId != null && pending.sessionId != receipt.sessionId ||
                !pendingStore.clearPendingPreservingImport(pending)
            ) {
                mutableState.value = cleanupFailed()
                return
            }
        }
        if (receipt.origin == FinalReceiptOrigin.RAW_TERMINAL) {
            activeBundle = null
        }
        publishTerminal(receipt.state)
    }

    private suspend fun resolveSuccessfulRaw(
        pending: PendingInstall,
        inbox: PendingInstallResult,
    ): InstallState? {
        val bundle = activeBundle
        if (bundle == null) {
            mutableState.value = InstallState.Failed(
                "IMPORT_NOT_AVAILABLE",
                "설치 결과를 검증할 임시 래퍼를 복원하지 못했습니다.",
            )
            return null
        }
        val installed = try {
            installedWrappers.find(pending.packageName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (!isExpectedInstall(pending, installed, bundle)) {
            return InstallState.Failed(
                "POST_INSTALL_VERIFICATION_FAILED",
                "설치된 래퍼를 검증할 수 없습니다.",
            )
        }
        val verified = requireNotNull(installed)
        val record = CatalogRecord(
            packageName = verified.packageName,
            extensionId = verified.extensionId,
            name = verified.name,
            iconPng = null,
            installedVersionCode = verified.versionCode,
            signerSha256 = verified.signerSha256,
            compatibilityStatus = bundle.report.status.name,
            reportJson = BundleJson.encodeCompatibility(bundle.report),
            lastVerifiedAtEpochMillis = clock(),
        )
        try {
            catalog.replaceVerified(record)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return InstallState.Failed(
                "CATALOG_UPDATE_FAILED",
                "설치 catalog를 갱신하지 못했습니다.",
            )
        }
        return InstallState.Installed(verified.packageName)
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
        when (val snapshot = pendingStore.finalReceiptSnapshot()) {
            is RecoveryRecordSnapshot.Present -> {
                if (snapshot.value.stage != FinalReceiptStage.CANCELLING) {
                    return snapshot.value.state
                }
            }
            RecoveryRecordSnapshot.Invalid -> return cleanupFailed()
            RecoveryRecordSnapshot.Absent -> Unit
        }
        return when (val snapshot = pendingStore.pendingInstallSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value.toState()
            RecoveryRecordSnapshot.Absent -> InstallState.Idle
            RecoveryRecordSnapshot.Invalid -> cleanupFailed()
        }
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
        if (cleanupInbox.toTerminalState() != next) {
            val importDiscarded = pendingStore.discardImportSafely(pending.importToken)
            val ledgerCleared = if (importDiscarded) {
                runCatching { pendingStore.clear(pending) }.getOrDefault(false)
            } else {
                false
            }
            val terminalCleared = ledgerCleared &&
                pendingStore.clearTerminalResult(cleanupInbox)
            if (!terminalCleared) {
                mutableState.value = cleanupFailed()
                return
            }
            activeBundle = null
            mutableState.value = next
            return
        }
        val ledgerCleared = runCatching { pendingStore.clear(pending) }.getOrDefault(false)
        if (!ledgerCleared) {
            mutableState.value = cleanupFailed()
            return
        }
        activeBundle = null
        publishTerminal(next)
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
            pendingStore.pendingInstallSnapshot() == RecoveryRecordSnapshot.Absent &&
            pendingStore.terminalResultSnapshot() == RecoveryRecordSnapshot.Absent &&
            pendingStore.finalReceiptSnapshot() == RecoveryRecordSnapshot.Absent &&
            pendingStore.normalHandoffSnapshot() == RecoveryRecordSnapshot.Absent
        if (complete) volatileConfirmation = null
        return complete
    }

    private fun cleanupResult(): InstallCleanupResult =
        if (cleanupComplete()) {
            InstallCleanupResult.Cleaned
        } else {
            InstallCleanupResult.CleanupFailed
        }

    private fun publishTerminal(state: InstallState) {
        mutableState.value = state
    }

    private fun PendingInstallResult.toTerminalState(): InstallState? =
        when (val terminal = result) {
            is PackageInstallResult.Success ->
                if (processed && packageName.isNotBlank()) {
                    InstallState.Installed(packageName)
                } else {
                    null
                }
            is PackageInstallResult.Cancelled -> InstallState.Cancelled
            is PackageInstallResult.Failure ->
                InstallState.Failed(terminal.code, terminal.message)
            is PackageInstallResult.PendingUserAction -> null
        }

    private fun PendingInstallResult.toLegacyTerminalState(): InstallState? =
        toTerminalState()

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

internal data class InstallRecoverySnapshot(
    val pending: PendingInstall?,
    val activeBundle: ImportedBundle?,
)
