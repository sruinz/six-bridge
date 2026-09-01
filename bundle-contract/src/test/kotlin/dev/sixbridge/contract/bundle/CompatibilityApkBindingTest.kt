package dev.sixbridge.contract.bundle

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CompatibilityApkBindingTest {
    @Test
    fun `canonical compatibility digest는 결정적이고 force 변경을 반영한다`() {
        val report = forcedReport()

        assertEquals("assets/sixbridge/compatibility.sha256", CompatibilityApkBinding.ENTRY_NAME)
        assertEquals(64, CompatibilityApkBinding.ENCODED_SIZE)
        assertContentEquals(
            CompatibilityApkBinding.encode(report),
            CompatibilityApkBinding.encode(report),
        )
        assertNotEquals(
            CompatibilityApkBinding.canonicalSha256(report),
            CompatibilityApkBinding.canonicalSha256(
                report.copy(forcedCompatibility = null),
            ),
        )
    }

    @Test
    fun `canonical compatibility digest는 status issue source profile 변경을 반영한다`() {
        val report = normalReport()
        val digest = CompatibilityApkBinding.canonicalSha256(report)

        assertNotEquals(digest, CompatibilityApkBinding.canonicalSha256(report.copy(status = ContractCompatibilityStatus.PARTIAL)))
        assertNotEquals(
            digest,
            CompatibilityApkBinding.canonicalSha256(
                report.copy(issues = listOf(ContractIssue("different", "issue", false))),
            ),
        )
        assertNotEquals(digest, CompatibilityApkBinding.canonicalSha256(report.copy(sourceSha256 = "e".repeat(64))))
        assertNotEquals(digest, CompatibilityApkBinding.canonicalSha256(report.copy(browserProfileSha256 = "f".repeat(64))))
    }

    @Test
    fun `encoded digest는 lowercase ASCII 64 bytes이고 malformed input을 거부한다`() {
        val encoded = CompatibilityApkBinding.encode(normalReport())

        assertEquals(64, encoded.size)
        // .six ZIP은 서명되지 않았으므로 Task 3·Task 7의 asset 대조 전에는 설치 권한을 부여하지 않는다.
        assertTrue(encoded.decodeToString().matches(Regex("[0-9a-f]{64}")))
        assertContentEquals(encoded, CompatibilityApkBinding.decode(encoded).encodeToByteArray())
        listOf(
            "a".repeat(63).encodeToByteArray(),
            "a".repeat(65).encodeToByteArray(),
            ("A" + "a".repeat(63)).encodeToByteArray(),
            ("a".repeat(63) + "\n").encodeToByteArray(),
        ).forEach { malformed ->
            assertFailsWith<IllegalArgumentException> { CompatibilityApkBinding.decode(malformed) }
        }
    }

    private fun normalReport() = ContractCompatibilityReport(
        schemaVersion = 6,
        status = ContractCompatibilityStatus.COMPATIBLE,
        manifestVersion = 3,
        extensionName = "Tiny",
        extensionVersion = "1.0.0",
        manifestKeyPresent = false,
        manifestKeyExtensionId = null,
        declaredPermissions = listOf("storage"),
        usedApis = listOf("storage"),
        unsupportedApis = emptyList(),
        issues = emptyList(),
        conversionState = "requires-approval",
        adapterDecisions = emptyList(),
        originalExtensionId = "abcdefghijklmnopabcdefghijklmnop",
        convertedExtensionId = "abcdefghijklmnopabcdefghijklmnop",
        sourceSha256 = "c".repeat(64),
        browserProfileSha256 = "d".repeat(64),
        forcedCompatibility = null,
    )

    private fun forcedReport() = normalReport().copy(
        status = ContractCompatibilityStatus.UNKNOWN,
        issues = listOf(ContractIssue("api_usage_dynamic", "dynamic", true)),
        forcedCompatibility = ContractForcedCompatibility(
            scope = "UNKNOWN_API_USAGE",
            originalStatus = ContractCompatibilityStatus.UNKNOWN,
            issueCodes = listOf("api_usage_dynamic"),
        ),
    )
}
