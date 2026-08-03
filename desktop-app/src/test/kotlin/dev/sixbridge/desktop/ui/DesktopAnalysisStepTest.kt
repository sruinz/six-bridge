package dev.sixbridge.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.AdapterRisk
import dev.sixbridge.core.conversion.LogicalIdentityEvidence
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.core.conversion.SourceEvidence
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.desktop.app.DesktopConversionState
import dev.sixbridge.desktop.app.DesktopFailureStage
import dev.sixbridge.desktop.app.DesktopReviewStep
import dev.sixbridge.desktop.conversion.DesktopAnalysisSession
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DesktopAnalysisStepTest {
    @Test
    fun `blocked 분석은 사유와 근거를 노출하고 다음 단계를 차단한다`() = runComposeUiTest {
        setContent { DesktopApp(analysisState(CompatibilityStatus.BLOCKED), emptyList(), {}) }

        onNodeWithTag("analysis:identity").assertTextContains("예시 확장", substring = true)
        onNodeWithTag("analysis:identity").assertTextContains("1.2.3", substring = true)
        onNodeWithTag("analysis:identity").assertTextContains(
            "abcdefghijklmnopabcdefghijklmnop",
            substring = true,
        )
        onNodeWithTag("analysis:status").assertTextContains("BLOCKED", substring = true)
        onNodeWithTag("analysis:permissions").assertExists()
        onNodeWithTag("analysis:apis:verified").assertTextContains("tabs.query", substring = true)
        onNodeWithTag("analysis:apis:unverified")
            .assertTextContains("userScripts.register", substring = true)
        onNodeWithTag("analysis:apis:unavailable")
            .assertTextContains("nativeMessaging", substring = true)
        onNodeWithTag("analysis:issues").assertTextContains("nativeMessaging", substring = true)
        onNodeWithTag("analysis:adapters").assertTextContains("context-menu-mobile", substring = true)
        onNodeWithTag("analysis:continue").assertIsNotEnabled()
    }

    @Test
    fun `unknown은 차단하고 compatible와 partial은 승인 단계로 계속한다`() = runComposeUiTest {
        var actions = emptyList<DesktopAction>()
        setContent {
            DesktopApp(
                state = analysisState(CompatibilityStatus.COMPATIBLE),
                profiles = emptyList(),
                onAction = { actions = actions + it },
            )
        }

        onNodeWithTag("analysis:continue").performScrollTo().assertIsEnabled().performClick()
        runOnIdle { assertEquals(listOf(DesktopAction.ContinueToApproval), actions) }

        setContent { DesktopApp(analysisState(CompatibilityStatus.UNKNOWN), emptyList(), {}) }
        onNodeWithTag("analysis:continue").assertIsNotEnabled()

        setContent { DesktopApp(analysisState(CompatibilityStatus.PARTIAL), emptyList(), {}) }
        onNodeWithTag("analysis:continue").assertIsEnabled()
    }

    @Test
    fun `분석 실패는 메시지를 표시하고 입력 단계로 돌아갈 수 있다`() = runComposeUiTest {
        var actions = emptyList<DesktopAction>()
        setContent {
            DesktopApp(
                state = DesktopConversionState.Failed(
                    stage = DesktopFailureStage.ANALYSIS,
                    message = "파일 또는 네트워크 처리에 실패했습니다.",
                    previous = DesktopConversionState.Input(),
                ),
                profiles = emptyList(),
                onAction = { actions = actions + it },
            )
        }

        onNodeWithTag("analysis:error").assertTextContains("실패", substring = true)
        onNodeWithTag("analysis:error-back").performClick()
        runOnIdle { assertEquals(listOf(DesktopAction.Back), actions) }
    }

    private fun analysisState(status: CompatibilityStatus): DesktopConversionState.AnalysisReady {
        val report = CompatibilityReport(
            status = status,
            manifestVersion = 3,
            extensionName = "예시 확장",
            extensionVersion = "1.2.3",
            originalExtensionId = "abcdefghijklmnopabcdefghijklmnop",
            declaredPermissions = setOf("storage", "contextMenus"),
            usedApis = setOf("chrome.storage", "chrome.contextMenus"),
            unsupportedApis = setOf("nativeMessaging"),
            verifiedApis = setOf("tabs.query"),
            unverifiedApis = setOf("userScripts.register"),
            unavailableApis = setOf("nativeMessaging"),
            issues = listOf(
                CompatibilityIssue(
                    code = "unsupported-api",
                    message = "nativeMessaging은 지원하지 않습니다.",
                    blocking = true,
                ),
            ),
            adapterDecisions = listOf(
                AdapterDecision(
                    id = "context-menu-mobile",
                    version = 1,
                    risk = AdapterRisk.SAFE,
                    disposition = AdapterDisposition.AUTOMATIC,
                    applied = false,
                    evidence = listOf(SourceEvidence("background.js", 7, "contextMenus.create")),
                    changes = listOf("모바일 메뉴 항목 추가"),
                    remainingLimitations = emptyList(),
                ),
            ),
        )
        return DesktopConversionState.AnalysisReady(
            session = DesktopAnalysisSession(
                token = "00000000-0000-0000-0000-000000000001",
                input = DesktopExtensionInput.Local(Path.of("extension.zip")),
                profile = BrowserProfile(
                    browserPackage = "com.sec.android.app.sbrowser",
                    versionName = "30.0.2.61",
                    versionCode = 300000261,
                    majorVersion = 30,
                    supportedApis = setOf("storage", "contextMenus"),
                    wrapperContract = WrapperContract.complete(),
                ),
                report = report,
                logicalSourceIdentity = LogicalSourceIdentityResult.Stable(
                    "abcdefghijklmnopabcdefghijklmnop",
                    LogicalIdentityEvidence.MANIFEST_KEY,
                ),
                selectedAutomaticAdapters = setOf("context-menu-mobile"),
                requiredApprovals = emptySet(),
            ),
            reviewStep = DesktopReviewStep.ANALYSIS,
            selectedAdapters = emptySet(),
            partialConfirmed = false,
            projectIdentity = null,
        )
    }
}
