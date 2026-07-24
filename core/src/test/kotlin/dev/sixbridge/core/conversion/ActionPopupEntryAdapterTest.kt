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
        val staleDecision = decision(AdapterDisposition.REQUIRES_APPROVAL)
        assertFalse(ActionPopupEntryAdapter().apply(withoutPopup, staleDecision).applied)
        assertFalse(Files.exists(withoutPopup.resolve("_sixbridge")))
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
        assertFalse(ActionPopupEntryAdapter().apply(noContent, noContentDecision).applied)
        assertFalse(ActionPopupEntryAdapter().apply(noTabs, noTabsDecision).applied)
    }

    @Test
    fun `preserves classic and module workers through canonical generated bootstraps`() {
        listOf(null, "module").forEach { workerType ->
            val source = extension(
                worker = "/scripts/../background.js",
                workerType = workerType,
            )
            val workerBefore = source.resolve("background.js").readBytes()
            val applied = applyApproved(source)
            val updatedBackground = manifest(source)["background"] as JsonObject
            val bootstrap = source.resolve(
                "_sixbridge/action-popup-entry-bootstrap.v1.js",
            ).readText()

            assertTrue(applied.applied)
            assertEquals(
                "_sixbridge/action-popup-entry-bootstrap.v1.js",
                (updatedBackground["service_worker"] as JsonPrimitive).content,
            )
            assertEquals(
                workerType,
                (updatedBackground["type"] as? JsonPrimitive)?.content,
            )
            if (workerType == "module") {
                assertEquals(
                    "import \"../background.js\";\n" +
                        "import \"./action-popup-entry-background.v1.js\";\n",
                    bootstrap,
                )
            } else {
                assertEquals(
                    "importScripts(\"../background.js\", " +
                        "\"./action-popup-entry-background.v1.js\");\n",
                    bootstrap,
                )
            }
            assertContentEquals(workerBefore, source.resolve("background.js").readBytes())
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
            val decision = approvedDecision(source)

            adapter.apply(source, decision)
            val manifestBefore = source.resolve("manifest.json").readBytes()
            val zipBefore = ExtensionZip.createBytes(source)
            val reapplied = adapter.apply(source, decision)

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
        tampered.resolve("_sixbridge/action-popup-entry-bootstrap.v1.js")
            .writeText("importScripts(\"../background.js\");")

        assertFailsWith<IllegalStateException> {
            applyApproved(tampered)
        }
        assertContentEquals(manifestBefore, tampered.resolve("manifest.json").readBytes())
    }

    private fun applyApproved(source: Path): AdapterDecision =
        ActionPopupEntryAdapter().apply(source, approvedDecision(source))

    private fun approvedDecision(source: Path): AdapterDecision {
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

    private fun decision(disposition: AdapterDisposition) = AdapterDecision(
        id = MobilePatternAnalyzer.ACTION_POPUP_ENTRY,
        version = 1,
        risk = AdapterRisk.BEHAVIOR_CHANGE,
        disposition = disposition,
        applied = false,
        evidence = listOf(SourceEvidence("manifest.json", 1, "action.default_popup=popup.html")),
        changes = emptyList(),
        remainingLimitations = emptyList(),
    )

    private fun extension(
        popup: String? = "popup.html",
        hasContent: Boolean = true,
        worker: String? = null,
        workerType: String? = null,
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
            root.resolve("background.js").writeText("globalThis.originalWorker = true;")
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
