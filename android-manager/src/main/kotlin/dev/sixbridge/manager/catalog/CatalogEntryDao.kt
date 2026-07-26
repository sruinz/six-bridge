package dev.sixbridge.manager.catalog

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private fun CatalogEntryEntity.hasIdentityOf(other: CatalogEntryEntity): Boolean =
    packageName == other.packageName &&
        extensionId == other.extensionId &&
        installedVersionCode == other.installedVersionCode &&
        signerSha256 == other.signerSha256

@Dao
interface CatalogEntryDao {
    @Query("SELECT * FROM catalog_entries ORDER BY packageName")
    fun observeAll(): Flow<List<CatalogEntryEntity>>

    @Upsert
    suspend fun upsert(entry: CatalogEntryEntity)

    @Query(
        """
        UPDATE catalog_entries
        SET iconPng = :iconPng
        WHERE packageName = :packageName
          AND extensionId = :extensionId
          AND installedVersionCode = :installedVersionCode
          AND signerSha256 = :signerSha256
        """,
    )
    suspend fun updateIconIfIdentity(
        packageName: String,
        extensionId: String,
        installedVersionCode: Long,
        signerSha256: String,
        iconPng: ByteArray,
    ): Int

    @Query("SELECT * FROM catalog_entries WHERE packageName = :packageName")
    suspend fun get(packageName: String): CatalogEntryEntity?

    @Transaction
    suspend fun ensureDiscoveredIdentity(
        discovered: CatalogEntryEntity,
    ): CatalogEntryEntity {
        val current = get(discovered.packageName)
        if (current == null || !current.hasIdentityOf(discovered)) {
            upsert(discovered)
            return requireNotNull(get(discovered.packageName))
        }
        return current
    }

    @Transaction
    suspend fun persistIconIfIdentity(
        identity: CatalogIdentity,
        iconPng: ByteArray,
    ): CatalogEntryEntity? {
        val updated = updateIconIfIdentity(
            packageName = identity.packageName,
            extensionId = identity.extensionId,
            installedVersionCode = identity.installedVersionCode,
            signerSha256 = identity.signerSha256,
            iconPng = iconPng,
        )
        return if (updated == 1) get(identity.packageName) else null
    }

    @Query("DELETE FROM catalog_entries WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM catalog_entries WHERE packageName IN (:packageNames)")
    suspend fun deleteAll(packageNames: Set<String>)
}
