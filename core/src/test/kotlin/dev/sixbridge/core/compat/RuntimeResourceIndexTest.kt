package dev.sixbridge.core.compat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeResourceIndexTest {
    @Test
    fun `MV3는 action popup만 popup 역할로 색인한다`() {
        val root = Files.createTempDirectory("sixbridge-index-mv3-popup-")
        root.resolve("mv3.html").writeText("<head><script src='mv3.js'></script></head>")
        root.resolve("mv3.js").writeText("console.log('mv3');")
        root.resolve("mv2.html").writeText("<head></head>")
        val manifest = Json.parseToJsonElement(
            """
            {
              "manifest_version": 3,
              "name": "Popup",
              "version": "1",
              "action": {"default_popup": "mv3.html"},
              "browser_action": {"default_popup": "mv2.html"}
            }
            """.trimIndent(),
        ) as JsonObject

        val index = RuntimeResourceIndex.create(root, manifest)

        assertEquals("mv3.html", ManifestPopupResolver.resolve(manifest))
        assertEquals(
            setOf(RuntimeResourceIndex.ResourceRole.ACTION_POPUP),
            index.rolesByRelativePath.getValue("mv3.html"),
        )
        assertEquals(
            setOf(RuntimeResourceIndex.ResourceRole.ACTION_POPUP),
            index.rolesByRelativePath.getValue("mv3.js"),
        )
        assertFalse("mv2.html" in index.rolesByRelativePath)
    }

    @Test
    fun `MV2는 browser action popup만 popup 역할로 색인한다`() {
        val root = Files.createTempDirectory("sixbridge-index-mv2-popup-")
        root.resolve("mv2.html").writeText("<head></head>")
        root.resolve("mv3.html").writeText("<head></head>")
        val manifest = Json.parseToJsonElement(
            """
            {
              "manifest_version": 2,
              "name": "Popup",
              "version": "1",
              "action": {"default_popup": "mv3.html"},
              "browser_action": {"default_popup": "mv2.html"}
            }
            """.trimIndent(),
        ) as JsonObject

        val index = RuntimeResourceIndex.create(root, manifest)

        assertEquals("mv2.html", ManifestPopupResolver.resolve(manifest))
        assertEquals(
            setOf(RuntimeResourceIndex.ResourceRole.ACTION_POPUP),
            index.rolesByRelativePath.getValue("mv2.html"),
        )
        assertFalse("mv3.html" in index.rolesByRelativePath)
    }

    @Test
    fun `marks declared and imported resources unresolved when resolution fails`() {
        val missingRoot = Files.createTempDirectory("sixbridge-index-missing-root-")
        val missingRootIndex = RuntimeResourceIndex.create(
            missingRoot,
            manifest("""{"service_worker":"missing.js"}"""),
        )
        assertTrue(missingRootIndex.unresolvedDependency)

        val invalidImports = listOf(
            """importScripts("./missing.js");""",
            """import "../outside.js";""",
            """importScripts("%2e%2e/outside.js");""",
            """importScripts("dir\\outside.js");""",
            """importScripts("https://example.test/remote.js");""",
        )
        invalidImports.forEach { source ->
            val root = workerExtension(source)
            val index = RuntimeResourceIndex.create(
                root,
                manifest("""{"service_worker":"background.js"}"""),
            )

            assertTrue(index.unresolvedDependency, source)
        }
    }

    @Test
    fun `records a deterministic complete runtime dependency graph`() {
        val root = workerExtension(
            """importScripts("./helper.js");""",
        )
        root.resolve("helper.js").writeText(
            """importScripts("./nested.js");""",
        )
        root.resolve("nested.js").writeText("console.log('nested');")

        val index = RuntimeResourceIndex.create(
            root,
            manifest("""{"service_worker":"background.js"}"""),
        )

        assertFalse(index.unresolvedDependency)
        assertEquals(
            setOf(
                RuntimeResourceIndex.RuntimeDependencyEdge(
                    "background.js",
                    "helper.js",
                ),
                RuntimeResourceIndex.RuntimeDependencyEdge(
                    "helper.js",
                    "nested.js",
                ),
            ),
            index.dependencyEdges,
        )
        assertEquals(
            setOf(RuntimeResourceIndex.ResourceRole.BACKGROUND),
            index.rolesByRelativePath.getValue("nested.js"),
        )
    }

    @Test
    fun `고정 dynamic import는 runtime dependency graph에 포함한다`() {
        val fixtures = listOf(
            """import("./helper.js");""",
            """import(chrome.runtime.getURL("./helper.js"));""",
            """import(globalThis.chrome.runtime.getURL("./helper.js"));""",
        )

        fixtures.forEach { source ->
            val root = workerExtension(source)
            root.resolve("helper.js").writeText("console.log('helper');")

            val index = RuntimeResourceIndex.create(
                root,
                manifest("""{"service_worker":"background.js"}"""),
            )

            assertFalse(index.unresolvedDependency, source)
            assertEquals(
                setOf(RuntimeResourceIndex.RuntimeDependencyEdge("background.js", "helper.js")),
                index.dependencyEdges,
                source,
            )
        }
    }

    @Test
    fun `계산된 dynamic import는 계속 unresolved로 차단한다`() {
        val fixtures = listOf(
            """const path = "./helper.js"; import(path);""",
            """const path = "./helper.js"; import(chrome.runtime.getURL(path));""",
            """import(chrome.runtime.getURL("./helper.js") + "?v=1");""",
        )

        fixtures.forEach { source ->
            val root = workerExtension(source)
            root.resolve("helper.js").writeText("console.log('helper');")

            val index = RuntimeResourceIndex.create(
                root,
                manifest("""{"service_worker":"background.js"}"""),
            )

            assertTrue(index.unresolvedDependency, source)
            assertTrue(index.dependencyEdges.isEmpty(), source)
        }
    }

    @Test
    fun `marks runtime closure unresolved when a declared resource read fails`() {
        val root = workerExtension(
            """importScripts("./helper.js");""",
        )
        root.resolve("helper.js").writeText("console.log('helper');")

        val index = RuntimeResourceIndex.createForTesting(
            root = root,
            manifest = manifest("""{"service_worker":"background.js"}"""),
            readSource = { path ->
                if (path.fileName.toString() == "helper.js") {
                    throw java.io.IOException("injected read failure")
                }
                java.nio.file.Files.readString(path)
            },
        )

        assertTrue(index.unresolvedDependency)
    }

    @Test
    fun `100MiB JSON dependency는 source reader 전에 공유 예산으로 거부한다`() {
        val root = workerExtension(
            """import data from "./large.json" with { type: "json" };""",
        )
        val large = root.resolve("large.json")
        java.nio.channels.FileChannel.open(
            large,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.WRITE,
        ).use {
            it.position(100L * 1024 * 1024 - 1)
            it.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)))
        }
        val reads = mutableListOf<String>()

        val error = assertFailsWith<AnalysisBudgetExceededException> {
            RuntimeResourceIndex.createForTesting(
                root = root,
                manifest = manifest("""{"service_worker":"background.js"}"""),
                readSource = { path ->
                    reads += path.fileName.toString()
                    Files.readString(path)
                },
                control = AnalysisControl(AnalysisSafetyPolicy.mobile),
            )
        }

        assertEquals("analysis_budget_exceeded", error.code)
        assertEquals(listOf("background.js"), reads)
    }

    @Test
    fun `작은 비실행 dependency는 String으로 읽지 않고 unresolved 처리한다`() {
        val root = workerExtension("""import "./payload.txt";""")
        root.resolve("payload.txt").writeText("opaque payload")
        val reads = mutableListOf<String>()

        val index = RuntimeResourceIndex.createForTesting(
            root = root,
            manifest = manifest("""{"service_worker":"background.js"}"""),
            readSource = { path ->
                reads += path.fileName.toString()
                Files.readString(path)
            },
            control = AnalysisControl(AnalysisSafetyPolicy.mobile),
        )

        assertEquals(listOf("background.js"), reads)
        assertTrue(index.unresolvedDependency)
    }

    @Test
    fun `runtime dependency 순회는 파일 사이 취소를 전달한다`() {
        val root = workerExtension("""importScripts("./b.js");""")
        root.resolve("b.js").writeText("console.log('b');")
        var checkpoints = 0
        val control = AnalysisControl(AnalysisSafetyPolicy.mobile) {
            checkpoints += 1
            if (checkpoints == 3) throw CancellationException("cancel")
        }

        assertFailsWith<CancellationException> {
            RuntimeResourceIndex.create(
                root,
                manifest("""{"service_worker":"background.js"}"""),
                control,
            )
        }
    }

    @Test
    fun `runtime dependency 순회는 마지막 resource 처리 뒤 취소를 전달한다`() {
        val root = workerExtension("""importScripts("./b.js");""")
        root.resolve("b.js").writeText("console.log('b');")
        var checkpoints = 0
        val control = AnalysisControl(AnalysisSafetyPolicy.mobile) {
            checkpoints += 1
            if (checkpoints == 8) throw CancellationException("cancel")
        }

        assertFailsWith<CancellationException> {
            RuntimeResourceIndex.create(
                root,
                manifest("""{"service_worker":"background.js"}"""),
                control,
            )
        }
    }

    private fun workerExtension(source: String): Path =
        Files.createTempDirectory("sixbridge-index-").also { root ->
            root.resolve("background.js").writeText(source)
        }

    private fun manifest(background: String): JsonObject =
        Json.parseToJsonElement(
            """{"manifest_version":3,"name":"Index","version":"1","background":$background}""",
        ) as JsonObject
}
