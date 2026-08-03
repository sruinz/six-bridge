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
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.SourceEvidence
import dev.sixbridge.desktop.app.DesktopConversionState

fun canContinueToApproval(status: CompatibilityStatus): Boolean =
    status == CompatibilityStatus.COMPATIBLE || status == CompatibilityStatus.PARTIAL

@Composable
fun AnalysisStep(
    state: DesktopConversionState.AnalysisReady,
    onAction: (DesktopAction) -> Unit,
) {
    val report = state.session.report
    val extensionId = report.originalExtensionId
        ?: report.convertedExtensionId
        ?: report.manifestKeyExtensionId
        ?: "확인되지 않음"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("분석 결과", style = MaterialTheme.typography.h4)
        AnalysisSection("확장 정보") {
            TaggedText(
                "analysis:identity",
                "이름: ${report.extensionName ?: "확인되지 않음"}\n" +
                    "버전: ${report.extensionVersion ?: "확인되지 않음"}\n" +
                    "ID: $extensionId",
            )
        }
        AnalysisSection("최종 호환성") {
            TaggedText("analysis:status", report.status.name, style = MaterialTheme.typography.h5)
            Text(compatibilityExplanation(report.status))
        }
        AnalysisSection("선언 권한") {
            TaggedText("analysis:permissions", reportList(report.declaredPermissions))
        }
        AnalysisSection("검증된 API") {
            TaggedText("analysis:apis:verified", reportList(report.verifiedApis))
        }
        AnalysisSection("발견됐지만 미검증 또는 제한된 API") {
            TaggedText("analysis:apis:unverified", reportList(report.unverifiedApis))
        }
        AnalysisSection("없거나 실패한 API") {
            TaggedText("analysis:apis:unavailable", reportList(report.unavailableApis))
        }
        AnalysisSection("분석 issue") {
            TaggedText(
                "analysis:issues",
                report.issues.sortedByDescending { it.blocking }
                    .joinToString("\n") { issue ->
                        "${if (issue.blocking) "[차단]" else "[알림]"} ${issue.code}: ${issue.message}"
                    }
                    .ifEmpty { "없음" },
                color = if (report.issues.any { it.blocking }) {
                    MaterialTheme.colors.error
                } else {
                    MaterialTheme.colors.onSurface
                },
            )
        }
        AnalysisSection("모바일 adapter 근거") {
            TaggedText(
                "analysis:adapters",
                report.adapterDecisions.joinToString("\n\n", transform = ::adapterReport)
                    .ifEmpty { "없음" },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedButton(onClick = { onAction(DesktopAction.Back) }) { Text("뒤로") }
            OutlinedButton(onClick = { onAction(DesktopAction.Cancel) }) { Text("취소") }
            Button(
                onClick = { onAction(DesktopAction.ContinueToApproval) },
                enabled = canContinueToApproval(report.status),
                modifier = Modifier.weight(1f).semantics { testTag = "analysis:continue" },
            ) {
                Text("승인 단계로 계속")
            }
        }
    }
}

@Composable
private fun AnalysisSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth(),
        elevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.h6)
            content()
        }
    }
}

@Composable
private fun TaggedText(
    tag: String,
    text: String,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
) = Text(text, Modifier.semantics { testTag = tag }, color = color, style = style)

private fun reportList(values: Collection<String>): String =
    values.sorted().joinToString("\n") { "• $it" }.ifEmpty { "없음" }

private fun adapterReport(adapter: AdapterDecision): String = buildString {
    append("${adapter.id} v${adapter.version} · ${adapter.disposition}")
    adapter.evidence.forEach { append("\n${evidenceLabel(it)}") }
    adapter.changes.forEach { append("\n변경: $it") }
    adapter.remainingLimitations.forEach { append("\n제한: $it") }
}

private fun evidenceLabel(evidence: SourceEvidence): String = buildString {
    append("근거: ")
    append(evidence.relativePath)
    evidence.line?.let { append(":$it") }
    append(" · ")
    append(evidence.detail)
}

private fun compatibilityExplanation(status: CompatibilityStatus): String = when (status) {
    CompatibilityStatus.COMPATIBLE -> "지원 범위에서 변환을 계속할 수 있습니다."
    CompatibilityStatus.PARTIAL -> "제한 사항을 확인한 뒤 계속할 수 있습니다."
    CompatibilityStatus.BLOCKED -> "차단 issue가 있어 변환을 계속할 수 없습니다."
    CompatibilityStatus.UNKNOWN -> "안전한 변환 여부를 판단할 수 없어 계속할 수 없습니다."
}
