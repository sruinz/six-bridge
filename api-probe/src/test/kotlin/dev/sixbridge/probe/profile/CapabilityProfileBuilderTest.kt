package dev.sixbridge.probe.profile

import dev.sixbridge.core.profile.ApiCapabilityStatus
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.probe.catalog.ApiProbeCatalog
import dev.sixbridge.probe.catalog.CatalogDisposition
import dev.sixbridge.probe.evidence.ProbeEvidence
import dev.sixbridge.probe.evidence.ProbeEvidenceJson
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class CapabilityProfileBuilderTest {
    private val catalog = loadCatalog()
    private val base = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 300020061,
        majorVersion = 30,
        supportedApis = setOf("storage"),
        detectedApis = BrowserProfile.BASELINE_APIS,
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = SHA_A,
        installationApkSetSha256 = SHA_B,
        sourceApkSignerSha256 = SHA_C,
    )

    @Test
    fun `browser provenance가 하나라도 다르면 후보 profile을 만들지 않는다`() {
        val evidence = evidence().toMutableList()
        evidence[0] = decodeEvidence(
            evidence[0],
            browserApkSetSha256 = SHA_D,
        )

        assertFailsWith<IllegalArgumentException> {
            CapabilityProfileBuilder(catalog).build(base, evidence)
        }
    }

    @Test
    fun `정확한 여섯 group과 catalog case coverage만 수락한다`() {
        val complete = evidence()
        assertEquals(6, complete.size)
        assertEquals(33, complete.sumOf { it.results.size })

        assertFailsWith<IllegalArgumentException> {
            CapabilityProfileBuilder(catalog).build(base, complete.dropLast(1))
        }
        val duplicate = complete.dropLast(1) + complete.first()
        assertFailsWith<IllegalArgumentException> {
            CapabilityProfileBuilder(catalog).build(base, duplicate)
        }
        val missingCase = complete.toMutableList()
        missingCase[0] = decodeEvidence(missingCase[0], results = missingCase[0].results.dropLast(1))
        assertFailsWith<IllegalArgumentException> {
            CapabilityProfileBuilder(catalog).build(base, missingCase)
        }
    }

    @Test
    fun `모든 group은 같은 session과 wrapper signer를 사용하고 서로 다른 extension ID를 사용한다`() {
        val complete = evidence()
        val wrongSession = complete.toMutableList()
        wrongSession[1] = decodeEvidence(wrongSession[1], sessionId = "other-session")
        assertFailsWith<IllegalArgumentException> {
            CapabilityProfileBuilder(catalog).build(base, wrongSession)
        }

        val wrongSigner = complete.toMutableList()
        wrongSigner[1] = decodeEvidence(wrongSigner[1], wrapperSignerSha256 = SHA_A)
        assertFailsWith<IllegalArgumentException> {
            CapabilityProfileBuilder(catalog).build(base, wrongSigner)
        }

        val duplicateExtension = complete.toMutableList()
        duplicateExtension[1] = decodeEvidence(
            duplicateExtension[1],
            extensionId = duplicateExtension[0].extensionId,
        )
        assertFailsWith<IllegalArgumentException> {
            CapabilityProfileBuilder(catalog).build(base, duplicateExtension)
        }
    }

    @Test
    fun `VERIFIED case가 모두 확인된 PROBE namespace만 legacy supportedApis로 승격한다`() {
        val candidate = CapabilityProfileBuilder(catalog).build(base, evidence())

        assertTrue("runtime" in candidate.supportedApis)
        assertTrue("action" in candidate.supportedApis)
        assertTrue("storage" in candidate.supportedApis)
        assertFalse("cookies" in candidate.supportedApis)
        assertFalse("browserAction" in candidate.supportedApis)
        assertEquals(33, candidate.apiCapabilities.size)
        assertEquals(
            ApiCapabilityStatus.EXPOSED,
            candidate.apiCapabilities.single { it.key.namespace == "cookies" }.status,
        )
        assertEquals(
            catalog.sha256,
            candidate.apiCapabilities.single { it.key.namespace == "runtime" }
                .probeCatalogSha256,
        )
    }

    @Test
    fun `ABSENT FAILED RESTRICTED는 지원 승격 근거가 되지 않는다`() {
        listOf("ABSENT", "FAILED", "RESTRICTED").forEach { status ->
            val detail = when (status) {
                "ABSENT" -> "{\"kind\":\"absent\"}"
                else -> "{\"kind\":\"error\",\"message\":\"boom\",\"name\":\"Error\"}"
            }
            val changed = evidence().map { group ->
                if (group.results.none { it.namespace == "runtime" }) group else {
                    val result = group.results.single { it.namespace == "runtime" }
                    decodeEvidence(
                        group,
                        results = group.results.map {
                            if (it !== result) it else it.copyForTest(
                                detail = detail,
                                status = status,
                            )
                        },
                    )
                }
            }

            val candidate = CapabilityProfileBuilder(catalog).build(base, changed)
            assertFalse("runtime" in candidate.supportedApis, status)
        }
    }

    @Test
    fun `PRESENCE_ONLY evidence를 VERIFIED로 위조하면 거부한다`() {
        listOf("cookies", "history", "proxy").forEach { namespace ->
            val tampered = evidenceWithStatus(namespace, "VERIFIED")

            assertFailsWith<IllegalArgumentException>(namespace) {
                CapabilityProfileBuilder(catalog).build(base, tampered)
            }
        }
    }

    @Test
    fun `SAFE_CALL evidence를 EXPOSED로 위조하면 거부한다`() {
        val tampered = evidenceWithStatus("runtime", "EXPOSED")

        assertFailsWith<IllegalArgumentException> {
            CapabilityProfileBuilder(catalog).build(base, tampered)
        }
    }

    @Test
    fun `core case와 presence case가 섞여도 core VERIFIED만 namespace 승격 근거로 사용한다`() {
        val candidate = CapabilityProfileBuilder(catalog).build(
            base.copy(supportedApis = emptySet()),
            evidence(),
        )

        assertTrue("runtime" in candidate.supportedApis)
        assertFalse("cookies" in candidate.supportedApis)
        assertFalse("history" in candidate.supportedApis)
        assertEquals(
            ApiCapabilityStatus.EXPOSED,
            candidate.apiCapabilities.single { it.key.namespace == "cookies" }.status,
        )
    }

    @Test
    fun `같은 capability key의 상충하는 status는 거부한다`() {
        val complete = evidence().toMutableList()
        val first = complete[0]
        val collision = first.results[0].copy(
            namespace = first.results[1].namespace,
            member = first.results[1].member,
            context = first.results[1].context,
            status = dev.sixbridge.probe.evidence.ProbeCaseStatus.ABSENT,
            detail = "{\"kind\":\"absent\"}",
        )
        complete[0] = ProbeEvidence(
            schemaVersion = first.schemaVersion,
            sessionId = first.sessionId,
            groupId = first.groupId,
            catalogSha256 = first.catalogSha256,
            extensionId = first.extensionId,
            wrapperSignerSha256 = first.wrapperSignerSha256,
            browserPackage = first.browserPackage,
            browserVersionName = first.browserVersionName,
            browserVersionCode = first.browserVersionCode,
            browserApkSetSha256 = first.browserApkSetSha256,
            browserSignerSha256 = first.browserSignerSha256,
            results = listOf(collision) + first.results.drop(1),
        )

        assertFailsWith<IllegalArgumentException> {
            CapabilityProfileBuilder(catalog).build(base, complete)
        }
    }

    @Test
    fun `같은 입력은 canonical profile과 digest를 결정론적으로 생성한다`() {
        val builder = CapabilityProfileBuilder(catalog)
        val evidence = evidence()

        val first = builder.build(base, evidence)
        val second = builder.build(base, evidence.reversed())

        assertEquals(first, second)
        assertEquals(first.canonicalSha256(), second.canonicalSha256())
        assertEquals(first.apiCapabilities.map { it.key.namespace }, first.apiCapabilities.map { it.key.namespace }.sorted())
    }

    private fun evidence(): List<ProbeEvidence> {
        val entries = catalog.entries.filter { it.disposition == CatalogDisposition.PROBE }
        return entries.groupBy { requireNotNull(it.groupId) }.toSortedMap().map { (groupId, group) ->
            val results = group.flatMap { entry ->
                entry.cases.map { case ->
                    val status = if (case.expectedOutcome.name == "VERIFIED") "VERIFIED" else "EXPOSED"
                    EvidenceResult(
                        caseId = case.id,
                        namespace = entry.namespace,
                        member = case.member,
                        context = case.context.name.lowercase(),
                        status = status,
                        detail = if (status == "VERIFIED") {
                            "{\"kind\":\"success\",\"valueType\":\"object\"}"
                        } else {
                            "{\"kind\":\"exposed\"}"
                        },
                    )
                }
            }
            decodeEvidence(
                groupId = groupId,
                extensionId = extensionId(groupId),
                results = results,
            )
        }
    }

    private fun evidenceWithStatus(namespace: String, status: String): List<ProbeEvidence> =
        evidence().map { group ->
            if (group.results.none { it.namespace == namespace }) group else {
                decodeEvidence(
                    group,
                    results = group.results.map { result ->
                        if (result.namespace != namespace) result else result.copyForTest(
                            status = status,
                            detail = when (status) {
                                "VERIFIED" -> "{\"kind\":\"success\",\"valueType\":\"object\"}"
                                "EXPOSED" -> "{\"kind\":\"exposed\"}"
                                else -> error("unsupported test status")
                            },
                        )
                    },
                )
            }
        }

    private fun decodeEvidence(
        original: ProbeEvidence? = null,
        groupId: String = requireNotNull(original?.groupId),
        sessionId: String = original?.sessionId ?: "session-20260802",
        extensionId: String = original?.extensionId ?: extensionId(groupId),
        wrapperSignerSha256: String = original?.wrapperSignerSha256 ?: SHA_D,
        browserApkSetSha256: String = original?.browserApkSetSha256 ?: SHA_B,
        results: List<Any> = original?.results ?: error("results are required"),
    ): ProbeEvidence {
        val resultObjects = results.mapIndexed { index, raw ->
            val result = when (raw) {
                is EvidenceResult -> raw
                is dev.sixbridge.probe.evidence.ProbeCaseResult -> EvidenceResult(
                    raw.caseId,
                    raw.namespace,
                    raw.member,
                    raw.context.name.lowercase(),
                    raw.status.name,
                    raw.detail,
                )
                else -> error("unknown result")
            }
            JsonObject(
                linkedMapOf(
                    "caseId" to JsonPrimitive(result.caseId),
                    "context" to JsonPrimitive(result.context),
                    "detail" to Json.parseToJsonElement(result.detail),
                    "durationMs" to JsonPrimitive(1),
                    "member" to JsonPrimitive(result.member),
                    "namespace" to JsonPrimitive(result.namespace),
                    "runToken" to JsonPrimitive("run-token-${index.toString().padStart(8, '0')}"),
                    "status" to JsonPrimitive(result.status),
                ),
            )
        }
        val root = JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "sessionId" to JsonPrimitive(sessionId),
                "groupId" to JsonPrimitive(groupId),
                "catalogSha256" to JsonPrimitive(catalog.sha256),
                "extensionId" to JsonPrimitive(extensionId),
                "wrapperSignerSha256" to JsonPrimitive(wrapperSignerSha256),
                "browserPackage" to JsonPrimitive(base.browserPackage),
                "browserVersionName" to JsonPrimitive(base.versionName),
                "browserVersionCode" to JsonPrimitive(base.versionCode),
                "browserApkSetSha256" to JsonPrimitive(browserApkSetSha256),
                "browserSignerSha256" to JsonPrimitive(base.sourceApkSignerSha256),
                "results" to JsonArray(resultObjects),
            ),
        )
        return ProbeEvidenceJson.decodeStrict(Json.encodeToString(JsonObject.serializer(), root))
    }

    private fun dev.sixbridge.probe.evidence.ProbeCaseResult.copyForTest(
        caseId: String = this.caseId,
        namespace: String = this.namespace,
        member: String = this.member,
        context: String = this.context.name.lowercase(),
        status: String = this.status.name,
        detail: String = this.detail,
    ) = EvidenceResult(caseId, namespace, member, context, status, detail)

    private fun extensionId(groupId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(groupId.toByteArray())
        .take(16)
        .joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            "${('a'.code + (value ushr 4)).toChar()}${('a'.code + (value and 0xf)).toChar()}"
        }

    private fun loadCatalog(): ApiProbeCatalog = ApiProbeCatalog.load(
        checkNotNull(javaClass.getResourceAsStream("/catalog/sbrowser-30-public-apis.json")),
    )

    private data class EvidenceResult(
        val caseId: String,
        val namespace: String,
        val member: String,
        val context: String,
        val status: String,
        val detail: String,
    )

    private companion object {
        const val SHA_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SHA_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val SHA_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val SHA_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
