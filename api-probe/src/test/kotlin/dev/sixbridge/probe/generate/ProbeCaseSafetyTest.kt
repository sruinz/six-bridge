package dev.sixbridge.probe.generate

import dev.sixbridge.probe.catalog.ApiProbeCatalog
import dev.sixbridge.probe.catalog.CatalogDisposition
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProbeCaseSafetyTest {
    @Test
    fun `33개 PROBE case는 여섯 runtime module에 정확히 한 번씩 구현된다`() = withWorkspace { workspace ->
        val catalog = loadCatalog()
        val generated = ProbeSourceGenerator().generate(catalog, workspace.resolve("output"))

        assertEquals(33, catalog.probeCaseIds.size)
        generated.forEach { probe ->
            EXPECTED_RUNTIME_ASSETS.forEach { relative ->
                assertTrue(Files.isRegularFile(probe.sourceRoot.resolve(relative), NOFOLLOW_LINKS), relative)
            }
        }

        val representative = generated.first().sourceRoot
        Files.writeString(representative.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val imports = EXPECTED_GROUPS.sorted().mapIndexed { index, group ->
            "import { CASE_IDS as ids$index } from '${representative.resolve("cases/$group.js").toUri()}';"
        }.joinToString("\n")
        val arrays = EXPECTED_GROUPS.indices.joinToString(",") { "ids$it" }
        val actualIds = runNodeModule(
            workspace,
            """
            $imports
            const ids = [$arrays].flat().sort();
            process.stdout.write(JSON.stringify(ids));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonArray.map { value -> value.jsonPrimitive.content }.toSet() }

        assertEquals(catalog.probeCaseIds, actualIds)
    }

    @Test
    fun `runtime source는 파괴적 호출과 동적 호출 우회를 포함하지 않는다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val sources = generated.first().sourceRoot.let { root ->
            Files.walk(root).use { paths ->
                paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) && it.name.endsWith(".js") }
                    .map { it.readText() }
                    .toList()
            }.joinToString("\n")
        }

        FORBIDDEN_TOKENS.forEach { token ->
            assertFalse(token in sources, "runtime에 금지 호출이 포함됨: $token")
        }
        FORBIDDEN_DYNAMIC_CALLS.forEach { pattern ->
            assertFalse(pattern.containsMatchIn(sources), "runtime에 동적 API 호출 우회가 포함됨: $pattern")
        }
        Regex("https://[^'\"`\\s)]+")
            .findAll(sources)
            .map { it.value }
            .forEach { url -> assertTrue(url.startsWith(FIXTURE_URL), "허용되지 않은 origin: $url") }
    }

    @Test
    fun `catalog mode와 안전 allowlist만 호출하고 cleanup은 생성한 ID에만 적용한다`() = withWorkspace { workspace ->
        val catalog = loadCatalog()
        val generated = ProbeSourceGenerator().generate(catalog, workspace.resolve("output"))
        val representative = generated.first().sourceRoot
        Files.writeString(representative.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val descriptorUris = generated.joinToString(",") {
            "'${it.sourceRoot.resolve("probe-cases.json").toUri()}'"
        }
        val groupImports = EXPECTED_GROUPS.sorted().mapIndexed { index, group ->
            "import { createCase as create$index } from '${representative.resolve("cases/$group.js").toUri()}';"
        }.joinToString("\n")
        val factories = EXPECTED_GROUPS.sorted().mapIndexed { index, group -> "'$group': create$index" }
            .joinToString(",")

        val output = runNodeModule(
            workspace,
            """
            import { readFileSync } from 'node:fs';
            import { runCase } from '${representative.resolve("probe-runtime.js").toUri()}';
            $groupImports
            const descriptors = [$descriptorUris].map((uri) => JSON.parse(readFileSync(new URL(uri), 'utf8')));
            const factories = {$factories};
            const calls = [];
            const fn = (name, value) => (...args) => { calls.push([name, args]); return Promise.resolve(value); };
            const chrome = {
              action: { getTitle: fn('action.getTitle', 'SIX API Probe') },
              alarms: { get: fn('alarms.get', undefined) },
              commands: { getAll: fn('commands.getAll', []) },
              contentSettings: { cookies: {} },
              contextMenus: {
                create: (...args) => {
                  calls.push(['contextMenus.create', args]);
                  args.at(-1)();
                  return 'sixbridge-probe-context-menu';
                },
                remove: fn('contextMenus.remove', undefined),
              },
              cookies: { get: fn('cookies.get', undefined) },
              debugger: { attach: fn('debugger.attach', undefined) },
              declarativeContent: { onPageChanged: {} },
              declarativeNetRequest: { getAvailableStaticRuleCount: fn('declarativeNetRequest.getAvailableStaticRuleCount', 0) },
              extension: { isAllowedIncognitoAccess: fn('extension.isAllowedIncognitoAccess', true) },
              history: { search: fn('history.search', []) },
              i18n: { getUILanguage: () => { calls.push(['i18n.getUILanguage', []]); return 'ko'; } },
              identity: { getRedirectURL: (...args) => { calls.push(['identity.getRedirectURL', args]); return 'https://example.invalid/'; } },
              idle: { queryState: fn('idle.queryState', 'active') },
              management: { getSelf: fn('management.getSelf', { id: 'probe' }) },
              notifications: { getPermissionLevel: fn('notifications.getPermissionLevel', 'granted') },
              offscreen: { hasDocument: fn('offscreen.hasDocument', false) },
              permissions: { contains: fn('permissions.contains', true) },
              privacy: { network: {} },
              proxy: { settings: {} },
              runtime: { getPlatformInfo: fn('runtime.getPlatformInfo', { os: 'android' }) },
              scripting: { getRegisteredContentScripts: fn('scripting.getRegisteredContentScripts', []) },
              search: { query: fn('search.query', undefined) },
              sidePanel: { getOptions: fn('sidePanel.getOptions', {}) },
              storage: { local: {} },
              tabGroups: { query: fn('tabGroups.query', []) },
              tabs: {
                create: (...args) => { calls.push(['tabs.create', args]); return Promise.resolve({ id: 731 }); },
                remove: fn('tabs.remove', undefined),
              },
              tts: { getVoices: fn('tts.getVoices', []) },
              userScripts: { getScripts: fn('userScripts.getScripts', []) },
              webAuthenticationProxy: { onRemoteSessionStateChange: {} },
              webNavigation: { getAllFrames: fn('webNavigation.getAllFrames', []) },
              webRequest: { onBeforeRequest: {} },
              windows: { getCurrent: fn('windows.getCurrent', { id: 1 }) },
            };
            const results = [];
            for (const group of descriptors) {
              for (const descriptor of group.cases) {
                results.push(await runCase(factories[group.groupId](descriptor, { chrome })));
              }
            }
            const summary = {
              calls: calls.map(([name, args]) => ({ name, args })),
              exposed: results.filter((result) => result.status === 'EXPOSED').length,
              verified: results.filter((result) => result.status === 'VERIFIED').length,
            };
            process.stdout.write(JSON.stringify(summary));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        assertEquals(13, output.getValue("exposed").jsonPrimitive.content.toInt())
        assertEquals(20, output.getValue("verified").jsonPrimitive.content.toInt())
        val calls = output.getValue("calls").jsonArray.map { it.jsonObject }
        val callNames = calls.map { it.getValue("name").jsonPrimitive.content }
        assertFalse(callNames.any { it in PRESENCE_ONLY_CALLS })
        assertEquals(SAFE_CALLS + CLEANUP_CALLS, callNames.toSet())
        assertEquals(listOf("tabs.remove", "contextMenus.remove"), callNames.filter { it.endsWith(".remove") })
        assertEquals(
            "sixbridge-probe-context-menu",
            calls.single { it.getValue("name").jsonPrimitive.content == "contextMenus.remove" }
                .getValue("args").jsonArray.first().jsonPrimitive.content,
        )
        assertEquals(
            "731",
            calls.single { it.getValue("name").jsonPrimitive.content == "tabs.remove" }
                .getValue("args").jsonArray.first().jsonPrimitive.content,
        )
        assertEquals(
            FIXTURE_URL,
            calls.single { it.getValue("name").jsonPrimitive.content == "tabs.create" }
                .getValue("args").jsonArray.first().jsonObject.getValue("url").jsonPrimitive.content,
        )
    }

    @Test
    fun `context menu cleanup은 create callback 완료 후 자신의 fixture ID만 제거한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val root = generated.first().sourceRoot
        Files.writeString(root.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val descriptor = generated
            .map { Json.parseToJsonElement(it.sourceRoot.resolve("probe-cases.json").readText()).jsonObject }
            .flatMap { it.getValue("cases").jsonArray }
            .map { it.jsonObject }
            .single { it.getValue("id").jsonPrimitive.content == "context-menus-create-worker" }
        val descriptorJson = Json.encodeToString(JsonObject.serializer(), descriptor)

        val output = runNodeModule(
            workspace,
            """
            import { runCase } from '${root.resolve("probe-runtime.js").toUri()}';
            import { createCase } from '${root.resolve("cases/user-interface.js").toUri()}';
            const events = [];
            const chrome = {
              contextMenus: {
                create: (_details, callback) => {
                  events.push('create-start');
                  setTimeout(() => { events.push('create-complete'); callback(); }, 20);
                  return 'sixbridge-probe-context-menu';
                },
                remove: (id, callback) => { events.push(`remove:${'$'}{id}`); callback(); },
              },
              runtime: {},
            };
            const result = await runCase(createCase($descriptorJson, { chrome }));
            process.stdout.write(JSON.stringify({ events, status: result.status }));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        assertEquals("VERIFIED", output.getValue("status").jsonPrimitive.content)
        assertEquals(
            listOf("create-start", "create-complete", "remove:sixbridge-probe-context-menu"),
            output.getValue("events").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `message bridge는 case ID를 상관관계화하고 timeout 후 다음 case를 계속한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val root = generated.first().sourceRoot
        Files.writeString(root.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val output = runNodeModule(
            workspace,
            """
            import { createProbeController, runSequentially } from '${root.resolve("service-worker.js").toUri()}';
            const descriptor = { id: 'gesture', namespace: 'action', member: 'getTitle', context: 'user_gesture', mode: 'safe_call' };
            const content = { ...descriptor, id: 'content', context: 'content_script' };
            const controller = createProbeController({
              descriptors: new Map([['gesture', descriptor], ['content', content]]),
              executeContextCase: async (testCase) => ({ caseId: testCase.id, status: 'VERIFIED' }),
              executeWorkerCase: async () => null,
            });
            const badType = await controller.handleMessage({ type: 'other', caseId: 'gesture' });
            const gesture = await controller.handleMessage({ type: 'six-probe-run', caseId: 'gesture' });
            const gestureAck = await controller.handleMessage({
              type: 'six-probe-result',
              result: { caseId: 'gesture', status: 'VERIFIED' },
            });
            const contentResult = await controller.handleMessage({ type: 'six-probe-run', caseId: 'content' });
            const results = await runSequentially(
              [{ id: 'slow' }, { id: 'fast' }],
              async (testCase) => testCase.id === 'slow' ? new Promise(() => {}) : ({ caseId: 'fast', status: 'VERIFIED' }),
              20,
            );
            process.stdout.write(JSON.stringify({ badType, contentResult, gesture, gestureAck, results }));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        assertTrue(output.getValue("badType") is kotlinx.serialization.json.JsonNull)
        assertEquals("VERIFIED", output.getValue("contentResult").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("NOT_RUN", output.getValue("gesture").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("six-probe-ack", output.getValue("gestureAck").jsonObject.getValue("type").jsonPrimitive.content)
        val results = output.getValue("results").jsonArray
        assertEquals(listOf("FAILED", "VERIFIED"), results.map { it.jsonObject.getValue("status").jsonPrimitive.content })
        assertEquals(listOf("slow", "fast"), results.map { it.jsonObject.getValue("caseId").jsonPrimitive.content })
    }

    @Test
    fun `현재 catalog에 content case가 없으면 fixture host 권한과 content script를 선언하지 않는다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))

        generated.forEach { probe ->
            val manifest = Json.parseToJsonElement(probe.sourceRoot.resolve("manifest.json").readText()).jsonObject
            assertFalse("host_permissions" in manifest)
            assertFalse("content_scripts" in manifest)
        }
    }

    private fun loadCatalog(): ApiProbeCatalog = ApiProbeCatalog.load(
        checkNotNull(javaClass.getResourceAsStream("/catalog/sbrowser-30-public-apis.json")),
    ).also { catalog ->
        assertEquals(
            33,
            catalog.entries.filter { it.disposition == CatalogDisposition.PROBE }.sumOf { it.cases.size },
        )
    }

    private fun runNodeModule(workspace: Path, source: String): String {
        val runner = workspace.resolve("runner-${System.nanoTime()}.mjs")
        Files.writeString(runner, source, UTF_8)
        val process = ProcessBuilder("node", runner.toString()).redirectErrorStream(true).start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            throw AssertionError("Node runtime contract test가 timeout되었습니다.")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.exitValue(), output)
        return output
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = Files.createTempDirectory("six-probe-case-safety-test")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val FIXTURE_URL = "https://example.com/"
        val EXPECTED_GROUPS = setOf(
            "advanced-runtime",
            "base-runtime",
            "identity-privacy",
            "network",
            "tab-page",
            "user-interface",
        )
        val EXPECTED_RUNTIME_ASSETS = setOf(
            "service-worker.js",
            "content-probe.js",
            "offscreen-probe.html",
            "offscreen-probe.js",
            "cases/advanced-runtime.js",
            "cases/base-runtime.js",
            "cases/identity-privacy.js",
            "cases/network.js",
            "cases/tab-page.js",
            "cases/user-interface.js",
        )
        val FORBIDDEN_TOKENS = setOf(
            "browsingData.remove",
            "history.delete",
            "downloads.download",
            "proxy.settings.set",
            "management.uninstall",
            "cookies.getAll",
        )
        val FORBIDDEN_DYNAMIC_CALLS = setOf(
            Regex("chrome(?:\\?\\.)?\\[[^]]+]\\s*\\("),
            Regex("\\.call\\s*\\(\\s*chrome"),
            Regex("\\.apply\\s*\\(\\s*chrome"),
        )
        val PRESENCE_ONLY_CALLS = setOf(
            "contentSettings.cookies",
            "cookies.get",
            "debugger.attach",
            "declarativeContent.onPageChanged",
            "history.search",
            "privacy.network",
            "proxy.settings",
            "search.query",
            "storage.local",
            "tabGroups.query",
            "webAuthenticationProxy.onRemoteSessionStateChange",
            "webNavigation.getAllFrames",
            "webRequest.onBeforeRequest",
        )
        val SAFE_CALLS = setOf(
            "action.getTitle",
            "alarms.get",
            "commands.getAll",
            "contextMenus.create",
            "declarativeNetRequest.getAvailableStaticRuleCount",
            "extension.isAllowedIncognitoAccess",
            "i18n.getUILanguage",
            "identity.getRedirectURL",
            "idle.queryState",
            "management.getSelf",
            "notifications.getPermissionLevel",
            "offscreen.hasDocument",
            "permissions.contains",
            "runtime.getPlatformInfo",
            "scripting.getRegisteredContentScripts",
            "sidePanel.getOptions",
            "tabs.create",
            "tts.getVoices",
            "userScripts.getScripts",
            "windows.getCurrent",
        )
        val CLEANUP_CALLS = setOf("contextMenus.remove", "tabs.remove")
    }
}
