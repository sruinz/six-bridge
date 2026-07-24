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
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
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
            val decision = automaticDecision(working)
            val applied = DesktopSelectionEventBridge().apply(working, decision)

            assertTrue(applied.applied)
            val manifest = Json.parseToJsonElement(working.resolve("manifest.json").readText()) as JsonObject
            val blocks = manifest["content_scripts"] as JsonArray
            assertEquals(
                listOf("_sixbridge/desktop-selection-event-bridge.v1.js", "renamed-handler.mjs"),
                scriptPaths(blocks[0] as JsonObject),
            )
            assertEquals(listOf("unrelated.js"), scriptPaths(blocks[1] as JsonObject))
            assertContentEquals(contentBefore, source.resolve("renamed-handler.mjs").readBytes())
            assertContentEquals(contentBefore, snapshot.originalRoot.resolve("renamed-handler.mjs").readBytes())
            assertContentEquals(contentBefore, working.resolve("renamed-handler.mjs").readBytes())
            assertTrue(
                Files.isRegularFile(
                    working.resolve("_sixbridge/desktop-selection-event-bridge.v1.js"),
                ),
            )
        }
    }

    @Test
    fun `reapplying is a byte for byte no op including the packed zip`() {
        val source = extension(
            content = "document.addEventListener(\"mouseup\", (event) => use(getSelection()));",
        )
        val bridge = DesktopSelectionEventBridge()
        val decision = automaticDecision(source)

        bridge.apply(source, decision)
        val manifestBefore = source.resolve("manifest.json").readBytes()
        val zipBefore = ExtensionZip.createBytes(source)
        val reapplied = bridge.apply(source, decision)

        assertTrue(reapplied.applied)
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

            val applied = DesktopSelectionEventBridge().apply(source, automaticDecision(source))

            assertTrue(applied.applied)
            val manifest = Json.parseToJsonElement(source.resolve("manifest.json").readText()) as JsonObject
            val scripts = scriptPaths((manifest["content_scripts"] as JsonArray)[0] as JsonObject)
            assertEquals("_sixbridge/desktop-selection-event-bridge.v1.js", scripts.first())
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
        val bridge = DesktopSelectionEventBridge()

        val unavailable = MobilePatternAnalyzer().analyze(sensitive, profile)
            .adapterDecisions.single()
        assertEquals(AdapterDisposition.UNAVAILABLE, unavailable.disposition)
        assertFalse(bridge.apply(sensitive, unavailable).applied)
        assertFalse(Files.exists(sensitive.resolve("_sixbridge")))

        assertNull(
            MobilePatternAnalyzer().analyze(mobileAware, profile)
                .adapterDecisions.singleOrNull(),
        )
        assertFalse(Files.exists(mobileAware.resolve("_sixbridge")))
    }

    private fun automaticDecision(source: Path): AdapterDecision {
        val decision = MobilePatternAnalyzer().analyze(source, profile)
            .adapterDecisions.singleOrNull()
        assertNotNull(decision)
        assertEquals(AdapterDisposition.AUTOMATIC, decision.disposition)
        return decision
    }

    private fun scriptPaths(block: JsonObject): List<String> =
        (block["js"] as JsonArray).map { (it as JsonPrimitive).content }

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
