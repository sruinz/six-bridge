package dev.sixbridge.core.compat

data class AnalysisSafetyPolicy(
    val maxManifestBytes: Long,
    val maxSingleScriptBytes: Long,
    val maxTotalScriptBytes: Long,
    val maxScriptFiles: Int,
) {
    companion object {
        val mobile = AnalysisSafetyPolicy(
            maxManifestBytes = 1L * 1024 * 1024,
            maxSingleScriptBytes = 2L * 1024 * 1024,
            maxTotalScriptBytes = 16L * 1024 * 1024,
            maxScriptFiles = 2_048,
        )
        val desktop = AnalysisSafetyPolicy(
            maxManifestBytes = 1L * 1024 * 1024,
            maxSingleScriptBytes = 128L * 1024 * 1024,
            maxTotalScriptBytes = 512L * 1024 * 1024,
            maxScriptFiles = 10_000,
        )
    }
}
