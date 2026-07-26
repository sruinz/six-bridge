package dev.sixbridge.manager.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.manager.app.ManagerImportResult
import dev.sixbridge.manager.app.ManagerRepository
import dev.sixbridge.manager.app.RepositoryLaunch
import dev.sixbridge.manager.app.RepositoryNavigationResult
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.uninstall.UninstallOutcome
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ManagerViewModel(
    private val repository: ManagerRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ManagerUiState())
    private val effectChannel = Channel<ManagerEffect>(Channel.UNLIMITED)
    private var routeOperation: Job? = null
    private var installJob: Job? = null
    private var operationGeneration = 0L
    private var launchGeneration = 0L
    private var uninstallGeneration = 0L
    private var pendingUninstallLaunch: PendingUninstallLaunch? = null
    private var pendingUninstallReturn: PendingUninstallReturn? = null
    private var pendingRecovery: PendingRecovery? = null
    private var initialized = false
    private val installStateAtInitialization = repository.installState.value
    private var firstInstallStateObserved = false
    private var deferredInitialTerminal: InstallState? = null
    private var lastInstallState: InstallState? = null
    private var ownedReviewToken: String? = null
    private val pendingReviewDiscards = linkedSetOf<String>()
    private var completedReplacementCleanupGeneration = -1L
    private var suppressedInstallTerminal: InstallState? = null
    private var replacementCancelGeneration: Long? = null
    private var deferredReplacementTerminal: Pair<Long, InstallState>? = null

    val state: StateFlow<ManagerUiState> = mutableState.asStateFlow()
    val effects: Flow<ManagerEffect> = effectChannel.receiveAsFlow().filter(::isEffectCurrent)

    init {
        restoreAndInitialize()
        observeCatalog()
        observeInstallState()
        observeRepositoryLaunches()
    }

    fun accept(action: ManagerAction) {
        if (action != ManagerAction.RetryRecovery &&
            action !is ManagerAction.LaunchSucceeded &&
            action !is ManagerAction.LaunchFailed &&
            action != ManagerAction.ReturnedToForeground
        ) {
            beginNewUserAction()
        }
        when (action) {
            ManagerAction.FilePickerOpened -> Unit
            is ManagerAction.ImportSelected -> startImport { repository.import(action.uri) }
            ManagerAction.ConfirmPartial -> confirmPartial()
            ManagerAction.InstallReviewedBundle -> installReviewedBundle()
            ManagerAction.CancelReview -> cancelReview()
            is ManagerAction.Remove -> remove(action.packageName)
            is ManagerAction.OpenReport -> openReport(action.packageName)
            ManagerAction.ReturnHome -> mutableState.update { it.copy(route = ManagerRoute.Home) }
            ManagerAction.OpenSamsungAddOns -> openSamsungAddOns()
            ManagerAction.OpenUnknownSourcesSettings -> openUnknownSourcesSettings()
            ManagerAction.ReturnedToForeground -> returnedToForeground()
            ManagerAction.DismissMessage -> mutableState.update { it.copy(message = null) }
            ManagerAction.RetryRecovery -> retryRecovery()
            is ManagerAction.LaunchSucceeded -> recordLaunchSuccess(action.effect)
            is ManagerAction.LaunchFailed -> recordLaunchFailure(action.effect)
        }
    }

    internal fun startImport(importOperation: suspend () -> ManagerImportResult) {
        moveOwnedReviewToPendingDiscard()
        val supersededInstall = detachInstallJob()
        supersededInstall?.cancel()
        val generation = replaceRouteOperation()
        routeOperation = viewModelScope.launch {
            supersededInstall?.cancelAndJoin()
            val cleaned = try {
                cancelInstallForRoute(generation, suppressTerminalOnSuccess = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            if (!cleaned) return@launch
            if (generation != operationGeneration) return@launch
            if (!discardPendingReviews()) return@launch
            if (generation != operationGeneration) return@launch
            completedReplacementCleanupGeneration = generation
            clearSavedReviewAndSession()
            mutableState.update {
                it.copy(
                    route = ManagerRoute.Home,
                    importState = ImportUiState.Importing,
                    message = null,
                )
            }
            val result = try {
                importOperation()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ManagerImportResult.Failure(ImportFailure(ImportFailureCode.SOURCE_UNREADABLE))
            }
            if (generation != operationGeneration) {
                if (result is ManagerImportResult.Success) {
                    queuePendingReviewDiscard(result.review.token)
                }
                return@launch
            }
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
        val generation = operationGeneration
        viewModelScope.launch {
            val savedToken = savedStateHandle.get<String>(REVIEW_TOKEN_KEY)
            val savedSession = savedStateHandle.get<Int>(INSTALL_SESSION_ID_KEY)
            val restored = try {
                repository.initialize(savedToken, savedSession)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            initialized = true
            if (generation != operationGeneration) {
                deferredInitialTerminal = null
                restored.review?.token?.let(::queuePendingReviewDiscard)
                return@launch
            }
            if (savedStateHandle.get<String>(REVIEW_TOKEN_KEY) != savedToken) {
                deferredInitialTerminal = null
                return@launch
            }
            if (restored.review != null) {
                showReview(restored.review)
            } else {
                savedStateHandle[REVIEW_TOKEN_KEY] = null
            }
            if (restored.installSessionId != null) {
                savedStateHandle[INSTALL_SESSION_ID_KEY] = restored.installSessionId
            } else {
                savedStateHandle[INSTALL_SESSION_ID_KEY] = null
            }
            val deferredTerminal = deferredInitialTerminal
            deferredInitialTerminal = null
            if (restored.review == null && deferredTerminal != null) {
                applyInstallTerminal(deferredTerminal)
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
                val firstEmission = !firstInstallStateObserved
                firstInstallStateObserved = true
                val changed = installState != lastInstallState
                lastInstallState = installState
                mutableState.update { it.copy(installState = installState) }
                if (firstEmission &&
                    installState == installStateAtInitialization &&
                    installState.isTerminalState() &&
                    savedStateHandle.get<String>(REVIEW_TOKEN_KEY) != null
                ) {
                    if (!initialized) deferredInitialTerminal = installState
                    if (!initialized || ownedReviewToken != null) return@collect
                }
                if (installState.isTerminalState()) {
                    replacementCancelGeneration?.let { generation ->
                        deferredReplacementTerminal = generation to installState
                        return@collect
                    }
                }
                val suppressedTerminal = installState == suppressedInstallTerminal
                if (suppressedTerminal) suppressedInstallTerminal = null
                when (installState) {
                    is InstallState.WritingSession -> {
                        saveSessionIfInitialized(installState.sessionId)
                        clearLaunchRecovery(LaunchDestination.UNKNOWN_SOURCES_SETTINGS)
                        clearUnknownSourcesMessage()
                    }
                    is InstallState.AwaitingUserApproval -> {
                        saveSessionIfInitialized(installState.sessionId)
                        clearLaunchRecovery(LaunchDestination.UNKNOWN_SOURCES_SETTINGS)
                        clearUnknownSourcesMessage()
                    }
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
                        enqueueEffect(currentLaunchEffect(repository.unknownSourcesSettings()))
                    }
                    is InstallState.Installed -> {
                        if (suppressedTerminal) return@collect
                        applyInstallTerminal(installState)
                    }
                    InstallState.Cancelled -> {
                        if (!suppressedTerminal) applyInstallTerminal(installState)
                    }
                    is InstallState.Failed -> {
                        if (suppressedTerminal) return@collect
                        applyInstallTerminal(installState)
                    }
                    InstallState.Idle -> Unit
                }
            }
        }
    }

    private fun observeRepositoryLaunches() {
        viewModelScope.launch {
            repository.launches.collect { launch ->
                enqueueEffect(currentLaunchEffect(launch))
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
        if (installJob?.isActive == true) return
        val generation = operationGeneration
        val reviewToken = ready.review.token
        installJob = viewModelScope.launch {
            val attemptResult = repository.install(ready.review, ready.partialConfirmed)
            if (generation != operationGeneration) return@launch
            if (attemptResult.isTerminalState() &&
                ownedReviewToken == reviewToken &&
                savedStateHandle.get<String>(REVIEW_TOKEN_KEY) == reviewToken &&
                mutableState.value.route == ManagerRoute.Review(reviewToken)
            ) {
                applyInstallTerminal(attemptResult)
            }
        }
    }

    private fun cancelReview() {
        moveOwnedReviewToPendingDiscard()
        val supersededInstall = detachInstallJob()
        supersededInstall?.cancel()
        val generation = replaceRouteOperation()
        routeOperation = viewModelScope.launch {
            supersededInstall?.cancelAndJoin()
            val cleaned = try {
                cancelInstallForRoute(generation, suppressTerminalOnSuccess = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@launch
            }
            if (cleaned &&
                generation == operationGeneration
            ) {
                if (!discardPendingReviews()) return@launch
                completedReplacementCleanupGeneration = generation
                clearSavedReviewAndSession()
                mutableState.update {
                    it.copy(
                        route = ManagerRoute.Home,
                        importState = ImportUiState.Idle,
                        message = null,
                    )
                }
            }
        }
    }

    private fun remove(packageName: String) {
        val effect = currentLaunchEffect(repository.uninstall(packageName))
        pendingUninstallLaunch = PendingUninstallLaunch(
            effect = effect,
            packageName = packageName,
            generation = uninstallGeneration,
        )
        enqueueEffect(effect)
    }

    private fun returnedToForeground() {
        val pendingReturn = pendingUninstallReturn
        if (pendingReturn != null && pendingReturn.generation == uninstallGeneration) {
            pendingUninstallReturn = null
            viewModelScope.launch {
                val outcome = try {
                    repository.reconcileUninstall(pendingReturn.packageName)
                } finally {
                    repository.reconcileCatalog()
                }
                if (pendingReturn.generation != uninstallGeneration) return@launch
                if (outcome == UninstallOutcome.CancelledOrStillInstalled) {
                    pendingRecovery = PendingRecovery.Uninstall(pendingReturn.packageName)
                }
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
        viewModelScope.launch {
            try {
                if (mutableState.value.installState == InstallState.UnknownSourcesPermissionRequired) {
                    repository.resumeAfterUnknownSourcesSettings()
                }
            } finally {
                repository.reconcileCatalog()
            }
        }
    }

    private fun openSamsungAddOns() {
        when (val result = repository.samsungAddOns()) {
            is RepositoryNavigationResult.Launch ->
                enqueueEffect(currentLaunchEffect(result.effect))
            RepositoryNavigationResult.SamsungBrowserMissing -> mutableState.update {
                it.copy(
                    message = UserMessage(
                        UserMessageKind.SAMSUNG_BROWSER_MISSING,
                        "삼성 인터넷이 설치되어 있지 않습니다.",
                        RecoveryAction.NONE,
                    ),
                )
            }
            RepositoryNavigationResult.AddOnsIntentUnavailable -> {
                pendingRecovery = PendingRecovery.SamsungAddOns
                mutableState.update {
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
    }

    private fun openReport(packageName: String) {
        if (mutableState.value.installed.none { it.packageName == packageName }) return
        mutableState.update { it.copy(route = ManagerRoute.Report(packageName), message = null) }
    }

    private fun openUnknownSourcesSettings() {
        if (mutableState.value.installState != InstallState.UnknownSourcesPermissionRequired) return
        enqueueEffect(currentLaunchEffect(repository.unknownSourcesSettings()))
    }

    private fun recordLaunchFailure(effect: ManagerEffect.LaunchIntent) {
        if (!isEffectCurrent(effect)) return
        val uninstallPackage = if (effect.destination == LaunchDestination.UNINSTALL) {
            val pendingLaunch = pendingUninstallLaunch
                ?.takeIf {
                    it.effect === effect &&
                        it.generation == uninstallGeneration
                }
                ?: return
            pendingUninstallLaunch = null
            pendingUninstallReturn = null
            pendingLaunch.packageName
        } else {
            null
        }
        val failureText = if (uninstallPackage != null) {
            "시스템 제거 화면을 열 수 없습니다."
        } else {
            "시스템 화면을 열 수 없습니다."
        }
        pendingRecovery = PendingRecovery.Launch(effect, uninstallPackage)
        mutableState.update {
            it.copy(
                message = UserMessage(
                    UserMessageKind.LAUNCH_FAILED,
                    failureText,
                    RecoveryAction.RETRY,
                ),
            )
        }
    }

    private fun recordLaunchSuccess(effect: ManagerEffect.LaunchIntent) {
        if (!isEffectCurrent(effect)) return
        if (effect.destination != LaunchDestination.UNINSTALL) return
        val pendingLaunch = pendingUninstallLaunch
            ?.takeIf {
                it.effect === effect &&
                    it.generation == uninstallGeneration
            }
            ?: return
        pendingUninstallLaunch = null
        pendingUninstallReturn = PendingUninstallReturn(
            packageName = pendingLaunch.packageName,
            generation = pendingLaunch.generation,
        )
    }

    private fun retryRecovery() {
        val recovery = pendingRecovery ?: return
        pendingRecovery = null
        invalidateUninstallAttempt()
        launchGeneration += 1
        mutableState.update { it.copy(message = null) }
        when (recovery) {
            PendingRecovery.SamsungAddOns -> openSamsungAddOns()
            is PendingRecovery.Uninstall -> remove(recovery.packageName)
            is PendingRecovery.Launch -> {
                val retriedEffect = ManagerEffect.LaunchIntent(
                    destination = recovery.effect.destination,
                    intentFactory = recovery.effect.intentFactory,
                    installSessionId = recovery.effect.installSessionId,
                    ownerGeneration = launchGeneration,
                )
                recovery.uninstallPackage?.let { packageName ->
                    pendingUninstallLaunch = PendingUninstallLaunch(
                        effect = retriedEffect,
                        packageName = packageName,
                        generation = uninstallGeneration,
                    )
                }
                enqueueEffect(retriedEffect)
            }
        }
    }

    private fun beginNewUserAction() {
        invalidateUninstallAttempt()
        launchGeneration += 1
        pendingRecovery = null
        if (mutableState.value.message?.recoveryAction == RecoveryAction.RETRY) {
            mutableState.update { it.copy(message = null) }
        }
    }

    private fun invalidateUninstallAttempt() {
        uninstallGeneration += 1
        pendingUninstallLaunch = null
        pendingUninstallReturn = null
    }

    private fun clearLaunchRecovery(vararg destinations: LaunchDestination) {
        val recovery = pendingRecovery as? PendingRecovery.Launch ?: return
        if (recovery.effect.destination !in destinations) return
        pendingRecovery = null
        if (mutableState.value.message?.kind == UserMessageKind.LAUNCH_FAILED) {
            mutableState.update { it.copy(message = null) }
        }
    }

    private fun clearUnknownSourcesMessage() {
        mutableState.update {
            if (it.message?.kind == UserMessageKind.UNKNOWN_SOURCES_PERMISSION) {
                it.copy(message = null)
            } else {
                it
            }
        }
    }

    private fun showReview(review: ReviewBundle) {
        ownedReviewToken = review.token
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

    private fun detachInstallJob(): Job? =
        installJob.also { installJob = null }

    private fun saveSessionIfInitialized(sessionId: Int) {
        if (initialized && sessionId >= 0) savedStateHandle[INSTALL_SESSION_ID_KEY] = sessionId
    }

    private fun clearSavedReviewAndSession() {
        ownedReviewToken = null
        savedStateHandle[REVIEW_TOKEN_KEY] = null
        savedStateHandle[INSTALL_SESSION_ID_KEY] = null
    }

    private fun moveOwnedReviewToPendingDiscard() {
        ownedReviewToken?.let(pendingReviewDiscards::add)
    }

    private fun queuePendingReviewDiscard(token: String) {
        if (token == ownedReviewToken) return
        pendingReviewDiscards += token
        if (completedReplacementCleanupGeneration == operationGeneration) {
            discardPendingReviews()
        }
    }

    private fun discardPendingReviews(): Boolean {
        val currentReviewToken = ownedReviewToken
        pendingReviewDiscards
            .toList()
            .filterNot { it == currentReviewToken }
            .forEach { token ->
                if (!repository.discardReview(token)) return false
                pendingReviewDiscards.remove(token)
            }
        if (currentReviewToken != null && currentReviewToken in pendingReviewDiscards) {
            if (!repository.discardReview(currentReviewToken)) return false
            pendingReviewDiscards.remove(currentReviewToken)
        }
        return true
    }

    private suspend fun cancelInstallForRoute(
        generation: Long,
        suppressTerminalOnSuccess: Boolean,
    ): Boolean {
        val previous = repository.installState.value
        val replacingActiveInstall =
            previous == InstallState.UnknownSourcesPermissionRequired ||
            previous is InstallState.WritingSession ||
            previous is InstallState.AwaitingUserApproval ||
            previous is InstallState.Failed && previous.code == "CLEANUP_FAILED"
        if (replacingActiveInstall) replacementCancelGeneration = generation
        val cleaned = try {
            repository.cancelInstall()
        } catch (error: CancellationException) {
            clearReplacementCancelMarker(generation)
            throw error
        } catch (error: Exception) {
            when (val deferred = clearReplacementCancelMarker(generation)) {
                is InstallState.Installed -> applyInstallTerminal(deferred)
                null -> Unit
                else -> suppressedInstallTerminal = deferred
            }
            throw error
        }
        if (!replacingActiveInstall || replacementCancelGeneration != generation) return cleaned

        val deferred = clearReplacementCancelMarker(generation)
        if (!cleaned) {
            val terminal = deferred ?: repository.installState.value
                .takeIf(InstallState::isTerminalState)
            if (terminal != null) suppressedInstallTerminal = terminal
            return false
        }
        if (suppressTerminalOnSuccess) {
            if (deferred == null) {
                repository.installState.value
                    .takeIf(InstallState::isTerminalState)
                    ?.let { suppressedInstallTerminal = it }
            }
        } else {
            deferred?.let(::applyInstallTerminal)
        }
        return cleaned
    }

    private fun clearReplacementCancelMarker(generation: Long): InstallState? {
        if (replacementCancelGeneration != generation) return null
        val deferred = deferredReplacementTerminal
            ?.takeIf { it.first == generation }
            ?.second
        replacementCancelGeneration = null
        deferredReplacementTerminal = null
        return deferred
    }

    private fun applyInstallTerminal(installState: InstallState) {
        supersedeRecoveryForTerminal()
        if (installState is InstallState.Failed &&
            installState.code in CLEANUP_RETRYABLE_FAILURES &&
            savedStateHandle.get<String>(REVIEW_TOKEN_KEY) != null
        ) {
            return
        }
        clearSavedReviewAndSession()
        mutableState.update {
            when (installState) {
                is InstallState.Installed -> it.copy(
                    route = ManagerRoute.Report(installState.packageName),
                    importState = ImportUiState.Idle,
                    message = null,
                )
                InstallState.Cancelled -> it.copy(
                    route = ManagerRoute.Home,
                    importState = ImportUiState.Idle,
                    message = UserMessage(
                        UserMessageKind.INSTALL_CANCELLED,
                        "시스템 설치가 취소되었습니다.",
                        RecoveryAction.SELECT_ANOTHER_FILE,
                    ),
                )
                is InstallState.Failed -> it.copy(
                    route = ManagerRoute.Home,
                    importState = ImportUiState.Idle,
                    message = installFailureMessage(installState.code),
                )
                else -> it
            }
        }
    }

    private fun enqueueEffect(effect: ManagerEffect) {
        effectChannel.trySend(effect)
    }

    private fun currentLaunchEffect(launch: RepositoryLaunch) =
        ManagerEffect.LaunchIntent(
            launch = launch,
            ownerGeneration = if (
                launch.destination == LaunchDestination.SYSTEM_INSTALL_CONFIRMATION
            ) {
                null
            } else {
                launchGeneration
            },
        )

    private fun supersedeRecoveryForTerminal() {
        val ownsRetry = pendingRecovery != null ||
            mutableState.value.message?.recoveryAction == RecoveryAction.RETRY
        if (ownsRetry) launchGeneration += 1
        pendingRecovery = null
        if (mutableState.value.message?.recoveryAction == RecoveryAction.RETRY) {
            mutableState.update { it.copy(message = null) }
        }
    }

    private fun isEffectCurrent(effect: ManagerEffect): Boolean {
        if (effect !is ManagerEffect.LaunchIntent) return true
        if (effect.ownerGeneration?.let { it != launchGeneration } == true) return false
        if (effect.destination != LaunchDestination.SYSTEM_INSTALL_CONFIRMATION &&
            effect.ownerGeneration == null
        ) {
            return false
        }
        if (effect.destination == LaunchDestination.UNKNOWN_SOURCES_SETTINGS &&
            repository.installState.value != InstallState.UnknownSourcesPermissionRequired
        ) {
            return false
        }
        if (effect.destination != LaunchDestination.SYSTEM_INSTALL_CONFIRMATION) return true
        val sessionId = effect.installSessionId ?: return false
        return repository.installState.value == InstallState.AwaitingUserApproval(sessionId)
    }

    companion object {
        const val REVIEW_TOKEN_KEY = "review_token"
        const val INSTALL_SESSION_ID_KEY = "install_session_id"
        private val CLEANUP_RETRYABLE_FAILURES = setOf(
            "CLEANUP_FAILED",
            "ABANDON_FAILED",
            "SESSION_RECOVERY_FAILED",
            "MISSING_OBSERVATION_RESET_FAILED",
        )
    }
}

private fun CatalogRecord.toItem(): ManagedExtensionItem {
    val decoded = reportJson?.let { encoded ->
        runCatching { BundleJson.decodeCompatibility(encoded) }
    }
    val report = decoded?.getOrNull()
    return ManagedExtensionItem(
        packageName = packageName,
        extensionId = extensionId,
        name = name,
        iconPng = iconPng,
        installedVersionCode = installedVersionCode,
        compatibilityStatus = report?.status ?: ContractCompatibilityStatus.UNKNOWN,
        signerSha256 = signerSha256,
        report = report,
        reportAvailability = when {
            reportJson == null -> ReportAvailability.MISSING
            report != null -> ReportAvailability.AVAILABLE
            else -> ReportAvailability.UNREADABLE
        },
    )
}

private fun InstallState.isTerminalState(): Boolean =
    this is InstallState.Installed || this == InstallState.Cancelled || this is InstallState.Failed

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
        RecoveryAction.SELECT_ANOTHER_FILE,
    )
}

private sealed interface PendingRecovery {
    data object SamsungAddOns : PendingRecovery
    data class Uninstall(val packageName: String) : PendingRecovery
    data class Launch(
        val effect: ManagerEffect.LaunchIntent,
        val uninstallPackage: String? = null,
    ) : PendingRecovery
}

private data class PendingUninstallLaunch(
    val effect: ManagerEffect.LaunchIntent,
    val packageName: String,
    val generation: Long,
)

private data class PendingUninstallReturn(
    val packageName: String,
    val generation: Long,
)
