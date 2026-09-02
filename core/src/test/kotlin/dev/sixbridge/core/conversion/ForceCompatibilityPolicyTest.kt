package dev.sixbridge.core.conversion

import dev.sixbridge.contract.bundle.CompatibilityApkBinding
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForceCompatibilityPolicyTest {
    @Test
    fun `dynamic API UNKNOWN만 강제 가능하다`() {
        val eligible = report(
            status = CompatibilityStatus.UNKNOWN,
            issues = listOf(CompatibilityIssue("api_usage_dynamic", "dynamic", true)),
        )

        assertTrue(ForceCompatibilityPolicy.isEligible(eligible))
        listOf(
            eligible.copy(
                issues = listOf(
                    CompatibilityIssue("browser_profile_unknown", "profile", true),
                ),
            ),
            eligible.copy(
                issues = listOf(
                    CompatibilityIssue("analysis_budget_exceeded", "budget", true),
                ),
            ),
            eligible.copy(
                unavailableApis = setOf("downloads"),
                unsupportedApis = setOf("downloads"),
            ),
            eligible.copy(conversionState = ConversionState.UNAVAILABLE),
            eligible.copy(
                adapterDecisions = listOf(
                    adapterDecision(AdapterDisposition.UNAVAILABLE),
                ),
            ),
            eligible.copy(sourceSha256 = null),
            eligible.copy(browserProfileSha256 = null),
            eligible.copy(status = CompatibilityStatus.BLOCKED),
            eligible.copy(
                unavailableApis = setOf("downloads"),
                unsupportedApis = emptySet(),
            ),
        ).forEach { report ->
            assertFalse(ForceCompatibilityPolicy.isEligible(report))
        }
    }

    @Test
    fun `강제 provenance는 blocking issue code를 정렬하고 중복 제거한다`() {
        val provenance = ForceCompatibilityPolicy.requireProvenance(
            report(
                status = CompatibilityStatus.UNKNOWN,
                issues = listOf(
                    CompatibilityIssue("api_usage_dynamic", "first", true),
                    CompatibilityIssue("api_usage_dynamic", "second", true),
                    CompatibilityIssue("non_blocking", "notice", false),
                ),
            ),
            ConversionAuthorization.forceUnknownApiUsage(),
        )

        assertEquals(
            ForcedCompatibilityProvenance(
                CompatibilityForceScope.UNKNOWN_API_USAGE,
                CompatibilityStatus.UNKNOWN,
                listOf("api_usage_dynamic"),
            ),
            provenance,
        )
    }

    @Test
    fun `서명 APK에 결합된 v6 report만 contract provenance를 복원한다`() {
        val source = report(
            status = CompatibilityStatus.UNKNOWN,
            issues = listOf(CompatibilityIssue("api_usage_dynamic", "dynamic", true)),
        )
        val provenance = ForceCompatibilityPolicy.requireProvenance(
            source,
            ConversionAuthorization.forceUnknownApiUsage(),
        )
        val contract = SixBundle.toContractV6(
            source,
            originalSourceSha256 = "c".repeat(64),
            planSha256 = "d".repeat(64),
            forcedCompatibility = provenance,
        )

        assertEquals(
            provenance,
            ForceCompatibilityPolicy.requireBoundContractProvenance(
                contract,
                CompatibilityApkBinding.canonicalSha256(contract),
            ),
        )
    }

    private fun report(
        status: CompatibilityStatus,
        issues: List<CompatibilityIssue>,
    ) = CompatibilityReport(
        status = status,
        manifestVersion = 3,
        extensionName = "Synthetic",
        extensionVersion = "1",
        declaredPermissions = emptySet(),
        usedApis = emptySet(),
        unsupportedApis = emptySet(),
        issues = issues,
        sourceSha256 = "a".repeat(64),
        browserProfileSha256 = "b".repeat(64),
    )

    private fun adapterDecision(disposition: AdapterDisposition) = AdapterDecision(
        id = "storage-fallback",
        version = 1,
        risk = AdapterRisk.SAFE,
        disposition = disposition,
        applied = false,
        evidence = emptyList(),
        changes = emptyList(),
        remainingLimitations = emptyList(),
    )
}
