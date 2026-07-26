package dev.sixbridge.manager.ui

import android.content.Intent
import android.net.Uri
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.manager.app.RepositoryLaunch
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.install.InstallState

sealed interface ManagerRoute {
    data object Home : ManagerRoute
    data class Review(val token: String) : ManagerRoute
    data class Report(val packageName: String) : ManagerRoute
}

data class ManagedExtensionItem(
    val packageName: String,
    val extensionId: String,
    val name: String,
    val installedVersionCode: Long,
    val compatibilityStatus: String,
)

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Importing : ImportUiState
    data class ReviewReady(
        val review: ReviewBundle,
        val partialConfirmed: Boolean,
    ) : ImportUiState {
        val installEnabled: Boolean
            get() = when (review.status) {
                ContractCompatibilityStatus.COMPATIBLE -> true
                ContractCompatibilityStatus.PARTIAL -> partialConfirmed
                ContractCompatibilityStatus.BLOCKED,
                ContractCompatibilityStatus.UNKNOWN,
                -> false
            }
    }
}

enum class RecoveryAction {
    NONE,
    SELECT_ANOTHER_FILE,
    CONFIRM_PARTIAL,
    OPEN_SETTINGS,
    RETRY,
}

enum class UserMessageKind {
    FILE_READ_OR_SIZE,
    ZIP_OR_SCHEMA,
    IDENTITY_VERIFICATION,
    BLOCKED_COMPATIBILITY,
    UNKNOWN_COMPATIBILITY,
    UPDATE_IDENTITY,
    UNKNOWN_SOURCES_PERMISSION,
    INSTALL_CANCELLED,
    INSTALL_FAILED,
    SAMSUNG_BROWSER_MISSING,
    SAMSUNG_ADD_ONS_UNAVAILABLE,
    UNINSTALL_CANCELLED,
    UNINSTALLED,
}

data class UserMessage(
    val kind: UserMessageKind,
    val text: String,
    val recoveryAction: RecoveryAction,
)

data class ManagerUiState(
    val route: ManagerRoute = ManagerRoute.Home,
    val installed: List<ManagedExtensionItem> = emptyList(),
    val importState: ImportUiState = ImportUiState.Idle,
    val installState: InstallState = InstallState.Idle,
    val message: UserMessage? = null,
)

sealed interface ManagerAction {
    data class ImportSelected(val uri: Uri) : ManagerAction
    data object ConfirmPartial : ManagerAction
    data object InstallReviewedBundle : ManagerAction
    data object CancelReview : ManagerAction
    data class Remove(val packageName: String) : ManagerAction
    data object OpenSamsungAddOns : ManagerAction
    data object OpenUnknownSourcesSettings : ManagerAction
    data object ReturnedToForeground : ManagerAction
    data object DismissMessage : ManagerAction
}

enum class LaunchDestination {
    UNKNOWN_SOURCES_SETTINGS,
    UNINSTALL,
    SAMSUNG_ADD_ONS,
    SYSTEM_INSTALL_CONFIRMATION,
}

sealed interface ManagerEffect {
    class LaunchIntent(
        val destination: LaunchDestination,
        val intentFactory: () -> Intent,
        val installSessionId: Int? = null,
    ) : ManagerEffect {
        constructor(launch: RepositoryLaunch) : this(
            launch.destination,
            launch.intentFactory,
            launch.installSessionId,
        )
    }
}
