package dev.sixbridge.manager.conversion

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ConversionJobDao {
    @Query("SELECT * FROM conversion_job WHERE singletonId = 1")
    suspend fun get(): ConversionJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entity: ConversionJobEntity)

    @Query(
        """
        UPDATE conversion_job
        SET stage = :stage,
            approvedAdapterIdsJson = :approvedAdapterIdsJson,
            profileSha256 = :profileSha256,
            bundleFileName = :bundleFileName,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE singletonId = 1 AND token = :token
        """,
    )
    suspend fun transition(
        token: String,
        stage: String,
        approvedAdapterIdsJson: String?,
        profileSha256: String?,
        bundleFileName: String?,
        updatedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM conversion_job WHERE singletonId = 1 AND token = :token")
    suspend fun delete(token: String): Int
}
