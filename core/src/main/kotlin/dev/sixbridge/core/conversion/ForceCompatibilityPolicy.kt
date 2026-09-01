package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus

enum class CompatibilityForceScope {
    UNKNOWN_API_USAGE,
}

data class ConversionAuthorization(
    val forceCompatibility: CompatibilityForceScope?,
) {
    companion object {
        val NONE = ConversionAuthorization(null)

        fun forceUnknownApiUsage() =
            ConversionAuthorization(CompatibilityForceScope.UNKNOWN_API_USAGE)
    }
}

data class ForcedCompatibilityProvenance(
    val scope: CompatibilityForceScope,
    val originalStatus: CompatibilityStatus,
    val issueCodes: List<String>,
)

object ForceCompatibilityPolicy {
    fun isEligible(report: CompatibilityReport): Boolean = runCatching {
        report.validate()
    }.isSuccess &&
        report.status == CompatibilityStatus.UNKNOWN &&
        report.issues.any { it.blocking } &&
        report.issues.filter { it.blocking }
            .all { it.code == "api_usage_dynamic" } &&
        report.unavailableApis.isEmpty() &&
        report.conversionState != ConversionState.UNAVAILABLE &&
        report.adapterDecisions.none {
            it.disposition == AdapterDisposition.UNAVAILABLE
        } &&
        report.sourceSha256 != null &&
        report.browserProfileSha256 != null

    fun requireProvenance(
        report: CompatibilityReport,
        authorization: ConversionAuthorization,
    ): ForcedCompatibilityProvenance? {
        val scope = authorization.forceCompatibility ?: return null
        if (!isEligible(report)) {
            throw ConversionPlanningException(
                ConversionPlanningFailure.INVALID_COMPATIBILITY_AUTHORIZATION,
                emptySet(),
            )
        }
        return ForcedCompatibilityProvenance(
            scope = scope,
            originalStatus = report.status,
            issueCodes = report.issues
                .filter { it.blocking }
                .map { it.code }
                .distinct()
                .sorted(),
        )
    }
}
