package dev.sixbridge.cli

import dev.sixbridge.core.SixBridgeService
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.AdapterRisk
import dev.sixbridge.core.conversion.SourceEvidence
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.WrapperContract
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertTrue("draft" in output.toString())
        assertTrue("build" in output.toString())
        assertTrue("device" in output.toString())
    }

    @Test
    fun `hidden build verifier remains directly invokable`() {
        val output = StringWriter()
        val command = CommandLine(SixBridgeCommand())
        command.out = PrintWriter(output)

        val exit = command.execute("verify-build", "--help")

        assertEquals(0, exit)
        assertTrue("--bundle" in output.toString())
        assertTrue("--crx" in output.toString())
        assertTrue("--wrapper" in output.toString())
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

    @Test
    fun `analyze reports conversion state adapter decisions and relative evidence`() {
        val workspace = Files.createTempDirectory("six-cli-conversion-analyze")
        val profile = writeProfile(workspace)
        val report = workspace.resolve("conversion.json")

        val result = execute(
            "--workspace", workspace.toString(),
            "analyze",
            "--input", conversionFixture(workspace).toString(),
            "--profile", profile.toString(),
            "--output", report.toString(),
        )

        assertEquals(0, result.exitCode, result.error)
        val json = report.readText()
        assertTrue(
            "\"conversionState\": \"requires-approval\"" in json,
            json,
        )
        setOf(
            "action-popup-entry",
            "desktop-selection-event-bridge",
            "storage-fallback",
        ).forEach { id ->
            assertTrue("\"id\": \"$id\"" in json)
        }
        val evidencePaths = """"relativePath":\s*"([^"]+)"""".toRegex()
            .findAll(json)
            .map { it.groupValues[1] }
            .toList()
        assertTrue(evidencePaths.isNotEmpty())
        assertTrue(
            evidencePaths.all { path ->
                    !Path.of(path).isAbsolute &&
                        path.split('/', '\\').none { it == ".." }
            },
        )
        assertTrue(
            """
            {
                "id": "action-popup-entry",
                "version": 2,
                "risk": "behavior-change",
                "disposition": "requires-approval",
                "applied": false,
                "evidence": [
                    {
                        "relativePath": "manifest.json",
                        "line": 6,
                        "detail": "action.default_popup=popup.html"
                    }
                ],
                "changes": [
                    "삼성 Add-ons 설정 메뉴에 기존 action popup을 연결합니다."
                ],
                "remainingLimitations": [
                    "popup은 삼성 Add-ons 상세 화면의 설정 메뉴에서 별도 탭으로 열립니다."
                ]
            }
            """.trimIndent().withoutLeadingIndent() in json.withoutLeadingIndent(),
            json,
        )
    }

    @Test
    fun `draft requires popup approval and leaves no output`() {
        val workspace = Files.createTempDirectory("six-cli-draft-approval")
        val output = workspace.resolve("missing.sixdraft")

        val result = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", goldenFixture().toString(),
            "--profile", writeProfile(workspace).toString(),
            "--output", output.toString(),
        )

        assertEquals(4, result.exitCode, result.error)
        assertFalse(output.exists())
    }

    @Test
    fun `approved draft is self verified and prints fixed conversion fields`() {
        val workspace = Files.createTempDirectory("six-cli-draft-success")
        val output = workspace.resolve("approved.sixdraft")

        val result = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", goldenFixture().toString(),
            "--profile", writeProfile(workspace).toString(),
            "--approve-adapter", "action-popup-entry",
            "--output", output.toString(),
        )

        assertEquals(0, result.exitCode, result.error)
        assertTrue(output.exists())
        val verified = SixDraft.read(output)
        val popupBefore = verified.payload.compatibilityBefore.adapterDecisions.single {
            it.id == "action-popup-entry"
        }
        val popupAfter = verified.payload.compatibilityAfter.adapterDecisions.single {
            it.id == "action-popup-entry"
        }
        assertEquals("converted", field(result.output, "conversion"))
        assertPopupDecision(popupBefore, applied = false)
        assertEquals(popupBefore.copy(applied = true), popupAfter)
        assertEquals(
            listOf(
                "action-popup-entry",
                "desktop-selection-event-bridge",
            ),
            field(result.output, "adapters").split(","),
        )
        assertEquals(
            verified.payload.input.originalSourceSha256,
            field(result.output, "sourceSha256"),
        )
        assertEquals(
            verified.payload.compatibilityAfter.sourceSha256,
            field(result.output, "transformedSha256"),
        )
        assertEquals(
            output.toAbsolutePath().normalize().toString(),
            field(result.output, "draft"),
        )
    }

    @Test
    fun `unknown and undetected adapter approvals are user input errors`() {
        listOf("not-an-adapter", "storage-fallback").forEach { adapter ->
            val workspace = Files.createTempDirectory("six-cli-invalid-approval")
            val source = workspace.resolve("source").also(Files::createDirectory)
            source.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Plain","version":"1"}""",
            )
            val result = execute(
                "--workspace", workspace.toString(),
                "draft",
                "--input", source.toString(),
                "--profile", writeProfile(workspace).toString(),
                "--approve-adapter", adapter,
                "--output", workspace.resolve("invalid.sixdraft").toString(),
            )

            assertEquals(2, result.exitCode, "$adapter: ${result.error}")
        }
    }

    @Test
    fun `draft preserves a compatible local source as passthrough`() {
        val workspace = Files.createTempDirectory("six-cli-source-passthrough")
        val source = workspace.resolve("source").also(Files::createDirectory)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Plain","version":"1"}""",
        )
        val output = workspace.resolve("plain.sixdraft")

        val result = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", source.toString(),
            "--profile", writeProfile(workspace).toString(),
            "--output", output.toString(),
        )

        assertEquals(0, result.exitCode, result.error)
        assertEquals("passthrough", field(result.output, "conversion"))
        assertEquals(
            SixDraft.read(output).payload.input.originalSourceSha256,
            field(result.output, "transformedSha256"),
        )
    }

    @Test
    fun `draft preserves a verified CRX as passthrough`() {
        val workspace = Files.createTempDirectory("six-cli-crx-passthrough")
        val source = workspace.resolve("source").also(Files::createDirectory)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"CRX","version":"1"}""",
        )
        val packed = Crx3.pack(
            source,
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                .generateKeyPair(),
        )
        val crx = workspace.resolve("plain.crx").also { it.writeBytes(packed.bytes) }
        val output = workspace.resolve("plain-crx.sixdraft")

        val result = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", crx.toString(),
            "--profile", writeProfile(workspace).toString(),
            "--output", output.toString(),
        )

        assertEquals(0, result.exitCode, result.error)
        val verified = SixDraft.read(output)
        assertEquals("passthrough", field(result.output, "conversion"))
        assertEquals(packed.extensionId, verified.payload.input.originalExtensionId)
        assertEquals(packed.extensionId, verified.payload.compatibilityAfter.convertedExtensionId)
    }

    @Test
    fun `draft refuses to overwrite its browser profile`() {
        val workspace = Files.createTempDirectory("six-cli-profile-overlap")
        val source = workspace.resolve("source").also(Files::createDirectory)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Plain","version":"1"}""",
        )
        val profile = writeProfile(workspace)
        val before = profile.readBytes()

        val result = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", source.toString(),
            "--profile", profile.toString(),
            "--output", profile.toString(),
        )

        assertEquals(2, result.exitCode, result.error)
        assertContentEquals(before, profile.readBytes())
    }

    @Test
    fun `verify draft performs an independent strict read`() {
        val workspace = Files.createTempDirectory("six-cli-draft-verify")
        val source = workspace.resolve("source").also(Files::createDirectory)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Plain","version":"1"}""",
        )
        val profile = writeProfile(workspace)
        val output = workspace.resolve("plain.sixdraft")
        assertEquals(
            0,
            execute(
                "--workspace", workspace.toString(),
                "draft",
                "--input", source.toString(),
                "--profile", profile.toString(),
                "--output", output.toString(),
            ).exitCode,
        )

        val result = execute(
            "--workspace", workspace.toString(),
            "verify-draft",
            "--input", output.toString(),
        )

        assertEquals(0, result.exitCode, result.error)
        assertTrue("SixDraft verified" in result.output)
    }

    @Test
    fun `invalid draft and unsafe draft key state are internal failures`() {
        val invalidWorkspace = Files.createTempDirectory("six-cli-invalid-draft")
        val invalidDraft = invalidWorkspace.resolve("invalid.sixdraft")
            .also { it.writeText("not a draft") }
        val invalid = execute(
            "--workspace", invalidWorkspace.toString(),
            "verify-draft",
            "--input", invalidDraft.toString(),
        )
        assertEquals(1, invalid.exitCode, invalid.error)

        val workspace = Files.createTempDirectory("six-cli-key-safety")
        val source = workspace.resolve("source").also(Files::createDirectory)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Plain","version":"1"}""",
        )
        val profile = writeProfile(workspace)
        val first = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", source.toString(),
            "--profile", profile.toString(),
            "--output", workspace.resolve("first.sixdraft").toString(),
        )
        assertEquals(0, first.exitCode, first.error)
        Files.delete(
            workspace.resolve(".local/keystore/draft-transfer/draft-transfer.pub"),
        )

        val unsafe = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", source.toString(),
            "--profile", profile.toString(),
            "--output", workspace.resolve("second.sixdraft").toString(),
        )
        assertEquals(1, unsafe.exitCode, unsafe.error)
    }

    @Test
    fun `self verification replacement is an internal CLI failure`() {
        val workspace = Files.createTempDirectory("six-cli-self-verify-failure")
        val source = workspace.resolve("source").also(Files::createDirectory)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Plain","version":"1"}""",
        )
        val profile = writeProfile(workspace)
        val replacingReader: (Path) -> dev.sixbridge.core.draft.VerifiedSixDraft = { path ->
            val original = SixDraft.read(path)
            path.writeBytes(
                SixDraft.createBytes(
                    original.payload,
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
                ),
            )
            SixDraft.read(path)
        }

        val result = executeWith(
            serviceFactory = { root ->
                SixBridgeService(
                    workspaceRoot = root,
                    draftReader = replacingReader,
                )
            },
            "--workspace", workspace.toString(),
            "draft",
            "--input", source.toString(),
            "--profile", profile.toString(),
            "--output", workspace.resolve("replaced.sixdraft").toString(),
        )

        assertEquals(1, result.exitCode, result.error)
    }

    @Test
    fun `malformed source and profile remain user input errors`() {
        val workspace = Files.createTempDirectory("six-cli-malformed-input")
        val source = workspace.resolve("source").also(Files::createDirectory)
        source.resolve("manifest.json").writeText("{")
        val profile = writeProfile(workspace)
        val malformedSource = execute(
            "--workspace", workspace.toString(),
            "analyze",
            "--input", source.toString(),
            "--profile", profile.toString(),
        )
        assertEquals(2, malformedSource.exitCode, malformedSource.error)

        val validSource = workspace.resolve("valid").also(Files::createDirectory)
        validSource.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Plain","version":"1"}""",
        )
        profile.writeText("{}")
        val malformedProfile = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", validSource.toString(),
            "--profile", profile.toString(),
            "--output", workspace.resolve("invalid.sixdraft").toString(),
        )
        assertEquals(2, malformedProfile.exitCode, malformedProfile.error)
    }

    @Test
    fun `approval order does not change draft hash`() {
        val workspace = Files.createTempDirectory("six-cli-draft-order")
        val profile = writeProfile(workspace)
        val first = workspace.resolve("first.sixdraft")
        val second = workspace.resolve("second.sixdraft")
        val firstResult = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", goldenFixture().toString(),
            "--profile", profile.toString(),
            "--approve-adapter", "action-popup-entry",
            "--approve-adapter", "desktop-selection-event-bridge",
            "--output", first.toString(),
        )
        val secondResult = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", goldenFixture().toString(),
            "--profile", profile.toString(),
            "--approve-adapter", "desktop-selection-event-bridge",
            "--approve-adapter", "action-popup-entry",
            "--output", second.toString(),
        )

        assertEquals(0, firstResult.exitCode, firstResult.error)
        assertEquals(0, secondResult.exitCode, secondResult.error)
        assertEquals(sha256(first.readBytes()), sha256(second.readBytes()))
    }

    @Test
    fun `build reports a missing output parent as actionable user input`() {
        val workspace = Files.createTempDirectory("six-cli-missing-output-parent")
        val source = workspace.resolve("source").also(Files::createDirectory)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Plain","version":"1"}""",
        )
        val output = workspace.resolve("missing/converted.six")

        val result = execute(
            "--workspace", workspace.toString(),
            "build",
            "--input", source.toString(),
            "--profile", writeProfile(workspace).toString(),
            "--output", output.toString(),
        )

        assertEquals(2, result.exitCode, result.error)
        assertTrue(result.error.contains("mkdir -p"), result.error)
        assertFalse(workspace.resolve("missing").exists())
        assertFalse(workspace.resolve(".local").exists())
    }

    @Test
    fun `build accepts popup approval and uses the draft transformed source hash`() {
        val workspace = Files.createTempDirectory("six-cli-build-conversion")
        val profile = writeProfile(workspace)
        val draft = workspace.resolve("fixture.sixdraft")
        val bundle = workspace.resolve("fixture.six")
        val draftResult = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", goldenFixture().toString(),
            "--profile", profile.toString(),
            "--approve-adapter", "action-popup-entry",
            "--output", draft.toString(),
        )
        val buildResult = execute(
            "--workspace", workspace.toString(),
            "build",
            "--input", goldenFixture().toString(),
            "--profile", profile.toString(),
            "--approve-adapter", "action-popup-entry",
            "--output", bundle.toString(),
        )

        assertEquals(0, draftResult.exitCode, draftResult.error)
        assertEquals(0, buildResult.exitCode, buildResult.error)
        assertEquals(
            field(draftResult.output, "transformedSha256"),
            field(buildResult.output, "transformedSha256"),
        )
        assertPopupDecision(
            SixBundle.read(bundle).compatibility.adapterDecisions.single {
                it.id == "action-popup-entry"
            },
            applied = true,
        )
        assertTrue(bundle.exists())
    }

    private fun execute(vararg arguments: String): Execution {
        return executeWith(::SixBridgeService, *arguments)
    }

    private fun executeWith(
        serviceFactory: (Path) -> SixBridgeService,
        vararg arguments: String,
    ): Execution {
        val output = StringWriter()
        val error = StringWriter()
        val command = createCommandLine(serviceFactory)
        command.out = PrintWriter(output)
        command.err = PrintWriter(error)
        return Execution(
            command.execute(*arguments),
            output.toString(),
            error.toString(),
        )
    }

    private fun writeProfile(workspace: Path): Path =
        workspace.resolve("profile.json").also { path ->
            path.writeText(
                BrowserProfileJson.encode(
                    BrowserProfile(
                        browserPackage = "com.sec.android.app.sbrowser",
                        versionName = "30.0.2.61",
                        versionCode = 1_300_261_502,
                        majorVersion = 30,
                        supportedApis = setOf(
                            "action",
                            "runtime",
                            "storage",
                            "tabs",
                        ),
                        wrapperContract = WrapperContract.complete(),
                        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
                        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
                        supportedFeatures = setOf(
                            "storage.local",
                            "extension.options-page.user-accessible",
                        ),
                        unavailableFeatures = setOf("storage.sync"),
                    ),
                ),
            )
        }

    private fun goldenFixture(): Path =
        Path.of("../fixtures/desktop-mobile-golden").toAbsolutePath().normalize()

    private fun conversionFixture(workspace: Path): Path {
        val source = goldenFixture()
        val target = workspace.resolve("desktop-mobile-golden")
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val relative = source.relativize(path)
                val destination = target.resolve(relative)
                if (Files.isDirectory(path)) {
                    Files.createDirectories(destination)
                } else {
                    Files.copy(path, destination)
                }
            }
        }
        target.resolve("storage-input.js").writeText(
            """
            chrome.storage.sync.get("theme");
            chrome?.storage?.sync.set({ mobile: true });
            """.trimIndent(),
        )
        target.resolve("background.js").writeText("void 0;")
        val manifest = target.resolve("manifest.json")
        manifest.writeText(
            manifest.readText().replace(
                """"js": ["content.js"]""",
                """"js": ["content.js", "storage-input.js"]""",
            ),
        )
        return target
    }

    private fun field(output: String, name: String): String =
        output.lineSequence()
            .first { it.startsWith("$name=") }
            .substringAfter("=")

    private fun String.withoutLeadingIndent(): String =
        lineSequence().joinToString("\n") { it.trimStart() }

    private fun assertPopupDecision(
        actual: AdapterDecision,
        applied: Boolean,
    ) {
        assertEquals(
            AdapterDecision(
                id = "action-popup-entry",
                version = 2,
                risk = AdapterRisk.BEHAVIOR_CHANGE,
                disposition = AdapterDisposition.REQUIRES_APPROVAL,
                applied = applied,
                evidence = listOf(
                    SourceEvidence(
                        relativePath = "manifest.json",
                        line = 6,
                        detail = "action.default_popup=popup.html",
                    ),
                ),
                changes = listOf(
                    "삼성 Add-ons 설정 메뉴에 기존 action popup을 연결합니다.",
                ),
                remainingLimitations = listOf(
                    "popup은 삼성 Add-ons 상세 화면의 설정 메뉴에서 별도 탭으로 열립니다.",
                ),
            ),
            actual,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class Execution(
        val exitCode: Int,
        val output: String,
        val error: String,
    )
}
