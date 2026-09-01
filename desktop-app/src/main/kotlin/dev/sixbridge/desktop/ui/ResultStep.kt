package dev.sixbridge.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import dev.sixbridge.desktop.app.DesktopConversionState
import java.nio.file.Path

@Composable
fun BuildingDraftStep(onAction: (DesktopAction) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            "변환 초안을 생성하는 중입니다.",
            modifier = Modifier.semantics { testTag = "result:building" },
        )
        OutlinedButton(
            onClick = { onAction(DesktopAction.Cancel) },
            modifier = Modifier.semantics { testTag = "result:cancel" },
        ) {
            Text("취소")
        }
    }
}

@Composable
fun ResultStep(
    state: DesktopConversionState.DraftReady,
    onAction: (DesktopAction) -> Unit,
    saveDraftPicker: (String) -> Path? = DesktopFileDialogs::saveDraft,
) {
    val artifact = state.artifact
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("변환 초안 준비 완료", style = MaterialTheme.typography.h4)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    artifact.suggestedFileName,
                    modifier = Modifier.semantics { testTag = "result:file-name" },
                )
                Text(
                    "SHA-256: ${artifact.sha256}",
                    modifier = Modifier.semantics { testTag = "result:sha256" },
                )
                Text(
                    "Signer: ${artifact.signerFingerprint}",
                    modifier = Modifier.semantics { testTag = "result:signer" },
                )
                state.savedPath?.let {
                    Text(
                        "저장됨: ${it.fileName}",
                        modifier = Modifier.semantics { testTag = "result:saved" },
                    )
                }
            }
        }
        artifact.forcedCompatibility?.let { forced ->
            Card(
                Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                    testTag = "result:forced-compatibility"
                },
            ) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("강제 변환 · 원래 상태 ${forced.originalStatus.name}")
                    forced.issueCodes.sorted().forEach { Text("issue: $it") }
                    Text("호환성은 보장되지 않습니다.")
                }
            }
        }
        state.message?.let {
            Text(
                it,
                color = MaterialTheme.colors.error,
                modifier = Modifier.semantics { testTag = "result:error" },
            )
        }
        if (state.isSaving) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text("안전하게 저장하는 중입니다.")
            }
        }
        Button(
            onClick = {
                saveDraftPicker(artifact.suggestedFileName)?.let {
                    onAction(DesktopAction.SaveDraft(it))
                }
            },
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "result:save" },
        ) {
            Text("파일로 저장")
        }
        OutlinedButton(
            onClick = { onAction(DesktopAction.RevealSavedDraft) },
            enabled = state.savedPath != null && !state.isSaving,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "result:reveal" },
        ) {
            Text("파일 위치 열기")
        }
        OutlinedButton(
            onClick = { onAction(DesktopAction.NewConversion) },
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "result:new" },
        ) {
            Text("새 변환")
        }
    }
}
