package dev.sixbridge.core.compat

import dev.sixbridge.core.conversion.OwnedTempTree
import dev.sixbridge.core.conversion.PendingTempCleanup
import dev.sixbridge.core.input.ExtensionZip
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.jvm.JvmSynthetic

internal class StableExtensionAnalysis private constructor(
    @get:JvmSynthetic val root: Path,
    @get:JvmSynthetic val sourceArchive: ByteArray,
    @get:JvmSynthetic val sourceSha256: String,
    private val temporaryRoot: Path,
    private val liveSource: Path?,
) : AutoCloseable {
    @JvmSynthetic
    fun verifyLiveSource() {
        val source = liveSource ?: return
        val currentArchive = ExtensionZip.createBytes(
            source,
            includeProjectMarker = true,
        )
        check(sourceArchive.contentEquals(currentArchive)) {
            "호환성 분석 중 canonical source archive가 변경되었습니다."
        }
    }

    override fun close() {
        OwnedTempTree.delete(temporaryRoot)
    }

    companion object {
        @JvmSynthetic
        fun captureLive(source: Path): StableExtensionAnalysis {
            val sourceRoot = source.toAbsolutePath().normalize()
            val temporary = PendingTempCleanup.createTempDirectory(
                "sixbridge-analysis-",
            )
            return try {
                val archive = ExtensionZip.createBytes(
                    sourceRoot,
                    includeProjectMarker = true,
                )
                val stableRoot = ExtensionZip.extract(
                    archive,
                    temporary.resolve("canonical"),
                )
                StableExtensionAnalysis(
                    root = stableRoot,
                    sourceArchive = archive,
                    sourceSha256 = sha256(archive),
                    temporaryRoot = temporary,
                    liveSource = sourceRoot,
                )
            } catch (error: Throwable) {
                cleanupFailure(temporary, error)
                throw error
            }
        }

        @JvmSynthetic
        fun fromArchive(bytes: ByteArray): StableExtensionAnalysis {
            val temporary = PendingTempCleanup.createTempDirectory(
                "sixbridge-analysis-",
            )
            return try {
                val archive = bytes.copyOf()
                val stableRoot = ExtensionZip.extract(
                    archive,
                    temporary.resolve("canonical"),
                )
                StableExtensionAnalysis(
                    root = stableRoot,
                    sourceArchive = archive,
                    sourceSha256 = sha256(archive),
                    temporaryRoot = temporary,
                    liveSource = null,
                )
            } catch (error: Throwable) {
                cleanupFailure(temporary, error)
                throw error
            }
        }

        private fun cleanupFailure(path: Path, failure: Throwable) {
            try {
                OwnedTempTree.delete(path)
            } catch (cleanupError: Throwable) {
                failure.addSuppressed(cleanupError)
            }
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
