package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.RuntimeResourceIndex
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

internal data class StorageFallbackCapability(
    val sourceClosureSha256: String,
    val convertedClosureSha256: String,
) {
    fun evidence(): SourceEvidence = SourceEvidence(
        relativePath = EVIDENCE_PATH,
        line = null,
        detail = "schema=1; source-closure-sha256=$sourceClosureSha256; " +
            "converted-closure-sha256=$convertedClosureSha256; " +
            "storage.local=supported; storage.sync=unavailable",
    )

    companion object {
        const val EVIDENCE_PATH = "@sixbridge/storage-fallback-capability"

        fun create(
            root: Path,
            index: RuntimeResourceIndex,
            convertedSources: Map<String, String>,
        ): StorageFallbackCapability = StorageFallbackCapability(
            sourceClosureSha256 = closureSha256(root, index, emptyMap()),
            convertedClosureSha256 = closureSha256(
                root,
                index,
                convertedSources.mapValues { (_, source) -> source.toByteArray(UTF_8) },
            ),
        )

        fun currentClosureSha256(
            root: Path,
            index: RuntimeResourceIndex,
        ): String = closureSha256(root, index, emptyMap())

        fun parse(evidence: SourceEvidence): StorageFallbackCapability? {
            if (evidence.relativePath != EVIDENCE_PATH || evidence.line != null) return null
            val match = DETAIL.matchEntire(evidence.detail) ?: return null
            return StorageFallbackCapability(
                sourceClosureSha256 = match.groupValues[1],
                convertedClosureSha256 = match.groupValues[2],
            )
        }

        private fun closureSha256(
            root: Path,
            index: RuntimeResourceIndex,
            overrides: Map<String, ByteArray>,
        ): String {
            val normalizedRoot = root.toAbsolutePath().normalize()
            val digest = MessageDigest.getInstance("SHA-256")
            digest.field("manifest")
            digest.field("manifest.json")
            digest.bytes(Files.readAllBytes(normalizedRoot.resolve("manifest.json")))

            index.resources
                .map { path ->
                    normalizedRoot.relativize(path.toAbsolutePath().normalize())
                        .joinToString("/") { it.toString() }
                }
                .sorted()
                .forEach { relativePath ->
                    digest.field("resource")
                    digest.field(relativePath)
                    digest.bytes(
                        overrides[relativePath]
                            ?: Files.readAllBytes(normalizedRoot.resolve(relativePath)),
                    )
                    index.rolesByRelativePath[relativePath]
                        .orEmpty()
                        .map(Enum<*>::name)
                        .sorted()
                        .forEach {
                            digest.field("role")
                            digest.field(it)
                        }
                }
            index.dependencyEdges
                .sortedWith(
                    compareBy(
                        RuntimeResourceIndex.RuntimeDependencyEdge::fromRelativePath,
                        RuntimeResourceIndex.RuntimeDependencyEdge::toRelativePath,
                    ),
                )
                .forEach { edge ->
                    digest.field("edge")
                    digest.field(edge.fromRelativePath)
                    digest.field(edge.toRelativePath)
                }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        private fun MessageDigest.field(value: String) {
            bytes(value.toByteArray(UTF_8))
        }

        private fun MessageDigest.bytes(value: ByteArray) {
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
            update(value)
        }

        private val DETAIL = Regex(
            """schema=1; source-closure-sha256=([0-9a-f]{64}); """ +
                """converted-closure-sha256=([0-9a-f]{64}); """ +
                """storage\.local=supported; storage\.sync=unavailable""",
        )
    }
}
