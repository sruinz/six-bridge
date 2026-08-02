package dev.sixbridge.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import dev.sixbridge.desktop.app.DesktopConversionState
import dev.sixbridge.desktop.app.DesktopReviewStep
import dev.sixbridge.desktop.profile.DesktopProfileOption
import java.nio.file.Path

sealed interface DesktopAction {
    data class UrlChanged(val value: String) : DesktopAction
    data class LocalInputSelected(val path: Path) : DesktopAction
    data class ProfileSelected(val id: String) : DesktopAction
    data class ProfileJsonSelected(val path: Path) : DesktopAction
    data class BrowserApkSelected(val path: Path) : DesktopAction
    data object Analyze : DesktopAction
    data object ContinueToApproval : DesktopAction
    data object Back : DesktopAction
    data object Cancel : DesktopAction
    data class AdapterToggled(val id: String) : DesktopAction
    data class PartialConfirmationChanged(val confirmed: Boolean) : DesktopAction
    data class ProjectIdentitySelected(val value: String) : DesktopAction
    data object BuildDraft : DesktopAction
    data class SaveDraft(val destination: Path) : DesktopAction
    data object RevealSavedDraft : DesktopAction
    data object NewConversion : DesktopAction
}

@Composable
fun DesktopApp(
    state: DesktopConversionState,
    profiles: List<DesktopProfileOption>,
    onAction: (DesktopAction) -> Unit,
) {
    DesktopTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when (state) {
                is DesktopConversionState.Input -> InputStep(state, profiles, onAction)
                is DesktopConversionState.Analyzing -> AnalyzingStep(state) {
                    onAction(DesktopAction.Cancel)
                }
                is DesktopConversionState.AnalysisReady -> if (state.reviewStep == DesktopReviewStep.ANALYSIS) {
                    AnalysisStep(state, onAction)
                } else {
                    ApprovalStep(state, onAction)
                }
                is DesktopConversionState.Failed -> {
                    val result = state.previous as? DesktopConversionState.DraftReady
                    if (state.stage in setOf(
                            dev.sixbridge.desktop.app.DesktopFailureStage.SAVE,
                            dev.sixbridge.desktop.app.DesktopFailureStage.REVEAL,
                        ) && result != null
                    ) {
                        ResultStep(result.copy(message = state.message), onAction)
                    } else {
                        FailureStep(state.message, onAction)
                    }
                }
                is DesktopConversionState.BuildingDraft -> BuildingDraftStep(onAction)
                is DesktopConversionState.DraftReady -> ResultStep(state, onAction)
            }
        }
    }
}
@Composable
private fun FailureStep(message: String, onAction: (DesktopAction) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            message,
            color = MaterialTheme.colors.error,
            modifier = Modifier.semantics { testTag = "analysis:error" },
        )
        Button(
            onClick = { onAction(DesktopAction.Back) },
            modifier = Modifier.semantics { testTag = "analysis:error-back" },
        ) {
            Text("입력 단계로 돌아가기")
        }
    }
}
