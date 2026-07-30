package dev.sixbridge.core.compat

class AnalysisControl(
    val policy: AnalysisSafetyPolicy = AnalysisSafetyPolicy.desktop,
    private val checkpoint: () -> Unit = {},
) {
    fun checkCancelled() = checkpoint()
}
