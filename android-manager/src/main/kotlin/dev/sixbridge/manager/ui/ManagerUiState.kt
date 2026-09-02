package dev.sixbridge.manager.ui

import android.content.Intent
import android.net.Uri
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.manager.app.RepositoryLaunch
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.conversion.GeneratedBundle
import dev.sixbridge.manager.conversion.IdentityRequirement
import dev.sixbridge.manager.conversion.InstallationIdentitySelection
import dev.sixbridge.manager.conversion.ConversionFailureCode
import dev.sixbridge.manager.conversion.ConversionForceChallenge
import dev.sixbridge.manager.conversion.LegacyConversionIdentity
import dev.sixbridge.manager.conversion.OnDeviceConversionApproval
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.ForceCompatibilityPolicy
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.install.SamsungInternetSupportStatus
import java.io.File

internal data class DocumentLaunchIdentity(
    val ownerGeneration: Long,
    val launchId: Long,
)

sealed interface ManagerRoute {
    data object Home : ManagerRoute
    data object ConversionInput : ManagerRoute
    data object ConversionIdentityManagement : ManagerRoute
    data class ConversionAnalysis(val token: String) : ManagerRoute
    data class ConversionResult(val token: String) : ManagerRoute
    data class Review(val token: String) : ManagerRoute
    data class Report(val packageName: String) : ManagerRoute
}

data class ConversionIdentityManagementUiState(
    val identities: List<LegacyConversionIdentity> = emptyList(),
    val loading: Boolean = false,
    val pendingCleanupIdentitySha256: String? = null,
    val cleanupInProgressIdentitySha256: String? = null,
    val outcome: ConversionIdentityCleanupUiOutcome? = null,
    val loadFailed: Boolean = false,
) {
    val pendingCleanupIdentity: LegacyConversionIdentity?
        get() = identities.singleOrNull {
            it.identitySha256 == pendingCleanupIdentitySha256
        }

    fun requestCleanup(identitySha256: String): ConversionIdentityManagementUiState {
        if (loading || cleanupInProgressIdentitySha256 != null) return this
        if (identities.none { it.identitySha256 == identitySha256 }) return this
        return copy(pendingCleanupIdentitySha256 = identitySha256, outcome = null)
    }
}

sealed interface ConversionIdentityCleanupUiOutcome {
    val displayName: String

    data class Removed(override val displayName: String) : ConversionIdentityCleanupUiOutcome
    data class AlreadyAbsent(override val displayName: String) :
        ConversionIdentityCleanupUiOutcome
    data class Preserved(
        override val displayName: String,
        val reason: String,
    ) : ConversionIdentityCleanupUiOutcome
}

data class ManagedExtensionItem(
    val packageName: String,
    val extensionId: String,
    val name: String,
    val iconPng: ByteArray? = null,
    val installedVersionCode: Long,
    val compatibilityStatus: ContractCompatibilityStatus,
    val signerSha256: String = "",
    val report: ContractCompatibilityReport? = null,
    val reportAvailability: ReportAvailability = ReportAvailability.MISSING,
)

enum class ReportAvailability {
    AVAILABLE,
    MISSING,
    UNREADABLE,
}

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Importing : ImportUiState
    data class ReviewReady(
        val review: ReviewBundle,
        val partialConfirmed: Boolean = false,
        val forceCompatibilityConfirmed: Boolean = false,
    ) : ImportUiState {
        val installEnabled: Boolean
            get() = when (review.status) {
                ContractCompatibilityStatus.COMPATIBLE -> true
                ContractCompatibilityStatus.PARTIAL -> partialConfirmed
                ContractCompatibilityStatus.UNKNOWN ->
                    review.forcedCompatibility != null && forceCompatibilityConfirmed
                ContractCompatibilityStatus.BLOCKED -> false
            }
    }
}

sealed interface ConversionUiState {
    data object Idle : ConversionUiState
    data object Acquiring : ConversionUiState
    data class Cancelling(val token: String?) : ConversionUiState

    data class AnalysisReady(
        val token: String,
        val report: CompatibilityReport,
        val selectedAutomaticAdapters: Set<String>,
        val requiredApprovals: Set<String>,
        val optionalApprovals: Set<String> = emptySet(),
        val identityRequirement: IdentityRequirement,
        val forceChallenge: ConversionForceChallenge? = null,
        val selectedAdapters: Set<String> = emptySet(),
        val resolvedOptionalAdapters: Set<String> = emptySet(),
        val partialConfirmed: Boolean = false,
        val forceApproval: OnDeviceConversionApproval? = null,
        val identitySelection: InstallationIdentitySelection? = null,
        val existingIdentityConfirmed: Boolean = false,
        val profileChanged: Boolean = false,
        val draftSignerFingerprint: String? = null,
    ) : ConversionUiState {
        val forceCompatibilityConfirmed: Boolean
            get() = forceApproval != null

        val canBuild: Boolean
            get() = (
                report.status in setOf(
                    CompatibilityStatus.COMPATIBLE,
                    CompatibilityStatus.PARTIAL,
                ) ||
                    forceCompatibilityConfirmed && ForceCompatibilityPolicy.isEligible(report)
                ) &&
                selectedAdapters.containsAll(requiredApprovals) &&
                (requiredApprovals + optionalApprovals).containsAll(selectedAdapters) &&
                resolvedOptionalAdapters.containsAll(optionalApprovals) &&
                (report.status != CompatibilityStatus.PARTIAL || partialConfirmed) &&
                identitySelectionSatisfied()

        fun toggleAdapter(id: String): AnalysisReady {
            if (id !in requiredApprovals) return this
            return copy(
                selectedAdapters = if (id in selectedAdapters) {
                    selectedAdapters - id
                } else {
                    selectedAdapters + id
                },
                forceApproval = null,
            )
        }

        fun chooseOptionalAdapter(id: String, apply: Boolean): AnalysisReady {
            if (id !in optionalApprovals) return this
            return copy(
                selectedAdapters = if (apply) {
                    selectedAdapters + id
                } else {
                    selectedAdapters - id
                },
                resolvedOptionalAdapters = resolvedOptionalAdapters + id,
                forceApproval = null,
            )
        }

        fun selectIdentity(selection: InstallationIdentitySelection): AnalysisReady {
            val requirement = identityRequirement as? IdentityRequirement.UserSelectionRequired
                ?: return this
            val accepted = when (selection) {
                InstallationIdentitySelection.CreateNew -> true
                is InstallationIdentitySelection.UseExisting ->
                    requirement.existingTargets.any {
                        it.identitySha256 == selection.identitySha256
                    }
            }
            if (!accepted) return this
            return copy(
                identitySelection = selection,
                existingIdentityConfirmed = false,
                forceApproval = null,
            )
        }

        private fun identitySelectionSatisfied(): Boolean = when (identityRequirement) {
            is IdentityRequirement.Stable -> identitySelection == null
            is IdentityRequirement.UserSelectionRequired -> when (identitySelection) {
                InstallationIdentitySelection.CreateNew -> true
                is InstallationIdentitySelection.UseExisting -> existingIdentityConfirmed
                null -> false
            }
        }
    }

    data class Building(
        val token: String,
        val previous: AnalysisReady,
        val forceApproval: OnDeviceConversionApproval = OnDeviceConversionApproval.NONE,
    ) : ConversionUiState

    data class ResultReady(
        val token: String,
        val review: ReviewBundle?,
        val generated: GeneratedBundle,
        val failureCode: ConversionFailureCode? = null,
        val installOwnerActive: Boolean = false,
        val cleanupPending: Boolean = false,
        val cleanupInProgress: Boolean = false,
    ) : ConversionUiState

    data class Failed(
        val token: String?,
        val code: ConversionFailureCode,
    ) : ConversionUiState
}

enum class RecoveryAction {
    NONE,
    SELECT_ANOTHER_FILE,
    CONFIRM_PARTIAL,
    OPEN_SETTINGS,
    RETRY,
    CHECK_INSTALL_STATUS,
}

enum class UserMessageKind {
    RECOVERY_BLOCKED,
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
    FILE_MANAGER_MISSING,
    UNINSTALL_CANCELLED,
    UNINSTALLED,
    LAUNCH_FAILED,
    CONVERSION_FAILURE,
    CONVERSION_SAVED,
    CONVERSION_SAVE_FAILED,
    CONVERSION_SAVE_CLEANUP_FAILED,
    CONVERSION_SHARE_FAILED,
    CONVERSION_CLEANUP_FAILED,
}

enum class StartupRecoveryUiState {
    INITIALIZING,
    READY,
    RECOVERY_BLOCKED,
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
    val conversionState: ConversionUiState = ConversionUiState.Idle,
    val conversionIdentityManagementState: ConversionIdentityManagementUiState =
        ConversionIdentityManagementUiState(),
    val conversionCleanupPending: Boolean = false,
    val conversionUrl: String = "",
    val installState: InstallState = InstallState.Idle,
    val reportInstallSupportStatus: SamsungInternetSupportStatus? = null,
    val message: UserMessage? = null,
    val destinationCommitToken: String? = null,
    val startupRecoveryState: StartupRecoveryUiState =
        StartupRecoveryUiState.INITIALIZING,
)

sealed interface ManagerAction {
    data object OpenSixFilePicker : ManagerAction
    data class ImportSelected(
        val uri: Uri?,
        val ownerGeneration: Long,
        val launchId: Long,
    ) : ManagerAction
    data object OpenDraftFilePicker : ManagerAction
    data class DraftFileSelected(
        val uri: Uri?,
        val ownerGeneration: Long,
        val launchId: Long,
    ) : ManagerAction
    data object OpenConversionInput : ManagerAction
    data object OpenConversionIdentityManagement : ManagerAction
    data class RequestLegacyIdentityCleanup(val identitySha256: String) : ManagerAction
    data object DismissLegacyIdentityCleanupConfirmation : ManagerAction
    data class ConfirmLegacyIdentityCleanup(val identitySha256: String) : ManagerAction
    data class ConversionUrlChanged(val value: String) : ManagerAction
    data object OpenConversionFilePicker : ManagerAction
    data class ConversionFileSelected(
        val uri: Uri?,
        val ownerGeneration: Long,
        val launchId: Long,
    ) : ManagerAction
    data object AnalyzeConversionUrl : ManagerAction
    data class ToggleConversionAdapter(val id: String) : ManagerAction
    data class ChooseOptionalConversionAdapter(
        val id: String,
        val apply: Boolean,
    ) : ManagerAction
    data object ConfirmConversionPartial : ManagerAction
    data object ConfirmConversionForce : ManagerAction
    data object SelectNewConversionIdentity : ManagerAction
    data class SelectExistingConversionIdentity(val identitySha256: String) : ManagerAction
    data object ConfirmExistingConversionIdentity : ManagerAction
    data object BuildConversion : ManagerAction
    data object PrepareGeneratedInstall : ManagerAction
    data object SaveGeneratedConversion : ManagerAction
    data class SaveGeneratedConversionTo(
        val token: String,
        val destination: String?,
        val ownerGeneration: Long,
        val launchId: Long,
    ) : ManagerAction
    data object ShareGeneratedConversion : ManagerAction
    data class GeneratedShareLaunchSucceeded(
        val token: String,
        val handoffId: Long,
    ) : ManagerAction
    data class GeneratedShareLaunchFailed(
        val token: String,
        val handoffId: Long,
    ) : ManagerAction
    data object FinishConversion : ManagerAction
    data object CancelConversion : ManagerAction
    data object RetryGeneratedCleanup : ManagerAction
    data class GeneratedCleanupDestinationPresented(val token: String) : ManagerAction
    data object ConfirmPartial : ManagerAction
    data object ConfirmForcedInstall : ManagerAction
    data object PrepareReviewedInstall : ManagerAction
    data object InstallReviewedBundle : ManagerAction
    @Deprecated("InstallApkCreated를 사용합니다.")
    open class SystemInstallApkCreated(
        open val importToken: String,
        open val uri: Uri?,
        open val ownerGeneration: Long,
        open val launchId: Long,
    ) : ManagerAction
    data class InstallApkCreated(
        override val importToken: String,
        override val uri: Uri?,
        override val ownerGeneration: Long,
        override val launchId: Long,
    ) : SystemInstallApkCreated(importToken, uri, ownerGeneration, launchId)
    data object ConfirmManualInstallStatus : ManagerAction
    @Deprecated("ConfirmManualInstallStatus를 사용합니다.")
    data object ConfirmSystemInstallStatus : ManagerAction
    data class ContinueInstallAfterNotificationPermission(
        val reviewToken: String,
        val generatedToken: String? = null,
    ) : ManagerAction

    data class InstallApkCleanupFailed(
        val ownerGeneration: Long,
        val launchId: Long,
    ) : ManagerAction
    data object CancelReview : ManagerAction
    data class Remove(val packageName: String) : ManagerAction
    data class OpenReport(val packageName: String) : ManagerAction
    data object ReturnHome : ManagerAction
    data object OpenSamsungAddOns : ManagerAction
    data object OpenFileManager : ManagerAction
    data object OpenUnknownSourcesSettings : ManagerAction
    data object ReturnedToForeground : ManagerAction
    data object DismissMessage : ManagerAction
    data object RetryRecovery : ManagerAction
    data class LaunchSucceeded(val effect: ManagerEffect.LaunchIntent) : ManagerAction
    data class LaunchFailed(val effect: ManagerEffect.LaunchIntent) : ManagerAction
}

enum class LaunchDestination {
    UNKNOWN_SOURCES_SETTINGS,
    UNINSTALL,
    SAMSUNG_ADD_ONS,
    FILE_MANAGER,
}

internal data class InstallApkDocumentTransactionKey(
    val importToken: String,
    val ownerGeneration: Long,
    val launchId: Long,
    val destination: String,
)

internal enum class InstallApkDocumentTransactionPhase {
    COPY_IN_FLIGHT,
    CLEANUP_PENDING,
    CLEANED,
    CLEANUP_FAILED,
    COMMITTED,
    COMMITTED_HANDOFF_PENDING,
    CLEANED_HANDOFF_PENDING,
    CLEANUP_RESOLVED_HANDOFF_PENDING,
    RECOVERY_BLOCKED,
}

internal sealed interface InstallApkDocumentTransactionState {
    data object Idle : InstallApkDocumentTransactionState
    data class Running(
        val key: InstallApkDocumentTransactionKey,
        val phase: InstallApkDocumentTransactionPhase,
    ) : InstallApkDocumentTransactionState
    data class Terminal(
        val key: InstallApkDocumentTransactionKey,
        val phase: InstallApkDocumentTransactionPhase,
    ) : InstallApkDocumentTransactionState
}

sealed interface ManagerEffect {
    data class PickSixDocument(
        val ownerGeneration: Long,
        val launchId: Long,
    ) : ManagerEffect
    data class PickDraftDocument(
        val ownerGeneration: Long,
        val launchId: Long,
    ) : ManagerEffect
    data class RequestInstallNotificationPermission(
        val reviewToken: String,
        val generatedToken: String? = null,
    ) : ManagerEffect

    data class PickConversionDocument(
        val ownerGeneration: Long,
        val launchId: Long,
    ) : ManagerEffect

    data class CreateConversionDocument(
        val token: String,
        val suggestedFileName: String,
        val ownerGeneration: Long,
        val launchId: Long,
    ) : ManagerEffect

    @Deprecated("CreateInstallApkDocument를 사용합니다.")
    open class CreateSystemInstallApkDocument(
        open val importToken: String,
        open val source: File,
        open val expectedSha256: String,
        open val suggestedFileName: String,
        open val ownerGeneration: Long,
        open val launchId: Long,
    ) : ManagerEffect {
        open fun copy(
            importToken: String = this.importToken,
            source: File = this.source,
            expectedSha256: String = this.expectedSha256,
            suggestedFileName: String = this.suggestedFileName,
            ownerGeneration: Long = this.ownerGeneration,
            launchId: Long = this.launchId,
        ): CreateSystemInstallApkDocument = CreateSystemInstallApkDocument(
            importToken,
            source,
            expectedSha256,
            suggestedFileName,
            ownerGeneration,
            launchId,
        )

        override fun equals(other: Any?): Boolean =
            other is CreateSystemInstallApkDocument &&
                importToken == other.importToken &&
                source == other.source &&
                expectedSha256 == other.expectedSha256 &&
                suggestedFileName == other.suggestedFileName &&
                ownerGeneration == other.ownerGeneration &&
                launchId == other.launchId

        override fun hashCode(): Int {
            var result = importToken.hashCode()
            result = 31 * result + source.hashCode()
            result = 31 * result + expectedSha256.hashCode()
            result = 31 * result + suggestedFileName.hashCode()
            result = 31 * result + ownerGeneration.hashCode()
            return 31 * result + launchId.hashCode()
        }
    }

    class CreateInstallApkDocument(
        override val importToken: String,
        override val source: File,
        override val expectedSha256: String,
        override val suggestedFileName: String,
        override val ownerGeneration: Long,
        override val launchId: Long,
    ) : CreateSystemInstallApkDocument(
        importToken,
        source,
        expectedSha256,
        suggestedFileName,
        ownerGeneration,
        launchId,
    ) {
        override fun copy(
            importToken: String,
            source: File,
            expectedSha256: String,
            suggestedFileName: String,
            ownerGeneration: Long,
            launchId: Long,
        ): CreateInstallApkDocument = CreateInstallApkDocument(
            importToken,
            source,
            expectedSha256,
            suggestedFileName,
            ownerGeneration,
            launchId,
        )
    }

    data class ShareGeneratedBundle(
        val token: String,
        val file: File,
        val handoffId: Long,
    ) : ManagerEffect

    class LaunchIntent(
        val destination: LaunchDestination,
        val intentFactory: () -> Intent,
        val installSessionId: Int? = null,
        val ownerGeneration: Long? = null,
    ) : ManagerEffect {
        constructor(
            launch: RepositoryLaunch,
            ownerGeneration: Long? = null,
        ) : this(
            launch.destination,
            launch.intentFactory,
            launch.installSessionId,
            ownerGeneration,
        )
    }
}
