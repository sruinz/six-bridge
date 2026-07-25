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
