package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AnalyzedConversionReportTest {
    @Test
    fun `verified CRX proof ID는 manifest report ID보다 우선한다`() {
        val report = report(manifestKeyExtensionId = "b".repeat(32))

        val retained = retainAnalyzedOriginalExtensionId(report, "a".repeat(32))

        assertEquals("a".repeat(32), retained.originalExtensionId)
    }

    @Test
    fun `proof ID가 없으면 manifest report ID를 유지한다`() {
        val report = report(manifestKeyExtensionId = "b".repeat(32))

        val retained = retainAnalyzedOriginalExtensionId(report, null)

        assertEquals("b".repeat(32), retained.originalExtensionId)
    }

    @Test
    fun `proof와 manifest report ID가 모두 없으면 null을 유지한다`() {
        val retained = retainAnalyzedOriginalExtensionId(report(), null)

        assertNull(retained.originalExtensionId)
    }

    @Test
    fun `ID 보존은 원본 report를 변경하지 않는다`() {
        val report = report(
            manifestKeyExtensionId = "b".repeat(32),
            originalExtensionId = "c".repeat(32),
        )

        retainAnalyzedOriginalExtensionId(report, "a".repeat(32))

        assertEquals("c".repeat(32), report.originalExtensionId)
    }

    private fun report(
        manifestKeyExtensionId: String? = null,
        originalExtensionId: String? = null,
    ) = CompatibilityReport(
        status = CompatibilityStatus.COMPATIBLE,
        manifestVersion = 3,
        extensionName = "test",
        extensionVersion = "1",
        manifestKeyExtensionId = manifestKeyExtensionId,
        declaredPermissions = emptySet(),
        usedApis = emptySet(),
        unsupportedApis = emptySet(),
        issues = emptyList(),
        originalExtensionId = originalExtensionId,
    )
}
