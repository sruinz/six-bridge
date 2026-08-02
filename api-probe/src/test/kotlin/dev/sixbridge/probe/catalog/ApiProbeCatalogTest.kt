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
                CatalogDisposition.PROBE to 37,
                CatalogDisposition.PLATFORM_RESTRICTED to 5,
                CatalogDisposition.LEGACY_APP to 2,
                CatalogDisposition.INTERNAL to 14,
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
                "iconVariants", "protocolHandlers", "scriptsInternal", "sixLogging", "trans", "types",
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
                    it.mode in ProbeMode.entries &&
                    it.expectedOutcome in ProbeExpectedOutcome.entries
            },
        )
        assertTrue(
            probes.filter {
                it.namespace in setOf("clipboard", "cookies", "debugger", "history", "proxy")
            }
                .flatMap { it.cases }
                .all {
                    it.mode == ProbeMode.PRESENCE_ONLY &&
                        it.expectedOutcome == ProbeExpectedOutcome.EXPOSED
                },
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
    fun `bundled PROBE descriptor는 검토한 member context mode sets outcome와 정확히 일치한다`() {
        val actual = loadCatalog().entries
            .filter { it.disposition == CatalogDisposition.PROBE }
            .associate { entry ->
                val case = entry.cases.single()
                entry.namespace to listOf(
                    entry.groupId,
                    case.member,
                    case.context.name,
                    case.mode.name,
                    case.permissions.sorted().joinToString(","),
                    case.manifestKeys.sorted().joinToString(","),
                    case.expectedOutcome.name,
                ).joinToString("|")
            }

        assertEquals(EXPECTED_DESCRIPTORS, actual)
        assertEquals(18, actual.values.count { "|SAFE_CALL|" in it })
        assertEquals(19, actual.values.count { "|PRESENCE_ONLY|" in it })
    }

    @Test
    fun `legacy와 manifest-only 판정은 official provenance를 가진다`() {
        val entries = loadCatalog().entries.associateBy { it.namespace }
        val expectedSources = mapOf(
            "clipboard" to setOf("https://developer.chrome.com/docs/apps/reference/clipboard"),
            "declarativeWebRequest" to setOf(
                "https://developer.chrome.com/docs/extensions/mv2/reference/declarativeWebRequest",
            ),
            "browserAction" to setOf(
                "https://developer.chrome.com/docs/extensions/mv2/reference/browserAction",
                "https://developer.chrome.com/docs/extensions/develop/migrate/api-calls",
            ),
            "pageAction" to setOf(
                "https://developer.chrome.com/docs/extensions/mv2/reference/pageAction",
                "https://developer.chrome.com/docs/extensions/develop/migrate/api-calls",
            ),
            "protocolHandlers" to setOf(
                "https://chromium.googlesource.com/chromium/src/+/HEAD/extensions/common/api/protocol_handlers.webidl",
            ),
        )

        assertEquals(
            expectedSources,
            entries.filterValues { it.sourceUrls.isNotEmpty() }.mapValues { it.value.sourceUrls },
        )
        assertTrue(expectedSources.keys.all { !entries.getValue(it).reason.isNullOrBlank() })
        assertEquals(CatalogDisposition.INTERNAL, entries.getValue("protocolHandlers").disposition)
    }

    @Test
    fun `provenance는 official URL과 비어 있지 않은 reason 조합만 허용한다`() {
        val official =
            "https://developer.chrome.com/docs/extensions/develop/migrate/api-calls"
        val invalid = listOf(
            VALID_CATALOG.replace("\"sourceUrls\": []", "\"sourceUrls\": [\"https://example.com\"]"),
            VALID_CATALOG.replace("\"sourceUrls\": []", "\"sourceUrls\": [\"$official\"]"),
            VALID_CATALOG.replace("\"reason\": null", "\"reason\": \"source 없음\""),
        )

        invalid.forEach { json ->
            assertFailsWith<IllegalArgumentException> { load(json) }
        }
    }

    @Test
    fun `mode는 expected outcome과 별도로 parsing하고 위험 case 호출을 거부한다`() {
        val invalid = listOf(
            VALID_CATALOG.replace("\"safe_call\"", "\"presence_only\""),
            VALID_CATALOG.replace("\"verified\"", "\"exposed\""),
            VALID_CATALOG.replace("\"runtime\"", "\"cookies\""),
            VALID_CATALOG.replace("\"getPlatformInfo\"", "\"attach\""),
        )

        invalid.forEach { json ->
            assertFailsWith<IllegalArgumentException> { load(json) }
        }
    }

    @Test
    fun `root entry case의 unknown 필드를 거부한다`() {
        val invalid = listOf(
            VALID_CATALOG.replace(
                "\"entries\": [",
                "\"unexpected\": true, \"entries\": [",
            ),
            VALID_CATALOG.replace(
                "\"namespace\": \"runtime\"",
                "\"unexpected\": true, \"namespace\": \"runtime\"",
            ),
            VALID_CATALOG.replace(
                "\"id\": \"runtime-platform-info-worker\"",
                "\"unexpected\": true, \"id\": \"runtime-platform-info-worker\"",
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
                "\"schemaVersion\": 2",
                "\"schemaVersion\": 2, \"schemaVersion\": 2",
            ),
            VALID_CATALOG.replace(
                "\"namespace\": \"runtime\"",
                "\"namespace\": \"runtime\", \"namespace\": \"runtime\"",
            ),
            VALID_CATALOG.replace(
                "\"id\": \"runtime-platform-info-worker\"",
                "\"id\": \"runtime-platform-info-worker\", \"id\": \"runtime-platform-info-worker\"",
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
            VALID_ENTRY.replace("\"runtime\"", "\"extension\"")
                .replace("\"getPlatformInfo\"", "\"isAllowedIncognitoAccess\""),
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
            .replace("\"groupId\": \"base-runtime\"", "\"groupId\": null")

        assertFailsWith<IllegalArgumentException> { load(emptyProbe) }
        assertFailsWith<IllegalArgumentException> { load(classifiedWithCase) }
    }

    @Test
    fun `잘못된 context disposition identifier outcome을 거부한다`() {
        val invalid = listOf(
            VALID_CATALOG.replace("\"service_worker\"", "\"worker\""),
            VALID_CATALOG.replace("\"probe\"", "\"public\""),
            VALID_CATALOG.replace("\"runtime\"", "\".runtime\""),
            VALID_CATALOG.replace("\"runtime-platform-info-worker\"", "\"runtime/info\""),
            VALID_CATALOG.replace("\"base-runtime\"", "\"Base Runtime\""),
            VALID_CATALOG.replace("\"safe_call\"", "\"unsafe_call\""),
            VALID_CATALOG.replace("\"verified\"", "\"failed\""),
        )

        invalid.forEach { json ->
            assertFailsWith<IllegalArgumentException> { load(json) }
        }
    }

    @Test
    fun `permission과 manifest key 세트의 중복 빈 값 잘못된 타입을 거부한다`() {
        val invalid = listOf(
            VALID_CATALOG.replace("\"permissions\": []", "\"permissions\": [\"alarms\", \"alarms\"]"),
            VALID_CATALOG.replace("\"manifestKeys\": []", "\"manifestKeys\": [\"action\", \"action\"]"),
            VALID_CATALOG.replace("\"permissions\": []", "\"permissions\": [\"\"]"),
            VALID_CATALOG.replace("\"manifestKeys\": []", "\"manifestKeys\": [\"bad key\"]"),
            VALID_CATALOG.replace("\"permissions\": []", "\"permissions\": \"alarms\""),
            VALID_CATALOG.replace("\"permissions\": []", "\"permissions\": [\"madeUpPermission\"]"),
            VALID_CATALOG.replace("\"manifestKeys\": []", "\"manifestKeys\": [\"made_up_key\"]"),
        )

        invalid.forEach { json ->
            assertFailsWith<IllegalArgumentException> { load(json) }
        }
    }

    @Test
    fun `catalog input은 1MiB와 64 구조 depth를 초과할 수 없다`() {
        val oversized = ByteArray(1_048_577) { ' '.code.toByte() }
        val tooDeep = "[".repeat(65) + "null" + "]".repeat(65)

        assertFailsWith<IllegalArgumentException> {
            ByteArrayInputStream(oversized).use(ApiProbeCatalog::load)
        }
        assertFailsWith<IllegalArgumentException> { load(tooDeep) }
    }

    @Test
    fun `load는 입력 순서와 공백에 무관한 canonical 순서와 digest를 만든다`() {
        val actionEntry = VALID_ENTRY
            .replace("\"runtime\"", "\"action\"")
            .replace("\"runtime-platform-info-worker\"", "\"action-get-title-page\"")
            .replace("\"getPlatformInfo\"", "\"getTitle\"")
            .replace("\"service_worker\"", "\"extension_page\"")
            .replace("\"manifestKeys\": []", "\"manifestKeys\": [\"action\"]")
        val first = load(catalogWithEntries(VALID_ENTRY, actionEntry))
        val second = load(
            """{"entries":[$actionEntry,$VALID_ENTRY],"schemaVersion":2}""",
        )

        assertEquals(listOf("action", "runtime"), first.entries.map { it.namespace })
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
        """{"schemaVersion":2,"entries":[${entries.joinToString(",")}]}"""

    companion object {
        private val EXPECTED_DESCRIPTORS = mapOf(
            "action" to "user-interface|getTitle|EXTENSION_PAGE|SAFE_CALL||action|VERIFIED",
            "alarms" to "base-runtime|get|SERVICE_WORKER|SAFE_CALL|alarms|permissions|VERIFIED",
            "browserAction" to "user-interface|getTitle|EXTENSION_PAGE|PRESENCE_ONLY|||EXPOSED",
            "clipboard" to "identity-privacy|setImageData|EXTENSION_PAGE|PRESENCE_ONLY|||EXPOSED",
            "commands" to "user-interface|getAll|EXTENSION_PAGE|SAFE_CALL||commands|VERIFIED",
            "contentSettings" to "identity-privacy|cookies|EXTENSION_PAGE|PRESENCE_ONLY|contentSettings|permissions|EXPOSED",
            "contextMenus" to "user-interface|create|SERVICE_WORKER|PRESENCE_ONLY|contextMenus|permissions|EXPOSED",
            "cookies" to "identity-privacy|get|SERVICE_WORKER|PRESENCE_ONLY|cookies|permissions|EXPOSED",
            "debugger" to "advanced-runtime|attach|SERVICE_WORKER|PRESENCE_ONLY|debugger|permissions|EXPOSED",
            "declarativeContent" to "network|onPageChanged|SERVICE_WORKER|PRESENCE_ONLY|declarativeContent|permissions|EXPOSED",
            "declarativeNetRequest" to "network|getAvailableStaticRuleCount|SERVICE_WORKER|SAFE_CALL|declarativeNetRequest|permissions|VERIFIED",
            "declarativeWebRequest" to "network|onRequest|SERVICE_WORKER|PRESENCE_ONLY|||EXPOSED",
            "extension" to "base-runtime|isAllowedIncognitoAccess|SERVICE_WORKER|SAFE_CALL|||VERIFIED",
            "history" to "identity-privacy|search|SERVICE_WORKER|PRESENCE_ONLY|history|permissions|EXPOSED",
            "i18n" to "base-runtime|getUILanguage|EXTENSION_PAGE|SAFE_CALL|||VERIFIED",
            "identity" to "identity-privacy|getRedirectURL|EXTENSION_PAGE|SAFE_CALL|identity|permissions|VERIFIED",
            "idle" to "identity-privacy|queryState|SERVICE_WORKER|SAFE_CALL|idle|permissions|VERIFIED",
            "management" to "advanced-runtime|getSelf|SERVICE_WORKER|SAFE_CALL|||VERIFIED",
            "notifications" to "user-interface|getPermissionLevel|SERVICE_WORKER|SAFE_CALL|notifications|permissions|VERIFIED",
            "offscreen" to "advanced-runtime|hasDocument|SERVICE_WORKER|SAFE_CALL|offscreen|permissions|VERIFIED",
            "pageAction" to "user-interface|getTitle|EXTENSION_PAGE|PRESENCE_ONLY|||EXPOSED",
            "permissions" to "base-runtime|contains|SERVICE_WORKER|SAFE_CALL|||VERIFIED",
            "privacy" to "identity-privacy|network|EXTENSION_PAGE|PRESENCE_ONLY|privacy|permissions|EXPOSED",
            "proxy" to "network|settings|SERVICE_WORKER|PRESENCE_ONLY|proxy|permissions|EXPOSED",
            "runtime" to "base-runtime|getPlatformInfo|SERVICE_WORKER|SAFE_CALL|||VERIFIED",
            "scripting" to "advanced-runtime|getRegisteredContentScripts|SERVICE_WORKER|SAFE_CALL|scripting|permissions|VERIFIED",
            "search" to "tab-page|query|EXTENSION_PAGE|PRESENCE_ONLY|||EXPOSED",
            "sidePanel" to "user-interface|getOptions|EXTENSION_PAGE|SAFE_CALL|sidePanel|permissions,side_panel|VERIFIED",
            "storage" to "base-runtime|local|SERVICE_WORKER|PRESENCE_ONLY|storage|permissions|EXPOSED",
            "tabGroups" to "tab-page|query|EXTENSION_PAGE|PRESENCE_ONLY|tabGroups|permissions|EXPOSED",
            "tabs" to "tab-page|create|EXTENSION_PAGE|PRESENCE_ONLY|||EXPOSED",
            "tts" to "user-interface|getVoices|EXTENSION_PAGE|SAFE_CALL|tts|permissions|VERIFIED",
            "userScripts" to "advanced-runtime|getScripts|SERVICE_WORKER|SAFE_CALL|userScripts|permissions|VERIFIED",
            "webAuthenticationProxy" to "identity-privacy|onRemoteSessionStateChange|SERVICE_WORKER|PRESENCE_ONLY|webAuthenticationProxy|permissions|EXPOSED",
            "webNavigation" to "network|getAllFrames|SERVICE_WORKER|PRESENCE_ONLY|webNavigation|permissions|EXPOSED",
            "webRequest" to "network|onBeforeRequest|SERVICE_WORKER|PRESENCE_ONLY|webRequest|permissions|EXPOSED",
            "windows" to "tab-page|getCurrent|EXTENSION_PAGE|SAFE_CALL|||VERIFIED",
        )

        private val VALID_CASE =
            """
            {
              "id": "runtime-platform-info-worker",
              "member": "getPlatformInfo",
              "context": "service_worker",
              "mode": "safe_call",
              "permissions": [],
              "manifestKeys": [],
              "expectedOutcome": "verified"
            }
            """.trimIndent()

        private val VALID_ENTRY =
            """
            {
              "namespace": "runtime",
              "disposition": "probe",
              "groupId": "base-runtime",
              "sourceUrls": [],
              "reason": null,
              "cases": [$VALID_CASE]
            }
            """.trimIndent()

        private val VALID_CATALOG =
            """
            {
              "schemaVersion": 2,
              "entries": [$VALID_ENTRY]
            }
            """.trimIndent()
    }
}
