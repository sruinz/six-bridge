package dev.sixbridge.cli

import dev.sixbridge.core.bundle.ExtensionDescriptor
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.device.BuildVerificationResult
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.probe.catalog.ApiProbeCatalog
import dev.sixbridge.probe.session.ProbeSessionConfigJson
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import picocli.CommandLine

class ProbeSessionCommandTest {
    @Test
    fun `prepare session은 exact 6 build artifact를 검증하고 0600 config를 생성한다`() = withWorkspace { root ->
        val profile = profile()
        val profilePath = root.resolve("profile.json").also {
            it.writeText(BrowserProfileJson.encode(profile))
        }
        val artifacts = writeArtifacts(root, profile.canonicalSha256())
        val verified = mutableListOf<Triple<Path, Path, Path>>()
        val command = ProbePrepareSessionCommand { bundle, crx, wrapper ->
            verified += Triple(bundle, crx, wrapper)
            val descriptor = SixBundle.read(bundle).extension
            assertEquals(bundle.resolveSibling("${bundle.fileName.toString().removeSuffix(".six")}.crx"), crx)
            assertEquals(bundle.resolveSibling("${bundle.fileName.toString().removeSuffix(".six")}-wrapper.apk"), wrapper)
            BuildVerificationResult(descriptor.extensionId, descriptor.wrapperPackage, descriptor.wrapperVersionCode)
        }
        val output = root.resolve("session.json")

        val result = execute(command, prepareArgs(profilePath, artifacts, output))

        assertEquals(0, result.exitCode, result.error)
        assertEquals(6, verified.size)
        val config = ProbeSessionConfigJson.readStrict(output)
        assertEquals("20260802-143000", config.sessionId)
        assertEquals(profile.browserPackage, config.browserPackage)
        assertEquals(profile.installationApkSetSha256, config.browserApkSetSha256)
        assertEquals(ApiProbeCatalog.PROBE_GROUP_IDS.sorted(), config.bindings.map { it.groupId })
        assertTrue("session config=" in result.output)
    }

    @Test
    fun `wrong group name profile digest external artifact와 existing output을 거부한다`() = withWorkspace { root ->
        val profile = profile()
        val profilePath = root.resolve("profile.json").also { it.writeText(BrowserProfileJson.encode(profile)) }
        val good = writeArtifacts(root.resolve("good"), profile.canonicalSha256())
        val wrongName = writeArtifacts(root.resolve("wrong-name"), profile.canonicalSha256(), wrongNameGroup = "network")
        val wrongProfile = writeArtifacts(root.resolve("wrong-profile"), "f".repeat(64))
        val output = root.resolve("session.json")

        assertTrue(execute(ProbePrepareSessionCommand(artifactVerifier = ::verifyFixture), prepareArgs(profilePath, wrongName, output)).exitCode != 0)
        assertFalse(Files.exists(output))
        assertTrue(execute(ProbePrepareSessionCommand(artifactVerifier = ::verifyFixture), prepareArgs(profilePath, wrongProfile, output)).exitCode != 0)
        assertFalse(Files.exists(output))

        val damagedGroup = good.keys.sorted().first()
        good.getValue(damagedGroup).resolveSibling("$damagedGroup.crx").writeBytes("bad".toByteArray())
        val verifier = ProbePrepareSessionCommand { bundle, crx, wrapper ->
            require(crx.readBytes().contentEquals("crx-$damagedGroup".toByteArray())) { "external CRX mismatch" }
            verifyFixture(bundle, crx, wrapper)
        }
        assertTrue(execute(verifier, prepareArgs(profilePath, good, output)).exitCode != 0)
        assertFalse(Files.exists(output))

        output.writeText("user-owned")
        val existing = execute(ProbePrepareSessionCommand(artifactVerifier = ::verifyFixture), prepareArgs(profilePath, good, output))
        assertTrue(existing.exitCode != 0)
        assertEquals("user-owned", Files.readString(output))
    }

    @Test
    fun `publication race는 경쟁자 output을 보존하고 임시 파일을 정리한다`() = withWorkspace { root ->
        val profile = profile()
        val profilePath = root.resolve("profile.json").also { it.writeText(BrowserProfileJson.encode(profile)) }
        val artifacts = writeArtifacts(root.resolve("artifacts"), profile.canonicalSha256())
        val output = root.resolve("session.json")
        val nio = NioProbePublicationFileOps()
        val publication = object : ProbePublicationFileOps by nio {
            override fun moveNew(source: Path, target: Path) {
                target.writeText("user-owned")
                nio.moveNew(source, target)
            }
        }

        val result = execute(
            ProbePrepareSessionCommand(publication, ::verifyFixture),
            prepareArgs(profilePath, artifacts, output),
        )

        assertTrue(result.exitCode != 0)
        assertEquals("user-owned", output.readText())
        assertFalse(Files.list(root).use { entries ->
            entries.anyMatch { it.fileName.toString().contains(".tmp-") }
        })
    }

    @Test
    fun `artifact set은 group 누락 중복 unknown을 거부한다`() = withWorkspace { root ->
        val profile = profile()
        val profilePath = root.resolve("profile.json").also { it.writeText(BrowserProfileJson.encode(profile)) }
        val artifacts = writeArtifacts(root, profile.canonicalSha256())
        val output = root.resolve("session.json")
        val missing = artifacts.entries.toList().dropLast(1).associate { it.toPair() }
        assertTrue(execute(ProbePrepareSessionCommand(artifactVerifier = ::verifyFixture), prepareArgs(profilePath, missing, output)).exitCode != 0)
        val duplicateArgs = prepareArgs(profilePath, artifacts, output).toMutableList().apply {
            addAll(listOf("--artifact-set", "base-runtime=${artifacts.getValue("base-runtime")}"))
        }
        assertTrue(execute(ProbePrepareSessionCommand(artifactVerifier = ::verifyFixture), duplicateArgs).exitCode != 0)
    }

    private fun writeArtifacts(
        root: Path,
        profileDigest: String,
        wrongNameGroup: String? = null,
    ): Map<String, Path> {
        Files.createDirectories(root)
        return ApiProbeCatalog.PROBE_GROUP_IDS.sorted().associateWith { groupId ->
            val bundle = root.resolve("$groupId.six")
            val wrapper = root.resolve("$groupId-wrapper.apk").also { it.writeBytes("apk-$groupId".toByteArray()) }
            root.resolve("$groupId.crx").writeBytes("crx-$groupId".toByteArray())
            val extensionId = ('a'.code + ApiProbeCatalog.PROBE_GROUP_IDS.sorted().indexOf(groupId))
                .toChar().toString().repeat(32)
            val bundleName = "SIX API Probe · ${if (wrongNameGroup == groupId) "wrong" else groupId}"
            SixBundle.write(
                bundle,
                wrapper,
                ExtensionDescriptor(
                    extensionId = extensionId,
                    name = bundleName,
                    version = "1.0.0",
                    sourceKind = "SOURCE_DIRECTORY",
                    crxSha256 = "1".repeat(64),
                    wrapperSha256 = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(wrapper.readBytes()).joinToString("") { "%02x".format(it) },
                    wrapperPackage = "dev.sixbridge.ext.$extensionId",
                    wrapperVersionCode = 1,
                    signerSha256 = "b".repeat(64),
                ),
                CompatibilityReport(
                    status = CompatibilityStatus.COMPATIBLE,
                    manifestVersion = 3,
                    extensionName = bundleName,
                    extensionVersion = "1.0.0",
                    declaredPermissions = emptySet(), usedApis = emptySet(), unsupportedApis = emptySet(),
                    issues = emptyList(), conversionState = ConversionState.PASSTHROUGH,
                    sourceSha256 = "3".repeat(64),
                    browserProfileSha256 = profileDigest,
                ),
            )
            bundle
        }
    }

    private fun verifyFixture(bundle: Path, _crx: Path, _wrapper: Path): BuildVerificationResult {
        val descriptor = SixBundle.read(bundle).extension
        return BuildVerificationResult(descriptor.extensionId, descriptor.wrapperPackage, descriptor.wrapperVersionCode)
    }

    private fun prepareArgs(profile: Path, artifacts: Map<String, Path>, output: Path): List<String> = buildList {
        addAll(listOf("--session-id", "20260802-143000", "--profile", profile.toString()))
        artifacts.toSortedMap().forEach { (group, bundle) -> addAll(listOf("--artifact-set", "$group=$bundle")) }
        addAll(listOf("--output", output.toString()))
    }

    private fun execute(command: Any, arguments: List<String>): Execution {
        val out = StringWriter()
        val err = StringWriter()
        val line = CommandLine(command)
        line.out = PrintWriter(out)
        line.err = PrintWriter(err)
        return Execution(line.execute(*arguments.toTypedArray()), out.toString(), err.toString())
    }

    private fun profile() = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 300020061,
        majorVersion = 30,
        supportedApis = emptySet(),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        installationApkSetSha256 = "c".repeat(64),
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        profileDifferences = emptyList(),
    )

    private fun withWorkspace(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("probe-session-cli-test-")
        try { block(root) } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }

    private data class Execution(val exitCode: Int, val output: String, val error: String)
}
