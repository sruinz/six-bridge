package dev.sixbridge.cli

import dev.sixbridge.core.SixBridgeService
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.AdapterRisk
import dev.sixbridge.core.conversion.SourceEvidence
import dev.sixbridge.core.draft.DraftReplayResult
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.draft.SixDraftReplay
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.input.ChromeWebStoreAddress
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.probe.catalog.ApiProbeCatalog
import dev.sixbridge.probe.catalog.CatalogDisposition
import picocli.CommandLine
import java.io.PrintWriter
import java.io.StringWriter
import java.io.IOException
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
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class SixBridgeCommandTest {
    private val projectIdentity = "0123456789abcdef0123456789abcdef"

    @Test
    fun `desktop downloader는 사용자 상세 URL 대신 고정 update endpoint를 구성한다`() {
        val address = ChromeWebStoreAddress.parse(
            "https://chromewebstore.google.com/detail/display-name/" +
                "fchehlladkkanoekpjffcfffpfbdaalj?hl=ko",
        )

        assertEquals(
            "https://clients2.google.com/service/update2/crx" +
                "?response=redirect&prodversion=130.0&acceptformat=crx3" +
                "&x=id%3Dfchehlladkkanoekpjffcfffpfbdaalj%26uc",
            DesktopChromeCrxDownloader.updateUri(address).toString(),
        )
    }

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
        assertTrue("probe" in output.toString())
    }

    @Test
    fun `probe option 누락 중복 unknown은 exit 2이고 stdout을 남기지 않는다`() {
        listOf(
            arrayOf("probe", "generate"),
            arrayOf("probe", "generate", "--output", "one", "--output", "two"),
            arrayOf("probe", "generate", "--unknown", "value"),
            arrayOf("probe", "verify-evidence"),
            arrayOf("probe", "build-profile", "--base", "base.json"),
        ).forEach { arguments ->
            val result = execute(*arguments)
            assertEquals(2, result.exitCode, arguments.joinToString(" "))
            assertEquals("", result.output, arguments.joinToString(" "))
        }
    }

    @Test
    fun `probe parent help는 주입된 writer로 출력한다`() {
        val result = execute("probe")

        assertEquals(0, result.exitCode, result.error)
        assertTrue("Usage:" in result.output)
        assertTrue("generate" in result.output)
        assertTrue("verify-evidence" in result.output)
        assertTrue("build-profile" in result.output)
    }

    @Test
    fun `probe generate는 정확한 여섯 source를 만들고 기존 output을 보존한다`() {
        val workspace = Files.createTempDirectory("six-cli-probe-generate")
        val output = workspace.resolve("probe-source")

        val first = execute("probe", "generate", "--output", output.toString())
        assertEquals(0, first.exitCode, first.error)
        assertEquals(6, Files.list(output).use { it.count() })
        assertTrue("생성 그룹=6" in first.output)

        val sentinel = output.resolve("sentinel.txt").also { it.writeText("user-owned") }
        val second = execute("probe", "generate", "--output", output.toString())
        assertEquals(2, second.exitCode)
        assertEquals("user-owned", sentinel.readText())
        assertEquals("", second.output)
    }

    @Test
    fun `probe verify-evidence는 여섯 group과 33 case를 한글로 결정론 출력한다`() {
        val workspace = Files.createTempDirectory("six-cli-probe-verify")
        val evidence = writeProbeEvidence(workspace.resolve("evidence"), mismatchProvenance = false)

        val result = execute("probe", "verify-evidence", "--input", evidence.toString())

        assertEquals(0, result.exitCode, result.error)
        assertTrue("그룹=6/6" in result.output)
        assertTrue("case=33/33" in result.output)
        assertTrue("증거 검증 완료" in result.output)
        assertEquals(result.output, execute("probe", "verify-evidence", "--input", evidence.toString()).output)
    }

    @Test
    fun `probe build-profile은 browser provenance 불일치를 거부하고 output을 만들지 않는다`() {
        val workspace = Files.createTempDirectory("six-cli-probe-provenance")
        val base = writeProfile(workspace)
        val evidence = writeProbeEvidence(workspace.resolve("evidence"), mismatchProvenance = true)
        val output = workspace.resolve("candidate.json")

        val result = execute(
            "probe", "build-profile",
            "--base", base.toString(),
            "--evidence", evidence.toString(),
            "--output", output.toString(),
        )

        assertEquals(2, result.exitCode)
        assertFalse(output.exists())
        assertEquals("", result.output)
    }

    @Test
    fun `probe build-profile은 검증된 schema v4를 새 output에만 게시한다`() {
        val workspace = Files.createTempDirectory("six-cli-probe-profile")
        val base = writeProfile(workspace)
        val evidence = writeProbeEvidence(workspace.resolve("evidence"), mismatchProvenance = false)
        val output = workspace.resolve("candidate.json")

        val result = execute(
            "probe", "build-profile",
            "--base", base.toString(),
            "--evidence", evidence.toString(),
            "--output", output.toString(),
        )

        assertEquals(0, result.exitCode, result.error)
        val candidate = BrowserProfileJson.decode(output.readText())
        assertEquals(33, candidate.apiCapabilities.size)
        assertTrue("runtime" in candidate.supportedApis)
        assertTrue("후보 profile=" in result.output)

        val bytes = output.readBytes()
        val repeated = execute(
            "probe", "build-profile",
            "--base", base.toString(),
            "--evidence", evidence.toString(),
            "--output", output.toString(),
        )
        assertEquals(2, repeated.exitCode)
        assertContentEquals(bytes, output.readBytes())
        assertEquals("", repeated.output)
    }

    @Test
    fun `probe evidence directory는 symlink와 unknown file을 거부한다`() {
        val workspace = Files.createTempDirectory("six-cli-probe-unsafe-evidence")
        val evidence = writeProbeEvidence(workspace.resolve("evidence"), mismatchProvenance = false)
        evidence.resolve("unknown.txt").writeText("user-owned")

        val unknown = execute("probe", "verify-evidence", "--input", evidence.toString())
        assertEquals(2, unknown.exitCode)
        assertEquals("", unknown.output)

        Files.delete(evidence.resolve("unknown.txt"))
        Files.delete(evidence.resolve("base-runtime.json"))
        Files.createSymbolicLink(
            evidence.resolve("base-runtime.json"),
            evidence.resolve("advanced-runtime.json"),
        )
        val linked = execute("probe", "verify-evidence", "--input", evidence.toString())
        assertEquals(2, linked.exitCode)
        assertEquals("", linked.output)
    }

    @Test
    fun `probe output은 symlink parent를 따라 쓰지 않는다`() {
        val workspace = Files.createTempDirectory("six-cli-probe-output-link")
        val realParent = workspace.resolve("real").also(Files::createDirectory)
        val linkedParent = workspace.resolve("linked").also {
            Files.createSymbolicLink(it, realParent)
        }

        val result = execute(
            "probe", "generate",
            "--output", linkedParent.resolve("probe-source").toString(),
        )

        assertEquals(2, result.exitCode)
        assertFalse(realParent.resolve("probe-source").exists())
        assertEquals("", result.output)
    }

    @Test
    fun `probe publication은 force move parent fsync 순서를 지킨다`() {
        val workspace = Files.createTempDirectory("six-cli-probe-publication-order")
        val base = writeProfile(workspace)
        val evidence = writeProbeEvidence(workspace.resolve("evidence"), mismatchProvenance = false)
        val profileOutput = workspace.resolve("candidate.json")
        val profileOps = RecordingProbePublicationFileOps()

        val profileExit = executeProbe(
            ProbeBuildProfileCommand(profileOps),
            "--base", base.toString(),
            "--evidence", evidence.toString(),
            "--output", profileOutput.toString(),
        )

        assertEquals(0, profileExit.exitCode, profileExit.error)
        assertEquals(listOf("write-force", "move-new", "fsync-directory"), profileOps.events)

        val sourceOutput = workspace.resolve("sources")
        val generateOps = RecordingProbePublicationFileOps()
        val generateExit = executeProbe(
            ProbeGenerateCommand(generateOps),
            "--output", sourceOutput.toString(),
        )
        assertEquals(0, generateExit.exitCode, generateExit.error)
        assertTrue(generateOps.events.indexOf("move-new") > generateOps.events.lastIndexOf("force-file"))
        assertEquals("fsync-directory", generateOps.events.last())
    }

    @Test
    fun `probe move race는 기존 target을 보존하고 임시 경로를 정리한다`() {
        val workspace = Files.createTempDirectory("six-cli-probe-publication-race")
        val target = workspace.resolve("sources")
        val ops = RecordingProbePublicationFileOps(onMove = { _, destination ->
            Files.createDirectory(destination)
            destination.resolve("sentinel.txt").writeText("user-owned")
        })

        val result = executeProbe(
            ProbeGenerateCommand(ops),
            "--output", target.toString(),
        )

        assertNotEquals(0, result.exitCode)
        assertEquals("user-owned", target.resolve("sentinel.txt").readText())
        assertTrue(Files.list(workspace).use { entries ->
            entries.noneMatch { it.fileName.toString().contains("staging") }
        })
    }

    @Test
    fun `probe fsync 실패는 임시 파일을 남기지 않는다`() {
        val workspace = Files.createTempDirectory("six-cli-probe-fsync-failure")
        val base = writeProfile(workspace)
        val evidence = writeProbeEvidence(workspace.resolve("evidence"), mismatchProvenance = false)
        val target = workspace.resolve("candidate.json")
        val ops = RecordingProbePublicationFileOps(failDirectoryFsync = true)

        val result = executeProbe(
            ProbeBuildProfileCommand(ops),
            "--base", base.toString(),
            "--evidence", evidence.toString(),
            "--output", target.toString(),
        )

        assertNotEquals(0, result.exitCode)
        assertTrue(Files.list(workspace).use { entries ->
            entries.noneMatch { it.fileName.toString().contains(".tmp-") }
        })
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
    fun `hidden CRX verifier checks signature and public key derived identity`() {
        val workspace = Files.createTempDirectory("six-cli-verify-crx")
        val source = workspace.resolve("source").also(Files::createDirectory)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Verified","version":"1"}""",
        )
        val packed = Crx3.pack(
            source,
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                .generateKeyPair(),
        )
        val crx = workspace.resolve("verified.crx").also {
            it.writeBytes(packed.bytes)
        }

        val result = execute(
            "verify-crx",
            "--input", crx.toString(),
            "--expected-id", packed.extensionId,
        )

        assertEquals(0, result.exitCode, result.error)
        assertTrue("extensionId=${packed.extensionId}" in result.output)
        assertTrue("CRX3 verified" in result.output)
    }

    @Test
    fun `hidden CRX verifier rejects magic-only fake CRX`() {
        val workspace = Files.createTempDirectory("six-cli-reject-fake-crx")
        val crx = workspace.resolve("fake.crx").also {
            it.writeText("Cr24 fixture is not a signed CRX3")
        }

        val result = execute(
            "verify-crx",
            "--input", crx.toString(),
            "--expected-id", "abcdefghijklmnopabcdefghijklmnop",
        )

        assertEquals(2, result.exitCode)
        assertTrue("CRX" in result.error)
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
            "--identity", projectIdentity,
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
        assertPopupDecision(popupAfter, applied = true, evidenceLine = 1)
        assertEquals(2, verified.payload.draft.schemaVersion)
        assertEquals(
            verified.payload.input.originalSourceSha256,
            verified.payload.expected.originalSourceSha256,
        )
        assertEquals(
            sha256(verified.payload.source),
            verified.payload.expected.originalSourceSha256,
        )
        assertEquals(
            verified.payload.compatibilityAfter.sourceSha256,
            verified.payload.expected.transformedSourceSha256,
        )
        assertNotEquals(
            verified.payload.expected.originalSourceSha256,
            verified.payload.expected.transformedSourceSha256,
        )
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
            "--identity", projectIdentity,
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
    fun `표식 없는 ZIP draft는 명시적 project identity가 있어야 연결된다`() {
        val workspace = Files.createTempDirectory("six-cli-markerless-zip")
        val source = workspace.resolve("source").also(Files::createDirectory)
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Plain ZIP","version":"1"}""",
        )
        val input = workspace.resolve("plain.zip")
        ExtensionZip.create(source, input)
        val profile = writeProfile(workspace)
        val output = workspace.resolve("plain.sixdraft")

        val missingIdentity = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", input.toString(),
            "--profile", profile.toString(),
            "--output", output.toString(),
        )

        assertEquals(2, missingIdentity.exitCode, missingIdentity.error)
        assertFalse(output.exists())
        assertFalse(workspace.resolve(".local").exists())

        val explicitIdentity = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", input.toString(),
            "--profile", profile.toString(),
            "--identity", projectIdentity,
            "--output", output.toString(),
        )

        assertEquals(0, explicitIdentity.exitCode, explicitIdentity.error)
        val verified = SixDraft.read(output)
        assertEquals(
            "project:$projectIdentity",
            verified.payload.draft.sourceIdentity,
        )
        assertEquals(
            "project:$projectIdentity",
            verified.payload.input.sourceIdentity,
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
        assertEquals(
            "store:${packed.extensionId}",
            verified.payload.draft.sourceIdentity,
        )
    }

    @Test
    fun `converted CRX draft stores the original CRX and key neutral expected proof`() {
        val workspace = Files.createTempDirectory("six-cli-crx-converted-v2")
        val source = workspace.resolve("source").also(Files::createDirectory)
        source.resolve("content.js").writeText(
            """
            document.addEventListener("mouseup", (event) => {
              const value = getSelection()?.toString();
              if (value) console.log(value);
            });
            """.trimIndent(),
        )
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Converted CRX",
              "version": "1",
              "content_scripts": [{
                "matches": ["https://example.com/*"],
                "js": ["content.js"]
              }]
            }
            """.trimIndent(),
        )
        val packed = Crx3.pack(
            source,
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                .generateKeyPair(),
        )
        val input = workspace.resolve("converted.crx").also {
            it.writeBytes(packed.bytes)
        }
        val profile = writeProfile(workspace)
        val output = workspace.resolve("converted.sixdraft")

        val result = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", input.toString(),
            "--profile", profile.toString(),
            "--output", output.toString(),
        )

        assertEquals(0, result.exitCode, result.error)
        val verified = SixDraft.read(output)
        val replayed = assertIs<DraftReplayResult.Ready>(
            SixDraftReplay().replay(
                verified,
                BrowserProfileJson.decode(profile.readText()),
                emptySet(),
            ),
        ).prepared
        assertEquals(2, verified.payload.draft.schemaVersion)
        assertContentEquals(packed.bytes, verified.payload.source)
        assertEquals(
            sha256(packed.zipBytes),
            verified.payload.expected.originalSourceSha256,
        )
        assertEquals(
            verified.payload.expected.transformedSourceSha256,
            replayed.transformedSourceSha256,
        )
        assertNotEquals(
            verified.payload.expected.originalSourceSha256,
            verified.payload.expected.transformedSourceSha256,
        )
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
                "--identity", projectIdentity,
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
            "--identity", projectIdentity,
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
            "--identity", projectIdentity,
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
            "--identity", projectIdentity,
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
            "--identity", projectIdentity,
            "--approve-adapter", "action-popup-entry",
            "--approve-adapter", "desktop-selection-event-bridge",
            "--output", first.toString(),
        )
        val secondResult = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", goldenFixture().toString(),
            "--profile", profile.toString(),
            "--identity", projectIdentity,
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
    fun `build accepts popup approval while draft keeps key neutral replay proof`() {
        val workspace = Files.createTempDirectory("six-cli-build-conversion")
        val profile = writeProfile(workspace)
        val draft = workspace.resolve("fixture.sixdraft")
        val bundle = workspace.resolve("fixture.six")
        val draftResult = execute(
            "--workspace", workspace.toString(),
            "draft",
            "--input", goldenFixture().toString(),
            "--profile", profile.toString(),
            "--identity", projectIdentity,
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
        val verified = SixDraft.read(draft)
        val replayed = assertIs<DraftReplayResult.Ready>(
            SixDraftReplay().replay(
                draft = verified,
                profile = BrowserProfileJson.decode(profile.readText()),
                approvedAdapterIds = setOf("action-popup-entry"),
            ),
        ).prepared
        assertEquals(
            field(draftResult.output, "transformedSha256"),
            verified.payload.expected.transformedSourceSha256,
        )
        assertEquals(
            verified.payload.expected.transformedSourceSha256,
            replayed.transformedSourceSha256,
        )
        assertNotEquals(
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

    private fun writeProbeEvidence(root: Path, mismatchProvenance: Boolean): Path {
        Files.createDirectory(root)
        val catalog = ApiProbeCatalog.load(
            checkNotNull(javaClass.getResourceAsStream("/catalog/sbrowser-30-public-apis.json")),
        )
        val groups = catalog.entries
            .filter { it.disposition == CatalogDisposition.PROBE }
            .groupBy { requireNotNull(it.groupId) }
            .toSortedMap()
        groups.forEach { (groupId, entries) ->
            val results = entries.flatMap { entry ->
                entry.cases.map { case -> entry to case }
            }.mapIndexed { index, (entry, case) ->
                    val verified = case.expectedOutcome.name == "VERIFIED"
                    JsonObject(
                        linkedMapOf(
                            "caseId" to JsonPrimitive(case.id),
                            "context" to JsonPrimitive(case.context.name.lowercase()),
                            "detail" to if (verified) {
                                JsonObject(
                                    linkedMapOf(
                                        "kind" to JsonPrimitive("success"),
                                        "valueType" to JsonPrimitive("object"),
                                    ),
                                )
                            } else {
                                JsonObject(linkedMapOf("kind" to JsonPrimitive("exposed")))
                            },
                            "durationMs" to JsonPrimitive(1),
                            "member" to JsonPrimitive(case.member),
                            "namespace" to JsonPrimitive(entry.namespace),
                            "runToken" to JsonPrimitive(
                                "run-${groupId.take(6)}-${index.toString().padStart(8, '0')}",
                            ),
                            "status" to JsonPrimitive(if (verified) "VERIFIED" else "EXPOSED"),
                        ),
                    )
            }
            val rootObject = JsonObject(
                linkedMapOf(
                    "schemaVersion" to JsonPrimitive(1),
                    "sessionId" to JsonPrimitive("session-20260802"),
                    "groupId" to JsonPrimitive(groupId),
                    "catalogSha256" to JsonPrimitive(catalog.sha256),
                    "extensionId" to JsonPrimitive(probeExtensionId(groupId)),
                    "wrapperSignerSha256" to JsonPrimitive("d".repeat(64)),
                    "browserPackage" to JsonPrimitive("com.sec.android.app.sbrowser"),
                    "browserVersionName" to JsonPrimitive("30.0.2.61"),
                    "browserVersionCode" to JsonPrimitive(1_300_261_502),
                    "browserApkSetSha256" to JsonPrimitive(
                        if (mismatchProvenance && groupId == groups.firstKey()) {
                            "e".repeat(64)
                        } else {
                            BrowserProfile.BASELINE_APK_SHA256
                        },
                    ),
                    "browserSignerSha256" to JsonPrimitive(BrowserProfile.SAMSUNG_SIGNER_SHA256),
                    "results" to JsonArray(results),
                ),
            )
            root.resolve("$groupId.json").writeText(
                Json.encodeToString(JsonObject.serializer(), rootObject),
            )
        }
        return root
    }

    private fun probeExtensionId(groupId: String): String = MessageDigest.getInstance("SHA-256")
        .digest(groupId.toByteArray())
        .take(16)
        .joinToString("") { byte ->
            val value = byte.toInt() and 0xff
            "${('a'.code + (value ushr 4)).toChar()}${('a'.code + (value and 0xf)).toChar()}"
        }

    private fun execute(vararg arguments: String): Execution {
        return executeWith(::SixBridgeService, *arguments)
    }

    private fun executeProbe(commandObject: Any, vararg arguments: String): Execution {
        val output = StringWriter()
        val error = StringWriter()
        val command = CommandLine(commandObject)
        command.out = PrintWriter(output)
        command.err = PrintWriter(error)
        return Execution(command.execute(*arguments), output.toString(), error.toString())
    }

    private class RecordingProbePublicationFileOps(
        private val onMove: (Path, Path) -> Unit = { _, _ -> },
        private val failDirectoryFsync: Boolean = false,
    ) : ProbePublicationFileOps {
        val events = mutableListOf<String>()

        override fun writeAndForce(path: Path, bytes: ByteArray) {
            events += "write-force"
            NioProbePublicationFileOps.writeAndForce(path, bytes)
        }

        override fun forceFile(path: Path) {
            events += "force-file"
            NioProbePublicationFileOps.forceFile(path)
        }

        override fun moveNew(source: Path, target: Path) {
            events += "move-new"
            onMove(source, target)
            NioProbePublicationFileOps.moveNew(source, target)
        }

        override fun forceDirectory(path: Path) {
            events += "fsync-directory"
            if (failDirectoryFsync) throw IOException("injected directory fsync failure")
            NioProbePublicationFileOps.forceDirectory(path)
        }
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
        evidenceLine: Int = 6,
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
                        line = evidenceLine,
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
