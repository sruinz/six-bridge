package dev.sixbridge.manager.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.manager.app.ManagerImportResult
import dev.sixbridge.manager.app.ManagerRepository
import dev.sixbridge.manager.app.RepositoryNavigationResult
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.uninstall.UninstallOutcome
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ManagerViewModel(
    private val repository: ManagerRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ManagerUiState())
    private val mutableEffects = MutableSharedFlow<ManagerEffect>(extraBufferCapacity = 8)
    private var routeOperation: Job? = null
    private var operationGeneration = 0L
    private var pendingUninstallPackage: String? = null
    private var initialized = false
    private var lastInstallState: InstallState? = null

    val state: StateFlow<ManagerUiState> = mutableState.asStateFlow()
    val effects: SharedFlow<ManagerEffect> = mutableEffects.asSharedFlow()

    init {
        restoreAndInitialize()
        observeCatalog()
        observeInstallState()
    }

    fun accept(action: ManagerAction) {
        when (action) {
            is ManagerAction.ImportSelected -> importSelected(action.uri)
            ManagerAction.ConfirmPartial -> confirmPartial()
            ManagerAction.InstallReviewedBundle -> installReviewedBundle()
            ManagerAction.CancelReview -> cancelReview()
            is ManagerAction.Remove -> remove(action.packageName)
            ManagerAction.OpenSamsungAddOns -> openSamsungAddOns()
            ManagerAction.ReturnedToForeground -> returnedToForeground()
            ManagerAction.DismissMessage -> mutableState.update { it.copy(message = null) }
        }
    }

    internal fun importSelected(request: Any) {
        val generation = replaceRouteOperation()
        mutableState.update {
            it.copy(
                route = ManagerRoute.Home,
                importState = ImportUiState.Importing,
                message = null,
            )
        }
        routeOperation = viewModelScope.launch {
            val result = try {
                repository.importRequest(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ManagerImportResult.Failure(ImportFailure(ImportFailureCode.SOURCE_UNREADABLE))
            }
            if (generation != operationGeneration) return@launch
            when (result) {
                is ManagerImportResult.Success -> showReview(result.review)
                is ManagerImportResult.Failure -> mutableState.update {
                    it.copy(
                        route = ManagerRoute.Home,
                        importState = ImportUiState.Idle,
                        message = importFailureMessage(result.reason.code),
                    )
                }
            }
        }
    }

    private fun restoreAndInitialize() {
        viewModelScope.launch {
            val savedToken = savedStateHandle.get<String>(REVIEW_TOKEN_KEY)
            val savedSession = savedStateHandle.get<Int>(INSTALL_SESSION_ID_KEY)
            val restored = try {
                repository.initialize(savedToken, savedSession)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            initialized = true
            if (restored?.review != null) {
                showReview(restored.review)
            } else {
                savedStateHandle[REVIEW_TOKEN_KEY] = null
            }
            if (restored?.installSessionId != null) {
                savedStateHandle[INSTALL_SESSION_ID_KEY] = restored.installSessionId
            } else {
                savedStateHandle[INSTALL_SESSION_ID_KEY] = null
            }
        }
    }

    private fun observeCatalog() {
        viewModelScope.launch {
            repository.catalog.collect { records ->
                mutableState.update { it.copy(installed = records.map(CatalogRecord::toItem)) }
            }
        }
    }

    private fun observeInstallState() {
        viewModelScope.launch {
            repository.installState.collect { installState ->
                val changed = installState != lastInstallState
                lastInstallState = installState
                mutableState.update { it.copy(installState = installState) }
                when (installState) {
                    is InstallState.WritingSession -> saveSessionIfInitialized(installState.sessionId)
                    is InstallState.AwaitingUserApproval -> saveSessionIfInitialized(installState.sessionId)
                    InstallState.UnknownSourcesPermissionRequired -> if (changed) {
                        mutableState.update {
                            it.copy(
                                message = UserMessage(
                                    UserMessageKind.UNKNOWN_SOURCES_PERMISSION,
                                    "이 앱의 알 수 없는 출처 설치를 허용해 주세요.",
                                    RecoveryAction.OPEN_SETTINGS,
                                ),
                            )
                        }
                        mutableEffects.emit(ManagerEffect.LaunchIntent(repository.unknownSourcesSettings()))
                    }
                    is InstallState.Installed -> {
                        clearSavedReviewAndSession()
                        mutableState.update {
                            it.copy(
                                route = ManagerRoute.Report(installState.packageName),
                                importState = ImportUiState.Idle,
                                message = null,
                            )
                        }
                    }
                    InstallState.Cancelled -> {
                        clearSavedReviewAndSession()
                        mutableState.update {
                            it.copy(
                                route = ManagerRoute.Home,
                                importState = ImportUiState.Idle,
                                message = UserMessage(
                                    UserMessageKind.INSTALL_CANCELLED,
                                    "시스템 설치가 취소되었습니다.",
                                    RecoveryAction.SELECT_ANOTHER_FILE,
                                ),
                            )
                        }
                    }
                    is InstallState.Failed -> {
                        clearSavedReviewAndSession()
                        mutableState.update {
                            it.copy(
                                route = ManagerRoute.Home,
                                importState = ImportUiState.Idle,
                                message = installFailureMessage(installState.code),
                            )
                        }
                    }
                    InstallState.Idle -> Unit
                }
            }
        }
    }

    private fun confirmPartial() {
        val ready = mutableState.value.importState as? ImportUiState.ReviewReady ?: return
        if (ready.review.status != ContractCompatibilityStatus.PARTIAL) return
        mutableState.update { it.copy(importState = ready.copy(partialConfirmed = true), message = null) }
    }

    private fun installReviewedBundle() {
        val ready = mutableState.value.importState as? ImportUiState.ReviewReady ?: return
        if (!ready.installEnabled) {
            val message = when (ready.review.status) {
                ContractCompatibilityStatus.BLOCKED -> UserMessage(
                    UserMessageKind.BLOCKED_COMPATIBILITY,
                    "차단된 호환성 문제가 있어 설치할 수 없습니다.",
                    RecoveryAction.SELECT_ANOTHER_FILE,
                )
                ContractCompatibilityStatus.UNKNOWN -> UserMessage(
                    UserMessageKind.UNKNOWN_COMPATIBILITY,
                    "호환성을 확인할 수 없어 설치를 차단했습니다.",
                    RecoveryAction.SELECT_ANOTHER_FILE,
                )
                ContractCompatibilityStatus.PARTIAL -> UserMessage(
                    UserMessageKind.UNKNOWN_COMPATIBILITY,
                    "부분 호환 설치 내용을 먼저 확인해 주세요.",
                    RecoveryAction.CONFIRM_PARTIAL,
                )
                ContractCompatibilityStatus.COMPATIBLE -> return
            }
            mutableState.update { it.copy(message = message) }
            return
        }
        val generation = operationGeneration
        viewModelScope.launch {
            repository.install(ready.review, ready.partialConfirmed)
            if (generation != operationGeneration) return@launch
        }
    }

    private fun cancelReview() {
        val token = (mutableState.value.importState as? ImportUiState.ReviewReady)?.review?.token
        replaceRouteOperation()
        if (token != null) repository.discardReview(token)
        clearSavedReviewAndSession()
        mutableState.update {
            it.copy(
                route = ManagerRoute.Home,
                importState = ImportUiState.Idle,
                message = null,
            )
        }
    }

    private fun remove(packageName: String) {
        pendingUninstallPackage = packageName
        mutableEffects.tryEmit(ManagerEffect.LaunchIntent(repository.uninstall(packageName)))
    }

    private fun returnedToForeground() {
        val uninstallPackage = pendingUninstallPackage
        if (uninstallPackage != null) {
            pendingUninstallPackage = null
            val generation = operationGeneration
            viewModelScope.launch {
                val outcome = repository.reconcileUninstall(uninstallPackage)
                if (generation != operationGeneration) return@launch
                mutableState.update {
                    it.copy(
                        message = when (outcome) {
                            UninstallOutcome.Removed -> UserMessage(
                                UserMessageKind.UNINSTALLED,
                                "확장 래퍼가 제거되었습니다.",
                                RecoveryAction.NONE,
                            )
                            UninstallOutcome.CancelledOrStillInstalled -> UserMessage(
                                UserMessageKind.UNINSTALL_CANCELLED,
                                "제거가 취소되었거나 확장 래퍼가 아직 설치되어 있습니다.",
                                RecoveryAction.RETRY,
                            )
                        },
                    )
                }
            }
            return
        }
        if (mutableState.value.installState == InstallState.UnknownSourcesPermissionRequired) {
            viewModelScope.launch { repository.resumeAfterUnknownSourcesSettings() }
        }
    }

    private fun openSamsungAddOns() {
        when (val result = repository.samsungAddOns()) {
            is RepositoryNavigationResult.Launch ->
                mutableEffects.tryEmit(ManagerEffect.LaunchIntent(result.effect))
            RepositoryNavigationResult.SamsungBrowserMissing -> mutableState.update {
                it.copy(
                    message = UserMessage(
                        UserMessageKind.SAMSUNG_BROWSER_MISSING,
                        "삼성 인터넷이 설치되어 있지 않습니다.",
                        RecoveryAction.NONE,
                    ),
                )
            }
            RepositoryNavigationResult.AddOnsIntentUnavailable -> mutableState.update {
                it.copy(
                    message = UserMessage(
                        UserMessageKind.SAMSUNG_ADD_ONS_UNAVAILABLE,
                        "삼성 인터넷 Add-ons 화면을 열 수 없습니다.",
                        RecoveryAction.RETRY,
                    ),
                )
            }
        }
    }

    private fun showReview(review: ReviewBundle) {
        savedStateHandle[REVIEW_TOKEN_KEY] = review.token
        mutableState.update {
            it.copy(
                route = ManagerRoute.Review(review.token),
                importState = ImportUiState.ReviewReady(review, partialConfirmed = false),
                message = null,
            )
        }
    }

    private fun replaceRouteOperation(): Long {
        routeOperation?.cancel()
        operationGeneration += 1
        return operationGeneration
    }

    private fun saveSessionIfInitialized(sessionId: Int) {
        if (initialized && sessionId >= 0) savedStateHandle[INSTALL_SESSION_ID_KEY] = sessionId
    }

    private fun clearSavedReviewAndSession() {
        savedStateHandle[REVIEW_TOKEN_KEY] = null
        savedStateHandle[INSTALL_SESSION_ID_KEY] = null
    }

    companion object {
        const val REVIEW_TOKEN_KEY = "review_token"
        const val INSTALL_SESSION_ID_KEY = "install_session_id"
    }
}

private fun CatalogRecord.toItem() = ManagedExtensionItem(
    packageName = packageName,
    extensionId = extensionId,
    name = name,
    installedVersionCode = installedVersionCode,
    compatibilityStatus = compatibilityStatus,
)

private fun importFailureMessage(code: ImportFailureCode): UserMessage = when (code) {
    ImportFailureCode.SOURCE_UNREADABLE,
    ImportFailureCode.SOURCE_TOO_LARGE,
    -> UserMessage(
        UserMessageKind.FILE_READ_OR_SIZE,
        "선택한 파일을 읽을 수 없거나 허용 크기를 초과했습니다.",
        RecoveryAction.SELECT_ANOTHER_FILE,
    )
    ImportFailureCode.INVALID_BUNDLE,
    ImportFailureCode.CACHE_BOUNDARY_INVALID,
    ImportFailureCode.CACHE_IO,
    ImportFailureCode.ARCHIVE_INVALID,
    ImportFailureCode.REPORT_SCHEMA_UNSUPPORTED,
    -> UserMessage(
        UserMessageKind.ZIP_OR_SCHEMA,
        "SIX 번들의 ZIP 또는 schema가 올바르지 않습니다.",
        RecoveryAction.SELECT_ANOTHER_FILE,
    )
    else -> UserMessage(
        UserMessageKind.IDENTITY_VERIFICATION,
        "래퍼의 hash, package, ID, signer 또는 CRX 검증에 실패했습니다.",
        RecoveryAction.SELECT_ANOTHER_FILE,
    )
}

private fun installFailureMessage(code: String): UserMessage = when (code) {
    "SIGNER_MISMATCH", "VERSION_NOT_NEWER", "PACKAGE_MISMATCH" -> UserMessage(
        UserMessageKind.UPDATE_IDENTITY,
        "설치된 래퍼와 signer 또는 version update 계약이 맞지 않습니다.",
        RecoveryAction.SELECT_ANOTHER_FILE,
    )
    "BLOCKED_COMPATIBILITY" -> UserMessage(
        UserMessageKind.BLOCKED_COMPATIBILITY,
        "차단된 호환성 문제가 있어 설치할 수 없습니다.",
        RecoveryAction.SELECT_ANOTHER_FILE,
    )
    "UNKNOWN_COMPATIBILITY" -> UserMessage(
        UserMessageKind.UNKNOWN_COMPATIBILITY,
        "호환성을 확인할 수 없어 설치를 차단했습니다.",
        RecoveryAction.SELECT_ANOTHER_FILE,
    )
    else -> UserMessage(
        UserMessageKind.INSTALL_FAILED,
        "시스템 설치를 완료하지 못했습니다.",
        RecoveryAction.RETRY,
    )
}
