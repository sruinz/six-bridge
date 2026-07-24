package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.RuntimeResourceIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

class StorageFallbackAdapter : MobileAdapter {
    override val id: String = MobilePatternAnalyzer.STORAGE_FALLBACK
    override val version: Int = 1

    override fun apply(
        workingRoot: Path,
        selected: SelectedAdapter,
    ): AdapterDecision {
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
        return ConversionWorkspaceTransaction().run(workingRoot) { stagedRoot ->
            applyToStagedTree(stagedRoot, decision, capability)
        }
    }

    private fun applyToStagedTree(
        stagedRoot: Path,
        decision: AdapterDecision,
        capability: StorageFallbackCapability,
    ): AdapterDecision {
        val root = verifiedRoot(stagedRoot)
        val manifest = manifest(root)
        val index = RuntimeResourceIndex.create(root, manifest)
        if (index.unresolvedDependency) {
            throw IllegalStateException("runtime dependency를 완전히 해석할 수 없습니다.")
        }
        val currentClosure = StorageFallbackCapability.currentClosureSha256(
            root,
            index,
        )
        val sourceState = when (currentClosure) {
            capability.sourceClosureSha256 -> true
            capability.convertedClosureSha256 -> false
            else -> throw IllegalStateException(
                "승인된 manifest 또는 runtime closure가 현재 staged tree와 다릅니다.",
            )
        }

        val rewriter = JavaScriptTokenRewriter()
        val files = index.scripts
            .groupBy(RuntimeResourceIndex.RuntimeScript::relativePath)
            .toSortedMap()
            .map { (relativePath, scripts) ->
                if (!isStandaloneJavaScript(relativePath) ||
                    scripts.any { it.lineOffset != 0 }
                ) {
                    throw IllegalStateException(
                        "인라인 runtime script는 storage fallback으로 변환할 수 없습니다: " +
                            relativePath,
                    )
                }
                val target = verifiedFile(root, relativePath)
                val bytes = Files.readAllBytes(target)
                val source = bytes.toString(UTF_8)
                if (scripts.any { it.source != source }) {
                    throw IllegalStateException(
                        "runtime 색인 후 파일이 변경되었습니다: $relativePath",
                    )
                }
                val result = rewriter.rewrite(source)
                if (result.unsafeAccesses.isNotEmpty()) {
                    throw IllegalStateException(
                        "승인 후 안전하지 않은 storage 접근이 발견되었습니다: $relativePath",
                    )
                }
                RewriteFile(relativePath, target, source, result)
            }

        val tokenEvidence = decision.evidence
            .filter { it.relativePath != StorageFallbackCapability.EVIDENCE_PATH }
            .distinct()
            .sortedWith(EVIDENCE_ORDER)
        if (!sourceState) {
            verifyReapplication(tokenEvidence, files)
            return decision.copy(applied = true, changes = emptyList())
        }

        val currentEvidence = files.flatMap { file ->
            file.result.replacements.map { access ->
                SourceEvidence(
                    relativePath = file.relativePath,
                    line = access.line,
                    detail = StorageFallbackEvidence.detail(
                        access.root,
                        file.source,
                        file.result.source,
                    ),
                )
            }
        }.distinct().sortedWith(EVIDENCE_ORDER)
        if (currentEvidence != tokenEvidence || currentEvidence.isEmpty()) {
            throw IllegalStateException("승인된 storage token 근거와 현재 source가 다릅니다.")
        }

        val convertedSources = files
            .filter { it.result.replacements.isNotEmpty() }
            .associate { it.relativePath to it.result.source }
        val expected = StorageFallbackCapability.create(
            root,
            index,
            convertedSources,
        )
        if (expected != capability) {
            throw IllegalStateException("승인된 storage closure 변환 결과와 현재 계획이 다릅니다.")
        }
        files.filter { it.result.source != it.source }.forEach { file ->
            Files.writeString(file.path, file.result.source, UTF_8)
        }
        val convertedManifest = manifest(root)
        val convertedIndex = RuntimeResourceIndex.create(root, convertedManifest)
        if (convertedIndex.unresolvedDependency ||
            StorageFallbackCapability.currentClosureSha256(
                root,
                convertedIndex,
            ) != capability.convertedClosureSha256
        ) {
            throw IllegalStateException("완성된 storage fallback closure 검증에 실패했습니다.")
        }
        return decision.copy(
            applied = true,
            changes = files.flatMap { file ->
                file.result.replacements.map { access ->
                    "${file.relativePath}:${access.line} storage.sync → storage.local"
                }
            }.distinct().sorted(),
        )
    }

    private fun verifyReapplication(
        expectedEvidence: List<SourceEvidence>,
        files: List<RewriteFile>,
    ) {
        val filesByPath = files.associateBy(RewriteFile::relativePath)
        val localKeys = files.flatMap { file ->
            file.result.localAccesses.map { access ->
                EvidenceKey(file.relativePath, access.line, access.root)
            }
        }.toSet()
        val expectedKeys = expectedEvidence.map { evidence ->
            val parsed = StorageFallbackEvidence.parse(evidence.detail)
                ?: throw IllegalStateException(
                    "승인된 storage token 근거 형식을 확인할 수 없습니다.",
                )
            val file = filesByPath[evidence.relativePath]
                ?: throw IllegalStateException(
                    "승인된 storage 파일을 runtime closure에서 찾을 수 없습니다.",
                )
            if (StorageFallbackEvidence.sha256(file.source) != parsed.convertedSha256) {
                throw IllegalStateException(
                    "승인 후 storage 변환 파일이 변경되었습니다: ${evidence.relativePath}",
                )
            }
            EvidenceKey(
                relativePath = evidence.relativePath,
                line = evidence.line ?: throw IllegalStateException(
                    "승인된 storage token 근거에 line이 없습니다.",
                ),
                root = parsed.root,
            )
        }.toSet()
        if (expectedKeys.isEmpty() || !localKeys.containsAll(expectedKeys)) {
            throw IllegalStateException("승인된 storage 변환 결과를 현재 closure에서 확인할 수 없습니다.")
        }
    }

    private fun manifest(root: Path): JsonObject {
        val path = verifiedFile(root, MANIFEST)
        return runCatching {
            Json.parseToJsonElement(Files.readString(path, UTF_8)) as? JsonObject
        }.getOrNull() ?: throw IllegalStateException("manifest.json을 해석할 수 없습니다.")
    }

    private fun verifiedRoot(stagedRoot: Path): Path {
        val root = stagedRoot.toAbsolutePath().normalize()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(root)
        ) {
            throw IllegalStateException("staged working root 경계를 확인할 수 없습니다.")
        }
        return root.toRealPath()
    }

    private fun verifiedFile(root: Path, relativePath: String): Path {
        if (relativePath.isBlank() ||
            '\\' in relativePath ||
            '%' in relativePath ||
            ':' in relativePath ||
            '?' in relativePath ||
            '#' in relativePath
        ) {
            throw IllegalStateException("runtime 경로가 안전하지 않습니다: $relativePath")
        }
        val relative = runCatching { Path.of(relativePath) }.getOrNull()
            ?: throw IllegalStateException("runtime 경로를 해석할 수 없습니다: $relativePath")
        if (relative.isAbsolute || relative.any { it.toString() == ".." }) {
            throw IllegalStateException("runtime 경로가 root를 벗어납니다: $relativePath")
        }
        var current = root
        relative.forEach { component ->
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) {
                throw IllegalStateException("runtime 경로에 symlink가 있습니다: $relativePath")
            }
        }
        val target = root.resolve(relative).normalize()
        if (!target.startsWith(root) ||
            !Files.isRegularFile(target, NOFOLLOW_LINKS) ||
            target.toRealPath() != target
        ) {
            throw IllegalStateException("runtime 파일을 확인할 수 없습니다: $relativePath")
        }
        return target
    }

    private fun isStandaloneJavaScript(relativePath: String): Boolean =
        relativePath.substringAfterLast('.', "").lowercase() in
            setOf("js", "mjs", "cjs")

    private data class RewriteFile(
        val relativePath: String,
        val path: Path,
        val source: String,
        val result: RewriteResult,
    )

    private data class EvidenceKey(
        val relativePath: String,
        val line: Int,
        val root: String,
    )

    private companion object {
        const val MANIFEST = "manifest.json"
        val EVIDENCE_ORDER: Comparator<SourceEvidence> = compareBy(
            SourceEvidence::relativePath,
            SourceEvidence::line,
            SourceEvidence::detail,
        )
    }
}
