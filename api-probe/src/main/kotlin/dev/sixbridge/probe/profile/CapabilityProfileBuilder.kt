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
import dev.sixbridge.probe.evidence.ProbeEvidenceJson
import dev.sixbridge.probe.evidence.ProbeManifestLoadIsolation

class CapabilityProfileBuilder(
    private val catalog: ApiProbeCatalog,
) {
    fun verify(
        evidence: List<ProbeEvidence>,
        manifestLoadIsolation: ProbeManifestLoadIsolation? = null,
    ): CapabilityEvidenceSummary {
        val groups = evidence.groupBy(ProbeEvidence::groupId)
        val expectedEntries = catalog.entries.filter {
            it.disposition == CatalogDisposition.PROBE
        }
        val expectedByGroup = expectedEntries.groupBy { requireNotNull(it.groupId) }
        require(expectedByGroup.keys == ApiProbeCatalog.PROBE_GROUP_IDS) {
            "API probe catalog group coverage가 올바르지 않습니다."
        }
        validateSetIdentity(evidence, groups, expectedByGroup, manifestLoadIsolation)
        val seen = linkedMapOf<ApiCapabilityKey, ProbeCaseStatus>()
        groups.toSortedMap().forEach { (groupId, items) ->
            validateGroup(
                groupId,
                items.single(),
                expectedByGroup.getValue(groupId),
                seen,
                manifestLoadIsolation?.takeIf { it.groupId == groupId }?.excludedCaseId,
            )
        }
        val first = evidence.first { it.groupId != manifestLoadIsolation?.groupId }
        val executedCaseCount = evidence.sumOf { it.results.size }
        val manifestLoadBlockedCaseCount = if (manifestLoadIsolation == null) 0 else 1
        return CapabilityEvidenceSummary(
            sessionId = manifestLoadIsolation?.controlSessionId ?: first.sessionId,
            catalogSha256 = catalog.sha256,
            groupCaseCounts = groups.toSortedMap().mapValues { it.value.single().results.size },
            executedCaseCount = executedCaseCount,
            manifestLoadBlockedCaseCount = manifestLoadBlockedCaseCount,
            totalCaseCount = executedCaseCount + manifestLoadBlockedCaseCount,
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

    fun build(
        base: BrowserProfile,
        evidence: List<ProbeEvidence>,
        manifestLoadIsolation: ProbeManifestLoadIsolation? = null,
    ): BrowserProfile {
        base.validate()
        verify(evidence, manifestLoadIsolation)
        val groups = evidence.groupBy(ProbeEvidence::groupId)
        evidence.forEach { item -> validateProvenance(base, item) }
        manifestLoadIsolation?.let { validateProvenance(base, it) }

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
            val excludedCaseId = manifestLoadIsolation
                ?.takeIf { it.groupId == groupId }
                ?.excludedCaseId
            require(
                item.results.map(ProbeCaseResult::caseId).toSet() ==
                    expectedById.keys - listOfNotNull(excludedCaseId),
            ) {
                "API probe evidence의 $groupId case coverage가 catalog와 일치하지 않습니다."
            }
            require(item.results.size == expectedById.size - if (excludedCaseId == null) 0 else 1) {
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
        manifestLoadIsolation?.let { isolation ->
            val expected = expectedByGroup.getValue(isolation.groupId)
                .flatMap { entry ->
                    entry.cases.map { case -> ExpectedCase(entry.namespace, case) }
                }
                .single { it.case.id == isolation.excludedCaseId }
            val key = ApiCapabilityKey(expected.namespace, expected.case.member, expected.case.context)
            require(statusesByKey.putIfAbsent(key, ApiCapabilityStatus.FAILED) == null) {
                "manifest 로드 차단 capability key는 중복될 수 없습니다."
            }
            capabilities += ApiCapabilityEvidence(
                key = key,
                status = ApiCapabilityStatus.FAILED,
                permissions = expected.case.permissions,
                manifestKeys = expected.case.manifestKeys,
                detail = manifestLoadBlockedDetail(isolation),
                probeId = expected.case.id,
                probeCatalogSha256 = catalog.sha256,
            )
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

    private fun validateProvenance(base: BrowserProfile, isolation: ProbeManifestLoadIsolation) {
        require(isolation.browserPackage == base.browserPackage) {
            "manifest 로드 분리 증거 browser package가 base profile과 일치하지 않습니다."
        }
        require(isolation.browserVersionName == base.versionName) {
            "manifest 로드 분리 증거 browser versionName이 base profile과 일치하지 않습니다."
        }
        require(isolation.browserVersionCode == base.versionCode) {
            "manifest 로드 분리 증거 browser versionCode가 base profile과 일치하지 않습니다."
        }
        require(isolation.browserApkSetSha256 == base.installationApkSetSha256) {
            "manifest 로드 분리 증거 browser APK set이 base profile과 일치하지 않습니다."
        }
        require(isolation.browserSignerSha256 == base.sourceApkSignerSha256) {
            "manifest 로드 분리 증거 browser signer가 base profile과 일치하지 않습니다."
        }
    }

    private fun validateSetIdentity(
        evidence: List<ProbeEvidence>,
        groups: Map<String, List<ProbeEvidence>>,
        expectedByGroup: Map<String, List<dev.sixbridge.probe.catalog.ProbeCatalogEntry>>,
        manifestLoadIsolation: ProbeManifestLoadIsolation?,
    ) {
        require(groups.keys == ApiProbeCatalog.PROBE_GROUP_IDS) {
            "API probe evidence는 정확한 6개 group을 포함해야 합니다."
        }
        require(groups.values.all { it.size == 1 }) {
            "API probe evidence group은 중복될 수 없습니다."
        }
        if (manifestLoadIsolation == null) {
            require(evidence.map(ProbeEvidence::sessionId).distinct().size == 1) {
                "API probe evidence sessionId가 일치하지 않습니다."
            }
        } else {
            validateManifestLoadIsolation(evidence, groups, expectedByGroup, manifestLoadIsolation)
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
        excludedCaseId: String? = null,
    ) {
        val expectedById = entries.flatMap { entry ->
            entry.cases.map { case -> case.id to ExpectedCase(entry.namespace, case) }
        }.toMap()
        require(
            evidence.results.map(ProbeCaseResult::caseId).toSet() ==
                expectedById.keys - listOfNotNull(excludedCaseId),
        ) {
            "API probe evidence의 $groupId case coverage가 catalog와 일치하지 않습니다."
        }
        require(evidence.results.size == expectedById.size - if (excludedCaseId == null) 0 else 1) {
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

    private fun validateManifestLoadIsolation(
        evidence: List<ProbeEvidence>,
        groups: Map<String, List<ProbeEvidence>>,
        expectedByGroup: Map<String, List<dev.sixbridge.probe.catalog.ProbeCatalogEntry>>,
        isolation: ProbeManifestLoadIsolation,
    ) {
        require(isolation.catalogSha256 == catalog.sha256) {
            "manifest 로드 분리 증거 catalog SHA-256이 일치하지 않습니다."
        }
        val variant = groups.getValue(isolation.groupId).single()
        val controls = evidence.filter { it.groupId != isolation.groupId }
        require(controls.isNotEmpty() && controls.all { it.sessionId == isolation.controlSessionId }) {
            "manifest 로드 분리 증거 control session이 다른 group과 일치하지 않습니다."
        }
        require(
            variant.sessionId == isolation.variantSessionId &&
                variant.extensionId == isolation.variantExtensionId &&
                variant.wrapperSignerSha256 == isolation.wrapperSignerSha256,
        ) { "manifest 로드 분리 증거 variant binding이 일치하지 않습니다." }
        require(isolation.controlExtensionId !in evidence.map(ProbeEvidence::extensionId)) {
            "manifest 로드 분리 증거 control extension ID는 실행 evidence와 달라야 합니다."
        }
        require(ProbeEvidenceJson.canonicalSha256(variant) == isolation.variantEvidenceSha256) {
            "manifest 로드 분리 증거 variant evidence SHA-256이 일치하지 않습니다."
        }
        require(
            isolation.browserPackage == variant.browserPackage &&
                isolation.browserVersionName == variant.browserVersionName &&
                isolation.browserVersionCode == variant.browserVersionCode &&
                isolation.browserApkSetSha256 == variant.browserApkSetSha256 &&
                isolation.browserSignerSha256 == variant.browserSignerSha256,
        ) { "manifest 로드 분리 증거 browser provenance가 variant와 일치하지 않습니다." }
        val expected = expectedByGroup.getValue(isolation.groupId)
            .flatMap { entry ->
                entry.cases.map { case -> ExpectedCase(entry.namespace, case) }
            }
            .singleOrNull { it.case.id == isolation.excludedCaseId }
            ?: throw IllegalArgumentException("manifest 로드 분리 excludedCaseId가 group에 없습니다.")
        require(expected.case.permissions == setOf(isolation.excludedPermission)) {
            "manifest 로드 분리 excludedPermission이 catalog case와 일치하지 않습니다."
        }
        require(
            variant.results.map(ProbeCaseResult::caseId).toSet() ==
                expectedByGroup.getValue(isolation.groupId)
                    .flatMap { it.cases }
                    .map(ProbeCatalogCase::id)
                    .toSet() - isolation.excludedCaseId,
        ) { "manifest 로드 분리 variant는 제외한 case만 누락해야 합니다." }
    }

    private fun manifestLoadBlockedDetail(isolation: ProbeManifestLoadIsolation): String =
        "{\"controlExtensionId\":\"${isolation.controlExtensionId}\"," +
            "\"kind\":\"manifest-load-blocked\"," +
            "\"permission\":\"${isolation.excludedPermission}\"," +
            "\"variantExtensionId\":\"${isolation.variantExtensionId}\"}"

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
    val executedCaseCount: Int,
    val manifestLoadBlockedCaseCount: Int,
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
