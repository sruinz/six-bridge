package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.RuntimeResourceIndex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class DesktopSelectionEventBridge : MobileAdapter {
    override val id: String = MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE
    override val version: Int = 1

    override fun apply(workingRoot: Path, decision: AdapterDecision): AdapterDecision {
        if (decision.id != id || decision.version != version ||
            decision.disposition != AdapterDisposition.AUTOMATIC
        ) {
            return decision
        }
        val manifestPath = workingRoot.resolve(MANIFEST)
        val manifest = runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(manifestPath.readText()) as? JsonObject
        }.getOrNull() ?: return decision
        val contentScripts = manifest[CONTENT_SCRIPTS] as? JsonArray ?: return decision
        val index = RuntimeResourceIndex.create(workingRoot, manifest)
        val targetEntryIndexes = decision.evidence
            .flatMapTo(sortedSetOf()) { evidence ->
                index.contentScriptEntryIndexesByRelativePath[evidence.relativePath].orEmpty()
            }
        if (targetEntryIndexes.isEmpty()) return decision
        val assetBytes = adapterBytes()
        ensureNoAssetConflict(workingRoot, assetBytes)
        var changed = false
        val updatedContentScripts = JsonArray(contentScripts.mapIndexed { entryIndex, entry ->
            val block = entry as? JsonObject ?: return@mapIndexed entry
            val scripts = block[SCRIPTS] as? JsonArray ?: return@mapIndexed entry
            if (entryIndex !in targetEntryIndexes) return@mapIndexed entry
            val canonicalScripts = JsonArray(
                listOf(JsonPrimitive(ASSET_RELATIVE_PATH)) + scripts.filterNot {
                    (it as? JsonPrimitive)?.content == ASSET_RELATIVE_PATH
                },
            )
            if (scripts == canonicalScripts) return@mapIndexed entry
            changed = true
            JsonObject(block + (SCRIPTS to canonicalScripts))
        })
        if (changed) {
            val updatedManifest = JsonObject(manifest + (CONTENT_SCRIPTS to updatedContentScripts))
            Files.writeString(manifestPath, updatedManifest.toString())
        }
        installAsset(workingRoot, assetBytes)
        return decision.copy(applied = true)
    }

    private fun adapterBytes(): ByteArray =
        javaClass.classLoader.getResourceAsStream(ASSET_RESOURCE_PATH)
            ?.use { it.readBytes() }
            ?: error("adapter resource를 찾지 못했습니다: $ASSET_RESOURCE_PATH")

    private fun ensureNoAssetConflict(workingRoot: Path, source: ByteArray) {
        val destination = workingRoot.resolve(ASSET_RELATIVE_PATH)
        if (!Files.exists(destination)) return
        if (!Files.isRegularFile(destination) ||
            !Files.readAllBytes(destination).contentEquals(source)
        ) {
            throw IllegalStateException("기존 adapter asset과 bytes가 다릅니다: $ASSET_RELATIVE_PATH")
        }
    }

    private fun installAsset(workingRoot: Path, source: ByteArray) {
        val destination = workingRoot.resolve(ASSET_RELATIVE_PATH)
        if (Files.exists(destination)) return
        Files.createDirectories(destination.parent)
        Files.write(destination, source)
    }

    private companion object {
        const val MANIFEST = "manifest.json"
        const val CONTENT_SCRIPTS = "content_scripts"
        const val SCRIPTS = "js"
        const val ASSET_FILE_NAME = "desktop-selection-event-bridge.v1.js"
        const val ASSET_RELATIVE_PATH = "_sixbridge/$ASSET_FILE_NAME"
        const val ASSET_RESOURCE_PATH = "dev/sixbridge/adapters/$ASSET_FILE_NAME"
    }
}
