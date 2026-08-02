package dev.sixbridge.probe.profile

import dev.sixbridge.core.profile.ApiCapabilityEvidence
import dev.sixbridge.core.profile.ApiCapabilityKey
import dev.sixbridge.core.profile.ApiCapabilityStatus
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.probe.catalog.ApiProbeCatalog
import dev.sixbridge.probe.catalog.CatalogDisposition
import dev.sixbridge.probe.catalog.ProbeCatalogCase
import dev.sixbridge.probe.catalog.ProbeExpectedOutcome
import dev.sixbridge.probe.catalog.ProbeMode
import dev.sixbridge.probe.evidence.ProbeCaseResult
import dev.sixbridge.probe.evidence.ProbeCaseStatus
import dev.sixbridge.probe.evidence.ProbeEvidence

class CapabilityProfileBuilder(
    private val catalog: ApiProbeCatalog,
) {
    fun verify(evidence: List<ProbeEvidence>): CapabilityEvidenceSummary {
        val groups = evidence.groupBy(ProbeEvidence::groupId)
        validateSetIdentity(evidence, groups)
        val expectedEntries = catalog.entries.filter {
            it.disposition == CatalogDisposition.PROBE
        }
        val expectedByGroup = expectedEntries.groupBy { requireNotNull(it.groupId) }
        require(expectedByGroup.keys == ApiProbeCatalog.PROBE_GROUP_IDS) {
            "API probe catalog group coverage가 올바르지 않습니다."
        }
        val seen = linkedMapOf<ApiCapabilityKey, ProbeCaseStatus>()
        groups.toSortedMap().forEach { (groupId, items) ->
            validateGroup(groupId, items.single(), expectedByGroup.getValue(groupId), seen)
        }
        val first = evidence.first()
        return CapabilityEvidenceSummary(
            sessionId = first.sessionId,
            catalogSha256 = catalog.sha256,
            groupCaseCounts = groups.toSortedMap().mapValues { it.value.single().results.size },
            totalCaseCount = evidence.sumOf { it.results.size },
            expectedTotalCaseCount = catalog.probeCaseIds.size,
            browserPackage = first.browserPackage,
            browserVersionName = first.browserVersionName,
            browserVersionCode = first.browserVersionCode,
            browserApkSetSha256 = first.browserApkSetSha256,
            browserSignerSha256 = first.browserSignerSha256,
            wrapperSignerSha256 = first.wrapperSignerSha256,
            extensionIds = groups.toSortedMap().mapValues { it.value.single().extensionId },
        )
    }

    fun build(base: BrowserProfile, evidence: List<ProbeEvidence>): BrowserProfile {
        base.validate()
        verify(evidence)
        val groups = evidence.groupBy(ProbeEvidence::groupId)
        evidence.forEach { item -> validateProvenance(base, item) }

        val expectedEntries = catalog.entries.filter {
            it.disposition == CatalogDisposition.PROBE
        }
        val expectedByGroup = expectedEntries.groupBy { requireNotNull(it.groupId) }
        require(expectedByGroup.keys == ApiProbeCatalog.PROBE_GROUP_IDS) {
            "API probe catalog group coverage가 올바르지 않습니다."
        }

        val capabilities = mutableListOf<ApiCapabilityEvidence>()
        val statusesByKey = linkedMapOf<ApiCapabilityKey, ApiCapabilityStatus>()
        groups.toSortedMap().forEach { (groupId, items) ->
            val item = items.single()
            val cases = expectedByGroup.getValue(groupId).flatMap { entry ->
                entry.cases.map { case -> ExpectedCase(entry.namespace, case) }
            }
            val expectedById = cases.associateBy { it.case.id }
            require(item.results.map(ProbeCaseResult::caseId).toSet() == expectedById.keys) {
                "API probe evidence의 $groupId case coverage가 catalog와 일치하지 않습니다."
            }
            require(item.results.size == expectedById.size) {
                "API probe evidence의 $groupId case가 중복되었습니다."
            }
            item.results.sortedBy(ProbeCaseResult::caseId).forEach { result ->
                val expected = expectedById.getValue(result.caseId)
                require(
                    result.namespace == expected.namespace &&
                        result.member == expected.case.member &&
                        result.context == expected.case.context,
                ) {
                    "API probe evidence의 ${result.caseId} descriptor가 catalog와 일치하지 않습니다."
                }
                val key = ApiCapabilityKey(result.namespace, result.member, result.context)
                val status = result.status.toCapabilityStatus()
                val previous = statusesByKey.putIfAbsent(key, status)
                require(previous == null) {
                    if (previous != status) {
                        "같은 API capability key에 상충하는 status가 있습니다."
                    } else {
                        "API capability key는 중복될 수 없습니다."
                    }
                }
                capabilities += ApiCapabilityEvidence(
                    key = key,
                    status = status,
                    permissions = expected.case.permissions,
                    manifestKeys = expected.case.manifestKeys,
                    detail = result.detail,
                    probeId = result.caseId,
                    probeCatalogSha256 = catalog.sha256,
                )
            }
        }

        val promoted = expectedEntries.filter { entry ->
            val coreCases = entry.cases.filter {
                it.expectedOutcome == ProbeExpectedOutcome.VERIFIED
            }
            coreCases.isNotEmpty() && coreCases.all { case ->
                statusesByKey[
                    ApiCapabilityKey(entry.namespace, case.member, case.context)
                ] == ApiCapabilityStatus.VERIFIED
            }
        }.mapTo(linkedSetOf()) { it.namespace }

        return base.copy(
            supportedApis = base.supportedApis + promoted,
            apiCapabilities = capabilities,
        ).also(BrowserProfile::validate)
    }

    private fun validateProvenance(base: BrowserProfile, evidence: ProbeEvidence) {
        require(evidence.catalogSha256 == catalog.sha256) {
            "API probe catalog SHA-256이 일치하지 않습니다."
        }
        require(evidence.browserPackage == base.browserPackage) {
            "API probe browser package가 base profile과 일치하지 않습니다."
        }
        require(evidence.browserVersionName == base.versionName) {
            "API probe browser versionName이 base profile과 일치하지 않습니다."
        }
        require(evidence.browserVersionCode == base.versionCode) {
            "API probe browser versionCode가 base profile과 일치하지 않습니다."
        }
        require(evidence.browserApkSetSha256 == base.installationApkSetSha256) {
            "API probe browser APK set SHA-256이 base profile과 일치하지 않습니다."
        }
        require(evidence.browserSignerSha256 == base.sourceApkSignerSha256) {
            "API probe browser signer가 base profile과 일치하지 않습니다."
        }
        require(EXTENSION_ID.matches(evidence.extensionId)) {
            "API probe extension ID 형식이 올바르지 않습니다."
        }
        require(SHA256.matches(evidence.wrapperSignerSha256)) {
            "API probe wrapper signer 형식이 올바르지 않습니다."
        }
    }

    private fun validateSetIdentity(
        evidence: List<ProbeEvidence>,
        groups: Map<String, List<ProbeEvidence>>,
    ) {
        require(groups.keys == ApiProbeCatalog.PROBE_GROUP_IDS) {
            "API probe evidence는 정확한 6개 group을 포함해야 합니다."
        }
        require(groups.values.all { it.size == 1 }) {
            "API probe evidence group은 중복될 수 없습니다."
        }
        require(evidence.map(ProbeEvidence::sessionId).distinct().size == 1) {
            "API probe evidence sessionId가 일치하지 않습니다."
        }
        require(evidence.map(ProbeEvidence::catalogSha256).toSet() == setOf(catalog.sha256)) {
            "API probe catalog SHA-256이 일치하지 않습니다."
        }
        require(evidence.map(ProbeEvidence::wrapperSignerSha256).distinct().size == 1) {
            "API probe wrapper signer가 group 사이에서 일치하지 않습니다."
        }
        require(evidence.map(ProbeEvidence::extensionId).distinct().size == evidence.size) {
            "API probe extension ID는 group마다 달라야 합니다."
        }
        val provenance = evidence.map {
            listOf(
                it.browserPackage,
                it.browserVersionName,
                it.browserVersionCode.toString(),
                it.browserApkSetSha256,
                it.browserSignerSha256,
            )
        }.distinct()
        require(provenance.size == 1) {
            "API probe browser provenance가 group 사이에서 일치하지 않습니다."
        }
        evidence.forEach {
            require(EXTENSION_ID.matches(it.extensionId)) {
                "API probe extension ID 형식이 올바르지 않습니다."
            }
            require(SHA256.matches(it.wrapperSignerSha256)) {
                "API probe wrapper signer 형식이 올바르지 않습니다."
            }
            require(SHA256.matches(it.browserApkSetSha256)) {
                "API probe browser APK set SHA-256 형식이 올바르지 않습니다."
            }
            require(SHA256.matches(it.browserSignerSha256)) {
                "API probe browser signer 형식이 올바르지 않습니다."
            }
        }
    }

    private fun validateGroup(
        groupId: String,
        evidence: ProbeEvidence,
        entries: List<dev.sixbridge.probe.catalog.ProbeCatalogEntry>,
        seen: MutableMap<ApiCapabilityKey, ProbeCaseStatus>,
    ) {
        val expectedById = entries.flatMap { entry ->
            entry.cases.map { case -> case.id to ExpectedCase(entry.namespace, case) }
        }.toMap()
        require(evidence.results.map(ProbeCaseResult::caseId).toSet() == expectedById.keys) {
            "API probe evidence의 $groupId case coverage가 catalog와 일치하지 않습니다."
        }
        require(evidence.results.size == expectedById.size) {
            "API probe evidence의 $groupId case가 중복되었습니다."
        }
        evidence.results.forEach { result ->
            val expected = expectedById.getValue(result.caseId)
            val key = ApiCapabilityKey(result.namespace, result.member, result.context)
            val previous = seen.putIfAbsent(key, result.status)
            require(previous == null) {
                if (previous != result.status) {
                    "같은 API capability key에 상충하는 status가 있습니다."
                } else {
                    "API capability key는 중복될 수 없습니다."
                }
            }
            require(
                result.namespace == expected.namespace &&
                    result.member == expected.case.member &&
                    result.context == expected.case.context,
            ) {
                "API probe evidence의 ${result.caseId} descriptor가 catalog와 일치하지 않습니다."
            }
            validateOutcome(result, expected.case)
        }
    }

    private fun validateOutcome(result: ProbeCaseResult, expected: ProbeCatalogCase) {
        val allowed = when (expected.mode) {
            ProbeMode.PRESENCE_ONLY -> setOf(
                ProbeCaseStatus.ABSENT,
                ProbeCaseStatus.EXPOSED,
                ProbeCaseStatus.FAILED,
                ProbeCaseStatus.RESTRICTED,
            )
            ProbeMode.SAFE_CALL, ProbeMode.SAFE_CALL_WITH_CLEANUP -> setOf(
                ProbeCaseStatus.ABSENT,
                ProbeCaseStatus.FAILED,
                ProbeCaseStatus.RESTRICTED,
                ProbeCaseStatus.VERIFIED,
            )
        }.let { statuses ->
            if (expected.context == dev.sixbridge.core.profile.ApiExecutionContext.USER_GESTURE) {
                statuses + ProbeCaseStatus.NOT_RUN
            } else {
                statuses
            }
        }
        require(result.status in allowed) {
            "API probe evidence의 ${result.caseId} status가 catalog mode/expectedOutcome과 모순됩니다."
        }
        require(
            (expected.expectedOutcome == ProbeExpectedOutcome.EXPOSED) ==
                (expected.mode == ProbeMode.PRESENCE_ONLY),
        ) {
            "API probe catalog mode와 expectedOutcome이 모순됩니다."
        }
    }

    private fun ProbeCaseStatus.toCapabilityStatus(): ApiCapabilityStatus = when (this) {
        ProbeCaseStatus.VERIFIED -> ApiCapabilityStatus.VERIFIED
        ProbeCaseStatus.EXPOSED -> ApiCapabilityStatus.EXPOSED
        ProbeCaseStatus.RESTRICTED -> ApiCapabilityStatus.RESTRICTED
        ProbeCaseStatus.ABSENT -> ApiCapabilityStatus.ABSENT
        ProbeCaseStatus.FAILED, ProbeCaseStatus.NOT_RUN -> ApiCapabilityStatus.FAILED
    }

    private data class ExpectedCase(
        val namespace: String,
        val case: ProbeCatalogCase,
    )

    private companion object {
        val EXTENSION_ID = Regex("[a-p]{32}")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

data class CapabilityEvidenceSummary(
    val sessionId: String,
    val catalogSha256: String,
    val groupCaseCounts: Map<String, Int>,
    val totalCaseCount: Int,
    val expectedTotalCaseCount: Int,
    val browserPackage: String,
    val browserVersionName: String,
    val browserVersionCode: Long,
    val browserApkSetSha256: String,
    val browserSignerSha256: String,
    val wrapperSignerSha256: String,
    val extensionIds: Map<String, String>,
)
