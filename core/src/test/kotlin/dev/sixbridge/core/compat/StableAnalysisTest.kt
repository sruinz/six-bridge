package dev.sixbridge.core.compat

import dev.sixbridge.core.conversion.MobilePatternAnalyzer
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StableAnalysisTest {
    @Test
    fun `analysis scope excludes files omitted from canonical source`() {
        val source = extension("stable-analysis-excluded", "chrome.runtime.getURL('x')")
        Files.createDirectories(source.resolve(".git"))
            .resolve("injected.js")
            .writeText("chrome.downloads.download({url:'https://example.test'})")

        val report = MobilePatternAnalyzer().analyze(source, profile)

        assertTrue("runtime" in report.usedApis)
        assertFalse("downloads" in report.usedApis)
        assertEquals(
            sha256(
                ExtensionZip.createBytes(
                    source,
                    includeProjectMarker = true,
                ),
            ),
            report.sourceSha256,
        )
    }

    @Test
    fun `all compatibility and mobile decisions read one stable source`() {
        val source = Files.createTempDirectory("stable-analysis-race")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version":3,
              "name":"Stable",
              "version":"1",
              "content_scripts":[{
                "matches":["<all_urls>"],
                "js":["a.js"]
              }]
            }
            """.trimIndent(),
        )
        val script = source.resolve("a.js")
        val fixedTime = FileTime.fromMillis(1_700_000_000_000)
        script.writeText("chrome.runtime.getURL('x')")
        Files.setLastModifiedTime(script, fixedTime)
        val control = AnalysisControl(onStage = { stage ->
            when (stage) {
                AnalysisStage.API_SCAN -> {
                    script.writeText("chrome.downloads.download({url:'https://example.test'})")
                    Files.setLastModifiedTime(script, fixedTime)
                }

                AnalysisStage.MOBILE_PATTERNS -> {
                    script.writeText("chrome.runtime.getURL('x')")
                    Files.setLastModifiedTime(script, fixedTime)
                }

                else -> Unit
            }
        })

        val report = MobilePatternAnalyzer(control).analyze(source, profile)

        assertTrue("runtime" in report.usedApis)
        assertFalse("downloads" in report.usedApis)
        assertEquals(
            sha256(
                ExtensionZip.createBytes(
                    source,
                    includeProjectMarker = true,
                ),
            ),
            report.sourceSha256,
        )
    }

    private fun extension(prefix: String, script: String): Path =
        Files.createTempDirectory(prefix).also { root ->
            root.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version":3,
                  "name":"Stable",
                  "version":"1",
                  "content_scripts":[{
                    "matches":["<all_urls>"],
                    "js":["content.js"]
                  }]
                }
                """.trimIndent(),
            )
            root.resolve("content.js").writeText(script)
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val profile = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = BrowserProfile.BASELINE_VERSION,
            versionCode = 1_300_261_502,
            majorVersion = 30,
            supportedApis = setOf("runtime"),
            wrapperContract = WrapperContract.complete(),
            sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
            sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        )
    }
}
