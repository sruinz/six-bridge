package dev.sixbridge.manager.ui

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.manager.conversion.ConversionFailureCode
import dev.sixbridge.manager.conversion.ExistingConversionTarget
import dev.sixbridge.manager.conversion.IdentityRequirement
import dev.sixbridge.manager.conversion.InstallationIdentitySelection
import dev.sixbridge.manager.conversion.ConversionForceProof
import dev.sixbridge.manager.conversion.OnDeviceConversionApproval
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class ConversionScreenTest {
    @Test
    fun `Android build는 선택적 결정을 모두 요구하되 원본 유지도 허용한다`() {
        val ready = analysis(
            optionalApprovals = setOf("popup-native-autofill-guard"),
        )

        assertFalse(ready.canBuild)

        val original = ready.chooseOptionalAdapter(
            "popup-native-autofill-guard",
            apply = false,
        )

        assertTrue(original.canBuild)
        assertTrue(original.selectedAdapters.isEmpty())
        assertEquals(
            setOf("popup-native-autofill-guard"),
            original.resolvedOptionalAdapters,
        )
    }

    @Test
    fun `동작 변경 어댑터는 모두 승인하기 전 build할 수 없다`() {
        val ready = analysis(requiredApprovals = setOf("action-popup-entry", "tabs"))

        assertFalse(ready.canBuild)
        assertFalse(ready.toggleAdapter("action-popup-entry").canBuild)
        assertTrue(
            ready.toggleAdapter("action-popup-entry")
                .toggleAdapter("tabs")
                .canBuild,
        )
    }

    @Test
    fun `partial은 별도 확인 전 build할 수 없다`() {
        val ready = analysis(status = CompatibilityStatus.PARTIAL)

        assertFalse(ready.canBuild)
        assertTrue(ready.copy(partialConfirmed = true).canBuild)
    }

    @Test
    fun `표식 없는 입력의 기존 대상은 선택과 별도 확인을 모두 요구한다`() {
        val target = ExistingConversionTarget(
            identitySha256 = "a".repeat(64),
            displayName = "기존 변환",
            extensionId = "b".repeat(32),
            packageName = "dev.sixbridge.ext.${"b".repeat(32)}",
            wrapperSignerSha256 = "c".repeat(64),
        )
        val ready = analysis(
            identityRequirement = IdentityRequirement.UserSelectionRequired(listOf(target)),
        )

        assertFalse(ready.canBuild)
        val selected = ready.selectIdentity(
            InstallationIdentitySelection.UseExisting(target.identitySha256),
        )
        assertFalse(selected.canBuild)
        assertTrue(selected.copy(existingIdentityConfirmed = true).canBuild)
        assertTrue(
            ready.selectIdentity(InstallationIdentitySelection.CreateNew).canBuild,
        )
    }

    @Test
    fun `blocked와 unknown은 build할 수 없다`() {
        assertFalse(analysis(status = CompatibilityStatus.BLOCKED).canBuild)
        assertFalse(analysis(status = CompatibilityStatus.UNKNOWN).canBuild)
    }

    @Test
    fun `dynamic UNKNOWN은 작업별 확인 후에만 build할 수 있다`() {
        val ready = analysis(
            status = CompatibilityStatus.UNKNOWN,
            issues = listOf(CompatibilityIssue("api_usage_dynamic", "dynamic API", true)),
        )

        assertFalse(ready.canBuild)
        assertTrue(ready.copy(forceApproval = forceApproval()).canBuild)
    }

    @Test
    fun `강제 불가 UNKNOWN은 확인 값을 주어도 build할 수 없다`() {
        val ready = analysis(
            status = CompatibilityStatus.UNKNOWN,
            issues = listOf(CompatibilityIssue("browser_profile_unknown", "profile", true)),
        )

        assertFalse(ready.copy(forceApproval = forceApproval()).canBuild)
    }

    @Test
    fun `draft signer는 원문을 보존하고 일반 입력은 signer가 없다`() {
        val fingerprint = "a".repeat(64)

        assertEquals(null, analysis().draftSignerFingerprint)
        assertEquals(
            fingerprint,
            analysis().copy(draftSignerFingerprint = fingerprint).draftSignerFingerprint,
        )
    }

    @Test
    fun `변환 실패 코드는 서로 다른 한글 원인과 복구 행동으로 표시한다`() {
        val messages = ConversionFailureCode.entries.map(::conversionFailureMessage)

        assertEquals(messages.size, messages.map(UserMessage::text).toSet().size)
        assertTrue(messages.all { message ->
            message.text.any { character -> character.code in 0xAC00..0xD7A3 }
        })
        assertTrue(messages.none { message ->
            "content://" in message.text ||
                "/storage/" in message.text ||
                "Exception" in message.text
        })
    }

    @Test
    fun `input cleanup 실패는 private 경로 없이 복구 불가 메시지로 표시한다`() {
        val message = conversionFailureMessage(ConversionFailureCode.INPUT_CLEANUP)

        assertEquals(RecoveryAction.NONE, message.recoveryAction)
        assertTrue("private 입력 정리" in message.text)
        assertFalse("/data/" in message.text)
        assertFalse("conversion-input" in message.text)
    }

    private fun analysis(
        status: CompatibilityStatus = CompatibilityStatus.COMPATIBLE,
        requiredApprovals: Set<String> = emptySet(),
        optionalApprovals: Set<String> = emptySet(),
        issues: List<CompatibilityIssue> = emptyList(),
        identityRequirement: IdentityRequirement =
            IdentityRequirement.Stable("0123456789ab"),
    ) = ConversionUiState.AnalysisReady(
        token = TOKEN,
        report = CompatibilityReport(
            status = status,
            manifestVersion = 3,
            extensionName = "테스트 확장",
            extensionVersion = "1.0",
            declaredPermissions = setOf("storage"),
            usedApis = setOf("tabs"),
            unsupportedApis = emptySet(),
            issues = issues,
            conversionState = ConversionState.REQUIRES_APPROVAL,
            sourceSha256 = "a".repeat(64),
            browserProfileSha256 = "b".repeat(64),
        ),
        selectedAutomaticAdapters = emptySet(),
        requiredApprovals = requiredApprovals,
        optionalApprovals = optionalApprovals,
        identityRequirement = identityRequirement,
    )

    private companion object {
        const val TOKEN = "11111111-1111-1111-1111-111111111111"
    }

    private fun forceApproval() = OnDeviceConversionApproval(
        forceProof = ConversionForceProof("proof"),
    )
}
