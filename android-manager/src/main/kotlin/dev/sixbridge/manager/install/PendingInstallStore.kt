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
    fun tryClaim(packageName: String, importToken: String, startedAtEpochMillis: Long): PendingInstall?
    fun assignSession(claim: PendingInstall, sessionId: Int): PendingInstall?
    fun recoverSession(claim: PendingInstall, sessionId: Int): PendingInstall?
    fun clear(expected: PendingInstall): Boolean
    fun clearWithoutTerminal(expected: PendingInstall): PendingCleanupResult
    fun discardImport(importToken: String)
    fun loadTerminalResult(): PendingInstallResult?
    fun isConfirmationDelivered(sessionId: Int): Boolean = false
    fun markConfirmationDelivered(sessionId: Int): Boolean = true
    fun recordTerminalResult(result: PackageInstallResult, terminalAtEpochMillis: Long): Boolean
    fun markTerminalProcessed(expected: PendingInstallResult): PendingInstallResult?
    fun clearTerminalResult(expected: PendingInstallResult): Boolean
    fun firstSessionMissingAt(pending: PendingInstall, observedAtEpochMillis: Long): Long
    fun clearSessionMissingObservation(pending: PendingInstall): Boolean
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

    override fun tryClaim(
        packageName: String,
        importToken: String,
        startedAtEpochMillis: Long,
    ): PendingInstall? = synchronized(PROCESS_LOCK) {
        if (loadPendingLocked() != null || loadTerminalLocked() != null) return@synchronized null
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
        if (loadTerminalLocked() != null) {
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
        val pending = loadPendingLocked()
        if (pending == null || pending.sessionId != result.sessionId) return@synchronized false
        val sanitized = PendingInstallResult(
            result = sanitize(result),
            terminalAtEpochMillis = terminalAtEpochMillis,
            packageName = pending.packageName,
            importToken = pending.importToken,
        )
        val existing = loadTerminalLocked()
        if (existing != null) {
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
            if (loadTerminalLocked() != expected) return@synchronized false
            recoveryPreferences.edit()
                .remove(KEY_RESULT_SESSION_ID)
                .remove(KEY_RESULT_KIND)
                .remove(KEY_RESULT_CODE)
                .remove(KEY_RESULT_MESSAGE)
                .remove(KEY_RESULT_PACKAGE_NAME)
                .remove(KEY_RESULT_IMPORT_TOKEN)
                .remove(KEY_RESULT_PROCESSED)
                .remove(KEY_CONFIRMATION_SESSION_ID)
                .remove(KEY_TERMINAL_AT)
                .commit()
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
        val importToken = recoveryPreferences.getString(KEY_RESULT_IMPORT_TOKEN, null)
            ?.takeIf(::isCanonicalToken)
        val result = when (recoveryPreferences.getString(KEY_RESULT_KIND, null)) {
            RESULT_SUCCESS -> PackageInstallResult.Success(sessionId)
            RESULT_CANCELLED -> PackageInstallResult.Cancelled(sessionId)
            RESULT_FAILURE -> PackageInstallResult.Failure(
                sessionId = sessionId,
                code = recoveryPreferences.getString(KEY_RESULT_CODE, null)
                    ?.takeIf(String::isNotBlank)
                    ?: DEFAULT_FAILURE_CODE,
                message = recoveryPreferences.getString(KEY_RESULT_MESSAGE, null)
                    ?.takeIf(String::isNotBlank)
                    ?: DEFAULT_FAILURE_MESSAGE,
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

    private fun isCanonicalToken(token: String): Boolean =
        runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)

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
        const val RESULT_SUCCESS = "success"
        const val RESULT_CANCELLED = "cancelled"
        const val RESULT_FAILURE = "failure"
        const val DEFAULT_FAILURE_CODE = "PACKAGE_INSTALLER_FAILURE"
        const val DEFAULT_FAILURE_MESSAGE = "PackageInstaller가 설치를 완료하지 못했습니다."
        const val MAX_CODE_LENGTH = 64
        const val MAX_MESSAGE_LENGTH = 256
        val PACKAGE_NAME = Regex("dev\\.sixbridge\\.ext\\.[a-p]{32}")
    }
}
