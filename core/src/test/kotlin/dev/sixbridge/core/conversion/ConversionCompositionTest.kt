package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversionCompositionTest {
    private val profile = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = setOf("runtime", "storage", "tabs"),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        supportedFeatures = setOf(
            "browser.internal-url.extensions-root",
            "storage.local",
            "extension.options-page.user-accessible",
        ),
        unavailableFeatures = setOf("storage.sync"),
    )

    @Test
    fun `executes storage first but returns decisions in planner order`() {
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            val plan = plan(snapshot.originalRoot)
            val applied = workspace.transform(snapshot, profile, plan)

            assertEquals(
                listOf(
                    MobilePatternAnalyzer.ACTION_POPUP_ENTRY,
                    MobilePatternAnalyzer.BROWSER_INTERNAL_URL_MAPPER,
                    MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE,
                    MobilePatternAnalyzer.POPUP_NATIVE_AUTOFILL_GUARD,
                    MobilePatternAnalyzer.STORAGE_FALLBACK,
                ),
                plan.selectedAdapters.map(SelectedAdapter::id),
            )
            assertTrue(applied.all(AdapterDecision::applied))
            val manifest = Json.parseToJsonElement(
                snapshot.workingRoot.resolve("manifest.json").readText(),
            ) as JsonObject
            assertEquals(
                JsonObject(
                    linkedMapOf(
                        "page" to JsonPrimitive("popup.html"),
                        "open_in_tab" to JsonPrimitive(true),
                    ),
                ),
                manifest["options_ui"],
            )
            assertEquals(
                """{"adapter":"action-popup-entry","version":2,"popup":"popup.html"}""",
                snapshot.workingRoot
                    .resolve("sixbridge/action-popup-entry.v2.json")
                    .readText(),
            )
            listOf(
                "_sixbridge/action-popup-entry-content.v1.js",
                "_sixbridge/action-popup-entry-background.v1.js",
                "_sixbridge-action-popup-entry-bootstrap.v1.js",
            ).forEach { relativePath ->
                assertFalse(
                    Files.exists(snapshot.workingRoot.resolve(relativePath)),
                    relativePath,
                )
            }
            assertTrue(
                "desktop-selection-event-bridge.v1.js" in manifest.toString(),
            )
            assertTrue(
                "chrome.storage.local" in
                    snapshot.workingRoot.resolve("content.js").readText(),
            )
            assertTrue(
                "<script src=\"/sixbridge/popup-native-autofill-guard.v1.js\"></script>" in
                    snapshot.workingRoot.resolve("popup.html").readText(),
            )
            assertTrue(
                "href=\"internet://extension\"" in
                    snapshot.workingRoot.resolve("popup.html").readText(),
            )
            assertTrue(
                Files.isRegularFile(
                    snapshot.workingRoot.resolve(
                        "sixbridge/browser-internal-url-mapper.v1.json",
                    ),
                ),
            )
            val reapplied = MobilePatternAnalyzer().analyze(
                snapshot.workingRoot,
                profile,
            ).adapterDecisions.filter {
                it.id == MobilePatternAnalyzer.BROWSER_INTERNAL_URL_MAPPER ||
                    it.id == MobilePatternAnalyzer.POPUP_NATIVE_AUTOFILL_GUARD
            }
            assertEquals(2, reapplied.size)
            assertTrue(reapplied.all(AdapterDecision::applied))
            assertFalse(
                "popup-native-autofill-guard.v1.js" in
                    snapshot.workingRoot.resolve("content.js").readText(),
            )
        }
    }

    @Test
    fun `rolls back when an externally observed stage is changed`() {
        val source = extension()
        repeat(1_000) { index ->
            source.resolve("padding-$index.txt").writeText("padding-$index")
        }
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            val plan = plan(snapshot.originalRoot)
            val before = ExtensionZip.createBytes(
                snapshot.workingRoot,
                includeProjectMarker = true,
            )

            val pool = Executors.newSingleThreadExecutor()
            val mutation = pool.submit {
                val stage = waitForSibling(
                    requireNotNull(snapshot.workingRoot.parent),
                    ".sixbridge-stage-",
                )
                val manifest = stage.resolve("manifest.json")
                while (!Files.exists(manifest)) Thread.onSpinWait()
                manifest.writeText("""{"manifest_version":3}""")
            }
            try {
                assertFailsWith<ConversionExecutionException> {
                    workspace.transform(snapshot, profile, plan)
                }
                mutation.get(5, TimeUnit.SECONDS)
            } finally {
                pool.shutdownNow()
            }

            assertTrue(
                before.contentEquals(
                    ExtensionZip.createBytes(
                        snapshot.workingRoot,
                        includeProjectMarker = true,
                    ),
                ),
            )
        }
    }

    @Test
    fun `rejects a report when execution profile is different`() {
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            val plan = plan(snapshot.originalRoot)
            val differentProfile = profile.copy(versionCode = profile.versionCode + 1)

            assertFailsWith<ConversionExecutionException> {
                workspace.transform(snapshot, differentProfile, plan)
            }
        }
    }

    @Test
    fun `rejects a planner capability replayed for a different source`() {
        val first = extension()
        val second = extension().also {
            it.resolve("content.js").writeText(
                it.resolve("content.js").readText() + "\nconsole.log('different');",
            )
        }
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(second)
            val plan = plan(first)

            assertFailsWith<ConversionExecutionException> {
                workspace.transform(snapshot, profile, plan)
            }
        }
    }

    @Test
    fun `rejects removed or reordered analyzer decisions`() {
        listOf<(List<AdapterDecision>) -> List<AdapterDecision>>(
            { decisions -> decisions.drop(1) },
            { decisions -> decisions.reversed() },
        ).forEach { mutate ->
            val source = extension()
            ConversionWorkspace().use { workspace ->
                val snapshot = workspace.snapshot(source)
                val report = MobilePatternAnalyzer().analyze(
                    snapshot.originalRoot,
                    profile,
                )
                val tampered = report.copy(
                    adapterDecisions = mutate(report.adapterDecisions),
                )
                val plan = ConversionPlanner().plan(
                    tampered,
                    if (
                        tampered.adapterDecisions.any {
                            it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY
                        }
                    ) {
                        setOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY)
                    } else {
                        emptySet()
                    },
                )

                assertFailsWith<ConversionExecutionException> {
                    workspace.transform(snapshot, profile, plan)
                }
            }
        }
    }

    private fun plan(root: java.nio.file.Path): ConversionPlan {
        val report = MobilePatternAnalyzer().analyze(root, profile)
        return ConversionPlanner().plan(
            report,
            setOf(
                MobilePatternAnalyzer.ACTION_POPUP_ENTRY,
                MobilePatternAnalyzer.POPUP_NATIVE_AUTOFILL_GUARD,
            ),
        )
    }

    private fun extension() =
        Files.createTempDirectory("sixbridge-composition-").also { root ->
            root.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "Composition",
                  "version": "1",
                  "permissions": ["storage"],
                  "action": {"default_popup": "popup.html"},
                  "content_scripts": [{
                    "matches": ["<all_urls>"],
                    "js": ["content.js"]
                  }]
                }
                """.trimIndent(),
            )
            root.resolve("popup.html").writeText(
                "<html><head></head><body><a href=\"chrome://extensions\">확장</a>" +
                    "<input type='email'></body></html>",
            )
            root.resolve("content.js").writeText(
                """
                document.addEventListener("mouseup", (event) => {
                  const text = getSelection()?.toString();
                  chrome.storage.sync.set({text});
                });
                """.trimIndent(),
            )
        }

    private fun waitForSibling(
        parent: java.nio.file.Path,
        prefix: String,
    ): java.nio.file.Path {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            Files.list(parent).use { paths ->
                paths.filter { it.fileName.toString().startsWith(prefix) }
                    .findFirst()
                    .orElse(null)
                    ?.let { return it }
            }
            Thread.onSpinWait()
        }
        error("$prefix sibling을 관찰하지 못했습니다.")
    }
}
