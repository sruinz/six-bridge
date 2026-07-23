package dev.sixbridge.cli

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.WrapperContract
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SixBridgeCommandTest {
    @Test
    fun `shows the fixed command surface`() {
        val output = StringWriter()
        val command = CommandLine(SixBridgeCommand())
        command.out = PrintWriter(output)

        val exit = command.execute("--help")

        assertEquals(0, exit)
        assertTrue("profile" in output.toString())
        assertTrue("analyze" in output.toString())
        assertTrue("build" in output.toString())
        assertTrue("device" in output.toString())
    }

    @Test
    fun `profile writes a reusable browser profile`() {
        val workspace = Files.createTempDirectory("six-cli-profile")
        val apk = workspace.resolve("sbrowser-30.0.2.61-1300261502_test.apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write("six://ex extension-id .contentprovider dev_ext".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libterrace.so"))
            zip.write("""{"namespace":"storage"}""".toByteArray())
            zip.closeEntry()
        }
        val output = workspace.resolve("profile.json")

        val exit = CommandLine(SixBridgeCommand()).execute(
            "--workspace",
            workspace.toString(),
            "profile",
            "--browser-apk",
            apk.toString(),
            "--output",
            output.toString(),
        )

        assertEquals(0, exit)
        assertTrue(output.exists())
        assertEquals(30, BrowserProfileJson.decode(output.readText()).majorVersion)
    }

    @Test
    fun `analyze emits the compatibility report`() {
        val workspace = Files.createTempDirectory("six-cli-analyze")
        val source = workspace.resolve("source").also { Files.createDirectories(it) }
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Tiny",
              "version": "1",
              "permissions": ["storage"]
            }
            """.trimIndent(),
        )
        val profilePath = workspace.resolve("profile.json")
        profilePath.writeText(
            BrowserProfileJson.encode(
                BrowserProfile(
                    browserPackage = "com.sec.android.app.sbrowser",
                    versionName = "30.0.2.61",
                    versionCode = 1_300_261_502,
                    majorVersion = 30,
                    supportedApis = setOf("storage"),
                    wrapperContract = WrapperContract.complete(),
                    sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
                    sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
                ),
            ),
        )
        val report = workspace.resolve("compatibility.json")

        val exit = CommandLine(SixBridgeCommand()).execute(
            "--workspace",
            workspace.toString(),
            "analyze",
            "--input",
            source.toString(),
            "--profile",
            profilePath.toString(),
            "--output",
            report.toString(),
        )

        assertEquals(0, exit)
        assertTrue("\"status\": \"compatible\"" in report.readText())
    }
}
