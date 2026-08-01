package dev.sixbridge.desktop.app

import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.desktop.conversion.DesktopAnalysisSession
import dev.sixbridge.desktop.conversion.DesktopConversionEngine
import dev.sixbridge.desktop.conversion.DesktopDraftArtifact
import dev.sixbridge.desktop.conversion.DesktopDraftSaver
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import dev.sixbridge.desktop.profile.DesktopProfileOption
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    data class Analyzing(val generation: Long) : DesktopConversionState

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
    @Suppress("UNUSED_PARAMETER") marker: Unit,
) : AutoCloseable {
    constructor() : this(DesktopConversionEngine(), DesktopDraftSaver(), Unit)

    internal constructor(
        engine: DesktopConversionEngine,
        saver: DesktopDraftSaver = DesktopDraftSaver(),
    ) : this(engine, saver, Unit)

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
        val operation = Operation()
        val nextGeneration: Long
        synchronized(lock) {
            if (closed) return
            oldToken = tokenOf(_state.value)
            cancelCurrentLocked()
            nextGeneration = generation.incrementAndGet()
            _state.value = DesktopConversionState.Analyzing(nextGeneration)
            currentOperation = operation
            currentJob = scope.launch {
                try {
                    val completedSession = AtomicReference<DesktopAnalysisSession>()
                    val session = try {
                        runInterruptible(Dispatchers.IO) {
                            engine.analyze(input, profile, operation.control).also(completedSession::set)
                        }
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
            _state.value = ready.copy(projectIdentity = value)
        }
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
                                ready.projectIdentity,
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
            currentOperation = operation
            currentJob = scope.launch {
                try {
                    runInterruptible(Dispatchers.IO) { saver.save(draft.artifact, destination) }
                    synchronized(lock) {
                        if (nextGeneration == generation.get() && !closed) {
                            _state.value = draft.copy(savedPath = destination.toAbsolutePath().normalize())
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
                    previous,
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
        is IllegalArgumentException -> error.message ?: "입력값이 올바르지 않습니다."
        is IllegalStateException -> error.message ?: "변환 상태가 올바르지 않습니다."
        else -> "변환 중 예상하지 못한 오류가 발생했습니다."
    }

    private fun isExpectedFailure(error: Exception): Boolean = error is IOException ||
        error is IllegalArgumentException ||
        error is IllegalStateException ||
        error is SecurityException

    private class Operation {
        private val cancelled = AtomicBoolean()
        val control = AnalysisControl {
            if (cancelled.get() || Thread.currentThread().isInterrupted) {
                throw CancellationException("사용자가 변환을 취소했습니다.")
            }
        }

        fun cancel() {
            cancelled.set(true)
        }
    }
}
