package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopSelectionEventBridgeTest {
    @Test
    fun `injects the generated shim only before the evidenced content script`() {
        val source = extension(
            contentName = "renamed-handler.mjs",
            content = "document.addEventListener(\"mouseup\", (event) => use(getSelection()));",
        )
        val contentBefore = source.resolve("renamed-handler.mjs").readBytes()

        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            val working = snapshot.workingRoot
            val selection = automaticSelection(working)
            val applied = workspace.transform(
                snapshot,
                profile,
                conversionPlanFor(snapshot.originalRoot, profile),
            ).single()

            assertTrue(applied.applied)
            val manifest = Json.parseToJsonElement(working.resolve("manifest.json").readText()) as JsonObject
            val blocks = manifest["content_scripts"] as JsonArray
            assertEquals(
                listOf("sixbridge/desktop-selection-event-bridge.v1.js", "renamed-handler.mjs"),
                scriptPaths(blocks[0] as JsonObject),
            )
            assertEquals(listOf("unrelated.js"), scriptPaths(blocks[1] as JsonObject))
            assertContentEquals(contentBefore, source.resolve("renamed-handler.mjs").readBytes())
            assertContentEquals(contentBefore, snapshot.originalRoot.resolve("renamed-handler.mjs").readBytes())
            assertContentEquals(contentBefore, working.resolve("renamed-handler.mjs").readBytes())
            assertTrue(
                Files.isRegularFile(
                    working.resolve("sixbridge/desktop-selection-event-bridge.v1.js"),
                ),
            )
            assertFalse(
                Files.exists(
                    working.resolve("_sixbridge/desktop-selection-event-bridge.v1.js"),
                ),
            )
        }
    }

    @Test
    fun `planner no-op after conversion is byte for byte stable`() {
        val source = extension(
            content = "document.addEventListener(\"mouseup\", (event) => use(getSelection()));",
        )
        val bridge = DesktopSelectionEventBridge()
        val selection = automaticSelection(source)

        applyAdapterToSource(source, profile, bridge, selection)
        val manifestBefore = source.resolve("manifest.json").readBytes()
        val zipBefore = ExtensionZip.createBytes(source)
        val reapplied = applyAdapterToSource(source, profile, bridge, selection)

        assertFalse(reapplied.applied)
        assertContentEquals(manifestBefore, source.resolve("manifest.json").readBytes())
        assertContentEquals(zipBefore, ExtensionZip.createBytes(source))
    }

    @Test
    fun `injection does not depend on extension identity or content filename`() {
        listOf(
            Triple("First extension", "aWQtb25l", "one.js"),
            Triple("Unrelated extension", "aWQtdHdv", "renamed-handler.mjs"),
        ).forEach { (name, manifestKey, contentName) ->
            val source = extension(
                name = name,
                manifestKey = manifestKey,
                contentName = contentName,
                content = "document.addEventListener(\"mouseup\", (event) => use(getSelection()));",
            )

            val applied = applyAdapterToSource(
                source,
                profile,
                DesktopSelectionEventBridge(),
                automaticSelection(source),
            )

            assertTrue(applied.applied)
            val manifest = Json.parseToJsonElement(source.resolve("manifest.json").readText()) as JsonObject
            val scripts = scriptPaths((manifest["content_scripts"] as JsonArray)[0] as JsonObject)
            assertEquals("sixbridge/desktop-selection-event-bridge.v1.js", scripts.first())
            assertEquals(contentName, scripts[1])
        }
    }

    @Test
    fun `does not apply unavailable or undetected selection decisions`() {
        val sensitive = extension(
            content = """
                document.addEventListener("mouseup", (event) => {
                  if (event.isTrusted) use(getSelection());
                });
            """.trimIndent(),
        )
        val mobileAware = extension(
            content = """
                document.addEventListener("mouseup", (event) => use(getSelection()));
                document.addEventListener("pointerup", () => use(getSelection()));
            """.trimIndent(),
        )
        val unavailableReport = MobilePatternAnalyzer().analyze(sensitive, profile)
        val unavailable = unavailableReport.adapterDecisions.single()
        assertEquals(AdapterDisposition.UNAVAILABLE, unavailable.disposition)
        val planningError = assertFailsWith<ConversionPlanningException> {
            ConversionPlanner().plan(unavailableReport, emptySet())
        }
        assertEquals(
            ConversionPlanningFailure.NO_APPLICABLE_ADAPTER,
            planningError.failure,
        )
        assertFalse(Files.exists(sensitive.resolve("sixbridge")))

        assertNull(
            MobilePatternAnalyzer().analyze(mobileAware, profile)
                .adapterDecisions.singleOrNull(),
        )
        assertFalse(Files.exists(mobileAware.resolve("sixbridge")))
    }

    @Test
    fun `injects into the manifest block that reaches direct or transitive evidence`() {
        val cases = listOf(
            listOf("./content.js") to mapOf(
                "content.js" to "document.addEventListener(\"mouseup\", (event) => use(getSelection()));",
            ),
            listOf("/content.js") to mapOf(
                "content.js" to "document.addEventListener(\"mouseup\", (event) => use(getSelection()));",
            ),
            listOf("bootstrap.js") to mapOf(
                "bootstrap.js" to "import \"./listener.js\";",
                "listener.js" to "document.addEventListener(\"mouseup\", (event) => use(getSelection()));",
            ),
        )
        cases.forEach { (scripts, files) ->
            val source = extensionWithBlocks(
                blocks = listOf(scripts, listOf("unrelated.js")),
                files = files + ("unrelated.js" to "document.addEventListener('click', () => {});"),
            )
            val report = MobilePatternAnalyzer().analyze(source, profile)
            val decision = report.adapterDecisions.singleOrNull()
            assertNotNull(decision, "$scripts ${report.status} ${report.issues}")
            val selection = ConversionPlanner().plan(report, emptySet())
                .selectedAdapters.single()
            val applied = applyAdapterToSource(
                source,
                profile,
                DesktopSelectionEventBridge(),
                selection,
            )
            val manifest = Json.parseToJsonElement(source.resolve("manifest.json").readText()) as JsonObject

            assertTrue(applied.applied)
            assertEquals(
                listOf("sixbridge/desktop-selection-event-bridge.v1.js") + scripts,
                scriptPaths((manifest["content_scripts"] as JsonArray)[0] as JsonObject),
            )
            assertEquals(
                listOf("unrelated.js"),
                scriptPaths((manifest["content_scripts"] as JsonArray)[1] as JsonObject),
            )
        }
    }

    @Test
    fun `planner does not reenter an already generated shim block`() {
        val source = extension(
            content = "document.addEventListener(\"mouseup\", (event) => use(getSelection()));",
        )
        val selection = automaticSelection(source)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Synthetic","version":"1","content_scripts":[
              {"matches":["<all_urls>"],"js":["content.js","sixbridge/desktop-selection-event-bridge.v1.js","content.js","sixbridge/desktop-selection-event-bridge.v1.js"]},
              {"matches":["<all_urls>"],"js":["unrelated.js"]}
            ]}""",
        )
        val asset = source.resolve("sixbridge/desktop-selection-event-bridge.v1.js")
        Files.createDirectories(asset.parent)
        asset.writeBytes(
            requireNotNull(
                javaClass.classLoader.getResourceAsStream(
                    "dev/sixbridge/adapters/desktop-selection-event-bridge.v1.js",
                ),
            ).readBytes(),
        )

        applyAdapterToSource(
            source,
            profile,
            DesktopSelectionEventBridge(),
            selection,
        )

        val manifest = Json.parseToJsonElement(source.resolve("manifest.json").readText()) as JsonObject
        assertEquals(
            listOf(
                "content.js",
                "sixbridge/desktop-selection-event-bridge.v1.js",
                "content.js",
                "sixbridge/desktop-selection-event-bridge.v1.js",
            ),
            scriptPaths((manifest["content_scripts"] as JsonArray)[0] as JsonObject),
        )
    }

    @Test
    fun `planner leaves generated shim aliases unchanged without a decision`() {
        val source = extension(
            content = "document.addEventListener(\"mouseup\", (event) => use(getSelection()));",
        )
        val selection = automaticSelection(source)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Synthetic","version":"1","content_scripts":[
              {"matches":["<all_urls>"],"js":["content.js","./sixbridge/desktop-selection-event-bridge.v1.js","/sixbridge/desktop-selection-event-bridge.v1.js","sixbridge/desktop-selection-event-bridge.v1.js"]},
              {"matches":["<all_urls>"],"js":["unrelated.js"]}
            ]}""",
        )
        writeGeneratedAsset(source)

        applyAdapterToSource(
            source,
            profile,
            DesktopSelectionEventBridge(),
            selection,
        )

        val manifest = Json.parseToJsonElement(source.resolve("manifest.json").readText()) as JsonObject
        assertEquals(
            listOf(
                "content.js",
                "./sixbridge/desktop-selection-event-bridge.v1.js",
                "/sixbridge/desktop-selection-event-bridge.v1.js",
                "sixbridge/desktop-selection-event-bridge.v1.js",
            ),
            scriptPaths((manifest["content_scripts"] as JsonArray)[0] as JsonObject),
        )
    }

    @Test
    fun `rejects a conflicting generated asset without changing manifest or asset`() {
        val source = extension(
            content = "document.addEventListener(\"mouseup\", (event) => use(getSelection()));",
        )
        val manifestBefore = source.resolve("manifest.json").readBytes()
        val asset = source.resolve("sixbridge/desktop-selection-event-bridge.v1.js")
        Files.createDirectories(asset.parent)
        asset.writeText("different adapter bytes")
        val assetBefore = asset.readBytes()

        assertFailsWith<IllegalStateException> {
            applyAdapterToSource(
                source,
                profile,
                DesktopSelectionEventBridge(),
                automaticSelection(source),
            )
        }

        assertContentEquals(manifestBefore, source.resolve("manifest.json").readBytes())
        assertContentEquals(assetBefore, asset.readBytes())
    }

    @Test
    fun `rejects generated asset directory or symlink before changing the manifest`() {
        listOf<(Path) -> Unit>(
            { asset ->
                Files.createDirectory(asset)
                asset.resolve("foreign.txt").writeText("foreign directory")
            },
            { asset ->
                val target = Files.createTempFile("six-selection-bridge", ".js")
                asset.createSymbolicLinkPointingTo(target)
            },
        ).forEachIndexed { index, createCollision ->
            val source = extension(
                content = "document.addEventListener(\"mouseup\", (event) => use(getSelection()));",
            )
            val manifestBefore = source.resolve("manifest.json").readBytes()
            val asset = source.resolve("sixbridge/desktop-selection-event-bridge.v1.js")
            Files.createDirectories(asset.parent)
            createCollision(asset)

            assertFails("collision $index") {
                applyAdapterToSource(
                    source,
                    profile,
                    DesktopSelectionEventBridge(),
                    automaticSelection(source),
                )
            }

            assertContentEquals(
                manifestBefore,
                source.resolve("manifest.json").readBytes(),
                "collision $index",
            )
            if (index == 0) {
                assertTrue(Files.isDirectory(asset), "collision $index")
            } else {
                assertTrue(Files.isSymbolicLink(asset), "collision $index")
            }
        }
    }

    @Test
    fun `rejects a generated asset parent file before changing the manifest`() {
        val source = extension(
            content = "document.addEventListener(\"mouseup\", (event) => use(getSelection()));",
        )
        val manifestBefore = source.resolve("manifest.json").readBytes()
        val parent = source.resolve("sixbridge")
        parent.writeText("not a directory")
        val parentBefore = parent.readBytes()

        val error = runCatching {
            applyAdapterToSource(
                source,
                profile,
                DesktopSelectionEventBridge(),
                automaticSelection(source),
            )
        }.exceptionOrNull()

        assertContentEquals(manifestBefore, source.resolve("manifest.json").readBytes())
        assertContentEquals(parentBefore, parent.readBytes())
        assertTrue(error is IllegalStateException)
    }

    private fun automaticSelection(source: Path): SelectedAdapter {
        val report = MobilePatternAnalyzer().analyze(source, profile)
        val decision = report
            .adapterDecisions.singleOrNull()
        assertNotNull(decision)
        assertEquals(AdapterDisposition.AUTOMATIC, decision.disposition)
        return ConversionPlanner().plan(report, emptySet()).selectedAdapters.single()
    }

    private fun scriptPaths(block: JsonObject): List<String> =
        (block["js"] as JsonArray).map { (it as JsonPrimitive).content }

    private fun writeGeneratedAsset(root: Path) {
        val asset = root.resolve("sixbridge/desktop-selection-event-bridge.v1.js")
        Files.createDirectories(asset.parent)
        asset.writeBytes(
            requireNotNull(
                javaClass.classLoader.getResourceAsStream(
                    "dev/sixbridge/adapters/desktop-selection-event-bridge.v1.js",
                ),
            ).use { it.readBytes() },
        )
    }

    private fun extension(
        name: String = "Unrelated extension name",
        manifestKey: String? = null,
        contentName: String = "content.js",
        content: String,
    ): Path {
        val root = Files.createTempDirectory("six-selection-bridge")
        val keyMember = manifestKey?.let { ",\"key\":\"$it\"" }.orEmpty()
        root.resolve("manifest.json").writeText(
            """
            {"manifest_version":3,"name":"$name","version":"1"$keyMember,"content_scripts":[
              {"matches":["<all_urls>"],"js":["$contentName"]},
              {"matches":["<all_urls>"],"js":["unrelated.js"]}
            ]}
            """.trimIndent(),
        )
        root.resolve(contentName).writeText(content)
        root.resolve("unrelated.js").writeText("document.addEventListener('click', () => {});")
        return root
    }

    private fun extensionWithBlocks(
        blocks: List<List<String>>,
        files: Map<String, String>,
    ): Path {
        val root = Files.createTempDirectory("six-selection-bridge-provenance")
        val contentScripts = blocks.joinToString(",") { scripts ->
            """{"matches":["<all_urls>"],"js":[${scripts.joinToString(",") { "\"$it\"" }}]}"""
        }
        root.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Synthetic","version":"1","content_scripts":[$contentScripts]}""",
        )
        files.forEach { (path, source) ->
            val output = root.resolve(path)
            Files.createDirectories(output.parent)
            output.writeText(source)
        }
        return root
    }

    private val profile = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = setOf("action", "runtime", "storage", "tabs"),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    )
}
