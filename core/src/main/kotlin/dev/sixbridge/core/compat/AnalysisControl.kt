package dev.sixbridge.core.compat

class AnalysisControl(
    val policy: AnalysisSafetyPolicy = AnalysisSafetyPolicy.desktop,
    private val checkpoint: () -> Unit = {},
    private val onStage: (AnalysisStage) -> Unit = {},
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit = Unit,
) {
    private var currentStage: AnalysisStage? = null

    constructor(checkpoint: () -> Unit) : this(
        AnalysisSafetyPolicy.desktop,
        checkpoint,
        {},
        Unit,
    )

    constructor(
        policy: AnalysisSafetyPolicy,
        checkpoint: () -> Unit,
    ) : this(policy, checkpoint, {}, Unit)

    fun checkCancelled() = checkpoint()

    @Synchronized
    fun stage(value: AnalysisStage) {
        if (currentStage == value) return
        currentStage = value
        onStage(value)
    }
}
