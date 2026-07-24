package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.RuntimeResourceIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

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

        val root = verifiedRoot(workingRoot)
        val manifestPath = verifiedFile(root, MANIFEST)
        val manifest = runCatching {
            Json.parseToJsonElement(Files.readString(manifestPath, UTF_8)) as? JsonObject
        }.getOrNull() ?: throw IllegalStateException("manifest.json을 해석할 수 없습니다.")
        val index = RuntimeResourceIndex.create(root, manifest)
        if (index.unresolvedDependency) {
            throw IllegalStateException("runtime dependency를 완전히 해석할 수 없습니다.")
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
                RewriteFile(relativePath, target, bytes, result)
            }

        val currentEvidence = files.flatMap { file ->
            file.result.replacements.map { access ->
                SourceEvidence(
                    relativePath = file.relativePath,
                    line = access.line,
                    detail = StorageFallbackEvidence.detail(
                        access.root,
                        file.originalBytes.toString(UTF_8),
                        file.result.source,
                    ),
                )
            }
        }.distinct().sortedWith(EVIDENCE_ORDER)
        val expectedEvidence = decision.evidence.distinct().sortedWith(EVIDENCE_ORDER)
        if (currentEvidence.isEmpty()) {
            verifyReapplication(expectedEvidence, files)
            return decision.copy(applied = true, changes = emptyList())
        }
        if (currentEvidence != expectedEvidence) {
            throw IllegalStateException("승인된 storage 근거와 현재 runtime source가 다릅니다.")
        }

        val changes = files.flatMap { file ->
            file.result.replacements.map { access ->
                "${file.relativePath}:${access.line} storage.sync → storage.local"
            }
        }.distinct().sorted()
        val writes = files
            .filter { it.result.source != it.originalBytes.toString(UTF_8) }
            .map { file ->
                PendingWrite(
                    path = file.path,
                    relativePath = file.relativePath,
                    originalBytes = file.originalBytes,
                    replacementBytes = file.result.source.toByteArray(UTF_8),
                )
            }
        installAtomically(root, writes)
        return decision.copy(
            applied = true,
            changes = changes,
            remainingLimitations = (
                decision.remainingLimitations +
                    "storage.sync 데이터는 이 기기에서 storage.local에만 저장됩니다."
                ).distinct(),
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
                    "승인된 storage 근거 형식을 확인할 수 없습니다.",
                )
            val file = filesByPath[evidence.relativePath]
                ?: throw IllegalStateException(
                    "승인된 storage 파일을 현재 runtime 색인에서 찾을 수 없습니다.",
                )
            if (StorageFallbackEvidence.sha256(
                    file.originalBytes.toString(UTF_8),
                ) != parsed.convertedSha256
            ) {
                throw IllegalStateException(
                    "승인 후 storage 변환 파일이 변경되었습니다: ${evidence.relativePath}",
                )
            }
            EvidenceKey(
                relativePath = evidence.relativePath,
                line = evidence.line ?: throw IllegalStateException(
                    "승인된 storage 근거에 line이 없습니다.",
                ),
                root = parsed.root,
            )
        }.toSet()
        if (expectedKeys.isEmpty() || !localKeys.containsAll(expectedKeys)) {
            throw IllegalStateException("승인된 storage 변환 결과를 현재 파일에서 확인할 수 없습니다.")
        }
    }

    private fun installAtomically(root: Path, writes: List<PendingWrite>) {
        if (writes.isEmpty()) return
        writes.forEach { write ->
            val current = verifiedFile(
                root,
                root.relativize(write.path).joinToString("/") { it.toString() },
            )
            if (current != write.path ||
                !Files.readAllBytes(current).contentEquals(write.originalBytes)
            ) {
                throw IllegalStateException(
                    "storage 변환 직전에 파일이 변경되었습니다: ${write.relativePath}",
                )
            }
        }

        val staged = mutableListOf<StagedWrite>()
        try {
            writes.forEach { write ->
                val temporary = Files.createTempFile(
                    write.path.parent,
                    ".sixbridge-storage-",
                    ".tmp",
                )
                Files.write(temporary, write.replacementBytes)
                staged += StagedWrite(write, temporary)
            }
            writes.forEach { write ->
                if (!Files.readAllBytes(
                        verifiedFile(root, write.relativePath),
                    ).contentEquals(write.originalBytes)
                ) {
                    throw IllegalStateException(
                        "storage 변환 중 파일이 변경되었습니다: ${write.relativePath}",
                    )
                }
            }
            val installed = mutableListOf<PendingWrite>()
            try {
                staged.forEach { stagedWrite ->
                    atomicReplace(stagedWrite.temporary, stagedWrite.write.path)
                    installed += stagedWrite.write
                }
            } catch (error: Throwable) {
                installed.asReversed().forEach { write ->
                    runCatching {
                        val rollback = Files.createTempFile(
                            write.path.parent,
                            ".sixbridge-storage-rollback-",
                            ".tmp",
                        )
                        Files.write(rollback, write.originalBytes)
                        atomicReplace(rollback, write.path)
                    }.exceptionOrNull()?.let(error::addSuppressed)
                }
                throw error
            }
        } finally {
            staged.forEach { Files.deleteIfExists(it.temporary) }
        }
    }

    private fun atomicReplace(source: Path, destination: Path) {
        try {
            Files.move(source, destination, ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (error: AtomicMoveNotSupportedException) {
            throw IllegalStateException(
                "원자적 파일 교체를 지원하지 않아 storage 변환을 중단합니다.",
                error,
            )
        }
    }

    private fun verifiedRoot(workingRoot: Path): Path {
        val root = workingRoot.toAbsolutePath().normalize()
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(root)
        ) {
            throw IllegalStateException("working root 경계를 확인할 수 없습니다.")
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
        val originalBytes: ByteArray,
        val result: RewriteResult,
    )

    private data class PendingWrite(
        val path: Path,
        val relativePath: String,
        val originalBytes: ByteArray,
        val replacementBytes: ByteArray,
    )

    private data class StagedWrite(
        val write: PendingWrite,
        val temporary: Path,
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
