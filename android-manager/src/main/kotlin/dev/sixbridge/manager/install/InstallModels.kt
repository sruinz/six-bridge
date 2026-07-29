package dev.sixbridge.manager.install

sealed interface InstallState {
    data object Idle : InstallState
    data object PreparingSystemInstaller : InstallState
    data class PreparingApkExport(val importToken: String) : InstallState
    data class AwaitingSystemInstaller(val importToken: String) : InstallState
    data class AwaitingManualInstall(val importToken: String) : InstallState
    data object UnknownSourcesPermissionRequired : InstallState
    data class WritingSession(val sessionId: Int) : InstallState
    data class AwaitingUserApproval(val sessionId: Int) : InstallState
    data class Installed(val packageName: String) : InstallState
    data object Cancelled : InstallState
    data class Failed(val code: String, val message: String) : InstallState
}

sealed interface PackageInstallResult {
    val sessionId: Int

    data class PendingUserAction(override val sessionId: Int) : PackageInstallResult
    data class Success(override val sessionId: Int) : PackageInstallResult
    data class Cancelled(override val sessionId: Int) : PackageInstallResult
    data class Failure(
        override val sessionId: Int,
        val code: String,
        val message: String,
    ) : PackageInstallResult
}

data class PendingInstall(
    val sessionId: Int,
    val packageName: String,
    val importToken: String,
    val startedAtEpochMillis: Long,
)

enum class SystemInstallStage {
    PREPARED,
    LAUNCHED,
    EXPORTED,
}

data class ExpectedInstalledWrapper(
    val packageName: String,
    val extensionId: String,
    val versionCode: Long,
    val signerSha256: String,
)

data class PendingSystemInstall(
    val importToken: String,
    val expected: ExpectedInstalledWrapper,
    val wrapperSha256: String,
    val previous: ExpectedInstalledWrapper?,
    val startedAtEpochMillis: Long,
    val stage: SystemInstallStage,
    val version: Int = 1,
)

data class PendingInstallResult(
    val result: PackageInstallResult,
    val terminalAtEpochMillis: Long,
    val packageName: String = "",
    val importToken: String? = null,
    val processed: Boolean = false,
)

enum class FinalReceiptStage {
    CANCELLING,
    READY,
    ACKNOWLEDGED,
}

enum class FinalReceiptOrigin {
    PROGRAM_CANCEL,
    RAW_TERMINAL,
    SYSTEM_INSTALLER,
    MY_FILES,
}

data class FinalInstallReceipt(
    val importToken: String,
    val sessionId: Int?,
    val packageName: String,
    val state: InstallState,
    val stage: FinalReceiptStage,
    val origin: FinalReceiptOrigin,
    val rawTerminalAtEpochMillis: Long?,
    val terminalAtEpochMillis: Long,
    val version: Int = CURRENT_VERSION,
) {
    init {
        require(sessionId == null || sessionId >= 0)
        require(
            state is InstallState.Installed ||
                state is InstallState.Cancelled ||
                state is InstallState.Failed,
        )
        require(
            when (origin) {
                FinalReceiptOrigin.PROGRAM_CANCEL ->
                    state is InstallState.Cancelled &&
                        rawTerminalAtEpochMillis == null
                FinalReceiptOrigin.RAW_TERMINAL ->
                    sessionId != null &&
                        rawTerminalAtEpochMillis != null &&
                        rawTerminalAtEpochMillis > 0 &&
                        stage != FinalReceiptStage.CANCELLING
                FinalReceiptOrigin.SYSTEM_INSTALLER,
                FinalReceiptOrigin.MY_FILES,
                ->
                    sessionId == null &&
                        rawTerminalAtEpochMillis == null &&
                        stage != FinalReceiptStage.CANCELLING
            },
        )
        require(
            stage != FinalReceiptStage.CANCELLING ||
                origin == FinalReceiptOrigin.PROGRAM_CANCEL,
        )
        require(terminalAtEpochMillis > 0)
        require(version == CURRENT_VERSION)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

enum class NormalHandoffStage {
    PREPARED,
    OUTCOME_READY,
}

data class NormalInstallHandoff(
    val importToken: String,
    val sessionId: Int?,
    val packageName: String,
    val state: InstallState,
    val stage: NormalHandoffStage,
    val version: Int = CURRENT_VERSION,
) {
    init {
        require(sessionId == null || sessionId >= 0)
        require(
            state is InstallState.Installed ||
                state is InstallState.Cancelled ||
                state is InstallState.Failed,
        )
        require(version == CURRENT_VERSION)
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

sealed interface PendingCleanupResult {
    data object Cleared : PendingCleanupResult
    data object TerminalAvailable : PendingCleanupResult
    data object Failed : PendingCleanupResult
}

sealed interface InstallCleanupResult {
    data object Cleaned : InstallCleanupResult
    data class OwnerSessionAssigned(val sessionId: Int) : InstallCleanupResult
    data object OwnerMismatch : InstallCleanupResult
    data object CleanupFailed : InstallCleanupResult
}

sealed interface InstallTerminalOwnerResult {
    data class Owned(val state: InstallState) : InstallTerminalOwnerResult
    data class OwnerSessionAssigned(
        val sessionId: Int,
        val state: InstallState,
    ) : InstallTerminalOwnerResult
    data object OwnerMismatch : InstallTerminalOwnerResult
}

sealed interface FinalReceiptOwnerResult {
    data class Owned(val receipt: FinalInstallReceipt) : FinalReceiptOwnerResult
    data class OwnerSessionAssigned(val sessionId: Int) : FinalReceiptOwnerResult
    data object OwnerMismatch : FinalReceiptOwnerResult
    data object Absent : FinalReceiptOwnerResult
}

sealed interface RecoveryRecordSnapshot<out T> {
    data class Present<T>(val value: T) : RecoveryRecordSnapshot<T>
    data object Absent : RecoveryRecordSnapshot<Nothing>
    data object Invalid : RecoveryRecordSnapshot<Nothing>
}

sealed interface RecoveryStoreSnapshot {
    data class Valid(
        val pending: PendingInstall?,
        val terminal: PendingInstallResult?,
        val finalReceipt: FinalInstallReceipt?,
        val normalHandoff: NormalInstallHandoff?,
    ) : RecoveryStoreSnapshot

    data object Invalid : RecoveryStoreSnapshot
}
