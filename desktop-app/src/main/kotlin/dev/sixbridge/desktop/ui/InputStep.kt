package dev.sixbridge.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.DropdownMenu
import androidx.compose.material.DropdownMenuItem
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import dev.sixbridge.desktop.app.DesktopConversionState
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import dev.sixbridge.desktop.profile.DesktopProfileOption

fun canAnalyze(
    input: DesktopExtensionInput?,
    profile: DesktopProfileOption?,
    busy: Boolean,
): Boolean = input != null && profile?.selectable == true && !busy

fun reduceInputState(
    state: DesktopConversionState.Input,
    action: DesktopAction,
    profiles: List<DesktopProfileOption>,
): DesktopConversionState.Input = when (action) {
    is DesktopAction.UrlChanged -> state.copy(
        urlText = action.value,
        selectedInput = action.value.trim().takeIf(String::isNotEmpty)
            ?.let(DesktopExtensionInput::ChromeStore),
        message = null,
    )
    is DesktopAction.LocalInputSelected -> state.copy(
        urlText = "",
        selectedInput = DesktopExtensionInput.Local(action.path),
        message = null,
    )
    is DesktopAction.ProfileSelected -> profiles.firstOrNull {
        it.id == action.id && it.selectable
    }?.let { state.copy(selectedProfile = it, message = null) } ?: state
    else -> state
}

internal class DesktopInputFeedback(
    private val onAction: (DesktopAction) -> Unit,
) {
    var dropMessage by mutableStateOf<String?>(null)
        private set

    fun reject(message: String) {
        dropMessage = message
    }

    fun selectLocal(path: java.nio.file.Path) {
        dropMessage = null
        onAction(DesktopAction.LocalInputSelected(path))
    }

    fun changeUrl(value: String) {
        dropMessage = null
        onAction(DesktopAction.UrlChanged(value))
    }
}

@Composable
fun InputStep(
    state: DesktopConversionState.Input,
    profiles: List<DesktopProfileOption>,
    onAction: (DesktopAction) -> Unit,
) {
    var profileMenuExpanded by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    val feedback = remember(onAction) { DesktopInputFeedback(onAction) }
    val dropTarget = remember(feedback) {
        DesktopDropTarget(
            onLocalPath = feedback::selectLocal,
            onRejected = feedback::reject,
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("SIX Bridge", style = MaterialTheme.typography.h4)
        Text("확장 프로그램 입력과 Samsung Internet profile을 선택하세요.")
        OutlinedTextField(
            value = state.urlText,
            onValueChange = feedback::changeUrl,
            label = { Text("Chrome 웹 스토어 URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().semantics { testTag = "input:url" },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = {
                    DesktopFileDialogs.pickFolder()?.let {
                        feedback.selectLocal(it)
                    }
                },
                modifier = Modifier.semantics { testTag = "input:pick-folder" },
            ) { Text("폴더 선택") }
            OutlinedButton(
                onClick = {
                    DesktopFileDialogs.pickArchive()?.let {
                        feedback.selectLocal(it)
                    }
                },
                modifier = Modifier.semantics { testTag = "input:pick-archive" },
            ) { Text("ZIP/CRX3 선택") }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colors.onSurface.copy(alpha = 0.35f))
                .dragAndDropTarget(
                    shouldStartDragAndDrop = dropTarget::accepts,
                    target = dropTarget,
                )
                .semantics { testTag = "input:drop" }
                .padding(28.dp),
        ) {
            Text("로컬 파일 또는 폴더 하나를 여기에 놓으세요.")
            (feedback.dropMessage ?: state.message)?.let {
                Text(it, color = MaterialTheme.colors.error)
            }
        }
        Column {
            OutlinedButton(
                onClick = { profileMenuExpanded = true },
                modifier = Modifier.semantics { testTag = "input:profile" },
            ) {
                Text(state.selectedProfile?.explanation ?: "Samsung Internet profile 선택")
            }
            DropdownMenu(
                expanded = profileMenuExpanded,
                onDismissRequest = { profileMenuExpanded = false },
            ) {
                profiles.forEach { profile ->
                    DropdownMenuItem(
                        enabled = profile.selectable,
                        onClick = {
                            profileMenuExpanded = false
                            onAction(DesktopAction.ProfileSelected(profile.id))
                        },
                    ) {
                        Text(profile.explanation)
                    }
                }
            }
        }
        Text(
            text = if (advancedExpanded) "고급 profile 접기" else "고급 profile 열기",
            color = MaterialTheme.colors.primary,
            modifier = Modifier
                .clickable { advancedExpanded = !advancedExpanded }
                .semantics { testTag = "input:advanced-profile" }
                .padding(vertical = 8.dp),
        )
        if (advancedExpanded) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        DesktopFileDialogs.pickProfileJson()?.let {
                            onAction(DesktopAction.ProfileJsonSelected(it))
                        }
                    },
                    modifier = Modifier.semantics { testTag = "input:pick-profile-json" },
                ) { Text("서명 profile JSON") }
                OutlinedButton(
                    onClick = {
                        DesktopFileDialogs.pickBrowserApk()?.let {
                            onAction(DesktopAction.BrowserApkSelected(it))
                        }
                    },
                    modifier = Modifier.semantics { testTag = "input:pick-browser-apk" },
                ) { Text("Samsung Internet APK") }
            }
        }
        state.selectedInput?.let { Text("선택된 입력: ${inputLabel(it)}") }
        Button(
            onClick = { onAction(DesktopAction.Analyze) },
            enabled = canAnalyze(state.selectedInput, state.selectedProfile, busy = false),
            modifier = Modifier.fillMaxWidth().semantics { testTag = "input:analyze" },
        ) {
            Text("분석")
        }
    }
}

private fun inputLabel(input: DesktopExtensionInput): String = when (input) {
    is DesktopExtensionInput.ChromeStore -> input.url
    is DesktopExtensionInput.Local -> input.path.toString()
}
