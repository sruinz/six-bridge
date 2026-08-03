package dev.sixbridge.cli

import dev.sixbridge.core.SixBridgeUserInputException
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.device.BuildArtifactVerifier
import dev.sixbridge.core.device.BuildVerificationResult
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.probe.catalog.ApiProbeCatalog
import dev.sixbridge.probe.evidence.ProbeEvidence
import dev.sixbridge.probe.evidence.ProbeEvidenceJson
import dev.sixbridge.probe.evidence.ProbeManifestLoadIsolation
import dev.sixbridge.probe.evidence.ProbeManifestLoadIsolationJson
import dev.sixbridge.probe.generate.ProbeSourceGenerator
import dev.sixbridge.probe.profile.CapabilityEvidenceSummary
import dev.sixbridge.probe.profile.CapabilityProfileBuilder
import dev.sixbridge.probe.session.ProbeSessionBinding
import dev.sixbridge.probe.session.ProbeSessionCaseDescriptor
import dev.sixbridge.probe.session.ProbeSessionConfig
import dev.sixbridge.probe.session.ProbeSessionConfigJson
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
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
        ProbePrepareSessionCommand::class,
        ProbeVerifyEvidenceCommand::class,
        ProbeBuildProfileCommand::class,
    ],
)
class ProbeCommand : Runnable {
    @ParentCommand
    lateinit var root: SixBridgeCommand

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun run() {
        spec.commandLine().usage(spec.commandLine().out)
    }
}

@Command(
    name = "prepare-session",
    mixinStandardHelpOptions = true,
    description = ["검증된 6개 probe build artifact로 다중-origin session config를 생성합니다."],
)
internal class ProbePrepareSessionCommand(
    private val publication: ProbePublicationFileOps = NioProbePublicationFileOps(),
    private val artifactVerifier: (Path, Path, Path) -> BuildVerificationResult =
        BuildArtifactVerifier()::verify,
) : Callable<Int> {
    @Option(names = ["--session-id"], required = true)
    lateinit var sessionId: String

    @Option(names = ["--profile"], required = true)
    lateinit var profile: Path

    @Option(names = ["--artifact-set"], required = true)
    var artifactSets: MutableList<String> = mutableListOf()

    @Option(names = ["--output"], required = true)
    lateinit var output: Path

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int = probeUserInput {
        val baseProfile = BrowserProfileJson.decode(readStrictFile(profile, MAX_PROFILE_BYTES))
        val profileDigest = baseProfile.canonicalSha256()
        val catalog = loadProbeCatalog()
        val bundles = parseArtifactSets(artifactSets)
        val bindings = bundles.toSortedMap().map { (groupId, bundle) ->
            val paths = artifactPaths(bundle)
            listOf(paths.bundle, paths.crx, paths.wrapper).forEach { path ->
                require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                    "probe build artifact는 symlink가 아닌 regular file이어야 합니다: $path"
                }
            }
            val contents = SixBundle.read(paths.bundle)
            val descriptor = contents.extension
            require(descriptor.name == "SIX API Probe · $groupId") {
                "probe bundle name이 groupId와 일치하지 않습니다: $groupId"
            }
            require(contents.compatibility.browserProfileSha256 == profileDigest) {
                "probe bundle browser profile digest가 base profile과 일치하지 않습니다: $groupId"
            }
            val verification = artifactVerifier(paths.bundle, paths.crx, paths.wrapper)
            require(verification.extensionId == descriptor.extensionId &&
                verification.wrapperPackage == descriptor.wrapperPackage &&
                verification.wrapperVersionCode == descriptor.wrapperVersionCode
            ) { "probe build artifact 검증 결과가 SixBundle descriptor와 일치하지 않습니다: $groupId" }
            ProbeSessionBinding(
                groupId = groupId,
                extensionId = descriptor.extensionId,
                wrapperSignerSha256 = descriptor.signerSha256,
                cases = ProbeSessionCaseDescriptor.forGroup(catalog, groupId),
            )
        }
        val config = ProbeSessionConfig(
            schemaVersion = 1,
            sessionId = sessionId,
            catalogSha256 = catalog.sha256,
            browserPackage = baseProfile.browserPackage,
            browserVersionName = baseProfile.versionName,
            browserVersionCode = baseProfile.versionCode,
            browserApkSetSha256 = baseProfile.installationApkSetSha256,
            browserSignerSha256 = baseProfile.sourceApkSignerSha256,
            bindings = bindings,
        ).also(ProbeSessionConfig::validate)
        publishNewSessionConfig(output, config, publication)
        spec.commandLine().out.println("session ID=${config.sessionId}")
        spec.commandLine().out.println("catalog SHA-256=${config.catalogSha256}")
        spec.commandLine().out.println("session config=${output.toAbsolutePath().normalize()}")
        0
    }

    private fun parseArtifactSets(values: List<String>): Map<String, Path> {
        require(values.size == ApiProbeCatalog.PROBE_GROUP_IDS.size) {
            "--artifact-set은 정확히 6번 지정해야 합니다."
        }
        val parsed = linkedMapOf<String, Path>()
        values.forEach { raw ->
            val parts = raw.split('=', limit = 2)
            require(parts.size == 2 && parts.all(String::isNotBlank)) {
                "--artifact-set은 groupId=bundle.six 형식이어야 합니다."
            }
            val groupId = parts[0]
            require(groupId in ApiProbeCatalog.PROBE_GROUP_IDS) { "알 수 없는 probe groupId입니다: $groupId" }
            require(parsed.put(groupId, Path.of(parts[1]).toAbsolutePath().normalize()) == null) {
                "probe artifact groupId는 중복될 수 없습니다: $groupId"
            }
        }
        require(parsed.keys == ApiProbeCatalog.PROBE_GROUP_IDS) {
            "probe artifact set은 정확한 6개 group을 포함해야 합니다."
        }
        return parsed
    }

    private fun artifactPaths(bundle: Path): ProbeBuildArtifactPaths {
        val filename = bundle.fileName?.toString()
            ?: throw IllegalArgumentException("probe bundle filename이 없습니다.")
        require(filename.endsWith(".six") && filename.length > 4) {
            "probe bundle은 .six 확장자여야 합니다."
        }
        val basename = filename.removeSuffix(".six")
        return ProbeBuildArtifactPaths(
            bundle = bundle,
            crx = bundle.resolveSibling("$basename.crx"),
            wrapper = bundle.resolveSibling("$basename-wrapper.apk"),
        )
    }
}

private data class ProbeBuildArtifactPaths(
    val bundle: Path,
    val crx: Path,
    val wrapper: Path,
)

private fun publishNewSessionConfig(
    output: Path,
    config: ProbeSessionConfig,
    publication: ProbePublicationFileOps,
) {
    val target = output.toAbsolutePath().normalize()
    val parent = prepareOutputParent(target)
    require(Files.getFileStore(parent).supportsFileAttributeView("posix")) {
        "probe session config는 POSIX 0600 permission을 검증할 수 있는 filesystem에서만 지원합니다."
    }
    require(!Files.exists(target, NOFOLLOW_LINKS)) {
        "probe session output은 존재하지 않아야 합니다."
    }
    val parentIdentity = directoryIdentity(parent)
    val temporary = Files.createTempFile(parent, ".${target.fileName}.tmp-", ".json")
    var movedSnapshot: ProbePublicationSnapshot? = null
    try {
        Files.setPosixFilePermissions(
            temporary,
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ),
        )
        val encoded = ProbeSessionConfigJson.encode(config).toByteArray(Charsets.UTF_8)
        val expectedSha256 = MessageDigest.getInstance("SHA-256").digest(encoded)
            .joinToString("") { "%02x".format(it) }
        publication.writeAndForce(temporary, encoded)
        require(directoryIdentity(parent) == parentIdentity) {
            "probe session output parent가 생성 중 변경되었습니다."
        }
        movedSnapshot = publication.snapshot(temporary).also { snapshot ->
            val root = snapshot.entries["."]
            require(snapshot.stable && snapshot.entries.size == 1 && root?.regularFile == true &&
                root.size == encoded.size.toLong() && root.sha256 == expectedSha256
            ) { "probe session config 임시 파일이 publication 직전 변경되었습니다." }
        }
        publication.moveNew(temporary, target)
        publication.forceDirectory(parent)
        require(directoryIdentity(parent) == parentIdentity) {
            "probe session output parent가 publication 중 변경되었습니다."
        }
        val publishedSnapshot = publication.snapshot(target)
        require(publishedSnapshot.stable &&
            publishedSnapshot.entries == checkNotNull(movedSnapshot).entries
        ) {
            "probe session config publication 자기 검증에 실패했습니다."
        }
    } catch (error: Exception) {
        movedSnapshot?.let { rollbackMovedTarget(target, parent, it, publication, error) }
        throw error
    } finally {
        Files.deleteIfExists(temporary)
    }
}

@Command(
    name = "generate",
    mixinStandardHelpOptions = true,
    description = ["6개 API probe MV3 source를 결정론적으로 생성합니다."],
)
class ProbeGenerateCommand internal constructor(
    private val publication: ProbePublicationFileOps = NioProbePublicationFileOps(),
) : Callable<Int> {
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
        var movedSnapshot: ProbePublicationSnapshot? = null
        try {
            val generated = ProbeSourceGenerator(directoryMover = { source, destination ->
                forceGeneratedTree(source, publication)
                require(directoryIdentity(parent) == parentIdentity) {
                    "API probe output parent가 생성 중 변경되었습니다."
                }
                val sourceSnapshot = publication.snapshot(source)
                require(sourceSnapshot.stable) {
                    "API probe staging tree가 publication 직전 변경되었습니다."
                }
                movedSnapshot = sourceSnapshot
                publication.moveNew(source, destination)
                publication.forceDirectory(parent)
            }).generate(catalog, target)
            require(generated.map { it.groupId }.toSet() == ApiProbeCatalog.PROBE_GROUP_IDS) {
                "API probe source group coverage가 올바르지 않습니다."
            }
            require(Files.isDirectory(target, NOFOLLOW_LINKS) && !Files.isSymbolicLink(target)) {
                "API probe output publication을 검증할 수 없습니다."
            }
            spec.commandLine().out.println("catalog SHA-256=${catalog.sha256}")
            spec.commandLine().out.println("생성 그룹=${generated.size}")
            spec.commandLine().out.println("출력=${target}")
            0
        } catch (error: Exception) {
            movedSnapshot?.let { rollbackMovedTarget(target, parent, it, publication, error) }
            throw error
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

    @Option(names = ["--manifest-load-isolation"])
    var manifestLoadIsolation: Path? = null

    @Option(names = ["--variant-evidence"])
    var variantEvidence: Path? = null

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int = probeUserInput {
        val catalog = loadProbeCatalog()
        val isolation = readManifestLoadIsolation(manifestLoadIsolation)
        val evidence = readEvidenceSet(input, isolation, variantEvidence)
        val summary = CapabilityProfileBuilder(catalog).verify(evidence, isolation)
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
class ProbeBuildProfileCommand internal constructor(
    private val publication: ProbePublicationFileOps = NioProbePublicationFileOps(),
) : Callable<Int> {
    @Option(names = ["--base"], required = true)
    lateinit var base: Path

    @Option(names = ["--evidence"], required = true)
    lateinit var evidence: Path

    @Option(names = ["--output"], required = true)
    lateinit var output: Path

    @Option(names = ["--manifest-load-isolation"])
    var manifestLoadIsolation: Path? = null

    @Option(names = ["--variant-evidence"])
    var variantEvidence: Path? = null

    @Spec
    lateinit var spec: CommandLine.Model.CommandSpec

    override fun call(): Int = probeUserInput {
        val catalog = loadProbeCatalog()
        val baseProfile = BrowserProfileJson.decode(readStrictFile(base, MAX_PROFILE_BYTES))
        val isolation = readManifestLoadIsolation(manifestLoadIsolation)
        val decodedEvidence = readEvidenceSet(evidence, isolation, variantEvidence)
        val builder = CapabilityProfileBuilder(catalog)
        val summary = builder.verify(decodedEvidence, isolation)
        val candidate = builder.build(baseProfile, decodedEvidence, isolation)
        val encoded = BrowserProfileJson.encode(candidate)
        val decoded = BrowserProfileJson.decode(encoded)
        require(decoded == candidate && decoded.canonicalSha256() == candidate.canonicalSha256()) {
            "후보 browser profile self-verification에 실패했습니다."
        }
        publishNewProfile(output, encoded, candidate.canonicalSha256(), publication)
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

private fun readEvidenceSet(
    path: Path,
    isolation: ProbeManifestLoadIsolation?,
    variantEvidence: Path?,
): List<ProbeEvidence> {
    if (isolation == null) {
        require(variantEvidence == null) {
            "--variant-evidence는 --manifest-load-isolation과 함께 사용해야 합니다."
        }
        return readEvidenceDirectory(
            path,
            ApiProbeCatalog.PROBE_GROUP_IDS.mapTo(linkedSetOf()) { "$it.json" },
        )
    }
    val variantPath = requireNotNull(variantEvidence) {
        "--manifest-load-isolation에는 --variant-evidence가 필요합니다."
    }
    val expectedControlNames = ApiProbeCatalog.PROBE_GROUP_IDS
        .filterNot { it == isolation.groupId }
        .mapTo(linkedSetOf()) { "$it.json" }
    return readEvidenceDirectory(path, expectedControlNames) + ProbeEvidenceJson.decodeStrict(
        readStrictFile(variantPath, MAX_EVIDENCE_BYTES),
    )
}

private fun readEvidenceDirectory(
    path: Path,
    expectedNames: Set<String>,
): List<ProbeEvidence> {
    val root = path.toAbsolutePath().normalize()
    require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
        "API probe evidence input은 symlink가 아닌 directory여야 합니다."
    }
    val identity = directoryIdentity(root)
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

private fun readManifestLoadIsolation(path: Path?): ProbeManifestLoadIsolation? = path?.let {
    ProbeManifestLoadIsolationJson.decodeStrict(readStrictFile(it, MAX_EVIDENCE_BYTES))
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

private fun publishNewProfile(
    output: Path,
    encoded: String,
    expectedDigest: String,
    publication: ProbePublicationFileOps,
) {
    val target = output.toAbsolutePath().normalize()
    val parent = prepareOutputParent(target)
    require(!Files.exists(target, NOFOLLOW_LINKS)) {
        "후보 browser profile output은 존재하지 않아야 합니다."
    }
    val parentIdentity = directoryIdentity(parent)
    val temporary = Files.createTempFile(parent, ".${target.fileName}.tmp-", ".json")
    var movedSnapshot: ProbePublicationSnapshot? = null
    try {
        publication.writeAndForce(temporary, encoded.toByteArray(Charsets.UTF_8))
        val decoded = BrowserProfileJson.decode(readStrictFile(temporary, MAX_PROFILE_BYTES))
        require(decoded.canonicalSha256() == expectedDigest) {
            "후보 browser profile 임시 파일 digest가 일치하지 않습니다."
        }
        require(directoryIdentity(parent) == parentIdentity) {
            "후보 browser profile parent가 생성 중 변경되었습니다."
        }
        val sourceSnapshot = publication.snapshot(temporary)
        require(sourceSnapshot.stable) {
            "후보 browser profile 임시 파일이 publication 직전 변경되었습니다."
        }
        movedSnapshot = sourceSnapshot
        publication.moveNew(temporary, target)
        publication.forceDirectory(parent)
        require(directoryIdentity(parent) == parentIdentity) {
            "후보 browser profile parent가 publication 중 변경되었습니다."
        }
        val published = BrowserProfileJson.decode(readStrictFile(target, MAX_PROFILE_BYTES))
        require(published.canonicalSha256() == expectedDigest) {
            "후보 browser profile publication digest가 일치하지 않습니다."
        }
    } catch (error: Exception) {
        movedSnapshot?.let { rollbackMovedTarget(target, parent, it, publication, error) }
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

private fun forceGeneratedTree(root: Path, publication: ProbePublicationFileOps) {
    val entries = Files.walk(root).use { stream -> stream.toList() }
    require(entries.all {
        !Files.isSymbolicLink(it) &&
            (Files.isRegularFile(it, NOFOLLOW_LINKS) || Files.isDirectory(it, NOFOLLOW_LINKS))
    }) { "API probe staging tree에 허용되지 않은 파일 종류가 있습니다." }
    entries.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }
        .sorted()
        .forEach(publication::forceFile)
    entries.filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
        .sortedWith(compareByDescending<Path> { it.nameCount }.thenBy(Path::toString))
        .forEach(publication::forceDirectory)
}

private fun rollbackMovedTarget(
    target: Path,
    parent: Path,
    snapshot: ProbePublicationSnapshot,
    publication: ProbePublicationFileOps,
    original: Exception,
) {
    try {
        val result = publication.quarantineAfterFailure(target, snapshot)
        original.addSuppressed(ProbeRollbackDiagnosticException(result))
        if (result.publicTargetChanged) {
            try {
                publication.forceDirectory(parent)
            } catch (rollbackFsync: Exception) {
                original.addSuppressed(rollbackFsync)
            }
        }
    } catch (rollback: Exception) {
        original.addSuppressed(rollback)
    }
}

internal interface ProbePublicationFileOps {
    fun writeAndForce(path: Path, bytes: ByteArray)
    fun forceFile(path: Path)
    fun snapshot(path: Path): ProbePublicationSnapshot
    fun moveNew(source: Path, target: Path)
    fun forceDirectory(path: Path)
    fun quarantineAfterFailure(
        path: Path,
        expected: ProbePublicationSnapshot,
    ): ProbeRollbackResult
}

internal data class ProbePublicationSnapshot(
    val entries: Map<String, ProbePublicationIdentity>,
    val stable: Boolean,
)

internal data class ProbePublicationIdentity(
    val fileKey: Any?,
    val creationTime: FileTime,
    val lastModifiedTime: FileTime,
    val size: Long,
    val regularFile: Boolean,
    val directory: Boolean,
    val sha256: String?,
)

internal enum class ProbeRollbackState {
    OWN_OUTPUT_ISOLATED,
    COMPETITOR_RESTORED,
    RESTORE_COLLISION,
}

internal data class ProbeRollbackResult(
    val state: ProbeRollbackState,
    val quarantinePath: Path,
    val publicTargetChanged: Boolean,
    val failure: Exception? = null,
)

internal class ProbeRollbackDiagnosticException(result: ProbeRollbackResult) : IOException(
    "probe rollback=${result.state}; quarantine=${result.quarantinePath.toAbsolutePath().normalize()}",
    result.failure,
)

internal class NioProbePublicationFileOps(
    private val afterQuarantineInspection: (Path) -> Unit = {},
    private val mover: (Path, Path) -> Unit = { source, target -> Files.move(source, target) },
) : ProbePublicationFileOps {
    override fun writeAndForce(path: Path, bytes: ByteArray) {
        FileChannel.open(path, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }

    override fun forceFile(path: Path) {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "force 대상은 symlink가 아닌 regular file이어야 합니다."
        }
        FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
    }

    override fun snapshot(path: Path): ProbePublicationSnapshot {
        val first = readTreeSnapshot(path)
        val second = readTreeSnapshot(path)
        return ProbePublicationSnapshot(second, stable = first == second)
    }

    override fun moveNew(source: Path, target: Path) {
        require(source.parent == target.parent) {
            "publication source와 target은 같은 parent에 있어야 합니다."
        }
        // ATOMIC_MOVE는 provider에 따라 기존 target을 교체할 수 있어 사용하지 않는다.
        // 같은 directory 내 no-replace move로 target race를 FileAlreadyExistsException으로 차단한다.
        mover(source, target)
    }

    override fun forceDirectory(path: Path) {
        require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "fsync 대상은 symlink가 아닌 directory여야 합니다."
        }
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
    }

    override fun quarantineAfterFailure(
        path: Path,
        expected: ProbePublicationSnapshot,
    ): ProbeRollbackResult {
        val parent = requireNotNull(path.parent) { "rollback target에 parent가 필요합니다." }
        val quarantine = Files.createTempDirectory(parent, ".${path.fileName}.rollback-")
            .toAbsolutePath().normalize()
        val entry = quarantine.resolve("entry")
        if (!Files.exists(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return ProbeRollbackResult(
                ProbeRollbackState.OWN_OUTPUT_ISOLATED,
                quarantine,
                publicTargetChanged = false,
            )
        }
        val publicSnapshot = runCatching { snapshot(path) }.getOrElse { inspectionFailure ->
            return ProbeRollbackResult(
                ProbeRollbackState.COMPETITOR_RESTORED,
                quarantine,
                publicTargetChanged = false,
                failure = IOException("rollback public target을 안정적으로 검사하지 못했습니다.", inspectionFailure),
            )
        }
        if (!publicSnapshot.stable || publicSnapshot.entries != expected.entries) {
            return ProbeRollbackResult(
                ProbeRollbackState.COMPETITOR_RESTORED,
                quarantine,
                publicTargetChanged = false,
            )
        }
        try {
            mover(path, entry)
        } catch (moveFailure: Exception) {
            return classifyAfterMoveException(
                path = path,
                entry = entry,
                quarantine = quarantine,
                expected = expected,
                moveFailure = moveFailure,
                direction = ProbeRollbackMoveDirection.TO_QUARANTINE,
            )
        }
        val quarantinedSnapshot = runCatching { snapshot(entry) }
        val confirmedSnapshot = runCatching {
            afterQuarantineInspection(entry)
            snapshot(entry)
        }
        val quarantineIsOwnOutput = quarantinedSnapshot.getOrNull()?.let { first ->
            confirmedSnapshot.getOrNull()?.let { second ->
                first.stable && second.stable &&
                    first.entries == expected.entries && second.entries == expected.entries
            }
        } == true
        if (quarantineIsOwnOutput) {
            return ProbeRollbackResult(
                ProbeRollbackState.OWN_OUTPUT_ISOLATED,
                quarantine,
                publicTargetChanged = true,
            )
        }
        val inspectionFailure = quarantinedSnapshot.exceptionOrNull()
            ?: confirmedSnapshot.exceptionOrNull()
        val restoreExpected = confirmedSnapshot.getOrNull()?.takeIf { it.stable }
            ?: quarantinedSnapshot.getOrNull()?.takeIf { it.stable }
        return try {
            mover(entry, path)
            ProbeRollbackResult(
                ProbeRollbackState.COMPETITOR_RESTORED,
                quarantine,
                publicTargetChanged = true,
                failure = inspectionFailure?.let {
                    IOException("quarantine target을 안정적으로 검사하지 못했습니다.", it)
                },
            )
        } catch (restoreFailure: Exception) {
            classifyAfterMoveException(
                path = path,
                entry = entry,
                quarantine = quarantine,
                expected = restoreExpected,
                moveFailure = restoreFailure,
                direction = ProbeRollbackMoveDirection.TO_PUBLIC,
                priorFailure = inspectionFailure,
            )
        }
    }

    private fun classifyAfterMoveException(
        path: Path,
        entry: Path,
        quarantine: Path,
        expected: ProbePublicationSnapshot?,
        moveFailure: Exception,
        direction: ProbeRollbackMoveDirection,
        priorFailure: Throwable? = null,
    ): ProbeRollbackResult {
        val entryExists = Files.exists(entry, NOFOLLOW_LINKS)
        val publicExists = Files.exists(path, NOFOLLOW_LINKS)
        val publicIsSymbolicLink = publicExists && Files.isSymbolicLink(path)
        val publicSnapshot = if (publicExists && !publicIsSymbolicLink) {
            runCatching { snapshot(path) }
        } else {
            null
        }
        val entrySnapshot = if (entryExists && !Files.isSymbolicLink(entry)) {
            runCatching { snapshot(entry) }
        } else if (entryExists) {
            Result.failure(IOException("quarantine entry가 symlink로 변경되었습니다."))
        } else {
            null
        }
        val inspectedPublic = publicSnapshot?.getOrNull()
        val publicMatchesExpected = expected != null && inspectedPublic?.let {
            it.stable && it.entries == expected.entries
        } == true
        val publicIsStableCompetitor = expected != null && inspectedPublic?.let {
            it.stable && it.entries != expected.entries
        } == true
        val publicChanged = when (direction) {
            ProbeRollbackMoveDirection.TO_PUBLIC -> true
            ProbeRollbackMoveDirection.TO_QUARANTINE ->
                !publicExists || publicIsSymbolicLink || publicIsStableCompetitor
        }
        val moveDiagnostic = IOException(
            "rollback target move가 예외로 종료되어 파일 상태를 재검사했습니다.",
            moveFailure,
        )
        priorFailure?.let(moveDiagnostic::addSuppressed)
        publicSnapshot?.exceptionOrNull()?.let(moveDiagnostic::addSuppressed)
        entrySnapshot?.exceptionOrNull()?.let(moveDiagnostic::addSuppressed)

        if (direction == ProbeRollbackMoveDirection.TO_PUBLIC) {
            return ProbeRollbackResult(
                state = if (publicMatchesExpected && !entryExists) {
                    ProbeRollbackState.COMPETITOR_RESTORED
                } else {
                    ProbeRollbackState.RESTORE_COLLISION
                },
                quarantinePath = quarantine,
                publicTargetChanged = publicChanged,
                failure = moveDiagnostic,
            )
        }

        if (!entryExists) {
            return ProbeRollbackResult(
                state = if (publicIsStableCompetitor) {
                    ProbeRollbackState.COMPETITOR_RESTORED
                } else {
                    ProbeRollbackState.RESTORE_COLLISION
                },
                quarantinePath = quarantine,
                publicTargetChanged = publicChanged,
                failure = moveDiagnostic,
            )
        }
        if (publicExists) {
            return ProbeRollbackResult(
                ProbeRollbackState.RESTORE_COLLISION,
                quarantine,
                publicTargetChanged = publicChanged,
                failure = moveDiagnostic,
            )
        }
        val inspectedEntry = entrySnapshot?.getOrNull()
        if (inspectedEntry == null || !inspectedEntry.stable) {
            return ProbeRollbackResult(
                ProbeRollbackState.RESTORE_COLLISION,
                quarantine,
                publicTargetChanged = true,
                failure = moveDiagnostic,
            )
        }
        if (expected != null && inspectedEntry.entries == expected.entries) {
            return ProbeRollbackResult(
                ProbeRollbackState.OWN_OUTPUT_ISOLATED,
                quarantine,
                publicTargetChanged = true,
                failure = moveDiagnostic,
            )
        }
        return try {
            mover(entry, path)
            ProbeRollbackResult(
                ProbeRollbackState.COMPETITOR_RESTORED,
                quarantine,
                publicTargetChanged = true,
                failure = moveDiagnostic,
            )
        } catch (restoreFailure: Exception) {
            classifyAfterMoveException(
                path = path,
                entry = entry,
                quarantine = quarantine,
                expected = inspectedEntry,
                moveFailure = restoreFailure,
                direction = ProbeRollbackMoveDirection.TO_PUBLIC,
                priorFailure = moveDiagnostic,
            )
        }
    }

    private enum class ProbeRollbackMoveDirection {
        TO_QUARANTINE,
        TO_PUBLIC,
    }

    private fun readTreeSnapshot(root: Path): Map<String, ProbePublicationIdentity> {
        require(!Files.isSymbolicLink(root)) { "publication snapshot 대상은 symlink일 수 없습니다." }
        val paths = if (Files.isDirectory(root, NOFOLLOW_LINKS)) {
            Files.walk(root).use { stream -> stream.sorted().toList() }
        } else {
            listOf(root)
        }
        return paths.associate { path ->
            require(!Files.isSymbolicLink(path)) {
                "publication snapshot tree에 symlink가 포함될 수 없습니다."
            }
            val attributes = Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            require(attributes.isRegularFile || attributes.isDirectory) {
                "publication snapshot 대상 종류가 올바르지 않습니다."
            }
            val key = if (path == root) "." else root.relativize(path).toString()
            key to ProbePublicationIdentity(
                fileKey = attributes.fileKey(),
                creationTime = attributes.creationTime(),
                lastModifiedTime = attributes.lastModifiedTime(),
                size = attributes.size(),
                regularFile = attributes.isRegularFile,
                directory = attributes.isDirectory,
                sha256 = if (attributes.isRegularFile) sha256(path) else null,
            )
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.allocate(16 * 1024)
            while (channel.read(buffer) >= 0) {
                buffer.flip()
                digest.update(buffer)
                buffer.clear()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
    if (summary.manifestLoadBlockedCaseCount > 0) {
        output.println("실행 case=${summary.executedCaseCount}/${summary.expectedTotalCaseCount}")
        output.println("manifest 로드 차단 분리=${summary.manifestLoadBlockedCaseCount}")
    }
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
