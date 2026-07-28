package dev.sixbridge.manager.install

sealed interface InstallState {
    data object Idle : InstallState
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

data class PendingInstallResult(
    val result: PackageInstallResult,
    val terminalAtEpochMillis: Long,
    val packageName: String = "",
    val importToken: String? = null,
    val processed: Boolean = false,
)

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
