package dev.sixbridge.core.conversion

import java.nio.file.Path

interface MobileAdapter {
    val id: String
    val version: Int

    fun apply(workingRoot: Path, selected: SelectedAdapter): AdapterDecision
}

sealed interface SelectedAdapter {
    val id: String
    val version: Int
    val risk: AdapterRisk
    val disposition: AdapterDisposition
    val applied: Boolean
    val evidence: List<SourceEvidence>
    val changes: List<String>
    val remainingLimitations: List<String>
}

internal fun SelectedAdapter.toDecision(): AdapterDecision = AdapterDecision(
    id = id,
    version = version,
    risk = risk,
    disposition = disposition,
    applied = applied,
    evidence = evidence,
    changes = changes,
    remainingLimitations = remainingLimitations,
)
