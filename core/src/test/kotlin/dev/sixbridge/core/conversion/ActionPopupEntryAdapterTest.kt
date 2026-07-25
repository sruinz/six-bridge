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
import kotlin.io.path.deleteExisting
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ActionPopupEntryAdapterTest {
    @Test
    fun `adds only options ui and deterministic provenance`() {
        val source = extension(worker = "background.js")
        val contentBefore = source.resolve("content.js").readBytes()
        val backgroundBefore = source.resolve("background.js").readBytes()
        val popupBefore = source.resolve("popup.html").readBytes()
        val manifestBefore = manifest(source)

        val applied = applyApproved(source)
        val updated = manifest(source)

        assertTrue(applied.applied)
        assertEquals(
            JsonObject(
                linkedMapOf(
                    "page" to JsonPrimitive("popup.html"),
                    "open_in_tab" to JsonPrimitive(true),
                ),
            ),
            updated["options_ui"],
        )
        assertEquals(manifestBefore["permissions"], updated["permissions"])
        assertEquals(manifestBefore["host_permissions"], updated["host_permissions"])
        assertEquals(manifestBefore["content_scripts"], updated["content_scripts"])
        assertEquals(manifestBefore["background"], updated["background"])
        assertContentEquals(contentBefore, source.resolve("content.js").readBytes())
        assertContentEquals(backgroundBefore, source.resolve("background.js").readBytes())
        assertContentEquals(popupBefore, source.resolve("popup.html").readBytes())
        assertEquals(
            """{"adapter":"action-popup-entry","version":2,"popup":"popup.html"}""",
            source.resolve("sixbridge/action-popup-entry.v2.json").readText(),
        )
        assertFalse(
            Files.exists(source.resolve("_sixbridge/action-popup-entry.v2.json")),
        )
    }

    @Test
    fun `does not overwrite options ui or options page added after approval`() {
        listOf(
            "options_ui" to JsonObject(
                linkedMapOf(
                    "page" to JsonPrimitive("settings.html"),
                    "open_in_tab" to JsonPrimitive(true),
                ),
            ),
            "options_page" to JsonPrimitive("settings.html"),
        ).forEach { (name, value) ->
            val source = extension()
            source.resolve("settings.html").writeText("<p>settings</p>")
            val selected = approvedSelection(source)
            source.resolve("manifest.json").writeText(
                JsonObject(manifest(source) + (name to value)).toString(),
            )
            val manifestBefore = source.resolve("manifest.json").readBytes()

            val result = applyAdapterToSource(
                source,
                profile,
                ActionPopupEntryAdapter(),
                selected,
            )

            assertFalse(result.applied, name)
            assertContentEquals(
                manifestBefore,
                source.resolve("manifest.json").readBytes(),
                name,
            )
            assertFalse(Files.exists(source.resolve("sixbridge")), name)
        }
    }

    @Test
    fun `rejects provenance path collisions before changing the manifest`() {
        listOf<(Path) -> Unit>(
            { marker ->
                Files.createDirectories(marker.parent)
                marker.writeText("foreign bytes")
            },
            { marker ->
                Files.createDirectories(marker.parent)
                Files.createDirectory(marker)
                marker.resolve("foreign.txt").writeText("foreign directory")
            },
            { marker ->
                Files.createDirectories(marker.parent)
                val target = Files.createTempFile("six-action-provenance", ".json")
                marker.createSymbolicLinkPointingTo(target)
            },
        ).forEachIndexed { index, createCollision ->
            val source = extension()
            val marker = source.resolve("sixbridge/action-popup-entry.v2.json")
            val manifestBefore = source.resolve("manifest.json").readBytes()
            createCollision(marker)

            assertFails("collision $index") {
                applyApproved(source)
            }

            assertContentEquals(
                manifestBefore,
                source.resolve("manifest.json").readBytes(),
                "collision $index",
            )
        }
    }

    @Test
    fun `rejects popup removed or replaced after approval`() {
        listOf<(Path) -> Unit>(
            { popup -> popup.deleteExisting() },
            { popup ->
                popup.deleteExisting()
                val target = Files.createTempFile("six-action-popup", ".html")
                popup.createSymbolicLinkPointingTo(target)
            },
            { popup ->
                popup.deleteExisting()
                Files.createDirectory(popup)
            },
        ).forEachIndexed { index, mutatePopup ->
            val source = extension()
            val selected = approvedSelection(source)
            mutatePopup(source.resolve("popup.html"))
            val manifestBefore = source.resolve("manifest.json").readBytes()

            assertFails("popup mutation $index") {
                applyAdapterToSource(
                    source,
                    profile,
                    ActionPopupEntryAdapter(),
                    selected,
                )
            }

            assertContentEquals(
                manifestBefore,
                source.resolve("manifest.json").readBytes(),
                "popup mutation $index",
            )
            assertFalse(Files.exists(source.resolve("sixbridge")), "popup mutation $index")
        }
    }

    @Test
    fun `reapplying is a manifest and zip byte for byte no op`() {
        val source = extension(worker = "background.js")
        val selected = approvedSelection(source)
        applyAdapterToSource(
            source,
            profile,
            ActionPopupEntryAdapter(),
            selected,
        )
        val manifestBefore = source.resolve("manifest.json").readBytes()
        val zipBefore = ExtensionZip.createBytes(source)

        val reapplied = applyAdapterToSource(
            source,
            profile,
            ActionPopupEntryAdapter(),
            selected,
        )

        assertTrue(reapplied.applied)
        assertContentEquals(manifestBefore, source.resolve("manifest.json").readBytes())
        assertContentEquals(zipBefore, ExtensionZip.createBytes(source))
    }

    @Test
    fun `reports false after provenance tampering`() {
        val source = extension()
        applyApproved(source)
        val adapter = ActionPopupEntryAdapter()

        assertTrue(adapter.isExactlyApplied(source, manifest(source), "popup.html"))

        source.resolve("sixbridge/action-popup-entry.v2.json")
            .writeText("""{"adapter":"foreign","version":2,"popup":"popup.html"}""")

        assertFalse(adapter.isExactlyApplied(source, manifest(source), "popup.html"))
    }

    @Test
    fun `reports false after options ui tampering`() {
        val source = extension()
        applyApproved(source)
        val adapter = ActionPopupEntryAdapter()
        val appliedManifest = manifest(source)
        val tamperedOptions = listOf(
            JsonObject(
                linkedMapOf(
                    "page" to JsonPrimitive("popup.html"),
                    "open_in_tab" to JsonPrimitive(true),
                    "extra" to JsonPrimitive(true),
                ),
            ),
            JsonObject(
                linkedMapOf(
                    "page" to JsonPrimitive("other.html"),
                    "open_in_tab" to JsonPrimitive(true),
                ),
            ),
            JsonObject(
                linkedMapOf(
                    "page" to JsonPrimitive("popup.html"),
                    "open_in_tab" to JsonPrimitive(false),
                ),
            ),
        )

        tamperedOptions.forEach { options ->
            val tampered = JsonObject(appliedManifest + ("options_ui" to options))
            assertFalse(adapter.isExactlyApplied(source, tampered, "popup.html"), options.toString())
        }
    }

    @Test
    fun `rejects options page added to exact application without changing bytes`() {
        val source = extension()
        val selected = approvedSelection(source)
        applyAdapterToSource(
            source,
            profile,
            ActionPopupEntryAdapter(),
            selected,
        )
        source.resolve("manifest.json").writeText(
            JsonObject(
                manifest(source) + ("options_page" to JsonPrimitive("popup.html")),
            ).toString(),
        )
        val manifestBefore = source.resolve("manifest.json").readBytes()
        val provenanceBefore = source.resolve(
            "sixbridge/action-popup-entry.v2.json",
        ).readBytes()

        assertFalse(
            ActionPopupEntryAdapter().isExactlyApplied(
                source,
                manifest(source),
                "popup.html",
            ),
        )
        val rejected = applyAdapterToSource(
            source,
            profile,
            ActionPopupEntryAdapter(),
            selected,
        )

        assertFalse(rejected.applied)
        assertContentEquals(
            manifestBefore,
            source.resolve("manifest.json").readBytes(),
        )
        assertContentEquals(
            provenanceBefore,
            source.resolve("sixbridge/action-popup-entry.v2.json").readBytes(),
        )
    }

    @Test
    fun `does not create version one runtime assets`() {
        val source = extension(worker = "background.js")
        applyApproved(source)

        listOf(
            "_sixbridge/action-popup-entry-content.v1.js",
            "_sixbridge/action-popup-entry-background.v1.js",
            "_sixbridge-action-popup-entry-bootstrap.v1.js",
        ).forEach { relativePath ->
            assertFalse(Files.exists(source.resolve(relativePath)), relativePath)
        }
        assertEquals(
            listOf("content.js", "second.js"),
            (manifest(source)["content_scripts"] as JsonArray)
                .flatMap { block ->
                    ((block as JsonObject)["js"] as JsonArray)
                        .map { (it as JsonPrimitive).content }
                },
        )
    }

    @Test
    fun `canonicalizes popup path for options ui and provenance`() {
        val source = extension(popup = "/pages/../popup.html")

        applyApproved(source)

        assertEquals(
            JsonObject(
                linkedMapOf(
                    "page" to JsonPrimitive("popup.html"),
                    "open_in_tab" to JsonPrimitive(true),
                ),
            ),
            manifest(source)["options_ui"],
        )
        assertEquals(
            """{"adapter":"action-popup-entry","version":2,"popup":"popup.html"}""",
            source.resolve("sixbridge/action-popup-entry.v2.json").readText(),
        )
    }

    private fun applyApproved(source: Path): AdapterDecision =
        applyAdapterToSource(
            source,
            profile,
            ActionPopupEntryAdapter(),
            approvedSelection(source),
        )

    private fun approvedSelection(source: Path): SelectedAdapter {
        val report = MobilePatternAnalyzer().analyze(source, profile)
        val decision = report.adapterDecisions
            .single { it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY }
        assertEquals(2, decision.version)
        assertEquals(AdapterDisposition.REQUIRES_APPROVAL, decision.disposition)
        return ConversionPlanner()
            .plan(report, setOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY))
            .selectedAdapters
            .single { it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY }
    }

    private fun manifest(root: Path): JsonObject =
        Json.parseToJsonElement(root.resolve("manifest.json").readText()) as JsonObject

    private fun extension(
        popup: String = "popup.html",
        worker: String? = null,
    ): Path {
        val root = Files.createTempDirectory("six-action-popup-entry")
        val backgroundMember = worker?.let {
            ""","background":{"service_worker":${JsonPrimitive(it)}}"""
        }.orEmpty()
        root.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Action fixture","version":"1","permissions":["storage"],"host_permissions":["https://example.test/*"],"action":{"default_popup":${JsonPrimitive(popup)}},"content_scripts":[{"matches":["https://example.test/*"],"js":["content.js"]},{"matches":["https://second.example.test/*"],"js":["second.js"]}]$backgroundMember}""",
        )
        root.resolve("content.js").writeText("console.log('content');")
        root.resolve("second.js").writeText("console.log('second');")
        val popupOutput = root.resolve(popup.removePrefix("/")).normalize()
        Files.createDirectories(popupOutput.parent)
        popupOutput.writeText("<p>popup</p>")
        worker?.let {
            val output = root.resolve(it.removePrefix("/")).normalize()
            Files.createDirectories(output.parent)
            output.writeText("globalThis.originalWorker = true;")
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
        supportedFeatures = setOf("extension.options-page.user-accessible"),
    )
}
