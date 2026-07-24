package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.RuntimeResourceIndex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readText

internal class DesktopSelectionEventBridge : MobileAdapter {
    override val id: String = MobilePatternAnalyzer.DESKTOP_SELECTION_EVENT_BRIDGE
    override val version: Int = 1

    override fun apply(
        context: AdapterExecutionContext,
        selected: SelectedAdapter,
    ): AdapterDecision {
        val workingRoot = context.stagedRoot
        val decision = selected.toDecision()
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
        val assetDestination = preflightAssetDestination(workingRoot, assetBytes)
        var changed = false
        val updatedContentScripts = JsonArray(contentScripts.mapIndexed { entryIndex, entry ->
            val block = entry as? JsonObject ?: return@mapIndexed entry
            val scripts = block[SCRIPTS] as? JsonArray ?: return@mapIndexed entry
            if (entryIndex !in targetEntryIndexes) return@mapIndexed entry
            val canonicalScripts = JsonArray(
                listOf(JsonPrimitive(ASSET_RELATIVE_PATH)) + scripts.filterNot {
                    isAssetAlias(it)
                },
            )
            if (scripts == canonicalScripts) return@mapIndexed entry
            changed = true
            JsonObject(block + (SCRIPTS to canonicalScripts))
        })
        installAsset(assetDestination, assetBytes)
        if (changed) {
            val updatedManifest = JsonObject(manifest + (CONTENT_SCRIPTS to updatedContentScripts))
            Files.writeString(manifestPath, updatedManifest.toString())
        }
        return decision.copy(applied = true)
    }

    private fun adapterBytes(): ByteArray =
        javaClass.classLoader.getResourceAsStream(ASSET_RESOURCE_PATH)
            ?.use { it.readBytes() }
            ?: error("adapter resource를 찾지 못했습니다: $ASSET_RESOURCE_PATH")

    private fun preflightAssetDestination(workingRoot: Path, source: ByteArray): Path {
        val destination = workingRoot.resolve(ASSET_RELATIVE_PATH)
        var parent = workingRoot
        ASSET_RELATIVE_PATH.split('/').dropLast(1).forEach { component ->
            parent = parent.resolve(component)
            if (Files.exists(parent, NOFOLLOW_LINKS) && !Files.isDirectory(parent, NOFOLLOW_LINKS)) {
                throw IllegalStateException("adapter asset parent가 디렉터리가 아닙니다: $parent")
            }
        }
        if (!Files.exists(destination, NOFOLLOW_LINKS)) return destination
        if (!Files.isRegularFile(destination, NOFOLLOW_LINKS) ||
            !Files.readAllBytes(destination).contentEquals(source)
        ) {
            throw IllegalStateException("기존 adapter asset과 bytes가 다릅니다: $ASSET_RELATIVE_PATH")
        }
        return destination
    }

    private fun installAsset(destination: Path, source: ByteArray) {
        if (Files.exists(destination, NOFOLLOW_LINKS)) return
        if (!Files.exists(destination.parent, NOFOLLOW_LINKS)) {
            Files.createDirectory(destination.parent)
        }
        Files.write(destination, source)
    }

    private fun isAssetAlias(element: kotlinx.serialization.json.JsonElement): Boolean =
        (element as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.let(::canonicalResourcePath) == ASSET_RELATIVE_PATH

    private fun canonicalResourcePath(value: String): String? = runCatching {
        Path.of(value.removePrefix("/")).normalize().invariantSeparatorsPathString
    }.getOrNull()

    private companion object {
        const val MANIFEST = "manifest.json"
        const val CONTENT_SCRIPTS = "content_scripts"
        const val SCRIPTS = "js"
        const val ASSET_FILE_NAME = "desktop-selection-event-bridge.v1.js"
        const val ASSET_RELATIVE_PATH = "_sixbridge/$ASSET_FILE_NAME"
        const val ASSET_RESOURCE_PATH = "dev/sixbridge/adapters/$ASSET_FILE_NAME"
    }
}
