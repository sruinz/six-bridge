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
}
