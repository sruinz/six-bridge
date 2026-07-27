package dev.sixbridge.manager.conversion

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversion_identity_leases",
    indices = [
        Index(value = ["crxKeyAlias"], unique = true),
        Index(value = ["ownerToken"], unique = true),
    ],
)
data class ConversionIdentityLeaseEntity(
    @PrimaryKey val identitySha256: String,
    val crxKeyAlias: String?,
    val ownerToken: String,
    val state: String,
    val createdAtEpochMillis: Long,
    val extensionId: String?,
    val packageName: String?,
    val wrapperSignerSha256: String?,
    val crxPublicKeySha256: String?,
    val reservedVersionCode: Int?,
)

internal enum class ConversionIdentityLeaseState {
    PENDING,
    READY,
    RESERVED,
    PACKAGING,
    CLEANING,
}
