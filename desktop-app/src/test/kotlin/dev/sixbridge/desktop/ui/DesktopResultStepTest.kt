package dev.sixbridge.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.LogicalIdentityEvidence
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.desktop.app.DesktopConversionState
import dev.sixbridge.desktop.app.DesktopReviewStep
import dev.sixbridge.desktop.conversion.DesktopAnalysisSession
import dev.sixbridge.desktop.conversion.DesktopDraftArtifact
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DesktopResultStepTest {
    @Test
    fun `결과는 파일명 hash signer와 저장 동작을 표시한다`() = runComposeUiTest {
        setContent { ResultStep(draftReadyState(), {}) }

        onNodeWithTag("result:file-name").assertTextContains(".sixdraft", substring = true)
        onNodeWithTag("result:sha256").assertTextContains("a".repeat(64), substring = true)
        onNodeWithTag("result:signer").assertTextContains("b".repeat(64), substring = true)
        onNodeWithTag("result:save").assertIsEnabled()
        onNodeWithTag("result:reveal").assertIsNotEnabled()
    }

    @Test
    fun `저장 경로를 고르면 SaveDraft를 전달하고 저장 후에만 위치를 열 수 있다`() = runComposeUiTest {
        val destination = Path.of("saved.sixdraft")
        val actions = mutableListOf<DesktopAction>()
        setContent {
            ResultStep(
                state = draftReadyState(),
                onAction = actions::add,
                saveDraftPicker = { suggested ->
                    assertEquals("example-1.sixdraft", suggested)
                    destination
                },
            )
        }

        onNodeWithTag("result:save").performClick()
        runOnIdle { assertEquals(listOf<DesktopAction>(DesktopAction.SaveDraft(destination)), actions) }

        setContent {
            ResultStep(
                state = draftReadyState().copy(savedPath = destination.toAbsolutePath()),
                onAction = actions::add,
            )
        }
        onNodeWithTag("result:reveal").assertIsEnabled().performClick()
        onNodeWithTag("result:saved").assertTextEquals("저장됨: saved.sixdraft")
        runOnIdle { assertEquals(DesktopAction.RevealSavedDraft, actions.last()) }
    }

    @Test
    fun `초안 생성 중에는 취소만 제공한다`() = runComposeUiTest {
        val actions = mutableListOf<DesktopAction>()
        val ready = approvalState()
        setContent {
            DesktopApp(
                DesktopConversionState.BuildingDraft(9, ready),
                emptyList(),
                actions::add,
            )
        }

        onNodeWithTag("result:building").assertTextContains("생성", substring = true)
        onNodeWithTag("result:cancel").performClick()
        runOnIdle { assertEquals(listOf<DesktopAction>(DesktopAction.Cancel), actions) }
    }

    private fun draftReadyState() = DesktopConversionState.DraftReady(
        artifact = DesktopDraftArtifact(
            token = "00000000-0000-0000-0000-000000000009",
            privateFile = Path.of("/private/example-1.sixdraft"),
            suggestedFileName = "example-1.sixdraft",
            sha256 = "a".repeat(64),
            signerFingerprint = "b".repeat(64),
        ),
    )

    private fun approvalState(): DesktopConversionState.AnalysisReady =
        DesktopConversionState.AnalysisReady(
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
                    status = CompatibilityStatus.COMPATIBLE,
                    manifestVersion = 3,
                    extensionName = "예시 확장",
                    extensionVersion = "1.0",
                    declaredPermissions = emptySet(),
                    usedApis = emptySet(),
                    unsupportedApis = emptySet(),
                    issues = emptyList(),
                ),
                logicalSourceIdentity = LogicalSourceIdentityResult.Stable(
                    "abcdefghijklmnopabcdefghijklmnop",
                    LogicalIdentityEvidence.MANIFEST_KEY,
                ),
                selectedAutomaticAdapters = emptySet(),
                requiredApprovals = emptySet(),
            ),
            reviewStep = DesktopReviewStep.APPROVAL,
            selectedAdapters = emptySet(),
            partialConfirmed = false,
            projectIdentity = null,
        )
}
