package dev.sixbridge.manager.install

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

interface PendingInstallStore {
    fun load(): PendingInstall?
    fun pendingInstallSnapshot(): RecoveryRecordSnapshot<PendingInstall> =
        load()?.let { RecoveryRecordSnapshot.Present(it) }
            ?: RecoveryRecordSnapshot.Absent
    fun tryClaim(packageName: String, importToken: String, startedAtEpochMillis: Long): PendingInstall?
    fun assignSession(claim: PendingInstall, sessionId: Int): PendingInstall?
    fun recoverSession(claim: PendingInstall, sessionId: Int): PendingInstall?
    fun clear(expected: PendingInstall): Boolean
    fun clearWithoutTerminal(expected: PendingInstall): PendingCleanupResult
    fun discardImport(importToken: String)
    fun loadTerminalResult(): PendingInstallResult?
    fun terminalResultSnapshot(): RecoveryRecordSnapshot<PendingInstallResult> =
        loadTerminalResult()?.let { RecoveryRecordSnapshot.Present(it) }
            ?: RecoveryRecordSnapshot.Absent
    fun isConfirmationDelivered(sessionId: Int): Boolean = false
    fun markConfirmationDelivered(sessionId: Int): Boolean = true
    fun recordTerminalResult(result: PackageInstallResult, terminalAtEpochMillis: Long): Boolean
    fun markTerminalProcessed(expected: PendingInstallResult): PendingInstallResult?
    fun clearTerminalResult(expected: PendingInstallResult): Boolean
    fun loadFinalReceipt(): FinalInstallReceipt? = null
    fun finalReceiptSnapshot(): RecoveryRecordSnapshot<FinalInstallReceipt> =
        loadFinalReceipt()?.let { RecoveryRecordSnapshot.Present(it) }
            ?: RecoveryRecordSnapshot.Absent
    fun createFinalReceipt(
        expectedPending: PendingInstall?,
        expectedRaw: PendingInstallResult?,
        receipt: FinalInstallReceipt,
    ): Boolean = false
    fun replaceFinalReceipt(
        expected: FinalInstallReceipt,
        expectedRaw: PendingInstallResult?,
        replacement: FinalInstallReceipt,
    ): Boolean = false
    fun acknowledgeFinalReceipt(
        expected: FinalInstallReceipt,
        expectedRaw: PendingInstallResult?,
    ): FinalInstallReceipt? = null
    fun finalizeFinalReceipt(expected: FinalInstallReceipt): Boolean = false
    fun clearPendingPreservingImport(expected: PendingInstall): Boolean = false
    fun loadNormalHandoff(): NormalInstallHandoff? = null
    fun normalHandoffSnapshot(): RecoveryRecordSnapshot<NormalInstallHandoff> =
        loadNormalHandoff()?.let { RecoveryRecordSnapshot.Present(it) }
            ?: RecoveryRecordSnapshot.Absent
    fun recoveryStoreSnapshot(): RecoveryStoreSnapshot =
        combineRecoverySnapshots(
            pending = pendingInstallSnapshot(),
            terminal = terminalResultSnapshot(),
            finalReceipt = finalReceiptSnapshot(),
            normalHandoff = normalHandoffSnapshot(),
        )
    fun compareAndSetNormalHandoff(
        expected: NormalInstallHandoff?,
        replacement: NormalInstallHandoff?,
    ): Boolean = false
    fun rebaseNormalHandoff(
        expected: NormalInstallHandoff,
        replacement: NormalInstallHandoff,
    ): Boolean = false
    fun quarantineTerminalResult(
        expectedRaw: PendingInstallResult,
        expectedPending: PendingInstall?,
        expectedFinal: FinalInstallReceipt?,
    ): Boolean = false
    fun firstSessionMissingAt(pending: PendingInstall, observedAtEpochMillis: Long): Long
    fun clearSessionMissingObservation(pending: PendingInstall): Boolean
}

private fun combineRecoverySnapshots(
    pending: RecoveryRecordSnapshot<PendingInstall>,
    terminal: RecoveryRecordSnapshot<PendingInstallResult>,
    finalReceipt: RecoveryRecordSnapshot<FinalInstallReceipt>,
    normalHandoff: RecoveryRecordSnapshot<NormalInstallHandoff>,
): RecoveryStoreSnapshot {
    if (pending == RecoveryRecordSnapshot.Invalid ||
        terminal == RecoveryRecordSnapshot.Invalid ||
        finalReceipt == RecoveryRecordSnapshot.Invalid ||
        normalHandoff == RecoveryRecordSnapshot.Invalid
    ) {
        return RecoveryStoreSnapshot.Invalid
    }
    return RecoveryStoreSnapshot.Valid(
        pending = when (pending) {
            is RecoveryRecordSnapshot.Present -> pending.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> error("invalid recovery snapshot")
        },
        terminal = when (terminal) {
            is RecoveryRecordSnapshot.Present -> terminal.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> error("invalid recovery snapshot")
        },
        finalReceipt = when (finalReceipt) {
            is RecoveryRecordSnapshot.Present -> finalReceipt.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> error("invalid recovery snapshot")
        },
        normalHandoff = when (normalHandoff) {
            is RecoveryRecordSnapshot.Present -> normalHandoff.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> error("invalid recovery snapshot")
        },
    )
}

class SharedPreferencesPendingInstallStore internal constructor(
    private val pendingPreferences: SharedPreferences,
    private val recoveryPreferences: SharedPreferences,
    private val importDirectory: File,
) : PendingInstallStore {
    constructor(context: Context) : this(
        pendingPreferences = context.getSharedPreferences(PENDING_PREFERENCES_NAME, Context.MODE_PRIVATE),
        recoveryPreferences = context.getSharedPreferences(RECOVERY_PREFERENCES_NAME, Context.MODE_PRIVATE),
        importDirectory = File(context.cacheDir, IMPORT_DIRECTORY),
    )

    override fun load(): PendingInstall? = synchronized(PROCESS_LOCK) {
        loadPendingLocked()
    }

    override fun pendingInstallSnapshot(): RecoveryRecordSnapshot<PendingInstall> =
        synchronized(PROCESS_LOCK) {
            pendingInstallSnapshotLocked()
        }

    override fun tryClaim(
        packageName: String,
        importToken: String,
        startedAtEpochMillis: Long,
    ): PendingInstall? = synchronized(PROCESS_LOCK) {
        if (pendingInstallSnapshotLocked() != RecoveryRecordSnapshot.Absent ||
            terminalResultSnapshotLocked() != RecoveryRecordSnapshot.Absent ||
            finalReceiptSnapshotLocked() != RecoveryRecordSnapshot.Absent ||
            normalHandoffSnapshotLocked() != RecoveryRecordSnapshot.Absent
        ) {
            return@synchronized null
        }
        if (!recoveryPreferences.edit().remove(KEY_CONFIRMATION_SESSION_ID).commit()) {
            return@synchronized null
        }
        val claim = PendingInstall(
            sessionId = UNASSIGNED_SESSION_ID,
            packageName = packageName,
            importToken = importToken,
            startedAtEpochMillis = startedAtEpochMillis,
        )
        require(isValid(claim)) { "올바르지 않은 pending install claim입니다." }
        if (!writePendingLocked(claim)) return@synchronized null
        claim
    }

    override fun assignSession(claim: PendingInstall, sessionId: Int): PendingInstall? =
        synchronized(PROCESS_LOCK) {
            if (sessionId < 0 || claim.sessionId != UNASSIGNED_SESSION_ID) return@synchronized null
            if (loadPendingLocked() != claim) return@synchronized null
            val pending = claim.copy(sessionId = sessionId)
            if (!pendingPreferences.edit().putInt(KEY_SESSION_ID, sessionId).commit()) {
                return@synchronized null
            }
            if (!recoveryPreferences.edit()
                .remove(KEY_RECOVERY_SESSION_ID)
                .remove(KEY_RECOVERY_IMPORT_TOKEN)
                .remove(KEY_CONFIRMATION_SESSION_ID)
                .commit()
            ) {
                return@synchronized null
            }
            pending
        }

    override fun recoverSession(
        claim: PendingInstall,
        sessionId: Int,
    ): PendingInstall? = synchronized(PROCESS_LOCK) {
        if (sessionId < 0 || claim.sessionId != UNASSIGNED_SESSION_ID) return@synchronized null
        val recovered = claim.copy(sessionId = sessionId)
        val current = loadPendingLocked()
        if (current != claim && current != recovered) return@synchronized null
        if (!recoveryPreferences.edit()
                .putInt(KEY_RECOVERY_SESSION_ID, sessionId)
                .putString(KEY_RECOVERY_IMPORT_TOKEN, claim.importToken)
                .commit()
        ) {
            return@synchronized null
        }
        recovered
    }

    override fun clear(expected: PendingInstall): Boolean = synchronized(PROCESS_LOCK) {
        if (loadPendingLocked() != expected) return@synchronized false
        clearPendingLocked()
    }

    override fun clearWithoutTerminal(
        expected: PendingInstall,
    ): PendingCleanupResult = synchronized(PROCESS_LOCK) {
        if (loadPendingLocked() != expected) return@synchronized PendingCleanupResult.Failed
        if (terminalResultSnapshotLocked() != RecoveryRecordSnapshot.Absent ||
            finalReceiptSnapshotLocked() != RecoveryRecordSnapshot.Absent
        ) {
            return@synchronized PendingCleanupResult.TerminalAvailable
        }
        val importDiscarded = runCatching {
            discardImport(expected.importToken)
        }.isSuccess
        if (importDiscarded && clearPendingLocked()) {
            PendingCleanupResult.Cleared
        } else {
            PendingCleanupResult.Failed
        }
    }

    override fun discardImport(importToken: String) {
        if (!isCanonicalToken(importToken)) return
        val root = canonicalImportRoot() ?: return
        val target = File(root, importToken)
        if (runCatching { target.canonicalFile.parentFile == root }.getOrDefault(false)) {
            check(deleteTree(target)) { "임시 import를 삭제하지 못했습니다." }
        }
    }

    override fun loadTerminalResult(): PendingInstallResult? = synchronized(PROCESS_LOCK) {
        loadTerminalLocked()
    }

    override fun terminalResultSnapshot(): RecoveryRecordSnapshot<PendingInstallResult> =
        synchronized(PROCESS_LOCK) {
            terminalResultSnapshotLocked()
        }

    override fun markConfirmationDelivered(sessionId: Int): Boolean =
        synchronized(PROCESS_LOCK) {
            if (loadPendingLocked()?.sessionId != sessionId) return@synchronized false
            if (recoveryPreferences.getInt(KEY_CONFIRMATION_SESSION_ID, -1) == sessionId) {
                return@synchronized false
            }
            recoveryPreferences.edit()
                .putInt(KEY_CONFIRMATION_SESSION_ID, sessionId)
                .commit()
        }

    override fun isConfirmationDelivered(sessionId: Int): Boolean =
        synchronized(PROCESS_LOCK) {
            loadPendingLocked()?.sessionId == sessionId &&
                recoveryPreferences.getInt(KEY_CONFIRMATION_SESSION_ID, -1) == sessionId
        }

    override fun recordTerminalResult(
        result: PackageInstallResult,
        terminalAtEpochMillis: Long,
    ): Boolean = synchronized(PROCESS_LOCK) {
        if (result is PackageInstallResult.PendingUserAction || terminalAtEpochMillis <= 0) {
            return@synchronized false
        }
        val pending = when (val snapshot = pendingInstallSnapshotLocked()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> return@synchronized false
        }
        val lateProgramCancel = when (val snapshot = finalReceiptSnapshotLocked()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value.takeIf { receipt ->
                pending == null &&
                    receipt.origin == FinalReceiptOrigin.PROGRAM_CANCEL &&
                    receipt.stage != FinalReceiptStage.ACKNOWLEDGED &&
                    receipt.sessionId == result.sessionId
            }
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> return@synchronized false
        }
        if (pending?.sessionId != result.sessionId && lateProgramCancel == null) {
            return@synchronized false
        }
        val sanitized = PendingInstallResult(
            result = sanitize(result),
            terminalAtEpochMillis = terminalAtEpochMillis,
            packageName = pending?.packageName ?: requireNotNull(lateProgramCancel).packageName,
            importToken = pending?.importToken ?: requireNotNull(lateProgramCancel).importToken,
        )
        if (terminalResultSnapshotLocked() != RecoveryRecordSnapshot.Absent) {
            return@synchronized false
        }
        val editor = recoveryPreferences.edit()
            .putInt(KEY_RESULT_SESSION_ID, sanitized.result.sessionId)
            .putString(KEY_RESULT_KIND, sanitized.result.kind())
            .putString(KEY_RESULT_PACKAGE_NAME, sanitized.packageName)
            .putString(KEY_RESULT_IMPORT_TOKEN, sanitized.importToken)
            .putLong(KEY_TERMINAL_AT, sanitized.terminalAtEpochMillis)
            .remove(KEY_RESULT_PROCESSED)
            .remove(KEY_CONFIRMATION_SESSION_ID)
            .remove(KEY_MISSING_SESSION_ID)
            .remove(KEY_MISSING_IMPORT_TOKEN)
            .remove(KEY_MISSING_OBSERVED_AT)
        if (sanitized.result is PackageInstallResult.Failure) {
            editor
                .putString(KEY_RESULT_CODE, sanitized.result.code)
                .putString(KEY_RESULT_MESSAGE, sanitized.result.message)
        } else {
            editor.remove(KEY_RESULT_CODE).remove(KEY_RESULT_MESSAGE)
        }
        editor.commit()
    }

    override fun markTerminalProcessed(
        expected: PendingInstallResult,
    ): PendingInstallResult? = synchronized(PROCESS_LOCK) {
        if (loadTerminalLocked() != expected) return@synchronized null
        if (!recoveryPreferences.edit().putBoolean(KEY_RESULT_PROCESSED, true).commit()) {
            return@synchronized null
        }
        expected.copy(processed = true)
    }

    override fun clearTerminalResult(expected: PendingInstallResult): Boolean =
        synchronized(PROCESS_LOCK) {
            if (!terminalResultSnapshotLocked().matchesExpected(expected) ||
                finalReceiptSnapshotLocked() != RecoveryRecordSnapshot.Absent
            ) {
                return@synchronized false
            }
            clearRawTerminal(recoveryPreferences.edit()).commit()
        }

    override fun loadFinalReceipt(): FinalInstallReceipt? = synchronized(PROCESS_LOCK) {
        loadFinalReceiptLocked()
    }

    override fun finalReceiptSnapshot(): RecoveryRecordSnapshot<FinalInstallReceipt> =
        synchronized(PROCESS_LOCK) {
            finalReceiptSnapshotLocked()
        }

    override fun createFinalReceipt(
        expectedPending: PendingInstall?,
        expectedRaw: PendingInstallResult?,
        receipt: FinalInstallReceipt,
    ): Boolean = synchronized(PROCESS_LOCK) {
        if (!isValidInitialReceipt(receipt) ||
            !receipt.matchesRaw(expectedRaw) ||
            !pendingInstallSnapshotLocked().matchesExpected(expectedPending) ||
            !terminalResultSnapshotLocked().matchesExpected(expectedRaw) ||
            finalReceiptSnapshotLocked() != RecoveryRecordSnapshot.Absent
        ) {
            return@synchronized false
        }
        writeFinalReceiptLocked(receipt)
    }

    override fun replaceFinalReceipt(
        expected: FinalInstallReceipt,
        expectedRaw: PendingInstallResult?,
        replacement: FinalInstallReceipt,
    ): Boolean = synchronized(PROCESS_LOCK) {
        if (!isValid(replacement) ||
            !finalReceiptSnapshotLocked().matchesExpected(expected) ||
            !terminalResultSnapshotLocked().matchesExpected(expectedRaw) ||
            expected.importToken != replacement.importToken ||
            expected.packageName != replacement.packageName
        ) {
            return@synchronized false
        }
        if (!replacement.matchesRaw(expectedRaw)) return@synchronized false
        val validTransition = when {
            expected.stage == FinalReceiptStage.CANCELLING &&
                expected.origin == FinalReceiptOrigin.PROGRAM_CANCEL &&
                replacement.stage == FinalReceiptStage.READY &&
                replacement.origin == FinalReceiptOrigin.PROGRAM_CANCEL &&
                expectedRaw == null &&
                expected.sessionId == replacement.sessionId -> true
            expected.stage == FinalReceiptStage.CANCELLING &&
                expected.origin == FinalReceiptOrigin.PROGRAM_CANCEL &&
                replacement.stage == FinalReceiptStage.READY &&
                replacement.origin == FinalReceiptOrigin.RAW_TERMINAL &&
                expectedRaw != null &&
                (expected.sessionId == null ||
                    expected.sessionId == replacement.sessionId) -> true
            expected.stage == FinalReceiptStage.READY &&
                expected.origin == FinalReceiptOrigin.PROGRAM_CANCEL &&
                replacement.stage == FinalReceiptStage.READY &&
                replacement.origin == FinalReceiptOrigin.RAW_TERMINAL &&
                expectedRaw != null &&
                expected.sessionId == replacement.sessionId -> true
            else -> false
        }
        if (!validTransition) return@synchronized false
        writeFinalReceiptLocked(replacement)
    }

    override fun acknowledgeFinalReceipt(
        expected: FinalInstallReceipt,
        expectedRaw: PendingInstallResult?,
    ): FinalInstallReceipt? = synchronized(PROCESS_LOCK) {
        val current = when (val snapshot = finalReceiptSnapshotLocked()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent,
            RecoveryRecordSnapshot.Invalid,
            -> return@synchronized null
        }
        if (current != expected ||
            !terminalResultSnapshotLocked().matchesExpected(expectedRaw) ||
            !current.matchesRaw(expectedRaw)
        ) {
            return@synchronized null
        }
        if (current.stage == FinalReceiptStage.ACKNOWLEDGED) {
            return@synchronized current
        }
        if (current.stage != FinalReceiptStage.READY) return@synchronized null
        val acknowledged = current.copy(stage = FinalReceiptStage.ACKNOWLEDGED)
        acknowledged.takeIf { writeFinalReceiptLocked(it) }
    }

    override fun finalizeFinalReceipt(
        expected: FinalInstallReceipt,
    ): Boolean = synchronized(PROCESS_LOCK) {
        val current = when (val snapshot = finalReceiptSnapshotLocked()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> return@synchronized true
            RecoveryRecordSnapshot.Invalid -> return@synchronized false
        }
        if (current != expected || current.stage != FinalReceiptStage.ACKNOWLEDGED) {
            return@synchronized false
        }
        val raw = when (val snapshot = terminalResultSnapshotLocked()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> return@synchronized false
        }
        if (raw != null && !raw.matches(current)) return@synchronized false
        val editor = clearFinalReceipt(recoveryPreferences.edit())
        if (raw != null) clearRawTerminal(editor)
        editor.commit()
    }

    override fun clearPendingPreservingImport(
        expected: PendingInstall,
    ): Boolean = synchronized(PROCESS_LOCK) {
        if (loadPendingLocked() != expected) return@synchronized false
        pendingPreferences.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_PACKAGE_NAME)
            .remove(KEY_IMPORT_TOKEN)
            .remove(KEY_STARTED_AT)
            .commit()
    }

    override fun loadNormalHandoff(): NormalInstallHandoff? =
        synchronized(PROCESS_LOCK) {
            loadNormalHandoffLocked()
        }

    override fun normalHandoffSnapshot(): RecoveryRecordSnapshot<NormalInstallHandoff> =
        synchronized(PROCESS_LOCK) {
            normalHandoffSnapshotLocked()
        }

    override fun recoveryStoreSnapshot(): RecoveryStoreSnapshot =
        synchronized(PROCESS_LOCK) {
            combineRecoverySnapshots(
                pending = pendingInstallSnapshotLocked(),
                terminal = terminalResultSnapshotLocked(),
                finalReceipt = finalReceiptSnapshotLocked(),
                normalHandoff = normalHandoffSnapshotLocked(),
            )
        }

    override fun compareAndSetNormalHandoff(
        expected: NormalInstallHandoff?,
        replacement: NormalInstallHandoff?,
    ): Boolean = synchronized(PROCESS_LOCK) {
        val current = when (val snapshot = normalHandoffSnapshotLocked()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> return@synchronized false
        }
        if (current != expected ||
            replacement?.let(::isValid) == false
        ) {
            return@synchronized false
        }
        val validTransition = when {
            expected == null ->
                replacement?.stage == NormalHandoffStage.PREPARED
            expected.stage == NormalHandoffStage.PREPARED ->
                replacement?.stage == NormalHandoffStage.OUTCOME_READY
            expected.stage == NormalHandoffStage.OUTCOME_READY ->
                replacement == null
            else -> false
        }
        if (!validTransition) return@synchronized false
        if (expected != null && replacement != null &&
            (expected.importToken != replacement.importToken ||
                expected.sessionId != replacement.sessionId ||
                expected.packageName != replacement.packageName ||
                expected.state != replacement.state)
        ) {
            return@synchronized false
        }
        if (replacement == null) {
            clearNormalHandoff(recoveryPreferences.edit()).commit()
        } else {
            writeNormalHandoffLocked(replacement)
        }
    }

    override fun rebaseNormalHandoff(
        expected: NormalInstallHandoff,
        replacement: NormalInstallHandoff,
    ): Boolean = synchronized(PROCESS_LOCK) {
        val currentFinal = when (val snapshot = finalReceiptSnapshotLocked()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent,
            RecoveryRecordSnapshot.Invalid,
            -> return@synchronized false
        }
        if (!normalHandoffSnapshotLocked().matchesExpected(expected) ||
            !isValid(replacement) ||
            expected.stage != NormalHandoffStage.PREPARED ||
            replacement.stage != NormalHandoffStage.PREPARED ||
            expected.importToken != replacement.importToken ||
            expected.sessionId != replacement.sessionId ||
            expected.packageName != replacement.packageName ||
            currentFinal.origin != FinalReceiptOrigin.RAW_TERMINAL ||
            currentFinal.stage != FinalReceiptStage.READY ||
            currentFinal.importToken != replacement.importToken ||
            currentFinal.sessionId != replacement.sessionId ||
            currentFinal.packageName != replacement.packageName ||
            currentFinal.state != replacement.state
        ) {
            return@synchronized false
        }
        writeNormalHandoffLocked(replacement)
    }

    override fun quarantineTerminalResult(
        expectedRaw: PendingInstallResult,
        expectedPending: PendingInstall?,
        expectedFinal: FinalInstallReceipt?,
    ): Boolean = synchronized(PROCESS_LOCK) {
        val currentFinal = when (val snapshot = finalReceiptSnapshotLocked()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent -> null
            RecoveryRecordSnapshot.Invalid -> return@synchronized false
        }
        if (expectedRaw.importToken != null ||
            !terminalResultSnapshotLocked().matchesExpected(expectedRaw) ||
            !pendingInstallSnapshotLocked().matchesExpected(expectedPending) ||
            currentFinal != expectedFinal
        ) {
            return@synchronized false
        }
        clearRawTerminal(recoveryPreferences.edit()).commit()
    }

    override fun firstSessionMissingAt(
        pending: PendingInstall,
        observedAtEpochMillis: Long,
    ): Long = synchronized(PROCESS_LOCK) {
        require(pending.sessionId >= UNASSIGNED_SESSION_ID && observedAtEpochMillis > 0)
        val existingSession = recoveryPreferences.getInt(KEY_MISSING_SESSION_ID, Int.MIN_VALUE)
        val existingToken = recoveryPreferences.getString(KEY_MISSING_IMPORT_TOKEN, null)
        val existingTime = recoveryPreferences.getLong(KEY_MISSING_OBSERVED_AT, -1)
        if (existingSession == pending.sessionId &&
            existingToken == pending.importToken &&
            existingTime > 0
        ) {
            return@synchronized existingTime
        }
        check(
            recoveryPreferences.edit()
                .putInt(KEY_MISSING_SESSION_ID, pending.sessionId)
                .putString(KEY_MISSING_IMPORT_TOKEN, pending.importToken)
                .putLong(KEY_MISSING_OBSERVED_AT, observedAtEpochMillis)
                .commit(),
        ) {
            "session 부재 관측 시각을 저장하지 못했습니다."
        }
        observedAtEpochMillis
    }

    override fun clearSessionMissingObservation(pending: PendingInstall): Boolean =
        synchronized(PROCESS_LOCK) {
            val existingSession = recoveryPreferences.getInt(
                KEY_MISSING_SESSION_ID,
                Int.MIN_VALUE,
            )
            val existingToken = recoveryPreferences.getString(KEY_MISSING_IMPORT_TOKEN, null)
            if (existingSession != pending.sessionId || existingToken != pending.importToken) {
                return@synchronized true
            }
            recoveryPreferences.edit()
                .remove(KEY_MISSING_SESSION_ID)
                .remove(KEY_MISSING_IMPORT_TOKEN)
                .remove(KEY_MISSING_OBSERVED_AT)
                .commit()
        }

    private fun loadPendingLocked(): PendingInstall? {
        if (!pendingPreferences.contains(KEY_SESSION_ID)) return null
        val rawSessionId = pendingPreferences.getInt(KEY_SESSION_ID, Int.MIN_VALUE)
        val importToken = pendingPreferences.getString(KEY_IMPORT_TOKEN, null).orEmpty()
        val recoveredSessionId = recoveryPreferences.getInt(
            KEY_RECOVERY_SESSION_ID,
            Int.MIN_VALUE,
        )
        val recoveredToken = recoveryPreferences.getString(KEY_RECOVERY_IMPORT_TOKEN, null)
        val sessionId = if (
            rawSessionId == UNASSIGNED_SESSION_ID &&
            recoveredSessionId >= 0 &&
            recoveredToken == importToken
        ) {
            recoveredSessionId
        } else {
            rawSessionId
        }
        val pending = PendingInstall(
            sessionId = sessionId,
            packageName = pendingPreferences.getString(KEY_PACKAGE_NAME, null).orEmpty(),
            importToken = importToken,
            startedAtEpochMillis = pendingPreferences.getLong(KEY_STARTED_AT, -1),
        )
        return pending.takeIf(::isValid)
    }

    private fun pendingInstallSnapshotLocked(): RecoveryRecordSnapshot<PendingInstall> {
        val markerPresent = listOf(
            KEY_SESSION_ID,
            KEY_PACKAGE_NAME,
            KEY_IMPORT_TOKEN,
            KEY_STARTED_AT,
        ).any(pendingPreferences::contains) || listOf(
            KEY_RECOVERY_SESSION_ID,
            KEY_RECOVERY_IMPORT_TOKEN,
        ).any(recoveryPreferences::contains)
        if (!markerPresent) return RecoveryRecordSnapshot.Absent
        return runCatching { loadPendingLocked() }.getOrNull()
            ?.let { RecoveryRecordSnapshot.Present(it) }
            ?: RecoveryRecordSnapshot.Invalid
    }

    private fun writePendingLocked(pending: PendingInstall): Boolean =
        pendingPreferences.edit()
            .putInt(KEY_SESSION_ID, pending.sessionId)
            .putString(KEY_PACKAGE_NAME, pending.packageName)
            .putString(KEY_IMPORT_TOKEN, pending.importToken)
            .putLong(KEY_STARTED_AT, pending.startedAtEpochMillis)
            .commit()

    private fun clearPendingLocked(): Boolean {
        val pendingCleared = pendingPreferences.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_PACKAGE_NAME)
            .remove(KEY_IMPORT_TOKEN)
            .remove(KEY_STARTED_AT)
            .commit()
        val missingObservationCleared = recoveryPreferences.edit()
            .remove(KEY_RECOVERY_SESSION_ID)
            .remove(KEY_RECOVERY_IMPORT_TOKEN)
            .remove(KEY_CONFIRMATION_SESSION_ID)
            .remove(KEY_MISSING_SESSION_ID)
            .remove(KEY_MISSING_IMPORT_TOKEN)
            .remove(KEY_MISSING_OBSERVED_AT)
            .commit()
        return pendingCleared && missingObservationCleared
    }

    private fun loadTerminalLocked(): PendingInstallResult? {
        val sessionId = recoveryPreferences.getInt(KEY_RESULT_SESSION_ID, -1)
        val terminalAt = recoveryPreferences.getLong(KEY_TERMINAL_AT, -1)
        val packageName = recoveryPreferences.getString(KEY_RESULT_PACKAGE_NAME, null).orEmpty()
        if (sessionId < 0 || terminalAt <= 0 || !PACKAGE_NAME.matches(packageName)) return null
        val importToken = if (recoveryPreferences.contains(KEY_RESULT_IMPORT_TOKEN)) {
            recoveryPreferences.getString(KEY_RESULT_IMPORT_TOKEN, null)
                ?.takeIf(::isCanonicalToken)
                ?: return null
        } else {
            null
        }
        val resultKind = recoveryPreferences.getString(KEY_RESULT_KIND, null)
        if (resultKind != RESULT_FAILURE &&
            (recoveryPreferences.contains(KEY_RESULT_CODE) ||
                recoveryPreferences.contains(KEY_RESULT_MESSAGE))
        ) {
            return null
        }
        val result = when (resultKind) {
            RESULT_SUCCESS -> PackageInstallResult.Success(sessionId)
            RESULT_CANCELLED -> PackageInstallResult.Cancelled(sessionId)
            RESULT_FAILURE -> PackageInstallResult.Failure(
                sessionId = sessionId,
                code = if (recoveryPreferences.contains(KEY_RESULT_CODE)) {
                    recoveryPreferences.getString(KEY_RESULT_CODE, null)
                        ?.takeIf(::isValidFailureCode)
                        ?: return null
                } else {
                    DEFAULT_FAILURE_CODE
                },
                message = if (recoveryPreferences.contains(KEY_RESULT_MESSAGE)) {
                    recoveryPreferences.getString(KEY_RESULT_MESSAGE, null)
                        ?.takeIf(::isValidFailureMessage)
                        ?: return null
                } else {
                    DEFAULT_FAILURE_MESSAGE
                },
            )
            else -> return null
        }
        return PendingInstallResult(
            result = result,
            terminalAtEpochMillis = terminalAt,
            packageName = packageName,
            importToken = importToken,
            processed = recoveryPreferences.getBoolean(KEY_RESULT_PROCESSED, false),
        )
    }

    private fun terminalResultSnapshotLocked(): RecoveryRecordSnapshot<PendingInstallResult> {
        val markerPresent = listOf(
            KEY_RESULT_SESSION_ID,
            KEY_RESULT_KIND,
            KEY_RESULT_CODE,
            KEY_RESULT_MESSAGE,
            KEY_RESULT_PACKAGE_NAME,
            KEY_RESULT_IMPORT_TOKEN,
            KEY_RESULT_PROCESSED,
            KEY_TERMINAL_AT,
        ).any(recoveryPreferences::contains)
        if (!markerPresent) return RecoveryRecordSnapshot.Absent
        return runCatching { loadTerminalLocked() }.getOrNull()
            ?.let { RecoveryRecordSnapshot.Present(it) }
            ?: RecoveryRecordSnapshot.Invalid
    }

    private fun loadFinalReceiptLocked(): FinalInstallReceipt? {
        if (!recoveryPreferences.contains(KEY_FINAL_VERSION)) return null
        val version = recoveryPreferences.getInt(KEY_FINAL_VERSION, -1)
        val token = recoveryPreferences.getString(KEY_FINAL_IMPORT_TOKEN, null).orEmpty()
        val packageName = recoveryPreferences.getString(KEY_FINAL_PACKAGE_NAME, null).orEmpty()
        val hasSession = recoveryPreferences.getBoolean(KEY_FINAL_HAS_SESSION, false)
        val sessionId = recoveryPreferences.getInt(KEY_FINAL_SESSION_ID, -1)
            .takeIf { hasSession }
        val stage = enumValueOrNull<FinalReceiptStage>(
            recoveryPreferences.getString(KEY_FINAL_STAGE, null),
        ) ?: return null
        val origin = enumValueOrNull<FinalReceiptOrigin>(
            recoveryPreferences.getString(KEY_FINAL_ORIGIN, null),
        ) ?: return null
        val rawTerminalAt = recoveryPreferences.getLong(KEY_FINAL_RAW_TERMINAL_AT, -1)
            .takeIf { it > 0 }
        val terminalAt = recoveryPreferences.getLong(KEY_FINAL_TERMINAL_AT, -1)
        val state = loadInstallState(
            kindKey = KEY_FINAL_STATE_KIND,
            codeKey = KEY_FINAL_STATE_CODE,
            messageKey = KEY_FINAL_STATE_MESSAGE,
            packageName = packageName,
        ) ?: return null
        return runCatching {
            FinalInstallReceipt(
                importToken = token,
                sessionId = sessionId,
                packageName = packageName,
                state = state,
                stage = stage,
                origin = origin,
                rawTerminalAtEpochMillis = rawTerminalAt,
                terminalAtEpochMillis = terminalAt,
                version = version,
            )
        }.getOrNull()?.takeIf(::isValid)
    }

    private fun finalReceiptSnapshotLocked(): RecoveryRecordSnapshot<FinalInstallReceipt> {
        val markerPresent = listOf(
            KEY_FINAL_VERSION,
            KEY_FINAL_IMPORT_TOKEN,
            KEY_FINAL_HAS_SESSION,
            KEY_FINAL_SESSION_ID,
            KEY_FINAL_PACKAGE_NAME,
            KEY_FINAL_STATE_KIND,
            KEY_FINAL_STATE_CODE,
            KEY_FINAL_STATE_MESSAGE,
            KEY_FINAL_STAGE,
            KEY_FINAL_ORIGIN,
            KEY_FINAL_RAW_TERMINAL_AT,
            KEY_FINAL_TERMINAL_AT,
        ).any(recoveryPreferences::contains)
        if (!markerPresent) return RecoveryRecordSnapshot.Absent
        return runCatching { loadFinalReceiptLocked() }.getOrNull()
            ?.let { RecoveryRecordSnapshot.Present(it) }
            ?: RecoveryRecordSnapshot.Invalid
    }

    private fun writeFinalReceiptLocked(receipt: FinalInstallReceipt): Boolean {
        val editor = recoveryPreferences.edit()
            .putInt(KEY_FINAL_VERSION, receipt.version)
            .putString(KEY_FINAL_IMPORT_TOKEN, receipt.importToken)
            .putBoolean(KEY_FINAL_HAS_SESSION, receipt.sessionId != null)
            .putString(KEY_FINAL_PACKAGE_NAME, receipt.packageName)
            .putString(KEY_FINAL_STAGE, receipt.stage.name)
            .putString(KEY_FINAL_ORIGIN, receipt.origin.name)
            .putLong(KEY_FINAL_TERMINAL_AT, receipt.terminalAtEpochMillis)
        receipt.sessionId?.let { editor.putInt(KEY_FINAL_SESSION_ID, it) }
            ?: editor.remove(KEY_FINAL_SESSION_ID)
        receipt.rawTerminalAtEpochMillis?.let {
            editor.putLong(KEY_FINAL_RAW_TERMINAL_AT, it)
        } ?: editor.remove(KEY_FINAL_RAW_TERMINAL_AT)
        writeInstallState(
            editor = editor,
            state = receipt.state,
            kindKey = KEY_FINAL_STATE_KIND,
            codeKey = KEY_FINAL_STATE_CODE,
            messageKey = KEY_FINAL_STATE_MESSAGE,
        )
        return editor.commit()
    }

    private fun loadNormalHandoffLocked(): NormalInstallHandoff? {
        if (!recoveryPreferences.contains(KEY_HANDOFF_VERSION)) return null
        val version = recoveryPreferences.getInt(KEY_HANDOFF_VERSION, -1)
        val token = recoveryPreferences.getString(KEY_HANDOFF_IMPORT_TOKEN, null).orEmpty()
        val packageName = recoveryPreferences.getString(KEY_HANDOFF_PACKAGE_NAME, null).orEmpty()
        val hasSession = recoveryPreferences.getBoolean(KEY_HANDOFF_HAS_SESSION, false)
        val sessionId = recoveryPreferences.getInt(KEY_HANDOFF_SESSION_ID, -1)
            .takeIf { hasSession }
        val stage = enumValueOrNull<NormalHandoffStage>(
            recoveryPreferences.getString(KEY_HANDOFF_STAGE, null),
        ) ?: return null
        val state = loadInstallState(
            kindKey = KEY_HANDOFF_STATE_KIND,
            codeKey = KEY_HANDOFF_STATE_CODE,
            messageKey = KEY_HANDOFF_STATE_MESSAGE,
            packageName = packageName,
        ) ?: return null
        return runCatching {
            NormalInstallHandoff(
                importToken = token,
                sessionId = sessionId,
                packageName = packageName,
                state = state,
                stage = stage,
                version = version,
            )
        }.getOrNull()?.takeIf(::isValid)
    }

    private fun normalHandoffSnapshotLocked(): RecoveryRecordSnapshot<NormalInstallHandoff> {
        val markerPresent = listOf(
            KEY_HANDOFF_VERSION,
            KEY_HANDOFF_IMPORT_TOKEN,
            KEY_HANDOFF_HAS_SESSION,
            KEY_HANDOFF_SESSION_ID,
            KEY_HANDOFF_PACKAGE_NAME,
            KEY_HANDOFF_STATE_KIND,
            KEY_HANDOFF_STATE_CODE,
            KEY_HANDOFF_STATE_MESSAGE,
            KEY_HANDOFF_STAGE,
        ).any(recoveryPreferences::contains)
        if (!markerPresent) return RecoveryRecordSnapshot.Absent
        return runCatching { loadNormalHandoffLocked() }.getOrNull()
            ?.let { RecoveryRecordSnapshot.Present(it) }
            ?: RecoveryRecordSnapshot.Invalid
    }

    private fun writeNormalHandoffLocked(handoff: NormalInstallHandoff): Boolean {
        val editor = recoveryPreferences.edit()
            .putInt(KEY_HANDOFF_VERSION, handoff.version)
            .putString(KEY_HANDOFF_IMPORT_TOKEN, handoff.importToken)
            .putBoolean(KEY_HANDOFF_HAS_SESSION, handoff.sessionId != null)
            .putString(KEY_HANDOFF_PACKAGE_NAME, handoff.packageName)
            .putString(KEY_HANDOFF_STAGE, handoff.stage.name)
        handoff.sessionId?.let { editor.putInt(KEY_HANDOFF_SESSION_ID, it) }
            ?: editor.remove(KEY_HANDOFF_SESSION_ID)
        writeInstallState(
            editor = editor,
            state = handoff.state,
            kindKey = KEY_HANDOFF_STATE_KIND,
            codeKey = KEY_HANDOFF_STATE_CODE,
            messageKey = KEY_HANDOFF_STATE_MESSAGE,
        )
        return editor.commit()
    }

    private fun loadInstallState(
        kindKey: String,
        codeKey: String,
        messageKey: String,
        packageName: String,
    ): InstallState? = when (recoveryPreferences.getString(kindKey, null)) {
        STATE_INSTALLED -> InstallState.Installed(packageName)
        STATE_CANCELLED -> InstallState.Cancelled
        STATE_FAILED -> InstallState.Failed(
            code = recoveryPreferences.getString(codeKey, null)
                ?.takeIf(String::isNotBlank)
                ?: return null,
            message = recoveryPreferences.getString(messageKey, null)
                ?.takeIf(String::isNotBlank)
                ?: return null,
        )
        else -> null
    }

    private fun writeInstallState(
        editor: SharedPreferences.Editor,
        state: InstallState,
        kindKey: String,
        codeKey: String,
        messageKey: String,
    ) {
        when (state) {
            is InstallState.Installed -> editor
                .putString(kindKey, STATE_INSTALLED)
                .remove(codeKey)
                .remove(messageKey)
            is InstallState.Cancelled -> editor
                .putString(kindKey, STATE_CANCELLED)
                .remove(codeKey)
                .remove(messageKey)
            is InstallState.Failed -> editor
                .putString(kindKey, STATE_FAILED)
                .putString(codeKey, state.code)
                .putString(messageKey, state.message)
            else -> error("terminal install state만 저장할 수 있습니다.")
        }
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
        value?.let { candidate ->
            enumValues<T>().firstOrNull { it.name == candidate }
        }

    private fun clearRawTerminal(editor: SharedPreferences.Editor): SharedPreferences.Editor =
        editor
            .remove(KEY_RESULT_SESSION_ID)
            .remove(KEY_RESULT_KIND)
            .remove(KEY_RESULT_CODE)
            .remove(KEY_RESULT_MESSAGE)
            .remove(KEY_RESULT_PACKAGE_NAME)
            .remove(KEY_RESULT_IMPORT_TOKEN)
            .remove(KEY_RESULT_PROCESSED)
            .remove(KEY_TERMINAL_AT)

    private fun clearFinalReceipt(editor: SharedPreferences.Editor): SharedPreferences.Editor =
        editor
            .remove(KEY_FINAL_VERSION)
            .remove(KEY_FINAL_IMPORT_TOKEN)
            .remove(KEY_FINAL_HAS_SESSION)
            .remove(KEY_FINAL_SESSION_ID)
            .remove(KEY_FINAL_PACKAGE_NAME)
            .remove(KEY_FINAL_STATE_KIND)
            .remove(KEY_FINAL_STATE_CODE)
            .remove(KEY_FINAL_STATE_MESSAGE)
            .remove(KEY_FINAL_STAGE)
            .remove(KEY_FINAL_ORIGIN)
            .remove(KEY_FINAL_RAW_TERMINAL_AT)
            .remove(KEY_FINAL_TERMINAL_AT)

    private fun clearNormalHandoff(editor: SharedPreferences.Editor): SharedPreferences.Editor =
        editor
            .remove(KEY_HANDOFF_VERSION)
            .remove(KEY_HANDOFF_IMPORT_TOKEN)
            .remove(KEY_HANDOFF_HAS_SESSION)
            .remove(KEY_HANDOFF_SESSION_ID)
            .remove(KEY_HANDOFF_PACKAGE_NAME)
            .remove(KEY_HANDOFF_STATE_KIND)
            .remove(KEY_HANDOFF_STATE_CODE)
            .remove(KEY_HANDOFF_STATE_MESSAGE)
            .remove(KEY_HANDOFF_STAGE)

    private fun FinalInstallReceipt.matchesRaw(raw: PendingInstallResult?): Boolean =
        when (origin) {
            FinalReceiptOrigin.PROGRAM_CANCEL -> raw == null
            FinalReceiptOrigin.RAW_TERMINAL ->
                raw != null &&
                    raw.result.sessionId == sessionId &&
                    raw.packageName == packageName &&
                    raw.importToken == importToken &&
                    raw.terminalAtEpochMillis == rawTerminalAtEpochMillis
            FinalReceiptOrigin.SYSTEM_INSTALLER,
            FinalReceiptOrigin.MY_FILES,
            -> raw == null
        }

    private fun PendingInstallResult.matches(receipt: FinalInstallReceipt): Boolean =
        receipt.origin == FinalReceiptOrigin.RAW_TERMINAL &&
            receipt.matchesRaw(this)

    private fun <T> RecoveryRecordSnapshot<T>.matchesExpected(expected: T?): Boolean =
        when (this) {
            is RecoveryRecordSnapshot.Present -> value == expected
            RecoveryRecordSnapshot.Absent -> expected == null
            RecoveryRecordSnapshot.Invalid -> false
        }

    private fun sanitize(result: PackageInstallResult): PackageInstallResult = when (result) {
        is PackageInstallResult.Success -> result
        is PackageInstallResult.Cancelled -> result
        is PackageInstallResult.PendingUserAction -> result
        is PackageInstallResult.Failure -> result.copy(
            code = result.code
                .take(MAX_CODE_LENGTH)
                .filter { it.isUpperCase() || it.isDigit() || it == '_' }
                .ifEmpty { DEFAULT_FAILURE_CODE },
            message = result.message
                .filter { it >= ' ' && it != '\u007f' }
                .take(MAX_MESSAGE_LENGTH)
                .ifBlank { DEFAULT_FAILURE_MESSAGE },
        )
    }

    private fun PackageInstallResult.kind(): String = when (this) {
        is PackageInstallResult.Success -> RESULT_SUCCESS
        is PackageInstallResult.Cancelled -> RESULT_CANCELLED
        is PackageInstallResult.Failure -> RESULT_FAILURE
        is PackageInstallResult.PendingUserAction -> error("pending user action은 terminal result가 아닙니다.")
    }

    private fun isValid(pending: PendingInstall): Boolean =
        pending.sessionId >= UNASSIGNED_SESSION_ID &&
            PACKAGE_NAME.matches(pending.packageName) &&
            isCanonicalToken(pending.importToken) &&
            pending.startedAtEpochMillis > 0

    private fun isValid(receipt: FinalInstallReceipt): Boolean =
        PACKAGE_NAME.matches(receipt.packageName) &&
            isCanonicalToken(receipt.importToken) &&
            (receipt.state !is InstallState.Installed ||
                receipt.state.packageName == receipt.packageName)

    private fun isValidInitialReceipt(receipt: FinalInstallReceipt): Boolean =
        isValid(receipt) &&
            (receipt.origin !in setOf(
                FinalReceiptOrigin.SYSTEM_INSTALLER,
                FinalReceiptOrigin.MY_FILES,
            ) ||
                receipt.stage == FinalReceiptStage.READY)

    private fun isValid(handoff: NormalInstallHandoff): Boolean =
        PACKAGE_NAME.matches(handoff.packageName) &&
            isCanonicalToken(handoff.importToken) &&
            (handoff.state !is InstallState.Installed ||
                handoff.state.packageName == handoff.packageName)

    private fun isCanonicalToken(token: String): Boolean =
        runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)

    private fun isValidFailureCode(code: String): Boolean =
        code.isNotBlank() &&
            code.length <= MAX_CODE_LENGTH &&
            code.all { it.isUpperCase() || it.isDigit() || it == '_' }

    private fun isValidFailureMessage(message: String): Boolean =
        message.isNotBlank() &&
            message.length <= MAX_MESSAGE_LENGTH &&
            message.all { it >= ' ' && it != '\u007f' }

    private fun canonicalImportRoot(): File? = runCatching {
        if (!importDirectory.exists() || !importDirectory.isDirectory) return null
        if (Files.isSymbolicLink(importDirectory.toPath())) return null
        importDirectory.canonicalFile
    }.getOrNull()

    private fun deleteTree(directory: File): Boolean {
        val path = directory.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return true
        return runCatching {
            Files.walkFileTree(
                path,
                object : SimpleFileVisitor<java.nio.file.Path>() {
                    override fun visitFile(
                        file: java.nio.file.Path,
                        attrs: BasicFileAttributes,
                    ): FileVisitResult {
                        Files.deleteIfExists(file)
                        return FileVisitResult.CONTINUE
                    }

                    override fun postVisitDirectory(
                        dir: java.nio.file.Path,
                        error: IOException?,
                    ): FileVisitResult {
                        if (error != null) throw error
                        Files.deleteIfExists(dir)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            !Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        }.getOrDefault(false)
    }

    private companion object {
        val PROCESS_LOCK = Any()
        const val UNASSIGNED_SESSION_ID = -1
        const val PENDING_PREFERENCES_NAME = "pending_install"
        const val RECOVERY_PREFERENCES_NAME = "pending_install_recovery"
        const val IMPORT_DIRECTORY = "imports"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_PACKAGE_NAME = "package_name"
        const val KEY_IMPORT_TOKEN = "import_token"
        const val KEY_STARTED_AT = "started_at"
        const val KEY_RECOVERY_SESSION_ID = "recovery_session_id"
        const val KEY_RECOVERY_IMPORT_TOKEN = "recovery_import_token"
        const val KEY_RESULT_SESSION_ID = "result_session_id"
        const val KEY_RESULT_KIND = "result_kind"
        const val KEY_RESULT_CODE = "result_code"
        const val KEY_RESULT_MESSAGE = "result_message"
        const val KEY_RESULT_PACKAGE_NAME = "result_package_name"
        const val KEY_RESULT_IMPORT_TOKEN = "result_import_token"
        const val KEY_RESULT_PROCESSED = "result_processed"
        const val KEY_CONFIRMATION_SESSION_ID = "confirmation_session_id"
        const val KEY_TERMINAL_AT = "terminal_at"
        const val KEY_MISSING_SESSION_ID = "missing_session_id"
        const val KEY_MISSING_IMPORT_TOKEN = "missing_import_token"
        const val KEY_MISSING_OBSERVED_AT = "missing_observed_at"
        const val KEY_FINAL_VERSION = "final_version"
        const val KEY_FINAL_IMPORT_TOKEN = "final_import_token"
        const val KEY_FINAL_HAS_SESSION = "final_has_session"
        const val KEY_FINAL_SESSION_ID = "final_session_id"
        const val KEY_FINAL_PACKAGE_NAME = "final_package_name"
        const val KEY_FINAL_STATE_KIND = "final_state_kind"
        const val KEY_FINAL_STATE_CODE = "final_state_code"
        const val KEY_FINAL_STATE_MESSAGE = "final_state_message"
        const val KEY_FINAL_STAGE = "final_stage"
        const val KEY_FINAL_ORIGIN = "final_origin"
        const val KEY_FINAL_RAW_TERMINAL_AT = "final_raw_terminal_at"
        const val KEY_FINAL_TERMINAL_AT = "final_terminal_at"
        const val KEY_HANDOFF_VERSION = "handoff_version"
        const val KEY_HANDOFF_IMPORT_TOKEN = "handoff_import_token"
        const val KEY_HANDOFF_HAS_SESSION = "handoff_has_session"
        const val KEY_HANDOFF_SESSION_ID = "handoff_session_id"
        const val KEY_HANDOFF_PACKAGE_NAME = "handoff_package_name"
        const val KEY_HANDOFF_STATE_KIND = "handoff_state_kind"
        const val KEY_HANDOFF_STATE_CODE = "handoff_state_code"
        const val KEY_HANDOFF_STATE_MESSAGE = "handoff_state_message"
        const val KEY_HANDOFF_STAGE = "handoff_stage"
        const val RESULT_SUCCESS = "success"
        const val RESULT_CANCELLED = "cancelled"
        const val RESULT_FAILURE = "failure"
        const val STATE_INSTALLED = "installed"
        const val STATE_CANCELLED = "cancelled"
        const val STATE_FAILED = "failed"
        const val DEFAULT_FAILURE_CODE = "PACKAGE_INSTALLER_FAILURE"
        const val DEFAULT_FAILURE_MESSAGE = "PackageInstaller가 설치를 완료하지 못했습니다."
        const val MAX_CODE_LENGTH = 64
        const val MAX_MESSAGE_LENGTH = 256
        val PACKAGE_NAME = Regex("dev\\.sixbridge\\.ext\\.[a-p]{32}")
    }
}
