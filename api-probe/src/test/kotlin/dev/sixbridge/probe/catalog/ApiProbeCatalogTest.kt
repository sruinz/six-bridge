package dev.sixbridge.probe.catalog

import dev.sixbridge.core.profile.ApiExecutionContext
import dev.sixbridge.core.profile.BrowserProfile
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ApiProbeCatalogTest {
    @Test
    fun `catalog는 삼성 30 baseline namespace를 정확히 한 번 분류한다`() {
        val catalog = loadCatalog()

        assertEquals(BrowserProfile.BASELINE_APIS, catalog.entries.map { it.namespace }.toSet())
        assertEquals(catalog.entries.size, catalog.entries.map { it.namespace }.distinct().size)
        assertEquals(58, catalog.entries.size)
        assertEquals(
            mapOf(
                CatalogDisposition.PROBE to 38,
                CatalogDisposition.PLATFORM_RESTRICTED to 5,
                CatalogDisposition.LEGACY_APP to 2,
                CatalogDisposition.INTERNAL to 13,
            ),
            catalog.entries.groupingBy { it.disposition }.eachCount(),
        )
    }

    @Test
    fun `brief의 내부 legacy platform 분류를 그대로 적용한다`() {
        val byDisposition = loadCatalog().entries.groupBy { it.disposition }
            .mapValues { (_, entries) -> entries.map { it.namespace }.toSet() }

        assertEquals(
            setOf(
                "appstoreService", "clientService", "dom", "events", "extensionTypes",
                "iconVariants", "scriptsInternal", "sixLogging", "trans", "types",
                "webRequestInternal", "webViewRequest", "webstorePrivate",
            ),
            byDisposition.getValue(CatalogDisposition.INTERNAL),
        )
        assertEquals(
            setOf("app", "app.runtime"),
            byDisposition.getValue(CatalogDisposition.LEGACY_APP),
        )
        assertEquals(
            setOf("automation", "fileHandlers", "gcm", "inputMethodPrivate", "ttsEngine"),
            byDisposition.getValue(CatalogDisposition.PLATFORM_RESTRICTED),
        )
    }

    @Test
    fun `모든 PROBE는 generator가 사용할 수 있는 안전한 case를 가진다`() {
        val probes = loadCatalog().entries.filter {
            it.disposition == CatalogDisposition.PROBE
        }

        assertTrue(probes.all { it.groupId != null && it.cases.isNotEmpty() })
        assertTrue(
            probes.flatMap { it.cases }.all {
                it.member.isNotBlank() &&
                    it.context in ApiExecutionContext.entries &&
                    it.expectedOutcome in ProbeExpectedOutcome.entries
            },
        )
        assertTrue(
            probes.filter { it.namespace in setOf("cookies", "history") }
                .flatMap { it.cases }
                .all { it.expectedOutcome == ProbeExpectedOutcome.EXPOSED },
        )
        val forbidden = setOf(
            "cookies.getAll",
            "history.deleteAll",
            "history.deleteRange",
            "history.deleteUrl",
            "management.uninstall",
        )
        assertTrue(
            probes.none { entry ->
                entry.cases.any { case -> "${entry.namespace}.${case.member}" in forbidden }
            },
        )
    }

    @Test
    fun `root entry case의 unknown 필드를 거부한다`() {
        val invalid = listOf(
            VALID_CATALOG.replace(
                "\"entries\": [",
                "\"unexpected\": true, \"entries\": [",
            ),
            VALID_CATALOG.replace(
                "\"namespace\": \"tabs\"",
                "\"unexpected\": true, \"namespace\": \"tabs\"",
            ),
            VALID_CATALOG.replace(
                "\"id\": \"tabs-query-worker\"",
                "\"unexpected\": true, \"id\": \"tabs-query-worker\"",
            ),
        )

        invalid.forEach { json ->
            assertFailsWith<IllegalArgumentException> { load(json) }
        }
    }

    @Test
    fun `root entry case의 duplicate JSON key를 거부한다`() {
        val invalid = listOf(
            VALID_CATALOG.replace(
                "\"schemaVersion\": 1",
                "\"schemaVersion\": 1, \"schemaVersion\": 1",
            ),
            VALID_CATALOG.replace(
                "\"namespace\": \"tabs\"",
                "\"namespace\": \"tabs\", \"namespace\": \"tabs\"",
            ),
            VALID_CATALOG.replace(
                "\"id\": \"tabs-query-worker\"",
                "\"id\": \"tabs-query-worker\", \"id\": \"tabs-query-worker\"",
            ),
        )

        invalid.forEach { json ->
            assertFailsWith<IllegalArgumentException> { load(json) }
        }
    }

    @Test
    fun `namespace와 case ID 중복을 거부한다`() {
        val duplicateNamespace = catalogWithEntries(VALID_ENTRY, VALID_ENTRY)
        val duplicateCaseId = catalogWithEntries(
            VALID_ENTRY,
            VALID_ENTRY.replace("\"tabs\"", "\"windows\"")
                .replace("\"query\"", "\"getAll\""),
        )

        assertFailsWith<IllegalArgumentException> { load(duplicateNamespace) }
        assertFailsWith<IllegalArgumentException> { load(duplicateCaseId) }
    }

    @Test
    fun `PROBE의 빈 case와 비 PROBE의 case를 거부한다`() {
        val emptyProbe = VALID_CATALOG.replace(
            "\"cases\": [$VALID_CASE]",
            "\"cases\": []",
        )
        val classifiedWithCase = VALID_CATALOG
            .replace("\"disposition\": \"probe\"", "\"disposition\": \"internal\"")
            .replace("\"groupId\": \"tab-page\"", "\"groupId\": null")

        assertFailsWith<IllegalArgumentException> { load(emptyProbe) }
        assertFailsWith<IllegalArgumentException> { load(classifiedWithCase) }
    }

    @Test
    fun `잘못된 context disposition identifier outcome을 거부한다`() {
        val invalid = listOf(
            VALID_CATALOG.replace("\"service_worker\"", "\"worker\""),
            VALID_CATALOG.replace("\"probe\"", "\"public\""),
            VALID_CATALOG.replace("\"tabs\"", "\".tabs\""),
            VALID_CATALOG.replace("\"tabs-query-worker\"", "\"tabs/query\""),
            VALID_CATALOG.replace("\"tab-page\"", "\"Tab Page\""),
            VALID_CATALOG.replace("\"verified\"", "\"failed\""),
        )

        invalid.forEach { json ->
            assertFailsWith<IllegalArgumentException> { load(json) }
        }
    }

    @Test
    fun `permission과 manifest key 세트의 중복 빈 값 잘못된 타입을 거부한다`() {
        val invalid = listOf(
            VALID_CATALOG.replace("[\"tabs\"]", "[\"tabs\", \"tabs\"]"),
            VALID_CATALOG.replace("[\"permissions\"]", "[\"permissions\", \"permissions\"]"),
            VALID_CATALOG.replace("[\"tabs\"]", "[\"\"]"),
            VALID_CATALOG.replace("[\"permissions\"]", "[\"bad key\"]"),
            VALID_CATALOG.replace("[\"tabs\"]", "\"tabs\""),
        )

        invalid.forEach { json ->
            assertFailsWith<IllegalArgumentException> { load(json) }
        }
    }

    @Test
    fun `load는 입력 순서와 공백에 무관한 canonical 순서와 digest를 만든다`() {
        val actionEntry = VALID_ENTRY
            .replace("\"tabs\"", "\"action\"")
            .replace("\"tabs-query-worker\"", "\"action-get-title-page\"")
            .replace("\"query\"", "\"getTitle\"")
            .replace("\"service_worker\"", "\"extension_page\"")
            .replace("[\"tabs\"]", "[]")
            .replace("[\"permissions\"]", "[\"action\"]")
        val first = load(catalogWithEntries(VALID_ENTRY, actionEntry))
        val second = load(
            """{"entries":[$actionEntry,$VALID_ENTRY],"schemaVersion":1}""",
        )

        assertEquals(listOf("action", "tabs"), first.entries.map { it.namespace })
        assertEquals(first.canonicalJson, second.canonicalJson)
        assertEquals(first.sha256, second.sha256)
        assertTrue(first.sha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun `probe group ID 집합은 외부에서 변경할 수 없다`() {
        @Suppress("UNCHECKED_CAST")
        val groups = ApiProbeCatalog.PROBE_GROUP_IDS as MutableSet<String>

        try {
            assertFailsWith<UnsupportedOperationException> {
                groups.add("mutated-group")
            }
        } finally {
            if ("mutated-group" in groups) groups.remove("mutated-group")
        }
    }

    private fun loadCatalog(): ApiProbeCatalog {
        val resource = requireNotNull(
            javaClass.getResourceAsStream("/catalog/sbrowser-30-public-apis.json"),
        )
        return resource.use(ApiProbeCatalog::load)
    }

    private fun load(value: String): ApiProbeCatalog =
        ByteArrayInputStream(value.toByteArray(UTF_8)).use(ApiProbeCatalog::load)

    private fun catalogWithEntries(vararg entries: String): String =
        """{"schemaVersion":1,"entries":[${entries.joinToString(",")}]}"""

    companion object {
        private val VALID_CASE =
            """
            {
              "id": "tabs-query-worker",
              "member": "query",
              "context": "service_worker",
              "permissions": ["tabs"],
              "manifestKeys": ["permissions"],
              "expectedOutcome": "verified"
            }
            """.trimIndent()

        private val VALID_ENTRY =
            """
            {
              "namespace": "tabs",
              "disposition": "probe",
              "groupId": "tab-page",
              "cases": [$VALID_CASE]
            }
            """.trimIndent()

        private val VALID_CATALOG =
            """
            {
              "schemaVersion": 1,
              "entries": [$VALID_ENTRY]
            }
            """.trimIndent()
    }
}
