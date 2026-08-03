package dev.sixbridge.core.compat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal object ManifestPopupResolver {
    fun resolve(manifest: JsonObject): String? {
        val manifestVersion = (manifest["manifest_version"] as? JsonPrimitive)
            ?.takeUnless(JsonPrimitive::isString)
            ?.intOrNull
        val containerName = when (manifestVersion) {
            3 -> "action"
            2 -> "browser_action"
            else -> return null
        }
        return ((manifest[containerName] as? JsonObject)?.get("default_popup") as? JsonPrimitive)
            ?.takeIf(JsonPrimitive::isString)
            ?.content
            ?.takeIf(String::isNotBlank)
    }
}
