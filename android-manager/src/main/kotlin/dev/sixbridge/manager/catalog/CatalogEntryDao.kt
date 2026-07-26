package dev.sixbridge.manager.catalog

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogEntryDao {
    @Query("SELECT * FROM catalog_entries ORDER BY packageName")
    fun observeAll(): Flow<List<CatalogEntryEntity>>

    @Upsert
    suspend fun upsert(entry: CatalogEntryEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: CatalogEntryEntity): Long

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
    suspend fun persistIconIfIdentity(
        discovered: CatalogEntryEntity,
        iconPng: ByteArray,
    ): CatalogEntryEntity? {
        insertIfAbsent(discovered.copy(iconPng = null))
        val updated = updateIconIfIdentity(
            packageName = discovered.packageName,
            extensionId = discovered.extensionId,
            installedVersionCode = discovered.installedVersionCode,
            signerSha256 = discovered.signerSha256,
            iconPng = iconPng,
        )
        return if (updated == 1) get(discovered.packageName) else null
    }

    @Query("DELETE FROM catalog_entries WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("DELETE FROM catalog_entries WHERE packageName IN (:packageNames)")
    suspend fun deleteAll(packageNames: Set<String>)
}
