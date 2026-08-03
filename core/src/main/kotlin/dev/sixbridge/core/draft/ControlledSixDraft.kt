package dev.sixbridge.core.draft

import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisInventoryResult
import dev.sixbridge.core.compat.CompatibilityReport
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.Collections
import java.util.LinkedHashSet

sealed interface ControlledSixDraft {
    data class Legacy(
        val verified: VerifiedSixDraft,
    ) : ControlledSixDraft

    data class Prepared(
        val verified: VerifiedPreparedSixDraft,
    ) : ControlledSixDraft

    data class BudgetExceeded(
        val exceeded: AnalysisInventoryResult.Exceeded,
    ) : ControlledSixDraft
}

class VerifiedPreparedSixDraft internal constructor(
    val draft: DraftMetadata,
    val input: DraftInputMetadata,
    val plan: DraftConversionPlan,
    compatibilityBefore: CompatibilityReport,
    compatibilityAfter: CompatibilityReport,
    val expected: DraftExpected,
    val signerFingerprint: String,
    signerPublicKey: ByteArray,
    entryNames: Set<String>,
    val preparedCrx: VerifiedPreparedCrxArtifact,
) {
    val compatibilityBefore = compatibilityBefore.deepCopy()
    val compatibilityAfter = compatibilityAfter.deepCopy()
    private val stableSignerPublicKey = signerPublicKey.copyOf()
    val signerPublicKey: ByteArray
        get() = stableSignerPublicKey.copyOf()
    val entryNames: Set<String> = Collections.unmodifiableSet(
        LinkedHashSet(entryNames.sorted()),
    )

    private fun CompatibilityReport.deepCopy(): CompatibilityReport =
        copy(
            declaredPermissions = declaredPermissions.toSet(),
            usedApis = usedApis.toSet(),
            unsupportedApis = unsupportedApis.toSet(),
            verifiedApis = verifiedApis.toSet(),
            unverifiedApis = unverifiedApis.toSet(),
            unavailableApis = unavailableApis.toSet(),
            issues = issues.map { it.copy() },
            adapterDecisions = adapterDecisions.map { decision ->
                decision.copy(
                    evidence = decision.evidence.map { it.copy() },
                    changes = decision.changes.toList(),
                    remainingLimitations = decision.remainingLimitations.toList(),
                )
            },
        )
}

class VerifiedPreparedCrxArtifact internal constructor(
    private val path: Path,
    private val offset: Long,
    val length: Long,
    val proof: VerifiedCrx3,
) {
    fun copyTo(output: Path, control: AnalysisControl) {
        require(length in 1..SixDraft.MAX_SOURCE_SIZE) {
            "prepared CRX artifact 크기가 올바르지 않습니다."
        }
        try {
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { input ->
                FileChannel.open(
                    output,
                    CREATE,
                    WRITE,
                    TRUNCATE_EXISTING,
                    NOFOLLOW_LINKS,
                ).use { target ->
                    val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
                    var position = offset
                    var remaining = length
                    while (remaining > 0L) {
                        control.checkCancelled()
                        buffer.clear()
                        buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                        val count = input.read(buffer, position)
                        require(count > 0) { "prepared CRX artifact가 잘렸습니다." }
                        buffer.flip()
                        while (buffer.hasRemaining()) {
                            control.checkCancelled()
                            target.write(buffer)
                        }
                        position += count
                        remaining -= count
                    }
                    target.force(true)
                }
            }
            control.checkCancelled()
            val copied = Crx3Verifier.verify(
                output,
                0L,
                Files.size(output),
                length,
                control::checkCancelled,
            )
            require(
                copied.extensionId == proof.extensionId &&
                    copied.publicKeySha256 == proof.publicKeySha256 &&
                    copied.crxSha256 == proof.crxSha256 &&
                    copied.zipSha256 == proof.zipSha256,
            ) {
                "prepared CRX artifact copy proof가 signed draft와 다릅니다."
            }
        } catch (error: Throwable) {
            runCatching { Files.deleteIfExists(output) }
            throw error
        }
    }

    private companion object {
        const val STREAM_BUFFER_SIZE = 64 * 1024
    }
}
