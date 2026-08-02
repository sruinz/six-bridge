package dev.sixbridge.core.compat

import kotlin.test.Test
import kotlin.test.assertEquals

class AnalysisControlTest {
    @Test
    fun `기존 trailing lambda는 취소 checkpoint로 유지한다`() {
        var checkpoints = 0
        val control = AnalysisControl { checkpoints++ }

        control.checkCancelled()

        assertEquals(1, checkpoints)
    }

    @Test
    fun `같은 분석 단계는 callback을 한 번만 전달한다`() {
        val stages = mutableListOf<AnalysisStage>()
        val control = AnalysisControl(onStage = stages::add)

        control.stage(AnalysisStage.API_SCAN)
        control.stage(AnalysisStage.API_SCAN)
        control.stage(AnalysisStage.FINALIZING)

        assertEquals(listOf(AnalysisStage.API_SCAN, AnalysisStage.FINALIZING), stages)
    }
}
