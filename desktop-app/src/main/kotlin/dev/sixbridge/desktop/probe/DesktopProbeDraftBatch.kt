package dev.sixbridge.desktop.probe

import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.desktop.conversion.DesktopConversionEngine
import dev.sixbridge.desktop.conversion.DesktopDraftSaver
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlin.io.path.readBytes

internal fun interface ProbeDraftBuilder {
    fun build(
        group: String,
        source: Path,
        profile: BrowserProfile,
        projectIdentity: String,
        destination: Path,
    )
}

object DesktopProbeDraftBatch {
    private const val MAX_PROFILE_BYTES = 8L * 1024 * 1024
    private val groups = listOf(
        "advanced-runtime",
        "base-runtime",
        "identity-privacy",
        "network",
        "tab-page",
        "user-interface",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val options = parseArgs(args)
        DesktopConversionEngine().use { engine ->
            val saver = DesktopDraftSaver()
            runBatch(
                sourceRoot = options.getValue("--source-root"),
                profilePath = options.getValue("--profile"),
                outputRoot = options.getValue("--output-root"),
                draftBuilder = ProbeDraftBuilder { _, source, profile, identity, destination ->
                    val session = engine.analyze(
                        DesktopExtensionInput.Local(source),
                        profile,
                        AnalysisControl(),
                    )
                    try {
                        check(session.report.status !in setOf(
                            CompatibilityStatus.BLOCKED,
                            CompatibilityStatus.UNKNOWN,
                        )) {
                            "probe group 분석을 draft로 만들 수 없습니다: " +
                                session.report.issues.joinToString { "${it.code}: ${it.message}" }
                        }
                        val artifact = engine.buildDraft(
                            session = session,
                            approvedAdapterIds = session.requiredApprovals,
                            projectIdentity = identity,
                            control = AnalysisControl(),
                        )
                        saver.save(artifact, destination)
                    } finally {
                        engine.release(session.token)
                    }
                },
            )
        }
    }

    internal fun runBatch(
        sourceRoot: Path,
        profilePath: Path,
        outputRoot: Path,
        draftBuilder: ProbeDraftBuilder,
    ) {
        val sources = requireDirectory(sourceRoot, "probe source root")
        val profileFile = requireRegularFile(profilePath, "browser profile")
        require(Files.size(profileFile) <= MAX_PROFILE_BYTES) {
            "browser profile 크기가 제한을 초과했습니다."
        }
        val profile = BrowserProfileJson.decode(profileFile.readBytes().decodeToString()).also {
            it.validate()
            it.canonicalSha256()
        }
        val sourceGroups = Files.list(sources).use { stream ->
            stream.map { it.fileName.toString() }.sorted().toList()
        }
        require(sourceGroups == groups) { "probe source group 구성이 canonical 목록과 다릅니다." }
        val verifiedSources = groups.associateWith { group ->
            requireSafeTree(sources.resolve(group), "probe source group $group")
        }

        val output = outputRoot.toAbsolutePath().normalize()
        require(!Files.exists(output, NOFOLLOW_LINKS)) { "probe draft output은 새 디렉터리여야 합니다." }
        val outputParent = requireDirectory(
            requireNotNull(output.parent) { "probe draft output parent가 필요합니다." },
            "probe draft output parent",
        )
        val ownedOutput = Files.createDirectory(outputParent.resolve(output.fileName))
        try {
            groups.forEach { group ->
                draftBuilder.build(
                    group,
                    verifiedSources.getValue(group),
                    profile,
                    projectIdentity(group),
                    ownedOutput.resolve("$group.sixdraft"),
                )
                requireRegularFile(ownedOutput.resolve("$group.sixdraft"), "생성된 probe draft")
            }
        } catch (error: Throwable) {
            deleteOwnedTree(ownedOutput)
            throw error
        }
    }

    private fun parseArgs(args: Array<String>): Map<String, Path> {
        require(args.size == 6) { "--source-root, --profile, --output-root가 필요합니다." }
        val allowed = setOf("--source-root", "--profile", "--output-root")
        val values = linkedMapOf<String, Path>()
        args.toList().chunked(2).forEach { pair ->
            require(pair.size == 2 && pair[0] in allowed && pair[0] !in values) {
                "probe batch 인수가 올바르지 않습니다."
            }
            values[pair[0]] = Path.of(pair[1])
        }
        require(values.keys == allowed) { "probe batch 필수 인수가 누락되었습니다." }
        return values
    }

    private fun requireDirectory(path: Path, label: String): Path {
        val candidate = path.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(candidate)) { "$label 은 심볼릭 링크일 수 없습니다." }
        val attributes = Files.readAttributes(candidate, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isDirectory && !attributes.isSymbolicLink) { "$label 은 실제 디렉터리여야 합니다." }
        return candidate.toRealPath()
    }

    private fun requireRegularFile(path: Path, label: String): Path {
        val candidate = path.toAbsolutePath().normalize()
        require(!Files.isSymbolicLink(candidate)) { "$label 은 심볼릭 링크일 수 없습니다." }
        val attributes = Files.readAttributes(candidate, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink) { "$label 은 일반 파일이어야 합니다." }
        return candidate.toRealPath()
    }

    private fun requireSafeTree(path: Path, label: String): Path {
        val root = requireDirectory(path, label)
        Files.walk(root).use { stream ->
            stream.forEach { entry ->
                require(!Files.isSymbolicLink(entry)) { "$label 에 심볼릭 링크가 포함되어 있습니다." }
                val attributes = Files.readAttributes(entry, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(attributes.isDirectory || attributes.isRegularFile) {
                    "$label 에 지원하지 않는 파일 형식이 포함되어 있습니다."
                }
            }
        }
        return root
    }

    private fun projectIdentity(group: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("sixbridge-api-probe:v1:$group".encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    private fun deleteOwnedTree(root: Path) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        Files.walk(root).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
