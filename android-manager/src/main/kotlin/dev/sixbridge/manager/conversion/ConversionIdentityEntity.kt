package dev.sixbridge.manager.conversion

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversion_identities")
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
)
