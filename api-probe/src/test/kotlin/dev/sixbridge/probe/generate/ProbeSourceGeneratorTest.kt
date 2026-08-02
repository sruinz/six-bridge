package dev.sixbridge.probe.generate

import dev.sixbridge.probe.catalog.ApiProbeCatalog
import dev.sixbridge.probe.catalog.CatalogDisposition
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
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
            .mapValues { (_, entries) -> entries.flatMap { it.cases }.map { it.id }.sorted() }

        val actualByGroup = generated.associate { probe ->
            val descriptor = Json.parseToJsonElement(
                probe.sourceRoot.resolve("probe-cases.json").readText(),
            ).jsonObject
            assertEquals(1, descriptor.getValue("schemaVersion").jsonPrimitive.content.toInt())
            assertEquals(probe.groupId, descriptor.getValue("groupId").jsonPrimitive.content)
            assertEquals(catalog.sha256, descriptor.getValue("catalogSha256").jsonPrimitive.content)
            assertCanonicalObjectOrder(descriptor)
            probe.groupId to descriptor.getValue("cases").jsonArray.map {
                it.jsonObject.getValue("id").jsonPrimitive.content
            }
        }

        assertEquals(expectedByGroup, actualByGroup)
        assertEquals(catalog.probeCaseIds, actualByGroup.values.flatten().toSet())
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
}
