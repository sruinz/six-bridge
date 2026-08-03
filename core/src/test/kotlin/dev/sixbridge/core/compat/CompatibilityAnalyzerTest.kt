package dev.sixbridge.core.compat

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.ApiCapabilityEvidence
import dev.sixbridge.core.profile.ApiCapabilityKey
import dev.sixbridge.core.profile.ApiCapabilityStatus
import dev.sixbridge.core.profile.ApiExecutionContext
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.Base64
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `사용 member를 capability 상태별로 분리한다`() {
        val source = Files.createTempDirectory("six-member-capability")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Member capability",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            chrome.tabs.query({});
            chrome["userScripts"]["register"]([]);
            chrome.downloads["download"]({});
            """.trimIndent(),
        )
        val catalogSha256 = "a".repeat(64)
        fun capability(
            namespace: String,
            member: String,
            status: ApiCapabilityStatus,
        ) = ApiCapabilityEvidence(
            key = ApiCapabilityKey(
                namespace,
                member,
                ApiExecutionContext.SERVICE_WORKER,
            ),
            status = status,
            permissions = emptySet(),
            manifestKeys = emptySet(),
            detail = "test",
            probeId = "$namespace.$member",
            probeCatalogSha256 = catalogSha256,
        )
        val capabilityProfile = profile.copy(
            supportedApis = setOf("tabs"),
            detectedApis = setOf("tabs", "userScripts", "downloads"),
            apiCapabilities = listOf(
                capability("tabs", "query", ApiCapabilityStatus.VERIFIED),
                capability("userScripts", "register", ApiCapabilityStatus.EXPOSED),
                capability("downloads", "download", ApiCapabilityStatus.FAILED),
            ),
        )

        val report = CompatibilityAnalyzer().analyzeStable(
            source,
            capabilityProfile,
            sourceSha256 = null,
        )

        assertEquals(setOf("tabs.query"), report.verifiedApis)
        assertEquals(setOf("userScripts.register"), report.unverifiedApis)
        assertEquals(setOf("downloads.download"), report.unavailableApis)
        assertEquals(report.unavailableApis, report.unsupportedApis)
        assertEquals(CompatibilityStatus.PARTIAL, report.status)
    }

    @Test
    fun `capability가 없는 member는 namespace 증거 수준에 따라 미검증 또는 사용 불가다`() {
        val source = Files.createTempDirectory("six-member-fallback")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Member fallback",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            chrome.tabs.unknownMember();
            chrome.history.search({});
            chrome.bookmarks.get("1");
            """.trimIndent(),
        )
        val capabilityProfile = profile.copy(
            supportedApis = setOf("tabs"),
            detectedApis = setOf("tabs", "history"),
            apiCapabilities = listOf(
                ApiCapabilityEvidence(
                    key = ApiCapabilityKey(
                        "tabs",
                        "query",
                        ApiExecutionContext.SERVICE_WORKER,
                    ),
                    status = ApiCapabilityStatus.VERIFIED,
                    permissions = emptySet(),
                    manifestKeys = emptySet(),
                    detail = "test",
                    probeId = "tabs.query",
                    probeCatalogSha256 = "a".repeat(64),
                ),
            ),
        )

        val report = CompatibilityAnalyzer().analyzeStable(
            source,
            capabilityProfile,
            sourceSha256 = null,
        )

        assertEquals(
            setOf("history.search", "tabs.unknownMember"),
            report.unverifiedApis,
        )
        assertEquals(setOf("bookmarks.get"), report.unavailableApis)
        assertEquals(CompatibilityStatus.PARTIAL, report.status)
    }

    @Test
    fun `분석 보고서는 지역화된 manifest 이름과 locale issue를 포함한다`() {
        val source = Files.createTempDirectory("six-localized-name")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"__MSG_extName__","version":"1","default_locale":"en"}""",
        )
        source.resolve("_locales").resolve("en").toFile().mkdirs()
        source.resolve("_locales/en/messages.json").writeText(
            """{"extName":{"message":"Localized extension"}}""",
        )

        val resolved = CompatibilityAnalyzer().analyzeStable(source, profile, sourceSha256 = null)

        assertEquals("Localized extension", resolved.extensionName)
        assertTrue(resolved.issues.isEmpty())

        source.resolve("_locales/en/messages.json").toFile().delete()
        val missing = CompatibilityAnalyzer().analyzeStable(source, profile, sourceSha256 = null)

        assertEquals("__MSG_extName__", missing.extensionName)
        assertEquals("manifest_name_locale_missing", missing.issues.single().code)
        assertEquals(false, missing.issues.single().blocking)
    }

    @Test
    fun `모바일 분석 예산 초과는 이름과 권한을 보존한 UNKNOWN이다`() {
        val source = Files.createTempDirectory("six-budget")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Large","version":"1","permissions":["storage"]}""",
        )
        Files.write(source.resolve("bundle.js"), ByteArray(2 * 1024 * 1024 + 1))

        val report = CompatibilityAnalyzer(
            AnalysisControl(AnalysisSafetyPolicy.mobile),
        ).analyze(source, profile)

        assertEquals(CompatibilityStatus.UNKNOWN, report.status)
        assertEquals("Large", report.extensionName)
        assertEquals(setOf("storage"), report.declaredPermissions)
        assertTrue(report.usedApis.isEmpty())
        assertEquals("analysis_budget_exceeded", report.issues.single().code)
    }

    @Test
    fun `stable 분석에서 모바일 예산 초과 preflight는 UNKNOWN 계약을 보존한다`() {
        val source = Files.createTempDirectory("six-budget-stable")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Large","version":"1","permissions":["storage"]}""",
        )
        Files.write(source.resolve("bundle.js"), ByteArray(2 * 1024 * 1024 + 1))

        val report = CompatibilityAnalyzer(
            AnalysisControl(AnalysisSafetyPolicy.mobile),
        ).analyzeStable(source, profile, sourceSha256 = null)

        assertEquals(CompatibilityStatus.UNKNOWN, report.status)
        assertEquals("Large", report.extensionName)
        assertEquals(setOf("storage"), report.declaredPermissions)
        assertTrue(report.usedApis.isEmpty())
        assertEquals("analysis_budget_exceeded", report.issues.single().code)
    }

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
    fun `canonical archive rejects a declared resource symlink`() {
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

        assertFailsWith<IllegalArgumentException> {
            CompatibilityAnalyzer().analyze(source, profile)
        }
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
    fun `resolves immutable globalThis destructured API aliases`() {
        val source = Files.createTempDirectory("six-globalthis-destructured-apis")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Immutable global aliases",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            const root = globalThis;
            const { chrome: C, browser: B } = root;
            C.userScripts.register([]);
            B.scripting.executeScript({});
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyzeStable(
            source,
            profile,
            sourceSha256 = null,
        )

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertEquals(setOf("scripting", "userScripts"), report.usedApis)
        assertEquals(setOf("scripting", "userScripts"), report.unsupportedApis)
        assertTrue(report.issues.none { it.code == "api_usage_dynamic" })
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
    fun `scans manifest referenced runtime scripts even when names contain test`() {
        val source = Files.createTempDirectory("six-runtime-test-named-scripts")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Runtime test-named scripts",
              "version": "1",
              "background": {"service_worker": "worker.test.js"},
              "content_scripts": [{
                "matches": ["<all_urls>"],
                "js": ["content.test.js"]
              }]
            }
            """.trimIndent(),
        )
        source.resolve("worker.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )
        source.resolve("content.test.js").writeText(
            "chrome.downloads.download({});",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertTrue("webstorePrivate" in report.usedApis)
        assertTrue("downloads" in report.usedApis)
    }

    @Test
    fun `does not treat regex character class slash star as a block comment`() {
        val source = Files.createTempDirectory("six-regex-comment-boundary")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Regex comment boundary",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            const slashOrStar = /[/*]/;
            chrome.webstorePrivate.install();
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertTrue("webstorePrivate" in report.usedApis)
    }

    @Test
    fun `scans template expressions while ignoring template text`() {
        val source = Files.createTempDirectory("six-template-expression-root")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Template expression root",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        val background = source.resolve("background.js")
        background.writeText(
            """const message = `chrome.webstorePrivate browser`;""",
        )

        val textOnlyReport = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, textOnlyReport.status)
        assertTrue(textOnlyReport.usedApis.isEmpty())

        background.writeText(
            """const message = `chrome browser ${'$'}{use(chrome)}`;""",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.UNKNOWN, report.status)
        assertTrue(report.issues.any { it.code == "api_usage_dynamic" })
    }

    @Test
    fun `scans test named scripts loaded by a runtime popup`() {
        val source = Files.createTempDirectory("six-popup-script-dependency")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Popup script dependency",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """<script src="popup.test.js"></script>""",
        )
        source.resolve("popup.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertTrue("webstorePrivate" in report.usedApis)
    }

    @Test
    fun `scans test named scripts loaded by importScripts`() {
        val source = Files.createTempDirectory("six-import-scripts-dependency")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "ImportScripts dependency",
              "version": "1",
              "background": {"service_worker": "worker.js"}
            }
            """.trimIndent(),
        )
        source.resolve("worker.js").writeText(
            """importScripts("helper.test.js");""",
        )
        source.resolve("helper.test.js").writeText(
            "chrome.downloads.download({});",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertTrue("downloads" in report.usedApis)
    }

    @Test
    fun `scans test named static import and export dependencies`() {
        val source = Files.createTempDirectory("six-static-module-dependencies")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Static module dependencies",
              "version": "1",
              "background": {
                "service_worker": "worker.js",
                "type": "module"
              }
            }
            """.trimIndent(),
        )
        source.resolve("worker.js").writeText(
            """
            import "./imported.test.js";
            export { helper } from "./exported.test.js";
            """.trimIndent(),
        )
        source.resolve("imported.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )
        source.resolve("exported.test.js").writeText(
            "export const helper = () => chrome.downloads.download({});",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertTrue("webstorePrivate" in report.usedApis)
        assertTrue("downloads" in report.usedApis)
    }

    @Test
    fun `recognizes a regex literal after a control condition`() {
        val source = Files.createTempDirectory("six-control-condition-regex")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Control condition regex",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            if (ready) /[/*]/.test(value);
            chrome.webstorePrivate.install();
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertTrue("webstorePrivate" in report.usedApis)
    }

    @Test
    fun `ignores API calls in strings while scanning real calls`() {
        val source = Files.createTempDirectory("six-api-call-string")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "API call string",
              "version": "1",
              "background": {"service_worker": "background.js"}
            }
            """.trimIndent(),
        )
        source.resolve("background.js").writeText(
            """
            const example = "chrome.webstorePrivate.install()";
            chrome.storage.local.get("value");
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertEquals(setOf("storage"), report.usedApis)
    }

    @Test
    fun `scans local test scripts in multiline importScripts calls`() {
        val source = Files.createTempDirectory("six-multiline-import-scripts")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Multiline importScripts",
              "version": "1",
              "background": {"service_worker": "worker.js"}
            }
            """.trimIndent(),
        )
        source.resolve("worker.js").writeText(
            """
            importScripts(
              /* first runtime dependency */
              "./first.test.js",
              /* second runtime dependency */
              "./second.test.js",
            );
            """.trimIndent(),
        )
        source.resolve("first.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )
        source.resolve("second.test.js").writeText(
            "chrome.downloads.download({});",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertEquals(setOf("downloads", "webstorePrivate"), report.unsupportedApis)
        assertTrue("downloads" in report.usedApis)
        assertTrue("webstorePrivate" in report.usedApis)
    }

    @Test
    fun `scans multiline static import and export dependencies`() {
        val source = Files.createTempDirectory("six-multiline-static-modules")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Multiline static modules",
              "version": "1",
              "background": {
                "service_worker": "worker.js",
                "type": "module"
              }
            }
            """.trimIndent(),
        )
        source.resolve("worker.js").writeText(
            """
            import /* imported bindings */ {
              imported,
            } from
              /* imported module */
              "./imported.test.js";
            export /* exported bindings */ {
              exported,
            } from
              /* exported module */
              "./exported.test.js";
            """.trimIndent(),
        )
        source.resolve("imported.test.js").writeText(
            "export const imported = chrome.webstorePrivate;",
        )
        source.resolve("exported.test.js").writeText(
            "export const exported = chrome.downloads;",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertEquals(setOf("downloads", "webstorePrivate"), report.unsupportedApis)
        assertTrue("downloads" in report.usedApis)
        assertTrue("webstorePrivate" in report.usedApis)
    }

    @Test
    fun `ignores script tags inside HTML comments`() {
        val source = Files.createTempDirectory("six-commented-html-script")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Commented HTML script",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """
            <!-- <script src="ignored.test.js"></script> -->
            <script>chrome.storage.local.get("value");</script>
            """.trimIndent(),
        )
        source.resolve("ignored.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertEquals(setOf("storage"), report.usedApis)
        assertTrue(report.unsupportedApis.isEmpty())
    }

    @Test
    fun `scans unquoted script src attributes`() {
        val source = Files.createTempDirectory("six-unquoted-script-src")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Unquoted script src",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """<ScRiPt SRC = popup.test.js></sCrIpT>""",
        )
        source.resolve("popup.test.js").writeText(
            "chrome.downloads.download({});",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertEquals(setOf("downloads"), report.unsupportedApis)
        assertTrue("downloads" in report.usedApis)
    }

    @Test
    fun `scans only inline script bodies in HTML`() {
        val source = Files.createTempDirectory("six-inline-script-only")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Inline script only",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """
            <pre>chrome.webstorePrivate.install()</pre>
            <script>chrome.storage.local.get("value");</script>
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertEquals(setOf("storage"), report.usedApis)
        assertTrue(report.unsupportedApis.isEmpty())
    }

    @Test
    fun `excludes unsafe runtime dependency paths`() {
        val container = Files.createTempDirectory("six-unsafe-dependencies")
        val source = Files.createDirectory(container.resolve("extension"))
        val outside = container.resolve("outside.test.js")
        outside.writeText("chrome.webstorePrivate.install();")
        source.resolve("query.test.js").writeText("chrome.downloads.download({});")
        source.resolve("fragment.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Unsafe dependencies",
              "version": "1",
              "background": {
                "service_worker": "worker.js",
                "type": "module"
              }
            }
            """.trimIndent(),
        )
        source.resolve("worker.js").writeText(
            """
            import "../outside.test.js";
            import "./link.test.js";
            import "https://example.com/remote.test.js";
            import "./query.test.js?cache=1";
            import "./fragment.test.js#part";
            chrome.storage.local.get("value");
            """.trimIndent(),
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.UNKNOWN, report.status)
        assertEquals(setOf("storage"), report.usedApis)
        assertTrue(report.unsupportedApis.isEmpty())
        assertTrue(report.issues.any { it.code == "api_usage_dynamic" })
    }

    @Test
    fun `scans dependencies imported by inline module scripts`() {
        val source = Files.createTempDirectory("six-inline-module-dependency")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Inline module dependency",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """
            <script type="module">
              import "./helper.test.js";
            </script>
            """.trimIndent(),
        )
        source.resolve("helper.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertEquals(setOf("webstorePrivate"), report.unsupportedApis)
        assertTrue("webstorePrivate" in report.usedApis)
    }

    @Test
    fun `decodes JavaScript escapes in static dependency paths`() {
        val source = Files.createTempDirectory("six-js-escaped-dependency")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "JavaScript escaped dependency",
              "version": "1",
              "background": {
                "service_worker": "worker.js",
                "type": "module"
              }
            }
            """.trimIndent(),
        )
        source.resolve("worker.js").writeText(
            "import \"./helper" + "\\u002e" + "test.js\";",
        )
        source.resolve("helper.test.js").writeText(
            "chrome.downloads.download({});",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertEquals(setOf("downloads"), report.unsupportedApis)
        assertTrue("downloads" in report.usedApis)
    }

    @Test
    fun `decodes HTML entities in script source paths`() {
        val source = Files.createTempDirectory("six-html-entity-dependency")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "HTML entity dependency",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """<script src="helper&#46;test.js"></script>""",
        )
        source.resolve("helper.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertEquals(setOf("webstorePrivate"), report.unsupportedApis)
        assertTrue("webstorePrivate" in report.usedApis)
    }

    @Test
    fun `ignores non executable script types`() {
        val source = Files.createTempDirectory("six-non-executable-script-types")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Non executable script types",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """
            <script type="text/x-template">
              chrome.webstorePrivate.install();
            </script>
            <script type="application/json" src="ignored.test.js"></script>
            <script type="importmap">
              {"imports":{"chrome.webstorePrivate":"./ignored.test.js"}}
            </script>
            <script>chrome.storage.local.get("value");</script>
            """.trimIndent(),
        )
        source.resolve("ignored.test.js").writeText(
            "chrome.downloads.download({});",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertEquals(setOf("storage"), report.usedApis)
        assertTrue(report.unsupportedApis.isEmpty())
    }

    @Test
    fun `keeps executable script records and HTML context behavior`() {
        val source = Files.createTempDirectory("six-executable-script-records")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Executable script records",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """
            <!-- <script src="ignored.test.js"></script> -->
            <script src="classic.test.js"></script>
            <script type="text/javascript" src = legacy.test.js></script>
            <script type="module" src="module.test.js"></script>
            <script>chrome.storage.local.get("value");</script>
            """.trimIndent(),
        )
        source.resolve("ignored.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )
        source.resolve("classic.test.js").writeText(
            "chrome.runtime.getManifest();",
        )
        source.resolve("legacy.test.js").writeText(
            "chrome.tabs.query({});",
        )
        source.resolve("module.test.js").writeText(
            "chrome.downloads.download({});",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.PARTIAL, report.status)
        assertEquals(setOf("downloads"), report.unsupportedApis)
        assertEquals(setOf("downloads", "runtime", "storage", "tabs"), report.usedApis)
        assertTrue("webstorePrivate" !in report.usedApis)
    }

    @Test
    fun `fails closed for undecodable static dependency paths`() {
        val source = Files.createTempDirectory("six-undecodable-dependencies")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Undecodable dependencies",
              "version": "1",
              "background": {
                "service_worker": "worker.js",
                "type": "module"
              },
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        val worker = source.resolve("worker.js")
        worker.writeText(
            "import \"./hidden" + "\\xZZ" + ".test.js\";" +
                "chrome.storage.local.get(\"value\");",
        )
        val popup = source.resolve("popup.html")
        popup.writeText("<script></script>")
        source.resolve("hidden.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )

        val invalidJavaScript = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.UNKNOWN, invalidJavaScript.status)
        assertEquals(setOf("storage"), invalidJavaScript.usedApis)
        assertTrue(invalidJavaScript.unsupportedApis.isEmpty())
        assertTrue(invalidJavaScript.issues.any { it.code == "api_usage_dynamic" })

        worker.writeText("chrome.storage.local.get(\"value\");")
        popup.writeText("""<script src="hidden&unknown;.test.js"></script>""")

        val invalidHtml = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.UNKNOWN, invalidHtml.status)
        assertEquals(setOf("storage"), invalidHtml.usedApis)
        assertTrue(invalidHtml.unsupportedApis.isEmpty())
        assertTrue(invalidHtml.issues.any { it.code == "api_usage_dynamic" })
    }

    @Test
    fun `fails closed when import map and module scripts share runtime HTML`() {
        val source = Files.createTempDirectory("six-import-map-module")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Import map module",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """
            <script type="importmap">
              {"imports":{"mapped":"./mapped.test.js"}}
            </script>
            <script type="module">
              import "mapped";
              chrome.storage.local.get("value");
            </script>
            """.trimIndent(),
        )
        source.resolve("mapped.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.UNKNOWN, report.status)
        assertEquals(setOf("storage"), report.usedApis)
        assertTrue(report.unsupportedApis.isEmpty())
        assertTrue(report.issues.any { it.code == "api_usage_dynamic" })
    }

    @Test
    fun `ignores import map without module scripts`() {
        val source = Files.createTempDirectory("six-import-map-only")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Import map only",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """
            <script type="importmap">
              {"imports":{"chrome.webstorePrivate":"./ignored.test.js"}}
            </script>
            <script>chrome.storage.local.get("value");</script>
            """.trimIndent(),
        )
        source.resolve("ignored.test.js").writeText(
            "chrome.downloads.download({});",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertEquals(setOf("storage"), report.usedApis)
        assertTrue(report.unsupportedApis.isEmpty())
    }

    @Test
    fun `keeps scripts and relative inline modules without import maps`() {
        val source = Files.createTempDirectory("six-no-import-map")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "No import map",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """
            <script>chrome.storage.local.get("value");</script>
            <script type="module">import "./relative.test.js";</script>
            """.trimIndent(),
        )
        source.resolve("relative.test.js").writeText(
            "chrome.tabs.query({});",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.COMPATIBLE, report.status)
        assertEquals(setOf("storage", "tabs"), report.usedApis)
        assertTrue(report.unsupportedApis.isEmpty())
    }

    @Test
    fun `applies runtime path boundaries after decoding`() {
        val container = Files.createTempDirectory("six-decoded-boundaries")
        val source = Files.createDirectory(container.resolve("extension"))
        container.resolve("outside.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Decoded boundaries",
              "version": "1",
              "background": {
                "service_worker": "worker.js",
                "type": "module"
              },
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("worker.js").writeText(
            "import \".." + "\\u002f" + "outside.test.js\";\n" +
                "import \"https" + "\\u003a" + "//example.com/remote.test.js\";\n" +
                "import \"." + "\\u005c" + "backslash.test.js\";\n" +
                "import \"./query.test.js" + "\\u003f" + "cache=1\";\n" +
                "import \"./fragment.test.js" + "\\u0023" + "part\";\n" +
                "chrome.storage.local.get(\"value\");",
        )
        source.resolve("popup.html").writeText(
            """
            <script src="..&#47;outside.test.js"></script>
            <script src="https&#58;//example.com/remote.test.js"></script>
            <script src=".&#92;backslash.test.js"></script>
            <script src="./query.test.js&#63;cache=1"></script>
            <script src="./fragment.test.js&#35;part"></script>
            """.trimIndent(),
        )
        source.resolve("backslash.test.js").writeText(
            "chrome.downloads.download({});",
        )
        source.resolve("query.test.js").writeText(
            "chrome.downloads.download({});",
        )
        source.resolve("fragment.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.UNKNOWN, report.status)
        assertEquals(setOf("storage"), report.usedApis)
        assertTrue(report.unsupportedApis.isEmpty())
        assertTrue(report.issues.any { it.code == "api_usage_dynamic" })
    }

    @Test
    fun `fails closed for C1 numeric HTML entities`() {
        val source = Files.createTempDirectory("six-c1-html-entity")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "C1 HTML entity",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        source.resolve("popup.html").writeText(
            """
            <script src="mapped&#128;.test.js"></script>
            <script>chrome.storage.local.get("value");</script>
            """.trimIndent(),
        )
        source.resolve("mapped€.test.js").writeText(
            "chrome.webstorePrivate.install();",
        )

        val report = CompatibilityAnalyzer().analyze(source, profile)

        assertEquals(CompatibilityStatus.UNKNOWN, report.status)
        assertEquals(setOf("storage"), report.usedApis)
        assertTrue(report.unsupportedApis.isEmpty())
        assertTrue(report.issues.any { it.code == "api_usage_dynamic" })
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
