package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityReport
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Collections

class ConversionPlanner {
    fun plan(
        report: CompatibilityReport,
        approvedAdapterIds: Set<String>,
    ): ConversionPlan {
        val immutableReport = report.immutableCopy()
            .also(CompatibilityReport::validate)
        val approvals = approvedAdapterIds.toSet()
        requireNotNull(immutableReport.sourceSha256) {
            "분석 report에 source provenance가 없습니다."
        }
        requireNotNull(immutableReport.browserProfileSha256) {
            "분석 report에 browser profile provenance가 없습니다."
        }
        val unknown = approvals - MobilePatternAnalyzer.ADAPTER_IDS
        if (unknown.isNotEmpty()) {
            throw ConversionPlanningException(
                ConversionPlanningFailure.UNKNOWN_APPROVAL,
                unknown,
            )
        }
        val detectedIds = immutableReport.adapterDecisions
            .mapTo(mutableSetOf()) { it.id }
        val undetected = approvals - detectedIds
        if (undetected.isNotEmpty()) {
            throw ConversionPlanningException(
                ConversionPlanningFailure.UNDETECTED_APPROVAL,
                undetected,
            )
        }
        val missing = immutableReport.adapterDecisions
            .filter {
                it.disposition == AdapterDisposition.REQUIRES_APPROVAL &&
                    it.id !in approvals
            }
            .mapTo(sortedSetOf()) { it.id }
        if (missing.isNotEmpty()) {
            throw ConversionPlanningException(
                ConversionPlanningFailure.MISSING_REQUIRED_APPROVAL,
                missing,
            )
        }
        val selected = immutableReport.adapterDecisions
            .filter {
                it.disposition == AdapterDisposition.AUTOMATIC ||
                    (
                        it.disposition == AdapterDisposition.REQUIRES_APPROVAL &&
                            it.id in approvals
                        )
            }
            .sortedWith(compareBy(AdapterDecision::id, AdapterDecision::version))
        val immutableSelected = Collections.unmodifiableList(
            selected.map(::PlannedAdapter),
        )
        return PlannerIssuedPlan(
            report = immutableReport,
            selectedAdapters = immutableSelected,
            proofSha256 = ConversionPlanProof.digest(
                immutableReport,
                immutableSelected,
            ),
        )
    }

    private class PlannerIssuedPlan(
        override val report: CompatibilityReport,
        override val selectedAdapters: List<SelectedAdapter>,
        override val proofSha256: String,
    ) : ConversionPlan

    private class PlannedAdapter(
        decision: AdapterDecision,
    ) : SelectedAdapter {
        override val id: String = decision.id
        override val version: Int = decision.version
        override val risk: AdapterRisk = decision.risk
        override val disposition: AdapterDisposition = decision.disposition
        override val applied: Boolean = decision.applied
        override val evidence: List<SourceEvidence> =
            Collections.unmodifiableList(decision.evidence.map(SourceEvidence::copy))
        override val changes: List<String> =
            Collections.unmodifiableList(decision.changes.toList())
        override val remainingLimitations: List<String> =
            Collections.unmodifiableList(decision.remainingLimitations.toList())
    }
}

sealed interface ConversionPlan {
    val report: CompatibilityReport
    val selectedAdapters: List<SelectedAdapter>
    val proofSha256: String
}

internal object ConversionPlanProof {
    fun digest(
        report: CompatibilityReport,
        selectedAdapters: List<SelectedAdapter>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.field(report.status.name)
        digest.field(report.manifestVersion?.toString().orEmpty())
        digest.field(report.extensionName.orEmpty())
        digest.field(report.extensionVersion.orEmpty())
        digest.field(report.manifestKeyPresent.toString())
        digest.field(report.manifestKeyExtensionId.orEmpty())
        digest.field(report.declaredPermissions.sorted().joinToString("\u0000"))
        digest.field(report.usedApis.sorted().joinToString("\u0000"))
        digest.field(report.unsupportedApis.sorted().joinToString("\u0000"))
        report.issues.forEach { issue ->
            digest.field(issue.code)
            digest.field(issue.message)
            digest.field(issue.blocking.toString())
        }
        digest.field(report.conversionState.name)
        report.adapterDecisions.forEach { digest.decision(it) }
        digest.field(report.originalExtensionId.orEmpty())
        digest.field(report.convertedExtensionId.orEmpty())
        digest.field(report.sourceSha256.orEmpty())
        digest.field(report.browserProfileSha256.orEmpty())
        selectedAdapters.forEach { digest.decision(it.toDecision()) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun MessageDigest.decision(decision: AdapterDecision) {
        field(decision.id)
        field(decision.version.toString())
        field(decision.risk.name)
        field(decision.disposition.name)
        field(decision.applied.toString())
        decision.evidence.forEach { evidence ->
            field(evidence.relativePath)
            field(evidence.line?.toString().orEmpty())
            field(evidence.detail)
        }
        decision.changes.forEach { field(it) }
        decision.remainingLimitations.forEach { field(it) }
    }

    private fun MessageDigest.field(value: String) {
        val bytes = value.toByteArray(UTF_8)
        update(
            ByteBuffer.allocate(Int.SIZE_BYTES)
                .putInt(bytes.size)
                .array(),
        )
        update(bytes)
    }
}

private fun CompatibilityReport.immutableCopy(): CompatibilityReport = copy(
    declaredPermissions = Collections.unmodifiableSet(declaredPermissions.toSortedSet()),
    usedApis = Collections.unmodifiableSet(usedApis.toSortedSet()),
    unsupportedApis = Collections.unmodifiableSet(unsupportedApis.toSortedSet()),
    issues = Collections.unmodifiableList(issues.map { it.copy() }),
    adapterDecisions = Collections.unmodifiableList(
        adapterDecisions.map { decision ->
            decision.copy(
                evidence = Collections.unmodifiableList(
                    decision.evidence.map(SourceEvidence::copy),
                ),
                changes = Collections.unmodifiableList(decision.changes.toList()),
                remainingLimitations = Collections.unmodifiableList(
                    decision.remainingLimitations.toList(),
                ),
            )
        },
    ),
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
