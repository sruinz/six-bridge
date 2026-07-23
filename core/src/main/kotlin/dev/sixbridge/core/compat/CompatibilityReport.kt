package dev.sixbridge.core.compat

enum class CompatibilityStatus {
    COMPATIBLE,
    PARTIAL,
    BLOCKED,
    UNKNOWN,
}

data class CompatibilityIssue(
    val code: String,
    val message: String,
    val blocking: Boolean,
)

data class CompatibilityReport(
    val status: CompatibilityStatus,
    val manifestVersion: Int?,
    val extensionName: String?,
    val extensionVersion: String?,
    val manifestKeyPresent: Boolean = false,
    val manifestKeyExtensionId: String? = null,
    val declaredPermissions: Set<String>,
    val usedApis: Set<String>,
    val unsupportedApis: Set<String>,
    val issues: List<CompatibilityIssue>,
)
