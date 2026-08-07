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
import androidx.compose.material.Checkbox
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.unit.dp
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.AdapterRisk
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.desktop.app.DesktopConversionState
import java.security.SecureRandom

private val projectIdentityPattern = Regex("[0-9a-f]{32}")

fun canBuildDraft(state: DesktopConversionState.AnalysisReady): Boolean {
    val allowedApprovals = state.session.requiredApprovals + state.session.optionalApprovals
    val approvalsReady = state.selectedAdapters.containsAll(state.session.requiredApprovals) &&
        allowedApprovals.containsAll(state.selectedAdapters) &&
        state.resolvedOptionalAdapters.containsAll(state.session.optionalApprovals)
    val partialReady = state.session.report.status != CompatibilityStatus.PARTIAL || state.partialConfirmed
    val identityReady = state.session.logicalSourceIdentity !is LogicalSourceIdentityResult.NeedsUserSelection ||
        state.projectIdentity?.matches(projectIdentityPattern) == true
    return approvalsReady && partialReady && identityReady
}

internal fun generateProjectIdentity(random: SecureRandom = SecureRandom()): String =
    ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

@Composable
fun ApprovalStep(
    state: DesktopConversionState.AnalysisReady,
    onAction: (DesktopAction) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("변환 승인", style = MaterialTheme.typography.h4)
        Text("적용할 모바일 adapter와 남는 제한을 확인하세요.")

        state.session.report.adapterDecisions.forEach { decision ->
            AdapterApproval(decision, state, onAction)
        }

        if (state.session.report.status == CompatibilityStatus.PARTIAL) {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = state.partialConfirmed,
                        onCheckedChange = {
                            onAction(DesktopAction.PartialConfirmationChanged(it))
                        },
                        modifier = Modifier.semantics { testTag = "approval:partial" },
                    )
                    Text("부분 호환으로 남는 제한을 확인했습니다.")
                }
            }
        }

        if (state.session.logicalSourceIdentity is LogicalSourceIdentityResult.NeedsUserSelection) {
            ProjectIdentityApproval(state, onAction)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { onAction(DesktopAction.Back) }) { Text("뒤로") }
            OutlinedButton(onClick = { onAction(DesktopAction.Cancel) }) { Text("취소") }
            Button(
                onClick = { onAction(DesktopAction.BuildDraft) },
                enabled = canBuildDraft(state),
                modifier = Modifier.weight(1f).semantics { testTag = "approval:build" },
            ) {
                Text("변환 초안 생성")
            }
        }
    }
}

@Composable
private fun AdapterApproval(
    decision: AdapterDecision,
    state: DesktopConversionState.AnalysisReady,
    onAction: (DesktopAction) -> Unit,
) {
    val automatic = decision.disposition == AdapterDisposition.AUTOMATIC
    val requiresApproval = decision.disposition == AdapterDisposition.REQUIRES_APPROVAL
    val optionalApproval = decision.disposition == AdapterDisposition.OPTIONAL_APPROVAL
    val checked = when {
        automatic -> decision.id in state.session.selectedAutomaticAdapters
        requiresApproval -> decision.id in state.selectedAdapters
        else -> false
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!optionalApproval) {
                    Checkbox(
                        checked = checked,
                        enabled = requiresApproval,
                        onCheckedChange = if (requiresApproval) {
                            { onAction(DesktopAction.AdapterToggled(decision.id)) }
                        } else {
                            null
                        },
                        modifier = Modifier.semantics {
                            testTag = "approval:adapter:${decision.id}"
                            if (!requiresApproval) disabled()
                        },
                    )
                }
                Column {
                    Text("${decision.id} v${decision.version}", style = MaterialTheme.typography.h6)
                    Text(
                        when (decision.disposition) {
                            AdapterDisposition.AUTOMATIC -> "안전 adapter · 자동 적용"
                            AdapterDisposition.REQUIRES_APPROVAL -> if (
                                decision.risk == AdapterRisk.BEHAVIOR_CHANGE
                            ) {
                                "동작 변경 · 사용자 승인 필요"
                            } else {
                                "안전 adapter · 사용자 승인 필요"
                            }
                            AdapterDisposition.OPTIONAL_APPROVAL -> "선택 adapter · 명시적 결정 필요"
                            AdapterDisposition.UNAVAILABLE -> "현재 profile에서 사용할 수 없음"
                        },
                    )
                }
            }
            if (optionalApproval) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = decision.id in state.resolvedOptionalAdapters &&
                            decision.id in state.selectedAdapters,
                        onClick = {
                            onAction(DesktopAction.OptionalAdapterChosen(decision.id, apply = true))
                        },
                        modifier = Modifier.semantics {
                            testTag = "approval:optional:${decision.id}:apply"
                        },
                    )
                    Text("보호 적용")
                    RadioButton(
                        selected = decision.id in state.resolvedOptionalAdapters &&
                            decision.id !in state.selectedAdapters,
                        onClick = {
                            onAction(DesktopAction.OptionalAdapterChosen(decision.id, apply = false))
                        },
                        modifier = Modifier.semantics {
                            testTag = "approval:optional:${decision.id}:original"
                        },
                    )
                    Text("원본 유지")
                }
                Text(
                    "원본 유지 시 자동 입력으로 인한 브라우저 종료 위험이 남으며 결과는 PARTIAL로 유지됩니다.",
                    modifier = Modifier.semantics {
                        testTag = "approval:optional:${decision.id}:risk"
                    },
                )
            }
            decision.changes.forEach { Text("변경: $it") }
            decision.remainingLimitations.forEach { Text("남는 제한: $it") }
        }
    }
}

@Composable
private fun ProjectIdentityApproval(
    state: DesktopConversionState.AnalysisReady,
    onAction: (DesktopAction) -> Unit,
) {
    var manualValue by remember(state.session.token) { mutableStateOf("") }
    val manualValid = projectIdentityPattern.matches(manualValue)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Project identity 선택", style = MaterialTheme.typography.h6)
            Text(
                "project marker가 없습니다. 기존 설치 대상을 추정하지 않습니다.",
                modifier = Modifier.semantics { testTag = "approval:identity-required" },
            )
            Button(
                onClick = {
                    onAction(DesktopAction.ProjectIdentitySelected(generateProjectIdentity()))
                },
                modifier = Modifier.semantics { testTag = "approval:identity-generate" },
            ) {
                Text("새 project identity 생성")
            }
            OutlinedTextField(
                value = manualValue,
                onValueChange = { value ->
                    manualValue = value
                    if (projectIdentityPattern.matches(value)) {
                        onAction(DesktopAction.ProjectIdentitySelected(value))
                    }
                },
                label = { Text("이전 변환의 32자 project identity") },
                isError = manualValue.isNotEmpty() && !manualValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().semantics { testTag = "approval:identity-input" },
            )
            state.projectIdentity?.let { Text("선택됨: $it") }
        }
    }
}
