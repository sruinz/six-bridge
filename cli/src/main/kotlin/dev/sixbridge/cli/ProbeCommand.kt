package dev.sixbridge.cli

import dev.sixbridge.core.SixBridgeUserInputException
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.probe.catalog.ApiProbeCatalog
import dev.sixbridge.probe.evidence.ProbeEvidence
import dev.sixbridge.probe.evidence.ProbeEvidenceJson
import dev.sixbridge.probe.generate.ProbeSourceGenerator
import dev.sixbridge.probe.profile.CapabilityEvidenceSummary
import dev.sixbridge.probe.profile.CapabilityProfileBuilder
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.Comparator
import java.util.concurrent.Callable
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.ParentCommand
import picocli.CommandLine.Spec

@Command(
    name = "probe",
    description = ["삼성 인터넷 API capability probe를 생성하고 evidence를 검증합니다."],
    subcommands = [
        ProbeGenerateCommand::class,
        ProbeVerifyEvidenceCommand::class,
        ProbeBuildProfileCommand::class,
    ],
)
class ProbeCommand : Runnable {
    @ParentCommand
    lateinit var root: SixBridgeCommand

    override fun run() {
        CommandLine(this).usage(System.out)
    }
}

@Command(
    name = "generate",
    mixinStandardHelpOptions = true,
    description = ["6개 API probe MV3 source를 결정론적으로 생성합니다."],
)
class ProbeGenerateCommand : Callable<Int> {
    @Option(names = ["--output"], required = true)
    lateinit var output: Path

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int = probeUserInput {
        val catalog = loadProbeCatalog()
        val target = output.toAbsolutePath().normalize()
        val parent = prepareOutputParent(target)
        require(!Files.exists(target, NOFOLLOW_LINKS)) {
            "API probe output은 존재하지 않아야 합니다."
        }
        val parentIdentity = directoryIdentity(parent)
        val staging = Files.createTempDirectory(parent, ".${target.fileName}.cli-staging-")
        try {
            val generatedRoot = staging.resolve("generated")
            val generated = ProbeSourceGenerator().generate(catalog, generatedRoot)
            require(generated.map { it.groupId }.toSet() == ApiProbeCatalog.PROBE_GROUP_IDS) {
                "API probe source group coverage가 올바르지 않습니다."
            }
            require(directoryIdentity(parent) == parentIdentity) {
                "API probe output parent가 생성 중 변경되었습니다."
            }
            Files.move(generatedRoot, target)
            require(Files.isDirectory(target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                "API probe output publication을 검증할 수 없습니다."
            }
            spec.commandLine().out.println("catalog SHA-256=${catalog.sha256}")
            spec.commandLine().out.println("생성 그룹=${generated.size}")
            spec.commandLine().out.println("출력=${target}")
            0
        } finally {
            deleteOwnedTree(staging)
        }
    }
}

@Command(
    name = "verify-evidence",
    mixinStandardHelpOptions = true,
    description = ["수집된 6개 group evidence의 provenance와 case coverage를 검증합니다."],
)
class ProbeVerifyEvidenceCommand : Callable<Int> {
    @Option(names = ["--input"], required = true)
    lateinit var input: Path

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int = probeUserInput {
        val catalog = loadProbeCatalog()
        val evidence = readEvidenceDirectory(input)
        val summary = CapabilityProfileBuilder(catalog).verify(evidence)
        printEvidenceSummary(spec.commandLine().out, summary)
        spec.commandLine().out.println("증거 검증 완료")
        0
    }
}

@Command(
    name = "build-profile",
    mixinStandardHelpOptions = true,
    description = ["base profile과 검증된 evidence로 schema v4 후보 profile을 생성합니다."],
)
class ProbeBuildProfileCommand : Callable<Int> {
    @Option(names = ["--base"], required = true)
    lateinit var base: Path

    @Option(names = ["--evidence"], required = true)
    lateinit var evidence: Path

    @Option(names = ["--output"], required = true)
    lateinit var output: Path

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int = probeUserInput {
        val catalog = loadProbeCatalog()
        val baseProfile = BrowserProfileJson.decode(readStrictFile(base, MAX_PROFILE_BYTES))
        val decodedEvidence = readEvidenceDirectory(evidence)
        val builder = CapabilityProfileBuilder(catalog)
        val summary = builder.verify(decodedEvidence)
        val candidate = builder.build(baseProfile, decodedEvidence)
        val encoded = BrowserProfileJson.encode(candidate)
        val decoded = BrowserProfileJson.decode(encoded)
        require(decoded == candidate && decoded.canonicalSha256() == candidate.canonicalSha256()) {
            "후보 browser profile self-verification에 실패했습니다."
        }
        publishNewProfile(output, encoded, candidate.canonicalSha256())
        printEvidenceSummary(spec.commandLine().out, summary)
        spec.commandLine().out.println("profile SHA-256=${candidate.canonicalSha256()}")
        spec.commandLine().out.println("후보 profile=${output.toAbsolutePath().normalize()}")
        0
    }
}

private fun loadProbeCatalog(): ApiProbeCatalog = ApiProbeCatalog.load(
    checkNotNull(ProbeCommand::class.java.getResourceAsStream(
        "/catalog/sbrowser-30-public-apis.json",
    )) { "bundled API probe catalog를 찾을 수 없습니다." },
)

private fun readEvidenceDirectory(path: Path): List<ProbeEvidence> {
    val root = path.toAbsolutePath().normalize()
    require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
        "API probe evidence input은 symlink가 아닌 directory여야 합니다."
    }
    val identity = directoryIdentity(root)
    val expectedNames = ApiProbeCatalog.PROBE_GROUP_IDS.mapTo(linkedSetOf()) { "$it.json" }
    val entries = Files.list(root).use { stream -> stream.toList() }
    require(entries.map { it.fileName.toString() }.toSet() == expectedNames) {
        "API probe evidence directory는 정확한 6개 group JSON만 포함해야 합니다."
    }
    require(entries.size == expectedNames.size) {
        "API probe evidence 파일은 중복될 수 없습니다."
    }
    val decoded = entries.sortedBy { it.fileName.toString() }.map { file ->
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) {
            "API probe evidence는 symlink가 아닌 regular file이어야 합니다."
        }
        ProbeEvidenceJson.decodeStrict(readStrictFile(file, MAX_EVIDENCE_BYTES))
    }
    require(directoryIdentity(root) == identity) {
        "API probe evidence directory가 검증 중 변경되었습니다."
    }
    val finalNames = Files.list(root).use { stream ->
        stream.map { it.fileName.toString() }.toList()
    }
    require(finalNames.size == expectedNames.size && finalNames.toSet() == expectedNames) {
        "API probe evidence directory가 검증 중 변경되었습니다."
    }
    return decoded
}

private fun readStrictFile(path: Path, maxBytes: Int): String {
    val file = path.toAbsolutePath().normalize()
    require(Files.isRegularFile(file, NOFOLLOW_LINKS) && !Files.isSymbolicLink(file)) {
        "입력은 symlink가 아닌 regular file이어야 합니다."
    }
    val before = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(before.size() in 1..maxBytes.toLong()) { "입력 파일 크기가 제한을 초과했습니다." }
    val options: Set<OpenOption> = setOf(READ, NOFOLLOW_LINKS)
    val bytes = Files.newByteChannel(file, options).use { channel ->
        Channels.newInputStream(channel).use { it.readNBytes(maxBytes + 1) }
    }
    require(bytes.size <= maxBytes) { "입력 파일 크기가 제한을 초과했습니다." }
    val after = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    require(fileIdentity(before) == fileIdentity(after)) {
        "입력 파일이 검증 중 변경되었습니다."
    }
    return bytes.toString(Charsets.UTF_8)
}

private fun publishNewProfile(output: Path, encoded: String, expectedDigest: String) {
    val target = output.toAbsolutePath().normalize()
    val parent = prepareOutputParent(target)
    require(!Files.exists(target, NOFOLLOW_LINKS)) {
        "후보 browser profile output은 존재하지 않아야 합니다."
    }
    val parentIdentity = directoryIdentity(parent)
    val temporary = Files.createTempFile(parent, ".${target.fileName}.tmp-", ".json")
    var publishedByThisCall = false
    try {
        FileChannel.open(temporary, WRITE, NOFOLLOW_LINKS).use { channel ->
            Channels.newOutputStream(channel).write(encoded.toByteArray(Charsets.UTF_8))
            channel.force(true)
        }
        val decoded = BrowserProfileJson.decode(readStrictFile(temporary, MAX_PROFILE_BYTES))
        require(decoded.canonicalSha256() == expectedDigest) {
            "후보 browser profile 임시 파일 digest가 일치하지 않습니다."
        }
        require(directoryIdentity(parent) == parentIdentity) {
            "후보 browser profile parent가 생성 중 변경되었습니다."
        }
        Files.createLink(target, temporary)
        publishedByThisCall = true
        require(directoryIdentity(parent) == parentIdentity) {
            "후보 browser profile parent가 publication 중 변경되었습니다."
        }
        val published = BrowserProfileJson.decode(readStrictFile(target, MAX_PROFILE_BYTES))
        require(published.canonicalSha256() == expectedDigest) {
            "후보 browser profile publication digest가 일치하지 않습니다."
        }
    } catch (error: Exception) {
        if (publishedByThisCall && Files.exists(target, NOFOLLOW_LINKS)) {
            val sameFile = runCatching { Files.isSameFile(temporary, target) }.getOrDefault(false)
            if (sameFile) Files.deleteIfExists(target)
        }
        throw error
    } finally {
        Files.deleteIfExists(temporary)
    }
}

private fun prepareOutputParent(target: Path): Path {
    val parent = requireNotNull(target.parent) { "output path에 parent가 필요합니다." }
    Files.createDirectories(parent)
    require(Files.isDirectory(parent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(parent)) {
        "output parent는 symlink가 아닌 directory여야 합니다."
    }
    return parent
}

private fun directoryIdentity(path: Path): Any =
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS).let { attributes ->
        attributes.fileKey() ?: listOf(path.toRealPath(NOFOLLOW_LINKS), attributes.creationTime())
    }

private fun fileIdentity(attributes: BasicFileAttributes): List<Any?> = listOf(
    attributes.fileKey(),
    attributes.creationTime(),
    attributes.lastModifiedTime(),
    attributes.size(),
)

private fun deleteOwnedTree(root: Path) {
    if (!Files.exists(root, NOFOLLOW_LINKS)) return
    Files.walk(root).use { stream ->
        stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
    }
}

private fun printEvidenceSummary(
    output: java.io.PrintWriter,
    summary: CapabilityEvidenceSummary,
) {
    output.println("세션=${summary.sessionId}")
    output.println("catalog SHA-256=${summary.catalogSha256}")
    output.println("그룹=${summary.groupCaseCounts.size}/${ApiProbeCatalog.PROBE_GROUP_IDS.size}")
    output.println("case=${summary.totalCaseCount}/${summary.expectedTotalCaseCount}")
    output.println(
        "브라우저=${summary.browserPackage} ${summary.browserVersionName}" +
            "(${summary.browserVersionCode})",
    )
    output.println("APK set SHA-256=${summary.browserApkSetSha256}")
    output.println("브라우저 서명 SHA-256=${summary.browserSignerSha256}")
    output.println("래퍼 서명 SHA-256=${summary.wrapperSignerSha256}")
    summary.groupCaseCounts.toSortedMap().forEach { (group, count) ->
        output.println("그룹 $group=$count, extensionId=${summary.extensionIds.getValue(group)}")
    }
}

private inline fun <T> probeUserInput(operation: () -> T): T = try {
    operation()
} catch (error: SixBridgeUserInputException) {
    throw error
} catch (error: IllegalArgumentException) {
    throw SixBridgeUserInputException(error.message ?: "API probe 입력이 올바르지 않습니다.", error)
} catch (error: IOException) {
    throw SixBridgeUserInputException(error.message ?: "API probe 파일 작업에 실패했습니다.", error)
} catch (error: SecurityException) {
    throw SixBridgeUserInputException(error.message ?: "API probe 파일 접근이 거부되었습니다.", error)
}

private const val MAX_EVIDENCE_BYTES = 1_048_576
private const val MAX_PROFILE_BYTES = 4 * 1_048_576
