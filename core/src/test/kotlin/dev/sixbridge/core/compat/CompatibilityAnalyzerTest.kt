package dev.sixbridge.core.compat

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompatibilityAnalyzerTest {
    private val profile = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = setOf(
            "action",
            "contextMenus",
            "offscreen",
            "runtime",
            "storage",
            "tabs",
            "webRequest",
        ),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    )

    @Test
    fun `classifies a supported MV3 extension as compatible`() {
        val source = Files.createTempDirectory("six-compatible")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Context helper",
              "version": "1.0.0",
              "permissions": ["contextMenus", "storage"],
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            "chrome.contextMenus.create({id:'x'}); chrome.storage.local.get('x');",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertEquals(setOf("contextMenus", "storage"), report.usedApis)
        assertTrue(report.unsupportedApis.isEmpty())
        assertEquals(false, report.manifestKeyPresent)
    }

    @Test
    fun `reports MediaNab unsupported APIs as partial without hiding supported APIs`() {
        val source = Files.createTempDirectory("six-partial")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "MediaNab",
              "version": "1.0.0",
              "permissions": ["downloads", "nativeMessaging", "offscreen", "webRequest"],
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            chrome.downloads.download({});
            chrome.runtime.sendNativeMessage('host', {});
            chrome.offscreen.createDocument({});
            chrome.webRequest.onBeforeRequest.addListener(() => {});
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertEquals(setOf("downloads", "nativeMessaging"), report.unsupportedApis)
        assertTrue("offscreen" in report.usedApis)
        assertTrue("webRequest" in report.usedApis)
    }

    @Test
    fun `blocks non MV3 manifests and treats unvalidated browser profiles as unknown`() {
        val source = Files.createTempDirectory("six-blocked")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":2,"name":"Old","version":"1"}""",
        )

        val blocked = CompatibilityAnalyzer().analyze(source, profile)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"New browser","version":"1"}""",
        )
        val unknown = CompatibilityAnalyzer().analyze(
            source,
            profile.copy(majorVersion = 31),
        )

        assertEquals(CompatibilityStatus.BLOCKED, blocked.status)
        assertEquals(CompatibilityStatus.UNKNOWN, unknown.status)
    }

    @Test
    fun `reports the extension id declared by manifest key`() {
        val source = Files.createTempDirectory("six-key")
        val publicKey = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
            .public
            .encoded
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Keyed",
              "version": "1",
              "key": "${Base64.getEncoder().encodeToString(publicKey)}"
            }
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertTrue(report.manifestKeyPresent)
        assertEquals(32, report.manifestKeyExtensionId?.length)
    }

    @Test
    fun `blocks blank manifest identity fields`() {
        val source = Files.createTempDirectory("six-blank-manifest")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"  ","version":""}""",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.BLOCKED, report.status)
        assertTrue(report.issues.any { it.code == "manifest_identity_invalid" })
    }

    @Test
    fun `blocks missing and escaping declared resources`() {
        val source = Files.createTempDirectory("six-invalid-resources")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Invalid resources",
              "version": "1",
              "background": {"service_worker": "missing.js"},
              "content_scripts": [{"matches": ["<all_urls>"], "js": ["../outside.js"]}],
              "action": {"default_popup": "/absolute.html"},
              "options_ui": {"page": "missing-options.html"}
            }
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.BLOCKED, report.status)
        assertTrue(report.issues.any { it.code == "manifest_resource_invalid" })
    }

    @Test
    fun `blocks a declared resource symlink that resolves outside the extension root`() {
        val source = Files.createTempDirectory("six-symlink-resource")
        val outside = Files.createTempFile("six-outside-resource", ".js")
        Files.createSymbolicLink(source.resolve("background.js"), outside)
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Symlink resource",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.BLOCKED, report.status)
        assertTrue(report.issues.any { it.code == "manifest_resource_invalid" })
    }

    @Test
    fun `accepts existing service worker content action and options resources`() {
        val source = Files.createTempDirectory("six-valid-resources")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Valid resources",
              "version": "1",
              "background": {"service_worker": "background.js"},
              "content_scripts": [{
                "matches": ["<all_urls>"],
                "js": ["content.js"],
                "css": ["content.css"]
              }],
              "action": {"default_popup": "popup.html"},
              "options_page": "options.html"
            }
            """.trimIndent(),
        )
        listOf(
            "background.js",
            "content.js",
            "content.css",
            "popup.html",
            "options.html",
        ).forEach { source.resolve(it).writeText("") }

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
    }

    @Test
    fun `detects bracket and object destructuring API namespaces`() {
        val source = Files.createTempDirectory("six-bracket-apis")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Bracket APIs",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            chrome["downloads"].download({});
            browser['downloads'].search({});
            const { downloads } = chrome;
            downloads.erase({});
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertTrue("downloads" in report.usedApis)
        assertTrue("downloads" in report.unsupportedApis)
    }

    @Test
    fun `marks computed namespace and browser object aliases unknown`() {
        val source = Files.createTempDirectory("six-dynamic-apis")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Dynamic APIs",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            const namespace = "downloads";
            chrome[namespace].download({});
            const rootName = "chrome";
            const api = globalThis[rootName];
            api.downloads.search({});
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.UNKNOWN, report.status)
        assertTrue(report.issues.any { it.code == "api_usage_dynamic" })
    }

    @Test
    fun `detects optional chaining namespaces and static root aliases`() {
        val source = Files.createTempDirectory("six-optional-alias-apis")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Optional and alias APIs",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            chrome?.debugger?.attach({});
            browser?.downloads;
            const api = globalThis.chrome;
            api.debugger.attach({});
            const second = chrome;
            second["downloads"].search({});
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertTrue("debugger" in report.usedApis)
        assertTrue("downloads" in report.usedApis)
        assertEquals(setOf("debugger", "downloads"), report.unsupportedApis)
    }

    @Test
    fun `marks an unprovable indirect root alias unknown`() {
        val source = Files.createTempDirectory("six-indirect-dynamic-alias")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Indirect dynamic alias",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            const root = chrome;
            const api = useBrowser ? root : fallback;
            api.downloads.search({});
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.UNKNOWN, report.status)
        assertTrue(report.issues.any { it.code == "api_usage_dynamic" })
    }

    @Test
    fun `marks unconsumed browser root references unknown`() {
        val source = Files.createTempDirectory("six-unconsumed-browser-root")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Unconsumed browser roots",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            let api;
            api = globalThis.chrome;
            api.debugger.attach({});
            use(browser);
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.UNKNOWN, report.status)
        assertTrue(report.issues.any { it.code == "api_usage_dynamic" })
    }

    @Test
    fun `ignores browser root words in comments and strings`() {
        val source = Files.createTempDirectory("six-comment-string-browser-root")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Comment and string roots",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            // use(chrome)
            /* return browser; */
            const examples = "chrome browser globalThis.chrome";
            const environments = { chrome: true, browser: false };
            const internalUrl = /^(?:chrome:|chrome-extension:|browser:)/;
            chrome.storage.local.get("browser");
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertEquals(setOf("storage"), report.usedApis)
    }

    @Test
    fun `ignores browser words in HTML text and scans inline scripts`() {
        val source = Files.createTempDirectory("six-html-browser-text")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "HTML browser text",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """
            <p>Restart your browser after installation.</p>
            <script>chrome.storage.local.get("value");</script>
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertEquals(setOf("storage"), report.usedApis)
    }

    @Test
    fun `ignores non-runtime test scripts that mock the browser root`() {
        val source = Files.createTempDirectory("six-browser-mock-test-script")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Browser mock test script",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText("chrome.storage.local.get('value');")
        source.resolve("background.test.mjs").writeText(
            """
            const chrome = { storage: { local: {} } };
            runInContext({ chrome });
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertEquals(setOf("storage"), report.usedApis)
    }

    @Test
    fun `scans a large minified string without exhausting the regex stack`() {
        val source = Files.createTempDirectory("six-large-minified-string")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Large minified string",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """const payload="${"a".repeat(600_000)}";chrome.storage.local.get("value");""",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertEquals(setOf("storage"), report.usedApis)
    }
}
