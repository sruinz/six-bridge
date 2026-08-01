package dev.sixbridge.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sixbridge.core.compat.CompatibilityReport
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
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DesktopApprovalStepTest {
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
                    issues = emptyList(),
                    adapterDecisions = adapters,
                ),
                logicalSourceIdentity = identity,
                selectedAutomaticAdapters = setOf("safe-entry"),
                requiredApprovals = requiredApprovals,
            ),
            reviewStep = DesktopReviewStep.APPROVAL,
            selectedAdapters = emptySet(),
            partialConfirmed = false,
            projectIdentity = null,
        )
    }
}
