package dev.sixbridge.manager.conversion

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

enum class ConversionJobStage {
    INPUT_READY,
    AWAITING_APPROVAL,
    BUILDING,
    BUNDLE_READY,
}

@Entity(tableName = "conversion_job")
data class ConversionJobEntity(
    @PrimaryKey val singletonId: Int = SINGLETON_ID,
    val token: String,
    val generation: Long,
    val inputKind: String,
    val inputSha256: String,
    val inputFileName: String,
    val stage: String,
    val approvedAdapterIdsJson: String?,
    val profileSha256: String?,
    val bundleFileName: String?,
    val bundleSha256: String?,
    val cleanupTokensJson: String,
    val updatedAtEpochMillis: Long,
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

internal fun encodeCleanupTokens(tokens: Collection<String>): String =
    buildJsonArray {
        tokens.toSortedSet().forEach { add(JsonPrimitive(it)) }
    }.toString()

internal fun decodeCleanupTokens(encoded: String): Set<String>? {
    val array = runCatching { Json.parseToJsonElement(encoded).jsonArray }.getOrNull()
        ?: return null
    val values = runCatching { array.map { it.jsonPrimitive.content } }.getOrNull()
        ?: return null
    if (values != values.distinct().sorted()) return null
    return values.toSet()
}
