package dev.sixbridge.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.AdapterRisk
import dev.sixbridge.core.conversion.LogicalIdentityEvidence
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.desktop.app.DesktopConversionState
import dev.sixbridge.desktop.app.DesktopReviewStep
import dev.sixbridge.desktop.conversion.DesktopAnalysisSession
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopApprovalStepTest {
    @Test
    fun `강제 가능 UNKNOWN은 확인과 기존 승인 gate를 모두 충족해야 한다`() {
        val unconfirmed = approvalState(
            status = CompatibilityStatus.UNKNOWN,
            requiredApprovals = emptySet(),
            forceEligible = true,
            forceConfirmed = false,
        )
        val confirmed = unconfirmed.copy(forceCompatibilityConfirmed = true)

        assertFalse(canBuildDraft(unconfirmed))
        assertTrue(canBuildDraft(confirmed))
    }

    @Test
    fun `다른 UNKNOWN과 BLOCKED에 확인값을 주입해도 생성할 수 없다`() {
        listOf(CompatibilityStatus.UNKNOWN, CompatibilityStatus.BLOCKED).forEach { status ->
            assertFalse(
                canBuildDraft(
                    approvalState(
                        status = status,
                        requiredApprovals = emptySet(),
                        forceConfirmed = true,
                    ),
                ),
            )
        }
    }

    @Test
    fun `선택적 adapter를 결정하기 전에는 draft를 만들 수 없다`() {
        val state = approvalState(
            status = CompatibilityStatus.PARTIAL,
            requiredApprovals = emptySet(),
            optionalApprovals = setOf("popup-native-autofill-guard"),
            resolvedOptionalAdapters = emptySet(),
            partialConfirmed = true,
        )

        assertFalse(canBuildDraft(state))
    }

    @Test
    fun `선택적 adapter는 보호 적용과 원본 유지를 배타적으로 선택한다`() = runComposeUiTest {
        var state by mutableStateOf(
            approvalState(
                status = CompatibilityStatus.PARTIAL,
                requiredApprovals = emptySet(),
                optionalApprovals = setOf("popup-native-autofill-guard"),
                resolvedOptionalAdapters = emptySet(),
                partialConfirmed = true,
            ),
        )
        val actions = mutableListOf<DesktopAction>()
        setContent {
            ApprovalStep(state) { action ->
                actions += action
                state = reduceApprovalForTest(state, action)
            }
        }

        onNodeWithTag("approval:build").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("approval:optional:popup-native-autofill-guard:original")
            .performScrollTo()
            .performClick()
            .assertIsSelected()
        onNodeWithTag("approval:optional:popup-native-autofill-guard:risk")
            .assertTextContains("브라우저 종료 위험", substring = true)
            .assertTextContains("PARTIAL", substring = true)
        onNodeWithTag("approval:build").assertIsEnabled()

        runOnIdle {
            assertEquals(
                listOf<DesktopAction>(
                    DesktopAction.OptionalAdapterChosen("popup-native-autofill-guard", false),
                ),
                actions,
            )
        }
    }

    @Test
    fun `동작 변경 adapter와 partial 확인 전에는 생성할 수 없다`() = runComposeUiTest {
        var state by mutableStateOf(approvalState(CompatibilityStatus.PARTIAL))
        val actions = mutableListOf<DesktopAction>()
        setContent {
            ApprovalStep(state) { action ->
                actions += action
                state = reduceApprovalForTest(state, action)
            }
        }

        onNodeWithTag("approval:adapter:safe-entry").assertIsNotEnabled()
        onNodeWithTag("approval:build").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("approval:adapter:action-popup-entry").performClick()
        onNodeWithTag("approval:build").assertIsNotEnabled()
        onNodeWithTag("approval:partial").performClick()
        onNodeWithTag("approval:build").assertIsEnabled()

        runOnIdle {
            assertEquals(
                listOf(
                    DesktopAction.AdapterToggled("action-popup-entry"),
                    DesktopAction.PartialConfirmationChanged(true),
                ),
                actions,
            )
        }
    }

    @Test
    fun `markerless 입력은 추정하지 않고 32자 project identity를 선택해야 한다`() = runComposeUiTest {
        var state by mutableStateOf(
            approvalState(
                status = CompatibilityStatus.COMPATIBLE,
                identity = LogicalSourceIdentityResult.NeedsUserSelection,
                requiredApprovals = emptySet(),
            ),
        )
        var selectedIdentity: String? = null
        setContent {
            ApprovalStep(state) { action ->
                if (action is DesktopAction.ProjectIdentitySelected) {
                    selectedIdentity = action.value
                    state = state.copy(projectIdentity = action.value)
                }
            }
        }

        onNodeWithTag("approval:identity-required").assertTextContains("기존 설치 대상을 추정하지 않습니다", substring = true)
        onNodeWithTag("approval:build").performScrollTo().assertIsNotEnabled()
        onNodeWithTag("approval:identity-generate").performScrollTo().performClick()
        onNodeWithTag("approval:build").assertIsEnabled()

        runOnIdle {
            assertTrue(requireNotNull(selectedIdentity).matches(Regex("[0-9a-f]{32}")))
        }
    }

    @Test
    fun `marker identity가 있는 입력은 project identity 선택을 요구하지 않는다`() {
        val state = approvalState(
            status = CompatibilityStatus.COMPATIBLE,
            identity = LogicalSourceIdentityResult.Stable(
                "project-identity",
                LogicalIdentityEvidence.PROJECT_MARKER,
            ),
            requiredApprovals = emptySet(),
        )

        assertTrue(canBuildDraft(state))
    }

    private fun reduceApprovalForTest(
        state: DesktopConversionState.AnalysisReady,
        action: DesktopAction,
    ): DesktopConversionState.AnalysisReady = when (action) {
        is DesktopAction.AdapterToggled -> state.copy(
            selectedAdapters = state.selectedAdapters.toMutableSet().also {
                if (!it.add(action.id)) it.remove(action.id)
            },
        )
        is DesktopAction.OptionalAdapterChosen -> state.copy(
            selectedAdapters = state.selectedAdapters.toMutableSet().also {
                if (action.apply) it.add(action.id) else it.remove(action.id)
            },
            resolvedOptionalAdapters = state.resolvedOptionalAdapters + action.id,
        )
        is DesktopAction.PartialConfirmationChanged -> state.copy(partialConfirmed = action.confirmed)
        else -> state
    }

    private fun approvalState(
        status: CompatibilityStatus,
        identity: LogicalSourceIdentityResult = LogicalSourceIdentityResult.Stable(
            "abcdefghijklmnopabcdefghijklmnop",
            LogicalIdentityEvidence.MANIFEST_KEY,
        ),
        requiredApprovals: Set<String> = setOf("action-popup-entry"),
        optionalApprovals: Set<String> = emptySet(),
        resolvedOptionalAdapters: Set<String> = emptySet(),
        partialConfirmed: Boolean = false,
        forceEligible: Boolean = false,
        forceConfirmed: Boolean = false,
    ): DesktopConversionState.AnalysisReady {
        val adapters = listOf(
            AdapterDecision(
                id = "safe-entry",
                version = 1,
                risk = AdapterRisk.SAFE,
                disposition = AdapterDisposition.AUTOMATIC,
                applied = false,
                evidence = emptyList(),
                changes = listOf("삼성 인터넷 메뉴에 표시"),
                remainingLimitations = emptyList(),
            ),
            AdapterDecision(
                id = "action-popup-entry",
                version = 1,
                risk = AdapterRisk.BEHAVIOR_CHANGE,
                disposition = AdapterDisposition.REQUIRES_APPROVAL,
                applied = false,
                evidence = emptyList(),
                changes = listOf("팝업을 모바일 메뉴로 변경"),
                remainingLimitations = listOf("데스크톱 팝업 크기는 유지되지 않음"),
            ),
            AdapterDecision(
                id = "popup-native-autofill-guard",
                version = 1,
                risk = AdapterRisk.BEHAVIOR_CHANGE,
                disposition = AdapterDisposition.OPTIONAL_APPROVAL,
                applied = false,
                evidence = emptyList(),
                changes = listOf("자동 입력 URL 이동 차단"),
                remainingLimitations = listOf("원본 유지 시 브라우저 종료 위험"),
            ),
        )
        return DesktopConversionState.AnalysisReady(
            session = DesktopAnalysisSession(
                token = "00000000-0000-0000-0000-000000000009",
                input = DesktopExtensionInput.Local(Path.of("extension.zip")),
                profile = BrowserProfile(
                    browserPackage = "com.sec.android.app.sbrowser",
                    versionName = "30.0.2.61",
                    versionCode = 300000261,
                    majorVersion = 30,
                    supportedApis = emptySet(),
                    wrapperContract = WrapperContract.complete(),
                ),
                report = CompatibilityReport(
                    status = status,
                    manifestVersion = 3,
                    extensionName = "예시 확장",
                    extensionVersion = "1.0",
                    declaredPermissions = emptySet(),
                    usedApis = emptySet(),
                    unsupportedApis = emptySet(),
                    issues = if (forceEligible) {
                        listOf(CompatibilityIssue("api_usage_dynamic", "dynamic", true))
                    } else {
                        emptyList()
                    },
                    adapterDecisions = adapters,
                    sourceSha256 = if (forceEligible) "a".repeat(64) else null,
                    browserProfileSha256 = if (forceEligible) "b".repeat(64) else null,
                ),
                logicalSourceIdentity = identity,
                selectedAutomaticAdapters = setOf("safe-entry"),
                requiredApprovals = requiredApprovals,
                optionalApprovals = optionalApprovals,
            ),
            reviewStep = DesktopReviewStep.APPROVAL,
            selectedAdapters = emptySet(),
            resolvedOptionalAdapters = resolvedOptionalAdapters,
            partialConfirmed = partialConfirmed,
            projectIdentity = null,
            forceCompatibilityConfirmed = forceConfirmed,
        )
    }
}
