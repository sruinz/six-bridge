package dev.sixbridge.probe.evidence

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProbeEvidenceJsonTest {
    @Test
    fun `유효한 evidence를 canonical JSON으로 결정론적으로 round trip한다`() {
        val decoded = ProbeEvidenceJson.decodeStrict(validEvidence())

        assertEquals("session-20260802", decoded.sessionId)
        assertEquals("base-runtime", decoded.groupId)
        assertEquals(2, decoded.results.size)
        assertEquals(
            "{\"kind\":\"success\",\"valueType\":\"object\"}",
            decoded.results.single { it.caseId == "runtime.getPlatformInfo" }.detail,
        )
        val encoded = ProbeEvidenceJson.encodeCanonical(decoded)
        assertEquals(encoded, ProbeEvidenceJson.encodeCanonical(ProbeEvidenceJson.decodeStrict(encoded)))
    }

    @Test
    fun `root와 result의 unknown 및 duplicate field를 거부한다`() {
        val invalidInputs = listOf(
            validEvidence().replace(
                "\"schemaVersion\": 1",
                "\"schemaVersion\": 1,\"schemaVersion\": 1",
            ),
            validEvidence().replace("\"results\": [", "\"unexpected\":true,\"results\": ["),
            validEvidence().replace(
                "\"caseId\": \"runtime.getPlatformInfo\"",
                "\"caseId\": \"runtime.getPlatformInfo\",\"caseId\":\"runtime.getPlatformInfo\"",
            ),
            validEvidence().replace(
                "\"status\": \"VERIFIED\"",
                "\"status\": \"VERIFIED\",\"unexpected\":true",
            ),
        )
        invalidInputs.forEach { value ->
            assertFailsWith<IllegalArgumentException> { ProbeEvidenceJson.decodeStrict(value) }
        }
    }

    @Test
    fun `case key와 run token 중복을 거부한다`() {
        val duplicateCase = validEvidence().replace(
            "\"caseId\": \"alarms.get\"",
            "\"caseId\": \"runtime.getPlatformInfo\"",
        ).replace("\"member\": \"get\"", "\"member\": \"getPlatformInfo\"")
            .replace("\"namespace\": \"alarms\"", "\"namespace\": \"runtime\"")
        assertFailsWith<IllegalArgumentException> { ProbeEvidenceJson.decodeStrict(duplicateCase) }
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(
                validEvidence().replace("run-token-00000002", "run-token-00000001"),
            )
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
            "{\"kind\":\"success\",\"valueType\":\"object\",\"message\":\"$oversizedString\"}",
            "{\"kind\":\"success\",\"valueType\":\"object\",\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":true}}}}}}}",
            "{\"kind\":\"success\",\"valueType\":\"object\",$tooManyValues}",
            "{\"kind\":\"success\",\"valueType\":\"object\",\"number\":1e309}",
        )
        invalidDetails.forEach { detail ->
            assertFailsWith<IllegalArgumentException> {
                ProbeEvidenceJson.decodeStrict(
                    validEvidence().replace(VALID_SUCCESS_DETAIL, detail),
                )
            }
        }
    }

    @Test
    fun `status와 detail kind가 다른 result를 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(
                validEvidence().replace("{\"kind\":\"absent\"}", VALID_SUCCESS_DETAIL),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(
                validEvidence().replace("\"status\": \"VERIFIED\"", "\"status\": \"FAILED\""),
            )
        }
    }

    @Test
    fun `Task4에서 생성되는 모든 terminal result shape를 수락한다`() {
        val results = JsonArray(
            listOf(
                result("absent", "service_worker", "{\"kind\":\"absent\"}", "ABSENT", 1),
                result("exposed", "extension_page", "{\"kind\":\"exposed\"}", "EXPOSED", 2),
                result(
                    "failed",
                    "service_worker",
                    "{\"kind\":\"error\",\"message\":\"boom\",\"name\":\"Error\"}",
                    "FAILED",
                    3,
                ),
                result(
                    "gesture",
                    "user_gesture",
                    "{\"kind\":\"gesture-required\"}",
                    "NOT_RUN",
                    4,
                ),
                result(
                    "restricted",
                    "service_worker",
                    "{\"kind\":\"error\",\"message\":\"denied\",\"name\":\"Error\"}",
                    "RESTRICTED",
                    5,
                ),
                result("verified", "offscreen_document", VALID_SUCCESS_DETAIL, "VERIFIED", 6),
            ),
        )

        val decoded = ProbeEvidenceJson.decodeStrict(withResults(validEvidence(), results))

        assertEquals(ProbeCaseStatus.entries.toSet(), decoded.results.map { it.status }.toSet())
    }

    @Test
    fun `Kotlin과 Node 공통 reject corpus를 모두 거부한다`() {
        val corpus = checkNotNull(javaClass.getResourceAsStream("/evidence/reject-corpus.json"))
            .bufferedReader().use { it.readText() }
        val cases = testJson.parseToJsonElement(corpus).jsonObject.getValue("cases").jsonArray
        assertEquals(10, cases.size)
        cases.forEach { case ->
            val operation = case.jsonObject.getValue("operation").jsonPrimitive.content
            assertFailsWith<IllegalArgumentException>(operation) {
                ProbeEvidenceJson.decodeStrict(applyCorpusOperation(validEvidence(), operation))
            }
        }
    }

    @Test
    fun `전체 JSON 크기와 nesting depth를 파싱 전에 제한한다`() {
        assertFailsWith<IllegalArgumentException> {
            ProbeEvidenceJson.decodeStrict(validEvidence() + " ".repeat(1_048_577))
        }
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
              "detail": $VALID_SUCCESS_DETAIL,
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

    private fun result(
        suffix: String,
        context: String,
        detail: String,
        status: String,
        token: Int,
    ): JsonElement = testJson.parseToJsonElement(
        """
        {
          "caseId":"case-$suffix",
          "context":"$context",
          "detail":$detail,
          "durationMs":0,
          "member":"member$token",
          "namespace":"namespace$token",
          "runToken":"run-token-${token.toString().padStart(8, '0')}",
          "status":"$status"
        }
        """.trimIndent(),
    )

    private fun withResults(value: String, results: JsonArray): String {
        val root = testJson.parseToJsonElement(value).jsonObject.toMutableMap()
        root["results"] = results
        return testJson.encodeToString(JsonElement.serializer(), JsonObject(root))
    }

    private fun applyCorpusOperation(value: String, operation: String): String {
        val root = testJson.parseToJsonElement(value).jsonObject.toMutableMap()
        val results = root.getValue("results").jsonArray.toMutableList()
        val first = results.first().jsonObject.toMutableMap()
        val detail = first.getValue("detail").jsonObject.toMutableMap()
        when (operation) {
            "detail-key-129" -> detail["k".repeat(129)] = JsonPrimitive(true)
            "detail-huge-finite-number" -> detail["number"] = JsonPrimitive(BigDecimal("1.5e308"))
            "unsafe-browser-version-code" -> root["browserVersionCode"] =
                JsonPrimitive(9_007_199_254_740_992L)
            "duplicate-case-key" -> results.add(results.first())
            "unknown-root-field" -> root["unknown"] = JsonPrimitive(true)
            "unknown-result-field" -> first["unknown"] = JsonPrimitive(true)
            "unknown-detail-field" -> detail["unknown"] = JsonPrimitive(true)
            "detail-depth-seven" -> detail["nested"] = testJson.parseToJsonElement(
                "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":{\"f\":{\"g\":true}}}}}}}",
            )
            "status-detail-mismatch" -> first["status"] = JsonPrimitive("ABSENT")
            "not-run-worker-context" -> {
                first["status"] = JsonPrimitive("NOT_RUN")
                first["detail"] = JsonObject(mapOf("kind" to JsonPrimitive("gesture-required")))
            }
            else -> error("알 수 없는 공통 corpus operation: $operation")
        }
        if (operation.startsWith("detail-") || operation == "unknown-detail-field") {
            first["detail"] = JsonObject(detail)
        }
        if (operation != "duplicate-case-key") results[0] = JsonObject(first)
        root["results"] = JsonArray(results)
        return testJson.encodeToString(JsonElement.serializer(), JsonObject(root))
    }

    private companion object {
        val testJson = Json { prettyPrint = false }
        const val VALID_SUCCESS_DETAIL = "{\"kind\":\"success\",\"valueType\":\"object\"}"
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
