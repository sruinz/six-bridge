package dev.sixbridge.core.conversion

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
        val evidencePaths = decision.evidence.mapTo(sortedSetOf()) { it.relativePath }
        val contentScripts = manifest[CONTENT_SCRIPTS] as? JsonArray ?: return decision
        var changed = false
        val updatedContentScripts = JsonArray(contentScripts.map { entry ->
            val block = entry as? JsonObject ?: return@map entry
            val scripts = block[SCRIPTS] as? JsonArray ?: return@map entry
            val scriptPaths = scripts.mapNotNull {
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            }
            if (scriptPaths.none(evidencePaths::contains)) return@map entry
            if (ASSET_RELATIVE_PATH in scriptPaths) return@map entry
            changed = true
            JsonObject(block + (SCRIPTS to JsonArray(listOf(JsonPrimitive(ASSET_RELATIVE_PATH)) + scripts)))
        })
        if (!changed) {
            val alreadyApplied = contentScripts.any { block ->
                val scripts = (block as? JsonObject)?.get(SCRIPTS) as? JsonArray
                val scriptPaths = scripts?.mapNotNull {
                    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
                }.orEmpty()
                ASSET_RELATIVE_PATH in scriptPaths && scriptPaths.any(evidencePaths::contains)
            }
            if (alreadyApplied) installAsset(workingRoot)
            return decision.copy(applied = alreadyApplied)
        }
        val updatedManifest = JsonObject(manifest + (CONTENT_SCRIPTS to updatedContentScripts))
        Files.writeString(manifestPath, updatedManifest.toString())
        installAsset(workingRoot)
        return decision.copy(applied = true)
    }

    private fun installAsset(workingRoot: Path) {
        val destination = workingRoot.resolve(ASSET_RELATIVE_PATH)
        val source = javaClass.classLoader.getResourceAsStream(ASSET_RESOURCE_PATH)
            ?.use { it.readBytes() }
            ?: error("adapter resource를 찾지 못했습니다: $ASSET_RESOURCE_PATH")
        if (Files.isRegularFile(destination) && Files.readAllBytes(destination).contentEquals(source)) {
            return
        }
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
