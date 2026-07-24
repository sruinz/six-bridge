package dev.sixbridge.core.compat

import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.ConversionState

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
    val conversionState: ConversionState = ConversionState.PASSTHROUGH,
    val adapterDecisions: List<AdapterDecision> = emptyList(),
    val originalExtensionId: String? = null,
    val convertedExtensionId: String? = null,
    val sourceSha256: String? = null,
    val browserProfileSha256: String? = null,
) {
    fun validate() {
        require(adapterDecisions.map { it.id to it.version }.toSet().size == adapterDecisions.size) {
            "어댑터 결정 ID와 version 조합은 중복될 수 없습니다."
        }
        require(sourceSha256 == null || SHA256.matches(sourceSha256)) {
            "sourceSha256은 SHA-256 형식이어야 합니다."
        }
        require(
            browserProfileSha256 == null ||
                SHA256.matches(browserProfileSha256),
        ) {
            "browserProfileSha256은 SHA-256 형식이어야 합니다."
        }
    }

    private companion object {
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
