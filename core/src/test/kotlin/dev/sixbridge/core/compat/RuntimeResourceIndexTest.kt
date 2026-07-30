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
