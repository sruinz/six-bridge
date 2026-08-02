package dev.sixbridge.probe.generate

import dev.sixbridge.probe.catalog.ApiProbeCatalog
import dev.sixbridge.probe.catalog.CatalogDisposition
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProbeSourceGeneratorTest {
    @Test
    fun `같은 catalog는 같은 여섯 probe source를 생성한다`() = withWorkspace { workspace ->
        val catalog = loadCatalog()

        val first = ProbeSourceGenerator().generate(catalog, workspace.resolve("first"))
        val second = ProbeSourceGenerator().generate(catalog, workspace.resolve("second"))

        assertEquals(EXPECTED_GROUPS, first.map { it.groupId }.toSet())
        assertEquals(EXPECTED_GROUPS.sorted(), first.map { it.groupId })
        assertEquals(treeDigest(first), treeDigest(second))
        assertTrue(first.all { it.catalogSha256 == catalog.sha256 })
        assertTrue(first.all { it.manifestSha256 == sha256(it.sourceRoot.resolve("manifest.json")) })
        assertTrue(Files.list(workspace).use { entries ->
            entries.noneMatch { it.name.contains(".staging-") }
        })
    }

    @Test
    fun `manifest는 그룹에 필요한 최소 권한만 canonical 순서로 선언한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))

        val expectedPermissions = mapOf(
            "advanced-runtime" to listOf("debugger", "offscreen", "scripting", "userScripts"),
            "base-runtime" to listOf("alarms", "storage"),
            "identity-privacy" to listOf(
                "contentSettings",
                "cookies",
                "history",
                "identity",
                "idle",
                "privacy",
                "webAuthenticationProxy",
            ),
            "network" to listOf(
                "declarativeContent",
                "declarativeNetRequest",
                "proxy",
                "webNavigation",
                "webRequest",
            ),
            "tab-page" to listOf("tabGroups"),
            "user-interface" to listOf("contextMenus", "notifications", "sidePanel", "tts"),
        )

        generated.forEach { probe ->
            val raw = probe.sourceRoot.resolve("manifest.json").readText()
            val manifest = Json.parseToJsonElement(raw).jsonObject

            assertEquals(3, manifest.getValue("manifest_version").jsonPrimitive.content.toInt())
            assertEquals("SIX API Probe · ${probe.groupId}", manifest.getValue("name").jsonPrimitive.content)
            assertEquals(expectedPermissions.getValue(probe.groupId), manifest.getValue("permissions").jsonArray.strings())
            assertFalse("host_permissions" in manifest)
            assertEquals(
                "service-worker.js",
                manifest.getValue("background").jsonObject.getValue("service_worker").jsonPrimitive.content,
            )
            assertEquals(
                "probe.html",
                manifest.getValue("action").jsonObject.getValue("default_popup").jsonPrimitive.content,
            )
            assertEquals(
                mapOf(
                    "16" to "icons/probe-16.png",
                    "48" to "icons/probe-48.png",
                    "128" to "icons/probe-128.png",
                ),
                manifest.getValue("icons").jsonObject.mapValues { it.value.jsonPrimitive.content },
            )
            assertCanonicalObjectOrder(Json.parseToJsonElement(raw).jsonObject)
        }
    }

    @Test
    fun `group descriptor는 catalog case를 손실 없이 결정론적으로 나눈다`() = withWorkspace { workspace ->
        val catalog = loadCatalog()
        val generated = ProbeSourceGenerator().generate(catalog, workspace.resolve("output"))
        val expectedByGroup = catalog.entries
            .filter { it.disposition == CatalogDisposition.PROBE }
            .groupBy { requireNotNull(it.groupId) }
            .mapValues { (_, entries) ->
                entries.flatMap { entry ->
                    entry.cases.map { case ->
                        Descriptor(
                            namespace = entry.namespace,
                            member = case.member,
                            context = case.context.name.lowercase(),
                            mode = case.mode.name.lowercase(),
                            expectedOutcome = case.expectedOutcome.name.lowercase(),
                            permissions = case.permissions.sorted(),
                            manifestKeys = case.manifestKeys.sorted(),
                            cleanupMember = case.cleanup?.member,
                            cleanupTarget = case.cleanup?.target?.name?.lowercase(),
                            cleanupFixtureId = case.cleanup?.fixtureId,
                            id = case.id,
                        )
                    }
                }.sortedBy(Descriptor::id)
            }

        val actualByGroup = generated.associate { probe ->
            val descriptor = Json.parseToJsonElement(
                probe.sourceRoot.resolve("probe-cases.json").readText(),
            ).jsonObject
            assertEquals(1, descriptor.getValue("schemaVersion").jsonPrimitive.content.toInt())
            assertEquals(probe.groupId, descriptor.getValue("groupId").jsonPrimitive.content)
            assertEquals(catalog.sha256, descriptor.getValue("catalogSha256").jsonPrimitive.content)
            assertCanonicalObjectOrder(descriptor)
            probe.groupId to descriptor.getValue("cases").jsonArray.map {
                val value = it.jsonObject
                val cleanup = value.getValue("cleanup")
                val cleanupObject = if (cleanup is JsonObject) cleanup else null
                Descriptor(
                    namespace = value.getValue("namespace").jsonPrimitive.content,
                    member = value.getValue("member").jsonPrimitive.content,
                    context = value.getValue("context").jsonPrimitive.content,
                    mode = value.getValue("mode").jsonPrimitive.content,
                    expectedOutcome = value.getValue("expectedOutcome").jsonPrimitive.content,
                    permissions = value.getValue("permissions").jsonArray.strings(),
                    manifestKeys = value.getValue("manifestKeys").jsonArray.strings(),
                    cleanupMember = cleanupObject?.getValue("member")?.jsonPrimitive?.content,
                    cleanupTarget = cleanupObject?.getValue("target")?.jsonPrimitive?.content,
                    cleanupFixtureId = cleanupObject?.get("fixtureId")?.let { fixture ->
                        if (fixture is kotlinx.serialization.json.JsonNull) null else fixture.jsonPrimitive.content
                    },
                    id = value.getValue("id").jsonPrimitive.content,
                )
            }.sortedBy(Descriptor::id)
        }

        assertEquals(expectedByGroup, actualByGroup)
        assertEquals(catalog.probeCaseIds, actualByGroup.values.flatten().map(Descriptor::id).toSet())
        assertEquals(
            DescriptorCleanup("remove", "fixture_id", "sixbridge-probe-context-menu"),
            actualByGroup.values.flatten().single { it.namespace == "contextMenus" }.cleanup(),
        )
        assertEquals(
            DescriptorCleanup("remove", "created_resource_id", null),
            actualByGroup.values.flatten().single { it.namespace == "tabs" }.cleanup(),
        )
        val nonProbeNamespaces = catalog.entries
            .filter { it.disposition != CatalogDisposition.PROBE }
            .map { it.namespace }
            .toSet()
        assertTrue(actualByGroup.values.flatten().none { it.namespace in nonProbeNamespaces })
    }

    @Test
    fun `생성 source는 공통 UI 상태와 사용자 gesture 증거 상태를 노출한다`() = withWorkspace { workspace ->
        val source = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output")).first().sourceRoot
        val html = source.resolve("probe.html").readText()
        val ui = source.resolve("probe-ui.js").readText()
        val runtime = source.resolve("probe-runtime.js").readText()

        listOf("전체", "현재", "OK", "제한", "없음", "실패", "미실행", "실행 context", "증거 전송").forEach {
            assertTrue(it in html || it in ui, "UI에 '$it' 상태가 표시되어야 합니다.")
        }
        assertTrue("gesture" in html)
        assertTrue("textContent" in ui)
        assertTrue("export async function runCase" in runtime)
        assertTrue("startedAt" in runtime)
        assertTrue("VERIFIED" in runtime)
    }

    @Test
    fun `runtime은 presence only member를 호출하지 않고 mode별 상태를 정규화한다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))
        val source = generated.first().sourceRoot
        val runtimeModule = workspace.resolve("probe-runtime.mjs")
        Files.copy(source.resolve("probe-runtime.js"), runtimeModule)
        val descriptorUris = generated.joinToString(",") {
            "'${it.sourceRoot.resolve("probe-cases.json").toUri()}'"
        }

        runNodeModule(
            workspace,
            """
            import { readFileSync } from 'node:fs';
            import { runCase } from '${runtimeModule.toUri()}';
            const cases = [$descriptorUris].flatMap((uri) => JSON.parse(readFileSync(new URL(uri), 'utf8')).cases);
            const presenceCase = cases.find((testCase) => testCase.mode === 'presence_only');
            const safeCase = cases.find((testCase) => testCase.mode === 'safe_call');
            const cleanupCase = cases.find((testCase) => testCase.mode === 'safe_call_with_cleanup');
            if (!presenceCase || !safeCase || !cleanupCase) throw new Error('canonical mode fixture missing');
            let calls = 0;
            const absent = await runCase({ ...presenceCase, resolve: () => undefined, run: () => { calls += 100; } });
            const exposed = await runCase({ ...presenceCase, resolve: () => () => { calls += 10; }, run: () => { calls += 100; } });
            const verified = await runCase({ ...safeCase, resolve: () => (() => {}), run: async () => { calls += 1; return 'ok'; } });
            const cleaned = await runCase({ ...cleanupCase, resolve: () => ({}), run: async () => { calls += 1; return 'clean'; } });
            const restricted = await runCase({ ...safeCase, resolve: () => ({}), run: async () => { throw new Error('permission denied'); } });
            const uppercase = await runCase({ ...presenceCase, mode: 'PRESENCE_ONLY', resolve: () => ({}), run: () => { calls += 100; } });
            const unknown = await runCase({ ...presenceCase, mode: 'unknown', resolve: () => ({}), run: () => { calls += 100; } });
            if (absent.status !== 'ABSENT') throw new Error(`absent: ${'$'}{absent.status}`);
            if (exposed.status !== 'EXPOSED') throw new Error(`exposed: ${'$'}{exposed.status}`);
            if (verified.status !== 'VERIFIED') throw new Error(`verified: ${'$'}{verified.status}`);
            if (cleaned.status !== 'VERIFIED') throw new Error(`cleaned: ${'$'}{cleaned.status}`);
            if (restricted.status !== 'RESTRICTED') throw new Error(`restricted: ${'$'}{restricted.status}`);
            if (uppercase.status !== 'FAILED') throw new Error(`uppercase: ${'$'}{uppercase.status}`);
            if (unknown.status !== 'FAILED') throw new Error(`unknown: ${'$'}{unknown.status}`);
            if (calls !== 2) throw new Error(`unexpected calls: ${'$'}{calls}`);
            """.trimIndent(),
        )
    }

    @Test
    fun `UI와 evidence 상태는 exposed를 OK에 포함하고 나머지를 각 버킷으로 집계한다`() = withWorkspace { workspace ->
        val source = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output")).first().sourceRoot
        val uiModule = workspace.resolve("probe-ui.mjs")
        Files.copy(source.resolve("probe-ui.js"), uiModule)

        runNodeModule(
            workspace,
            """
            import { summaryBuckets } from '${uiModule.toUri()}';
            const buckets = summaryBuckets({ VERIFIED: 2, EXPOSED: 3, RESTRICTED: 4, ABSENT: 5, FAILED: 6, NOT_RUN: 7 });
            const expected = { ok: 5, restricted: 4, absent: 5, failed: 6, notRun: 7 };
            if (JSON.stringify(buckets) !== JSON.stringify(expected)) {
              throw new Error(JSON.stringify(buckets));
            }
            """.trimIndent(),
        )
    }

    @Test
    fun `생성 source는 unsafe JavaScript와 remote script를 포함하지 않는다`() = withWorkspace { workspace ->
        val generated = ProbeSourceGenerator().generate(loadCatalog(), workspace.resolve("output"))

        generated.forEach { probe ->
            val textSources = Files.walk(probe.sourceRoot).use { paths ->
                paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .filter { it.name.endsWith(".html") || it.name.endsWith(".js") }
                    .map { it.readText() }
                    .toList()
            }
            textSources.forEach { source ->
                assertFalse("innerHTML" in source)
                assertFalse(Regex("\\beval\\s*\\(").containsMatchIn(source))
                assertFalse(Regex("\\bnew\\s+Function\\b").containsMatchIn(source))
                assertFalse(Regex("<(?:script|link)[^>]+(?:src|href)\\s*=\\s*[\"']https?://", RegexOption.IGNORE_CASE).containsMatchIn(source))
                assertFalse(Regex("\\bimport\\s*\\(\\s*[\"']https?://").containsMatchIn(source))
            }
        }
    }

    @Test
    fun `output root symlink와 예상 밖 파일을 거부하고 기존 bytes를 보존한다`() = withWorkspace { workspace ->
        val catalog = loadCatalog()
        val outside = workspace.resolve("outside").also(Files::createDirectory)
        val linkedOutput = workspace.resolve("linked-output")
        linkedOutput.createSymbolicLinkPointingTo(outside)

        assertFailsWith<IllegalArgumentException> {
            ProbeSourceGenerator().generate(catalog, linkedOutput)
        }
        assertTrue(linkedOutput.isSymbolicLink())
        assertEquals(emptyList(), Files.list(outside).use { it.toList() })

        val occupiedOutput = workspace.resolve("occupied").also(Files::createDirectory)
        val sentinel = occupiedOutput.resolve("keep.txt")
        Files.writeString(sentinel, "user-owned", UTF_8)
        assertFailsWith<IllegalArgumentException> {
            ProbeSourceGenerator().generate(catalog, occupiedOutput)
        }
        assertEquals("user-owned", sentinel.readText())
        assertTrue(Files.list(workspace).use { entries ->
            entries.noneMatch { it.name.contains(".staging-") }
        })
    }

    @Test
    fun `빈 output root도 기존 경로로 거부하고 삭제하지 않는다`() = withWorkspace { workspace ->
        val output = workspace.resolve("existing-empty").also(Files::createDirectory)

        assertFailsWith<IllegalArgumentException> {
            ProbeSourceGenerator().generate(loadCatalog(), output)
        }

        assertTrue(Files.isDirectory(output, NOFOLLOW_LINKS))
        assertTrue(Files.list(output).use { it.findAny().isEmpty })
    }

    @Test
    fun `asset 누락 실패는 target을 만들지 않고 staging을 정리한다`() = withWorkspace { workspace ->
        val output = workspace.resolve("asset-failure")
        val generator = ProbeSourceGenerator(
            assetLoader = { name ->
                if (name == "probe/probe.css") error("missing asset") else bundledAsset(name)
            },
        )

        assertFailsWith<IllegalStateException> {
            generator.generate(loadCatalog(), output)
        }

        assertFalse(Files.exists(output, NOFOLLOW_LINKS))
        assertNoStaging(workspace, output.fileName.toString())
    }

    @Test
    fun `atomic move 실패는 target과 sibling을 변경하지 않고 staging을 정리한다`() = withWorkspace { workspace ->
        val output = workspace.resolve("move-failure")
        val sibling = workspace.resolve("keep.txt")
        Files.writeString(sibling, "keep", UTF_8)
        val generator = ProbeSourceGenerator(
            directoryMover = { _, _ -> error("atomic move failed") },
        )

        assertFailsWith<IllegalStateException> {
            generator.generate(loadCatalog(), output)
        }

        assertFalse(Files.exists(output, NOFOLLOW_LINKS))
        assertEquals("keep", sibling.readText())
        assertNoStaging(workspace, output.fileName.toString())
    }

    @Test
    fun `서로 다른 catalog digest는 서로 다른 source tree를 만든다`() = withWorkspace { workspace ->
        val catalog = loadCatalog()
        val first = ProbeSourceGenerator().generate(catalog, workspace.resolve("first"))
        val alternateCatalog = ApiProbeCatalog.load(
            catalog.canonicalJson.replace("alarms-get-worker", "alarms-get-worker-alt")
                .byteInputStream(),
        )
        val second = ProbeSourceGenerator().generate(alternateCatalog, workspace.resolve("second"))

        assertNotEquals(treeDigest(first), treeDigest(second))
    }

    private fun loadCatalog(): ApiProbeCatalog = ApiProbeCatalog.load(
        checkNotNull(javaClass.getResourceAsStream("/catalog/sbrowser-30-public-apis.json")),
    )

    private fun bundledAsset(name: String): ByteArray = checkNotNull(
        javaClass.classLoader.getResourceAsStream(name),
    ).use { it.readBytes() }

    private fun treeDigest(generated: List<GeneratedProbe>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        generated.sortedBy { it.groupId }.forEach { probe ->
            Files.walk(probe.sourceRoot).use { paths ->
                paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
                    .sorted { left, right ->
                        probe.sourceRoot.relativize(left).toString()
                            .compareTo(probe.sourceRoot.relativize(right).toString())
                    }
                    .forEach { path ->
                        digest.update(probe.groupId.toByteArray(UTF_8))
                        digest.update(0)
                        digest.update(probe.sourceRoot.relativize(path).toString().toByteArray(UTF_8))
                        digest.update(0)
                        digest.update(path.readBytes())
                    }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256")
        .digest(path.readBytes())
        .joinToString("") { "%02x".format(it) }

    private fun JsonArray.strings(): List<String> = map { it.jsonPrimitive.content }

    private fun assertNoStaging(parent: Path, outputName: String) {
        assertTrue(Files.list(parent).use { entries ->
            entries.noneMatch { it.name.startsWith(".$outputName.staging-") }
        })
    }

    private fun runNodeModule(workspace: Path, source: String) {
        val runner = workspace.resolve("runner-${System.nanoTime()}.mjs")
        Files.writeString(runner, source, UTF_8)
        val process = ProcessBuilder("node", runner.toString())
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            throw AssertionError("Node runtime contract test가 timeout되었습니다.")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.exitValue(), output)
    }

    private fun assertCanonicalObjectOrder(value: JsonObject) {
        assertEquals(value.keys.sorted(), value.keys.toList())
        value.values.forEach { child ->
            when (child) {
                is JsonObject -> assertCanonicalObjectOrder(child)
                is JsonArray -> child.forEach {
                    if (it is JsonObject) assertCanonicalObjectOrder(it)
                }
                else -> Unit
            }
        }
    }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspace = Files.createTempDirectory("six-probe-generator-test")
        try {
            block(workspace)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private companion object {
        val EXPECTED_GROUPS = setOf(
            "base-runtime",
            "tab-page",
            "network",
            "user-interface",
            "advanced-runtime",
            "identity-privacy",
        )
    }

    private data class Descriptor(
        val namespace: String,
        val member: String,
        val context: String,
        val mode: String,
        val expectedOutcome: String,
        val permissions: List<String>,
        val manifestKeys: List<String>,
        val cleanupMember: String?,
        val cleanupTarget: String?,
        val cleanupFixtureId: String?,
        val id: String,
    ) {
        fun cleanup(): DescriptorCleanup = DescriptorCleanup(
            cleanupMember,
            cleanupTarget,
            cleanupFixtureId,
        )
    }

    private data class DescriptorCleanup(
        val member: String?,
        val target: String?,
        val fixtureId: String?,
    )
}
