package dev.sixbridge.cli

import dev.sixbridge.core.SixBridgeService
import dev.sixbridge.core.SixBridgeUserInputException
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterApprovalException
import dev.sixbridge.core.conversion.ConversionAuthorization
import dev.sixbridge.core.conversion.ConversionPlanningException
import dev.sixbridge.core.conversion.ConversionUnavailableException
import dev.sixbridge.core.conversion.ForcedCompatibilityProvenance
import dev.sixbridge.core.conversion.MissingAdapterApprovalException
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.crx.InvalidCrxException
import dev.sixbridge.core.device.AdbDeviceClient
import dev.sixbridge.core.device.BuildArtifactVerifier
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.input.ExtensionInputResolver
import dev.sixbridge.core.input.UnsupportedInputException
import dev.sixbridge.core.profile.BrowserProfileExtractor
import dev.sixbridge.core.profile.BrowserProfileJson
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    exitProcess(createCommandLine().execute(*args))
}

fun createCommandLine(
    serviceFactory: (Path) -> SixBridgeService = ::createDesktopService,
): CommandLine {
    val command = CommandLine(SixBridgeCommand(serviceFactory))
    command.isOverwrittenOptionsAllowed = false
    command.executionExceptionHandler = CommandLine.IExecutionExceptionHandler { error, line, _ ->
        line.err.println("오류: ${error.message}")
        if (System.getenv("SIXBRIDGE_DEBUG") == "1") {
            error.printStackTrace(line.err)
        }
        when (error) {
            is MissingAdapterApprovalException -> 4
            is AdapterApprovalException,
            is ConversionPlanningException,
            is ConversionUnavailableException,
            is SixBridgeUserInputException,
            is UnsupportedInputException,
            is InvalidCrxException,
            -> 2
            else -> 1
        }
    }
    return command
}

@Command(
    name = "sixbridge",
    mixinStandardHelpOptions = true,
    version = ["SIX Bridge 0.1.3-SNAPSHOT"],
    description = ["Chrome/Edge 확장을 삼성 인터넷 개발 확장 래퍼로 변환합니다."],
    subcommands = [
        ProfileCommand::class,
        AnalyzeCommand::class,
        DraftCommand::class,
        VerifyDraftCommand::class,
        VerifyCrxCommand::class,
        VerifyBuildCommand::class,
        BuildCommand::class,
        ProbeCommand::class,
        DeviceCommand::class,
    ],
)
class SixBridgeCommand(
    private val serviceFactory: (Path) -> SixBridgeService = ::createDesktopService,
) : Runnable {
    @Option(
        names = ["--workspace"],
        description = ["프로젝트 루트 (기본값: 현재 디렉터리)"],
    )
    var workspace: Path = Path.of("").toAbsolutePath().normalize()

    override fun run() {
        CommandLine(this).usage(System.out)
    }

    fun service(): SixBridgeService = serviceFactory(workspace)
}

internal fun createDesktopService(workspace: Path): SixBridgeService =
    SixBridgeService(
        workspaceRoot = workspace,
        inputResolver = ExtensionInputResolver(
            DesktopChromeCrxDownloader(),
            CliSecureWorkspace::createExtractionRoot,
        ),
        secureConversionRoot = CliSecureWorkspace.root,
    )

@Command(
    name = "profile",
    mixinStandardHelpOptions = true,
    description = ["삼성 브라우저 APK에서 버전·API·래퍼 계약 프로필을 추출합니다."],
)
class ProfileCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: SixBridgeCommand

    @Option(names = ["--browser-apk"], required = true)
    lateinit var browserApk: Path

    @Option(names = ["--output"])
    var output: Path? = null

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        val profile = BrowserProfileExtractor().extract(browserApk)
        val target = output ?: root.workspace.resolve(
            "profiles/sbrowser-${profile.versionName}.json",
        )
        Files.createDirectories(target.toAbsolutePath().parent)
        target.writeText(BrowserProfileJson.encode(profile))
        spec.commandLine().out.println("profile=${target.toAbsolutePath()}")
        spec.commandLine().out.println("support=${profile.support.name.lowercase()}")
        spec.commandLine().out.println("apis=${profile.supportedApis.size}")
        return 0
    }
}

@Command(
    name = "analyze",
    mixinStandardHelpOptions = true,
    description = ["확장 Manifest V3·권한·chrome.* API 호환성을 분석합니다."],
)
class AnalyzeCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: SixBridgeCommand

    @Option(names = ["--input"], required = true)
    lateinit var input: String

    @Option(names = ["--profile"], required = true)
    lateinit var profilePath: Path

    @Option(names = ["--output"])
    var output: Path? = null

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        val profile = readProfile(profilePath)
        val report = root.service().analyze(input, profile)
        val encoded = SixBundle.encodeCompatibility(report)
        if (output == null) {
            spec.commandLine().out.println(encoded)
        } else {
            Files.createDirectories(output!!.toAbsolutePath().parent)
            output!!.writeText(encoded)
            spec.commandLine().out.println("report=${output!!.toAbsolutePath()}")
        }
        return if (report.status == CompatibilityStatus.BLOCKED ||
            report.status == CompatibilityStatus.UNKNOWN
        ) {
            2
        } else {
            0
        }
    }
}

@Command(
    name = "draft",
    mixinStandardHelpOptions = true,
    description = ["모바일 변환 결과를 서명된 SixDraft로 생성하고 즉시 검증합니다."],
)
class DraftCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: SixBridgeCommand

    @Option(names = ["--input"], required = true)
    lateinit var input: String

    @Option(names = ["--profile"], required = true)
    lateinit var profilePath: Path

    @Option(names = ["--output"], required = true)
    lateinit var output: Path

    @Option(
        names = ["--identity"],
        description = ["markerless 업데이트 ZIP에 사용할 source project identity"],
    )
    var projectIdentity: String? = null

    @Option(names = ["--approve-adapter"])
    var approvedAdapterIds: MutableSet<String> = linkedSetOf()

    @Option(
        names = ["--force-compatibility-unknown"],
        description = ["api_usage_dynamic UNKNOWN의 위험을 확인하고 이 작업만 강제 변환"],
    )
    var forceCompatibilityUnknown: Boolean = false

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        val profile = readProfile(profilePath)
        val authorization = if (forceCompatibilityUnknown) {
            ConversionAuthorization.forceUnknownApiUsage()
        } else {
            ConversionAuthorization.NONE
        }
        val result = root.service().draft(
            input = input,
            profile = profile,
            outputDraft = output,
            projectIdentity = projectIdentity,
            approvedAdapterIds = approvedAdapterIds,
            protectedInputs = listOf(profilePath),
            authorization = authorization,
        )
        spec.commandLine().out.println(
            "conversion=${result.conversionState.name.lowercase()}",
        )
        spec.commandLine().out.println("adapters=${result.adapterIds.joinToString(",")}")
        spec.commandLine().out.println("sourceSha256=${result.sourceSha256}")
        spec.commandLine().out.println("transformedSha256=${result.transformedSha256}")
        spec.commandLine().out.println("draft=${result.draft}")
        printForcedCompatibility(
            spec,
            SixDraft.read(result.draft).payload.plan.forcedCompatibility,
        )
        return 0
    }
}

@Command(
    name = "verify-draft",
    hidden = true,
    mixinStandardHelpOptions = true,
    description = ["SixDraft를 strict reader로 독립 검증합니다."],
)
class VerifyDraftCommand : Callable<Int> {
    @Option(names = ["--input"], required = true)
    lateinit var input: Path

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        SixDraft.read(input)
        spec.commandLine().out.println("SixDraft verified")
        return 0
    }
}

@Command(
    name = "verify-crx",
    hidden = true,
    mixinStandardHelpOptions = true,
    description = ["CRX3 서명과 공개 키 기반 extension ID를 독립 검증합니다."],
)
class VerifyCrxCommand : Callable<Int> {
    @Option(names = ["--input"], required = true)
    lateinit var input: Path

    @Option(names = ["--expected-id"], required = true)
    lateinit var expectedId: String

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        val declaredSize = Files.size(input)
        require(declaredSize in 1..MAX_CRX_BYTES) {
            "CRX3 파일 크기가 제한을 초과합니다."
        }
        val bytes = Files.readAllBytes(input)
        require(bytes.size.toLong() == declaredSize) {
            "CRX3 파일이 검증 중 변경되었습니다."
        }
        val proof = Crx3.parse(bytes)
        require(proof.extensionId == expectedId) {
            "CRX3 공개 키에서 유도한 extension ID가 예상 ID와 일치하지 않습니다."
        }
        spec.commandLine().out.println("extensionId=${proof.extensionId}")
        spec.commandLine().out.println("CRX3 verified")
        return 0
    }

    private companion object {
        const val MAX_CRX_BYTES = 128L * 1024 * 1024
    }
}

@Command(
    name = "verify-build",
    hidden = true,
    mixinStandardHelpOptions = true,
    description = ["CRX, wrapper APK와 SixBundle의 결합 계약을 독립 검증합니다."],
)
class VerifyBuildCommand : Callable<Int> {
    @Option(names = ["--bundle"], required = true)
    lateinit var bundle: Path

    @Option(names = ["--crx"], required = true)
    lateinit var crx: Path

    @Option(names = ["--wrapper"], required = true)
    lateinit var wrapper: Path

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        val result = BuildArtifactVerifier().verify(bundle, crx, wrapper)
        spec.commandLine().out.println("extensionId=${result.extensionId}")
        spec.commandLine().out.println("wrapperPackage=${result.wrapperPackage}")
        spec.commandLine().out.println("wrapperVersionCode=${result.wrapperVersionCode}")
        spec.commandLine().out.println("Build artifacts verified")
        return 0
    }
}

@Command(
    name = "build",
    mixinStandardHelpOptions = true,
    description = ["CRX3, 서명된 Android 래퍼 APK와 SixBundle을 생성합니다."],
)
class BuildCommand : Callable<Int> {
    @ParentCommand
    lateinit var root: SixBridgeCommand

    @Option(names = ["--input"], required = true)
    lateinit var input: String

    @Option(names = ["--profile"], required = true)
    lateinit var profilePath: Path

    @Option(names = ["--output"], description = ["출력 .six 경로"])
    var output: Path? = null

    @Option(
        names = ["--identity"],
        description = ["markerless 업데이트 ZIP에 사용할 source project identity"],
    )
    var projectIdentity: String? = null

    @Option(names = ["--approve-adapter"])
    var approvedAdapterIds: MutableSet<String> = linkedSetOf()

    @Option(
        names = ["--force-compatibility-unknown"],
        description = ["api_usage_dynamic UNKNOWN의 위험을 확인하고 이 작업만 강제 변환"],
    )
    var forceCompatibilityUnknown: Boolean = false

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        val profile = readProfile(profilePath)
        val authorization = if (forceCompatibilityUnknown) {
            ConversionAuthorization.forceUnknownApiUsage()
        } else {
            ConversionAuthorization.NONE
        }
        val result = root.service().build(
            input = input,
            profile = profile,
            outputBundle = output,
            projectIdentity = projectIdentity,
            approvedAdapterIds = approvedAdapterIds,
            protectedInputs = listOf(profilePath),
            authorization = authorization,
        )
        spec.commandLine().out.println(
            "conversion=${result.compatibility.conversionState.name.lowercase()}",
        )
        spec.commandLine().out.println("adapters=${result.adapterIds.joinToString(",")}")
        spec.commandLine().out.println("sourceSha256=${result.sourceSha256}")
        spec.commandLine().out.println("transformedSha256=${result.transformedSha256}")
        spec.commandLine().out.println("extensionId=${result.extensionId}")
        spec.commandLine().out.println("compatibility=${result.compatibility.status.name.lowercase()}")
        spec.commandLine().out.println("crx=${result.crx.toAbsolutePath()}")
        spec.commandLine().out.println("wrapper=${result.wrapperApk.toAbsolutePath()}")
        spec.commandLine().out.println("bundle=${result.bundle.toAbsolutePath()}")
        printForcedCompatibility(spec, result.forcedCompatibility)
        return 0
    }
}

private fun printForcedCompatibility(
    spec: CommandLine.Model.CommandSpec,
    provenance: ForcedCompatibilityProvenance?,
) {
    provenance ?: return
    spec.commandLine().out.println("forcedCompatibility=true")
    spec.commandLine().out.println(
        "originalCompatibility=${provenance.originalStatus.name.lowercase()}",
    )
    spec.commandLine().out.println("forcedIssueCodes=${provenance.issueCodes.joinToString(",")}")
    spec.commandLine().err.println(
        "경고: 강제 변환 결과는 삼성 인터넷 호환성을 보장하지 않습니다.",
    )
}

private fun readProfile(path: Path) = try {
    BrowserProfileJson.decode(path.readText())
} catch (error: Exception) {
    throw SixBridgeUserInputException(
        "브라우저 profile을 읽을 수 없습니다. ${error.message}",
        error,
    )
}

@Command(
    name = "device",
    description = ["ADB로 SixBundle 래퍼를 설치하고 삼성 인터넷 인식 증거를 수집합니다."],
    subcommands = [DeviceInstallCommand::class, DeviceSmokeCommand::class],
)
class DeviceCommand : Runnable {
    @ParentCommand
    lateinit var root: SixBridgeCommand

    override fun run() {
        CommandLine(this).usage(System.out)
    }
}

@Command(
    name = "install",
    mixinStandardHelpOptions = true,
    description = ["SixBundle의 래퍼 APK를 사용자 지정 ADB 기기에 설치·업데이트합니다."],
)
class DeviceInstallCommand : Callable<Int> {
    @ParentCommand
    lateinit var device: DeviceCommand

    @Option(names = ["--bundle"], required = true)
    lateinit var bundle: Path

    @Option(names = ["--serial"], required = true)
    lateinit var serial: String

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        val result = AdbDeviceClient().install(bundle, serial)
        spec.commandLine().out.println("package=${result.packageName}")
        spec.commandLine().out.println("result=${result.output}")
        return 0
    }
}

@Command(
    name = "smoke",
    mixinStandardHelpOptions = true,
    description = ["래퍼 패키지·six://ex 인식 여부와 관련 logcat 증거를 수집합니다."],
)
class DeviceSmokeCommand : Callable<Int> {
    @ParentCommand
    lateinit var device: DeviceCommand

    @Option(names = ["--bundle"], required = true)
    lateinit var bundle: Path

    @Option(names = ["--serial"], required = true)
    lateinit var serial: String

    @Option(names = ["--evidence-dir"])
    var evidenceDirectory: Path? = null

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        val evidence = evidenceDirectory
            ?: device.root.workspace.resolve(".local/evidence")
        val result = AdbDeviceClient().smoke(bundle, serial, evidence)
        spec.commandLine().out.println("installed=${result.installed}")
        spec.commandLine().out.println("discovery=${result.discoveryActivityResolved}")
        spec.commandLine().out.println("provider=${result.providerReadable}")
        spec.commandLine().out.println("evidence=${result.evidenceLog.toAbsolutePath()}")
        return if (
            result.installed &&
            result.discoveryActivityResolved &&
            result.providerReadable
        ) {
            0
        } else {
            3
        }
    }
}
