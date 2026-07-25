package dev.sixbridge.manager.catalog

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CatalogEntryDao {
    @Query("SELECT * FROM catalog_entries ORDER BY packageName")
    fun observeAll(): Flow<List<CatalogEntryEntity>>

    @Upsert
    suspend fun upsert(entry: CatalogEntryEntity)

    @Query("SELECT * FROM catalog_entries WHERE packageName = :packageName")
    suspend fun get(packageName: String): CatalogEntryEntity?

    @Query("DELETE FROM catalog_entries WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
