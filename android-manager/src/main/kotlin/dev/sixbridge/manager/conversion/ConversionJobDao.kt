package dev.sixbridge.manager.conversion

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
abstract class ConversionJobDao {
    @Query("SELECT * FROM conversion_job WHERE singletonId = 1")
    abstract suspend fun get(): ConversionJobEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    protected abstract suspend fun replaceSingletonInternal(entity: ConversionJobEntity)

    @Transaction
    open suspend fun claimCreate(candidate: ConversionJobEntity): ConversionJobEntity {
        val current = get()
        val currentGeneration = current?.generation ?: 0L
        check(currentGeneration < Long.MAX_VALUE) { "작업 generation이 범위를 초과했습니다." }
        val inheritedCleanup = buildSet {
            decodeCleanupTokens(candidate.cleanupTokensJson)?.let(::addAll)
                ?: throw IllegalArgumentException("cleanup token 목록이 올바르지 않습니다.")
            current?.let { row ->
                require(row.singletonId == ConversionJobEntity.SINGLETON_ID) {
                    "singleton 작업 행이 올바르지 않습니다."
                }
                requireNotNull(decodeCleanupTokens(row.cleanupTokensJson)) {
                    "저장된 cleanup token 목록이 올바르지 않습니다."
                }.let(::addAll)
                add(row.token)
            }
            remove(candidate.token)
        }
        val stored = candidate.copy(
            singletonId = ConversionJobEntity.SINGLETON_ID,
            generation = currentGeneration + 1,
            cleanupTokensJson = encodeCleanupTokens(inheritedCleanup),
        )
        replaceSingletonInternal(stored)
        return stored
    }

    @Query(
        """
        UPDATE conversion_job
        SET stage = :nextStage,
            approvedAdapterIdsJson = :approvedAdapterIdsJson,
            profileSha256 = :profileSha256,
            bundleFileName = :bundleFileName,
            bundleSha256 = :bundleSha256,
            updatedAtEpochMillis = :updatedAtEpochMillis,
            generation = generation + 1
        WHERE singletonId = 1
          AND token = :token
          AND stage = :expectedStage
          AND generation = :expectedGeneration
          AND (:expectedProfileSha256 IS NULL OR profileSha256 = :expectedProfileSha256)
        """,
    )
    abstract suspend fun transition(
        token: String,
        expectedStage: String,
        expectedGeneration: Long,
        expectedProfileSha256: String?,
        nextStage: String,
        approvedAdapterIdsJson: String?,
        profileSha256: String?,
        bundleFileName: String?,
        bundleSha256: String?,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        UPDATE conversion_job
        SET cleanupTokensJson = :cleanupTokensJson,
            generation = generation + 1,
            updatedAtEpochMillis = :updatedAtEpochMillis
        WHERE singletonId = 1
          AND token = :token
          AND generation = :expectedGeneration
        """,
    )
    abstract suspend fun updateCleanupTokens(
        token: String,
        expectedGeneration: Long,
        cleanupTokensJson: String,
        updatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        DELETE FROM conversion_job
        WHERE singletonId = 1 AND token = :token AND generation = :expectedGeneration
        """,
    )
    abstract suspend fun delete(token: String, expectedGeneration: Long): Int
}
