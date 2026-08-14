package dev.sixbridge.manager.conversion

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

internal enum class ConversionIdentityLifecycleState {
    PROVISIONAL,
    ACTIVE,
    LEGACY_UNKNOWN,
    CLEANING,
    RETIRED,
}

@Entity(
    tableName = "conversion_identities",
    indices = [
        Index(value = ["extensionId"], unique = true),
        Index(value = ["packageName"], unique = true),
        Index(value = ["crxKeyAlias"], unique = true),
        Index(value = ["provisionOwnerToken"], unique = true),
    ],
)
data class ConversionIdentityEntity(
    @PrimaryKey val identitySha256: String,
    val logicalSourceIdentity: String,
    val profileMajorVersion: Int,
    val mode: String,
    val displayName: String,
    val extensionId: String,
    val packageName: String,
    val wrapperSignerSha256: String,
    val crxKeyAlias: String?,
    val lastReservedVersionCode: Int,
    val lifecycleState: String,
    val provisionOwnerToken: String?,
    val crxPublicKeySha256: String?,
    val lastSuccessfulVersionCode: Int?,
    val lifecycleUpdatedAtEpochMillis: Long,
)
