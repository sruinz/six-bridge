package dev.sixbridge.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import dev.sixbridge.core.compat.AnalysisStage
import dev.sixbridge.desktop.app.DesktopConversionState

@Composable
fun AnalyzingStep(
    state: DesktopConversionState.Analyzing,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text("확장 분석 중")
        Text(
            analysisStageLabel(state.stage),
            Modifier.semantics { testTag = "analysis:stage" },
        )
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.semantics { testTag = "analysis:cancel" },
        ) {
            Text("취소")
        }
    }
}

internal fun analysisStageLabel(stage: AnalysisStage): String = when (stage) {
    AnalysisStage.INPUT_VALIDATION -> "입력 검증"
    AnalysisStage.ARCHIVE_INVENTORY -> "확장 파일 목록 확인"
    AnalysisStage.RUNTIME_CLOSURE -> "실행 리소스 확인"
    AnalysisStage.API_SCAN -> "브라우저 API 사용 분석"
    AnalysisStage.MOBILE_PATTERNS -> "모바일 변환 패턴 분석"
    AnalysisStage.FINALIZING -> "분석 결과 정리"
}
