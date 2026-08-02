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
        ).let { Json.parseToJsonElement(it).jsonArray.map { value -> value.jsonPrimitive.content } }

        assertEquals(33, actualIds.size)
        assertEquals(catalog.probeCaseIds, actualIds.toSet())
        assertTrue(actualIds.groupingBy { it }.eachCount().values.all { it == 1 })
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
        val tabFixtureUrl = calls.single { it.getValue("name").jsonPrimitive.content == "tabs.create" }
            .getValue("args").jsonArray.first().jsonObject.getValue("url").jsonPrimitive.content
        assertTrue(tabFixtureUrl.startsWith("${FIXTURE_URL}#sixbridge-api-probe=tabs-create-fixture-page"))
        assertTrue("runToken=local-run-00000000" in tabFixtureUrl)
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
    fun `create callback이 멈춰도 context menu와 token marker tab을 정리한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val root = generated.first().sourceRoot
        Files.writeString(root.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val descriptors = generated
            .map { Json.parseToJsonElement(it.sourceRoot.resolve("probe-cases.json").readText()).jsonObject }
            .flatMap { it.getValue("cases").jsonArray }
            .map { it.jsonObject }
            .filter { it.getValue("id").jsonPrimitive.content in setOf("context-menus-create-worker", "tabs-create-fixture-page") }
            .associateBy { it.getValue("id").jsonPrimitive.content }
        val contextJson = Json.encodeToString(JsonObject.serializer(), descriptors.getValue("context-menus-create-worker"))
        val tabsJson = Json.encodeToString(JsonObject.serializer(), descriptors.getValue("tabs-create-fixture-page"))
        val tabManifest = generated.single { it.groupId == "tab-page" }.sourceRoot
            .resolve("manifest.json").readText()
        val hasFixtureHost = Json.parseToJsonElement(tabManifest).jsonObject["host_permissions"]
            ?.jsonArray
            ?.any { it.jsonPrimitive.content == "https://example.com/*" }
            ?: false

        val output = runNodeModule(
            workspace,
            """
            import { runCase } from '${root.resolve("probe-runtime.js").toUri()}';
            import { createCase as createTabs } from '${root.resolve("cases/tab-page.js").toUri()}';
            import { createCase as createUi } from '${root.resolve("cases/user-interface.js").toUri()}';
            const events = [];
            const hasFixtureHost = $hasFixtureHost;
            let fixtureUrl;
            const chrome = {
              contextMenus: {
                create: (_details, _done) => { events.push('context:create'); return 'sixbridge-probe-context-menu'; },
                remove: (id, done) => { events.push(`context:remove:${'$'}{id}`); done(); },
              },
              runtime: {},
              tabs: {
                create: (details, _done) => { fixtureUrl = details.url; events.push('tab:create'); },
                query: (_query, done) => {
                  events.push('tab:query');
                  setTimeout(() => done(hasFixtureHost
                    ? [{ id: 41, url: fixtureUrl }, { id: 42, url: 'https://example.com/#other' }]
                    : [{ id: 41 }, { id: 42 }]), 10);
                },
                remove: (id, done) => { events.push(`tab:remove:${'$'}{id}`); done(); },
              },
            };
            const token = 'run-token-00000001';
            const contextResult = await runCase(createUi($contextJson, { chrome }), token, 20);
            const tabResult = await runCase(createTabs($tabsJson, { chrome }), token, 20);
            process.stdout.write(JSON.stringify({ contextResult, events, fixtureUrl, tabResult }));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        assertEquals("FAILED", output.getValue("contextResult").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("FAILED", output.getValue("tabResult").jsonObject.getValue("status").jsonPrimitive.content)
        assertTrue(output.getValue("fixtureUrl").jsonPrimitive.content.endsWith("runToken=run-token-00000001"))
        assertEquals(
            listOf(
                "context:create",
                "context:remove:sixbridge-probe-context-menu",
                "tab:create",
                "tab:query",
                "tab:remove:41",
            ),
            output.getValue("events").jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test
    fun `실제 service worker listener는 sender를 검증하고 canonical result를 응답한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val root = generated.first().sourceRoot
        Files.writeString(root.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val output = runNodeModule(
            workspace,
            """
            import { installServiceWorkerBridge } from '${root.resolve("service-worker.js").toUri()}';
            let installed;
            const descriptor = {
              id: 'alarms-get-worker', namespace: 'alarms', member: 'get',
              context: 'service_worker', mode: 'safe_call', permissions: ['alarms'],
            };
            const chrome = {
              alarms: { get: (_name, done) => done(undefined) },
              runtime: {
                id: 'runtime-id',
                getURL: (path) => `chrome-extension://runtime-id/${'$'}{path}`,
                onMessage: { addListener: (listener) => { installed = listener; } },
              },
            };
            const environment = { chrome };
            const listener = installServiceWorkerBridge(
              environment,
              async () => ({ ok: true, json: async () => ({ groupId: 'base-runtime', cases: [descriptor] }) }),
              { timeoutMs: 50, tokenFactory: () => 'run-token-00000001' },
            );
            const sender = { id: 'runtime-id', url: chrome.runtime.getURL('probe.html') };
            const response = await new Promise((resolve) => {
              const asyncResponse = listener({ type: 'six-probe-run', caseId: descriptor.id }, sender, resolve);
              if (asyncResponse !== true) throw new Error('listener did not keep response channel open');
            });
            const ignored = listener({ type: 'other' }, sender, () => { throw new Error('unexpected response'); });
            process.stdout.write(JSON.stringify({ ignored, installed: installed === listener, response }));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        assertEquals("false", output.getValue("ignored").jsonPrimitive.content)
        assertEquals("true", output.getValue("installed").jsonPrimitive.content)
        val result = output.getValue("response").jsonObject.getValue("result").jsonObject
        assertEquals("alarms-get-worker", result.getValue("caseId").jsonPrimitive.content)
        assertEquals("run-token-00000001", result.getValue("runToken").jsonPrimitive.content)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `service worker context callback이 멈춰도 소유 tab과 offscreen document를 정리한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val root = generated.first().sourceRoot
        Files.writeString(root.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val output = runNodeModule(
            workspace,
            """
            import { installServiceWorkerBridge } from '${root.resolve("service-worker.js").toUri()}';
            const events = [];
            const content = {
              id: 'alarms-get-worker', namespace: 'alarms', member: 'get',
              context: 'content_script', mode: 'safe_call', permissions: ['alarms'],
            };
            const offscreen = { ...content, id: 'offscreen-hang', context: 'offscreen_document' };
            const tokens = ['run-token-00000001', 'run-token-00000002'];
            const chrome = {
              offscreen: {
                hasDocument: async () => false,
                createDocument: async () => { events.push('offscreen:create'); },
                closeDocument: async () => { events.push('offscreen:close'); },
              },
              runtime: {
                id: 'runtime-id',
                getURL: (path) => `chrome-extension://runtime-id/${'$'}{String(path).replace(/^\//, '')}`,
                onMessage: { addListener: () => {} },
                sendMessage: (_message, _done) => { events.push('offscreen:send'); },
              },
              scripting: { executeScript: async () => { events.push('content:inject'); } },
              tabs: {
                create: (details, done) => { events.push(`tab:create:${'$'}{details.url}`); done({ id: 77 }); },
                sendMessage: (_id, _message, _done) => { events.push('tab:send'); },
                remove: (id, done) => { events.push(`tab:remove:${'$'}{id}`); done(); },
                query: (_query, done) => done([]),
              },
            };
            const listener = installServiceWorkerBridge(
              { chrome },
              async () => ({ ok: true, json: async () => ({ groupId: 'base-runtime', cases: [content, offscreen] }) }),
              { timeoutMs: 20, tokenFactory: () => tokens.shift() },
            );
            const sender = { id: 'runtime-id', url: chrome.runtime.getURL('probe.html') };
            const run = (caseId) => new Promise((resolve) => listener({ type: 'six-probe-run', caseId }, sender, resolve));
            const contentResult = await run(content.id);
            const offscreenResult = await run(offscreen.id);
            process.stdout.write(JSON.stringify({ contentResult, events, offscreenResult }));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        assertEquals("FAILED", output.getValue("contentResult").jsonObject.getValue("result").jsonObject
            .getValue("status").jsonPrimitive.content)
        assertEquals("FAILED", output.getValue("offscreenResult").jsonObject.getValue("result").jsonObject
            .getValue("status").jsonPrimitive.content)
        val events = output.getValue("events").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(events.first { it.startsWith("tab:create:") }.contains("runToken=run-token-00000001"))
        assertTrue("tab:remove:77" in events)
        assertEquals(
            listOf("offscreen:create", "offscreen:send", "offscreen:close"),
            events.filter { it.startsWith("offscreen:") },
        )
    }

    @Test
    fun `offscreen lease는 동시 run이 끝난 뒤 probe 생성 document만 한 번 닫는다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val root = generated.first().sourceRoot
        Files.writeString(root.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val output = runNodeModule(
            workspace,
            """
            import { installServiceWorkerBridge } from '${root.resolve("service-worker.js").toUri()}';
            const events = [];
            const descriptor = (id) => ({
              id, namespace: 'alarms', member: 'get', context: 'offscreen_document',
              mode: 'safe_call', permissions: ['alarms'],
            });
            const first = descriptor('offscreen-first');
            const second = descriptor('offscreen-second');
            const hanging = descriptor('offscreen-hanging');
            const throwing = descriptor('offscreen-throwing');
            const existing = descriptor('offscreen-existing');
            const tokens = [
              'run-token-00000001', 'run-token-00000002', 'run-token-00000003',
              'run-token-00000004', 'run-token-00000005',
            ];
            let exists = false;
            let creating = false;
            let listener;
            const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
            const chrome = {
              offscreen: {
                hasDocument: async () => { events.push('has'); await wait(5); return exists; },
                createDocument: async () => {
                  events.push('create');
                  if (creating) throw new Error('concurrent create');
                  creating = true;
                  await wait(5);
                  exists = true;
                  creating = false;
                },
                closeDocument: async () => { events.push('close'); exists = false; },
              },
              runtime: {
                id: 'runtime-id',
                getURL: (path) => `chrome-extension://runtime-id/${'$'}{String(path).replace(/^\//, '')}`,
                onMessage: { addListener: (value) => { listener = value; } },
                sendMessage: (message, done) => {
                  events.push(`send:${'$'}{message.caseId}`);
                  if (message.caseId === throwing.id) throw new Error('dispatch throw');
                  done({ caseId: message.caseId, runToken: message.runToken, type: 'six-probe-dispatched' });
                  if (message.caseId === hanging.id) return;
                  const delay = message.caseId === second.id ? 30 : 10;
                  setTimeout(() => {
                    events.push(`result:${'$'}{message.caseId}`);
                    const result = {
                      caseId: message.caseId,
                      context: message.descriptor.context,
                      detail: { kind: 'success', valueType: 'string' },
                      durationMs: delay,
                      member: message.descriptor.member,
                      namespace: message.descriptor.namespace,
                      runToken: message.runToken,
                      status: 'VERIFIED',
                    };
                    listener(
                      { result, type: 'six-probe-result' },
                      { id: 'runtime-id', url: chrome.runtime.getURL('offscreen-probe.html') },
                      (ack) => events.push(`ack:${'$'}{ack.caseId}`),
                    );
                  }, delay);
                },
              },
            };
            listener = installServiceWorkerBridge(
              { chrome },
              async () => ({
                ok: true,
                json: async () => ({ groupId: 'base-runtime', cases: [first, second, hanging, throwing, existing] }),
              }),
              { timeoutMs: 60, tokenFactory: () => tokens.shift() },
            );
            const pageSender = { id: 'runtime-id', url: chrome.runtime.getURL('probe.html') };
            const run = (caseId) => new Promise((resolve) => listener(
              { caseId, type: 'six-probe-run' }, pageSender, resolve,
            ));
            const [firstResult, secondResult] = await Promise.all([run(first.id), run(second.id)]);
            const hangingResult = await run(hanging.id);
            const throwingResult = await run(throwing.id);
            exists = true;
            const closeCountBeforeExisting = events.filter((event) => event === 'close').length;
            const existingResult = await run(existing.id);
            process.stdout.write(JSON.stringify({
              closeCountBeforeExisting,
              events,
              existingResult,
              firstResult,
              hangingResult,
              secondResult,
              throwingResult,
            }));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        fun status(name: String): String = output.getValue(name).jsonObject.getValue("result").jsonObject
            .getValue("status").jsonPrimitive.content
        assertEquals("VERIFIED", status("firstResult"))
        assertEquals("VERIFIED", status("secondResult"))
        assertEquals("FAILED", status("hangingResult"))
        assertEquals("FAILED", status("throwingResult"))
        assertEquals("VERIFIED", status("existingResult"))
        val events = output.getValue("events").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(3, events.count { it == "create" })
        assertEquals(3, events.count { it == "close" })
        assertTrue(events.indexOf("close") > events.indexOf("result:offscreen-second"))
        assertEquals("3", output.getValue("closeCountBeforeExisting").jsonPrimitive.content)
    }

    @Test
    fun `offscreen lifecycle hang과 late create 뒤에도 queue가 다음 case를 실행한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val root = generated.first().sourceRoot
        Files.writeString(root.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val output = runNodeModule(
            workspace,
            """
            import { installServiceWorkerBridge } from '${root.resolve("service-worker.js").toUri()}';
            const wait = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
            const terminal = (promise, timeoutMs = 150) => Promise.race([
              promise,
              wait(timeoutMs).then(() => ({ timeout: true })),
            ]);
            const descriptor = (id) => ({
              id, namespace: 'alarms', member: 'get', context: 'offscreen_document',
              mode: 'safe_call', permissions: ['alarms'],
            });
            function harness(prefix, lifecycle, resultDelay = 0, operationTimeoutMs = 20) {
              const events = [];
              const first = descriptor(`${'$'}{prefix}-first`);
              const second = descriptor(`${'$'}{prefix}-second`);
              const third = descriptor(`${'$'}{prefix}-third`);
              const tokens = [
                `${'$'}{prefix}-run-token-0001`,
                `${'$'}{prefix}-run-token-0002`,
                `${'$'}{prefix}-run-token-0003`,
              ];
              let listener;
              const chrome = {
                offscreen: lifecycle(events),
                runtime: {
                  id: `${'$'}{prefix}-runtime`,
                  getURL: (path) => `chrome-extension://${'$'}{prefix}-runtime/${'$'}{String(path).replace(/^\//, '')}`,
                  onMessage: { addListener: (value) => { listener = value; } },
                  sendMessage: (message, done) => {
                    events.push(`send:${'$'}{message.caseId}`);
                    done({ caseId: message.caseId, runToken: message.runToken, type: 'six-probe-dispatched' });
                    setTimeout(() => {
                      events.push(`result:${'$'}{message.caseId}`);
                      const result = {
                        caseId: message.caseId,
                        context: message.descriptor.context,
                        detail: { kind: 'success', valueType: 'string' },
                        durationMs: resultDelay,
                        member: message.descriptor.member,
                        namespace: message.descriptor.namespace,
                        runToken: message.runToken,
                        status: 'VERIFIED',
                      };
                      listener(
                        { result, type: 'six-probe-result' },
                        { id: chrome.runtime.id, url: chrome.runtime.getURL('offscreen-probe.html') },
                        () => {},
                      );
                    }, resultDelay);
                  },
                },
              };
              listener = installServiceWorkerBridge(
                { chrome },
                async () => ({
                  ok: true,
                  json: async () => ({ groupId: 'base-runtime', cases: [first, second, third] }),
                }),
                {
                  lifecycleTimeoutMs: 10,
                  timeoutMs: operationTimeoutMs,
                  tokenFactory: () => tokens.shift(),
                },
              );
              const sender = { id: chrome.runtime.id, url: chrome.runtime.getURL('probe.html') };
              const run = (caseId) => new Promise((resolve) => listener(
                { caseId, type: 'six-probe-run' }, sender, resolve,
              ));
              return { events, first, run, second, third };
            }

            let createCalls = 0;
            let createExists = false;
            const createHang = harness('create-hang', (events) => ({
              hasDocument: async () => createExists,
              createDocument: async () => {
                createCalls += 1;
                events.push(`create:${'$'}{createCalls}`);
                if (createCalls === 1) return new Promise(() => {});
                createExists = true;
              },
              closeDocument: async () => { events.push('close'); createExists = false; },
            }));
            const createHangFirst = await terminal(createHang.run(createHang.first.id), 1_200);
            const createHangSecond = await terminal(createHang.run(createHang.second.id));

            let closeExists = false;
            let closeCalls = 0;
            const closeHang = harness('close-hang', (events) => ({
              hasDocument: async () => closeExists,
              createDocument: async () => { events.push('create'); closeExists = true; },
              closeDocument: async () => {
                closeCalls += 1;
                events.push(`close:${'$'}{closeCalls}`);
                return new Promise(() => {});
              },
            }));
            const closeHangFirst = await terminal(closeHang.run(closeHang.first.id), 1_200);
            const closeHangSecond = await terminal(closeHang.run(closeHang.second.id));

            let lateCloseExists = false;
            let lateCloseCalls = 0;
            const lateClose = harness('late-close', (events) => ({
              hasDocument: async () => lateCloseExists,
              createDocument: async () => { events.push('create'); lateCloseExists = true; },
              closeDocument: async () => {
                lateCloseCalls += 1;
                events.push(`close:${'$'}{lateCloseCalls}`);
                if (lateCloseCalls === 1) await wait(50);
                lateCloseExists = false;
                events.push(`close-resolved:${'$'}{lateCloseCalls}`);
              },
            }));
            const lateCloseFirst = await terminal(lateClose.run(lateClose.first.id), 1_200);
            const lateCloseSecondStartedAt = Date.now();
            const lateCloseSecond = await terminal(lateClose.run(lateClose.second.id));
            const lateCloseSecondElapsed = Date.now() - lateCloseSecondStartedAt;
            await wait(70);
            const lateCloseThird = await terminal(lateClose.run(lateClose.third.id));

            let lateCreateCalls = 0;
            let lateExists = false;
            const lateCreate = harness('late-create', (events) => ({
              hasDocument: async () => lateExists,
              createDocument: async () => {
                lateCreateCalls += 1;
                events.push(`create:${'$'}{lateCreateCalls}`);
                if (lateCreateCalls === 1) await wait(50);
                lateExists = true;
              },
              closeDocument: async () => { events.push('close'); lateExists = false; },
            }), 70, 100);
            const lateStartedAt = Date.now();
            const lateFirstPromise = lateCreate.run(lateCreate.first.id);
            await wait(15);
            const lateSecondPromise = lateCreate.run(lateCreate.second.id);
            const lateCreateFirst = await terminal(lateFirstPromise);
            const lateFirstElapsed = Date.now() - lateStartedAt;
            const lateCreateSecond = await terminal(lateSecondPromise);
            await wait(20);

            process.stdout.write(JSON.stringify({
              closeCalls,
              closeHangEvents: closeHang.events,
              closeHangFirst,
              closeHangSecond,
              createHangEvents: createHang.events,
              createHangFirst,
              createHangSecond,
              lateCreateEvents: lateCreate.events,
              lateCreateFirst,
              lateCreateSecond,
              lateFirstElapsed,
              lateCloseCalls,
              lateCloseEvents: lateClose.events,
              lateCloseFirst,
              lateCloseSecond,
              lateCloseSecondElapsed,
              lateCloseThird,
            }));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        fun assertTerminal(name: String, expectedStatus: String) {
            val response = output.getValue(name).jsonObject
            assertFalse("timeout" in response, "$name queue가 settle되지 않았습니다.")
            assertEquals(
                expectedStatus,
                response.getValue("result").jsonObject.getValue("status").jsonPrimitive.content,
                name,
            )
        }
        assertTerminal("createHangFirst", "FAILED")
        assertTerminal("createHangSecond", "VERIFIED")
        assertTerminal("closeHangFirst", "VERIFIED")
        assertTerminal("closeHangSecond", "FAILED")
        assertTerminal("lateCloseFirst", "VERIFIED")
        assertTerminal("lateCloseSecond", "FAILED")
        assertTerminal("lateCloseThird", "VERIFIED")
        assertTerminal("lateCreateFirst", "FAILED")
        assertTerminal("lateCreateSecond", "VERIFIED")
        assertEquals("1", output.getValue("closeCalls").jsonPrimitive.content)
        val closeHangEvents = output.getValue("closeHangEvents").jsonArray.map { it.jsonPrimitive.content }
        assertFalse("send:close-hang-second" in closeHangEvents)
        val lateCloseEvents = output.getValue("lateCloseEvents").jsonArray.map { it.jsonPrimitive.content }
        assertFalse("send:late-close-second" in lateCloseEvents)
        assertTrue(lateCloseEvents.indexOf("close-resolved:1") < lateCloseEvents.indexOf("send:late-close-third"))
        assertEquals("2", output.getValue("lateCloseCalls").jsonPrimitive.content)
        assertTrue(output.getValue("lateCloseSecondElapsed").jsonPrimitive.content.toInt() < 45)
        val lateCloseMessage = output.getValue("lateCloseSecond").jsonObject
            .getValue("result").jsonObject.getValue("detail").jsonObject
            .getValue("message").jsonPrimitive.content
        assertTrue(lateCloseMessage.contains("offscreen lifecycle is closing"))
        val lateEvents = output.getValue("lateCreateEvents").jsonArray.map { it.jsonPrimitive.content }
        assertEquals(1, lateEvents.count { it == "close" })
        assertTrue(lateEvents.indexOf("close") > lateEvents.indexOf("result:late-create-second"))
        assertTrue(output.getValue("lateFirstElapsed").jsonPrimitive.content.toInt() < 45)
    }

    @Test
    fun `실제 content listener는 hash token을 검증하고 dispatch와 result를 분리한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val root = generated.first().sourceRoot
        Files.writeString(root.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val output = runNodeModule(
            workspace,
            """
            let listener;
            let sentResult;
            const token = 'run-token-00000001';
            const descriptor = {
              id: 'action-get-title-page', namespace: 'action', member: 'getTitle',
              context: 'content_script', mode: 'safe_call', permissions: ['action'],
            };
            globalThis.location = { href: `https://example.com/#sixbridge-api-probe=${'$'}{descriptor.id}&runToken=${'$'}{token}` };
            globalThis.chrome = {
              action: { getTitle: (_details, done) => done('SIX') },
              runtime: {
                getURL: (path) => new URL(path, '${root.toUri()}').href,
                onMessage: { addListener: (value) => { listener = value; } },
                sendMessage: (message, done) => {
                  sentResult = message;
                  done({ caseId: descriptor.id, runToken: token, type: 'six-probe-ack' });
                },
              },
            };
            await import('${root.resolve("content-probe.js").toUri()}');
            const message = {
              caseId: descriptor.id, descriptor, groupId: 'user-interface',
              runToken: token, type: 'six-probe-run',
            };
            const dispatch = await new Promise((resolve) => listener(message, {}, resolve));
            const rejected = listener({ ...message, runToken: 'run-token-99999999' }, {}, () => {
              throw new Error('mismatched hash token responded');
            });
            for (let index = 0; index < 20 && !sentResult; index += 1) {
              await new Promise((resolve) => setTimeout(resolve, 5));
            }
            process.stdout.write(JSON.stringify({ dispatch, rejected, sentResult }));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        assertEquals("six-probe-dispatched", output.getValue("dispatch").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("false", output.getValue("rejected").jsonPrimitive.content)
        val result = output.getValue("sentResult").jsonObject.getValue("result").jsonObject
        assertEquals("run-token-00000001", result.getValue("runToken").jsonPrimitive.content)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `실제 offscreen listener는 exact acknowledgement까지 확인한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val root = generated.first().sourceRoot
        Files.writeString(root.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val output = runNodeModule(
            workspace,
            """
            let listener;
            let sentResult;
            const token = 'run-token-00000001';
            const descriptor = {
              id: 'action-get-title-page', namespace: 'action', member: 'getTitle',
              context: 'offscreen_document', mode: 'safe_call', permissions: ['action'],
            };
            globalThis.chrome = {
              action: { getTitle: (_details, done) => done('SIX') },
              runtime: {
                onMessage: { addListener: (value) => { listener = value; } },
                sendMessage: (message, done) => {
                  sentResult = message;
                  done({ caseId: descriptor.id, runToken: token, type: 'six-probe-ack' });
                },
              },
            };
            await import('${root.resolve("offscreen-probe.js").toUri()}');
            const message = {
              bridgeTarget: 'offscreen', caseId: descriptor.id, descriptor,
              groupId: 'user-interface', runToken: token, type: 'six-probe-run',
            };
            const dispatch = await new Promise((resolve) => listener(message, {}, resolve));
            for (let index = 0; index < 20 && !sentResult; index += 1) {
              await new Promise((resolve) => setTimeout(resolve, 5));
            }
            process.stdout.write(JSON.stringify({ dispatch, sentResult }));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        assertEquals("six-probe-dispatched", output.getValue("dispatch").jsonObject.getValue("type").jsonPrimitive.content)
        val result = output.getValue("sentResult").jsonObject.getValue("result").jsonObject
        assertEquals("run-token-00000001", result.getValue("runToken").jsonPrimitive.content)
        assertEquals("VERIFIED", result.getValue("status").jsonPrimitive.content)
    }

    @Test
    fun `gesture runner는 중복 click을 막고 NOT_RUN을 최종 상태로 치환한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val root = generated.first().sourceRoot
        Files.writeString(root.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val output = runNodeModule(
            workspace,
            """
            import { createGestureRunner, transitionCounts } from '${root.resolve("probe-ui.js").toUri()}';
            const button = { disabled: false, hidden: false };
            const counts = { ABSENT: 0, EXPOSED: 0, FAILED: 0, NOT_RUN: 1, RESTRICTED: 0, VERIFIED: 0 };
            let executions = 0;
            let finish;
            const gate = new Promise((resolve) => { finish = resolve; });
            const handler = createGestureRunner({
              button,
              execute: async () => { executions += 1; await gate; return { status: 'VERIFIED' }; },
              onResult: (result) => transitionCounts(counts, 'NOT_RUN', result.status),
            });
            const first = handler();
            const repeated = await handler();
            const disabledDuringRun = button.disabled;
            finish();
            await first;
            process.stdout.write(JSON.stringify({ button, counts, disabledDuringRun, executions, repeated }));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        assertEquals("1", output.getValue("executions").jsonPrimitive.content)
        assertTrue(output.getValue("repeated") is kotlinx.serialization.json.JsonNull)
        assertEquals("true", output.getValue("disabledDuringRun").jsonPrimitive.content)
        assertEquals("true", output.getValue("button").jsonObject.getValue("hidden").jsonPrimitive.content)
        assertEquals("0", output.getValue("counts").jsonObject.getValue("NOT_RUN").jsonPrimitive.content)
        assertEquals("1", output.getValue("counts").jsonObject.getValue("VERIFIED").jsonPrimitive.content)
    }

    @Test
    fun `controller는 run token과 sender를 결속하고 동시 실행·위조·지연 result를 거부한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val root = generated.first().sourceRoot
        Files.writeString(root.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val output = runNodeModule(
            workspace,
            """
            import { createProbeController } from '${root.resolve("service-worker.js").toUri()}';
            const descriptor = { id: 'page-case', namespace: 'action', member: 'getTitle', context: 'extension_page', mode: 'safe_call' };
            const sender = { id: 'runtime-id', url: 'chrome-extension://runtime-id/probe.html' };
            const spoof = { id: 'runtime-id', url: 'chrome-extension://runtime-id/evil.html' };
            const tokens = ['run-token-00000001', 'run-token-00000002', 'run-token-00000003'];
            const controller = createProbeController({
              descriptors: new Map([[descriptor.id, descriptor]]),
              executeContextCase: async () => null,
              executeWorkerCase: async () => null,
              extensionPageUrl: sender.url,
              runtimeId: sender.id,
              timeoutMs: 25,
              tokenFactory: () => tokens.shift(),
            });
            const first = await controller.handleMessage({ type: 'six-probe-run', caseId: descriptor.id }, sender);
            const duplicate = await controller.handleMessage({ type: 'six-probe-run', caseId: descriptor.id }, sender);
            const result = {
              caseId: descriptor.id,
              context: descriptor.context,
              detail: { kind: 'success', valueType: 'string' },
              durationMs: 1,
              member: descriptor.member,
              namespace: descriptor.namespace,
              runToken: first.runToken,
              status: 'VERIFIED',
            };
            const spoofAck = await controller.handleMessage({ type: 'six-probe-result', result }, spoof);
            const ack = await controller.handleMessage({ type: 'six-probe-result', result }, sender);
            const late = await controller.handleMessage({ type: 'six-probe-result', result }, sender);
            const expiring = await controller.handleMessage({ type: 'six-probe-run', caseId: descriptor.id }, sender);
            await new Promise((resolve) => setTimeout(resolve, 35));
            const expired = await controller.handleMessage({
              type: 'six-probe-result',
              result: { ...result, runToken: expiring.runToken },
            }, sender);
            process.stdout.write(JSON.stringify({
              ack, duplicate, expired, first, late, pendingCount: controller.pendingCount(), spoofAck,
            }));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        val first = output.getValue("first").jsonObject
        assertEquals("run-token-00000001", first.getValue("runToken").jsonPrimitive.content)
        assertEquals("six-probe-dispatch", first.getValue("type").jsonPrimitive.content)
        assertEquals("FAILED", output.getValue("duplicate").jsonObject.getValue("result").jsonObject.getValue("status").jsonPrimitive.content)
        assertTrue(output.getValue("spoofAck") is kotlinx.serialization.json.JsonNull)
        val ack = output.getValue("ack").jsonObject
        assertEquals("six-probe-ack", ack.getValue("type").jsonPrimitive.content)
        assertEquals("run-token-00000001", ack.getValue("runToken").jsonPrimitive.content)
        assertTrue(output.getValue("late") is kotlinx.serialization.json.JsonNull)
        assertTrue(output.getValue("expired") is kotlinx.serialization.json.JsonNull)
        assertEquals("0", output.getValue("pendingCount").jsonPrimitive.content)
    }

    @Test
    fun `strict result validator는 descriptor·token·status가 다른 outer response를 거부한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val root = generated.first().sourceRoot
        Files.writeString(root.resolve("package.json"), "{\"type\":\"module\"}\n", UTF_8)
        val output = runNodeModule(
            workspace,
            """
            import { failedResult, validateResultEnvelope } from '${root.resolve("probe-runtime.js").toUri()}';
            const descriptor = { id: 'case', namespace: 'tabs', member: 'create', context: 'extension_page' };
            const failed = failedResult(descriptor, 'run-token-00000001', new Error('  broken   value  '), 4);
            const valid = { type: 'six-probe-result', result: failed };
            const accepted = validateResultEnvelope(valid, descriptor, 'run-token-00000001');
            const malformed = [
              { ...valid, type: 'other' },
              { ...valid, extra: true },
              { ...valid, result: { ...failed, runToken: 'wrong-token-0001' } },
              { ...valid, result: { ...failed, status: 'UNKNOWN' } },
              { ...valid, result: { ...failed, namespace: 'history' } },
              { ...valid, result: { ...failed, detail: { kind: 'error', message: 'x'.repeat(5000), name: 'Error' } } },
            ].map((value) => {
              try { validateResultEnvelope(value, descriptor, 'run-token-00000001'); return false; }
              catch { return true; }
            });
            process.stdout.write(JSON.stringify({ accepted, malformed }));
            """.trimIndent(),
        ).let { Json.parseToJsonElement(it).jsonObject }

        assertEquals("broken value", output.getValue("accepted").jsonObject.getValue("result").jsonObject
            .getValue("detail").jsonObject.getValue("message").jsonPrimitive.content)
        assertTrue(output.getValue("malformed").jsonArray.all { it.jsonPrimitive.content.toBoolean() })
    }

    @Test
    fun `fixture host 권한은 tab fixture group에만 exact match로 선언한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))

        generated.forEach { probe ->
            val manifest = Json.parseToJsonElement(probe.sourceRoot.resolve("manifest.json").readText()).jsonObject
            if (probe.groupId == "tab-page") {
                assertEquals(
                    listOf("https://example.com/*"),
                    manifest.getValue("host_permissions").jsonArray.map { it.jsonPrimitive.content },
                )
            } else {
                assertFalse("host_permissions" in manifest)
            }
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
