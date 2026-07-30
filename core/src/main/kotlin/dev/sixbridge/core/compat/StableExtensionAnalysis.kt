package dev.sixbridge.core.compat

import dev.sixbridge.core.conversion.OwnedTempDirectory
import dev.sixbridge.core.conversion.OwnedTempCleanupException
import dev.sixbridge.core.conversion.OwnedTempTree
import dev.sixbridge.core.input.ExtensionZip
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.jvm.JvmSynthetic

internal class StableExtensionAnalysis private constructor(
    @get:JvmSynthetic val root: Path,
    @get:JvmSynthetic val sourceArchive: ByteArray,
    @get:JvmSynthetic val sourceSha256: String,
    private val temporaryRoot: OwnedTempDirectory,
    private val liveSource: Path?,
    private val control: AnalysisControl,
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
        val cancellation = runCatching(control::checkCancelled).exceptionOrNull()
        val cleanupFailure = cleanupStableOwner(temporaryRoot)
        if (cancellation != null) {
            cleanupFailure?.let(cancellation::addSuppressed)
            throw cancellation
        }
        cleanupFailure?.let { throw it }
    }

    companion object {
        @JvmSynthetic
        fun captureLive(
            source: Path,
            control: AnalysisControl = AnalysisControl(),
        ): StableExtensionAnalysis {
            control.checkCancelled()
            val sourceRoot = source.toAbsolutePath().normalize()
            val temporary = OwnedTempTree.create(
                "sixbridge-analysis-",
            )
            return try {
                val archive = AnalysisInputMaterializer.snapshotDirectory(
                    sourceRoot,
                    control,
                ) {
                    ExtensionZip.createBytes(
                        it,
                        includeProjectMarker = true,
                    )
                }
                val canonical = OwnedTempTree.createNamed(
                    temporary,
                    "canonical",
                )
                val stableRoot = ExtensionZip.extractInto(
                    archive,
                    canonical.rootPath,
                )
                control.checkCancelled()
                StableExtensionAnalysis(
                    root = stableRoot,
                    sourceArchive = archive,
                    sourceSha256 = sha256(archive),
                    temporaryRoot = temporary,
                    liveSource = sourceRoot,
                    control = control,
                )
            } catch (error: Throwable) {
                cleanupFailure(temporary, error)
                throw error
            }
        }

        @JvmSynthetic
        fun fromArchive(
            bytes: ByteArray,
            control: AnalysisControl = AnalysisControl(),
        ): StableExtensionAnalysis {
            control.checkCancelled()
            val temporary = OwnedTempTree.create(
                "sixbridge-analysis-",
            )
            return try {
                val archive = bytes.copyOf()
                val canonical = OwnedTempTree.createNamed(
                    temporary,
                    "canonical",
                )
                val stableRoot = ExtensionZip.extractInto(
                    archive,
                    canonical.rootPath,
                )
                control.checkCancelled()
                StableExtensionAnalysis(
                    root = stableRoot,
                    sourceArchive = archive,
                    sourceSha256 = sha256(archive),
                    temporaryRoot = temporary,
                    liveSource = null,
                    control = control,
                )
            } catch (error: Throwable) {
                cleanupFailure(temporary, error)
                throw error
            }
        }

        private fun cleanupFailure(
            owner: OwnedTempDirectory,
            failure: Throwable,
        ) {
            cleanupStableOwner(owner)?.let { cleanupError ->
                failure.addSuppressed(cleanupError)
            }
        }

        private fun cleanupStableOwner(
            owner: OwnedTempDirectory,
        ): IllegalStateException? {
            repeat(MAX_STABLE_DELETE_ATTEMPTS) {
                try {
                    OwnedTempTree.delete(owner)
                    return null
                } catch (_: Throwable) {
                    // bounded retry before safe no-delete abandon
                }
            }
            runCatching {
                OwnedTempTree.abandonNoDelete(owner)
            }
            return OwnedTempCleanupException()
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

        private const val MAX_STABLE_DELETE_ATTEMPTS = 2
    }
}
