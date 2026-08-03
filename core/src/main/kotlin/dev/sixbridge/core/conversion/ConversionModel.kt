package dev.sixbridge.core.conversion

enum class ConversionState {
    PASSTHROUGH,
    AUTOMATIC,
    REQUIRES_APPROVAL,
    CONVERTED,
    UNAVAILABLE,
}

enum class AdapterRisk {
    SAFE,
    BEHAVIOR_CHANGE,
}

enum class AdapterDisposition {
    AUTOMATIC,
    REQUIRES_APPROVAL,
    OPTIONAL_APPROVAL,
    UNAVAILABLE,
}

data class SourceEvidence(
    val relativePath: String,
    val line: Int?,
    val detail: String,
)

data class AdapterDecision(
    val id: String,
    val version: Int,
    val risk: AdapterRisk,
    val disposition: AdapterDisposition,
    val applied: Boolean,
    val evidence: List<SourceEvidence>,
    val changes: List<String>,
    val remainingLimitations: List<String>,
)
