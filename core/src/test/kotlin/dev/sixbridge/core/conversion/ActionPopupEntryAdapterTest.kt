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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionPopupEntryAdapterTest {
    @Test
    fun `requires approval before applying and then injects without changing permissions or originals`() {
        val source = extension()
        val manifestBefore = manifest(source)
        val contentBefore = source.resolve("content.js").readBytes()
        val popupBefore = source.resolve("popup.html").readBytes()
        val report = MobilePatternAnalyzer().analyze(source, profile)

        assertFailsWith<ConversionPlanningException> {
            ConversionPlanner().plan(report, emptySet())
        }
        assertFalse(Files.exists(source.resolve("_sixbridge")))

        val approved = ConversionPlanner()
            .plan(report, setOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY))
            .selectedAdapters
            .single { it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY }
        val applied = ActionPopupEntryAdapter().apply(source, approved)
        val updated = manifest(source)

        assertTrue(applied.applied)
        assertEquals(
            listOf("_sixbridge/action-popup-entry-content.v1.js", "content.js"),
            scripts((updated["content_scripts"] as JsonArray)[0] as JsonObject),
        )
        assertEquals(
            listOf("_sixbridge/action-popup-entry-content.v1.js", "second.js"),
            scripts((updated["content_scripts"] as JsonArray)[1] as JsonObject),
        )
        assertEquals(manifestBefore["permissions"], updated["permissions"])
        assertEquals(manifestBefore["host_permissions"], updated["host_permissions"])
        assertEquals(
            "_sixbridge/action-popup-entry-background.v1.js",
            ((updated["background"] as JsonObject)["service_worker"] as JsonPrimitive).content,
        )
        assertContentEquals(contentBefore, source.resolve("content.js").readBytes())
        assertContentEquals(popupBefore, source.resolve("popup.html").readBytes())
    }

    @Test
    fun `does not apply without a popup or when accessibility is verified`() {
        val withoutPopup = extension(popup = null)
        val accessible = extension()
        val changedAfterApproval = extension()
        val selectedBeforePopupRemoval = approvedSelection(changedAfterApproval)
        changedAfterApproval.resolve("manifest.json").writeText(
            manifest(changedAfterApproval).let {
                JsonObject(it - "action").toString()
            },
        )

        assertNull(
            MobilePatternAnalyzer().analyze(withoutPopup, profile)
                .adapterDecisions.singleOrNull { it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY },
        )
        assertNull(
            MobilePatternAnalyzer().analyze(
                accessible,
                profile.copy(supportedFeatures = setOf("action.popup.user-accessible")),
            ).adapterDecisions.singleOrNull {
                it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY
            },
        )
        assertFalse(
            ActionPopupEntryAdapter()
                .apply(changedAfterApproval, selectedBeforePopupRemoval)
                .applied,
        )
        assertFalse(Files.exists(changedAfterApproval.resolve("_sixbridge")))
    }

    @Test
    fun `marks missing content scripts or tabs namespace unavailable`() {
        val noContent = extension(hasContent = false)
        val noTabs = extension()

        val noContentDecision = MobilePatternAnalyzer().analyze(noContent, profile)
            .adapterDecisions.single { it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY }
        val noTabsDecision = MobilePatternAnalyzer().analyze(
            noTabs,
            profile.copy(supportedApis = profile.supportedApis - "tabs"),
        ).adapterDecisions.single { it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY }

        assertEquals(AdapterDisposition.UNAVAILABLE, noContentDecision.disposition)
        assertEquals(AdapterDisposition.UNAVAILABLE, noTabsDecision.disposition)
        assertTrue(
            ConversionPlanner().plan(
                MobilePatternAnalyzer().analyze(noContent, profile),
                emptySet(),
            ).selectedAdapters.isEmpty(),
        )
        assertTrue(
            ConversionPlanner().plan(
                MobilePatternAnalyzer().analyze(
                    noTabs,
                    profile.copy(supportedApis = profile.supportedApis - "tabs"),
                ),
                emptySet(),
            ).selectedAdapters.isEmpty(),
        )
    }

    @Test
    fun `preserves classic and module workers through canonical generated bootstraps`() {
        listOf(null, "module").forEach { workerType ->
            val source = extension(
                worker = "/scripts/./background.js",
                workerType = workerType,
            )
            val workerBefore = source.resolve("scripts/background.js").readBytes()
            val applied = applyApproved(source)
            val updatedBackground = manifest(source)["background"] as JsonObject
            val bootstrap = source.resolve(
                "scripts/_sixbridge-action-popup-entry-bootstrap.v1.js",
            ).readText()

            assertTrue(applied.applied)
            assertEquals(
                "scripts/_sixbridge-action-popup-entry-bootstrap.v1.js",
                (updatedBackground["service_worker"] as JsonPrimitive).content,
            )
            assertEquals(
                workerType,
                (updatedBackground["type"] as? JsonPrimitive)?.content,
            )
            if (workerType == "module") {
                assertEquals(
                    "import \"./background.js\";\n" +
                        "import \"../_sixbridge/action-popup-entry-background.v1.js\";\n",
                    bootstrap,
                )
            } else {
                assertEquals(
                    "importScripts(\"./background.js\", " +
                        "\"../_sixbridge/action-popup-entry-background.v1.js\");\n",
                    bootstrap,
                )
            }
            assertContentEquals(workerBefore, source.resolve("scripts/background.js").readBytes())
        }
    }

    @Test
    fun `marks worker URL dependencies unavailable and rejects a stale selected capability`() {
        val dependent = extension(
            worker = "scripts/background.js",
            workerSource = "globalThis.workerUrl = self.location.href;",
        )
        val report = MobilePatternAnalyzer().analyze(dependent, profile)
        val decision = report.adapterDecisions
            .single { it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY }

        assertEquals(AdapterDisposition.UNAVAILABLE, decision.disposition)
        assertTrue(ConversionPlanner().plan(report, emptySet()).selectedAdapters.isEmpty())

        val changedAfterApproval = extension(worker = "scripts/background.js")
        val selected = approvedSelection(changedAfterApproval)
        changedAfterApproval.resolve("scripts/background.js")
            .writeText("globalThis.workerUrl = self.location.href;")

        assertFailsWith<IllegalStateException> {
            ActionPopupEntryAdapter().apply(changedAfterApproval, selected)
        }
        assertFalse(Files.exists(changedAfterApproval.resolve("_sixbridge")))
    }

    @Test
    fun `conservatively detects worker URL access but ignores comments and plain strings`() {
        listOf(
            """new URL("worker.js", location);""",
            "void location;",
            """self["location"].href;""",
            "self?.location.href;",
            """new URL("worker.js", import.meta["url"]);""",
        ).forEach { workerSource ->
            val source = extension(
                worker = "background.js",
                workerType = "module",
                workerSource = workerSource,
            )
            val decision = MobilePatternAnalyzer().analyze(source, profile)
                .adapterDecisions
                .single { it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY }

            assertEquals(
                AdapterDisposition.UNAVAILABLE,
                decision.disposition,
                workerSource,
            )
        }

        val lexicalOnly = extension(
            worker = "background.js",
            workerSource = """
                // location and import.meta are documentation words.
                const text = "location import.meta";
            """.trimIndent(),
        )
        val lexicalDecision = MobilePatternAnalyzer().analyze(lexicalOnly, profile)
            .adapterDecisions
            .single { it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY }
        assertEquals(AdapterDisposition.REQUIRES_APPROVAL, lexicalDecision.disposition)
    }

    @Test
    fun `apply rechecks unresolved and transitive background dependencies after approval`() {
        val changedTransitive = extension(
            worker = "background.js",
            workerSource = """importScripts("./dependency.js");""",
        )
        changedTransitive.resolve("dependency.js").writeText(
            "globalThis.initiallySafe = true;",
        )
        val transitiveSelection = approvedSelection(changedTransitive)
        changedTransitive.resolve("dependency.js").writeText(
            """new URL("worker.js", location);""",
        )
        assertFailsWith<IllegalStateException>("changed transitive dependency") {
            ActionPopupEntryAdapter().apply(changedTransitive, transitiveSelection)
        }
        assertFalse(Files.exists(changedTransitive.resolve("_sixbridge")))

        val cases = listOf(
            Triple(
                "new static dependency",
                """importScripts("./dependency.js");""",
                """self["location"].href;""",
            ),
            Triple(
                "unresolved dependency",
                "importScripts(nextScript);",
                "",
            ),
        )
        cases.forEach { (label, changedWorker, changedDependency) ->
            val source = extension(
                worker = "background.js",
                workerSource = "globalThis.initiallySafe = true;",
            )
            val selected = approvedSelection(source)
            source.resolve("background.js").writeText(changedWorker)
            if (changedDependency.isNotEmpty()) {
                source.resolve("dependency.js").writeText(changedDependency)
            }
            val manifestBefore = source.resolve("manifest.json").readBytes()

            assertFailsWith<IllegalStateException>(label) {
                ActionPopupEntryAdapter().apply(source, selected)
            }
            assertContentEquals(
                manifestBefore,
                source.resolve("manifest.json").readBytes(),
                label,
            )
            assertFalse(Files.exists(source.resolve("_sixbridge")), label)
        }
    }

    @Test
    fun `generated bootstrap reapply rechecks transitive background dependencies`() {
        val source = extension(
            worker = "scripts/background.js",
            workerSource = """importScripts("./dependency.js");""",
        )
        source.resolve("scripts/dependency.js").writeText(
            "globalThis.initiallySafe = true;",
        )
        val selected = approvedSelection(source)
        ActionPopupEntryAdapter().apply(source, selected)
        val manifestBefore = source.resolve("manifest.json").readBytes()
        source.resolve("scripts/dependency.js").writeText(
            """self["location"].href;""",
        )

        assertFailsWith<IllegalStateException> {
            ActionPopupEntryAdapter().apply(source, selected)
        }
        assertContentEquals(manifestBefore, source.resolve("manifest.json").readBytes())
    }

    @Test
    fun `rejects encoded and URL-like popup and worker paths`() {
        val unsafeSegments = listOf(
            "%2e%2e/popup.html",
            ".%2e/popup.html",
            "%2e./popup.html",
            "%2fpopup.html",
            "%5cpopup.html",
            "https://example.test/popup.html",
            "popup.html?mode=1",
            "popup.html#fragment",
            """folder\popup.html""",
        )
        unsafeSegments.forEach { unsafePopup ->
            val source = extension(popup = unsafePopup)
            val report = MobilePatternAnalyzer().analyze(source, profile)

            assertEquals(
                dev.sixbridge.core.compat.CompatibilityStatus.BLOCKED,
                report.status,
                unsafePopup,
            )
            assertTrue(report.adapterDecisions.isEmpty(), unsafePopup)
        }

        val unsafeWorkers = listOf(
            "%2e%2e/background.js",
            ".%2e/background.js",
            "%2e./background.js",
            "%2fbackground.js",
            "%5cbackground.js",
            "https://example.test/background.js",
            "background.js?mode=1",
            "background.js#fragment",
            """folder\background.js""",
        )
        unsafeWorkers.forEach { unsafeWorker ->
            val source = extension(worker = "background.js")
            val selected = approvedSelection(source)
            val unsafeOutput = source.resolve(unsafeWorker.removePrefix("/")).normalize()
            Files.createDirectories(unsafeOutput.parent)
            unsafeOutput.writeText("globalThis.unsafeWorker = true;")
            source.resolve("manifest.json").writeText(
                JsonObject(
                    manifest(source) + (
                        "background" to JsonObject(
                            mapOf("service_worker" to JsonPrimitive(unsafeWorker)),
                        )
                    ),
                ).toString(),
            )

            assertFailsWith<IllegalStateException>(unsafeWorker) {
                ActionPopupEntryAdapter().apply(source, selected)
            }
            assertFalse(Files.exists(source.resolve("_sixbridge")), unsafeWorker)
        }
    }

    @Test
    fun `embeds the popup as an escaped JavaScript string`() {
        val popup = "popup-\"quoted.html"
        val source = extension(popup = popup)

        applyApproved(source)

        val generated = source.resolve(
            "_sixbridge/action-popup-entry-background.v1.js",
        ).readText()
        assertTrue("""const popupPath = "popup-\"quoted.html";""" in generated)
    }

    @Test
    fun `reapplying is a byte for byte no op for no worker classic and module forms`() {
        listOf(
            null to null,
            "background.js" to null,
            "background.js" to "module",
        ).forEach { (worker, workerType) ->
            val source = extension(worker = worker, workerType = workerType)
            val adapter = ActionPopupEntryAdapter()
            val selection = approvedSelection(source)

            adapter.apply(source, selection)
            val manifestBefore = source.resolve("manifest.json").readBytes()
            val zipBefore = ExtensionZip.createBytes(source)
            val reapplied = adapter.apply(source, selection)

            assertTrue(reapplied.applied)
            assertContentEquals(manifestBefore, source.resolve("manifest.json").readBytes())
            assertContentEquals(zipBefore, ExtensionZip.createBytes(source))
        }
    }

    @Test
    fun `rejects generated asset collisions and parent conflicts before changing the manifest`() {
        val collision = extension(worker = "background.js")
        val collisionManifest = collision.resolve("manifest.json").readBytes()
        val generated = collision.resolve("_sixbridge/action-popup-entry-content.v1.js")
        Files.createDirectories(generated.parent)
        generated.writeText("foreign bytes")

        assertFailsWith<IllegalStateException> {
            applyApproved(collision)
        }
        assertContentEquals(collisionManifest, collision.resolve("manifest.json").readBytes())
        assertEquals("foreign bytes", generated.readText())
        assertFalse(
            Files.exists(collision.resolve("_sixbridge/action-popup-entry-background.v1.js")),
        )

        val parentConflict = extension()
        val parentManifest = parentConflict.resolve("manifest.json").readBytes()
        parentConflict.resolve("_sixbridge").writeText("not a directory")

        assertFailsWith<IllegalStateException> {
            applyApproved(parentConflict)
        }
        assertContentEquals(parentManifest, parentConflict.resolve("manifest.json").readBytes())
    }

    @Test
    fun `canonicalizes generated aliases and rejects an unproven generated bootstrap`() {
        val source = extension()
        applyApproved(source)
        val firstManifest = manifest(source)
        val blocks = firstManifest["content_scripts"] as JsonArray
        val block = blocks[0] as JsonObject
        val aliasedBlock = JsonObject(
            block + (
                "js" to JsonArray(
                    listOf(
                        JsonPrimitive("content.js"),
                        JsonPrimitive("./_sixbridge/action-popup-entry-content.v1.js"),
                        JsonPrimitive("/_sixbridge/action-popup-entry-content.v1.js"),
                    ),
                )
            ),
        )
        val aliasedManifest = JsonObject(
            firstManifest + ("content_scripts" to JsonArray(listOf(aliasedBlock))),
        )
        source.resolve("manifest.json").writeText(aliasedManifest.toString())

        applyApproved(source)

        assertEquals(
            listOf("_sixbridge/action-popup-entry-content.v1.js", "content.js"),
            scripts((manifest(source)["content_scripts"] as JsonArray)[0] as JsonObject),
        )

        val tampered = extension(worker = "background.js")
        applyApproved(tampered)
        val manifestBefore = tampered.resolve("manifest.json").readBytes()
        tampered.resolve("_sixbridge-action-popup-entry-bootstrap.v1.js")
            .writeText("importScripts(\"./background.js\");")

        assertFailsWith<IllegalStateException> {
            applyApproved(tampered)
        }
        assertContentEquals(manifestBefore, tampered.resolve("manifest.json").readBytes())
    }

    @Test
    fun `rejects percent encoded generated bootstrap imports`() {
        listOf(
            "./%2e%2e/background.js",
            "./.%2e/background.js",
            "./%2e./background.js",
            "./%2fbackground.js",
            "./%5cbackground.js",
        ).forEach { unsafeImport ->
            val source = extension(worker = "scripts/background.js")
            val selected = approvedSelection(source)
            ActionPopupEntryAdapter().apply(source, selected)
            val bootstrap = source.resolve(
                "scripts/_sixbridge-action-popup-entry-bootstrap.v1.js",
            )
            bootstrap.writeText(
                "importScripts(${JsonPrimitive(unsafeImport)}, " +
                    "\"../_sixbridge/action-popup-entry-background.v1.js\");\n",
            )
            val manifestBefore = source.resolve("manifest.json").readBytes()

            assertFailsWith<IllegalStateException>(unsafeImport) {
                ActionPopupEntryAdapter().apply(source, selected)
            }
            assertContentEquals(
                manifestBefore,
                source.resolve("manifest.json").readBytes(),
                unsafeImport,
            )
        }
    }

    private fun applyApproved(source: Path): AdapterDecision =
        ActionPopupEntryAdapter().apply(source, approvedSelection(source))

    private fun approvedSelection(source: Path): SelectedAdapter {
        val report = MobilePatternAnalyzer().analyze(source, profile)
        val decision = report.adapterDecisions
            .single { it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY }
        assertEquals(AdapterDisposition.REQUIRES_APPROVAL, decision.disposition)
        return ConversionPlanner()
            .plan(report, setOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY))
            .selectedAdapters
            .single { it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY }
    }

    private fun manifest(root: Path): JsonObject =
        Json.parseToJsonElement(root.resolve("manifest.json").readText()) as JsonObject

    private fun scripts(block: JsonObject): List<String> =
        (block["js"] as JsonArray).map { (it as JsonPrimitive).content }

    private fun extension(
        popup: String? = "popup.html",
        hasContent: Boolean = true,
        worker: String? = null,
        workerType: String? = null,
        workerSource: String = "globalThis.originalWorker = true;",
    ): Path {
        val root = Files.createTempDirectory("six-action-popup-entry")
        val popupMember = popup?.let {
            ""","action":{"default_popup":${JsonPrimitive(it)}}"""
        }.orEmpty()
        val contentMember = if (hasContent) {
            ""","content_scripts":[{"matches":["https://example.test/*"],"js":["content.js"]},{"matches":["https://second.example.test/*"],"js":["second.js"]}]"""
        } else {
            ""
        }
        val typeMember = workerType?.let { ""","type":${JsonPrimitive(it)}""" }.orEmpty()
        val backgroundMember = worker?.let {
            ""","background":{"service_worker":${JsonPrimitive(it)}$typeMember}"""
        }.orEmpty()
        root.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Action fixture","version":"1","permissions":["storage"],"host_permissions":["https://example.test/*"]$popupMember$contentMember$backgroundMember}""",
        )
        if (hasContent) {
            root.resolve("content.js").writeText("console.log('content');")
            root.resolve("second.js").writeText("console.log('second');")
        }
        popup?.let { path ->
            val output = root.resolve(path)
            Files.createDirectories(output.parent)
            output.writeText("<p>popup</p>")
        }
        worker?.let {
            val output = root.resolve(it.removePrefix("/")).normalize()
            if (output.startsWith(root)) {
                Files.createDirectories(output.parent)
                output.writeText(workerSource)
            }
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
