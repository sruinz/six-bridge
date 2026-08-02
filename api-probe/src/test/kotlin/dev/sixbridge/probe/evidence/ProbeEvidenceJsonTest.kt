package dev.sixbridge.probe.evidence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProbeEvidenceJsonTest {
    @Test
    fun `유효한 evidence를 canonical JSON으로 결정론적으로 round trip한다`() {
        val decoded = ProbeEvidenceJson.decodeStrict(validEvidence())

        assertEquals("session-20260802", decoded.sessionId)
        assertEquals("base-runtime", decoded.groupId)
        assertEquals(2, decoded.results.size)
        assertEquals(
            "{\"kind\":\"success\",\"nested\":{\"a\":true,\"z\":2},\"valueType\":\"object\"}",
            decoded.results.single { it.caseId == "runtime.getPlatformInfo" }.detail,
        )

        val encoded = ProbeEvidenceJson.encodeCanonical(decoded)
        assertEquals(encoded, ProbeEvidenceJson.encodeCanonical(ProbeEvidenceJson.decodeStrict(encoded)))
    }

    @Test
    fun `root와 result의 unknown 및 duplicate field를 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(
                validEvidence().replace(
                    "\"schemaVersion\": 1",
                    "\"schemaVersion\": 1,\"schemaVersion\": 1",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(
                validEvidence().replace("\"results\": [", "\"unexpected\":true,\"results\": ["),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(
                validEvidence().replace(
                    "\"caseId\": \"runtime.getPlatformInfo\"",
                    "\"caseId\": \"runtime.getPlatformInfo\",\"caseId\":\"runtime.getPlatformInfo\"",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(
                validEvidence().replace(
                    "\"status\": \"VERIFIED\"",
                    "\"status\": \"VERIFIED\",\"unexpected\":true",
                ),
            )
        }
    }

    @Test
    fun `case key와 run token 중복을 거부한다`() {
        val duplicateCase = validEvidence().replace(
            "\"caseId\": \"alarms.get\"",
            "\"caseId\": \"runtime.getPlatformInfo\"",
        ).replace("\"member\": \"get\"", "\"member\": \"getPlatformInfo\"")
            .replace("\"namespace\": \"alarms\"", "\"namespace\": \"runtime\"")
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(duplicateCase)
        }

        val duplicateRunToken = validEvidence().replace("run-token-00000002", "run-token-00000001")
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(duplicateRunToken)
        }
    }

    @Test
    fun `ID enum digest와 숫자 범위를 엄격히 검증한다`() {
        val invalidInputs = listOf(
            validEvidence().replace("session-20260802", "../session"),
            validEvidence().replace("base-runtime", "base/runtime"),
            validEvidence().replace("abcdefghijklmnopabcdefghijklmnop", "abcdefghijklmnopabcdefghijklmn0p"),
            validEvidence().replace(SHA_A, SHA_A.uppercase()),
            validEvidence().replace("\"status\": \"VERIFIED\"", "\"status\": \"UNKNOWN\""),
            validEvidence().replace("\"context\": \"service_worker\"", "\"context\": \"worker\""),
            validEvidence().replace("\"durationMs\": 12", "\"durationMs\": 600001"),
            validEvidence().replace("\"browserVersionCode\": 300020061", "\"browserVersionCode\": -1"),
            validEvidence().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2"),
        )
        invalidInputs.forEach { value ->
            assertFailsWith<IllegalArgumentException> { ProbeEvidenceJson.decodeStrict(value) }
        }
    }

    @Test
    fun `detail의 크기 깊이 값 수 문자열과 숫자 범위를 제한한다`() {
        val oversizedString = "x".repeat(1_025)
        val tooManyValues = (0..64).joinToString(",") { "\"k$it\":$it" }
        val invalidDetails = listOf(
            "{\"message\":\"$oversizedString\"}",
            "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":true}}}}}}}",
            "{$tooManyValues}",
            "{\"number\":1e309}",
        )
        invalidDetails.forEach { detail ->
            assertFailsWith<IllegalArgumentException> {
                ProbeEvidenceJson.decodeStrict(
                    validEvidence().replace(
                        "{\"kind\":\"success\",\"valueType\":\"object\",\"nested\":{\"z\":2,\"a\":true}}",
                        detail,
                    ),
                )
            }
        }
    }

    @Test
    fun `status와 detail kind가 다른 result를 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(
                validEvidence().replace(
                    "{\"kind\":\"absent\"}",
                    "{\"kind\":\"success\",\"valueType\":\"object\"}",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(
                validEvidence().replace(
                    "\"status\": \"VERIFIED\"",
                    "\"status\": \"FAILED\"",
                ),
            )
        }
    }

    @Test
    fun `전체 JSON 크기와 nesting depth를 파싱 전에 제한한다`() {
        val oversized = validEvidence() + " ".repeat(1_048_577)
        assertFailsWith<IllegalArgumentException> { ProbeEvidenceJson.decodeStrict(oversized) }

        val deeplyNested = "[".repeat(65) + "0" + "]".repeat(65)
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(validEvidence().replace("{\"kind\":\"absent\"}", deeplyNested))
        }
    }

    @Test
    fun `decode 결과의 results 목록은 외부에서 변경할 수 없다`() {
        val decoded = ProbeEvidenceJson.decodeStrict(validEvidence())

        @Suppress("UNCHECKED_CAST")
        val mutable = decoded.results as MutableList<ProbeCaseResult>
        assertFailsWith<UnsupportedOperationException> { mutable.clear() }
        assertEquals(2, decoded.results.size)
        assertTrue(decoded.results.javaClass.name.contains("Unmodifiable"))
    }

    private fun validEvidence(): String =
        """
        {
          "schemaVersion": 1,
          "sessionId": "session-20260802",
          "groupId": "base-runtime",
          "catalogSha256": "$SHA_A",
          "extensionId": "abcdefghijklmnopabcdefghijklmnop",
          "wrapperSignerSha256": "$SHA_B",
          "browserPackage": "com.sec.android.app.sbrowser",
          "browserVersionName": "30.0.2.61",
          "browserVersionCode": 300020061,
          "browserApkSetSha256": "$SHA_C",
          "browserSignerSha256": "$SHA_D",
          "results": [
            {
              "caseId": "runtime.getPlatformInfo",
              "context": "service_worker",
              "detail": {"kind":"success","valueType":"object","nested":{"z":2,"a":true}},
              "durationMs": 12,
              "member": "getPlatformInfo",
              "namespace": "runtime",
              "runToken": "run-token-00000001",
              "status": "VERIFIED"
            },
            {
              "caseId": "alarms.get",
              "context": "service_worker",
              "detail": {"kind":"absent"},
              "durationMs": 0,
              "member": "get",
              "namespace": "alarms",
              "runToken": "run-token-00000002",
              "status": "ABSENT"
            }
          ]
        }
        """.trimIndent()

    private companion object {
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
