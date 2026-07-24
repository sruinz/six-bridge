package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        supportedFeatures = setOf("storage.local"),
        unavailableFeatures = setOf("storage.sync"),
    )

    @Test
    fun `applies action selection and storage in planner order in one transaction`() {
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            val plan = plan(snapshot.originalRoot)
            val applied = workspace.transform(snapshot, profile) { context ->
                plan.selectedAdapters.map { selected ->
                    adapters.getValue(selected.id).apply(context, selected)
                }
            }

            assertEquals(
                listOf(
                    MobilePatternAnalyzer.ACTION_POPUP_ENTRY,
                    MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE,
                    MobilePatternAnalyzer.STORAGE_FALLBACK,
                ),
                plan.selectedAdapters.map(SelectedAdapter::id),
            )
            assertTrue(applied.all(AdapterDecision::applied))
            val manifest = snapshot.workingRoot.resolve("manifest.json").readText()
            assertTrue("action-popup-entry-content.v1.js" in manifest)
            assertTrue("desktop-selection-event-bridge.v1.js" in manifest)
            assertTrue(
                "chrome.storage.local" in
                    snapshot.workingRoot.resolve("content.js").readText(),
            )
        }
    }

    @Test
    fun `rolls back earlier adapters when storage target is changed inside transaction`() {
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            val plan = plan(snapshot.originalRoot)
            val before = ExtensionZip.createBytes(
                snapshot.workingRoot,
                includeProjectMarker = true,
            )

            assertFailsWith<ConversionExecutionException> {
                workspace.transform(snapshot, profile) { context ->
                    plan.selectedAdapters.forEach { selected ->
                        if (selected.id == MobilePatternAnalyzer.STORAGE_FALLBACK) {
                            context.stagedRoot.resolve("content.js")
                                .writeText("chrome.storage.sync.get('tampered');")
                        }
                        adapters.getValue(selected.id).apply(context, selected)
                    }
                }
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
                workspace.transform(snapshot, differentProfile) { context ->
                    plan.selectedAdapters.forEach { selected ->
                        adapters.getValue(selected.id).apply(context, selected)
                    }
                }
            }
        }
    }

    private fun plan(root: java.nio.file.Path): ConversionPlan {
        val report = MobilePatternAnalyzer().analyze(root, profile)
        return ConversionPlanner().plan(
            report,
            setOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY),
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
            root.resolve("popup.html").writeText("<p>popup</p>")
            root.resolve("content.js").writeText(
                """
                document.addEventListener("mouseup", (event) => {
                  const text = getSelection()?.toString();
                  chrome.storage.sync.set({text});
                });
                """.trimIndent(),
            )
        }

    private companion object {
        val adapters: Map<String, MobileAdapter> = mapOf(
            MobilePatternAnalyzer.ACTION_POPUP_ENTRY to ActionPopupEntryAdapter(),
            MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE to
                DesktopSelectionEventBridge(),
            MobilePatternAnalyzer.STORAGE_FALLBACK to StorageFallbackAdapter(),
        )
    }
}
