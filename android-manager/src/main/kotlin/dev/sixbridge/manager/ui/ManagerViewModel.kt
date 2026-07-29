package dev.sixbridge.manager.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.manager.app.ManagerImportResult
import dev.sixbridge.manager.app.ManagerRepository
import dev.sixbridge.manager.app.ManagerRestoreResult
import dev.sixbridge.manager.app.RestoredInstallTerminal
import dev.sixbridge.manager.app.RepositoryLaunch
import dev.sixbridge.manager.app.RepositoryNavigationResult
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.app.TerminalReceiptOwnerResult
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.conversion.ConversionBeginResult
import dev.sixbridge.manager.conversion.ConversionBuildResult
import dev.sixbridge.manager.conversion.ConversionFailureCode
import dev.sixbridge.manager.conversion.InstallationIdentitySelection
import dev.sixbridge.manager.conversion.OnDeviceInputRequest
import dev.sixbridge.manager.conversion.RestoredConversion
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.install.InstallCleanupResult
import dev.sixbridge.manager.install.FinalReceiptStage
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.install.InstallTerminalOwnerResult
import dev.sixbridge.manager.install.NormalHandoffStage
import dev.sixbridge.manager.install.NormalInstallHandoff
import dev.sixbridge.manager.uninstall.UninstallOutcome
import dev.sixbridge.manager.GeneratedDocumentSaveResult
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

class ManagerViewModel(
    private val repository: ManagerRepository,
    private val savedStateHandle: SavedStateHandle,
    private val saveGeneratedDocument: suspend (
        destination: String,
        expectedHash: String,
        copy: (OutputStream) -> String?,
    ) -> GeneratedDocumentSaveResult = { _, _, _ ->
        GeneratedDocumentSaveResult.Failure(cleanupFailed = false)
    },
    private val afterGeneratedInstallCleanupTransitionPersisted: () -> Unit = {},
    private val afterGeneratedReviewCleanupCompleted: () -> Unit = {},
    private val afterGeneratedCleanupCompletionStep: (
        GeneratedCleanupCompletionStep,
    ) -> Unit = {},
    private val afterGeneratedTerminalHandoffPersisted: () -> Unit = {},
    private val afterGeneratedTerminalHandoffRebased: () -> Unit = {},
    private val afterTerminalReceiptFinalized: () -> Unit = {},
    private val afterNormalHandoffPersisted: () -> Unit = {},
    private val afterGeneratedTerminalHandoffCleared: () -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow(ManagerUiState())
    private val effectChannel = Channel<ManagerEffect>(Channel.UNLIMITED)
    private var routeOperation: Job? = null
    private var routeCleanup: Job? = null
    private var routeCleanupFailed = false
    private val scheduledConversionCleanupTokens = mutableSetOf<String>()
    private var conversionIoJob: Job? = null
    private var conversionIoResult: CompletableDeferred<GeneratedDocumentSaveResult>? = null
    private var generatedShareJob: Job? = null
    private var pendingGeneratedShareHandoff: PendingGeneratedShareHandoff? = null
    private var generatedShareHandoffSequence = 0L
    private var installJob: Job? = null
    private var pendingSystemInstallApkDocument:
        ManagerEffect.CreateSystemInstallApkDocument? =
        restorePendingSystemInstallApkDocument()
    private var operationGeneration = 0L
    private var launchGeneration = 0L
    private var uninstallGeneration = 0L
    private var pendingUninstallLaunch: PendingUninstallLaunch? = null
    private var pendingUninstallReturn: PendingUninstallReturn? = null
    private var systemInstallReturnToken: String? = null
    private var pendingRecovery: PendingRecovery? = null
    private var initialized = false
    private val installStateAtInitialization = repository.installState.value
    private var firstInstallStateObserved = false
    private var deferredInitialTerminals: DeferredInitialTerminals? = null
    private var lastInstallState: InstallState? = null
    private var ownedReviewToken: String? = null
    private val pendingReviewDiscards = linkedSetOf<String>()
    private var completedReplacementCleanupGeneration = -1L
    private var suppressedInstallTerminal: InstallState? = null
    private var replacementCancelGeneration: Long? = null
    private var deferredReplacementTerminal: Pair<Long, InstallState>? = null
    private var generatedInstallOwnerToken: String? =
        savedStateHandle[INSTALL_OWNER_CONVERSION_TOKEN_KEY]
    private var generatedResultOwnerToken: String? =
        savedStateHandle[RESULT_OWNER_CONVERSION_TOKEN_KEY]
    private var generatedResultReviewToken: String? =
        savedStateHandle[RESULT_OWNER_REVIEW_TOKEN_KEY]
    private var generatedCleanupRestoreInvalid = false
    private var generatedTerminalHandoffRestoreInvalid = false
    private var pendingNormalHandoff: NormalInstallHandoff? = null
    private var pendingGeneratedTerminalHandoff = restoreGeneratedTerminalHandoff()
    private var pendingGeneratedCleanup =
        if (generatedTerminalHandoffRestoreInvalid) null else restorePendingGeneratedCleanup()
    private var quarantinedGeneratedCleanup = restoreQuarantinedGeneratedCleanup()
    private var intermediateGeneratedCleanupRestorePending = false
    private var generatedCleanupJob: Job? = null
    private var quarantinedGeneratedCleanupJob: Job? = null
    private var documentOwnerGeneration =
        savedStateHandle.get<Long>(DOCUMENT_OWNER_GENERATION_KEY) ?: 0L
    private var documentLaunchSequence =
        savedStateHandle.get<Long>(DOCUMENT_LAUNCH_SEQUENCE_KEY) ?: 0L
    private var pendingSixPicker = restorePendingSixPicker()
    private var pendingConversionPicker = restorePendingConversionPicker()
    private var pendingConversionSave = restorePendingConversionSave()

    val state: StateFlow<ManagerUiState> = mutableState.asStateFlow()
    val effects: Flow<ManagerEffect> = effectChannel.receiveAsFlow().filter(::isEffectCurrent)

    init {
        if (!generatedCleanupRestoreInvalid &&
            !generatedTerminalHandoffRestoreInvalid
        ) {
            pendingGeneratedCleanup
                ?.takeUnless {
                    it.phase == GeneratedCleanupPhase.COMMIT_DESTINATION ||
                        it.matchesResultOwners()
                }
                ?.let(::quarantineGeneratedCleanup)
        }
        intermediateGeneratedCleanupRestorePending =
            pendingGeneratedCleanup?.phase in setOf(
                GeneratedCleanupPhase.REVIEW,
                GeneratedCleanupPhase.CONVERSION,
            )
        restoreAndInitialize()
        observeCatalog()
        observeInstallState()
        observeRepositoryLaunches()
    }

    fun accept(action: ManagerAction) {
        if (generatedCleanupRestoreInvalid) {
            showGeneratedCleanupRestoreBlocked()
            return
        }
        if (generatedTerminalHandoffRestoreInvalid) {
            showStartupRecoveryBlocked()
            return
        }
        if (!gateStartupAction(action)) return
        if (intermediateGeneratedCleanupRestorePending &&
            pendingGeneratedCleanup?.phase in setOf(
                GeneratedCleanupPhase.REVIEW,
                GeneratedCleanupPhase.CONVERSION,
            ) &&
            action.mutatesGeneratedCleanupOwners()
        ) {
            return
        }
        if (!acknowledgeNormalOutcome(action)) return
        if (mutableState.value.startupRecoveryState == StartupRecoveryUiState.READY &&
            action != ManagerAction.RetryRecovery &&
            action != ManagerAction.RetryGeneratedCleanup &&
            action !is ManagerAction.LaunchSucceeded &&
            action !is ManagerAction.LaunchFailed &&
            action !is ManagerAction.GeneratedShareLaunchSucceeded &&
            action !is ManagerAction.GeneratedShareLaunchFailed &&
            action !is ManagerAction.ImportSelected &&
            action !is ManagerAction.ConversionFileSelected &&
            action !is ManagerAction.SaveGeneratedConversionTo &&
            action !is ManagerAction.SystemInstallApkCreated &&
            action !is ManagerAction.ContinueInstallAfterNotificationPermission &&
            action !is ManagerAction.GeneratedCleanupDestinationPresented &&
            action != ManagerAction.ReturnedToForeground
        ) {
            beginNewUserAction(
                preserveRetryMessage = action == ManagerAction.PrepareGeneratedInstall ||
                    action == ManagerAction.SaveGeneratedConversion ||
                    action == ManagerAction.ShareGeneratedConversion,
            )
        }
        when (action) {
            ManagerAction.OpenSixFilePicker ->
                requestSixFilePicker()
            is ManagerAction.ImportSelected -> acceptSixFileResult(action)
            ManagerAction.OpenConversionInput -> openConversionInput()
            is ManagerAction.ConversionUrlChanged -> updateConversionUrl(action.value)
            ManagerAction.OpenConversionFilePicker -> requestConversionFilePicker()
            is ManagerAction.ConversionFileSelected -> acceptConversionFileResult(action)
            ManagerAction.AnalyzeConversionUrl -> analyzeConversionUrl()
            is ManagerAction.ToggleConversionAdapter -> updateConversionAnalysis {
                it.toggleAdapter(action.id)
            }
            ManagerAction.ConfirmConversionPartial -> updateConversionAnalysis {
                it.copy(partialConfirmed = true)
            }
            ManagerAction.SelectNewConversionIdentity -> updateConversionAnalysis {
                it.selectIdentity(InstallationIdentitySelection.CreateNew)
            }
            is ManagerAction.SelectExistingConversionIdentity -> updateConversionAnalysis {
                it.selectIdentity(
                    InstallationIdentitySelection.UseExisting(action.identitySha256),
                )
            }
            ManagerAction.ConfirmExistingConversionIdentity -> updateConversionAnalysis {
                if (it.identitySelection is InstallationIdentitySelection.UseExisting) {
                    it.copy(existingIdentityConfirmed = true)
                } else {
                    it
                }
            }
            ManagerAction.BuildConversion -> buildConversion()
            ManagerAction.PrepareGeneratedInstall -> prepareGeneratedInstall()
            ManagerAction.SaveGeneratedConversion -> requestGeneratedSave()
            is ManagerAction.SaveGeneratedConversionTo -> acceptConversionSaveResult(action)
            ManagerAction.ShareGeneratedConversion -> shareGenerated()
            is ManagerAction.GeneratedShareLaunchSucceeded ->
                acknowledgeGeneratedShareHandoff(
                    action.token,
                    action.handoffId,
                    launched = true,
                )
            is ManagerAction.GeneratedShareLaunchFailed ->
                acknowledgeGeneratedShareHandoff(
                    action.token,
                    action.handoffId,
                    launched = false,
                )
            ManagerAction.FinishConversion -> finishConversion()
            ManagerAction.CancelConversion -> cancelConversion()
            ManagerAction.RetryGeneratedCleanup -> retryGeneratedCleanup()
            is ManagerAction.GeneratedCleanupDestinationPresented ->
                acknowledgeGeneratedCleanupDestination(action.token)
            ManagerAction.ConfirmPartial -> confirmPartial()
            ManagerAction.PrepareReviewedInstall -> prepareReviewedInstall()
            ManagerAction.InstallReviewedBundle -> installReviewedBundle()
            is ManagerAction.SystemInstallApkCreated ->
                acceptSystemInstallApkCreated(action)
            ManagerAction.ConfirmSystemInstallStatus -> confirmSystemInstallStatus()
            is ManagerAction.ContinueInstallAfterNotificationPermission ->
                continueInstallAfterNotificationPermission(action)
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

    private fun gateStartupAction(action: ManagerAction): Boolean {
        if (action is ManagerAction.OpenReport || action == ManagerAction.ReturnHome) return true
        return when (mutableState.value.startupRecoveryState) {
            StartupRecoveryUiState.READY -> true
            StartupRecoveryUiState.INITIALIZING -> false
            StartupRecoveryUiState.RECOVERY_BLOCKED -> {
                showStartupRecoveryBlocked()
                false
            }
        }
    }

    private fun acknowledgeNormalOutcome(action: ManagerAction): Boolean {
        val handoff = pendingNormalHandoff ?: return true
        if (handoff.stage != NormalHandoffStage.OUTCOME_READY) return true
        val acknowledges = when (val terminal = handoff.state) {
            InstallState.Cancelled,
            is InstallState.Failed,
            -> action == ManagerAction.DismissMessage &&
                mutableState.value.route == ManagerRoute.Home &&
                mutableState.value.message == normalOutcomeMessage(terminal)
            is InstallState.Installed ->
                action == ManagerAction.ReturnHome &&
                    mutableState.value.route == ManagerRoute.Report(terminal.packageName)
            else -> false
        }
        if (!acknowledges) return true
        if (!repository.compareAndSetNormalHandoff(handoff, null)) return false
        pendingNormalHandoff = null
        pendingReviewDiscards.remove(handoff.importToken)
        return true
    }

    internal fun startImport(importOperation: suspend () -> ManagerImportResult) {
        if (mutableState.value.startupRecoveryState != StartupRecoveryUiState.READY) return
        invalidatePendingDocumentLaunches()
        moveOwnedReviewToPendingDiscard()
        val supersededInstall = detachInstallJob()
        supersededInstall?.cancel()
        val ticket = replaceRouteOperation()
        val generation = ticket.generation
        routeOperation = viewModelScope.launch {
            if (!awaitRouteMutation(ticket)) return@launch
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

    private fun openConversionInput() {
        if (mutableState.value.startupRecoveryState != StartupRecoveryUiState.READY) return
        if (mutableState.value.conversionState is ConversionUiState.Building &&
            routeCleanup?.isActive != true
        ) {
            return
        }
        if (pendingNormalHandoff != null) {
            val ticket = replaceRouteOperation()
            val generation = ticket.generation
            routeOperation = viewModelScope.launch {
                if (!awaitRouteMutation(ticket)) return@launch
                if (generation != operationGeneration) return@launch
                openConversionInput()
            }
            return
        }
        if (mutableState.value.conversionState is ConversionUiState.ResultReady) {
            cleanupConversion(ManagerRoute.ConversionInput)
            return
        }
        mutableState.update {
            it.copy(
                route = ManagerRoute.ConversionInput,
                conversionState = ConversionUiState.Idle,
                conversionUrl = "",
                message = null,
            )
        }
        clearSavedConversion()
    }

    private fun updateConversionUrl(value: String) {
        if (mutableState.value.route != ManagerRoute.ConversionInput) return
        if (mutableState.value.conversionState == ConversionUiState.Acquiring) return
        mutableState.update { it.copy(conversionUrl = value, message = null) }
    }

    private fun requestConversionFilePicker() {
        if (mutableState.value.route != ManagerRoute.ConversionInput ||
            mutableState.value.conversionState == ConversionUiState.Acquiring
        ) {
            return
        }
        val launch = newDocumentLaunch()
        pendingConversionPicker = launch
        savePendingConversionPicker(launch)
        enqueueEffect(
            ManagerEffect.PickConversionDocument(
                ownerGeneration = launch.ownerGeneration,
                launchId = launch.launchId,
            ),
        )
    }

    private fun requestSixFilePicker() {
        val launch = newDocumentLaunch()
        pendingSixPicker = launch
        savePendingSixPicker(launch)
        enqueueEffect(
            ManagerEffect.PickSixDocument(
                ownerGeneration = launch.ownerGeneration,
                launchId = launch.launchId,
            ),
        )
    }

    private fun acceptSixFileResult(action: ManagerAction.ImportSelected) {
        pendingSixPicker
            ?.takeIf {
                it.ownerGeneration == action.ownerGeneration &&
                    it.launchId == action.launchId &&
                    it.ownerGeneration == documentOwnerGeneration
            }
            ?: return
        clearPendingSixPicker()
        beginNewUserAction()
        action.uri?.let { uri ->
            startImport { repository.import(uri) }
        }
    }

    private fun acceptConversionFileResult(
        action: ManagerAction.ConversionFileSelected,
    ) {
        val pending = pendingConversionPicker
            ?.takeIf {
                it.ownerGeneration == action.ownerGeneration &&
                    it.launchId == action.launchId &&
                    it.ownerGeneration == documentOwnerGeneration
            }
            ?: return
        if (mutableState.value.route != ManagerRoute.ConversionInput) return
        clearPendingConversionPicker()
        action.uri?.let { beginConversion(OnDeviceInputRequest.Saf(it)) }
    }

    private fun analyzeConversionUrl() {
        val url = mutableState.value.conversionUrl.trim()
        if (url.isEmpty()) {
            mutableState.update {
                it.copy(message = conversionFailureMessage(ConversionFailureCode.URL))
            }
            return
        }
        beginConversion(OnDeviceInputRequest.ChromeStore(url))
    }

    private fun beginConversion(request: OnDeviceInputRequest) {
        if (mutableState.value.conversionState == ConversionUiState.Acquiring) return
        val ticket = replaceRouteOperation()
        val generation = ticket.generation
        mutableState.update {
            it.copy(
                route = ManagerRoute.ConversionInput,
                conversionState = ConversionUiState.Acquiring,
                message = null,
            )
        }
        routeOperation = viewModelScope.launch {
            if (!awaitRouteMutation(ticket)) return@launch
            val result = try {
                repository.beginConversion(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)
            }
            if (generation != operationGeneration) {
                result.conversionTokenOrNull()?.let(::enqueueStaleConversionCleanup)
                return@launch
            }
            when (result) {
                is ConversionBeginResult.AnalysisReady -> showConversionAnalysis(result)
                is ConversionBeginResult.Failure -> {
                    saveConversionRoute(result.token, "input")
                    mutableState.update {
                        it.copy(
                            route = ManagerRoute.ConversionInput,
                            conversionState = ConversionUiState.Failed(
                                result.token,
                                result.code,
                            ),
                            message = conversionFailureMessage(result.code),
                        )
                    }
                }
            }
        }
    }

    private fun updateConversionAnalysis(
        transform: (ConversionUiState.AnalysisReady) -> ConversionUiState.AnalysisReady,
    ) {
        val ready = mutableState.value.conversionState as? ConversionUiState.AnalysisReady ?: return
        mutableState.update { it.copy(conversionState = transform(ready), message = null) }
    }

    private fun buildConversion() {
        val ready = mutableState.value.conversionState as? ConversionUiState.AnalysisReady ?: return
        if (!ready.canBuild || routeOperation?.isActive == true) return
        val ticket = replaceRouteOperation()
        val generation = ticket.generation
        mutableState.update {
            it.copy(
                route = ManagerRoute.ConversionAnalysis(ready.token),
                conversionState = ConversionUiState.Building(ready.token, ready),
                message = null,
            )
        }
        routeOperation = viewModelScope.launch {
            if (!awaitRouteMutation(ticket)) return@launch
            val result = try {
                repository.approveConversion(
                    token = ready.token,
                    approvedAdapterIds = ready.selectedAdapters,
                    partialConfirmed = ready.partialConfirmed,
                    identitySelection = ready.identitySelection,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                ConversionBuildResult.Failure(ConversionFailureCode.PACKAGE)
            }
            if (generation != operationGeneration) {
                enqueueStaleBuildCleanup(result)
                return@launch
            }
            when (result) {
                is ConversionBuildResult.BundleReady ->
                    showConversionResult(result.review, result.generated)
                is ConversionBuildResult.Failure -> {
                    if (result.generated != null) {
                        showConversionResult(
                            review = null,
                            generated = result.generated,
                            failureCode = result.code,
                        )
                    } else {
                        mutableState.update {
                            it.copy(
                                route = ManagerRoute.ConversionAnalysis(ready.token),
                                conversionState = ready,
                                message = conversionFailureMessage(result.code),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun prepareGeneratedInstall() {
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady ?: return
        if (mutableState.value.route != ManagerRoute.ConversionResult(result.token)) return
        if (result.installOwnerActive || result.cleanupPending || result.cleanupInProgress) return
        val review = result.review ?: run {
            mutableState.update {
                it.copy(
                    message = conversionFailureMessage(
                        result.failureCode ?: ConversionFailureCode.BUNDLE_IO,
                    ).copy(recoveryAction = RecoveryAction.NONE),
                )
            }
            return
        }
        ownedReviewToken = review.token
        savedStateHandle[REVIEW_TOKEN_KEY] = review.token
        generatedInstallOwnerToken = result.token
        savedStateHandle[INSTALL_OWNER_CONVERSION_TOKEN_KEY] = result.token
        mutableState.update {
            it.copy(
                conversionState = result.copy(installOwnerActive = true),
                importState = ImportUiState.ReviewReady(
                    review = review,
                    partialConfirmed = review.status == ContractCompatibilityStatus.PARTIAL,
                ),
                message = null,
            )
        }
        installReviewedBundle()
    }

    private fun requestGeneratedSave() {
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady ?: return
        if (result.cleanupPending || result.cleanupInProgress) return
        val launch = newDocumentLaunch(token = result.token)
        pendingConversionSave = launch
        savePendingConversionSave(launch)
        enqueueEffect(
            ManagerEffect.CreateConversionDocument(
                token = result.token,
                suggestedFileName = result.generated.suggestedFileName,
                ownerGeneration = launch.ownerGeneration,
                launchId = launch.launchId,
            ),
        )
    }

    private fun acceptConversionSaveResult(
        action: ManagerAction.SaveGeneratedConversionTo,
    ) {
        val pending = pendingConversionSave
            ?.takeIf {
                it.token == action.token &&
                    it.ownerGeneration == action.ownerGeneration &&
                    it.launchId == action.launchId &&
                    it.ownerGeneration == documentOwnerGeneration
            }
            ?: return
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady ?: return
        if (result.token != action.token) return
        clearPendingConversionSave()
        action.destination?.let { saveGenerated(action.token, it) }
    }

    private fun saveGenerated(token: String, destination: String) {
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady ?: return
        if (result.token != token) return
        if (conversionIoJob?.isActive == true) return
        val completion = CompletableDeferred<GeneratedDocumentSaveResult>()
        conversionIoResult = completion
        val job = viewModelScope.launch {
            val saveResult = try {
                saveGeneratedDocument(
                    destination,
                    result.generated.sha256,
                ) { output ->
                    repository.copyGeneratedConversion(result.token, output)
                }
            } catch (_: CancellationException) {
                GeneratedDocumentSaveResult.Cancelled(cleanupFailed = true)
            } catch (_: Exception) {
                GeneratedDocumentSaveResult.Failure(cleanupFailed = false)
            }
            completion.complete(saveResult)
            if (mutableState.value.conversionState !is ConversionUiState.ResultReady) return@launch
            mutableState.update {
                it.copy(
                    message = when (saveResult) {
                        GeneratedDocumentSaveResult.Success -> UserMessage(
                            UserMessageKind.CONVERSION_SAVED,
                            "변환된 .six 파일을 안전하게 저장했습니다.",
                            RecoveryAction.NONE,
                        )
                        is GeneratedDocumentSaveResult.Failure ->
                            if (saveResult.cleanupFailed) {
                                UserMessage(
                                    UserMessageKind.CONVERSION_SAVE_CLEANUP_FAILED,
                                    "파일 저장과 부분 파일 삭제를 완료하지 못했습니다. 저장 위치에서 파일을 직접 삭제한 뒤 다시 시도해 주세요.",
                                    RecoveryAction.RETRY,
                                )
                            } else {
                                UserMessage(
                                    UserMessageKind.CONVERSION_SAVE_FAILED,
                                    "파일 저장을 완료하지 못했습니다. 저장 위치를 다시 선택해 주세요.",
                                    RecoveryAction.RETRY,
                                )
                            }
                        is GeneratedDocumentSaveResult.Cancelled ->
                            if (saveResult.cleanupFailed) {
                                UserMessage(
                                    UserMessageKind.CONVERSION_SAVE_CLEANUP_FAILED,
                                    "부분 파일 삭제를 완료하지 못했습니다. 저장 위치에서 파일을 직접 삭제한 뒤 다시 시도해 주세요.",
                                    RecoveryAction.RETRY,
                                )
                            } else {
                                UserMessage(
                                    UserMessageKind.CONVERSION_SAVE_FAILED,
                                    "파일 저장이 취소되었습니다.",
                                    RecoveryAction.RETRY,
                                )
                            }
                    },
                )
            }
        }
        conversionIoJob = job
        job.invokeOnCompletion {
            completion.complete(GeneratedDocumentSaveResult.Cancelled(cleanupFailed = true))
        }
    }

    private fun shareGenerated() {
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady ?: return
        if (result.cleanupPending || result.cleanupInProgress) return
        if (generatedShareJob?.isActive == true) return
        generatedShareHandoffSequence += 1
        val handoffId = generatedShareHandoffSequence
        val completion = CompletableDeferred<Boolean>()
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                val snapshot = try {
                    repository.createGeneratedShareSnapshot(result.token)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    null
                }
                if (snapshot == null) {
                    showGeneratedShareFailure(
                        "공유용 파일을 준비하지 못했습니다. 파일로 저장한 뒤 다시 시도해 주세요.",
                    )
                    return@launch
                }
                enqueueEffect(
                    ManagerEffect.ShareGeneratedBundle(
                        token = result.token,
                        file = snapshot,
                        handoffId = handoffId,
                    ),
                )
                if (!completion.await()) {
                    showGeneratedShareFailure(
                        "공유 화면을 열 수 없습니다. 파일로 저장한 뒤 다시 공유해 주세요.",
                    )
                }
            } finally {
                val pending = pendingGeneratedShareHandoff
                if (pending?.token == result.token &&
                    pending.handoffId == handoffId &&
                    pending.completion === completion
                ) {
                    pendingGeneratedShareHandoff = null
                }
            }
        }
        pendingGeneratedShareHandoff = PendingGeneratedShareHandoff(
            token = result.token,
            handoffId = handoffId,
            completion = completion,
            job = job,
        )
        generatedShareJob = job
        job.start()
    }

    private fun acknowledgeGeneratedShareHandoff(
        token: String,
        handoffId: Long,
        launched: Boolean,
    ) {
        val pending = pendingGeneratedShareHandoff ?: return
        if (pending.token != token || pending.handoffId != handoffId) return
        pending.completion.complete(launched)
    }

    private fun showGeneratedShareFailure(text: String) {
        mutableState.update {
            it.copy(
                message = UserMessage(
                    UserMessageKind.CONVERSION_SHARE_FAILED,
                    text,
                    RecoveryAction.NONE,
                ),
            )
        }
    }

    private fun cancelConversion() {
        cleanupConversion(ManagerRoute.Home)
    }

    private fun retryGeneratedCleanup() {
        if (pendingGeneratedCleanup != null) {
            enqueueGeneratedCleanup {
                performPendingGeneratedCleanup()
            }
            return
        }
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady ?: return
        val failure = repository.installState.value as? InstallState.Failed ?: return
        if (failure.code !in CLEANUP_RETRYABLE_FAILURES ||
            generatedInstallOwnerToken != result.token
        ) {
            return
        }
        val supersededInstall = detachInstallJob()
        supersededInstall?.cancel()
        val ticket = replaceRouteOperation()
        val generation = ticket.generation
        routeOperation = null
        enqueueRouteCleanup {
            ticket.previousOperation?.cancelAndJoin()
            supersededInstall?.cancelAndJoin()
            awaitGeneratedShareSnapshot()
            val cleaned = try {
                cancelInstallForRoute(
                    generation = generation,
                    suppressTerminalOnSuccess = true,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showGeneratedCleanupFailure()
                return@enqueueRouteCleanup false
            }
            if (!cleaned) {
                showGeneratedCleanupFailure()
                return@enqueueRouteCleanup false
            }
            clearGeneratedInstallOwner()
            savedStateHandle[INSTALL_SESSION_ID_KEY] = null
            mutableState.update {
                val ready = it.conversionState as? ConversionUiState.ResultReady
                it.copy(
                    conversionState = ready?.copy(installOwnerActive = false)
                        ?: it.conversionState,
                    message = null,
                )
            }
            true
        }
    }

    private fun cleanupConversion(destination: ManagerRoute) {
        val stateBeforeCancel = mutableState.value.conversionState
        if (stateBeforeCancel is ConversionUiState.ResultReady &&
            (stateBeforeCancel.cleanupPending ||
                stateBeforeCancel.cleanupInProgress ||
                pendingGeneratedCleanup?.token == stateBeforeCancel.token)
        ) {
            return
        }
        val token = when (stateBeforeCancel) {
            is ConversionUiState.AnalysisReady -> stateBeforeCancel.token
            is ConversionUiState.Building -> stateBeforeCancel.token
            is ConversionUiState.ResultReady -> stateBeforeCancel.token
            is ConversionUiState.Failed -> stateBeforeCancel.token
            ConversionUiState.Acquiring,
            ConversionUiState.Idle,
            -> savedStateHandle.get<String>(CONVERSION_TOKEN_KEY)
        }
        val reviewToken = (stateBeforeCancel as? ConversionUiState.ResultReady)
            ?.review
            ?.token
        val generatedInstallOwned = generatedInstallOwnerToken == token
        val generatedCleanup = if (
            stateBeforeCancel is ConversionUiState.ResultReady &&
            token != null
        ) {
            beginGeneratedCleanup(
                token = token,
                reviewToken = reviewToken,
                phase = if (generatedInstallOwned) {
                    GeneratedCleanupPhase.INSTALL
                } else if (reviewToken != null) {
                    GeneratedCleanupPhase.REVIEW
                } else {
                    GeneratedCleanupPhase.CONVERSION
                },
                destination = GeneratedCleanupDestination.fromRoute(destination),
                installSessionId = if (generatedInstallOwned) {
                    savedStateHandle[INSTALL_SESSION_ID_KEY]
                } else {
                    null
                },
            )
        } else {
            null
        }
        val ticket = replaceRouteOperation()
        val generation = ticket.generation
        val supersededInstall = if (generatedInstallOwned) detachInstallJob() else null
        supersededInstall?.cancel()
        routeOperation = null
        val cleanupWork: suspend () -> Boolean = cleanup@{
            ticket.previousOperation?.cancelAndJoin()
            supersededInstall?.cancelAndJoin()
            if (generatedInstallOwned) {
                val cleaned = try {
                    generatedCleanup?.let {
                        retryPendingGeneratedInstallCleanup(
                            cleanup = it,
                            generation = generation,
                        )
                    } ?: false
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    showGeneratedCleanupFailure()
                    return@cleanup false
                }
                if (!cleaned) {
                    showGeneratedCleanupFailure()
                    return@cleanup false
                }
                generatedCleanup?.let {
                    advanceGeneratedCleanup(
                        it.copy(
                            phase = if (it.reviewToken != null) {
                                GeneratedCleanupPhase.REVIEW
                            } else {
                                GeneratedCleanupPhase.CONVERSION
                            },
                            installSessionId = null,
                        ),
                    )
                }
            }
            awaitGeneratedShareSnapshot()
            if (!cancelAndAwaitGeneratedSave()) {
                generatedCleanup?.let {
                    clearPendingGeneratedCleanup()
                    updateGeneratedCleanupState(
                        token = it.token,
                        pending = false,
                        inProgress = false,
                    )
                }
                showConversionSaveCleanupFailure()
                return@cleanup true
            }
            if (generatedCleanup != null) {
                return@cleanup performPendingGeneratedCleanup()
            }
            if (token != null &&
                token !in scheduledConversionCleanupTokens &&
                !repository.discardConversion(token)
            ) {
                showGeneratedCleanupFailure()
                return@cleanup false
            }
            if (ownedReviewToken == reviewToken) {
                clearSavedReviewAndSession()
            }
            if (reviewToken != null &&
                token !in scheduledConversionCleanupTokens &&
                !repository.discardReview(reviewToken)
            ) {
                showGeneratedCleanupFailure()
                return@cleanup false
            }
            clearGeneratedInstallOwner()
            clearGeneratedResultOwner()
            clearSavedConversion()
            mutableState.update {
                it.copy(
                    route = destination,
                    conversionState = ConversionUiState.Idle,
                    conversionUrl = "",
                    importState = if (ownedReviewToken == null) {
                        ImportUiState.Idle
                    } else {
                        it.importState
                    },
                    message = null,
                )
            }
            true
        }
        if (generatedCleanup != null) {
            enqueueGeneratedCleanup(cleanupWork)
        } else {
            enqueueRouteCleanup(cleanupWork)
        }
    }

    private fun finishConversion() {
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady
        if (result?.installOwnerActive == true || generatedInstallOwnerToken == result?.token) return
        cleanupConversion(ManagerRoute.Home)
    }

    private fun showConversionAnalysis(result: ConversionBeginResult.AnalysisReady) {
        saveConversionRoute(result.token, "analysis")
        mutableState.update {
            it.copy(
                route = ManagerRoute.ConversionAnalysis(result.token),
                conversionState = result.toUiState(),
                conversionUrl = "",
                message = if (result.profileChanged) {
                    conversionFailureMessage(ConversionFailureCode.PROFILE_CHANGED)
                } else {
                    null
                },
            )
        }
    }

    private fun showConversionResult(
        review: ReviewBundle?,
        generated: dev.sixbridge.manager.conversion.GeneratedBundle,
        failureCode: ConversionFailureCode? = null,
        installOwnerActive: Boolean = generatedInstallOwnerToken == generated.token,
        persistOwners: Boolean = true,
    ) {
        if (persistOwners) {
            saveConversionRoute(generated.token, "result")
            generatedResultOwnerToken = generated.token
            generatedResultReviewToken = review?.token
            savedStateHandle[RESULT_OWNER_CONVERSION_TOKEN_KEY] = generated.token
            savedStateHandle[RESULT_OWNER_REVIEW_TOKEN_KEY] = review?.token
        }
        val cleanupPending = pendingGeneratedCleanup?.token == generated.token
        mutableState.update {
            it.copy(
                route = ManagerRoute.ConversionResult(generated.token),
                conversionState = ConversionUiState.ResultReady(
                    token = generated.token,
                    review = review,
                    generated = generated,
                    failureCode = failureCode,
                    installOwnerActive = installOwnerActive,
                    cleanupPending = cleanupPending,
                    cleanupInProgress = cleanupPending &&
                        generatedCleanupJob?.isActive == true,
                ),
                conversionUrl = "",
                message = if (review == null) {
                    conversionFailureMessage(
                        failureCode ?: ConversionFailureCode.BUNDLE_IO,
                    ).copy(recoveryAction = RecoveryAction.NONE)
                } else {
                    null
                },
            )
        }
    }

    private fun restoreAndInitialize() {
        val generation = operationGeneration
        viewModelScope.launch {
            if (generatedCleanupRestoreInvalid) {
                initialized = true
                showGeneratedCleanupRestoreBlocked()
                return@launch
            }
            if (generatedTerminalHandoffRestoreInvalid) {
                initialized = true
                showStartupRecoveryBlocked()
                return@launch
            }
            savedStateHandle["conversion_url"] = null
            val committedDestination = pendingGeneratedCleanup?.takeIf {
                it.phase == GeneratedCleanupPhase.COMMIT_DESTINATION
            }
            val savedToken = savedStateHandle.get<String>(REVIEW_TOKEN_KEY)
            val savedSession = savedStateHandle.get<Int>(INSTALL_SESSION_ID_KEY)
            val restored = initializeRepositoryWithRetry(savedToken, savedSession)
            if (restored == null) {
                initialized = true
                if (generatedCleanupRestoreInvalid) {
                    showGeneratedCleanupRestoreBlocked()
                } else {
                    showStartupRecoveryBlocked()
                }
                return@launch
            }
            initialized = true
            if (generatedCleanupRestoreInvalid) {
                showGeneratedCleanupRestoreBlocked()
                return@launch
            }
            if (generatedTerminalHandoffRestoreInvalid) {
                showStartupRecoveryBlocked()
                return@launch
            }
            if (restored.recoveryBlocked) {
                showStartupRecoveryBlocked()
                return@launch
            }
            if (committedDestination != null) {
                if (completeGeneratedCleanup(committedDestination)) {
                    markStartupRecoveryReady()
                } else {
                    showStartupRecoveryBlocked()
                }
                return@launch
            }
            val intermediateCleanup = pendingGeneratedCleanup?.takeIf {
                it.phase == GeneratedCleanupPhase.REVIEW ||
                    it.phase == GeneratedCleanupPhase.CONVERSION
            }
            if (generation != operationGeneration) {
                deferredInitialTerminals = null
                if (intermediateCleanup == null) {
                    restored.review?.token?.let(::queuePendingReviewDiscard)
                }
                if (reconcileRestoredNormalHandoffAfterStartupMismatch(restored)) {
                    markStartupRecoveryReady()
                }
                return@launch
            }
            if (savedStateHandle.get<String>(REVIEW_TOKEN_KEY) != savedToken) {
                deferredInitialTerminals = null
                if (reconcileRestoredNormalHandoffAfterStartupMismatch(restored)) {
                    markStartupRecoveryReady()
                }
                return@launch
            }
            if (intermediateCleanup != null &&
                pendingGeneratedTerminalHandoff?.state !is InstallState.Installed
            ) {
                if (!reconcilePersistedGeneratedTerminal(
                        cleanup = intermediateCleanup,
                        savedReviewToken = savedToken,
                        savedSessionId = savedSession,
                    )
                ) {
                    intermediateGeneratedCleanupRestorePending = false
                    showStartupRecoveryBlocked()
                    return@launch
                }
                restoreConversion(
                    generation = generation,
                    retainedReview = restored.review?.takeIf {
                        intermediateCleanup.phase == GeneratedCleanupPhase.REVIEW &&
                            it.token == intermediateCleanup.reviewToken
                    },
                    importReview =
                        intermediateCleanup.phase == GeneratedCleanupPhase.REVIEW,
                    persistOwners = false,
                )
                if (generation != operationGeneration ||
                    savedStateHandle.get<String>(REVIEW_TOKEN_KEY) != savedToken ||
                    pendingGeneratedCleanup != intermediateCleanup
                ) {
                    if (reconcileRestoredNormalHandoffAfterStartupMismatch(restored)) {
                        markStartupRecoveryReady()
                    }
                    return@launch
                }
                intermediateGeneratedCleanupRestorePending = false
                val replayDeletedConversion =
                    intermediateCleanup.phase == GeneratedCleanupPhase.CONVERSION &&
                        mutableState.value.conversionState !is ConversionUiState.ResultReady
                if ((intermediateCleanup.autoReplayPending ||
                        replayDeletedConversion) &&
                    intermediateCleanup.matchesSavedOwners()
                ) {
                    enqueueGeneratedCleanup {
                        performPendingGeneratedCleanup()
                    }
                } else {
                    showGeneratedCleanupFailure()
                }
                markStartupRecoveryReady()
                return@launch
            }
            val resultOwner = generatedResultOwnerToken
            val retainedResultReview = restored.review?.takeIf {
                it.token == generatedResultReviewToken
            }
            if (restored.review != null) {
                if (generatedInstallOwnerToken != null || resultOwner != null) {
                    ownedReviewToken = restored.review.token
                    savedStateHandle[REVIEW_TOKEN_KEY] = restored.review.token
                    mutableState.update {
                        it.copy(
                            importState = ImportUiState.ReviewReady(
                                review = restored.review,
                                partialConfirmed =
                                    restored.review.status ==
                                        ContractCompatibilityStatus.PARTIAL,
                            ),
                        )
                    }
                } else {
                    showReview(restored.review)
                }
            } else {
                savedStateHandle[REVIEW_TOKEN_KEY] = null
            }
            if (restored.installSessionId != null) {
                savedStateHandle[INSTALL_SESSION_ID_KEY] = restored.installSessionId
            } else if (
                pendingGeneratedCleanup?.phase != GeneratedCleanupPhase.INSTALL ||
                savedSession == null
            ) {
                savedStateHandle[INSTALL_SESSION_ID_KEY] = null
            }
            restored.normalHandoff?.let { handoff ->
                pendingNormalHandoff = handoff
                if (!resumeNormalHandoffWithCurrentReceipt(
                        handoff,
                        restored.terminal,
                    )
                ) {
                    showStartupRecoveryBlocked()
                    return@launch
                }
                markStartupRecoveryReady()
                return@launch
            }
            restored.terminal?.let { terminal ->
                if (generatedInstallOwnerToken == null &&
                    resultOwner == null &&
                    restored.review?.token == terminal.importToken &&
                    restored.review.packageName == terminal.packageName
                ) {
                    savedStateHandle[INSTALL_SESSION_ID_KEY] = terminal.sessionId
                    if (!applyInstallTerminal(
                            installState = terminal.state,
                            expectedReviewToken = terminal.importToken,
                            expectedSessionId = terminal.sessionId,
                        )
                    ) {
                        showStartupRecoveryBlocked()
                        return@launch
                    }
                    markStartupRecoveryReady()
                    return@launch
                }
            }
            if (resultOwner != null &&
                restored.review != null &&
                retainedResultReview == null
            ) {
                clearGeneratedInstallOwner()
                clearGeneratedResultOwner()
                clearSavedConversion()
                mutableState.update {
                    it.copy(conversionState = ConversionUiState.Idle)
                }
                showReview(restored.review)
                markStartupRecoveryReady()
                return@launch
            }
            if (restored.review == null ||
                generatedInstallOwnerToken != null ||
                resultOwner != null
            ) {
                restoreConversion(
                    generation = generation,
                    retainedReview = retainedResultReview.takeIf { resultOwner != null },
                    importReview = resultOwner == null &&
                        pendingGeneratedCleanup?.phase !=
                        GeneratedCleanupPhase.CONVERSION,
                )
            }
            if (resultOwner != null) {
                val ready =
                    mutableState.value.conversionState as? ConversionUiState.ResultReady
                val exactDeletedConversion =
                    pendingGeneratedCleanup?.let { cleanup ->
                        cleanup.phase == GeneratedCleanupPhase.CONVERSION &&
                            cleanup.token == resultOwner &&
                            cleanup.matchesSavedOwners()
                    } == true
                if (ready?.token != resultOwner && !exactDeletedConversion) {
                    clearGeneratedResultOwner()
                    restored.review?.let(::showReview)
                }
            }
            val generatedOwner = generatedInstallOwnerToken
            if (generatedOwner != null) {
                val ready = mutableState.value.conversionState as? ConversionUiState.ResultReady
                if (ready?.token != generatedOwner || ready.review == null) {
                    clearGeneratedInstallOwner()
                    restored.review?.let(::showReview)
                } else if (!reconcileDeferredInitialTerminals()) {
                    showStartupRecoveryBlocked()
                    return@launch
                }
            }
            if (generatedInstallOwnerToken == null &&
                resultOwner == null &&
                restored.review != null
            ) {
                if (!reconcileDeferredInitialTerminals()) {
                    showStartupRecoveryBlocked()
                    return@launch
                }
            }
            pendingGeneratedTerminalHandoff?.let { handoff ->
                if (!resumeGeneratedTerminalHandoff(handoff, restored.terminal)) {
                    showStartupRecoveryBlocked()
                    return@launch
                }
                markStartupRecoveryReady()
                return@launch
            }
            val pendingCleanup = pendingGeneratedCleanup
            val ready = mutableState.value.conversionState as? ConversionUiState.ResultReady
            if (generatedCleanupRestoreInvalid && ready != null) {
                showGeneratedCleanupFailure()
            }
            if (pendingCleanup != null &&
                ready?.token == pendingCleanup.token &&
                pendingCleanup.phase in setOf(
                    GeneratedCleanupPhase.INSTALL,
                    GeneratedCleanupPhase.REVIEW,
                )
            ) {
                showGeneratedCleanupFailure()
            }
            if (pendingCleanup?.phase == GeneratedCleanupPhase.CONVERSION &&
                ready == null &&
                pendingCleanup.matchesSavedOwners()
            ) {
                enqueueGeneratedCleanup {
                    performPendingGeneratedCleanup()
                }
            }
            markStartupRecoveryReady()
        }
    }

    private suspend fun initializeRepositoryWithRetry(
        savedToken: String?,
        savedSession: Int?,
    ): ManagerRestoreResult? {
        repeat(STARTUP_INITIALIZE_MAX_ATTEMPTS) { attempt ->
            try {
                return repository.initialize(savedToken, savedSession)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (generatedCleanupRestoreInvalid ||
                    attempt == STARTUP_INITIALIZE_MAX_ATTEMPTS - 1
                ) {
                    return null
                }
                delay(STARTUP_INITIALIZE_RETRY_DELAY_MILLIS)
            }
        }
        return null
    }

    private suspend fun reconcileDeferredInitialTerminals(): Boolean {
        val terminals = deferredInitialTerminals?.candidates() ?: return true
        deferredInitialTerminals = null
        try {
            var applied = false
            for (terminal in terminals) {
                if (applyObservedInstallTerminal(terminal)) {
                    applied = true
                    break
                }
            }
            if (!applied && deferredInitialTerminals != null) {
                deferredInitialTerminals = null
                applied = applyCurrentOwnedInstallTerminal()
            }
            return applied
        } finally {
            deferredInitialTerminals = null
        }
    }

    private fun deferInitialTerminal(installState: InstallState) {
        val deferred = deferredInitialTerminals
        deferredInitialTerminals = if (deferred == null) {
            DeferredInitialTerminals(initial = installState)
        } else {
            deferred.copy(latest = installState)
        }
    }

    private suspend fun reconcileRestoredNormalHandoffAfterStartupMismatch(
        restored: ManagerRestoreResult,
    ): Boolean {
        val handoff = restored.normalHandoff ?: return true
        pendingNormalHandoff = handoff
        if (resumeNormalHandoffWithCurrentReceipt(handoff, restored.terminal)) return true
        showStartupRecoveryBlocked()
        return false
    }

    private suspend fun reconcilePersistedGeneratedTerminal(
        cleanup: PendingGeneratedCleanup,
        savedReviewToken: String?,
        savedSessionId: Int?,
    ): Boolean {
        val installOwner = generatedInstallOwnerToken ?: return true
        val reviewToken = cleanup.reviewToken ?: return false
        if (cleanup.phase !in setOf(
                GeneratedCleanupPhase.REVIEW,
                GeneratedCleanupPhase.CONVERSION,
            ) ||
            installOwner != cleanup.token ||
            savedStateHandle.get<String>(INSTALL_OWNER_CONVERSION_TOKEN_KEY) != cleanup.token ||
            savedReviewToken != reviewToken ||
            savedSessionId == null
        ) {
            return false
        }
        val acknowledged = when (
            val owner = repository.terminalForOwner(reviewToken, savedSessionId)
        ) {
            is InstallTerminalOwnerResult.Owned ->
                completeTerminalReceipt(
                    expectedReviewToken = reviewToken,
                    expectedSessionId = savedSessionId,
                    expectedState = owner.state,
                )
            is InstallTerminalOwnerResult.OwnerSessionAssigned -> false
            InstallTerminalOwnerResult.OwnerMismatch ->
                repository.installState.value == InstallState.Idle
        }
        if (!acknowledged) return false
        clearGeneratedInstallOwner()
        savedStateHandle[INSTALL_SESSION_ID_KEY] = null
        return true
    }

    private suspend fun restoreConversion(
        generation: Long,
        retainedReview: ReviewBundle? = null,
        importReview: Boolean =
            pendingGeneratedCleanup?.phase != GeneratedCleanupPhase.CONVERSION,
        persistOwners: Boolean = true,
    ) {
        val restored = try {
            repository.restoreConversion(importReview = importReview)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (generation != operationGeneration) return
        when (restored) {
            is RestoredConversion.Analysis -> {
                if (persistOwners) showConversionAnalysis(restored.value)
            }
            is RestoredConversion.BundleReady ->
                showConversionResult(
                    review = if (importReview) retainedReview ?: restored.review else retainedReview,
                    generated = restored.generated,
                    persistOwners = persistOwners,
                )
            is RestoredConversion.GeneratedPending ->
                showConversionResult(
                    review = retainedReview,
                    generated = restored.generated,
                    persistOwners = persistOwners,
                )
            null -> {
                if (pendingGeneratedCleanup?.matchesSavedOwners() != true) {
                    clearSavedConversion()
                }
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
                if (generatedCleanupRestoreInvalid) {
                    showGeneratedCleanupRestoreBlocked()
                    return@collect
                }
                if (mutableState.value.startupRecoveryState ==
                    StartupRecoveryUiState.RECOVERY_BLOCKED
                ) {
                    return@collect
                }
                if (pendingGeneratedCleanup?.phase in setOf(
                        GeneratedCleanupPhase.REVIEW,
                        GeneratedCleanupPhase.CONVERSION,
                    )
                ) {
                    return@collect
                }
                if (mutableState.value.startupRecoveryState ==
                    StartupRecoveryUiState.INITIALIZING
                ) {
                    if (installState.isTerminalState() &&
                        (savedStateHandle.get<String>(REVIEW_TOKEN_KEY) != null ||
                            generatedInstallOwnerToken != null)
                    ) {
                        deferInitialTerminal(installState)
                    }
                    return@collect
                }
                if (firstEmission &&
                    installState == installStateAtInitialization &&
                    installState.isTerminalState() &&
                    savedStateHandle.get<String>(REVIEW_TOKEN_KEY) != null
                ) {
                    if (!initialized) {
                        deferInitialTerminal(installState)
                        return@collect
                    }
                    if (ownedReviewToken != null && generatedInstallOwnerToken == null) {
                        return@collect
                    }
                }
                if (installState.isTerminalState()) {
                    systemInstallReturnToken = null
                    replacementCancelGeneration?.let { generation ->
                        deferredReplacementTerminal = generation to installState
                        return@collect
                    }
                }
                val suppressedTerminal = installState == suppressedInstallTerminal
                if (suppressedTerminal) suppressedInstallTerminal = null
                when (installState) {
                    InstallState.PreparingSystemInstaller,
                    is InstallState.AwaitingSystemInstaller,
                    is InstallState.AwaitingManualInstall,
                    -> Unit
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
                        applyObservedInstallTerminal(installState)
                    }
                    InstallState.Cancelled -> {
                        if (!suppressedTerminal) applyObservedInstallTerminal(installState)
                    }
                    is InstallState.Failed -> {
                        if (suppressedTerminal) return@collect
                        applyObservedInstallTerminal(installState)
                    }
                    InstallState.Idle -> Unit
                }
            }
        }
    }

    private fun observeRepositoryLaunches() {
        viewModelScope.launch {
            repository.launches.collect { launch ->
                if (generatedCleanupRestoreInvalid) return@collect
                if (mutableState.value.startupRecoveryState !=
                    StartupRecoveryUiState.READY
                ) {
                    return@collect
                }
                enqueueEffect(currentLaunchEffect(launch))
            }
        }
    }

    private fun confirmPartial() {
        val ready = mutableState.value.importState as? ImportUiState.ReviewReady ?: return
        if (ready.review.status != ContractCompatibilityStatus.PARTIAL) return
        mutableState.update { it.copy(importState = ready.copy(partialConfirmed = true), message = null) }
    }

    private fun prepareReviewedInstall() {
        val ready = mutableState.value.importState as? ImportUiState.ReviewReady ?: return
        if (mutableState.value.route != ManagerRoute.Review(ready.review.token)) return
        if (generatedInstallOwnerToken != null) return
        installReviewedBundle()
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
            val generatedOwner = generatedInstallOwnerToken
            val stillOwnsAttempt = ownedReviewToken == reviewToken &&
                savedStateHandle.get<String>(REVIEW_TOKEN_KEY) == reviewToken &&
                if (generatedOwner == null) {
                    mutableState.value.route == ManagerRoute.Review(reviewToken)
                } else {
                    val result =
                        mutableState.value.conversionState as? ConversionUiState.ResultReady
                    result?.token == generatedOwner && result.review?.token == reviewToken
                }
            val fallback = repository.systemInstallApkFallback(reviewToken)
            if (fallback != null && stillOwnsAttempt) {
                val effect = ManagerEffect.CreateSystemInstallApkDocument(
                    importToken = fallback.importToken,
                    source = fallback.source,
                    expectedSha256 = fallback.expectedSha256,
                    suggestedFileName = suggestedWrapperApkName(fallback.extensionName),
                )
                setPendingSystemInstallApkDocument(effect)
                enqueueEffect(effect)
                return@launch
            }
            if (attemptResult.isTerminalState() && stillOwnsAttempt) {
                val applied = applyObservedInstallTerminal(attemptResult)
                if (!applied &&
                    attemptResult is InstallState.Failed &&
                    !hasOwnedTerminal(reviewToken, attemptResult)
                ) {
                    applyAttemptOwnedFailure(reviewToken, attemptResult)
                }
            }
        }
    }

    private fun acceptSystemInstallApkCreated(
        action: ManagerAction.SystemInstallApkCreated,
    ) {
        val pending = pendingSystemInstallApkDocument
            ?.takeIf {
                it.importToken == action.importToken &&
                    isCurrentReviewOwner(action.importToken)
            }
            ?: return
        if (action.uri == null) {
            pendingRecovery = PendingRecovery.SystemInstallApk(pending)
            mutableState.update {
                it.copy(
                    message = UserMessage(
                        UserMessageKind.LAUNCH_FAILED,
                        "APK를 저장하지 못했습니다.",
                        RecoveryAction.RETRY,
                    ),
                )
            }
            return
        }
        if (!repository.markSystemInstallApkExported(action.importToken)) {
            pendingRecovery = PendingRecovery.SystemInstallApk(pending)
            mutableState.update {
                it.copy(
                    message = UserMessage(
                        UserMessageKind.INSTALL_FAILED,
                        "APK 저장 상태를 확인하지 못했습니다.",
                        RecoveryAction.RETRY,
                    ),
                )
            }
            return
        }
        clearPendingSystemInstallApkDocument()
        pendingRecovery = null
        showManualSystemInstallMessage()
    }

    private fun confirmSystemInstallStatus() {
        val manual = mutableState.value.installState as? InstallState.AwaitingManualInstall
            ?: return
        if (!isCurrentReviewOwner(manual.importToken)) return
        viewModelScope.launch {
            repository.reconcileManualSystemInstall(manual.importToken)
        }
    }

    private fun showManualSystemInstallMessage() {
        mutableState.update {
            it.copy(
                message = UserMessage(
                    UserMessageKind.INSTALL_FAILED,
                    "시스템 설치 프로그램을 직접 열 수 없습니다.\n" +
                        "저장한 APK를 내 파일에서 열어 설치한 뒤 ‘설치 상태 확인’을 눌러 주세요.",
                    RecoveryAction.CHECK_INSTALL_STATUS,
                ),
            )
        }
    }

    private fun continueInstallAfterNotificationPermission(
        action: ManagerAction.ContinueInstallAfterNotificationPermission,
    ) {
        if (!isInstallPermissionOwnerCurrent(
                reviewToken = action.reviewToken,
                generatedToken = action.generatedToken,
            )
        ) {
            return
        }
        beginNewUserAction()
        installReviewedBundle()
    }

    private fun cancelReview() {
        moveOwnedReviewToPendingDiscard()
        val supersededInstall = detachInstallJob()
        supersededInstall?.cancel()
        val ticket = replaceRouteOperation()
        val generation = ticket.generation
        routeOperation = null
        enqueueRouteCleanup {
            ticket.previousOperation?.cancelAndJoin()
            supersededInstall?.cancelAndJoin()
            val cleaned = if (pendingNormalHandoff != null) {
                completePendingNormalHandoffForNewWork()
            } else {
                try {
                    cancelInstallForRoute(generation, suppressTerminalOnSuccess = true)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    return@enqueueRouteCleanup false
                }
            }
            if (cleaned &&
                generation == operationGeneration
            ) {
                if (!discardPendingReviews()) return@enqueueRouteCleanup false
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
            cleaned
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
        val systemToken = systemInstallReturnToken
        val systemState = mutableState.value.installState
        if (systemToken != null &&
            systemState == InstallState.AwaitingSystemInstaller(systemToken) &&
            isCurrentReviewOwner(systemToken)
        ) {
            systemInstallReturnToken = null
            viewModelScope.launch {
                repository.reconcileSystemInstallAfterReturn()
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
        mutableState.update {
            it.copy(
                route = ManagerRoute.Report(packageName),
                message = if (
                    it.startupRecoveryState == StartupRecoveryUiState.RECOVERY_BLOCKED
                ) {
                    it.message
                } else {
                    null
                },
            )
        }
    }

    private fun openUnknownSourcesSettings() {
        if (mutableState.value.installState != InstallState.UnknownSourcesPermissionRequired) return
        enqueueEffect(currentLaunchEffect(repository.unknownSourcesSettings()))
    }

    private fun recordLaunchFailure(effect: ManagerEffect.LaunchIntent) {
        if (!isEffectCurrent(effect)) return
        val systemInstallToken =
            effect.installImportToken.takeIf {
                effect.destination == LaunchDestination.SYSTEM_INSTALL_CONFIRMATION
            }
        if (systemInstallToken != null) {
            if (!repository.markSystemInstallLaunchFailed(systemInstallToken)) return
            systemInstallReturnToken = null
        }
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
        val failureText = when {
            uninstallPackage != null -> "시스템 제거 화면을 열 수 없습니다."
            systemInstallToken != null -> "시스템 설치 화면을 열 수 없습니다."
            else -> "시스템 화면을 열 수 없습니다."
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
        if (effect.destination == LaunchDestination.SYSTEM_INSTALL_CONFIRMATION) {
            val importToken = effect.installImportToken ?: return
            if (repository.markSystemInstallLaunchSucceeded(importToken)) {
                systemInstallReturnToken = importToken
            }
            return
        }
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
                    installImportToken = recovery.effect.installImportToken,
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
            is PendingRecovery.SystemInstallApk -> {
                setPendingSystemInstallApkDocument(recovery.effect)
                enqueueEffect(recovery.effect)
            }
        }
    }

    private fun beginNewUserAction(preserveRetryMessage: Boolean = false) {
        mutableState.value.destinationCommitToken
            ?.let(::acknowledgeGeneratedCleanupDestination)
        invalidatePendingDocumentLaunches()
        invalidateUninstallAttempt()
        launchGeneration += 1
        pendingRecovery = null
        if (!preserveRetryMessage &&
            mutableState.value.message?.recoveryAction in setOf(
                RecoveryAction.RETRY,
                RecoveryAction.CHECK_INSTALL_STATUS,
            )
        ) {
            mutableState.update { it.copy(message = null) }
        }
    }

    private fun ManagerAction.mutatesGeneratedCleanupOwners(): Boolean = when (this) {
        is ManagerAction.ImportSelected,
        ManagerAction.OpenConversionInput,
        ManagerAction.FinishConversion,
        ManagerAction.CancelConversion,
        ManagerAction.CancelReview,
        -> true
        else -> false
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

    private fun saveConversionRoute(token: String?, route: String) {
        savedStateHandle[CONVERSION_TOKEN_KEY] = token
        savedStateHandle[CONVERSION_ROUTE_KEY] = if (token == null) null else route
    }

    private fun clearSavedConversion() {
        savedStateHandle[CONVERSION_TOKEN_KEY] = null
        savedStateHandle[CONVERSION_ROUTE_KEY] = null
        savedStateHandle["conversion_url"] = null
    }

    private fun replaceRouteOperation(): RouteOperationTicket {
        val previous = routeOperation
        previous?.cancel()
        operationGeneration += 1
        return RouteOperationTicket(operationGeneration, previous)
    }

    private suspend fun awaitRouteMutation(ticket: RouteOperationTicket): Boolean {
        ticket.previousOperation?.cancelAndJoin()
        while (true) {
            val cleanup = routeCleanup ?: break
            cleanup.join()
            if (cleanup === routeCleanup) break
        }
        if (pendingNormalHandoff != null) {
            if (!completePendingNormalHandoffForNewWork()) return false
            routeCleanupFailed = false
        }
        if (routeCleanupFailed) {
            showGeneratedCleanupFailure()
            return false
        }
        return ticket.generation == operationGeneration
    }

    private fun enqueueRouteCleanup(
        cleanup: suspend () -> Boolean,
    ): Job {
        val previous = routeCleanup
        val job = viewModelScope.launch {
            previous?.join()
            val succeeded = withContext(NonCancellable) {
                runCatching { cleanup() }.getOrDefault(false)
            }
            routeCleanupFailed = !succeeded
        }
        routeCleanup = job
        return job
    }

    private fun enqueueStaleConversionCleanup(token: String) {
        enqueueStaleConversionCleanup(token, reviewToken = null)
    }

    private fun enqueueStaleConversionCleanup(
        token: String,
        reviewToken: String?,
    ) {
        if (!scheduledConversionCleanupTokens.add(token)) return
        enqueueRouteCleanup {
            try {
                val reviewCleaned = reviewToken?.let(repository::discardReview) ?: true
                val conversionCleaned = repository.discardConversion(token)
                reviewCleaned && conversionCleaned
            } finally {
                scheduledConversionCleanupTokens.remove(token)
            }
        }
    }

    private fun enqueueStaleBuildCleanup(result: ConversionBuildResult) {
        when (result) {
            is ConversionBuildResult.BundleReady -> enqueueStaleConversionCleanup(
                token = result.generated.token,
                reviewToken = result.review.token,
            )
            is ConversionBuildResult.Failure ->
                result.generated?.token?.let(::enqueueStaleConversionCleanup)
        }
    }

    private fun newDocumentLaunch(token: String? = null): PendingDocumentLaunch {
        documentOwnerGeneration += 1
        documentLaunchSequence += 1
        savedStateHandle[DOCUMENT_OWNER_GENERATION_KEY] = documentOwnerGeneration
        savedStateHandle[DOCUMENT_LAUNCH_SEQUENCE_KEY] = documentLaunchSequence
        return PendingDocumentLaunch(
            token = token,
            ownerGeneration = documentOwnerGeneration,
            launchId = documentLaunchSequence,
        )
    }

    private fun invalidatePendingDocumentLaunches() {
        documentOwnerGeneration += 1
        savedStateHandle[DOCUMENT_OWNER_GENERATION_KEY] = documentOwnerGeneration
        clearPendingSixPicker()
        clearPendingConversionPicker()
        clearPendingConversionSave()
        clearPendingSystemInstallApkDocument()
    }

    private fun savePendingSixPicker(launch: PendingDocumentLaunch) {
        savedStateHandle[PENDING_SIX_PICKER_OWNER_GENERATION_KEY] = launch.ownerGeneration
        savedStateHandle[PENDING_SIX_PICKER_LAUNCH_ID_KEY] = launch.launchId
    }

    private fun savePendingConversionPicker(launch: PendingDocumentLaunch) {
        savedStateHandle[PENDING_PICKER_OWNER_GENERATION_KEY] = launch.ownerGeneration
        savedStateHandle[PENDING_PICKER_LAUNCH_ID_KEY] = launch.launchId
    }

    private fun savePendingConversionSave(launch: PendingDocumentLaunch) {
        savedStateHandle[PENDING_SAVE_TOKEN_KEY] = launch.token
        savedStateHandle[PENDING_SAVE_OWNER_GENERATION_KEY] = launch.ownerGeneration
        savedStateHandle[PENDING_SAVE_LAUNCH_ID_KEY] = launch.launchId
    }

    private fun clearPendingConversionPicker() {
        pendingConversionPicker = null
        savedStateHandle[PENDING_PICKER_OWNER_GENERATION_KEY] = null
        savedStateHandle[PENDING_PICKER_LAUNCH_ID_KEY] = null
    }

    private fun clearPendingSixPicker() {
        pendingSixPicker = null
        savedStateHandle[PENDING_SIX_PICKER_OWNER_GENERATION_KEY] = null
        savedStateHandle[PENDING_SIX_PICKER_LAUNCH_ID_KEY] = null
    }

    private fun clearPendingConversionSave() {
        pendingConversionSave = null
        savedStateHandle[PENDING_SAVE_TOKEN_KEY] = null
        savedStateHandle[PENDING_SAVE_OWNER_GENERATION_KEY] = null
        savedStateHandle[PENDING_SAVE_LAUNCH_ID_KEY] = null
    }

    private fun restorePendingConversionPicker(): PendingDocumentLaunch? {
        val owner = savedStateHandle.get<Long>(PENDING_PICKER_OWNER_GENERATION_KEY)
            ?: return null
        val launchId = savedStateHandle.get<Long>(PENDING_PICKER_LAUNCH_ID_KEY)
            ?: return null
        if (owner != documentOwnerGeneration) return null
        return PendingDocumentLaunch(
            token = null,
            ownerGeneration = owner,
            launchId = launchId,
        )
    }

    private fun restorePendingSixPicker(): PendingDocumentLaunch? {
        val owner = savedStateHandle.get<Long>(PENDING_SIX_PICKER_OWNER_GENERATION_KEY)
            ?: return null
        val launchId = savedStateHandle.get<Long>(PENDING_SIX_PICKER_LAUNCH_ID_KEY)
            ?: return null
        if (owner != documentOwnerGeneration) return null
        return PendingDocumentLaunch(
            token = null,
            ownerGeneration = owner,
            launchId = launchId,
        )
    }

    private fun restorePendingConversionSave(): PendingDocumentLaunch? {
        val token = savedStateHandle.get<String>(PENDING_SAVE_TOKEN_KEY)
            ?: return null
        val owner = savedStateHandle.get<Long>(PENDING_SAVE_OWNER_GENERATION_KEY)
            ?: return null
        val launchId = savedStateHandle.get<Long>(PENDING_SAVE_LAUNCH_ID_KEY)
            ?: return null
        if (owner != documentOwnerGeneration) return null
        return PendingDocumentLaunch(
            token = token,
            ownerGeneration = owner,
            launchId = launchId,
        )
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
        val expectedReviewToken =
            ownedReviewToken ?: savedStateHandle.get<String>(REVIEW_TOKEN_KEY)
        val previous = repository.installState.value
        val replacingActiveInstall =
            previous == InstallState.UnknownSourcesPermissionRequired ||
            previous is InstallState.WritingSession ||
            previous is InstallState.AwaitingUserApproval ||
            previous is InstallState.Failed &&
            previous.code in CLEANUP_RETRYABLE_FAILURES
        if (expectedReviewToken == null) {
            return !replacingActiveInstall
        }
        val resolution = reconcileInstallCancellation(
            generation = generation,
            suppressTerminalOnSuccess = suppressTerminalOnSuccess,
            expectedReviewToken = expectedReviewToken,
            expectedSessionId = savedStateHandle[INSTALL_SESSION_ID_KEY],
            onOwnerSessionAssigned = { sessionId ->
                if (sessionId < 0 ||
                    ownedReviewToken != expectedReviewToken ||
                    savedStateHandle.get<String>(REVIEW_TOKEN_KEY) != expectedReviewToken
                ) {
                    false
                } else {
                    savedStateHandle[INSTALL_SESSION_ID_KEY] = sessionId
                    true
                }
            },
        )
        return when (resolution) {
            InstallCancellationResolution.Cleaned -> true
            is InstallCancellationResolution.OwnedTerminal -> when (resolution.state) {
                InstallState.Cancelled -> {
                    val generatedOwner = generatedInstallOwnerToken
                    val applied = applyInstallTerminal(
                        installState = resolution.state,
                        expectedReviewToken = expectedReviewToken,
                        expectedSessionId = resolution.sessionId,
                    )
                    if (applied && generatedOwner == null) {
                        pendingReviewDiscards.remove(expectedReviewToken)
                    }
                    applied
                }
                else -> {
                    applyInstallTerminal(
                        installState = resolution.state,
                        expectedReviewToken = expectedReviewToken,
                        expectedSessionId = resolution.sessionId,
                    )
                    false
                }
            }
            InstallCancellationResolution.Failed -> false
        }
    }

    private suspend fun reconcileInstallCancellation(
        generation: Long,
        suppressTerminalOnSuccess: Boolean,
        expectedReviewToken: String,
        expectedSessionId: Int?,
        onOwnerSessionAssigned: (Int) -> Boolean,
    ): InstallCancellationResolution {
        val previous = repository.installState.value
        val replacingActiveInstall =
            previous == InstallState.UnknownSourcesPermissionRequired ||
                previous is InstallState.WritingSession ||
                previous is InstallState.AwaitingUserApproval ||
                previous is InstallState.Failed &&
                previous.code in CLEANUP_RETRYABLE_FAILURES
        if (replacingActiveInstall) replacementCancelGeneration = generation
        var resolvedSessionId = expectedSessionId
        val result = try {
            var current = repository.cancelInstall(expectedReviewToken, resolvedSessionId)
            if (current is InstallCleanupResult.OwnerSessionAssigned) {
                if (!onOwnerSessionAssigned(current.sessionId)) {
                    return finishInstallCancellationReconciliation(
                        generation,
                        replacingActiveInstall,
                        suppressTerminalOnSuccess,
                        expectedReviewToken,
                        resolvedSessionId,
                        InstallCleanupResult.OwnerMismatch,
                    )
                }
                resolvedSessionId = current.sessionId
                current = repository.cancelInstall(expectedReviewToken, resolvedSessionId)
            }
            current
        } catch (error: CancellationException) {
            clearReplacementCancelMarker(generation)
            throw error
        } catch (error: Exception) {
            val resolution = finishInstallCancellationReconciliation(
                generation,
                replacingActiveInstall,
                suppressTerminalOnSuccess,
                expectedReviewToken,
                resolvedSessionId,
                InstallCleanupResult.CleanupFailed,
            )
            if (resolution is InstallCancellationResolution.OwnedTerminal) {
                return resolution
            }
            throw error
        }
        return finishInstallCancellationReconciliation(
            generation,
            replacingActiveInstall,
            suppressTerminalOnSuccess,
            expectedReviewToken,
            resolvedSessionId,
            result,
        )
    }

    private suspend fun finishInstallCancellationReconciliation(
        generation: Long,
        replacingActiveInstall: Boolean,
        suppressTerminalOnSuccess: Boolean,
        expectedReviewToken: String,
        expectedSessionId: Int?,
        result: InstallCleanupResult,
    ): InstallCancellationResolution {
        if (!replacingActiveInstall || replacementCancelGeneration != generation) {
            return if (result == InstallCleanupResult.Cleaned) {
                InstallCancellationResolution.Cleaned
            } else {
                InstallCancellationResolution.Failed
            }
        }
        val deferred = clearReplacementCancelMarker(generation)
        if (result == InstallCleanupResult.Cleaned) {
            if (suppressTerminalOnSuccess) {
                if (deferred == null) {
                    repository.installState.value
                        .takeIf(InstallState::isTerminalState)
                        ?.let { suppressedInstallTerminal = it }
                }
            }
            return InstallCancellationResolution.Cleaned
        }
        val terminal = deferred ?: repository.installState.value
            .takeIf(InstallState::isTerminalState)
            ?: return InstallCancellationResolution.Failed
        return when (
            val owner = repository.terminalForOwner(
                expectedReviewToken,
                expectedSessionId,
            )
        ) {
            is InstallTerminalOwnerResult.Owned ->
                if (owner.state == terminal) {
                    InstallCancellationResolution.OwnedTerminal(
                        state = terminal,
                        sessionId = expectedSessionId,
                    )
                } else {
                    InstallCancellationResolution.Failed
                }
            is InstallTerminalOwnerResult.OwnerSessionAssigned,
            InstallTerminalOwnerResult.OwnerMismatch,
            -> InstallCancellationResolution.Failed
        }
    }

    private suspend fun applyObservedInstallTerminal(
        installState: InstallState,
    ): Boolean = applyOwnedInstallTerminal(installState)

    private suspend fun applyCurrentOwnedInstallTerminal(): Boolean =
        applyOwnedInstallTerminal(expectedInstallState = null)

    private suspend fun applyOwnedInstallTerminal(
        expectedInstallState: InstallState?,
    ): Boolean {
        val generatedToken = generatedInstallOwnerToken
        val expectedReviewToken = if (generatedToken != null) {
            val result = mutableState.value.conversionState as? ConversionUiState.ResultReady
            result?.review?.token?.takeIf { result.token == generatedToken }
        } else {
            ownedReviewToken ?: savedStateHandle.get<String>(REVIEW_TOKEN_KEY)
        } ?: return false
        val expectedSessionId = savedStateHandle.get<Int>(INSTALL_SESSION_ID_KEY)
        val proof = repository.terminalForOwner(expectedReviewToken, expectedSessionId)
        var ownedSessionId = expectedSessionId
        val ownedInstallState = when (proof) {
            is InstallTerminalOwnerResult.Owned ->
                proof.state.takeIf {
                    expectedInstallState == null || it == expectedInstallState
                }
            is InstallTerminalOwnerResult.OwnerSessionAssigned -> {
                if ((expectedInstallState != null &&
                        proof.state != expectedInstallState) ||
                    expectedSessionId != null ||
                    !assignTerminalSessionToCurrentOwner(
                        expectedReviewToken,
                        proof.sessionId,
                    )
                ) {
                    null
                } else {
                    ownedSessionId = proof.sessionId
                    proof.state
                }
            }
            InstallTerminalOwnerResult.OwnerMismatch -> null
        } ?: return false
        return applyInstallTerminal(
            installState = ownedInstallState,
            expectedReviewToken = expectedReviewToken,
            expectedSessionId = ownedSessionId,
        )
    }

    private suspend fun hasOwnedTerminal(
        expectedReviewToken: String,
        expectedState: InstallState,
    ): Boolean = when (
        val proof = repository.terminalForOwner(
            expectedReviewToken,
            savedStateHandle[INSTALL_SESSION_ID_KEY],
        )
    ) {
        is InstallTerminalOwnerResult.Owned -> proof.state == expectedState
        is InstallTerminalOwnerResult.OwnerSessionAssigned -> proof.state == expectedState
        InstallTerminalOwnerResult.OwnerMismatch -> false
    }

    private fun applyAttemptOwnedFailure(
        expectedReviewToken: String,
        installState: InstallState.Failed,
    ) {
        if (ownedReviewToken != expectedReviewToken ||
            savedStateHandle.get<String>(REVIEW_TOKEN_KEY) != expectedReviewToken
        ) {
            return
        }
        val generatedOwner = generatedInstallOwnerToken
        if (generatedOwner != null) {
            val result =
                mutableState.value.conversionState as? ConversionUiState.ResultReady ?: return
            if (result.token != generatedOwner || result.review?.token != expectedReviewToken) return
            if (installState.code in CLEANUP_RETRYABLE_FAILURES) {
                mutableState.update {
                    it.copy(
                        route = ManagerRoute.ConversionResult(result.token),
                        conversionState = result.copy(installOwnerActive = true),
                        message = installFailureMessage(installState.code),
                    )
                }
            } else {
                returnGeneratedInstallToResult(installFailureMessage(installState.code))
            }
            return
        }
        supersedeRecoveryForTerminal()
        if (installState.code in CLEANUP_RETRYABLE_FAILURES) {
            mutableState.update { it.copy(message = installFailureMessage(installState.code)) }
            return
        }
        clearSavedReviewAndSession()
        mutableState.update {
            it.copy(
                route = ManagerRoute.Home,
                importState = ImportUiState.Idle,
                message = installFailureMessage(installState.code),
            )
        }
    }

    private fun assignTerminalSessionToCurrentOwner(
        expectedReviewToken: String,
        sessionId: Int,
    ): Boolean {
        if (sessionId < 0 ||
            savedStateHandle.get<String>(REVIEW_TOKEN_KEY) != expectedReviewToken
        ) {
            return false
        }
        val generatedOwner = generatedInstallOwnerToken
        if (generatedOwner != null) {
            val result = mutableState.value.conversionState as? ConversionUiState.ResultReady
            if (result?.token != generatedOwner || result.review?.token != expectedReviewToken) {
                return false
            }
            val cleanup = pendingGeneratedCleanup
            if (cleanup?.phase == GeneratedCleanupPhase.INSTALL) {
                val promoted = promotePendingGeneratedInstallSession(cleanup, sessionId)
                    ?: return false
                if (promoted.installSessionId != sessionId) return false
            } else if (cleanup != null) {
                return false
            }
        } else if (ownedReviewToken != expectedReviewToken) {
            val savedOwnerAwaitingInitialization =
                !initialized &&
                    ownedReviewToken == null &&
                    savedStateHandle.get<String>(REVIEW_TOKEN_KEY) == expectedReviewToken
            if (!savedOwnerAwaitingInitialization) return false
            ownedReviewToken = expectedReviewToken
        }
        savedStateHandle[INSTALL_SESSION_ID_KEY] = sessionId
        return true
    }

    private fun promotePendingGeneratedInstallSession(
        current: PendingGeneratedCleanup,
        sessionId: Int,
    ): PendingGeneratedCleanup? {
        val latest = pendingGeneratedCleanup
        if (sessionId < 0 ||
            latest?.token != current.token ||
            latest.reviewToken != current.reviewToken ||
            latest.phase != GeneratedCleanupPhase.INSTALL ||
            latest.installSessionId?.let { it != sessionId } == true ||
            savedStateHandle.get<Int>(INSTALL_SESSION_ID_KEY)
                ?.let { it != sessionId } == true ||
            !latest.matchesResultOwners() ||
            generatedInstallOwnerToken != latest.token
        ) {
            return null
        }
        return latest.copy(installSessionId = sessionId).also(::advanceGeneratedCleanup)
    }

    private sealed interface InstallCancellationResolution {
        data object Cleaned : InstallCancellationResolution
        data class OwnedTerminal(
            val state: InstallState,
            val sessionId: Int?,
        ) : InstallCancellationResolution
        data object Failed : InstallCancellationResolution
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

    private suspend fun applyInstallTerminal(
        installState: InstallState,
        expectedReviewToken: String,
        expectedSessionId: Int?,
    ): Boolean {
        val generatedOwner = generatedInstallOwnerToken
        if (generatedOwner != null) {
            return applyGeneratedInstallTerminal(
                token = generatedOwner,
                installState = installState,
                expectedReviewToken = expectedReviewToken,
                expectedSessionId = expectedSessionId,
            )
        }
        supersedeRecoveryForTerminal()
        if (installState is InstallState.Failed &&
            installState.code in CLEANUP_RETRYABLE_FAILURES &&
            savedStateHandle.get<String>(REVIEW_TOKEN_KEY) != null
        ) {
            return true
        }
        val handoff = NormalInstallHandoff(
            importToken = expectedReviewToken,
            sessionId = expectedSessionId,
            packageName = when (installState) {
                is InstallState.Installed -> installState.packageName
                else -> (mutableState.value.importState as? ImportUiState.ReviewReady)
                    ?.review
                    ?.packageName
                    ?: return false
            },
            state = installState,
            stage = NormalHandoffStage.PREPARED,
        )
        if (!repository.compareAndSetNormalHandoff(null, handoff)) {
            showTerminalHandoffFailure()
            return false
        }
        pendingNormalHandoff = handoff
        afterNormalHandoffPersisted()
        return resumeNormalHandoff(
            handoff,
            RestoredInstallTerminal(
                importToken = expectedReviewToken,
                sessionId = expectedSessionId,
                packageName = handoff.packageName,
                state = installState,
                stage = FinalReceiptStage.READY,
            ),
        )
    }

    private suspend fun resumeNormalHandoff(
        handoff: NormalInstallHandoff,
        terminal: RestoredInstallTerminal?,
    ): Boolean {
        if (handoff.stage == NormalHandoffStage.OUTCOME_READY) {
            pendingNormalHandoff = handoff
            presentNormalHandoff(handoff)
            return true
        }
        if (terminal != null) {
            if (terminal.importToken != handoff.importToken ||
                terminal.sessionId != handoff.sessionId ||
                terminal.packageName != handoff.packageName ||
                terminal.stage == FinalReceiptStage.CANCELLING
            ) {
                return false
            }
            if (terminal.state != handoff.state) {
                return rebaseAndResumeNormalHandoff(handoff)
            }
        }
        if (terminal != null && !completeTerminalReceipt(
                expectedReviewToken = handoff.importToken,
                expectedSessionId = handoff.sessionId,
                expectedState = handoff.state,
            )
        ) {
            return rebaseAndResumeNormalHandoff(handoff)
        }
        afterTerminalReceiptFinalized()
        if (!repository.discardReview(handoff.importToken)) return false
        val ready = handoff.copy(stage = NormalHandoffStage.OUTCOME_READY)
        if (!repository.compareAndSetNormalHandoff(handoff, ready)) return false
        pendingNormalHandoff = ready
        presentNormalHandoff(ready)
        return true
    }

    private suspend fun rebaseAndResumeNormalHandoff(
        handoff: NormalInstallHandoff,
    ): Boolean {
        val current = repository.rawReadyTerminalForOwner(
            expectedReviewToken = handoff.importToken,
            expectedSessionId = handoff.sessionId,
            expectedPackageName = handoff.packageName,
        ) ?: return false
        if (current.state == handoff.state) return false
        val replacement = handoff.copy(state = current.state)
        if (!repository.rebaseNormalHandoff(handoff, replacement)) return false
        pendingNormalHandoff = replacement
        afterNormalHandoffPersisted()
        return resumeNormalHandoff(replacement, current)
    }

    private suspend fun resumeNormalHandoffWithCurrentReceipt(
        handoff: NormalInstallHandoff,
        restoredTerminal: RestoredInstallTerminal? = null,
    ): Boolean {
        if (handoff.stage == NormalHandoffStage.OUTCOME_READY ||
            restoredTerminal != null
        ) {
            return resumeNormalHandoff(handoff, restoredTerminal)
        }
        return when (val proof = repository.currentTerminalReceiptForOwner(
            expectedReviewToken = handoff.importToken,
            expectedSessionId = handoff.sessionId,
            expectedPackageName = handoff.packageName,
        )) {
            is TerminalReceiptOwnerResult.Owned ->
                resumeNormalHandoff(handoff, proof.terminal)
            TerminalReceiptOwnerResult.Absent ->
                resumeNormalHandoff(handoff, terminal = null)
            is TerminalReceiptOwnerResult.OwnerSessionAssigned,
            TerminalReceiptOwnerResult.OwnerMismatch,
            -> false
        }
    }

    private suspend fun completePendingNormalHandoffForNewWork(): Boolean {
        var handoff = pendingNormalHandoff ?: return true
        if (handoff.stage == NormalHandoffStage.PREPARED) {
            if (!resumeNormalHandoffWithCurrentReceipt(handoff)) {
                showTerminalHandoffFailure()
                return false
            }
            handoff = pendingNormalHandoff ?: return false
        }
        if (handoff.stage != NormalHandoffStage.OUTCOME_READY ||
            !repository.compareAndSetNormalHandoff(handoff, null)
        ) {
            showTerminalHandoffFailure()
            return false
        }
        pendingNormalHandoff = null
        pendingReviewDiscards.remove(handoff.importToken)
        return true
    }

    private fun presentNormalHandoff(handoff: NormalInstallHandoff) {
        clearSavedReviewAndSession()
        mutableState.update {
            when (val terminal = handoff.state) {
                is InstallState.Installed -> it.copy(
                    route = ManagerRoute.Report(terminal.packageName),
                    importState = ImportUiState.Idle,
                    message = null,
                )
                InstallState.Cancelled -> it.copy(
                    route = ManagerRoute.Home,
                    importState = ImportUiState.Idle,
                    message = normalOutcomeMessage(terminal),
                )
                is InstallState.Failed -> it.copy(
                    route = ManagerRoute.Home,
                    importState = ImportUiState.Idle,
                    message = normalOutcomeMessage(terminal),
                )
                else -> it
            }
        }
    }

    private fun normalOutcomeMessage(state: InstallState): UserMessage? = when (state) {
        InstallState.Cancelled -> UserMessage(
            UserMessageKind.INSTALL_CANCELLED,
            "시스템 설치가 취소되었습니다.",
            RecoveryAction.SELECT_ANOTHER_FILE,
        )
        is InstallState.Failed -> installFailureMessage(state.code)
        else -> null
    }

    private fun showTerminalHandoffFailure() {
        mutableState.update {
            it.copy(
                message = UserMessage(
                    UserMessageKind.INSTALL_FAILED,
                    "설치 결과 정리를 완료하지 못했습니다. 다시 시도하거나 다른 파일을 선택해 주세요.",
                    RecoveryAction.SELECT_ANOTHER_FILE,
                ),
            )
        }
    }

    private suspend fun completeTerminalReceipt(
        expectedReviewToken: String,
        expectedSessionId: Int?,
        expectedState: InstallState,
    ): Boolean {
        if (repository.finalizeTerminal(
                expectedReviewToken,
                expectedSessionId,
                expectedState,
            )
        ) {
            return true
        }
        if (!repository.acknowledgeTerminal(
                expectedReviewToken,
                expectedSessionId,
                expectedState,
            )
        ) {
            return false
        }
        return repository.finalizeTerminal(
            expectedReviewToken,
            expectedSessionId,
            expectedState,
        )
    }

    private suspend fun applyGeneratedInstallTerminal(
        token: String,
        installState: InstallState,
        expectedReviewToken: String,
        expectedSessionId: Int?,
    ): Boolean {
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady
        if (result?.token != token) return false
        supersedeRecoveryForTerminal()
        val pendingInstallCleanup = pendingInstallCleanupForCurrentOwner(token)
        if (pendingGeneratedCleanup?.let {
                it.phase == GeneratedCleanupPhase.INSTALL && it.token == token
            } == true &&
            pendingInstallCleanup == null
        ) {
            return false
        }
        if (pendingInstallCleanup != null &&
            (installState !is InstallState.Failed ||
                installState.code !in CLEANUP_RETRYABLE_FAILURES)
        ) {
            val transitioned = transitionPendingInstallCleanup(
                cleanup = pendingInstallCleanup,
                destination = if (installState is InstallState.Installed) {
                    GeneratedCleanupDestination.fromRoute(
                        ManagerRoute.Report(installState.packageName),
                    )
                } else {
                    pendingInstallCleanup.destination
                },
                terminalReceipt = GeneratedTerminalReceipt(
                    reviewToken = expectedReviewToken,
                    sessionId = expectedSessionId,
                    state = installState,
                ),
            )
            if (transitioned == null) return false
            enqueueGeneratedCleanup {
                performPendingGeneratedCleanup()
            }
            return true
        }
        return when (installState) {
            is InstallState.Installed -> {
                beginGeneratedCleanup(
                    token = token,
                    reviewToken = result.review?.token,
                    phase = if (result.review != null) {
                        GeneratedCleanupPhase.REVIEW
                    } else {
                        GeneratedCleanupPhase.CONVERSION
                    },
                    destination = GeneratedCleanupDestination.fromRoute(
                        ManagerRoute.Report(installState.packageName),
                    ),
                    autoReplayPending = true,
                )
                afterGeneratedInstallCleanupTransitionPersisted()
                if (!completeTerminalReceipt(
                        expectedReviewToken = expectedReviewToken,
                        expectedSessionId = expectedSessionId,
                        expectedState = installState,
                    )
                ) {
                    showGeneratedCleanupFailure()
                    false
                } else {
                    afterTerminalReceiptFinalized()
                    clearGeneratedInstallOwner()
                    savedStateHandle[INSTALL_SESSION_ID_KEY] = null
                    mutableState.update {
                        val ready = it.conversionState as? ConversionUiState.ResultReady
                        it.copy(
                            conversionState = ready?.copy(installOwnerActive = false)
                                ?: it.conversionState,
                        )
                    }
                    enqueueGeneratedCleanup {
                        performPendingGeneratedCleanup()
                    }
                    true
                }
            }
            InstallState.Cancelled -> {
                val handoff = saveGeneratedTerminalHandoff(
                    token,
                    expectedReviewToken,
                    expectedSessionId,
                    installState,
                )
                afterGeneratedTerminalHandoffPersisted()
                if (!completeTerminalReceipt(
                        expectedReviewToken,
                        expectedSessionId,
                        installState,
                    )
                ) {
                    false
                } else {
                    afterTerminalReceiptFinalized()
                    returnGeneratedInstallToResult(
                        UserMessage(
                            UserMessageKind.INSTALL_CANCELLED,
                            "시스템 설치가 취소되었습니다. 생성 결과를 저장하거나 다시 설치할 수 있습니다.",
                            RecoveryAction.NONE,
                        ),
                    )
                    clearGeneratedTerminalHandoff(handoff)
                    true
                }
            }
            is InstallState.Failed ->
                if (installState.code in CLEANUP_RETRYABLE_FAILURES) {
                    mutableState.update {
                        it.copy(
                            route = ManagerRoute.ConversionResult(result.token),
                            conversionState = result.copy(installOwnerActive = true),
                            message = installFailureMessage(installState.code),
                        )
                    }
                    true
                } else {
                    val handoff = saveGeneratedTerminalHandoff(
                        token,
                        expectedReviewToken,
                        expectedSessionId,
                        installState,
                    )
                    afterGeneratedTerminalHandoffPersisted()
                    if (!completeTerminalReceipt(
                            expectedReviewToken,
                            expectedSessionId,
                            installState,
                        )
                    ) {
                        false
                    } else {
                        afterTerminalReceiptFinalized()
                        returnGeneratedInstallToResult(
                            installFailureMessage(installState.code),
                        )
                        clearGeneratedTerminalHandoff(handoff)
                        true
                    }
                }
            else -> false
        }
    }

    private fun pendingInstallCleanupForCurrentOwner(
        token: String,
    ): PendingGeneratedCleanup? {
        val cleanup = pendingGeneratedCleanup ?: return null
        return cleanup.takeIf {
            it.token == token &&
                it.phase == GeneratedCleanupPhase.INSTALL &&
                generatedInstallOwnerToken == token &&
                savedStateHandle.get<String>(INSTALL_OWNER_CONVERSION_TOKEN_KEY) == token &&
                it.matchesResultOwners() &&
                installSessionsDoNotConflict(it)
        }
    }

    private suspend fun transitionPendingInstallCleanup(
        cleanup: PendingGeneratedCleanup,
        destination: GeneratedCleanupDestination,
        terminalReceipt: GeneratedTerminalReceipt? = null,
    ): PendingGeneratedCleanup? {
        val next = cleanup.copy(
            phase = if (cleanup.reviewToken != null) {
                GeneratedCleanupPhase.REVIEW
            } else {
                GeneratedCleanupPhase.CONVERSION
            },
            destination = destination,
            installSessionId = null,
            autoReplayPending = terminalReceipt?.state is InstallState.Installed,
        )
        advanceGeneratedCleanup(next)
        afterGeneratedInstallCleanupTransitionPersisted()
        if (terminalReceipt != null &&
            !completeTerminalReceipt(
                expectedReviewToken = terminalReceipt.reviewToken,
                expectedSessionId = terminalReceipt.sessionId,
                expectedState = terminalReceipt.state,
            )
        ) {
            showGeneratedCleanupFailure()
            return null
        }
        if (terminalReceipt != null) afterTerminalReceiptFinalized()
        clearGeneratedInstallOwner()
        savedStateHandle[INSTALL_SESSION_ID_KEY] = null
        mutableState.update {
            val ready = it.conversionState as? ConversionUiState.ResultReady
            it.copy(
                conversionState = ready?.copy(installOwnerActive = false)
                    ?: it.conversionState,
                message = null,
            )
        }
        return next
    }

    private fun returnGeneratedInstallToResult(message: UserMessage) {
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady ?: return
        clearGeneratedInstallOwner()
        savedStateHandle[INSTALL_SESSION_ID_KEY] = null
        mutableState.update {
            it.copy(
                route = ManagerRoute.ConversionResult(result.token),
                conversionState = result.copy(installOwnerActive = false),
                message = message,
            )
        }
    }

    private suspend fun resumeGeneratedTerminalHandoff(
        handoff: PendingGeneratedTerminalHandoff,
        terminal: RestoredInstallTerminal?,
    ): Boolean {
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady
            ?: return false
        if (result.token != handoff.token ||
            result.review?.token != handoff.reviewToken
        ) {
            return false
        }
        if (terminal != null &&
            (terminal.importToken != handoff.reviewToken ||
                terminal.sessionId != handoff.sessionId ||
                terminal.packageName != result.review.packageName ||
                terminal.stage == FinalReceiptStage.CANCELLING)
        ) {
            return false
        }
        if (terminal != null && terminal.state != handoff.state) {
            return rebaseAndResumeGeneratedTerminalHandoff(handoff, result)
        }
        if (handoff.state is InstallState.Installed) {
            return resumeGeneratedInstalledHandoff(
                handoff = handoff,
                result = result,
                terminalAvailable = terminal != null,
            )
        }
        if (terminal != null && !completeTerminalReceipt(
                handoff.reviewToken,
                handoff.sessionId,
                handoff.state,
            )
        ) {
            return rebaseAndResumeGeneratedTerminalHandoff(handoff, result)
        }
        if (terminal != null) afterTerminalReceiptFinalized()
        returnGeneratedInstallToResult(generatedTerminalMessage(handoff.state))
        clearGeneratedTerminalHandoff(handoff)
        return true
    }

    private suspend fun resumeGeneratedInstalledHandoff(
        handoff: PendingGeneratedTerminalHandoff,
        result: ConversionUiState.ResultReady,
        terminalAvailable: Boolean,
    ): Boolean {
        val installed = handoff.state as? InstallState.Installed ?: return false
        val destination = GeneratedCleanupDestination.fromRoute(
            ManagerRoute.Report(installed.packageName),
        )
        val cleanup = pendingGeneratedCleanup?.takeIf { existing ->
            existing.token == handoff.token &&
                existing.reviewToken == handoff.reviewToken &&
                existing.destination == destination &&
                existing.installSessionId == null &&
                existing.autoReplayPending &&
                existing.phase in setOf(
                    GeneratedCleanupPhase.REVIEW,
                    GeneratedCleanupPhase.CONVERSION,
                )
        } ?: run {
            if (pendingGeneratedCleanup != null) return false
            beginGeneratedCleanup(
                token = handoff.token,
                reviewToken = handoff.reviewToken,
                phase = if (result.review != null) {
                    GeneratedCleanupPhase.REVIEW
                } else {
                    GeneratedCleanupPhase.CONVERSION
                },
                destination = destination,
                autoReplayPending = true,
            )
        }
        afterGeneratedInstallCleanupTransitionPersisted()
        if (terminalAvailable &&
            !completeTerminalReceipt(
                expectedReviewToken = handoff.reviewToken,
                expectedSessionId = handoff.sessionId,
                expectedState = installed,
            )
        ) {
            return false
        }
        if (terminalAvailable) afterTerminalReceiptFinalized()
        if (pendingGeneratedCleanup != cleanup) return false
        clearGeneratedInstallOwner()
        savedStateHandle[INSTALL_SESSION_ID_KEY] = null
        mutableState.update {
            val ready = it.conversionState as? ConversionUiState.ResultReady
            it.copy(
                conversionState = ready?.copy(installOwnerActive = false)
                    ?: it.conversionState,
                message = null,
            )
        }
        clearGeneratedTerminalHandoff(handoff)
        afterGeneratedTerminalHandoffCleared()
        enqueueGeneratedCleanup {
            performPendingGeneratedCleanup()
        }
        return true
    }

    private suspend fun rebaseAndResumeGeneratedTerminalHandoff(
        handoff: PendingGeneratedTerminalHandoff,
        result: ConversionUiState.ResultReady,
    ): Boolean {
        val packageName = result.review?.packageName ?: return false
        val current = repository.rawReadyTerminalForOwner(
            expectedReviewToken = handoff.reviewToken,
            expectedSessionId = handoff.sessionId,
            expectedPackageName = packageName,
        ) ?: return false
        val supportedTerminal = current.state is InstallState.Failed ||
            current.state == InstallState.Cancelled ||
            current.state is InstallState.Installed
        if (current.state == handoff.state || !supportedTerminal) {
            return false
        }
        val replacement = handoff.copy(state = current.state)
        if (!replaceGeneratedTerminalHandoff(handoff, replacement)) return false
        afterGeneratedTerminalHandoffRebased()
        return resumeGeneratedTerminalHandoff(replacement, current)
    }

    private fun generatedTerminalMessage(state: InstallState): UserMessage = when (state) {
        InstallState.Cancelled -> UserMessage(
            UserMessageKind.INSTALL_CANCELLED,
            "시스템 설치가 취소되었습니다. 생성 결과를 저장하거나 다시 설치할 수 있습니다.",
            RecoveryAction.NONE,
        )
        is InstallState.Failed -> installFailureMessage(state.code)
        else -> error("generated result handoff는 취소 또는 실패만 허용합니다.")
    }

    private fun saveGeneratedTerminalHandoff(
        token: String,
        reviewToken: String,
        sessionId: Int?,
        state: InstallState,
    ): PendingGeneratedTerminalHandoff {
        val handoff = PendingGeneratedTerminalHandoff(
            token = token,
            reviewToken = reviewToken,
            sessionId = sessionId,
            state = state,
        )
        savedStateHandle[GENERATED_TERMINAL_HANDOFF_KEY] =
            encodeGeneratedTerminalHandoff(handoff)
        pendingGeneratedTerminalHandoff = handoff
        return handoff
    }

    private fun clearGeneratedTerminalHandoff(
        expected: PendingGeneratedTerminalHandoff,
    ) {
        if (pendingGeneratedTerminalHandoff != expected) return
        savedStateHandle[GENERATED_TERMINAL_HANDOFF_KEY] = null
        pendingGeneratedTerminalHandoff = null
    }

    private fun replaceGeneratedTerminalHandoff(
        expected: PendingGeneratedTerminalHandoff,
        replacement: PendingGeneratedTerminalHandoff,
    ): Boolean {
        if (pendingGeneratedTerminalHandoff != expected ||
            restoreGeneratedTerminalHandoff() != expected
        ) {
            return false
        }
        savedStateHandle[GENERATED_TERMINAL_HANDOFF_KEY] =
            encodeGeneratedTerminalHandoff(replacement)
        pendingGeneratedTerminalHandoff = replacement
        return true
    }

    private fun showGeneratedCleanupFailure() {
        mutableState.update {
            val result = it.conversionState as? ConversionUiState.ResultReady
            it.copy(
                route = result?.let { ready -> ManagerRoute.ConversionResult(ready.token) }
                    ?: it.route,
                message = UserMessage(
                    UserMessageKind.CONVERSION_CLEANUP_FAILED,
                    "설치 작업 정리를 완료하지 못했습니다. 생성 결과를 보존했으니 다시 시도해 주세요.",
                    RecoveryAction.RETRY,
                ),
            )
        }
    }

    private fun showStartupRecoveryBlocked() {
        mutableState.update {
            it.copy(
                startupRecoveryState = StartupRecoveryUiState.RECOVERY_BLOCKED,
                message = UserMessage(
                    kind = UserMessageKind.RECOVERY_BLOCKED,
                    text = "저장된 설치 복구 정보를 읽을 수 없어 복구 자료를 보존했습니다. " +
                        "앱을 업데이트한 뒤 다시 확인해 주세요.",
                    recoveryAction = RecoveryAction.NONE,
                ),
            )
        }
    }

    private suspend fun markStartupRecoveryReady() {
        if (mutableState.value.startupRecoveryState ==
            StartupRecoveryUiState.RECOVERY_BLOCKED
        ) {
            return
        }
        if (generatedCleanupRestoreInvalid) {
            showGeneratedCleanupRestoreBlocked()
            return
        }
        if (generatedTerminalHandoffRestoreInvalid) {
            showStartupRecoveryBlocked()
            return
        }
        deferredInitialTerminals = null
        val installState = repository.installState.value
        if (installState is InstallState.AwaitingSystemInstaller &&
            isCurrentReviewOwner(installState.importToken)
        ) {
            systemInstallReturnToken = installState.importToken
        }
        mutableState.update {
            it.copy(startupRecoveryState = StartupRecoveryUiState.READY)
        }
        val reviewToken =
            (mutableState.value.importState as? ImportUiState.ReviewReady)
                ?.review
                ?.token
        if (installState == InstallState.PreparingSystemInstaller &&
            reviewToken != null &&
            isCurrentReviewOwner(reviewToken)
        ) {
            repository.systemInstallApkFallback(reviewToken)
                ?.let { fallback ->
                    setPendingSystemInstallApkDocument(
                        ManagerEffect.CreateSystemInstallApkDocument(
                            importToken = fallback.importToken,
                            source = fallback.source,
                            expectedSha256 = fallback.expectedSha256,
                            suggestedFileName =
                                suggestedWrapperApkName(fallback.extensionName),
                        ),
                    )
                }
            pendingSystemInstallApkDocument
                ?.takeIf { it.importToken == reviewToken }
                ?.let { effect ->
                    pendingRecovery = PendingRecovery.SystemInstallApk(effect)
                    mutableState.update {
                        it.copy(
                            message = UserMessage(
                                UserMessageKind.LAUNCH_FAILED,
                                "APK 저장을 다시 시도해 주세요.",
                                RecoveryAction.RETRY,
                            ),
                        )
                    }
                }
        }
        if (installState is InstallState.AwaitingManualInstall &&
            isCurrentReviewOwner(installState.importToken)
        ) {
            clearPendingSystemInstallApkDocument()
            showManualSystemInstallMessage()
        }
        reconcileQuarantinedGeneratedCleanup()
    }

    internal fun ownsSystemInstallApkDocument(
        effect: ManagerEffect.CreateSystemInstallApkDocument,
    ): Boolean = pendingSystemInstallApkDocument == effect &&
        isCurrentReviewOwner(effect.importToken)

    private fun setPendingSystemInstallApkDocument(
        effect: ManagerEffect.CreateSystemInstallApkDocument,
    ) {
        pendingSystemInstallApkDocument = effect
        savedStateHandle[PENDING_SYSTEM_APK_TOKEN_KEY] = effect.importToken
        savedStateHandle[PENDING_SYSTEM_APK_SOURCE_KEY] = effect.source.path
        savedStateHandle[PENDING_SYSTEM_APK_SHA256_KEY] = effect.expectedSha256
        savedStateHandle[PENDING_SYSTEM_APK_NAME_KEY] = effect.suggestedFileName
    }

    private fun clearPendingSystemInstallApkDocument() {
        pendingSystemInstallApkDocument = null
        savedStateHandle[PENDING_SYSTEM_APK_TOKEN_KEY] = null
        savedStateHandle[PENDING_SYSTEM_APK_SOURCE_KEY] = null
        savedStateHandle[PENDING_SYSTEM_APK_SHA256_KEY] = null
        savedStateHandle[PENDING_SYSTEM_APK_NAME_KEY] = null
    }

    private fun restorePendingSystemInstallApkDocument():
        ManagerEffect.CreateSystemInstallApkDocument? {
        val token = savedStateHandle.get<String>(PENDING_SYSTEM_APK_TOKEN_KEY)
            ?: return null
        val source = savedStateHandle.get<String>(PENDING_SYSTEM_APK_SOURCE_KEY)
            ?: return null
        val sha256 = savedStateHandle.get<String>(PENDING_SYSTEM_APK_SHA256_KEY)
            ?: return null
        val name = savedStateHandle.get<String>(PENDING_SYSTEM_APK_NAME_KEY)
            ?: return null
        return ManagerEffect.CreateSystemInstallApkDocument(
            importToken = token,
            source = File(source),
            expectedSha256 = sha256,
            suggestedFileName = name,
        )
    }

    private fun showGeneratedCleanupRestoreBlocked() {
        mutableState.update {
            it.copy(
                startupRecoveryState = StartupRecoveryUiState.RECOVERY_BLOCKED,
                message = UserMessage(
                    UserMessageKind.CONVERSION_CLEANUP_FAILED,
                    "저장된 설치 정리 정보를 읽을 수 없어 복구 자료를 보존했습니다. 앱을 업데이트한 뒤 다시 확인해 주세요.",
                    RecoveryAction.NONE,
                ),
            )
        }
    }

    private fun showConversionSaveCleanupFailure() {
        mutableState.update {
            val result = it.conversionState as? ConversionUiState.ResultReady
            it.copy(
                route = result?.let { ready -> ManagerRoute.ConversionResult(ready.token) }
                    ?: it.route,
                message = UserMessage(
                    UserMessageKind.CONVERSION_SAVE_CLEANUP_FAILED,
                    "부분 파일 삭제를 완료하지 못했습니다. 저장 위치에서 파일을 직접 삭제한 뒤 다시 시도해 주세요.",
                    RecoveryAction.RETRY,
                ),
            )
        }
    }

    private fun beginGeneratedCleanup(
        token: String,
        reviewToken: String?,
        phase: GeneratedCleanupPhase,
        destination: GeneratedCleanupDestination,
        installSessionId: Int? = null,
        autoReplayPending: Boolean = false,
    ): PendingGeneratedCleanup {
        pendingGeneratedCleanup?.let { existing ->
            if (existing.token == token &&
                existing.reviewToken == reviewToken &&
                existing.matchesResultOwners()
            ) {
                return existing
            }
            quarantineGeneratedCleanup(existing)
            reconcileQuarantinedGeneratedCleanup()
        }
        return PendingGeneratedCleanup(
            token = token,
            reviewToken = reviewToken,
            phase = phase,
            destination = destination,
            installSessionId = installSessionId,
            autoReplayPending = autoReplayPending,
        ).also {
            advanceGeneratedCleanup(it)
            updateGeneratedCleanupState(
                token = it.token,
                pending = true,
                inProgress = false,
            )
        }
    }

    private fun enqueueGeneratedCleanup(
        cleanup: suspend () -> Boolean,
    ): Job {
        generatedCleanupJob?.takeIf { it.isActive }?.let { return it }
        val token = requireNotNull(pendingGeneratedCleanup).token
        updateGeneratedCleanupState(token, pending = true, inProgress = true)
        return enqueueRouteCleanup {
            try {
                pendingGeneratedCleanup
                    ?.takeIf { it.token == token && it.autoReplayPending }
                    ?.let { advanceGeneratedCleanup(it.copy(autoReplayPending = false)) }
                cleanup()
            } finally {
                updateGeneratedCleanupState(
                    token = token,
                    pending = pendingGeneratedCleanup?.token == token,
                    inProgress = false,
                )
            }
        }.also {
            generatedCleanupJob = it
        }
    }

    private fun updateGeneratedCleanupState(
        token: String,
        pending: Boolean,
        inProgress: Boolean,
    ) {
        mutableState.update {
            val ready = it.conversionState as? ConversionUiState.ResultReady
            if (ready?.token != token) {
                it
            } else {
                it.copy(
                    conversionState = ready.copy(
                        cleanupPending = pending,
                        cleanupInProgress = inProgress,
                    ),
                )
            }
        }
    }

    private fun advanceGeneratedCleanup(cleanup: PendingGeneratedCleanup) {
        savedStateHandle[GENERATED_CLEANUP_JOURNAL_KEY] =
            encodePendingGeneratedCleanup(cleanup)
        pendingGeneratedCleanup = cleanup
    }

    private suspend fun performPendingGeneratedCleanup(): Boolean {
        var cleanup = pendingGeneratedCleanup ?: return false
        if (cleanup.phase == GeneratedCleanupPhase.COMMIT_DESTINATION) {
            return completeGeneratedCleanup(cleanup)
        }
        if (!cleanup.matchesResultOwners()) {
            quarantineGeneratedCleanup(cleanup)
            reconcileQuarantinedGeneratedCleanup()
            return true
        }
        if (!cleanup.matchesSavedOwners()) {
            showGeneratedCleanupFailure()
            return false
        }
        awaitGeneratedShareSnapshot()
        if (!cancelAndAwaitGeneratedSave()) {
            clearPendingGeneratedCleanup()
            updateGeneratedCleanupState(
                token = cleanup.token,
                pending = false,
                inProgress = false,
            )
            return true
        }
        if (cleanup.phase == GeneratedCleanupPhase.INSTALL) {
            if (!retryPendingGeneratedInstallCleanup(cleanup)) return false
            cleanup = pendingGeneratedCleanup ?: return false
        }
        if (cleanup.phase == GeneratedCleanupPhase.REVIEW) {
            val reviewToken = cleanup.reviewToken
            if (reviewToken != null && !repository.discardReview(reviewToken)) {
                showGeneratedCleanupFailure()
                return false
            }
            afterGeneratedReviewCleanupCompleted()
            mutableState.update {
                val ready = it.conversionState as? ConversionUiState.ResultReady
                it.copy(
                    conversionState = ready?.copy(review = null) ?: it.conversionState,
                    importState = ImportUiState.Idle,
                )
            }
            cleanup = cleanup.copy(phase = GeneratedCleanupPhase.CONVERSION)
            advanceGeneratedCleanup(cleanup)
        }
        if (!repository.discardConversion(cleanup.token)) {
            showGeneratedCleanupFailure()
            return false
        }
        val committed = cleanup.copy(
            reviewToken = null,
            phase = GeneratedCleanupPhase.COMMIT_DESTINATION,
            installSessionId = null,
        )
        advanceGeneratedCleanup(committed)
        afterGeneratedCleanupCompletionStep(
            GeneratedCleanupCompletionStep.DESTINATION_COMMITTED,
        )
        return completeGeneratedCleanup(committed)
    }

    private suspend fun cancelAndAwaitGeneratedSave(): Boolean {
        val saveJob = conversionIoJob
        val saveResult = conversionIoResult
        saveJob?.cancel()
        val completedSave = saveResult?.await()
        saveJob?.join()
        if (conversionIoJob === saveJob) {
            conversionIoJob = null
            conversionIoResult = null
        }
        val cleanupFailed = when (completedSave) {
            is GeneratedDocumentSaveResult.Cancelled -> completedSave.cleanupFailed
            is GeneratedDocumentSaveResult.Failure -> completedSave.cleanupFailed
            GeneratedDocumentSaveResult.Success,
            null,
            -> false
        }
        if (cleanupFailed) showConversionSaveCleanupFailure()
        return !cleanupFailed
    }

    private suspend fun awaitGeneratedShareSnapshot() {
        val shareJob = generatedShareJob
        shareJob?.join()
        if (generatedShareJob === shareJob) {
            generatedShareJob = null
        }
    }

    private suspend fun retryPendingGeneratedInstallCleanup(
        cleanup: PendingGeneratedCleanup,
        generation: Long = operationGeneration,
    ): Boolean {
        val ready = mutableState.value.conversionState as? ConversionUiState.ResultReady
        val reviewToken = cleanup.reviewToken
        val exactOwner = reviewToken != null &&
            ready?.token == cleanup.token &&
            generatedInstallOwnerToken == cleanup.token &&
            savedStateHandle.get<String>(INSTALL_OWNER_CONVERSION_TOKEN_KEY) == cleanup.token &&
            savedStateHandle.get<String>(REVIEW_TOKEN_KEY) == reviewToken &&
            cleanup.matchesResultOwners() &&
            installSessionsDoNotConflict(cleanup)
        if (!exactOwner) {
            showGeneratedCleanupFailure()
            return false
        }
        var current = cleanup
        var savedSession = savedStateHandle.get<Int>(INSTALL_SESSION_ID_KEY)
        when {
            current.installSessionId != null && savedSession == null -> {
                savedStateHandle[INSTALL_SESSION_ID_KEY] = current.installSessionId
                savedSession = current.installSessionId
            }
            current.installSessionId == null && savedSession != null -> {
                current = current.copy(installSessionId = savedSession)
                advanceGeneratedCleanup(current)
            }
        }
        val resolution = try {
            reconcileInstallCancellation(
                generation = generation,
                suppressTerminalOnSuccess = true,
                expectedReviewToken = reviewToken,
                expectedSessionId = current.installSessionId ?: savedSession,
                onOwnerSessionAssigned = { actualSessionId ->
                    val promoted =
                        promotePendingGeneratedInstallSession(current, actualSessionId)
                            ?: return@reconcileInstallCancellation false
                    current = promoted
                    savedStateHandle[INSTALL_SESSION_ID_KEY] = actualSessionId
                    true
                },
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            showGeneratedCleanupFailure()
            return false
        }
        val destination = when (resolution) {
            InstallCancellationResolution.Cleaned -> current.destination
            is InstallCancellationResolution.OwnedTerminal ->
                if (resolution.state is InstallState.Installed) {
                    GeneratedCleanupDestination.fromRoute(
                        ManagerRoute.Report(resolution.state.packageName),
                    )
                } else {
                    current.destination
                }
            InstallCancellationResolution.Failed -> {
                showGeneratedCleanupFailure()
                return false
            }
        }
        val terminalReceipt = (resolution as? InstallCancellationResolution.OwnedTerminal)
            ?.let {
                GeneratedTerminalReceipt(
                    reviewToken = reviewToken,
                    sessionId = it.sessionId,
                    state = it.state,
                )
            }
        if (transitionPendingInstallCleanup(
            cleanup = current,
            destination = destination,
            terminalReceipt = terminalReceipt,
        ) == null
        ) {
            return false
        }
        return true
    }

    private fun completeGeneratedCleanup(cleanup: PendingGeneratedCleanup): Boolean {
        if (cleanup.phase != GeneratedCleanupPhase.COMMIT_DESTINATION &&
            !cleanup.matchesSavedOwners()
        ) {
            quarantineGeneratedCleanup(cleanup)
            reconcileQuarantinedGeneratedCleanup()
            return false
        }
        clearGeneratedInstallOwner()
        afterGeneratedCleanupCompletionStep(
            GeneratedCleanupCompletionStep.INSTALL_OWNER_CLEARED,
        )
        clearGeneratedResultOwner()
        afterGeneratedCleanupCompletionStep(
            GeneratedCleanupCompletionStep.RESULT_OWNER_CLEARED,
        )
        clearSavedReviewAndSession()
        afterGeneratedCleanupCompletionStep(
            GeneratedCleanupCompletionStep.REVIEW_SESSION_CLEARED,
        )
        clearSavedConversion()
        afterGeneratedCleanupCompletionStep(
            GeneratedCleanupCompletionStep.CONVERSION_OWNER_CLEARED,
        )
        mutableState.update {
            it.copy(
                route = cleanup.destination.toRoute(),
                conversionState = ConversionUiState.Idle,
                conversionUrl = "",
                importState = ImportUiState.Idle,
                message = null,
                destinationCommitToken = cleanup.token,
            )
        }
        return true
    }

    private fun acknowledgeGeneratedCleanupDestination(token: String) {
        val cleanup = pendingGeneratedCleanup ?: return
        val state = mutableState.value
        if (cleanup.phase != GeneratedCleanupPhase.COMMIT_DESTINATION ||
            cleanup.token != token ||
            state.destinationCommitToken != token ||
            state.route != cleanup.destination.toRoute()
        ) {
            return
        }
        clearPendingGeneratedCleanup()
        mutableState.update { it.copy(destinationCommitToken = null) }
    }

    private fun PendingGeneratedCleanup.matchesSavedOwners(): Boolean {
        if (!matchesResultOwners()) return false
        if (phase == GeneratedCleanupPhase.INSTALL) {
            return generatedInstallOwnerToken == token &&
                savedStateHandle.get<String>(INSTALL_OWNER_CONVERSION_TOKEN_KEY) == token &&
                savedStateHandle.get<String>(REVIEW_TOKEN_KEY) == reviewToken &&
                installSessionsDoNotConflict(this)
        }
        return savedStateHandle.get<String>(INSTALL_OWNER_CONVERSION_TOKEN_KEY)
            .let { it == null || it == token }
    }

    private fun installSessionsDoNotConflict(
        cleanup: PendingGeneratedCleanup,
    ): Boolean {
        val savedSession = savedStateHandle.get<Int>(INSTALL_SESSION_ID_KEY)
        return cleanup.installSessionId == null ||
            savedSession == null ||
            cleanup.installSessionId == savedSession
    }

    private fun PendingGeneratedCleanup.matchesResultOwners(): Boolean {
        if (savedStateHandle.get<String>(RESULT_OWNER_CONVERSION_TOKEN_KEY) != token ||
            savedStateHandle.get<String>(CONVERSION_TOKEN_KEY) != token
        ) {
            return false
        }
        return savedStateHandle.get<String>(RESULT_OWNER_REVIEW_TOKEN_KEY) == reviewToken
    }

    private fun quarantineGeneratedCleanup(cleanup: PendingGeneratedCleanup) {
        val quarantined = QuarantinedGeneratedCleanup(
            token = cleanup.token,
            reviewToken = cleanup.reviewToken,
            phase = cleanup.phase,
            installSessionId = cleanup.installSessionId,
            conversionCleaned = false,
        )
        advanceQuarantinedGeneratedCleanup(quarantined)
        if (pendingGeneratedCleanup == cleanup) {
            clearPendingGeneratedCleanup()
        }
    }

    private fun reconcileQuarantinedGeneratedCleanup() {
        if (generatedCleanupRestoreInvalid ||
            mutableState.value.startupRecoveryState != StartupRecoveryUiState.READY
        ) {
            return
        }
        quarantinedGeneratedCleanup ?: return
        quarantinedGeneratedCleanupJob
            ?.takeIf { it.isActive }
            ?.let { return }
        quarantinedGeneratedCleanupJob = viewModelScope.launch {
            var cleanup = quarantinedGeneratedCleanup ?: return@launch
            if (cleanup.phase == GeneratedCleanupPhase.INSTALL) {
                cleanup = cleanup.copy(
                    phase = if (cleanup.reviewToken != null) {
                        GeneratedCleanupPhase.REVIEW
                    } else {
                        GeneratedCleanupPhase.CONVERSION
                    },
                    installSessionId = null,
                )
                advanceQuarantinedGeneratedCleanup(cleanup)
            }
            if (cleanup.phase == GeneratedCleanupPhase.REVIEW) {
                val reviewToken = cleanup.reviewToken
                if (reviewToken == null ||
                    cleanup.overlapsCurrentReview() ||
                    repository.discardReview(reviewToken)
                ) {
                    cleanup = cleanup.copy(
                        reviewToken = null,
                        phase = GeneratedCleanupPhase.CONVERSION,
                    )
                    advanceQuarantinedGeneratedCleanup(cleanup)
                }
            }
            if (!cleanup.conversionCleaned &&
                !cleanup.overlapsCurrentConversion() &&
                repository.discardConversion(cleanup.token)
            ) {
                cleanup = cleanup.copy(conversionCleaned = true)
                advanceQuarantinedGeneratedCleanup(cleanup)
            }
            if (cleanup.phase == GeneratedCleanupPhase.CONVERSION &&
                cleanup.conversionCleaned &&
                quarantinedGeneratedCleanup == cleanup
            ) {
                clearQuarantinedGeneratedCleanup()
            }
        }
    }

    private fun QuarantinedGeneratedCleanup.overlapsCurrentReview(): Boolean =
        reviewToken != null &&
            reviewToken in setOfNotNull(
                ownedReviewToken,
                generatedResultReviewToken,
                pendingNormalHandoff?.importToken,
                pendingGeneratedTerminalHandoff?.reviewToken,
                pendingGeneratedCleanup?.reviewToken,
                savedStateHandle[REVIEW_TOKEN_KEY],
                savedStateHandle[RESULT_OWNER_REVIEW_TOKEN_KEY],
                (mutableState.value.importState as? ImportUiState.ReviewReady)
                    ?.review
                    ?.token,
                (mutableState.value.conversionState as? ConversionUiState.ResultReady)
                    ?.review
                    ?.token,
            )

    private fun QuarantinedGeneratedCleanup.overlapsCurrentConversion(): Boolean =
        token in setOfNotNull(
            generatedResultOwnerToken,
            generatedInstallOwnerToken,
            pendingGeneratedTerminalHandoff?.token,
            pendingGeneratedCleanup?.token,
            savedStateHandle[RESULT_OWNER_CONVERSION_TOKEN_KEY],
            savedStateHandle[CONVERSION_TOKEN_KEY],
            (mutableState.value.conversionState as? ConversionUiState.ResultReady)?.token,
        )

    private fun advanceQuarantinedGeneratedCleanup(
        cleanup: QuarantinedGeneratedCleanup,
    ) {
        quarantinedGeneratedCleanup = cleanup
        savedStateHandle[QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY] = cleanup.token
        savedStateHandle[QUARANTINED_GENERATED_CLEANUP_REVIEW_TOKEN_KEY] =
            cleanup.reviewToken
        savedStateHandle[QUARANTINED_GENERATED_CLEANUP_PHASE_KEY] = cleanup.phase.name
        savedStateHandle[QUARANTINED_GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY] =
            cleanup.installSessionId
        savedStateHandle[QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY] =
            cleanup.conversionCleaned
    }

    private fun restoreQuarantinedGeneratedCleanup(): QuarantinedGeneratedCleanup? {
        val rawToken =
            savedStateHandle.get<Any?>(QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY)
        val rawReviewToken =
            savedStateHandle.get<Any?>(QUARANTINED_GENERATED_CLEANUP_REVIEW_TOKEN_KEY)
        val rawPhase =
            savedStateHandle.get<Any?>(QUARANTINED_GENERATED_CLEANUP_PHASE_KEY)
        val rawSession = savedStateHandle.get<Any?>(
            QUARANTINED_GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY,
        )
        val rawConversionCleaned = savedStateHandle.get<Any?>(
            QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY,
        )
        if (listOf(
                rawToken,
                rawReviewToken,
                rawPhase,
                rawSession,
                rawConversionCleaned,
            ).all { it == null }
        ) {
            return null
        }
        return runCatching {
            val token = requireNotNull(rawToken as? String)
            require(token.isCanonicalUuid())
            val reviewToken = when (rawReviewToken) {
                null -> null
                is String -> rawReviewToken.also { require(it.isCanonicalUuid()) }
                else -> error("quarantine review token type")
            }
            val phase = GeneratedCleanupPhase.valueOf(
                requireNotNull(rawPhase as? String),
            )
            val installSessionId = when (rawSession) {
                null -> null
                is Int -> rawSession.also { require(it >= 0) }
                else -> error("quarantine install session type")
            }
            val conversionCleaned = when (rawConversionCleaned) {
                null -> false
                is Boolean -> rawConversionCleaned
                else -> error("quarantine conversion flag type")
            }
            QuarantinedGeneratedCleanup(
                token = token,
                reviewToken = reviewToken,
                phase = phase,
                installSessionId = installSessionId,
                conversionCleaned = conversionCleaned,
            )
        }.getOrElse {
            generatedCleanupRestoreInvalid = true
            null
        }
    }

    private fun clearQuarantinedGeneratedCleanup() {
        quarantinedGeneratedCleanup = null
        savedStateHandle[QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY] = null
        savedStateHandle[QUARANTINED_GENERATED_CLEANUP_REVIEW_TOKEN_KEY] = null
        savedStateHandle[QUARANTINED_GENERATED_CLEANUP_PHASE_KEY] = null
        savedStateHandle[QUARANTINED_GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY] = null
        savedStateHandle[QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY] = null
    }

    private fun restorePendingGeneratedCleanup(): PendingGeneratedCleanup? {
        savedStateHandle.get<String>(GENERATED_CLEANUP_JOURNAL_KEY)?.let { encoded ->
            return decodePendingGeneratedCleanup(encoded).also {
                generatedCleanupRestoreInvalid = it == null
            }
        }
        if (!hasLegacyGeneratedCleanup()) return null
        val cleanup = restoreLegacyPendingGeneratedCleanup()
        if (cleanup == null) {
            generatedCleanupRestoreInvalid = true
            return null
        }
        savedStateHandle[GENERATED_CLEANUP_JOURNAL_KEY] =
            encodePendingGeneratedCleanup(cleanup)
        clearLegacyGeneratedCleanup()
        return cleanup
    }

    private fun clearPendingGeneratedCleanup() {
        clearLegacyGeneratedCleanup()
        savedStateHandle[GENERATED_CLEANUP_JOURNAL_KEY] = null
        generatedCleanupRestoreInvalid = false
        pendingGeneratedCleanup = null
    }

    private fun hasLegacyGeneratedCleanup(): Boolean =
        GENERATED_CLEANUP_LEGACY_KEYS.any { savedStateHandle.get<Any>(it) != null }

    private fun restoreLegacyPendingGeneratedCleanup(): PendingGeneratedCleanup? =
        runCatching {
            PendingGeneratedCleanup(
                token = requireNotNull(
                    savedStateHandle.get<String>(GENERATED_CLEANUP_TOKEN_KEY),
                ),
                reviewToken =
                    savedStateHandle[GENERATED_CLEANUP_REVIEW_TOKEN_KEY],
                phase = GeneratedCleanupPhase.valueOf(
                    requireNotNull(
                        savedStateHandle.get<String>(GENERATED_CLEANUP_PHASE_KEY),
                    ),
                ),
                destination = requireNotNull(
                    GeneratedCleanupDestination.restore(
                        kind = savedStateHandle[GENERATED_CLEANUP_DESTINATION_KEY],
                        packageName = savedStateHandle[GENERATED_CLEANUP_PACKAGE_KEY],
                    ),
                ),
                installSessionId =
                    savedStateHandle[GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY],
            ).also { require(it.isValid()) }
        }.getOrNull()

    private fun clearLegacyGeneratedCleanup() {
        savedStateHandle[GENERATED_CLEANUP_TOKEN_KEY] = null
        savedStateHandle[GENERATED_CLEANUP_REVIEW_TOKEN_KEY] = null
        savedStateHandle[GENERATED_CLEANUP_PHASE_KEY] = null
        savedStateHandle[GENERATED_CLEANUP_DESTINATION_KEY] = null
        savedStateHandle[GENERATED_CLEANUP_PACKAGE_KEY] = null
        savedStateHandle[GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY] = null
    }

    private fun restoreGeneratedTerminalHandoff(): PendingGeneratedTerminalHandoff? {
        val raw = savedStateHandle.get<Any?>(GENERATED_TERMINAL_HANDOFF_KEY)
            ?: return null
        val encoded = raw as? String
        if (encoded == null) {
            generatedTerminalHandoffRestoreInvalid = true
            return null
        }
        return decodeGeneratedTerminalHandoff(encoded).also {
            if (it == null) generatedTerminalHandoffRestoreInvalid = true
        }
    }

    private fun encodeGeneratedTerminalHandoff(
        handoff: PendingGeneratedTerminalHandoff,
    ): String = buildJsonObject {
        put("version", GENERATED_TERMINAL_HANDOFF_VERSION)
        put("token", handoff.token)
        put("reviewToken", handoff.reviewToken)
        put(
            "sessionId",
            handoff.sessionId?.let(::JsonPrimitive) ?: JsonNull,
        )
        when (val state = handoff.state) {
            InstallState.Cancelled -> put("state", "CANCELLED")
            is InstallState.Failed -> {
                put("state", "FAILED")
                put("code", state.code)
                put("message", state.message)
            }
            is InstallState.Installed -> {
                put("state", "INSTALLED")
                put("packageName", state.packageName)
            }
            else -> error("generated result handoff는 terminal state만 허용합니다.")
        }
    }.toString()

    private fun decodeGeneratedTerminalHandoff(
        encoded: String,
    ): PendingGeneratedTerminalHandoff? = runCatching {
        val root = Json.parseToJsonElement(encoded) as? JsonObject
            ?: error("generated terminal handoff root")
        require(root.requiredInt("version") == GENERATED_TERMINAL_HANDOFF_VERSION)
        val state = when (root.requiredString("state")) {
            "CANCELLED" -> InstallState.Cancelled
            "FAILED" -> InstallState.Failed(
                root.requiredString("code"),
                root.requiredString("message"),
            )
            "INSTALLED" -> InstallState.Installed(
                root.requiredString("packageName"),
            )
            else -> error("generated terminal handoff state")
        }
        PendingGeneratedTerminalHandoff(
            token = root.requiredString("token"),
            reviewToken = root.requiredString("reviewToken"),
            sessionId = root.nullableInt("sessionId"),
            state = state,
        ).also {
            require(it.token.isCanonicalUuid())
            require(it.reviewToken.isCanonicalUuid())
            require(it.sessionId == null || it.sessionId >= 0)
        }
    }.getOrNull()

    private fun encodePendingGeneratedCleanup(cleanup: PendingGeneratedCleanup): String {
        require(cleanup.isValid())
        return buildJsonObject {
            put("version", GENERATED_CLEANUP_JOURNAL_VERSION)
            put("token", cleanup.token)
            put(
                "reviewToken",
                cleanup.reviewToken?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("phase", cleanup.phase.name)
            put(
                "destination",
                buildJsonObject {
                    put("kind", cleanup.destination.kind)
                    put(
                        "packageName",
                        cleanup.destination.packageName?.let(::JsonPrimitive) ?: JsonNull,
                    )
                },
            )
            put(
                "installSessionId",
                cleanup.installSessionId?.let(::JsonPrimitive) ?: JsonNull,
            )
            put("autoReplayPending", cleanup.autoReplayPending)
        }.toString()
    }

    private fun decodePendingGeneratedCleanup(encoded: String): PendingGeneratedCleanup? =
        runCatching {
            val root = Json.parseToJsonElement(encoded) as? JsonObject
                ?: error("cleanup journal root")
            require(
                root.keys == GENERATED_CLEANUP_JOURNAL_FIELDS ||
                    root.keys == GENERATED_CLEANUP_JOURNAL_FIELDS +
                    GENERATED_CLEANUP_AUTO_REPLAY_FIELD,
            )
            require(
                root.requiredInt("version") == GENERATED_CLEANUP_JOURNAL_VERSION,
            )
            val destination = root.getValue("destination") as? JsonObject
                ?: error("cleanup destination")
            require(destination.keys == GENERATED_CLEANUP_DESTINATION_FIELDS)
            PendingGeneratedCleanup(
                token = root.requiredString("token"),
                reviewToken = root.nullableString("reviewToken"),
                phase = GeneratedCleanupPhase.valueOf(root.requiredString("phase")),
                destination = requireNotNull(
                    GeneratedCleanupDestination.restore(
                        kind = destination.requiredString("kind"),
                        packageName = destination.nullableString("packageName"),
                    ),
                ),
                installSessionId = root.nullableInt("installSessionId"),
                autoReplayPending =
                    root.optionalBoolean(GENERATED_CLEANUP_AUTO_REPLAY_FIELD),
            ).also { require(it.isValid()) }
        }.getOrNull()

    private fun PendingGeneratedCleanup.isValid(): Boolean =
        token.isCanonicalUuid() &&
            (reviewToken == null || reviewToken.isCanonicalUuid()) &&
            destination.isValid() &&
            when (phase) {
                GeneratedCleanupPhase.INSTALL ->
                    installSessionId == null || installSessionId >= 0
                GeneratedCleanupPhase.REVIEW ->
                    reviewToken != null && installSessionId == null
                GeneratedCleanupPhase.CONVERSION ->
                    installSessionId == null
                GeneratedCleanupPhase.COMMIT_DESTINATION ->
                    reviewToken == null && installSessionId == null
            }

    private fun clearGeneratedInstallOwner() {
        generatedInstallOwnerToken = null
        savedStateHandle[INSTALL_OWNER_CONVERSION_TOKEN_KEY] = null
    }

    private fun clearGeneratedResultOwner() {
        generatedResultOwnerToken = null
        generatedResultReviewToken = null
        savedStateHandle[RESULT_OWNER_CONVERSION_TOKEN_KEY] = null
        savedStateHandle[RESULT_OWNER_REVIEW_TOKEN_KEY] = null
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
        if (mutableState.value.startupRecoveryState != StartupRecoveryUiState.READY) {
            return false
        }
        when (effect) {
            is ManagerEffect.PickSixDocument -> {
                val pending = pendingSixPicker ?: return false
                return pending.ownerGeneration == effect.ownerGeneration &&
                    pending.launchId == effect.launchId &&
                    pending.ownerGeneration == documentOwnerGeneration
            }
            is ManagerEffect.RequestInstallNotificationPermission -> {
                return isInstallPermissionOwnerCurrent(
                    reviewToken = effect.reviewToken,
                    generatedToken = effect.generatedToken,
                )
            }
            is ManagerEffect.PickConversionDocument -> {
                val pending = pendingConversionPicker ?: return false
                return mutableState.value.route == ManagerRoute.ConversionInput &&
                    pending.ownerGeneration == effect.ownerGeneration &&
                    pending.launchId == effect.launchId &&
                    pending.ownerGeneration == documentOwnerGeneration
            }
            is ManagerEffect.CreateConversionDocument -> {
                val pending = pendingConversionSave ?: return false
                val result =
                    mutableState.value.conversionState as? ConversionUiState.ResultReady
                        ?: return false
                return result.token == effect.token &&
                    pending.token == effect.token &&
                    pending.ownerGeneration == effect.ownerGeneration &&
                    pending.launchId == effect.launchId &&
                    pending.ownerGeneration == documentOwnerGeneration
            }
            is ManagerEffect.CreateSystemInstallApkDocument -> {
                val pending = pendingSystemInstallApkDocument ?: return false
                return pending == effect && isCurrentReviewOwner(effect.importToken)
            }
            is ManagerEffect.ShareGeneratedBundle -> {
                val handoff = pendingGeneratedShareHandoff ?: return false
                val result =
                    mutableState.value.conversionState as? ConversionUiState.ResultReady
                        ?: return false
                return result.token == effect.token &&
                    handoff.token == effect.token &&
                    handoff.handoffId == effect.handoffId &&
                    mutableState.value.route == ManagerRoute.ConversionResult(effect.token)
            }
            is ManagerEffect.LaunchIntent -> Unit
        }
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
        val importToken = effect.installImportToken ?: return false
        return repository.installState.value ==
            InstallState.AwaitingSystemInstaller(importToken) &&
            isCurrentReviewOwner(importToken)
    }

    private fun isCurrentReviewOwner(importToken: String): Boolean {
        val ready = mutableState.value.importState as? ImportUiState.ReviewReady ?: return false
        return ready.review.token == importToken &&
            ownedReviewToken == importToken &&
            savedStateHandle.get<String>(REVIEW_TOKEN_KEY) == importToken
    }

    private fun isInstallPermissionOwnerCurrent(
        reviewToken: String,
        generatedToken: String?,
    ): Boolean {
        val ready =
            mutableState.value.importState as? ImportUiState.ReviewReady
                ?: return false
        if (ready.review.token != reviewToken ||
            ownedReviewToken != reviewToken ||
            savedStateHandle.get<String>(REVIEW_TOKEN_KEY) != reviewToken
        ) {
            return false
        }
        if (generatedToken == null) {
            return generatedInstallOwnerToken == null &&
                mutableState.value.route == ManagerRoute.Review(reviewToken)
        }
        val result =
            mutableState.value.conversionState as? ConversionUiState.ResultReady
                ?: return false
        return generatedInstallOwnerToken == generatedToken &&
            result.installOwnerActive &&
            result.token == generatedToken &&
            result.review?.token == reviewToken &&
            mutableState.value.route == ManagerRoute.ConversionResult(generatedToken)
    }

    companion object {
        const val REVIEW_TOKEN_KEY = "review_token"
        const val INSTALL_SESSION_ID_KEY = "install_session_id"
        const val CONVERSION_TOKEN_KEY = "conversion_token"
        const val CONVERSION_ROUTE_KEY = "conversion_route"
        const val INSTALL_OWNER_CONVERSION_TOKEN_KEY = "install_owner_conversion_token"
        const val RESULT_OWNER_CONVERSION_TOKEN_KEY = "result_owner_conversion_token"
        const val RESULT_OWNER_REVIEW_TOKEN_KEY = "result_owner_review_token"
        const val GENERATED_CLEANUP_TOKEN_KEY = "generated_cleanup_token"
        const val GENERATED_CLEANUP_REVIEW_TOKEN_KEY = "generated_cleanup_review_token"
        const val GENERATED_CLEANUP_PHASE_KEY = "generated_cleanup_phase"
        const val GENERATED_CLEANUP_DESTINATION_KEY = "generated_cleanup_destination"
        const val GENERATED_CLEANUP_PACKAGE_KEY = "generated_cleanup_package"
        const val GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY =
            "generated_cleanup_install_session_id"
        const val GENERATED_CLEANUP_JOURNAL_KEY = "generated_cleanup_journal"
        const val GENERATED_TERMINAL_HANDOFF_KEY = "generated_terminal_handoff"
        const val QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY =
            "quarantined_generated_cleanup_token"
        const val QUARANTINED_GENERATED_CLEANUP_REVIEW_TOKEN_KEY =
            "quarantined_generated_cleanup_review_token"
        const val QUARANTINED_GENERATED_CLEANUP_PHASE_KEY =
            "quarantined_generated_cleanup_phase"
        const val QUARANTINED_GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY =
            "quarantined_generated_cleanup_install_session_id"
        const val QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY =
            "quarantined_generated_cleanup_conversion_cleaned"
        const val DOCUMENT_OWNER_GENERATION_KEY = "document_owner_generation"
        const val DOCUMENT_LAUNCH_SEQUENCE_KEY = "document_launch_sequence"
        const val PENDING_SIX_PICKER_OWNER_GENERATION_KEY =
            "pending_six_picker_owner_generation"
        const val PENDING_SIX_PICKER_LAUNCH_ID_KEY = "pending_six_picker_launch_id"
        const val PENDING_PICKER_OWNER_GENERATION_KEY = "pending_picker_owner_generation"
        const val PENDING_PICKER_LAUNCH_ID_KEY = "pending_picker_launch_id"
        const val PENDING_SAVE_TOKEN_KEY = "pending_save_token"
        const val PENDING_SAVE_OWNER_GENERATION_KEY = "pending_save_owner_generation"
        const val PENDING_SAVE_LAUNCH_ID_KEY = "pending_save_launch_id"
        const val PENDING_SYSTEM_APK_TOKEN_KEY = "pending_system_apk_token"
        const val PENDING_SYSTEM_APK_SOURCE_KEY = "pending_system_apk_source"
        const val PENDING_SYSTEM_APK_SHA256_KEY = "pending_system_apk_sha256"
        const val PENDING_SYSTEM_APK_NAME_KEY = "pending_system_apk_name"
        private val CLEANUP_RETRYABLE_FAILURES = setOf(
            "CLEANUP_FAILED",
            "ABANDON_FAILED",
            "SESSION_RECOVERY_FAILED",
            "MISSING_OBSERVATION_RESET_FAILED",
        )
        private const val GENERATED_CLEANUP_JOURNAL_VERSION = 1
        private const val GENERATED_TERMINAL_HANDOFF_VERSION = 1
        private const val STARTUP_INITIALIZE_MAX_ATTEMPTS = 3
        private const val STARTUP_INITIALIZE_RETRY_DELAY_MILLIS = 250L
        private const val GENERATED_CLEANUP_AUTO_REPLAY_FIELD =
            "autoReplayPending"
        private val GENERATED_CLEANUP_JOURNAL_FIELDS = setOf(
            "version",
            "token",
            "reviewToken",
            "phase",
            "destination",
            "installSessionId",
        )
        private val GENERATED_CLEANUP_DESTINATION_FIELDS = setOf(
            "kind",
            "packageName",
        )
        private val GENERATED_CLEANUP_LEGACY_KEYS = setOf(
            GENERATED_CLEANUP_TOKEN_KEY,
            GENERATED_CLEANUP_REVIEW_TOKEN_KEY,
            GENERATED_CLEANUP_PHASE_KEY,
            GENERATED_CLEANUP_DESTINATION_KEY,
            GENERATED_CLEANUP_PACKAGE_KEY,
            GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY,
        )
    }
}

enum class GeneratedCleanupCompletionStep {
    DESTINATION_COMMITTED,
    INSTALL_OWNER_CLEARED,
    RESULT_OWNER_CLEARED,
    REVIEW_SESSION_CLEARED,
    CONVERSION_OWNER_CLEARED,
}

private enum class GeneratedCleanupPhase {
    INSTALL,
    REVIEW,
    CONVERSION,
    COMMIT_DESTINATION,
}

private data class DeferredInitialTerminals(
    val initial: InstallState,
    val latest: InstallState? = null,
) {
    fun candidates(): List<InstallState> =
        if (latest == null || latest == initial) listOf(initial) else listOf(initial, latest)
}

private data class PendingGeneratedCleanup(
    val token: String,
    val reviewToken: String?,
    val phase: GeneratedCleanupPhase,
    val destination: GeneratedCleanupDestination,
    val installSessionId: Int? = null,
    val autoReplayPending: Boolean = false,
)

private data class GeneratedTerminalReceipt(
    val reviewToken: String,
    val sessionId: Int?,
    val state: InstallState,
)

private data class PendingGeneratedTerminalHandoff(
    val token: String,
    val reviewToken: String,
    val sessionId: Int?,
    val state: InstallState,
)

private data class QuarantinedGeneratedCleanup(
    val token: String,
    val reviewToken: String?,
    val phase: GeneratedCleanupPhase,
    val installSessionId: Int?,
    val conversionCleaned: Boolean,
)

private data class PendingGeneratedShareHandoff(
    val token: String,
    val handoffId: Long,
    val completion: CompletableDeferred<Boolean>,
    val job: Job,
)

private data class GeneratedCleanupDestination(
    val kind: String,
    val packageName: String? = null,
) {
    fun toRoute(): ManagerRoute = when (kind) {
        HOME -> ManagerRoute.Home
        INPUT -> ManagerRoute.ConversionInput
        REPORT -> ManagerRoute.Report(requireNotNull(packageName))
        else -> error("지원하지 않는 generated cleanup destination입니다.")
    }

    fun isValid(): Boolean = when (kind) {
        HOME, INPUT -> packageName == null
        REPORT -> packageName?.matches(ANDROID_PACKAGE_NAME) == true
        else -> false
    }

    companion object {
        private const val HOME = "HOME"
        private const val INPUT = "INPUT"
        private const val REPORT = "REPORT"

        fun fromRoute(route: ManagerRoute): GeneratedCleanupDestination = when (route) {
            ManagerRoute.Home -> GeneratedCleanupDestination(HOME)
            ManagerRoute.ConversionInput -> GeneratedCleanupDestination(INPUT)
            is ManagerRoute.Report -> GeneratedCleanupDestination(REPORT, route.packageName)
            else -> error("generated cleanup destination으로 사용할 수 없는 route입니다.")
        }

        fun restore(kind: String?, packageName: String?): GeneratedCleanupDestination? =
            when (kind) {
                HOME -> GeneratedCleanupDestination(HOME)
                INPUT -> GeneratedCleanupDestination(INPUT)
                REPORT -> packageName?.let { GeneratedCleanupDestination(REPORT, it) }
                else -> null
            }
    }
}

private fun JsonObject.requiredString(key: String): String {
    val primitive = getValue(key) as? JsonPrimitive ?: error("string: $key")
    require(primitive.isString)
    return primitive.content
}

private fun JsonObject.nullableString(key: String): String? {
    val value = getValue(key)
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: error("nullable string: $key")
    require(primitive.isString)
    return primitive.content
}

private fun JsonObject.requiredInt(key: String): Int {
    val primitive = getValue(key) as? JsonPrimitive ?: error("int: $key")
    require(!primitive.isString)
    return requireNotNull(primitive.intOrNull)
}

private fun JsonObject.nullableInt(key: String): Int? {
    val value = getValue(key)
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: error("nullable int: $key")
    require(!primitive.isString)
    return requireNotNull(primitive.intOrNull)
}

private fun JsonObject.optionalBoolean(key: String): Boolean {
    val primitive = get(key) as? JsonPrimitive ?: return false
    require(!primitive.isString)
    return primitive.content.toBooleanStrict()
}

private fun String.isCanonicalUuid(): Boolean =
    runCatching { UUID.fromString(this).toString() == this }.getOrDefault(false)

private val ANDROID_PACKAGE_NAME =
    Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")

private fun ConversionBeginResult.AnalysisReady.toUiState() =
    ConversionUiState.AnalysisReady(
        token = token,
        report = report,
        selectedAutomaticAdapters = selectedAutomaticAdapters,
        requiredApprovals = requiredApprovals,
        identityRequirement = identityRequirement,
        profileChanged = profileChanged,
    )

internal fun conversionFailureMessage(code: ConversionFailureCode): UserMessage = when (code) {
    ConversionFailureCode.URL -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "Chrome 웹 스토어 상세 주소 형식이 아닙니다. 주소를 확인하고 다시 분석해 주세요.",
        RecoveryAction.NONE,
    )
    ConversionFailureCode.NETWORK -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "Chrome 확장 다운로드에 실패했습니다. 네트워크 연결을 확인한 뒤 다시 시도해 주세요.",
        RecoveryAction.RETRY,
    )
    ConversionFailureCode.INPUT_INVALID -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "선택한 파일을 확장 입력으로 확인할 수 없습니다. 다른 ZIP 또는 CRX3 파일을 선택해 주세요.",
        RecoveryAction.SELECT_ANOTHER_FILE,
    )
    ConversionFailureCode.INPUT_CLEANUP -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "private 입력 정리를 완료하지 못했습니다. 앱 저장 공간을 확인한 뒤 새 입력을 다시 선택해 주세요.",
        RecoveryAction.NONE,
    )
    ConversionFailureCode.DRAFT_INVALID -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "SixDraft 서명이나 변환 증명이 올바르지 않습니다. 신뢰할 수 있는 초안을 다시 선택해 주세요.",
        RecoveryAction.SELECT_ANOTHER_FILE,
    )
    ConversionFailureCode.BROWSER_MISSING -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "삼성 인터넷이 설치되어 있지 않아 변환할 수 없습니다. 삼성 인터넷을 설치한 뒤 다시 확인해 주세요.",
        RecoveryAction.NONE,
    )
    ConversionFailureCode.PROFILE_UNKNOWN -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "설치된 삼성 인터넷의 변환 계약을 확인하지 못했습니다. 앱을 업데이트한 뒤 다시 검사해 주세요.",
        RecoveryAction.RETRY,
    )
    ConversionFailureCode.PROFILE_CHANGED -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "삼성 인터넷 프로필이 달라져 분석을 새로 실행했습니다. 승인 내용을 다시 검토해 주세요.",
        RecoveryAction.NONE,
    )
    ConversionFailureCode.BLOCKED -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "차단되는 호환성 문제가 있어 이 입력은 변환할 수 없습니다. 이슈를 수정한 파일을 선택해 주세요.",
        RecoveryAction.SELECT_ANOTHER_FILE,
    )
    ConversionFailureCode.UNKNOWN -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "호환성 판단 근거가 부족해 변환을 차단했습니다. 지원 여부가 확인된 입력을 사용해 주세요.",
        RecoveryAction.SELECT_ANOTHER_FILE,
    )
    ConversionFailureCode.APPROVAL_REQUIRED -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "필수 동작 변경 어댑터 승인이 빠졌습니다. 모든 변경 항목을 직접 확인해 주세요.",
        RecoveryAction.NONE,
    )
    ConversionFailureCode.PARTIAL_CONFIRMATION_REQUIRED -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "부분 호환 제한 확인이 필요합니다. 남은 제한을 읽고 별도 확인해 주세요.",
        RecoveryAction.CONFIRM_PARTIAL,
    )
    ConversionFailureCode.TRANSFORM_MISMATCH -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "재검증한 변환 결과가 처음 분석과 달라졌습니다. 현재 입력으로 분석을 다시 시작해 주세요.",
        RecoveryAction.RETRY,
    )
    ConversionFailureCode.KEYSTORE -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "기기 서명 신원을 사용할 수 없습니다. 기기 잠금을 확인한 뒤 변환을 다시 시도해 주세요.",
        RecoveryAction.RETRY,
    )
    ConversionFailureCode.PACKAGE -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "Android 확장 래퍼 생성에 실패했습니다. 입력을 다시 분석한 뒤 재시도해 주세요.",
        RecoveryAction.RETRY,
    )
    ConversionFailureCode.IDENTITY_MISMATCH -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "기존 변환 대상의 확장 ID, package 또는 signer가 일치하지 않습니다. 새 변환 신원을 선택해 주세요.",
        RecoveryAction.NONE,
    )
    ConversionFailureCode.BUNDLE_IO -> UserMessage(
        UserMessageKind.CONVERSION_FAILURE,
        "생성된 .six 검증 또는 읽기에 실패했습니다. 결과를 닫고 변환을 다시 실행해 주세요.",
        RecoveryAction.RETRY,
    )
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

internal fun suggestedWrapperApkName(extensionName: String): String {
    val safeName = extensionName
        .trim()
        .replace(Regex("""[\\/:*?"<>|\p{Cc}]"""), "-")
        .trim('.', ' ')
        .ifBlank { "extension" }
    return "$safeName-wrapper.apk"
}

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
    data class SystemInstallApk(
        val effect: ManagerEffect.CreateSystemInstallApkDocument,
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

private data class RouteOperationTicket(
    val generation: Long,
    val previousOperation: Job?,
)

private data class PendingDocumentLaunch(
    val token: String?,
    val ownerGeneration: Long,
    val launchId: Long,
)

private fun ConversionBeginResult.conversionTokenOrNull(): String? = when (this) {
    is ConversionBeginResult.AnalysisReady -> token
    is ConversionBeginResult.Failure -> token
}
