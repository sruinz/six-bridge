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
            .map(::PlannedAdapter)
        return ConversionPlan(
            report = report,
            selectedAdapters = selected,
        )
    }

    private class PlannedAdapter(
        decision: AdapterDecision,
    ) : SelectedAdapter {
        override val id: String = decision.id
        override val version: Int = decision.version
        override val risk: AdapterRisk = decision.risk
        override val disposition: AdapterDisposition = decision.disposition
        override val applied: Boolean = decision.applied
        override val evidence: List<SourceEvidence> = decision.evidence
        override val changes: List<String> = decision.changes
        override val remainingLimitations: List<String> =
            decision.remainingLimitations
    }
}

data class ConversionPlan(
    val report: CompatibilityReport,
    val selectedAdapters: List<SelectedAdapter>,
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
