package dev.sixbridge.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
    var advancedExpanded by remember { mutableStateOf(false) }
    val feedback = remember(onAction) { DesktopInputFeedback(onAction) }
    val dropTarget = remember(feedback) {
        DesktopDropTarget(
            onLocalPath = feedback::selectLocal,
            onRejected = feedback::reject,
        )
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val compact = maxWidth < 1120.dp
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(if (compact) 24.dp else 32.dp),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            DesktopStartHeader()
            if (compact) {
                DesktopStepRail(
                    compact = true,
                    inputReady = state.selectedInput != null,
                    profileReady = state.selectedProfile?.selectable == true,
                )
                DesktopInputWorkspace(state, profiles, feedback, dropTarget, advancedExpanded, {
                    advancedExpanded = it
                }, onAction)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(28.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    DesktopStepRail(
                        compact = false,
                        inputReady = state.selectedInput != null,
                        profileReady = state.selectedProfile?.selectable == true,
                        modifier = Modifier.weight(0.25f),
                    )
                    DesktopInputWorkspace(
                        state,
                        profiles,
                        feedback,
                        dropTarget,
                        advancedExpanded,
                        { advancedExpanded = it },
                        onAction,
                        modifier = Modifier.weight(0.75f),
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopInputWorkspace(
    state: DesktopConversionState.Input,
    profiles: List<DesktopProfileOption>,
    feedback: DesktopInputFeedback,
    dropTarget: DesktopDropTarget,
    advancedExpanded: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    onAction: (DesktopAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colors.onSurface.copy(alpha = 0.14f),
                RoundedCornerShape(12.dp),
            ),
        shape = RoundedCornerShape(12.dp),
        elevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("확장 선택", style = MaterialTheme.typography.h5, fontWeight = FontWeight.Bold)
            Text(
                "Chrome 웹 스토어 URL을 입력하거나 로컬 파일을 선택하세요.",
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    color = MaterialTheme.colors.primary.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("웹 스토어 URL", fontWeight = FontWeight.SemiBold)
                    }
                }
                Surface(
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.05f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("로컬 파일", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            OutlinedTextField(
                value = state.urlText,
                onValueChange = feedback::changeUrl,
                label = { Text("Chrome 웹 스토어 URL") },
                leadingIcon = { Icon(Icons.Default.Language, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { testTag = "input:url" },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = {
                        DesktopFileDialogs.pickFolder()?.let(feedback::selectLocal)
                    },
                    modifier = Modifier.semantics { testTag = "input:pick-folder" },
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("폴더 선택")
                }
                OutlinedButton(
                    onClick = {
                        DesktopFileDialogs.pickArchive()?.let(feedback::selectLocal)
                    },
                    modifier = Modifier.semantics { testTag = "input:pick-archive" },
                ) {
                    Icon(Icons.Default.Archive, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("ZIP/CRX3 선택")
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 156.dp)
                    .border(
                        1.dp,
                        MaterialTheme.colors.primary.copy(alpha = 0.72f),
                        RoundedCornerShape(10.dp),
                    )
                    .dragAndDropTarget(
                        shouldStartDragAndDrop = dropTarget::accepts,
                        target = dropTarget,
                    )
                    .semantics { testTag = "input:drop" }
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Default.FileDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colors.primary,
                )
                Text("로컬 파일 또는 폴더 하나를 여기에 드래그하세요.", fontWeight = FontWeight.SemiBold)
                Text(
                    "폴더 또는 ZIP/CRX3 파일을 지원합니다.",
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.58f),
                )
                (feedback.dropMessage ?: state.message)?.let {
                    Text(it, color = MaterialTheme.colors.error)
                }
            }
            state.selectedInput?.let {
                Text(
                    text = "선택된 입력: ${inputLabel(it)}",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.72f),
                )
            }
            DesktopProfilePanel(
                selectedProfile = state.selectedProfile,
                profiles = profiles,
                expanded = advancedExpanded,
                onExpandedChange = onAdvancedExpandedChange,
                onProfileSelected = { onAction(DesktopAction.ProfileSelected(it)) },
                onPickProfileJson = {
                    DesktopFileDialogs.pickProfileJson()?.let {
                        onAction(DesktopAction.ProfileJsonSelected(it))
                    }
                },
                onPickBrowserApk = {
                    DesktopFileDialogs.pickBrowserApk()?.let {
                        onAction(DesktopAction.BrowserApkSelected(it))
                    }
                },
            )
            DesktopReadinessBar(
                input = state.selectedInput,
                profile = state.selectedProfile,
                onAnalyze = { onAction(DesktopAction.Analyze) },
            )
        }
    }
}

private fun inputLabel(input: DesktopExtensionInput): String = when (input) {
    is DesktopExtensionInput.ChromeStore -> input.url
    is DesktopExtensionInput.Local -> input.path.toString()
}
