package dev.sixbridge.probe.evidence

import dev.sixbridge.core.profile.ApiExecutionContext
import java.util.Collections

enum class ProbeCaseStatus {
    ABSENT,
    EXPOSED,
    FAILED,
    NOT_RUN,
    RESTRICTED,
    VERIFIED,
}

data class ProbeCaseResult(
    val caseId: String,
    val context: ApiExecutionContext,
    val detail: String,
    val durationMs: Long,
    val member: String,
    val namespace: String,
    val runToken: String,
    val status: ProbeCaseStatus,
)

class ProbeEvidence internal constructor(
    val schemaVersion: Int,
    val sessionId: String,
    val groupId: String,
    val catalogSha256: String,
    val extensionId: String,
    val wrapperSignerSha256: String,
    val browserPackage: String,
    val browserVersionName: String,
    val browserVersionCode: Long,
    val browserApkSetSha256: String,
    val browserSignerSha256: String,
    results: List<ProbeCaseResult>,
) {
    val results: List<ProbeCaseResult> = Collections.unmodifiableList(results.toList())

    override fun equals(other: Any?): Boolean =
        other is ProbeEvidence &&
            schemaVersion == other.schemaVersion &&
            sessionId == other.sessionId &&
            groupId == other.groupId &&
            catalogSha256 == other.catalogSha256 &&
            extensionId == other.extensionId &&
            wrapperSignerSha256 == other.wrapperSignerSha256 &&
            browserPackage == other.browserPackage &&
            browserVersionName == other.browserVersionName &&
            browserVersionCode == other.browserVersionCode &&
            browserApkSetSha256 == other.browserApkSetSha256 &&
            browserSignerSha256 == other.browserSignerSha256 &&
            results == other.results

    override fun hashCode(): Int = listOf(
        schemaVersion,
        sessionId,
        groupId,
        catalogSha256,
        extensionId,
        wrapperSignerSha256,
        browserPackage,
        browserVersionName,
        browserVersionCode,
        browserApkSetSha256,
        browserSignerSha256,
        results,
    ).hashCode()
}
