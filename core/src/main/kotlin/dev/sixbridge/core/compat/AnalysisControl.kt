package dev.sixbridge.core.compat

import kotlin.jvm.JvmSynthetic

class AnalysisControl(
    val policy: AnalysisSafetyPolicy = AnalysisSafetyPolicy.desktop,
    private val checkpoint: () -> Unit = {},
) {
    private var onStage: (AnalysisStage) -> Unit = {}
    private var currentStage: AnalysisStage? = null

    fun checkCancelled() = checkpoint()

    @Synchronized
    fun stage(value: AnalysisStage) {
        val current = currentStage
        if (current != null && value.ordinal <= current.ordinal) return
        currentStage = value
        onStage(value)
    }

    @Synchronized
    @JvmSynthetic
    internal fun beginOperation() {
        currentStage = null
    }

    @Synchronized
    @JvmSynthetic
    internal fun reportStagesTo(observer: (AnalysisStage) -> Unit): AnalysisControl {
        onStage = observer
        return this
    }
}

@Suppress("FunctionName")
@JvmSynthetic
fun AnalysisControl(
    onStage: (AnalysisStage) -> Unit,
    policy: AnalysisSafetyPolicy = AnalysisSafetyPolicy.desktop,
    checkpoint: () -> Unit = {},
): AnalysisControl = AnalysisControl(policy, checkpoint).reportStagesTo(onStage)
