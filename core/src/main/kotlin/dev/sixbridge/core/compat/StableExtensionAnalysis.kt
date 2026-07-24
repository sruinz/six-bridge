package dev.sixbridge.core.compat

import dev.sixbridge.core.conversion.NativeWorkspaceTree
import dev.sixbridge.core.conversion.WorkspaceTreeDigest
import dev.sixbridge.core.input.ExtensionZip
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.attribute.BasicFileAttributes
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Comparator
import kotlin.jvm.JvmSynthetic

internal class StableExtensionAnalysis private constructor(
    @get:JvmSynthetic val root: Path,
    @get:JvmSynthetic val sourceArchive: ByteArray?,
    @get:JvmSynthetic val sourceSha256: String?,
    private val temporaryRoot: Path,
    private val liveSource: Path?,
    private val liveState: LiveSourceState?,
) : AutoCloseable {
    @JvmSynthetic
    fun verifyLiveSource() {
        val source = liveSource ?: return
        check(requireNotNull(liveState).matches(source)) {
            "호환성 분석 중 source native tree가 변경되었습니다."
        }
        val currentArchive = canonicalArchive(source)
        check(
            if (sourceArchive == null) {
                currentArchive == null
            } else {
                currentArchive != null &&
                    sourceArchive.contentEquals(currentArchive)
            },
        ) {
            "호환성 분석 중 source canonical tree가 변경되었습니다."
        }
    }

    override fun close() {
        deleteTree(temporaryRoot)
    }

    companion object {
        @JvmSynthetic
        fun captureLive(source: Path): StableExtensionAnalysis {
            val sourceRoot = source.toAbsolutePath().normalize()
            val temporary = Files.createTempDirectory(
                "sixbridge-analysis-",
            )
            try {
                val liveState = LiveSourceState.capture(sourceRoot)
                val immutable = when (liveState) {
                    is NativeLiveSourceState -> {
                        NativeWorkspaceTree.copy(
                            sourceRoot,
                            temporary.resolve("immutable"),
                        ).also { copied ->
                            check(
                                liveState.state.provenanceSha256 ==
                                    WorkspaceTreeDigest.capture(
                                        copied,
                                    ).provenanceSha256,
                            ) {
                                "호환성 분석 snapshot이 최초 source와 다릅니다."
                            }
                        }
                    }
                    is UnsafeLiveSourceState -> {
                        copyUnsafeTree(
                            sourceRoot,
                            temporary.resolve("immutable"),
                        )
                    }
                }
                check(liveState.matches(sourceRoot)) {
                    "호환성 분석 snapshot 중 source가 변경되었습니다."
                }
                val archive = canonicalArchive(immutable)
                val stableRoot = if (archive == null) {
                    immutable
                } else {
                    ExtensionZip.extract(
                        archive,
                        temporary.resolve("canonical"),
                    )
                }
                return StableExtensionAnalysis(
                    root = stableRoot,
                    sourceArchive = archive,
                    sourceSha256 = archive?.let(::sha256),
                    temporaryRoot = temporary,
                    liveSource = sourceRoot,
                    liveState = liveState,
                )
            } catch (error: Throwable) {
                deleteTree(temporary)
                throw error
            }
        }

        @JvmSynthetic
        fun fromArchive(bytes: ByteArray): StableExtensionAnalysis {
            val temporary = Files.createTempDirectory(
                "sixbridge-analysis-",
            )
            return try {
                val stableRoot = ExtensionZip.extract(
                    bytes,
                    temporary.resolve("canonical"),
                )
                StableExtensionAnalysis(
                    root = stableRoot,
                    sourceArchive = bytes.copyOf(),
                    sourceSha256 = sha256(bytes),
                    temporaryRoot = temporary,
                    liveSource = null,
                    liveState = null,
                )
            } catch (error: Throwable) {
                deleteTree(temporary)
                throw error
            }
        }

        private fun canonicalArchive(root: Path): ByteArray? =
            runCatching {
                ExtensionZip.createBytes(
                    root,
                    includeProjectMarker = true,
                )
            }.getOrNull()

        private fun copyUnsafeTree(source: Path, destination: Path): Path {
            val entries = Files.walk(source).use { paths ->
                paths.sorted().toList()
            }
            entries.forEach { input ->
                val relative = source.relativize(input)
                val output = if (relative.toString().isEmpty()) {
                    destination
                } else {
                    destination.resolve(relative.toString())
                }
                val attributes = Files.readAttributes(
                    input,
                    BasicFileAttributes::class.java,
                    NOFOLLOW_LINKS,
                )
                when {
                    attributes.isDirectory -> Files.createDirectory(output)
                    attributes.isRegularFile -> Files.copy(
                        input,
                        output,
                        COPY_ATTRIBUTES,
                        NOFOLLOW_LINKS,
                    )
                    else -> Files.createSymbolicLink(
                        output,
                        Path.of(".sixbridge-invalid-link-target"),
                    )
                }
            }
            return destination
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        private fun deleteTree(path: Path) {
            if (!Files.exists(path, NOFOLLOW_LINKS)) return
            Files.walk(path).use { paths ->
                paths.sorted(Comparator.reverseOrder())
                    .forEach(Files::deleteIfExists)
            }
        }
    }

    private sealed interface LiveSourceState {
        fun matches(source: Path): Boolean

        companion object {
            fun capture(source: Path): LiveSourceState {
                val native = runCatching {
                    WorkspaceTreeDigest.capture(source)
                }
                if (native.isSuccess) {
                    return NativeLiveSourceState(native.getOrThrow())
                }
                val unsafe = UnsafeTreeDigest.capture(source)
                check(unsafe.hasUnsupportedEntries) {
                    throw requireNotNull(native.exceptionOrNull())
                }
                return UnsafeLiveSourceState(unsafe.digest)
            }
        }
    }

    private data class NativeLiveSourceState(
        val state: WorkspaceTreeDigest.State,
    ) : LiveSourceState {
        override fun matches(source: Path): Boolean =
            runCatching {
                WorkspaceTreeDigest.capture(source) == state
            }.getOrDefault(false)
    }

    private data class UnsafeLiveSourceState(
        val digest: String,
    ) : LiveSourceState {
        override fun matches(source: Path): Boolean =
            runCatching {
                UnsafeTreeDigest.capture(source).digest == digest
            }.getOrDefault(false)
    }

    private object UnsafeTreeDigest {
        fun capture(root: Path): Result {
            val first = entries(root)
            val second = entries(root)
            check(first == second) {
                "unsafe source tree가 분석 snapshot 중 변경되었습니다."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            first.forEach { entry ->
                digest.field(entry.relativePath)
                digest.field(entry.type)
                digest.field(entry.size.toString())
                digest.field(entry.lastModified)
                digest.field(entry.fileKey)
                digest.field(entry.payload)
            }
            return Result(
                digest = digest.digest()
                    .joinToString("") { "%02x".format(it) },
                hasUnsupportedEntries = first.any {
                    it.type != "directory" && it.type != "file"
                },
            )
        }

        private fun entries(root: Path): List<Entry> =
            Files.walk(root).use { paths ->
                paths.sorted().map { path ->
                    val attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        NOFOLLOW_LINKS,
                    )
                    val type = when {
                        attributes.isDirectory -> "directory"
                        attributes.isRegularFile -> "file"
                        attributes.isSymbolicLink -> "symlink"
                        else -> "other"
                    }
                    val payload = when (type) {
                        "file" -> sha256(Files.readAllBytes(path))
                        "symlink" -> Files.readSymbolicLink(path).toString()
                        else -> ""
                    }
                    Entry(
                        relativePath = if (path == root) {
                            "."
                        } else {
                            root.relativize(path)
                                .joinToString("/") { it.toString() }
                        },
                        type = type,
                        size = attributes.size(),
                        lastModified = attributes.lastModifiedTime()
                            .toInstant()
                            .let { "${it.epochSecond}:${it.nano}" },
                        fileKey = requireNotNull(attributes.fileKey()) {
                            "unsafe source entry identity를 확인할 수 없습니다."
                        }.toString(),
                        payload = payload,
                    )
                }.toList()
            }

        private fun MessageDigest.field(value: String) {
            val bytes = value.toByteArray(UTF_8)
            update(
                ByteBuffer.allocate(Int.SIZE_BYTES)
                    .putInt(bytes.size)
                    .array(),
            )
            update(bytes)
        }

        data class Result(
            val digest: String,
            val hasUnsupportedEntries: Boolean,
        )

        private data class Entry(
            val relativePath: String,
            val type: String,
            val size: Long,
            val lastModified: String,
            val fileKey: String,
            val payload: String,
        )
    }
}
