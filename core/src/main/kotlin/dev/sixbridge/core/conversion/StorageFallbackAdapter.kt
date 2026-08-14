package dev.sixbridge.core.conversion

import dev.sixbridge.core.profile.BrowserProfileDigest
import dev.sixbridge.core.compat.RuntimeResourceIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal class StorageFallbackAdapter : MobileAdapter {
    override val id: String = MobilePatternAnalyzer.STORAGE_FALLBACK
    override val version: Int = 1

    override fun apply(
        context: AdapterExecutionContext,
        selected: SelectedAdapter,
    ): AdapterDecision {
        context.requireExactOriginalTree()
        val activeContext = context
        val decision = selected.toDecision()
        if (decision.id != id ||
            decision.version != version ||
            decision.risk != AdapterRisk.SAFE ||
            decision.disposition != AdapterDisposition.AUTOMATIC
        ) {
            return decision.copy(applied = false, changes = emptyList())
        }
        val capabilityEvidence = decision.evidence.filter {
            it.relativePath == StorageFallbackCapability.EVIDENCE_PATH
        }.singleOrNull() ?: throw IllegalStateException(
            "storage fallback capability evidence가 정확히 하나가 아닙니다.",
        )
        val capability = StorageFallbackCapability.parse(capabilityEvidence)
            ?: throw IllegalStateException(
                "storage fallback closure 또는 feature proof가 올바르지 않습니다.",
            )
        check(
            capability.profileSha256 ==
                BrowserProfileDigest.sha256(activeContext.profile),
        ) {
            "승인된 browser profile과 현재 실행 profile이 다릅니다."
        }
        return applyToStagedTree(activeContext, decision, capability)
    }

    private fun applyToStagedTree(
        context: AdapterExecutionContext,
        decision: AdapterDecision,
        capability: StorageFallbackCapability,
    ): AdapterDecision {
        val originalRoot = verifiedRoot(context.originalRoot)
        val stagedRoot = verifiedRoot(context.stagedRoot)
        val originalIndex = runtimeIndex(originalRoot)
        check(
            StorageFallbackCapability.currentClosureSha256(
                originalRoot,
                originalIndex,
            ) == capability.sourceClosureSha256,
        ) {
            "승인된 immutable original runtime closure와 다릅니다."
        }
        val tokenEvidence = decision.evidence
            .filter { it.relativePath != StorageFallbackCapability.EVIDENCE_PATH }
            .distinct()
            .sortedWith(EVIDENCE_ORDER)
        val plans = targetPlans(
            originalRoot,
            originalIndex,
            stagedRoot,
            tokenEvidence,
        )
        val expectedCapability = StorageFallbackCapability.create(
            root = originalRoot,
            index = originalIndex,
            convertedSources = plans.associate {
                it.relativePath to it.convertedSource
            },
            profile = context.profile,
        )
        check(expectedCapability == capability) {
            "승인된 storage target 또는 profile provenance가 다릅니다."
        }
        verifyOriginalRuntimeResources(
            originalRoot,
            stagedRoot,
            originalIndex,
            plans,
        )
        val stagedIndex = runtimeIndex(stagedRoot)
        val stagedScriptPaths = stagedIndex.scripts
            .mapTo(mutableSetOf()) { it.relativePath }
        check(plans.all { it.relativePath in stagedScriptPaths }) {
            "승인된 storage target이 현재 staged runtime closure에 없습니다."
        }

        val states = plans.mapTo(mutableSetOf()) { plan ->
            when (plan.currentSource) {
                plan.originalSource -> TargetState.SOURCE
                plan.convertedSource -> TargetState.CONVERTED
                else -> throw IllegalStateException(
                    "승인된 storage target bytes가 현재 staged tree와 다릅니다.",
                )
            }
        }
        check(states.size == 1) {
            "storage target 일부만 변환된 상태는 허용하지 않습니다."
        }
        return if (states.single() == TargetState.CONVERTED) {
            verifyConvertedTargets(plans, tokenEvidence)
            decision.copy(applied = true, changes = emptyList())
        } else {
            plans.forEach { plan ->
                Files.write(
                    plan.stagedPath,
                    plan.convertedSource.toByteArray(UTF_8),
                )
                check(
                    Files.newBufferedReader(plan.stagedPath, UTF_8).use { it.readText() } ==
                        plan.convertedSource,
                ) {
                    "storage target 변환 결과 검증에 실패했습니다."
                }
            }
            decision.copy(
                applied = true,
                changes = plans.flatMap { plan ->
                    plan.replacements.map { access ->
                        "${plan.relativePath}:${access.line} storage.sync → storage.local"
                    }
                }.distinct().sorted(),
            )
        }
    }

    private fun targetPlans(
        originalRoot: Path,
        originalIndex: RuntimeResourceIndex,
        stagedRoot: Path,
        expectedEvidence: List<SourceEvidence>,
    ): List<TargetPlan> {
        check(expectedEvidence.isNotEmpty()) {
            "승인된 storage token 근거가 없습니다."
        }
        val evidencePaths = expectedEvidence.mapTo(sortedSetOf()) {
            it.relativePath
        }
        val scriptsByPath = originalIndex.scripts
            .groupBy(RuntimeResourceIndex.RuntimeScript::relativePath)
        val rewriter = JavaScriptTokenRewriter()
        val plans = evidencePaths.map { relativePath ->
            val scripts = scriptsByPath[relativePath]
                ?: throw IllegalStateException(
                    "승인된 storage target을 immutable closure에서 찾을 수 없습니다.",
                )
            check(
                isStandaloneJavaScript(relativePath) &&
                    scripts.all { it.lineOffset == 0 },
            ) {
                "인라인 runtime script는 storage fallback으로 변환할 수 없습니다."
            }
            val originalPath = verifiedFile(originalRoot, relativePath)
            val originalSource = Files.newBufferedReader(originalPath, UTF_8).use { it.readText() }
            check(scripts.all { it.source == originalSource }) {
                "immutable runtime 색인과 target bytes가 다릅니다."
            }
            val inspection = rewriter.inspect(originalSource)
            check(inspection.unsafeAccesses.isEmpty()) {
                "immutable storage target에 안전하지 않은 실행 환경이 있습니다."
            }
            val rewrite = rewriter.rewrite(originalSource)
            check(rewrite.replacements.isNotEmpty()) {
                "immutable storage target에서 변환 token을 찾지 못했습니다."
            }
            TargetPlan(
                relativePath = relativePath,
                originalSource = originalSource,
                convertedSource = rewrite.source,
                currentSource = Files.newBufferedReader(
                    verifiedFile(stagedRoot, relativePath),
                    UTF_8,
                ).use { it.readText() },
                stagedPath = verifiedFile(stagedRoot, relativePath),
                replacements = rewrite.replacements,
            )
        }
        val currentEvidence = plans.flatMap { plan ->
            plan.replacements.map { access ->
                SourceEvidence(
                    relativePath = plan.relativePath,
                    line = access.line,
                    detail = StorageFallbackEvidence.detail(
                        access.root,
                        plan.originalSource,
                        plan.convertedSource,
                    ),
                )
            }
        }.distinct().sortedWith(EVIDENCE_ORDER)
        check(currentEvidence == expectedEvidence) {
            "승인된 storage token 근거와 immutable original source가 다릅니다."
        }
        return plans
    }

    private fun verifyOriginalRuntimeResources(
        originalRoot: Path,
        stagedRoot: Path,
        originalIndex: RuntimeResourceIndex,
        plans: List<TargetPlan>,
    ) {
        val plansByPath = plans.associateBy(TargetPlan::relativePath)
        originalIndex.resources.forEach { originalPath ->
            val relativePath = relativePath(originalRoot, originalPath)
            val originalBytes = Files.readAllBytes(
                verifiedFile(originalRoot, relativePath),
            )
            val stagedBytes = Files.readAllBytes(
                verifiedFile(stagedRoot, relativePath),
            )
            val target = plansByPath[relativePath]
            if (target == null) {
                check(originalBytes.contentEquals(stagedBytes)) {
                    "원본 runtime resource bytes가 staged tree에서 변경되었습니다."
                }
            } else {
                val sourceBytes = target.originalSource.toByteArray(UTF_8)
                val convertedBytes = target.convertedSource.toByteArray(UTF_8)
                check(
                    stagedBytes.contentEquals(sourceBytes) ||
                        stagedBytes.contentEquals(convertedBytes),
                ) {
                    "원본 storage target bytes가 staged tree에서 변경되었습니다."
                }
            }
        }
    }

    private fun verifyConvertedTargets(
        plans: List<TargetPlan>,
        expectedEvidence: List<SourceEvidence>,
    ) {
        val localKeys = plans.flatMap { plan ->
            JavaScriptTokenRewriter().inspect(plan.currentSource)
                .localAccesses
                .map { access ->
                    EvidenceKey(plan.relativePath, access.line, access.root)
                }
        }.toSet()
        val expectedKeys = expectedEvidence.map { evidence ->
            val parsed = StorageFallbackEvidence.parse(evidence.detail)
                ?: throw IllegalStateException(
                    "승인된 storage token 근거 형식을 확인할 수 없습니다.",
                )
            val plan = plans.singleOrNull {
                it.relativePath == evidence.relativePath
            } ?: throw IllegalStateException(
                "승인된 storage target plan을 찾을 수 없습니다.",
            )
            check(
                StorageFallbackEvidence.sha256(plan.currentSource) ==
                    parsed.convertedSha256,
            ) {
                "승인 후 storage 변환 target이 변경되었습니다."
            }
            EvidenceKey(
                relativePath = evidence.relativePath,
                line = evidence.line ?: throw IllegalStateException(
                    "승인된 storage token 근거에 line이 없습니다.",
                ),
                root = parsed.root,
            )
        }.toSet()
        check(expectedKeys.isNotEmpty() && localKeys.containsAll(expectedKeys)) {
            "승인된 storage 변환 결과를 현재 target에서 확인할 수 없습니다."
        }
    }

    private fun runtimeIndex(root: Path): RuntimeResourceIndex {
        val index = RuntimeResourceIndex.create(root, manifest(root))
        check(!index.unresolvedDependency) {
            "runtime dependency를 완전히 해석할 수 없습니다."
        }
        return index
    }

    private fun manifest(root: Path): JsonObject {
        val path = verifiedFile(root, MANIFEST)
        return runCatching {
            Json.parseToJsonElement(
                Files.newBufferedReader(path, UTF_8).use { it.readText() },
            ) as? JsonObject
        }.getOrNull() ?: throw IllegalStateException("manifest.json을 해석할 수 없습니다.")
    }

    private fun verifiedRoot(path: Path): Path {
        val root = path.toAbsolutePath().normalize()
        check(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "conversion root 경계를 확인할 수 없습니다."
        }
        return root.toRealPath()
    }

    private fun verifiedFile(root: Path, relativePath: String): Path {
        check(
            relativePath.isNotBlank() &&
                '\\' !in relativePath &&
                '%' !in relativePath &&
                ':' !in relativePath &&
                '?' !in relativePath &&
                '#' !in relativePath,
        ) {
            "runtime 경로가 안전하지 않습니다."
        }
        val relative = runCatching { Path.of(relativePath) }.getOrNull()
            ?: throw IllegalStateException("runtime 경로를 해석할 수 없습니다.")
        check(!relative.isAbsolute && relative.none { it.toString() == ".." }) {
            "runtime 경로가 root를 벗어납니다."
        }
        var current = root
        relative.forEach { component ->
            current = current.resolve(component)
            check(!Files.isSymbolicLink(current)) {
                "runtime 경로에 symlink가 있습니다."
            }
        }
        val target = root.resolve(relative).normalize()
        check(
            target.startsWith(root) &&
                Files.isRegularFile(target, NOFOLLOW_LINKS) &&
                target.toRealPath() == target,
        ) {
            "runtime 파일을 확인할 수 없습니다."
        }
        return target
    }

    private fun relativePath(root: Path, path: Path): String =
        root.relativize(path.toAbsolutePath().normalize())
            .joinToString("/") { it.toString() }

    private fun isStandaloneJavaScript(relativePath: String): Boolean =
        relativePath.substringAfterLast('.', "").lowercase() in
            setOf("js", "mjs", "cjs")

    private data class TargetPlan(
        val relativePath: String,
        val originalSource: String,
        val convertedSource: String,
        val currentSource: String,
        val stagedPath: Path,
        val replacements: List<StaticStorageAccess>,
    )

    private data class EvidenceKey(
        val relativePath: String,
        val line: Int,
        val root: String,
    )

    private enum class TargetState {
        SOURCE,
        CONVERTED,
    }

    private companion object {
        const val MANIFEST = "manifest.json"
        val EVIDENCE_ORDER: Comparator<SourceEvidence> = compareBy(
            SourceEvidence::relativePath,
            SourceEvidence::line,
            SourceEvidence::detail,
        )
    }
}
