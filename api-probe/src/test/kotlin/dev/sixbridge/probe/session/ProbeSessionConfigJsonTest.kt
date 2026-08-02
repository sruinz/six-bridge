package dev.sixbridge.probe.session

import dev.sixbridge.probe.catalog.ApiProbeCatalog
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProbeSessionConfigJsonTest {
    @Test
    fun `session config가 exact canonical JSON으로 round trip한다`() {
        val encoded = ProbeSessionConfigJson.encode(config())
        val decoded = ProbeSessionConfigJson.decodeStrict(encoded)

        assertEquals(config(), decoded)
        assertEquals(encoded, ProbeSessionConfigJson.encode(decoded))
        assertTrue(encoded.endsWith("\n"))
        assertEquals(ApiProbeCatalog.PROBE_GROUP_IDS.sorted(), decoded.bindings.map { it.groupId })
    }

    @Test
    fun `unknown missing duplicate field를 거부한다`() {
        val encoded = ProbeSessionConfigJson.encode(config())
        assertFailsWith<IllegalArgumentException> {
            ProbeSessionConfigJson.decodeStrict(encoded.replaceFirst("{", "{\"unknown\":true,"))
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeSessionConfigJson.decodeStrict(encoded.replace(
                "    \"sessionId\": \"20260802-143000\",\n",
                "",
            ))
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeSessionConfigJson.decodeStrict(encoded.replaceFirst(
                "\"schemaVersion\": 1",
                "\"schemaVersion\": 1, \"schemaVersion\": 1",
            ))
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeSessionConfigJson.decodeStrict(encoded.replaceFirst(
                "\"groupId\": \"advanced-runtime\"",
                "\"groupId\": \"advanced-runtime\", \"groupId\": \"advanced-runtime\"",
            ))
        }
    }

    @Test
    fun `binding은 exact group과 unique extension ID 및 동일 signer를 요구한다`() {
        val base = config()
        assertFailsWith<IllegalArgumentException> {
            base.copy(bindings = base.bindings.reversed()).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(bindings = base.bindings.dropLast(1)).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(bindings = base.bindings.mapIndexed { index, value ->
                if (index == 1) value.copy(extensionId = base.bindings.first().extensionId) else value
            }).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(bindings = base.bindings.mapIndexed { index, value ->
                if (index == 1) value.copy(wrapperSignerSha256 = "f".repeat(64)) else value
            }).validate()
        }
    }

    @Test
    fun `binding case는 bundled catalog의 group별 descriptor를 정확히 한 번씩 포함한다`() {
        val base = config()
        val first = base.bindings.first()
        assertFailsWith<IllegalArgumentException> {
            base.copy(bindings = listOf(first.copy(cases = first.cases.dropLast(1))) + base.bindings.drop(1)).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(bindings = listOf(first.copy(cases = first.cases + first.cases.first())) + base.bindings.drop(1)).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            val changed = first.cases.first().copy(member = "wrongMember")
            base.copy(bindings = listOf(first.copy(cases = listOf(changed) + first.cases.drop(1))) + base.bindings.drop(1)).validate()
        }
        assertFailsWith<IllegalArgumentException> {
            base.copy(bindings = listOf(first.copy(cases = first.cases.reversed())) + base.bindings.drop(1)).validate()
        }
    }

    @Test
    fun `case descriptor unknown missing duplicate field를 거부한다`() {
        val encoded = ProbeSessionConfigJson.encode(config())
        assertFailsWith<IllegalArgumentException> {
            ProbeSessionConfigJson.decodeStrict(encoded.replaceFirst(
                "\"caseId\":",
                "\"unknown\": true,\n                    \"caseId\":",
            ))
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeSessionConfigJson.decodeStrict(encoded.replaceFirst(
                Regex("\\s+\\\"expectedOutcome\\\": \\\"[^\\\"]+\\\",?"),
                "",
            ))
        }
        assertFailsWith<IllegalArgumentException> {
            ProbeSessionConfigJson.decodeStrict(encoded.replaceFirst(
                "\"caseId\":",
                "\"caseId\": \"duplicate\", \"caseId\":",
            ))
        }
    }

    @Test
    fun `origin은 extension ID에서만 계산한다`() {
        val binding = config().bindings.first()
        assertEquals("chrome-extension://${binding.extensionId}", binding.origin)
    }

    private fun config(): ProbeSessionConfig {
        val catalog = catalog()
        val signer = "b".repeat(64)
        return ProbeSessionConfig(
            schemaVersion = 1,
            sessionId = "20260802-143000",
            catalogSha256 = catalog.sha256,
            browserPackage = "com.sec.android.app.sbrowser",
            browserVersionName = "30.0.2.61",
            browserVersionCode = 300020061,
            browserApkSetSha256 = "c".repeat(64),
            browserSignerSha256 = "d".repeat(64),
            bindings = ApiProbeCatalog.PROBE_GROUP_IDS.sorted().mapIndexed { index, groupId ->
                ProbeSessionBinding(
                    groupId = groupId,
                    extensionId = ('a'.code + index).toChar().toString().repeat(32),
                    wrapperSignerSha256 = signer,
                    cases = catalog.entries
                        .filter { it.groupId == groupId }
                        .flatMap { entry ->
                            entry.cases.map { case ->
                                ProbeSessionCaseDescriptor(
                                    caseId = case.id,
                                    namespace = entry.namespace,
                                    member = case.member,
                                    context = case.context,
                                    mode = case.mode,
                                    expectedOutcome = case.expectedOutcome,
                                )
                            }
                        }
                        .sortedBy(ProbeSessionCaseDescriptor::caseId),
                )
            },
        ).also(ProbeSessionConfig::validate)
    }

    private fun catalog(): ApiProbeCatalog = ApiProbeCatalog.load(
        checkNotNull(javaClass.getResourceAsStream("/catalog/sbrowser-30-public-apis.json")),
    )

}
