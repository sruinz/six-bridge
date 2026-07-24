package dev.sixbridge.core.draft

import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SixDraftFixReview3Test {
    @Test
    fun `typed compatibility writer rejects more than twenty thousand JSON keys`() {
        val report = reportWithIssues(7_000)

        val failure = assertFailsWith<IllegalArgumentException> {
            SixDraftJson.encodeCompatibility(report)
        }

        assertTrue(
            failure.message.orEmpty().contains("key") ||
                failure.message.orEmpty().contains("token"),
        )
    }

    @Test
    fun `typed compatibility below limits roundtrips through strict reader`() {
        val report = reportWithIssues(6_500)

        val bytes = SixDraftJson.encodeCompatibility(report)
        assertTrue(bytes.size < SixDraft.MAX_METADATA_SIZE)
        assertEquals(
            report,
            SixDraftJson.decodeCompatibility("compatibility.json", bytes),
        )
    }

    @Test
    fun `typed writer rejects lone high and low surrogates before UTF8 encoding`() {
        listOf("\uD800", "\uDC00").forEach { loneSurrogate ->
            val report = reportWithIssues(0).copy(
                extensionName = "invalid-$loneSurrogate",
            )

            assertFailsWith<IllegalArgumentException> {
                SixDraftJson.encodeCompatibility(report)
            }
        }
    }

    @Test
    fun `typed writer preserves a valid surrogate pair through strict reader`() {
        val extensionName = "Rocket-\uD83D\uDE80"
        val report = reportWithIssues(0).copy(extensionName = extensionName)

        val decoded = SixDraftJson.decodeCompatibility(
            "compatibility.json",
            SixDraftJson.encodeCompatibility(report),
        )

        assertEquals(extensionName, decoded.extensionName)
        assertEquals(report, decoded)
    }

    private fun reportWithIssues(count: Int): CompatibilityReport =
        CompatibilityReport(
            status = CompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = "Typed writer limit",
            extensionVersion = "1.0.0",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = List(count) {
                CompatibilityIssue("note", "bounded", false)
            },
            sourceSha256 = "1".repeat(64),
            browserProfileSha256 = "2".repeat(64),
        )
}
