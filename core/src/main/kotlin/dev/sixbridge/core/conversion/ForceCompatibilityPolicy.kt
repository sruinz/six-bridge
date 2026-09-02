package dev.sixbridge.core.conversion

import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.contract.bundle.CompatibilityApkBinding
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import java.util.Collections

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

internal fun ForcedCompatibilityProvenance?.deepImmutableCopy(): ForcedCompatibilityProvenance? =
    this?.let {
        ForcedCompatibilityProvenance(
            scope = it.scope,
            originalStatus = it.originalStatus,
            issueCodes = Collections.unmodifiableList(it.issueCodes.toList()),
        )
    }

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
        return when (scope) {
            CompatibilityForceScope.UNKNOWN_API_USAGE ->
                requireUnknownApiUsageProvenance(report)
        }
    }

    fun requireBoundContractProvenance(
        report: ContractCompatibilityReport,
        embeddedCanonicalSha256: String,
    ): ForcedCompatibilityProvenance? {
        BundleJson.validateCompatibility(report)
        require(report.schemaVersion == 6) {
            "서명된 compatibility binding은 schema v6에서만 허용됩니다."
        }
        require(SHA256.matches(embeddedCanonicalSha256)) {
            "compatibility binding SHA-256이 올바르지 않습니다."
        }
        require(
            embeddedCanonicalSha256 == CompatibilityApkBinding.canonicalSha256(report),
        ) {
            "compatibility report와 서명된 binding이 일치하지 않습니다."
        }
        val contractForced = report.forcedCompatibility ?: return null
        val authorization = when (
            CompatibilityForceScope.valueOf(contractForced.scope)
        ) {
            CompatibilityForceScope.UNKNOWN_API_USAGE ->
                ConversionAuthorization.forceUnknownApiUsage()
        }
        val provenance = requireNotNull(
            requireProvenance(
                SixBundle.toCoreCompatibility(report),
                authorization,
            ),
        )
        require(
            provenance.scope.name == contractForced.scope &&
                provenance.originalStatus.name == contractForced.originalStatus.name &&
                provenance.issueCodes == contractForced.issueCodes,
        ) {
            "compatibility force provenance가 중앙 정책 결과와 다릅니다."
        }
        return provenance.deepImmutableCopy()
    }

    private fun requireUnknownApiUsageProvenance(
        report: CompatibilityReport,
    ): ForcedCompatibilityProvenance {
        if (!isEligible(report)) {
            throw ConversionPlanningException(
                ConversionPlanningFailure.INVALID_COMPATIBILITY_AUTHORIZATION,
                emptySet(),
            )
        }
        return ForcedCompatibilityProvenance(
            scope = CompatibilityForceScope.UNKNOWN_API_USAGE,
            originalStatus = report.status,
            issueCodes = report.issues
                .filter { it.blocking }
                .map { it.code }
                .distinct()
                .sorted(),
        )
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
}
