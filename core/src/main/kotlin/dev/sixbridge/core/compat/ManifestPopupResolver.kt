package dev.sixbridge.core.compat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.intOrNull

internal object ManifestPopupResolver {
    data class Declaration(
        val field: String,
        val value: JsonElement?,
    )

    fun resolve(manifest: JsonObject): String? {
        return (declaration(manifest)?.value as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.takeIf(String::isNotBlank)
    }

    fun declaration(manifest: JsonObject): Declaration? {
        val containerName = selectedContainerName(manifest) ?: return null
        val container = manifest[containerName] as? JsonObject ?: return null
        if ("default_popup" !in container) return null
        return Declaration(
            field = "$containerName.default_popup",
            value = container["default_popup"],
        )
    }

    fun selectedContainerName(manifest: JsonObject): String? {
        val manifestVersion = (manifest["manifest_version"] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.intOrNull
        return when (manifestVersion) {
            3 -> "action"
            2 -> "browser_action"
            else -> return null
        }
    }
}
