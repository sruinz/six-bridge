package dev.sixbridge.core.compat

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AnalysisInventoryTest {
    @Test
    fun `모바일 개별 스크립트 2MiB 초과를 읽기 전에 거부한다`() {
        val root = extensionRoot()
        Files.write(root.resolve("large.js"), ByteArray(2 * 1024 * 1024 + 1) { 'a'.code.toByte() })

        val result = AnalysisInventory.inspect(
            root,
            AnalysisSafetyPolicy.mobile,
        )

        assertEquals(
            AnalysisInventoryResult.Exceeded(
                metric = AnalysisBudgetMetric.SINGLE_SCRIPT_BYTES,
                actual = 2L * 1024 * 1024 + 1,
                limit = 2L * 1024 * 1024,
            ),
            result,
        )
    }

    @Test
    fun `모바일 전체 스크립트 16MiB 초과를 거부한다`() {
        val root = extensionRoot()
        repeat(9) { index ->
            Files.write(root.resolve("$index.js"), ByteArray(2 * 1024 * 1024) { 'a'.code.toByte() })
        }

        val result = AnalysisInventory.inspect(root, AnalysisSafetyPolicy.mobile)

        assertIs<AnalysisInventoryResult.Exceeded>(result)
        assertEquals(AnalysisBudgetMetric.TOTAL_SCRIPT_BYTES, result.metric)
    }

    @Test
    fun `모바일 분석 파일 2048개 초과를 거부한다`() {
        val root = extensionRoot()
        repeat(2_049) { Files.writeString(root.resolve("$it.js"), "") }

        val result = AnalysisInventory.inspect(root, AnalysisSafetyPolicy.mobile)

        assertIs<AnalysisInventoryResult.Exceeded>(result)
        assertEquals(AnalysisBudgetMetric.SCRIPT_FILE_COUNT, result.metric)
    }

    private fun extensionRoot() = Files.createTempDirectory("six-analysis-inventory").also { root ->
        Files.writeString(
            root.resolve("manifest.json"),
            """{"manifest_version":3,"name":"Test","version":"1"}""",
        )
    }
}
