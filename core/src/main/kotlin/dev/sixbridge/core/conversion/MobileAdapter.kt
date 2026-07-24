package dev.sixbridge.core.conversion

import java.nio.file.Path

interface MobileAdapter {
    val id: String
    val version: Int

    fun apply(workingRoot: Path, selected: SelectedAdapter): AdapterDecision
}

class SelectedAdapter internal constructor(
    internal val decision: AdapterDecision,
) {
    val id: String get() = decision.id
    val version: Int get() = decision.version
    val risk: AdapterRisk get() = decision.risk
    val disposition: AdapterDisposition get() = decision.disposition
    val applied: Boolean get() = decision.applied
    val evidence: List<SourceEvidence> get() = decision.evidence
    val changes: List<String> get() = decision.changes
    val remainingLimitations: List<String> get() = decision.remainingLimitations
}
