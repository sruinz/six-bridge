package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityReport

class ConversionPlanner(
    private val registryAdapterIds: Set<String> = MobilePatternAnalyzer.ADAPTER_IDS,
) {
    fun plan(
        report: CompatibilityReport,
        approvedAdapterIds: Set<String>,
    ): ConversionPlan {
        val unknown = approvedAdapterIds - registryAdapterIds
        if (unknown.isNotEmpty()) {
            throw ConversionPlanningException(
                ConversionPlanningFailure.UNKNOWN_APPROVAL,
                unknown,
            )
        }
        val detectedIds = report.adapterDecisions.mapTo(mutableSetOf()) { it.id }
        val undetected = approvedAdapterIds - detectedIds
        if (undetected.isNotEmpty()) {
            throw ConversionPlanningException(
                ConversionPlanningFailure.UNDETECTED_APPROVAL,
                undetected,
            )
        }
        val missing = report.adapterDecisions
            .filter {
                it.disposition == AdapterDisposition.REQUIRES_APPROVAL &&
                    it.id !in approvedAdapterIds
            }
            .mapTo(sortedSetOf()) { it.id }
        if (missing.isNotEmpty()) {
            throw ConversionPlanningException(
                ConversionPlanningFailure.MISSING_REQUIRED_APPROVAL,
                missing,
            )
        }
        val selected = report.adapterDecisions
            .filter {
                it.disposition == AdapterDisposition.AUTOMATIC ||
                    (
                        it.disposition == AdapterDisposition.REQUIRES_APPROVAL &&
                            it.id in approvedAdapterIds
                        )
            }
            .sortedWith(compareBy(AdapterDecision::id, AdapterDecision::version))
        return ConversionPlan(
            report = report,
            selectedAdapters = selected,
        )
    }
}

data class ConversionPlan(
    val report: CompatibilityReport,
    val selectedAdapters: List<AdapterDecision>,
)

enum class ConversionPlanningFailure {
    MISSING_REQUIRED_APPROVAL,
    UNKNOWN_APPROVAL,
    UNDETECTED_APPROVAL,
}

class ConversionPlanningException(
    val failure: ConversionPlanningFailure,
    adapterIds: Set<String>,
) : IllegalArgumentException(
    "${failure.name}: ${adapterIds.sorted().joinToString(",")}",
) {
    val adapterIds: Set<String> = adapterIds.toSortedSet()
}
