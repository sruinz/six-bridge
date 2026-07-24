package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobilePatternAnalyzerTest {
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

    @Test
    fun `detects a static desktop selection listener as an automatic adapter`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = """
                document.addEventListener("mouseup", (event) => {
                  const selected = getSelection()?.toString();
                  show(selected);
                });
            """.trimIndent(),
        )

        val report = MobilePatternAnalyzer().analyze(source, profile)

        val decision = report.decision("desktop-selection-event-bridge")
        assertNotNull(decision)
        assertEquals(AdapterDisposition.AUTOMATIC, decision.disposition)
        assertEquals(AdapterRisk.SAFE, decision.risk)
        assertEquals("content.js", decision.evidence.single().relativePath)
        assertEquals(1, decision.evidence.single().line)
        assertEquals(ConversionState.AUTOMATIC, report.conversionState)
    }

    @Test
    fun `selection decisions do not depend on extension identity or file name`() {
        val first = extension(
            name = "First extension",
            key = manifestKey(),
            contentFile = "content.js",
            contentSource = selectionSource,
        )
        val second = extension(
            name = "Completely unrelated name",
            key = manifestKey(),
            contentFile = "renamed-handler.mjs",
            contentSource = selectionSource,
        )

        val firstDecision = MobilePatternAnalyzer().analyze(first, profile)
            .decision("desktop-selection-event-bridge")
        val secondDecision = MobilePatternAnalyzer().analyze(second, profile)
            .decision("desktop-selection-event-bridge")

        assertNotNull(firstDecision)
        assertNotNull(secondDecision)
        assertEquals(firstDecision.id, secondDecision.id)
        assertEquals(firstDecision.version, secondDecision.version)
        assertEquals(firstDecision.risk, secondDecision.risk)
        assertEquals(firstDecision.disposition, secondDecision.disposition)
        assertEquals(firstDecision.changes, secondDecision.changes)
        assertEquals(firstDecision.remainingLimitations, secondDecision.remainingLimitations)
    }

    @Test
    fun `does not propose a selection bridge when a mobile input listener exists`() {
        listOf("pointerup", "touchend", "selectionchange").forEach { mobileEvent ->
            val source = extension(
                contentFile = "content.js",
                contentSource = """
                    document.addEventListener("mouseup", () => use(getSelection()));
                    document.addEventListener("$mobileEvent", handleMobileInput);
                """.trimIndent(),
            )

            val report = MobilePatternAnalyzer().analyze(source, profile)

            assertNull(report.decision("desktop-selection-event-bridge"))
        }
    }

    @Test
    fun `marks selection bridge unavailable for trusted and mouse state dependencies`() {
        val sensitiveMembers = listOf(
            "isTrusted",
            "buttons",
            "movementX",
            "movementY",
        )
        sensitiveMembers.forEach { member ->
            val source = extension(
                contentFile = "content.js",
                contentSource = """
                    document.addEventListener("mouseup", (event) => {
                      if (event.$member) use(getSelection());
                    });
                """.trimIndent(),
            )

            val decision = MobilePatternAnalyzer().analyze(source, profile)
                .decision("desktop-selection-event-bridge")

            assertNotNull(decision)
            assertEquals(AdapterDisposition.UNAVAILABLE, decision.disposition)
            assertEquals("content.js", decision.evidence.single().relativePath)
            assertEquals(2, decision.evidence.single().line)
            assertTrue(member in decision.evidence.single().detail)
        }
    }

    @Test
    fun `marks selection bridge unavailable for callback event button access`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = """
                document.addEventListener("mouseup", (event) => {
                  if (event.button !== 0) return;
                  use(getSelection());
                });
            """.trimIndent(),
        )

        val decision = MobilePatternAnalyzer().analyze(source, profile)
            .decision("desktop-selection-event-bridge")

        assertNotNull(decision)
        assertEquals(AdapterDisposition.UNAVAILABLE, decision.disposition)
        assertEquals(2, decision.evidence.single().line)
        assertTrue("button" in decision.evidence.single().detail)
    }

    @Test
    fun `does not bind unrelated toolbar button member to selection event`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = """
                document.addEventListener("mouseup", (event) => {
                  toolbar.button.disabled = true;
                  use(getSelection());
                });
            """.trimIndent(),
        )

        val decision = MobilePatternAnalyzer().analyze(source, profile)
            .decision("desktop-selection-event-bridge")

        assertNotNull(decision)
        assertEquals(AdapterDisposition.AUTOMATIC, decision.disposition)
    }

    @Test
    fun `finds mouse state access in a named function handler`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = """
                document.addEventListener("mouseup", handleSelection);
                function handleSelection(event) {
                  if (event.button !== 0) return;
                  use(getSelection());
                }
            """.trimIndent(),
        )

        val decision = MobilePatternAnalyzer().analyze(source, profile)
            .decision("desktop-selection-event-bridge")

        assertNotNull(decision)
        assertEquals(AdapterDisposition.UNAVAILABLE, decision.disposition)
        assertEquals(3, decision.evidence.single().line)
    }

    @Test
    fun `finds mouse state access in async arrow handlers`() {
        listOf(
            """async (event) => { if (event.isTrusted) use(getSelection()); }""",
            """async event => { if (event.buttons) use(getSelection()); }""",
        ).forEach { callback ->
            val source = extension(
                contentFile = "content.js",
                contentSource =
                    """document.addEventListener("mouseup", $callback);""",
            )

            val decision = MobilePatternAnalyzer().analyze(source, profile)
                .decision("desktop-selection-event-bridge")

            assertNotNull(decision)
            assertEquals(AdapterDisposition.UNAVAILABLE, decision.disposition)
        }
    }

    @Test
    fun `expression callback excludes third argument from event member evidence`() {
        val source = extension(
            contentFile = "content.js",
            contentSource =
                """document.addEventListener("mouseup", """ +
                    """event => use(getSelection()), {capture: event.button});""",
        )

        val decision = MobilePatternAnalyzer().analyze(source, profile)
            .decision("desktop-selection-event-bridge")

        assertNotNull(decision)
        assertEquals(AdapterDisposition.AUTOMATIC, decision.disposition)
    }

    @Test
    fun `unresolved callback expressions fail closed`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = """
                document.addEventListener("mouseup", handlers.selection);
                use(getSelection());
            """.trimIndent(),
        )

        val decision = MobilePatternAnalyzer().analyze(source, profile)
            .decision("desktop-selection-event-bridge")

        assertNotNull(decision)
        assertEquals(AdapterDisposition.UNAVAILABLE, decision.disposition)
        assertTrue("callback" in decision.evidence.single().detail)
    }

    @Test
    fun `compound callback expressions fail closed`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = """
                function safe(event) {
                  use(getSelection());
                }
                function unsafe(event) {
                  if (event.button !== 0) return;
                  use(getSelection());
                }
                document.addEventListener("mouseup", safe && unsafe);
            """.trimIndent(),
        )

        val decision = MobilePatternAnalyzer().analyze(source, profile)
            .decision("desktop-selection-event-bridge")

        assertNotNull(decision)
        assertEquals(AdapterDisposition.UNAVAILABLE, decision.disposition)
        assertTrue("callback" in decision.evidence.single().detail)
    }

    @Test
    fun `requires approval for an inaccessible action popup with an existing content script`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = "console.log('content');",
            popup = "popup.html",
        )
        source.resolve("popup.html").writeText("<script src=\"popup.js\"></script>")
        source.resolve("popup.js").writeText("chrome.runtime.getURL('popup.html');")

        val report = MobilePatternAnalyzer().analyze(source, profile)

        val decision = report.decision("action-popup-entry")
        assertNotNull(decision)
        assertEquals(AdapterDisposition.REQUIRES_APPROVAL, decision.disposition)
        assertEquals(AdapterRisk.BEHAVIOR_CHANGE, decision.risk)
        assertEquals("manifest.json", decision.evidence.single().relativePath)
        assertEquals(ConversionState.REQUIRES_APPROVAL, report.conversionState)
    }

    @Test
    fun `does not propose an action popup entry when user accessibility is supported`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = "console.log('content');",
            popup = "popup.html",
        )
        source.resolve("popup.html").writeText("<p>popup</p>")

        val report = MobilePatternAnalyzer().analyze(
            source,
            profile.copy(supportedFeatures = setOf("action.popup.user-accessible")),
        )

        assertNull(report.decision("action-popup-entry"))
    }

    @Test
    fun `detects static storage sync use only with complete fallback evidence`() {
        listOf("chrome", "browser").forEach { root ->
            val source = extension(
                contentFile = "content.js",
                contentSource = "$root.storage.sync.get('value');",
            )
            val fallbackProfile = profile.copy(
                supportedFeatures = setOf("storage.local"),
                unavailableFeatures = setOf("storage.sync"),
            )

            val report = MobilePatternAnalyzer().analyze(source, fallbackProfile)

            val decision = report.decision("storage-fallback")
            assertNotNull(decision)
            assertEquals(AdapterDisposition.AUTOMATIC, decision.disposition)
            val tokenEvidence = decision.evidence.single {
                it.relativePath == "content.js"
            }
            assertEquals(1, tokenEvidence.line)
            assertFalse(report.issues.any { it.code == "storage_area_unknown" })
        }
    }

    @Test
    fun `unavailable conversion outranks approval and automatic decisions`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = """
                document.addEventListener("mouseup", (event) => {
                  if (event.isTrusted) use(getSelection());
                });
                chrome.storage.sync.get("value");
            """.trimIndent(),
            popup = "popup.html",
        )
        source.resolve("popup.html").writeText("<p>popup</p>")
        val fallbackProfile = profile.copy(
            supportedFeatures = setOf("storage.local"),
            unavailableFeatures = setOf("storage.sync"),
        )

        val report = MobilePatternAnalyzer().analyze(source, fallbackProfile)

        assertTrue(
            report.adapterDecisions.any {
                it.disposition == AdapterDisposition.UNAVAILABLE
            },
        )
        assertTrue(
            report.adapterDecisions.any {
                it.disposition == AdapterDisposition.REQUIRES_APPROVAL
            },
        )
        assertTrue(
            report.adapterDecisions.any {
                it.disposition == AdapterDisposition.AUTOMATIC
            },
        )
        assertEquals(ConversionState.UNAVAILABLE, report.conversionState)
    }

    @Test
    fun `approval outranks automatic while blocked and unknown compatibility remain unchanged`() {
        val approvalAndAutomatic = extension(
            contentFile = "content.js",
            contentSource = "chrome.storage.sync.get('value');",
            popup = "popup.html",
        )
        approvalAndAutomatic.resolve("popup.html").writeText("<p>popup</p>")
        val fallbackProfile = profile.copy(
            supportedFeatures = setOf("storage.local"),
            unavailableFeatures = setOf("storage.sync"),
        )
        val approvalReport = MobilePatternAnalyzer().analyze(
            approvalAndAutomatic,
            fallbackProfile,
        )
        val unknown = extension(
            contentFile = "content.js",
            contentSource = "import(moduleName); chrome.storage.sync.get('value');",
        )
        val blocked = extension(
            contentFile = "content.js",
            contentSource = "console.log('blocked manifest');",
        )
        blocked.resolve("manifest.json").writeText(
            blocked.resolve("manifest.json").readText()
                .replace("\"manifest_version\": 3", "\"manifest_version\": 2"),
        )

        assertEquals(ConversionState.REQUIRES_APPROVAL, approvalReport.conversionState)
        assertEquals(
            CompatibilityStatus.UNKNOWN,
            MobilePatternAnalyzer().analyze(unknown, fallbackProfile).status,
        )
        assertEquals(
            CompatibilityStatus.BLOCKED,
            MobilePatternAnalyzer().analyze(blocked, fallbackProfile).status,
        )
    }

    @Test
    fun `reports unknown storage area evidence without proposing fallback`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = "chrome.storage.sync.get('value');",
        )

        val report = MobilePatternAnalyzer().analyze(source, profile)

        assertNull(report.decision("storage-fallback"))
        assertTrue(report.issues.any { it.code == "storage_area_unknown" })
    }

    @Test
    fun `dynamic and obfuscated usage keeps compatibility unknown and creates no unsafe decision`() {
        val dynamicApi = extension(
            contentFile = "content.js",
            contentSource = "chrome[namespace].sync.get('value');",
        )
        val dynamicImport = extension(
            contentFile = "content.js",
            contentSource = """
                import(moduleName);
                document.addEventListener("mouseup", () => use(getSelection()));
            """.trimIndent(),
        )
        val obfuscatedEvent = extension(
            contentFile = "content.js",
            contentSource = """
                const eventName = "mouse" + "up";
                document.addEventListener(eventName, () => use(getSelection()));
            """.trimIndent(),
        )

        val dynamicApiReport = MobilePatternAnalyzer().analyze(dynamicApi, profile)
        val dynamicImportReport = MobilePatternAnalyzer().analyze(dynamicImport, profile)
        val obfuscatedEventReport = MobilePatternAnalyzer().analyze(obfuscatedEvent, profile)

        assertEquals(CompatibilityStatus.UNKNOWN, dynamicApiReport.status)
        assertEquals(CompatibilityStatus.UNKNOWN, dynamicImportReport.status)
        assertNull(dynamicApiReport.decision("storage-fallback"))
        assertEquals(
            CompatibilityStatus.COMPATIBLE,
            obfuscatedEventReport.status,
        )
        assertNull(obfuscatedEventReport.decision("desktop-selection-event-bridge"))
    }

    @Test
    fun `ignores unreachable samples tests and node modules as pattern evidence`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = "console.log('reachable');",
        )
        source.resolve("sample.js").writeText(selectionSource)
        source.resolve("content.test.js").writeText("chrome.storage.sync.get('value');")
        Files.createDirectories(source.resolve("node_modules/pkg"))
        source.resolve("node_modules/pkg/index.js").writeText(selectionSource)

        val report = MobilePatternAnalyzer().analyze(
            source,
            profile.copy(
                supportedFeatures = setOf("storage.local"),
                unavailableFeatures = setOf("storage.sync"),
            ),
        )

        assertTrue(report.adapterDecisions.isEmpty())
        assertFalse(report.issues.any { it.code == "storage_area_unknown" })
    }

    @Test
    fun `ignores pattern words in strings templates regexes and comments`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = """
                const example = 'document.addEventListener("mouseup", getSelection)';
                const template = `chrome.storage.sync and event.isTrusted`;
                const matcher = /chrome\.storage\.sync|pointerup/;
                // document.addEventListener("mouseup", () => getSelection());
                /* browser.storage.sync.get("value"); */
            """.trimIndent(),
        )

        val report = MobilePatternAnalyzer().analyze(source, profile)

        assertTrue(report.adapterDecisions.isEmpty())
        assertFalse(report.issues.any { it.code == "storage_area_unknown" })
    }

    @Test
    fun `ignores storage pattern regex after a completed block`() {
        val source = extension(
            contentFile = "content.js",
            contentSource = "if (ready) {} /chrome.storage.sync/.test(value);",
        )

        val report = MobilePatternAnalyzer().analyze(source, fallbackProfile)

        assertNull(report.decision("storage-fallback"))
        assertTrue(report.usedApis.isEmpty())
    }

    @Test
    fun `ignores storage pattern regex after a function declaration`() {
        val source = extension(
            contentFile = "content.js",
            contentSource =
                "function validate() {} /browser.storage.sync/.test(value);",
        )

        val report = MobilePatternAnalyzer().analyze(source, fallbackProfile)

        assertNull(report.decision("storage-fallback"))
        assertTrue(report.usedApis.isEmpty())
    }

    @Test
    fun `keeps division after a function expression as executable code`() {
        val source = extension(
            contentFile = "content.js",
            contentSource =
                "const ratio = function value() {} / chrome.storage.sync.value;",
        )

        val report = MobilePatternAnalyzer().analyze(source, fallbackProfile)

        assertNotNull(report.decision("storage-fallback"))
        assertEquals(setOf("storage"), report.usedApis)
    }

    @Test
    fun `ignores storage regex after a statement block`() {
        val source = extension(
            contentFile = "content.js",
            contentSource =
                "{ prepare(); } /chrome.storage.sync/.test(value);",
        )

        val report = MobilePatternAnalyzer().analyze(source, fallbackProfile)

        assertNull(report.decision("storage-fallback"))
        assertTrue(report.usedApis.isEmpty())
    }

    @Test
    fun `ignores storage regex after a class declaration`() {
        val source = extension(
            contentFile = "content.js",
            contentSource =
                "class Example {} /browser.storage.sync/.test(value);",
        )

        val report = MobilePatternAnalyzer().analyze(source, fallbackProfile)

        assertNull(report.decision("storage-fallback"))
        assertTrue(report.usedApis.isEmpty())
    }

    @Test
    fun `keeps division after a class expression as executable code`() {
        val source = extension(
            contentFile = "content.js",
            contentSource =
                "const Constructor = class {} / chrome.storage.sync.value;",
        )

        val report = MobilePatternAnalyzer().analyze(source, fallbackProfile)

        assertNotNull(report.decision("storage-fallback"))
        assertEquals(setOf("storage"), report.usedApis)
    }

    private fun extension(
        name: String = "Synthetic extension",
        key: String? = null,
        contentFile: String,
        contentSource: String,
        popup: String? = null,
    ): Path {
        val root = Files.createTempDirectory("six-mobile-pattern")
        val keyMember = key?.let { """, "key": "$it"""" }.orEmpty()
        val popupMember = popup?.let { """, "action": {"default_popup": "$it"}""" }.orEmpty()
        root.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "$name",
              "version": "1"$keyMember$popupMember,
              "content_scripts": [{
                "matches": ["<all_urls>"],
                "js": ["$contentFile"]
              }]
            }
            """.trimIndent(),
        )
        root.resolve(contentFile).writeText(contentSource)
        return root
    }

    private fun manifestKey(): String {
        val publicKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(1024) }
            .generateKeyPair()
            .public
            .encoded
        return Base64.getEncoder().encodeToString(publicKey)
    }

    private fun dev.sixbridge.core.compat.CompatibilityReport.decision(
        id: String,
    ): AdapterDecision? = adapterDecisions.singleOrNull { it.id == id }

    private val selectionSource =
        """document.addEventListener("mouseup", () => use(getSelection()));"""

    private val fallbackProfile = profile.copy(
        supportedFeatures = setOf("storage.local"),
        unavailableFeatures = setOf("storage.sync"),
    )
}
