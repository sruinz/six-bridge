package dev.sixbridge.desktop.app

import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisStage
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.desktop.conversion.DesktopAnalysisSession
import dev.sixbridge.desktop.conversion.DesktopConversionEngine
import dev.sixbridge.desktop.conversion.DesktopDraftArtifact
import dev.sixbridge.desktop.conversion.DesktopDraftSaver
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import dev.sixbridge.desktop.profile.DesktopProfileOption
import dev.sixbridge.desktop.ui.DesktopRevealResult
import dev.sixbridge.desktop.ui.DesktopSavedDraftRevealer
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class DesktopFailureStage {
    INPUT,
    ANALYSIS,
    APPROVAL,
    DRAFT,
    SAVE,
    REVEAL,
}

enum class DesktopReviewStep {
    ANALYSIS,
    APPROVAL,
}

sealed interface DesktopConversionState {
    data class Input(
        val urlText: String = "",
        val selectedInput: DesktopExtensionInput? = null,
        val selectedProfile: DesktopProfileOption? = null,
        val message: String? = null,
    ) : DesktopConversionState

    data class Analyzing(
        val generation: Long,
        val stage: AnalysisStage,
    ) : DesktopConversionState

    data class AnalysisReady(
        val session: DesktopAnalysisSession,
        val reviewStep: DesktopReviewStep,
        val selectedAdapters: Set<String>,
        val partialConfirmed: Boolean,
        val projectIdentity: String?,
    ) : DesktopConversionState

    data class BuildingDraft(
        val generation: Long,
        val previous: AnalysisReady,
    ) : DesktopConversionState

    data class DraftReady(
        val artifact: DesktopDraftArtifact,
        val savedPath: Path? = null,
        val isSaving: Boolean = false,
        val message: String? = null,
    ) : DesktopConversionState

    data class Failed(
        val stage: DesktopFailureStage,
        val message: String,
        val previous: DesktopConversionState?,
    ) : DesktopConversionState
}

class DesktopConversionController private constructor(
    private val engine: DesktopConversionEngine,
    private val saver: DesktopDraftSaver,
    private val revealer: DesktopSavedDraftRevealer,
    private val uiDispatcher: CoroutineDispatcher,
    private val analysisTimeoutMillis: Long,
    private val afterAnalysisStageForTesting: (AnalysisStage) -> Unit,
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : AutoCloseable {
    constructor() : this(
        DesktopConversionEngine(),
        DesktopDraftSaver(),
        DesktopSavedDraftRevealer(),
        Dispatchers.Swing,
        ANALYSIS_TIMEOUT_MILLIS,
        {},
        Unit,
    )

    internal constructor(
        engine: DesktopConversionEngine,
        saver: DesktopDraftSaver = DesktopDraftSaver(),
        revealer: DesktopSavedDraftRevealer = DesktopSavedDraftRevealer(),
        uiDispatcher: CoroutineDispatcher = Dispatchers.Swing,
        analysisTimeoutMillis: Long = ANALYSIS_TIMEOUT_MILLIS,
        afterAnalysisStageForTesting: (AnalysisStage) -> Unit = {},
    ) : this(
        engine,
        saver,
        revealer,
        uiDispatcher,
        analysisTimeoutMillis,
        afterAnalysisStageForTesting,
        Unit,
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val generation = AtomicLong()
    private val _state = MutableStateFlow<DesktopConversionState>(DesktopConversionState.Input())
    val state: StateFlow<DesktopConversionState> = _state.asStateFlow()

    private var currentJob: Job? = null
    private var currentOperation: Operation? = null
    private var closed = false

    fun analyze(input: DesktopExtensionInput, profile: BrowserProfile) {
        val oldToken: String?
        val operation: Operation
        val nextGeneration: Long
        synchronized(lock) {
            if (closed) return
            oldToken = tokenOf(_state.value)
            cancelCurrentLocked()
            nextGeneration = generation.incrementAndGet()
            operation = Operation { stage ->
                val applied = updateAnalysisStage(nextGeneration, stage)
                if (applied) afterAnalysisStageForTesting(stage)
            }
            _state.value = DesktopConversionState.Analyzing(
                nextGeneration,
                AnalysisStage.INPUT_VALIDATION,
            )
            currentOperation = operation
            currentJob = scope.launch {
                val completedSession = AtomicReference<DesktopAnalysisSession>()
                try {
                    val session = try {
                        withTimeout(analysisTimeoutMillis) {
                            runInterruptible(Dispatchers.IO) {
                                engine.analyze(input, profile, operation.control)
                                    .also(completedSession::set)
                            }
                        }
                    } catch (error: TimeoutCancellationException) {
                        throw error
                    } catch (error: CancellationException) {
                        completedSession.get() ?: throw error
                    }
                    synchronized(lock) {
                        if (nextGeneration != generation.get() || closed) {
                            releaseAsync(session.token)
                        } else {
                            _state.value = DesktopConversionState.AnalysisReady(
                                session = session,
                                reviewStep = DesktopReviewStep.ANALYSIS,
                                selectedAdapters = emptySet(),
                                partialConfirmed = false,
                                projectIdentity = null,
                            )
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    completedSession.get()?.let { releaseAsync(it.token) }
                    failAnalysisTimeoutIfCurrent(
                        nextGeneration,
                        DesktopConversionState.Input(selectedInput = input),
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Error) {
                    throw error
                } catch (error: Exception) {
                    if (!isExpectedFailure(error)) throw error
                    failIfCurrent(nextGeneration, DesktopFailureStage.ANALYSIS, error, DesktopConversionState.Input(selectedInput = input))
                } finally {
                    clearCurrent(nextGeneration, operation)
                }
            }
        }
        oldToken?.let(::releaseAsync)
    }

    fun continueToApproval() {
        synchronized(lock) {
            val ready = approvalReady(_state.value) ?: return
            if (ready.session.report.status !in setOf(CompatibilityStatus.COMPATIBLE, CompatibilityStatus.PARTIAL)) return
            _state.value = ready.copy(reviewStep = DesktopReviewStep.APPROVAL)
        }
    }

    fun toggleAdapter(id: String) {
        synchronized(lock) {
            val ready = approvalReady(_state.value) ?: return
            if (id !in ready.session.requiredApprovals) return
            _state.value = ready.copy(
                selectedAdapters = ready.selectedAdapters.toMutableSet().also {
                    if (!it.add(id)) it.remove(id)
                },
            )
        }
    }

    fun setPartialConfirmed(confirmed: Boolean) {
        synchronized(lock) {
            val ready = approvalReady(_state.value) ?: return
            _state.value = ready.copy(partialConfirmed = confirmed)
        }
    }

    fun selectProjectIdentity(value: String) {
        synchronized(lock) {
            val ready = approvalReady(_state.value) ?: return
            if (ready.session.logicalSourceIdentity !is LogicalSourceIdentityResult.NeedsUserSelection) return
            if (!PROJECT_IDENTITY.matches(value)) return
            _state.value = ready.copy(projectIdentity = value)
        }
    }

    fun back() {
        val returnToInput: Boolean
        synchronized(lock) {
            val ready = approvalReady(_state.value)
            if (ready?.reviewStep == DesktopReviewStep.APPROVAL) {
                _state.value = ready.copy(reviewStep = DesktopReviewStep.ANALYSIS)
                return
            }
            returnToInput = true
        }
        if (returnToInput) replaceWithInput(release = true)
    }

    fun buildDraft() {
        val ready: DesktopConversionState.AnalysisReady
        val operation = Operation()
        val nextGeneration: Long
        synchronized(lock) {
            ready = approvalReady(_state.value) ?: return
            if (ready.reviewStep != DesktopReviewStep.APPROVAL) return
            if (!ready.selectedAdapters.containsAll(ready.session.requiredApprovals)) {
                _state.value = DesktopConversionState.Failed(DesktopFailureStage.APPROVAL, "필수 adapter 승인이 필요합니다.", ready)
                return
            }
            if (ready.session.report.status == CompatibilityStatus.PARTIAL && !ready.partialConfirmed) {
                _state.value = DesktopConversionState.Failed(DesktopFailureStage.APPROVAL, "부분 호환 결과를 확인해야 합니다.", ready)
                return
            }
            val requiresProjectIdentity =
                ready.session.logicalSourceIdentity is LogicalSourceIdentityResult.NeedsUserSelection
            if (requiresProjectIdentity && ready.projectIdentity?.matches(PROJECT_IDENTITY) != true) {
                _state.value = DesktopConversionState.Failed(
                    DesktopFailureStage.APPROVAL,
                    "project identity를 생성하거나 이전 변환 값을 입력하세요.",
                    ready,
                )
                return
            }
            val projectIdentity = ready.projectIdentity.takeIf { requiresProjectIdentity }
            nextGeneration = generation.incrementAndGet()
            _state.value = DesktopConversionState.BuildingDraft(nextGeneration, ready)
            currentOperation = operation
            currentJob = scope.launch {
                try {
                    val completedArtifact = AtomicReference<DesktopDraftArtifact>()
                    val artifact = try {
                        runInterruptible(Dispatchers.IO) {
                            engine.buildDraft(
                                ready.session,
                                ready.selectedAdapters,
                                projectIdentity,
                                operation.control,
                            ).also(completedArtifact::set)
                        }
                    } catch (error: CancellationException) {
                        completedArtifact.get() ?: throw error
                    }
                    synchronized(lock) {
                        if (nextGeneration != generation.get() || closed) {
                            releaseAsync(artifact.token)
                        } else {
                            _state.value = DesktopConversionState.DraftReady(artifact)
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Error) {
                    throw error
                } catch (error: Exception) {
                    if (!isExpectedFailure(error)) throw error
                    failIfCurrent(nextGeneration, DesktopFailureStage.DRAFT, error, ready)
                } finally {
                    clearCurrent(nextGeneration, operation)
                }
            }
        }
    }

    fun saveDraft(destination: Path) {
        val draft: DesktopConversionState.DraftReady
        val operation = Operation()
        val nextGeneration: Long
        synchronized(lock) {
            if (currentJob?.isActive == true) return
            draft = savedDraftForRetry(_state.value) ?: return
            nextGeneration = generation.incrementAndGet()
            _state.value = draft.copy(isSaving = true, message = null)
            currentOperation = operation
            currentJob = scope.launch {
                try {
                    runInterruptible(Dispatchers.IO) { saver.save(draft.artifact, destination) }
                    synchronized(lock) {
                        if (nextGeneration == generation.get() && !closed) {
                            _state.value = draft.copy(
                                savedPath = destination.toAbsolutePath().normalize(),
                                isSaving = false,
                                message = null,
                            )
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Error) {
                    throw error
                } catch (error: Exception) {
                    if (!isExpectedFailure(error)) throw error
                    failSaveIfCurrent(nextGeneration, operation, error, draft)
                } finally {
                    clearCurrent(nextGeneration, operation)
                }
            }
        }
    }

    fun revealSavedDraft() {
        val draft: DesktopConversionState.DraftReady
        val savedPath: Path
        val operation = Operation()
        val nextGeneration: Long
        synchronized(lock) {
            if (closed || currentJob?.isActive == true) return
            draft = savedDraftForRetry(_state.value) ?: return
            savedPath = draft.savedPath ?: return
            nextGeneration = generation.incrementAndGet()
            currentOperation = operation
            currentJob = scope.launch {
                try {
                    val result = runInterruptible(Dispatchers.IO) {
                        revealer.reveal(savedPath)
                    }
                    withContext(uiDispatcher) {
                        if (result is DesktopRevealResult.Failed) {
                            synchronized(lock) {
                                val current = savedDraftForRetry(_state.value)
                                if (
                                    nextGeneration == generation.get() &&
                                    !closed &&
                                    current?.artifact == draft.artifact &&
                                    current.savedPath == savedPath
                                ) {
                                    _state.value = DesktopConversionState.Failed(
                                        DesktopFailureStage.REVEAL,
                                        result.message,
                                        draft.copy(isSaving = false),
                                    )
                                }
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Error) {
                    throw error
                } finally {
                    clearCurrent(nextGeneration, operation)
                }
            }
        }
    }

    fun isBusy(): Boolean = synchronized(lock) {
        !closed && (
            currentOperation != null ||
                _state.value is DesktopConversionState.Analyzing ||
                _state.value is DesktopConversionState.BuildingDraft
            )
    }

    fun cancel() = replaceWithInput(release = true)

    fun reset() = replaceWithInput(release = true)

    override fun close() {
        val shouldClose: Boolean
        synchronized(lock) {
            shouldClose = !closed
            if (!shouldClose) return
            closed = true
        }
        replaceWithInput(release = true)
        scope.cancel()
        engine.close()
    }

    private fun replaceWithInput(release: Boolean) {
        val token: String?
        synchronized(lock) {
            token = tokenOf(_state.value)
            generation.incrementAndGet()
            cancelCurrentLocked()
            _state.value = DesktopConversionState.Input(selectedInput = inputOf(_state.value))
        }
        if (release) token?.let(::releaseAsync)
    }

    private fun cancelCurrentLocked() {
        currentOperation?.cancel()
        currentJob?.cancel()
        currentOperation = null
        currentJob = null
    }

    private fun clearCurrent(expectedGeneration: Long, operation: Operation) {
        synchronized(lock) {
            if (generation.get() == expectedGeneration && currentOperation === operation) {
                currentOperation = null
                currentJob = null
            }
        }
    }

    private fun updateAnalysisStage(
        expectedGeneration: Long,
        stage: AnalysisStage,
    ): Boolean = synchronized(lock) {
        if (generation.get() != expectedGeneration || closed) return@synchronized false
        val current = _state.value as? DesktopConversionState.Analyzing
            ?: return@synchronized false
        _state.value = current.copy(stage = stage)
        true
    }

    private fun failAnalysisTimeoutIfCurrent(
        expectedGeneration: Long,
        previous: DesktopConversionState,
    ) {
        synchronized(lock) {
            if (generation.get() == expectedGeneration && !closed) {
                _state.value = DesktopConversionState.Failed(
                    DesktopFailureStage.ANALYSIS,
                    "분석 제한 시간 5분을 초과했습니다.",
                    previous,
                )
            }
        }
    }

    private fun failIfCurrent(
        expectedGeneration: Long,
        stage: DesktopFailureStage,
        error: Exception,
        previous: DesktopConversionState,
    ) {
        synchronized(lock) {
            if (generation.get() == expectedGeneration && !closed) {
                _state.value = DesktopConversionState.Failed(stage, failureMessage(error), previous)
            }
        }
    }

    private fun failSaveIfCurrent(
        expectedGeneration: Long,
        operation: Operation,
        error: Exception,
        previous: DesktopConversionState.DraftReady,
    ) {
        synchronized(lock) {
            if (generation.get() == expectedGeneration && !closed && currentOperation === operation) {
                currentOperation = null
                currentJob = null
                _state.value = DesktopConversionState.Failed(
                    DesktopFailureStage.SAVE,
                    failureMessage(error),
                    previous.copy(isSaving = false),
                )
            }
        }
    }

    private fun releaseAsync(token: String) {
        scope.launch {
            runInterruptible(Dispatchers.IO) { engine.release(token) }
        }
    }

    private fun savedDraftForRetry(state: DesktopConversionState): DesktopConversionState.DraftReady? = when (state) {
        is DesktopConversionState.DraftReady -> state
        is DesktopConversionState.Failed -> state.previous as? DesktopConversionState.DraftReady
        else -> null
    }

    private fun approvalReady(state: DesktopConversionState): DesktopConversionState.AnalysisReady? = when (state) {
        is DesktopConversionState.AnalysisReady -> state
        is DesktopConversionState.Failed -> if (state.stage == DesktopFailureStage.APPROVAL) {
            state.previous as? DesktopConversionState.AnalysisReady
        } else {
            null
        }
        else -> null
    }

    private fun tokenOf(state: DesktopConversionState?): String? = when (state) {
        is DesktopConversionState.AnalysisReady -> state.session.token
        is DesktopConversionState.BuildingDraft -> state.previous.session.token
        is DesktopConversionState.DraftReady -> state.artifact.token
        is DesktopConversionState.Failed -> tokenOf(state.previous)
        else -> null
    }

    private fun inputOf(state: DesktopConversionState?): DesktopExtensionInput? = when (state) {
        is DesktopConversionState.AnalysisReady -> state.session.input
        is DesktopConversionState.BuildingDraft -> state.previous.session.input
        is DesktopConversionState.Failed -> inputOf(state.previous)
        else -> null
    }

    private fun failureMessage(error: Exception): String = when (error) {
        is IOException -> "파일 또는 네트워크 처리에 실패했습니다."
        is IllegalArgumentException -> "입력값이 올바르지 않습니다."
        is IllegalStateException -> "변환 상태 또는 저장 위치를 확인하세요."
        is SecurityException -> "파일 접근 권한을 확인하세요."
        else -> "변환 중 예상하지 못한 오류가 발생했습니다."
    }

    private fun isExpectedFailure(error: Exception): Boolean = error is IOException ||
        error is IllegalArgumentException ||
        error is IllegalStateException ||
        error is SecurityException

    private class Operation(
        onStage: (AnalysisStage) -> Unit = {},
    ) {
        private val cancelled = AtomicBoolean()
        val control = AnalysisControl(
            checkpoint = {
                if (cancelled.get() || Thread.currentThread().isInterrupted) {
                    throw CancellationException("사용자가 변환을 취소했습니다.")
                }
            },
            onStage = onStage,
        )

        fun cancel() {
            cancelled.set(true)
        }
    }

    private companion object {
        const val ANALYSIS_TIMEOUT_MILLIS = 300_000L
        val PROJECT_IDENTITY = Regex("[0-9a-f]{32}")
    }
}
