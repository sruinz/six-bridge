package dev.sixbridge.core.conversion

import dev.sixbridge.core.profile.BrowserProfile
import kotlinx.serialization.json.JsonObject
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path

internal interface MobileAdapter {
    val id: String
    val version: Int

    fun apply(
        context: AdapterExecutionContext,
        selected: SelectedAdapter,
    ): AdapterDecision
}

internal sealed interface AdapterExecutionContext {
    val originalRoot: Path
    val stagedRoot: Path
    val profile: BrowserProfile
    fun requireExactOriginalTree()
}

sealed interface SelectedAdapter {
    val id: String
    val version: Int
    val risk: AdapterRisk
    val disposition: AdapterDisposition
    val applied: Boolean
    val evidence: List<SourceEvidence>
    val changes: List<String>
    val remainingLimitations: List<String>
}

class ConversionExecutionException internal constructor() :
    IllegalStateException("변환 작업을 안전하게 완료하지 못했습니다.")

internal fun SelectedAdapter.toDecision(): AdapterDecision = AdapterDecision(
    id = id,
    version = version,
    risk = risk,
    disposition = disposition,
    applied = applied,
    evidence = evidence,
    changes = changes,
    remainingLimitations = remainingLimitations,
)

internal fun writeManifestUtf8Strict(path: Path, manifest: JsonObject) {
    Files.newBufferedWriter(path, UTF_8).use { writer ->
        writer.write(manifest.toString())
    }
}
