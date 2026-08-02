package dev.sixbridge.desktop.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.sixbridge.core.compat.AnalysisStage
import dev.sixbridge.desktop.app.DesktopConversionState
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DesktopAnalyzingStepTest {
    @Test
    fun `분석 중 화면은 현재 단계를 한글로 표시하고 취소를 전달한다`() = runComposeUiTest {
        var actions = emptyList<DesktopAction>()
        setContent {
            DesktopApp(
                state = DesktopConversionState.Analyzing(7L, AnalysisStage.API_SCAN),
                profiles = emptyList(),
                onAction = { actions = actions + it },
            )
        }

        onNodeWithTag("analysis:stage").assertTextEquals("브라우저 API 사용 분석")
        onNodeWithTag("analysis:cancel").performClick()
        runOnIdle { assertEquals(listOf<DesktopAction>(DesktopAction.Cancel), actions) }
    }

    @Test
    fun `모든 분석 단계 label은 한글 문구로 고정한다`() {
        assertEquals("입력 검증", analysisStageLabel(AnalysisStage.INPUT_VALIDATION))
        assertEquals("확장 파일 목록 확인", analysisStageLabel(AnalysisStage.ARCHIVE_INVENTORY))
        assertEquals("실행 리소스 확인", analysisStageLabel(AnalysisStage.RUNTIME_CLOSURE))
        assertEquals("브라우저 API 사용 분석", analysisStageLabel(AnalysisStage.API_SCAN))
        assertEquals("모바일 변환 패턴 분석", analysisStageLabel(AnalysisStage.MOBILE_PATTERNS))
        assertEquals("분석 결과 정리", analysisStageLabel(AnalysisStage.FINALIZING))
    }
}
