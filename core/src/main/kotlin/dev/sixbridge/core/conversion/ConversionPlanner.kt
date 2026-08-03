package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Collections

class ConversionPlanner {
    fun plan(
        report: CompatibilityReport,
        approvedAdapterIds: Set<String>,
    ): ConversionPlan {
        val immutableReport = report.deepImmutableCopy()
            .also(CompatibilityReport::validate)
        val approvals = approvedAdapterIds.toSet()
        if (
            immutableReport.status == CompatibilityStatus.BLOCKED ||
            immutableReport.status == CompatibilityStatus.UNKNOWN
        ) {
            throw ConversionPlanningException(
                ConversionPlanningFailure.INELIGIBLE_COMPATIBILITY_STATUS,
                emptySet(),
            )
        }
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
                        ) ||
                    (
                        it.disposition == AdapterDisposition.OPTIONAL_APPROVAL &&
                            it.id in approvals
                        )
            }
            .sortedWith(compareBy(AdapterDecision::id, AdapterDecision::version))
        val optionalPresent = immutableReport.adapterDecisions.any {
            it.disposition == AdapterDisposition.OPTIONAL_APPROVAL
        }
        if (
            immutableReport.adapterDecisions.isNotEmpty() &&
            selected.isEmpty() &&
            !optionalPresent
        ) {
            throw ConversionPlanningException(
                ConversionPlanningFailure.NO_APPLICABLE_ADAPTER,
                emptySet(),
            )
        }
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
    fun digestDecisions(
        report: CompatibilityReport,
        selectedAdapters: List<AdapterDecision>,
    ): String = digestInternal(report, selectedAdapters)

    fun digest(
        report: CompatibilityReport,
        selectedAdapters: List<SelectedAdapter>,
    ): String = digestInternal(report, selectedAdapters.map(SelectedAdapter::toDecision))

    private fun digestInternal(
        report: CompatibilityReport,
        selectedAdapters: List<AdapterDecision>,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.field("schema", "conversion-plan-proof-v2")
        digest.field("report.status", report.status.name)
        digest.nullableField(
            "report.manifestVersion",
            report.manifestVersion?.toString(),
        )
        digest.nullableField("report.extensionName", report.extensionName)
        digest.nullableField("report.extensionVersion", report.extensionVersion)
        digest.field(
            "report.manifestKeyPresent",
            report.manifestKeyPresent.toString(),
        )
        digest.nullableField(
            "report.manifestKeyExtensionId",
            report.manifestKeyExtensionId,
        )
        digest.stringSet(
            "report.declaredPermissions",
            report.declaredPermissions,
        )
        digest.stringSet("report.usedApis", report.usedApis)
        digest.stringSet("report.unsupportedApis", report.unsupportedApis)
        digest.stringSet("report.verifiedApis", report.verifiedApis)
        digest.stringSet("report.unverifiedApis", report.unverifiedApis)
        digest.stringSet("report.unavailableApis", report.unavailableApis)
        digest.count("report.issues", report.issues.size)
        report.issues.forEachIndexed { index, issue ->
            digest.field("report.issues[$index].code", issue.code)
            digest.field("report.issues[$index].message", issue.message)
            digest.field(
                "report.issues[$index].blocking",
                issue.blocking.toString(),
            )
        }
        digest.field("report.conversionState", report.conversionState.name)
        digest.count(
            "report.adapterDecisions",
            report.adapterDecisions.size,
        )
        report.adapterDecisions.forEachIndexed { index, decision ->
            digest.decision("report.adapterDecisions[$index]", decision)
        }
        digest.nullableField(
            "report.originalExtensionId",
            report.originalExtensionId,
        )
        digest.nullableField(
            "report.convertedExtensionId",
            report.convertedExtensionId,
        )
        digest.nullableField("report.sourceSha256", report.sourceSha256)
        digest.nullableField(
            "report.browserProfileSha256",
            report.browserProfileSha256,
        )
        digest.count("selectedAdapters", selectedAdapters.size)
        selectedAdapters.forEachIndexed { index, selected ->
            digest.decision("selectedAdapters[$index]", selected)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun MessageDigest.decision(
        name: String,
        decision: AdapterDecision,
    ) {
        field("$name.id", decision.id)
        field("$name.version", decision.version.toString())
        field("$name.risk", decision.risk.name)
        field("$name.disposition", decision.disposition.name)
        field("$name.applied", decision.applied.toString())
        count("$name.evidence", decision.evidence.size)
        decision.evidence.forEachIndexed { index, evidence ->
            field("$name.evidence[$index].relativePath", evidence.relativePath)
            nullableField(
                "$name.evidence[$index].line",
                evidence.line?.toString(),
            )
            field("$name.evidence[$index].detail", evidence.detail)
        }
        stringList("$name.changes", decision.changes)
        stringList(
            "$name.remainingLimitations",
            decision.remainingLimitations,
        )
    }

    private fun MessageDigest.stringSet(name: String, values: Set<String>) {
        stringList(name, values.sorted())
    }

    private fun MessageDigest.stringList(name: String, values: List<String>) {
        count(name, values.size)
        values.forEachIndexed { index, value ->
            field("$name[$index]", value)
        }
    }

    private fun MessageDigest.count(name: String, value: Int) {
        field("$name.count", value.toString())
    }

    private fun MessageDigest.nullableField(name: String, value: String?) {
        field("$name.present", (value != null).toString())
        if (value != null) field(name, value)
    }

    private fun MessageDigest.field(name: String, value: String) {
        bytes(name.toByteArray(UTF_8))
        bytes(value.toByteArray(UTF_8))
    }

    private fun MessageDigest.bytes(bytes: ByteArray) {
        update(
            ByteBuffer.allocate(Int.SIZE_BYTES)
                .putInt(bytes.size)
                .array(),
        )
        update(bytes)
    }
}

internal fun CompatibilityReport.deepImmutableCopy(): CompatibilityReport = copy(
    declaredPermissions = Collections.unmodifiableSet(declaredPermissions.toSortedSet()),
    usedApis = Collections.unmodifiableSet(usedApis.toSortedSet()),
    unsupportedApis = Collections.unmodifiableSet(unsupportedApis.toSortedSet()),
    verifiedApis = Collections.unmodifiableSet(verifiedApis.toSortedSet()),
    unverifiedApis = Collections.unmodifiableSet(unverifiedApis.toSortedSet()),
    unavailableApis = Collections.unmodifiableSet(unavailableApis.toSortedSet()),
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
    INELIGIBLE_COMPATIBILITY_STATUS,
    NO_APPLICABLE_ADAPTER,
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
