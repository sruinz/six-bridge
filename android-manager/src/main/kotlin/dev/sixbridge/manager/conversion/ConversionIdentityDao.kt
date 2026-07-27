package dev.sixbridge.manager.conversion

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface ConversionIdentityDao {
    @Query("SELECT * FROM conversion_identities WHERE identitySha256 = :identitySha256")
    suspend fun get(identitySha256: String): ConversionIdentityEntity?

    @Insert
    suspend fun insert(entity: ConversionIdentityEntity)

    @Update
    suspend fun update(entity: ConversionIdentityEntity)

    @Query(
        """
        SELECT * FROM conversion_identities
        WHERE mode = :mode AND profileMajorVersion = :profileMajorVersion
        ORDER BY displayName COLLATE NOCASE, identitySha256
        """,
    )
    suspend fun listByModeAndProfile(
        mode: String,
        profileMajorVersion: Int,
    ): List<ConversionIdentityEntity>

    @Transaction
    suspend fun reserve(
        requested: ConversionIdentityEntity,
        installedVersionCode: Long?,
    ): ConversionIdentityEntity {
        require(installedVersionCode == null || installedVersionCode >= 0) {
            "설치본 versionCode가 올바르지 않습니다."
        }
        require(installedVersionCode == null || installedVersionCode < Int.MAX_VALUE) {
            "설치본 versionCode 다음 값을 예약할 수 없습니다."
        }
        val current = get(requested.identitySha256)
        if (current != null) requireIdentityFieldsMatch(current, requested)
        val lastReserved = current?.lastReservedVersionCode ?: 0
        if (lastReserved == Int.MAX_VALUE) {
            throw IllegalStateException("versionCode 예약값이 범위를 초과했습니다.")
        }
        val next = maxOf(
            lastReserved.toLong() + 1,
            installedVersionCode?.plus(1) ?: 1,
            1,
        )
        if (next > Int.MAX_VALUE) {
            throw IllegalStateException("versionCode 예약값이 범위를 초과했습니다.")
        }
        val stored = requested.copy(lastReservedVersionCode = next.toInt())
        if (current == null) {
            insert(stored)
        } else {
            update(stored)
        }
        return stored
    }
}

private fun requireIdentityFieldsMatch(
    current: ConversionIdentityEntity,
    requested: ConversionIdentityEntity,
) {
    require(current.logicalSourceIdentity == requested.logicalSourceIdentity) {
        "논리 원본 신원이 기존 대상과 일치하지 않습니다."
    }
    require(current.profileMajorVersion == requested.profileMajorVersion) {
        "프로필 주 버전이 기존 대상과 일치하지 않습니다."
    }
    require(current.mode == requested.mode) {
        "설치 신원 분기가 기존 대상과 일치하지 않습니다."
    }
    require(current.extensionId == requested.extensionId) {
        "extension ID가 기존 대상과 일치하지 않습니다."
    }
    require(current.packageName == requested.packageName) {
        "package가 기존 대상과 일치하지 않습니다."
    }
    require(current.wrapperSignerSha256 == requested.wrapperSignerSha256) {
        "wrapper signer가 기존 대상과 일치하지 않습니다."
    }
    require(current.crxKeyAlias == requested.crxKeyAlias) {
        "CRX key alias가 기존 대상과 일치하지 않습니다."
    }
}
