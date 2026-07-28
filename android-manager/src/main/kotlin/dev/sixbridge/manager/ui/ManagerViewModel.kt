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
import dev.sixbridge.manager.conversion.ConversionBeginResult
import dev.sixbridge.manager.conversion.ConversionBuildResult
import dev.sixbridge.manager.conversion.ConversionFailureCode
import dev.sixbridge.manager.conversion.InstallationIdentitySelection
import dev.sixbridge.manager.conversion.OnDeviceInputRequest
import dev.sixbridge.manager.conversion.RestoredConversion
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.uninstall.UninstallOutcome
import dev.sixbridge.manager.GeneratedDocumentSaveResult
import java.io.OutputStream
import java.util.concurrent.CancellationException
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ManagerViewModel(
    private val repository: ManagerRepository,
    private val savedStateHandle: SavedStateHandle,
    private val saveGeneratedDocument: (
        destination: String,
        expectedHash: String,
        copy: (OutputStream) -> String?,
    ) -> GeneratedDocumentSaveResult = { _, _, _ ->
        GeneratedDocumentSaveResult.Failure(cleanupFailed = false)
    },
) : ViewModel() {
    private val mutableState = MutableStateFlow(ManagerUiState())
    private val effectChannel = Channel<ManagerEffect>(Channel.UNLIMITED)
    private var routeOperation: Job? = null
    private var routeCleanup: Job? = null
    private var routeCleanupFailed = false
    private val scheduledConversionCleanupTokens = mutableSetOf<String>()
    private var conversionIoJob: Job? = null
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
    private var generatedInstallOwnerToken: String? =
        savedStateHandle[INSTALL_OWNER_CONVERSION_TOKEN_KEY]
    private var generatedTerminalJob: Job? = null
    private var documentOwnerGeneration =
        savedStateHandle.get<Long>(DOCUMENT_OWNER_GENERATION_KEY) ?: 0L
    private var documentLaunchSequence =
        savedStateHandle.get<Long>(DOCUMENT_LAUNCH_SEQUENCE_KEY) ?: 0L
    private var pendingConversionPicker = restorePendingConversionPicker()
    private var pendingConversionSave = restorePendingConversionSave()

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
            action !is ManagerAction.ConversionFileSelected &&
            action !is ManagerAction.SaveGeneratedConversionTo &&
            action != ManagerAction.ReturnedToForeground
        ) {
            beginNewUserAction()
        }
        when (action) {
            ManagerAction.FilePickerOpened -> Unit
            is ManagerAction.ImportSelected -> startImport { repository.import(action.uri) }
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
            ManagerAction.GeneratedShareFailed -> mutableState.update {
                it.copy(
                    message = UserMessage(
                        UserMessageKind.CONVERSION_SHARE_FAILED,
                        "공유 화면을 열 수 없습니다. 파일로 저장한 뒤 다시 공유해 주세요.",
                        RecoveryAction.NONE,
                    ),
                )
            }
            ManagerAction.FinishConversion -> finishConversion()
            ManagerAction.CancelConversion -> cancelConversion()
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
        if (mutableState.value.conversionState is ConversionUiState.Building &&
            routeCleanup?.isActive != true
        ) {
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
        if (result.installOwnerActive) return
        val review = result.review ?: run {
            mutableState.update {
                it.copy(message = conversionFailureMessage(ConversionFailureCode.BUNDLE_IO))
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
    }

    private fun requestGeneratedSave() {
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady ?: return
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
        conversionIoJob = viewModelScope.launch {
            val saveResult = try {
                saveGeneratedDocument(
                    destination,
                    result.generated.sha256,
                ) { output ->
                    repository.copyGeneratedConversion(result.token, output)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                GeneratedDocumentSaveResult.Failure(cleanupFailed = false)
            }
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
                    },
                )
            }
        }
    }

    private fun shareGenerated() {
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady ?: return
        enqueueEffect(
            ManagerEffect.ShareGeneratedBundle(
                token = result.token,
                file = result.generated.file,
            ),
        )
    }

    private fun cancelConversion() {
        cleanupConversion(ManagerRoute.Home)
    }

    private fun cleanupConversion(destination: ManagerRoute) {
        val stateBeforeCancel = mutableState.value.conversionState
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
        val ticket = replaceRouteOperation()
        val generation = ticket.generation
        val supersededInstall = if (generatedInstallOwned) detachInstallJob() else null
        supersededInstall?.cancel()
        routeOperation = null
        enqueueRouteCleanup {
            ticket.previousOperation?.cancelAndJoin()
            supersededInstall?.cancelAndJoin()
            if (generatedInstallOwned) {
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
            }
            conversionIoJob?.cancelAndJoin()
            conversionIoJob = null
            if (token != null &&
                token !in scheduledConversionCleanupTokens &&
                !repository.discardConversion(token)
            ) {
                showGeneratedCleanupFailure()
                return@enqueueRouteCleanup false
            }
            if (reviewToken != null &&
                token !in scheduledConversionCleanupTokens &&
                !repository.discardReview(reviewToken)
            ) {
                showGeneratedCleanupFailure()
                return@enqueueRouteCleanup false
            }
            if (ownedReviewToken == reviewToken) {
                clearSavedReviewAndSession()
            }
            clearGeneratedInstallOwner()
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
    ) {
        saveConversionRoute(generated.token, "result")
        mutableState.update {
            it.copy(
                route = ManagerRoute.ConversionResult(generated.token),
                conversionState = ConversionUiState.ResultReady(
                    token = generated.token,
                    review = review,
                    generated = generated,
                    failureCode = failureCode,
                    installOwnerActive = installOwnerActive,
                ),
                conversionUrl = "",
                message = if (review == null) {
                    conversionFailureMessage(failureCode ?: ConversionFailureCode.BUNDLE_IO)
                } else {
                    null
                },
            )
        }
    }

    private fun restoreAndInitialize() {
        val generation = operationGeneration
        viewModelScope.launch {
            savedStateHandle["conversion_url"] = null
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
                if (generatedInstallOwnerToken != null) {
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
            } else {
                savedStateHandle[INSTALL_SESSION_ID_KEY] = null
            }
            val deferredTerminal = deferredInitialTerminal
            deferredInitialTerminal = null
            if (restored.review == null && deferredTerminal != null) {
                applyInstallTerminal(deferredTerminal)
            }
            if (restored.review == null || generatedInstallOwnerToken != null) {
                restoreConversion(generation)
            }
            val generatedOwner = generatedInstallOwnerToken
            if (generatedOwner != null) {
                val ready = mutableState.value.conversionState as? ConversionUiState.ResultReady
                if (ready?.token != generatedOwner || restored.review == null) {
                    clearGeneratedInstallOwner()
                    restored.review?.let(::showReview)
                } else if (deferredTerminal != null) {
                    applyInstallTerminal(deferredTerminal)
                }
            }
        }
    }

    private suspend fun restoreConversion(generation: Long) {
        val restored = try {
            repository.restoreConversion()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (generation != operationGeneration) return
        when (restored) {
            is RestoredConversion.Analysis -> showConversionAnalysis(restored.value)
            is RestoredConversion.BundleReady ->
                showConversionResult(restored.review, restored.generated)
            is RestoredConversion.GeneratedPending ->
                showConversionResult(review = null, generated = restored.generated)
            null -> clearSavedConversion()
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
        val ticket = replaceRouteOperation()
        val generation = ticket.generation
        routeOperation = null
        enqueueRouteCleanup {
            ticket.previousOperation?.cancelAndJoin()
            supersededInstall?.cancelAndJoin()
            val cleaned = try {
                cancelInstallForRoute(generation, suppressTerminalOnSuccess = true)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return@enqueueRouteCleanup false
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
        invalidatePendingDocumentLaunches()
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
        clearPendingConversionPicker()
        clearPendingConversionSave()
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
        val generatedOwner = generatedInstallOwnerToken
        if (generatedOwner != null) {
            applyGeneratedInstallTerminal(generatedOwner, installState)
            return
        }
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

    private fun applyGeneratedInstallTerminal(
        token: String,
        installState: InstallState,
    ) {
        val result = mutableState.value.conversionState as? ConversionUiState.ResultReady
        if (result?.token != token) return
        supersedeRecoveryForTerminal()
        when (installState) {
            is InstallState.Installed -> {
                if (generatedTerminalJob?.isActive == true) return
                generatedTerminalJob = viewModelScope.launch {
                    if (!repository.discardConversion(token)) {
                        showGeneratedCleanupFailure()
                        return@launch
                    }
                    val reviewToken = result.review?.token
                    if (reviewToken != null && !repository.discardReview(reviewToken)) {
                        showGeneratedCleanupFailure()
                        return@launch
                    }
                    clearGeneratedInstallOwner()
                    clearSavedReviewAndSession()
                    clearSavedConversion()
                    mutableState.update {
                        it.copy(
                            route = ManagerRoute.Report(installState.packageName),
                            importState = ImportUiState.Idle,
                            conversionState = ConversionUiState.Idle,
                            message = null,
                        )
                    }
                }
            }
            InstallState.Cancelled -> returnGeneratedInstallToResult(
                UserMessage(
                    UserMessageKind.INSTALL_CANCELLED,
                    "시스템 설치가 취소되었습니다. 생성 결과를 저장하거나 다시 설치할 수 있습니다.",
                    RecoveryAction.NONE,
                ),
            )
            is InstallState.Failed -> returnGeneratedInstallToResult(
                installFailureMessage(installState.code),
            )
            else -> Unit
        }
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

    private fun clearGeneratedInstallOwner() {
        generatedInstallOwnerToken = null
        savedStateHandle[INSTALL_OWNER_CONVERSION_TOKEN_KEY] = null
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
        when (effect) {
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
            is ManagerEffect.ShareGeneratedBundle -> {
                val result =
                    mutableState.value.conversionState as? ConversionUiState.ResultReady
                        ?: return false
                return result.token == effect.token &&
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
        val sessionId = effect.installSessionId ?: return false
        return repository.installState.value == InstallState.AwaitingUserApproval(sessionId)
    }

    companion object {
        const val REVIEW_TOKEN_KEY = "review_token"
        const val INSTALL_SESSION_ID_KEY = "install_session_id"
        const val CONVERSION_TOKEN_KEY = "conversion_token"
        const val CONVERSION_ROUTE_KEY = "conversion_route"
        const val INSTALL_OWNER_CONVERSION_TOKEN_KEY = "install_owner_conversion_token"
        const val DOCUMENT_OWNER_GENERATION_KEY = "document_owner_generation"
        const val DOCUMENT_LAUNCH_SEQUENCE_KEY = "document_launch_sequence"
        const val PENDING_PICKER_OWNER_GENERATION_KEY = "pending_picker_owner_generation"
        const val PENDING_PICKER_LAUNCH_ID_KEY = "pending_picker_launch_id"
        const val PENDING_SAVE_TOKEN_KEY = "pending_save_token"
        const val PENDING_SAVE_OWNER_GENERATION_KEY = "pending_save_owner_generation"
        const val PENDING_SAVE_LAUNCH_ID_KEY = "pending_save_launch_id"
        private val CLEANUP_RETRYABLE_FAILURES = setOf(
            "CLEANUP_FAILED",
            "ABANDON_FAILED",
            "SESSION_RECOVERY_FAILED",
            "MISSING_OBSERVATION_RESET_FAILED",
        )
    }
}

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
