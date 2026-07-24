package dev.sixbridge.core.conversion

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
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
import kotlin.test.assertTrue

class StorageFallbackAdapterTest {
    private val fallbackProfile = profile(
        supportedFeatures = setOf("storage.local"),
        unavailableFeatures = setOf("storage.sync"),
    )

    @Test
    fun `rewrites only exact executable static member tokens`() {
        val source = """
            chrome.storage.sync.get("a");
            browser . storage ?. sync . set({a: 1});
            chrome?.storage.sync.remove("a");
            const text = "chrome.storage.sync";
            const computedText = "chrome.storage['sync']";
            // browser.storage.sync.clear();
            /* chrome.storage.sync.get(); */
            const template = `chrome.storage.sync ${'$'}{browser.storage.sync.get("b")}`;
            const pattern = /chrome\.storage\.sync/;
            xchrome.storage.sync.get("c");
            객체chrome.storage.sync.get("d");
        """.trimIndent().replace("\n", "\r\n")

        val result = JavaScriptTokenRewriter().rewrite(source)

        assertEquals(
            """
                chrome.storage.local.get("a");
                browser . storage ?. local . set({a: 1});
                chrome?.storage.local.remove("a");
                const text = "chrome.storage.sync";
                const computedText = "chrome.storage['sync']";
                // browser.storage.sync.clear();
                /* chrome.storage.sync.get(); */
                const template = `chrome.storage.sync ${'$'}{browser.storage.local.get("b")}`;
                const pattern = /chrome\.storage\.sync/;
                xchrome.storage.sync.get("c");
                객체chrome.storage.sync.get("d");
            """.trimIndent().replace("\n", "\r\n"),
            result.source,
        )
        assertEquals(listOf(1, 2, 3, 8), result.replacements.map { it.line })
        assertTrue(result.unsafeAccesses.isEmpty())
    }

    @Test
    fun `reports computed alias destructuring and eval access as unsafe`() {
        val samples = mapOf(
            """chrome.storage["sync"].get("a");""" to "계산된",
            """chrome["storage"].sync.get("a");""" to "계산된",
            """const area = chrome.storage.sync; area.get("a");""" to "별칭",
            """const storage = browser.storage; storage.sync.get("a");""" to "별칭",
            """const api = chrome; api.storage.sync.get("a");""" to "별칭",
            """const { sync } = chrome.storage; sync.get("a");""" to "구조 분해",
            """const { storage } = chrome; storage.sync.get("a");""" to "구조 분해",
            """chrome["storage"]["sync"].get("a");""" to "계산된",
            """some.chrome.storage.sync.get("a");""" to "속성",
            """eval("chrome.storage.sync.get('a')");""" to "eval",
        )

        samples.forEach { (source, expectedDetail) ->
            val result = JavaScriptTokenRewriter().rewrite(source)

            assertEquals(source, result.source)
            assertTrue(
                result.unsafeAccesses.any { expectedDetail in it.detail },
                "$source: ${result.unsafeAccesses}",
            )
        }
    }

    @Test
    fun `requires complete feature evidence and marks unsafe access unavailable`() {
        val safe = extension("chrome.storage.sync.get('a');")
        val computed = extension("chrome.storage['sync'].get('a');")

        val automatic = MobilePatternAnalyzer().analyze(safe, fallbackProfile)
            .adapterDecisions.single { it.id == MobilePatternAnalyzer.STORAGE_FALLBACK }
        val unknown = MobilePatternAnalyzer().analyze(safe, profile())
        val unavailable = MobilePatternAnalyzer().analyze(computed, fallbackProfile)
            .adapterDecisions.single { it.id == MobilePatternAnalyzer.STORAGE_FALLBACK }

        assertEquals(AdapterDisposition.AUTOMATIC, automatic.disposition)
        assertEquals(AdapterDisposition.UNAVAILABLE, unavailable.disposition)
        assertTrue(unavailable.evidence.single().detail.contains("계산된"))
        assertTrue(unknown.adapterDecisions.none { it.id == MobilePatternAnalyzer.STORAGE_FALLBACK })
        assertTrue(unknown.issues.any { it.code == "storage_area_unknown" })
    }

    @Test
    fun `rewrites only reachable scripts in working snapshot and is byte idempotent`() {
        val source = extension(
            content = "chrome.storage.sync.get('content');\r\n",
            background = """
                importScripts("./reachable.js");
                browser.storage.sync.set({background: true});
            """.trimIndent() + "\n",
        )
        source.resolve("reachable.js").writeText(
            "chrome?.storage?.sync.clear();\n",
        )
        source.resolve("unrelated.js").writeText(
            "chrome.storage.sync.get('must-stay');\n",
        )
        val sourceContent = source.resolve("content.js").readBytes()
        val sourceBackground = source.resolve("background.js").readBytes()

        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            val report = MobilePatternAnalyzer().analyze(
                snapshot.workingRoot,
                fallbackProfile,
            )
            val selected = ConversionPlanner().plan(report, emptySet())
                .selectedAdapters
                .single { it.id == MobilePatternAnalyzer.STORAGE_FALLBACK }

            val applied = StorageFallbackAdapter().apply(snapshot.workingRoot, selected)

            assertTrue(applied.applied)
            assertTrue(
                applied.changes == listOf(
                    "background.js:2 storage.sync → storage.local",
                    "content.js:1 storage.sync → storage.local",
                    "reachable.js:1 storage.sync → storage.local",
                ),
                applied.changes.toString(),
            )
            assertTrue(applied.remainingLimitations.any { "동기화" in it })
            assertTrue("storage.local" in snapshot.workingRoot.resolve("content.js").readText())
            assertTrue("storage.local" in snapshot.workingRoot.resolve("background.js").readText())
            assertTrue(
                "storage?.local" in snapshot.workingRoot.resolve("reachable.js").readText(),
                snapshot.workingRoot.resolve("reachable.js").readText(),
            )
            assertTrue("storage.sync" in snapshot.workingRoot.resolve("unrelated.js").readText())
            assertContentEquals(sourceContent, snapshot.originalRoot.resolve("content.js").readBytes())
            assertContentEquals(
                sourceBackground,
                snapshot.originalRoot.resolve("background.js").readBytes(),
            )

            val beforeReapply = treeBytes(snapshot.workingRoot)
            val reapplied = StorageFallbackAdapter().apply(snapshot.workingRoot, selected)

            assertTrue(reapplied.applied)
            assertTrue(reapplied.changes.isEmpty())
            assertEquals(beforeReapply, treeBytes(snapshot.workingRoot))
        }
    }

    @Test
    fun `fails closed when selected source becomes unsafe`() {
        val source = extension("chrome.storage.sync.get('a');")
        val selected = selected(source)
        source.resolve("content.js").writeText("chrome.storage['sync'].get('a');")
        val before = treeBytes(source)

        assertFailsWith<IllegalStateException> {
            StorageFallbackAdapter().apply(source, selected)
        }

        assertEquals(before, treeBytes(source))
    }

    @Test
    fun `fails closed when an approved runtime file changes outside the token`() {
        val source = extension("chrome.storage.sync.get('a');")
        val selected = selected(source)
        source.resolve("content.js").writeText(
            "chrome.storage.sync.get('a');\nconsole.log('changed');",
        )
        val before = treeBytes(source)

        assertFailsWith<IllegalStateException> {
            StorageFallbackAdapter().apply(source, selected)
        }

        assertEquals(before, treeBytes(source))
    }

    @Test
    fun `rejects a symlink swapped in after selection`() {
        val source = extension("chrome.storage.sync.get('a');")
        val selected = selected(source)
        val outside = Files.createTempFile("sixbridge-storage-outside-", ".js")
        outside.writeText("chrome.storage.sync.get('outside');")
        val content = source.resolve("content.js")
        Files.delete(content)
        val symlinkCreated = runCatching {
            Files.createSymbolicLink(content, outside)
        }.isSuccess
        if (!symlinkCreated) return

        assertFailsWith<IllegalStateException> {
            StorageFallbackAdapter().apply(source, selected)
        }
        assertEquals("chrome.storage.sync.get('outside');", outside.readText())
    }

    @Test
    fun `does not accept another adapter selection capability`() {
        val source = extension(
            content = """
                document.addEventListener("mouseup", (event) => use(getSelection()));
                chrome.storage.sync.get('a');
            """.trimIndent(),
        )
        val selection = ConversionPlanner().plan(
            MobilePatternAnalyzer().analyze(source, fallbackProfile),
            emptySet(),
        ).selectedAdapters.single {
            it.id == MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE
        }
        val before = treeBytes(source)

        val result = StorageFallbackAdapter().apply(source, selection)

        assertFalse(result.applied)
        assertEquals(before, treeBytes(source))
    }

    private fun selected(source: Path): SelectedAdapter {
        val report = MobilePatternAnalyzer().analyze(source, fallbackProfile)
        return ConversionPlanner().plan(report, emptySet())
            .selectedAdapters
            .single { it.id == MobilePatternAnalyzer.STORAGE_FALLBACK }
    }

    private fun extension(
        content: String,
        background: String? = null,
    ): Path {
        val root = Files.createTempDirectory("sixbridge-storage-")
        val backgroundJson = if (background == null) {
            ""
        } else {
            ""","background":{"service_worker":"background.js"}"""
        }
        root.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Storage fixture","version":"1","permissions":["storage"],""" +
                """"content_scripts":[{"matches":["<all_urls>"],"js":["content.js"]}]""" +
                "$backgroundJson}",
        )
        root.resolve("content.js").writeText(content)
        if (background != null) root.resolve("background.js").writeText(background)
        return root
    }

    private fun profile(
        supportedFeatures: Set<String> = emptySet(),
        unavailableFeatures: Set<String> = emptySet(),
    ) = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = setOf("runtime", "storage"),
        supportedFeatures = supportedFeatures,
        unavailableFeatures = unavailableFeatures,
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    )

    private fun treeBytes(root: Path): Map<String, List<Byte>> =
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile)
                .sorted()
                .toList()
                .associate {
                    root.relativize(it).toString() to
                        it.readBytes().map(Byte::toByte)
                }
        }
}
