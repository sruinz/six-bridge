package dev.sixbridge.core.compat

import dev.sixbridge.core.conversion.MobilePatternAnalyzer
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
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
        val resources = (0 until 3_000).map { "padding-$it.js" }
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
              }],
              "web_accessible_resources":[{
                "resources":[${resources.joinToString(",") { "\"$it\"" }}],
                "matches":["<all_urls>"]
              }]
            }
            """.trimIndent(),
        )
        resources.forEach { source.resolve(it).writeText("const value = 1;") }
        val script = source.resolve("a.js")
        val fixedTime = FileTime.fromMillis(1_700_000_000_000)
        script.writeText("chrome.runtime.getURL('x')")
        Files.setLastModifiedTime(script, fixedTime)
        val task = FutureTask {
            MobilePatternAnalyzer().analyze(source, profile)
        }
        val victim = Thread(task, "stable-analysis-victim")
        victim.start()
        try {
            waitForFrame(
                victim,
                CompatibilityAnalyzer::class.java.name,
                "scanApiCalls",
            )
            script.writeText("chrome.downloads.download({url:'https://example.test'})")
            Files.setLastModifiedTime(script, fixedTime)

            waitForFrameAbsentButCallerPresent(
                victim,
                CompatibilityAnalyzer::class.java.name,
                absentMethod = "scanApiCalls",
                presentMethod = "analyzeUnsealed",
            )
            script.writeText("chrome.runtime.getURL('x')")
            Files.setLastModifiedTime(script, fixedTime)

            val report = task.get(15, TimeUnit.SECONDS)
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
        } finally {
            victim.join(5_000)
        }
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

    private fun waitForFrame(
        thread: Thread,
        className: String,
        methodName: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            check(thread.isAlive) {
                "analysis thread가 공격 지점을 관찰하기 전에 종료되었습니다."
            }
            if (
                thread.stackTrace.any {
                    it.className == className && it.methodName == methodName
                }
            ) {
                return
            }
            Thread.onSpinWait()
        }
        error("$className.$methodName frame을 관찰하지 못했습니다.")
    }

    private fun waitForFrameAbsentButCallerPresent(
        thread: Thread,
        className: String,
        absentMethod: String,
        presentMethod: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            check(thread.isAlive) {
                "analysis thread가 복원 지점을 관찰하기 전에 종료되었습니다."
            }
            val stack = thread.stackTrace
            if (
                stack.none {
                    it.className == className && it.methodName == absentMethod
                } &&
                stack.any {
                    it.className == className && it.methodName == presentMethod
                }
            ) {
                return
            }
            Thread.onSpinWait()
        }
        error("$className.$presentMethod 복원 frame을 관찰하지 못했습니다.")
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
