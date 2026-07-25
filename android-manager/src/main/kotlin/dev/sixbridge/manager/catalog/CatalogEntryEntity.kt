package dev.sixbridge.manager.catalog

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "catalog_entries")
data class CatalogEntryEntity(
    @PrimaryKey val packageName: String,
    val extensionId: String,
    val name: String,
    val iconPng: ByteArray?,
    val installedVersionCode: Long,
    val signerSha256: String,
    val compatibilityStatus: String,
    val reportJson: String?,
    val lastVerifiedAtEpochMillis: Long,
)

fun CatalogEntryEntity.toRecord() = CatalogRecord(
    packageName = packageName,
    extensionId = extensionId,
    name = name,
    iconPng = iconPng,
    installedVersionCode = installedVersionCode,
    signerSha256 = signerSha256,
    compatibilityStatus = compatibilityStatus,
    reportJson = reportJson,
    lastVerifiedAtEpochMillis = lastVerifiedAtEpochMillis,
)

fun CatalogRecord.toEntity() = CatalogEntryEntity(
    packageName = packageName,
    extensionId = extensionId,
    name = name,
    iconPng = iconPng,
    installedVersionCode = installedVersionCode,
    signerSha256 = signerSha256,
    compatibilityStatus = compatibilityStatus,
    reportJson = reportJson,
    lastVerifiedAtEpochMillis = lastVerifiedAtEpochMillis,
)
