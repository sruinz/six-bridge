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

    @Query("SELECT * FROM conversion_identities WHERE extensionId = :extensionId LIMIT 1")
    suspend fun findByExtensionId(extensionId: String): ConversionIdentityEntity?

    @Query("SELECT * FROM conversion_identities WHERE packageName = :packageName LIMIT 1")
    suspend fun findByPackageName(packageName: String): ConversionIdentityEntity?

    @Query("SELECT * FROM conversion_identities WHERE crxKeyAlias = :crxKeyAlias LIMIT 1")
    suspend fun findByCrxKeyAlias(crxKeyAlias: String): ConversionIdentityEntity?

    @Query("SELECT * FROM conversion_identities WHERE provisionOwnerToken = :ownerToken LIMIT 1")
    suspend fun findByProvisionOwnerToken(ownerToken: String): ConversionIdentityEntity?

    @Query(
        """
        SELECT * FROM conversion_identities
        WHERE lifecycleState = :lifecycleState
        ORDER BY displayName COLLATE NOCASE, identitySha256
        """,
    )
    suspend fun listByLifecycle(lifecycleState: String): List<ConversionIdentityEntity>

    @Insert
    suspend fun insert(entity: ConversionIdentityEntity)

    @Update
    suspend fun update(entity: ConversionIdentityEntity)

    @Query("SELECT * FROM conversion_identity_leases WHERE identitySha256 = :identitySha256")
    suspend fun getLease(identitySha256: String): ConversionIdentityLeaseEntity?

    @Query("SELECT * FROM conversion_identity_leases WHERE ownerToken = :ownerToken LIMIT 1")
    suspend fun getLeaseByOwnerToken(ownerToken: String): ConversionIdentityLeaseEntity?

    @Query("SELECT * FROM conversion_job WHERE singletonId = 1")
    suspend fun getJobForIdentityFinalization(): ConversionJobEntity?

    @Insert
    suspend fun insertLease(entity: ConversionIdentityLeaseEntity)

    @Query(
        """
        UPDATE conversion_identity_leases
        SET state = 'READY',
            extensionId = :extensionId,
            packageName = :packageName,
            wrapperSignerSha256 = :wrapperSignerSha256,
            crxPublicKeySha256 = :crxPublicKeySha256
        WHERE identitySha256 = :identitySha256
          AND ownerToken = :ownerToken
          AND state = 'PENDING'
        """,
    )
    suspend fun markLeaseReady(
        identitySha256: String,
        ownerToken: String,
        extensionId: String,
        packageName: String,
        wrapperSignerSha256: String,
        crxPublicKeySha256: String,
    ): Int

    @Query(
        """
        UPDATE conversion_identity_leases
        SET state = :nextState
        WHERE identitySha256 = :identitySha256
          AND ownerToken = :ownerToken
          AND state = :expectedState
        """,
    )
    suspend fun transitionLease(
        identitySha256: String,
        ownerToken: String,
        expectedState: String,
        nextState: String,
    ): Int

    @Query(
        """
        UPDATE conversion_identity_leases
        SET state = 'RESERVED',
            ownerToken = :reservationOwnerToken,
            reservedVersionCode = :reservedVersionCode
        WHERE identitySha256 = :identitySha256
          AND ownerToken = :readyOwnerToken
          AND state = 'READY'
        """,
    )
    suspend fun consumeReadyLease(
        identitySha256: String,
        readyOwnerToken: String,
        reservationOwnerToken: String,
        reservedVersionCode: Int,
    ): Int

    @Query(
        """
        DELETE FROM conversion_identity_leases
        WHERE identitySha256 = :identitySha256
          AND ownerToken = :ownerToken
          AND state = :state
        """,
    )
    suspend fun deleteLease(
        identitySha256: String,
        ownerToken: String,
        state: String,
    ): Int

    @Query(
        """
        UPDATE conversion_identities
        SET lifecycleState = 'CLEANING',
            crxPublicKeySha256 = :cleanupCrxPublicKeySha256,
            lifecycleUpdatedAtEpochMillis = :lifecycleUpdatedAtEpochMillis
        WHERE identitySha256 = :identitySha256
          AND lifecycleState = :expectedLifecycleState
          AND (
            (:expectedProvisionOwnerToken IS NULL AND provisionOwnerToken IS NULL)
            OR provisionOwnerToken = :expectedProvisionOwnerToken
          )
          AND (
            (:expectedCrxPublicKeySha256 IS NULL AND crxPublicKeySha256 IS NULL)
            OR crxPublicKeySha256 = :expectedCrxPublicKeySha256
          )
          AND (
            (:expectedCrxKeyAlias IS NULL AND crxKeyAlias IS NULL)
            OR crxKeyAlias = :expectedCrxKeyAlias
          )
          AND mode = :expectedMode
          AND extensionId = :expectedExtensionId
          AND packageName = :expectedPackageName
          AND wrapperSignerSha256 = :expectedWrapperSignerSha256
          AND lastReservedVersionCode = :expectedLastReservedVersionCode
        """,
    )
    suspend fun transitionIdentityToCleaning(
        identitySha256: String,
        expectedLifecycleState: String,
        expectedProvisionOwnerToken: String?,
        expectedCrxPublicKeySha256: String?,
        expectedCrxKeyAlias: String?,
        expectedMode: String,
        expectedExtensionId: String,
        expectedPackageName: String,
        expectedWrapperSignerSha256: String,
        expectedLastReservedVersionCode: Int,
        cleanupCrxPublicKeySha256: String?,
        lifecycleUpdatedAtEpochMillis: Long,
    ): Int

    @Query(
        """
        DELETE FROM conversion_identities
        WHERE identitySha256 = :identitySha256
          AND lifecycleState = 'CLEANING'
          AND (
            (:provisionOwnerToken IS NULL AND provisionOwnerToken IS NULL)
            OR provisionOwnerToken = :provisionOwnerToken
          )
          AND (
            (:crxKeyAlias IS NULL AND crxKeyAlias IS NULL)
            OR crxKeyAlias = :crxKeyAlias
          )
          AND (
            (:crxPublicKeySha256 IS NULL AND crxPublicKeySha256 IS NULL)
            OR crxPublicKeySha256 = :crxPublicKeySha256
          )
          AND mode = :mode
          AND extensionId = :extensionId
          AND packageName = :packageName
          AND wrapperSignerSha256 = :wrapperSignerSha256
        """,
    )
    suspend fun deleteCleaningIdentity(
        identitySha256: String,
        provisionOwnerToken: String?,
        crxKeyAlias: String?,
        crxPublicKeySha256: String?,
        mode: String,
        extensionId: String,
        packageName: String,
        wrapperSignerSha256: String,
    ): Int

    @Transaction
    suspend fun claimIdentityCleanup(
        expected: ConversionIdentityEntity,
        cleanupCrxPublicKeySha256: String?,
        lifecycleUpdatedAtEpochMillis: Long,
        observedBundleProof: CurrentBundleReadyProof,
    ): ConversionIdentityEntity? {
        if (getLease(expected.identitySha256) != null || !jobMatches(observedBundleProof)) return null
        val changed = transitionIdentityToCleaning(
            identitySha256 = expected.identitySha256,
            expectedLifecycleState = expected.lifecycleState,
            expectedProvisionOwnerToken = expected.provisionOwnerToken,
            expectedCrxPublicKeySha256 = expected.crxPublicKeySha256,
            expectedCrxKeyAlias = expected.crxKeyAlias,
            expectedMode = expected.mode,
            expectedExtensionId = expected.extensionId,
            expectedPackageName = expected.packageName,
            expectedWrapperSignerSha256 = expected.wrapperSignerSha256,
            expectedLastReservedVersionCode = expected.lastReservedVersionCode,
            cleanupCrxPublicKeySha256 = cleanupCrxPublicKeySha256,
            lifecycleUpdatedAtEpochMillis = lifecycleUpdatedAtEpochMillis,
        )
        return if (changed == 1) get(expected.identitySha256) else null
    }

    @Transaction
    suspend fun deleteClaimedIdentity(
        expected: ConversionIdentityEntity,
        observedBundleProof: CurrentBundleReadyProof,
    ): Boolean {
        if (getLease(expected.identitySha256) != null || !jobMatches(observedBundleProof)) {
            return false
        }
        if (get(expected.identitySha256) != expected ||
            expected.lifecycleState != ConversionIdentityLifecycleState.CLEANING.name
        ) {
            return false
        }
        return deleteCleaningIdentity(
            identitySha256 = expected.identitySha256,
            provisionOwnerToken = expected.provisionOwnerToken,
            crxKeyAlias = expected.crxKeyAlias,
            crxPublicKeySha256 = expected.crxPublicKeySha256,
            mode = expected.mode,
            extensionId = expected.extensionId,
            packageName = expected.packageName,
            wrapperSignerSha256 = expected.wrapperSignerSha256,
        ) == 1
    }

    private suspend fun jobMatches(observed: CurrentBundleReadyProof): Boolean {
        val job = getJobForIdentityFinalization()
        return when (observed) {
            CurrentBundleReadyProof.Absent -> job?.stage != ConversionJobStage.BUNDLE_READY.name
            is CurrentBundleReadyProof.Unreadable -> false
            is CurrentBundleReadyProof.Verified ->
                job?.token == observed.ownerToken &&
                    job.generation == observed.generation &&
                    job.stage == ConversionJobStage.BUNDLE_READY.name &&
                    job.bundleSha256 == observed.bundleSha256
        }
    }

    @Transaction
    suspend fun claimCleanup(
        identitySha256: String,
        ownerToken: String,
    ) {
        require(get(identitySha256) == null) {
            "원장에 예약된 CRX 신원은 미예약 cleanup으로 삭제할 수 없습니다."
        }
        val lease = getLease(identitySha256)
            ?: throw IllegalArgumentException("정리할 identity lease를 찾을 수 없습니다.")
        require(lease.ownerToken == ownerToken) {
            "정리할 identity lease owner가 일치하지 않습니다."
        }
        when (lease.state) {
            ConversionIdentityLeaseState.CLEANING.name -> Unit
            ConversionIdentityLeaseState.PENDING.name,
            ConversionIdentityLeaseState.READY.name,
            -> check(
                transitionLease(
                    identitySha256 = identitySha256,
                    ownerToken = ownerToken,
                    expectedState = lease.state,
                    nextState = ConversionIdentityLeaseState.CLEANING.name,
                ) == 1,
            ) {
                "identity cleanup lease를 원자적으로 claim하지 못했습니다."
            }
            else -> throw IllegalArgumentException("예약 또는 package 중인 identity는 정리할 수 없습니다.")
        }
    }

    @Query(
        """
        SELECT * FROM conversion_identities
        WHERE mode = :mode
          AND profileMajorVersion = :profileMajorVersion
          AND lifecycleState = :lifecycleState
        ORDER BY displayName COLLATE NOCASE, identitySha256
        """,
    )
    suspend fun listByModeProfileAndLifecycle(
        mode: String,
        profileMajorVersion: Int,
        lifecycleState: String,
    ): List<ConversionIdentityEntity>

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

    @Query(
        """
        UPDATE conversion_identities
        SET displayName = :displayName,
            lastReservedVersionCode = :lastReservedVersionCode
        WHERE identitySha256 = :identitySha256
          AND lifecycleState = :expectedLifecycleState
          AND (
            (:expectedProvisionOwnerToken IS NULL AND provisionOwnerToken IS NULL)
            OR provisionOwnerToken = :expectedProvisionOwnerToken
          )
        """,
    )
    suspend fun updateForReservation(
        identitySha256: String,
        displayName: String,
        lastReservedVersionCode: Int,
        expectedLifecycleState: String,
        expectedProvisionOwnerToken: String?,
    ): Int

    @Transaction
    suspend fun reserve(
        requested: ConversionIdentityEntity,
        installedVersionCode: Long?,
        readyOwnerToken: String?,
        reservationOwnerToken: String,
        createdAtEpochMillis: Long,
        crxPublicKeySha256: String?,
    ): ConversionIdentityEntity {
        require(installedVersionCode == null || installedVersionCode >= 0) {
            "설치본 versionCode가 올바르지 않습니다."
        }
        require(installedVersionCode == null || installedVersionCode < Int.MAX_VALUE) {
            "설치본 versionCode 다음 값을 예약할 수 없습니다."
        }
        val current = get(requested.identitySha256)
        if (current != null) {
            requireIdentityFieldsMatch(current, requested)
            require(current.crxPublicKeySha256 == crxPublicKeySha256) {
                "CRX 공개 키 proof가 기존 대상과 일치하지 않습니다."
            }
            when (current.lifecycleState) {
                ConversionIdentityLifecycleState.PROVISIONAL.name -> require(
                    current.provisionOwnerToken == reservationOwnerToken,
                ) {
                    "PROVISIONAL 설치 신원은 소유 job만 재시도할 수 있습니다."
                }
                ConversionIdentityLifecycleState.ACTIVE.name -> require(
                    current.provisionOwnerToken == null,
                ) {
                    "ACTIVE 설치 신원에 PROVISIONAL owner가 남아 있습니다."
                }
                ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name,
                ConversionIdentityLifecycleState.CLEANING.name,
                -> throw IllegalArgumentException(
                    "현재 lifecycle의 설치 신원은 package version을 예약할 수 없습니다.",
                )
                else -> throw IllegalArgumentException("설치 신원 lifecycle이 손상되었습니다.")
            }
        }
        val lease = getLease(requested.identitySha256)
        if (current == null && requested.mode == InstallationIdentityMode.CONVERTED.name) {
            require(readyOwnerToken != null) {
                "새 converted 신원에는 READY lease capability가 필요합니다."
            }
            requireReadyLeaseMatches(
                requireNotNull(lease) { "새 converted 신원의 READY lease가 없습니다." },
                requested,
                readyOwnerToken,
                requireNotNull(crxPublicKeySha256) {
                    "새 converted 신원의 공개 키 proof가 없습니다."
                },
            )
        } else {
            require(readyOwnerToken == null) {
                "기존 또는 passthrough 신원에는 READY lease capability를 사용할 수 없습니다."
            }
            require(lease == null) {
                "설치 신원에 처리 중인 lease가 있습니다."
            }
        }
        requireNoCrossIdentityBindingCollision(
            requested,
            findByExtensionId(requested.extensionId),
            "extension ID",
        )
        requireNoCrossIdentityBindingCollision(
            requested,
            findByPackageName(requested.packageName),
            "package",
        )
        requested.crxKeyAlias?.let { alias ->
            requireNoCrossIdentityBindingCollision(
                requested,
                findByCrxKeyAlias(alias),
                "CRX key alias",
            )
        }
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
        val stored = if (current == null) {
            requested.copy(
                lastReservedVersionCode = next.toInt(),
                lifecycleState = ConversionIdentityLifecycleState.PROVISIONAL.name,
                provisionOwnerToken = reservationOwnerToken,
                crxPublicKeySha256 = crxPublicKeySha256,
                lastSuccessfulVersionCode = null,
                lifecycleUpdatedAtEpochMillis = createdAtEpochMillis,
            )
        } else {
            requested.copy(
                lastReservedVersionCode = next.toInt(),
                lifecycleState = current.lifecycleState,
                provisionOwnerToken = current.provisionOwnerToken,
                crxPublicKeySha256 = current.crxPublicKeySha256,
                lastSuccessfulVersionCode = current.lastSuccessfulVersionCode,
                lifecycleUpdatedAtEpochMillis = current.lifecycleUpdatedAtEpochMillis,
            )
        }
        if (current == null) {
            insert(stored)
        } else {
            check(
                updateForReservation(
                    identitySha256 = stored.identitySha256,
                    displayName = stored.displayName,
                    lastReservedVersionCode = stored.lastReservedVersionCode,
                    expectedLifecycleState = current.lifecycleState,
                    expectedProvisionOwnerToken = current.provisionOwnerToken,
                ) == 1,
            ) {
                "설치 신원 lifecycle이 바뀌어 version 예약을 완료하지 못했습니다."
            }
        }
        if (lease == null) {
            insertLease(
                ConversionIdentityLeaseEntity(
                    identitySha256 = stored.identitySha256,
                    crxKeyAlias = stored.crxKeyAlias,
                    ownerToken = reservationOwnerToken,
                    state = ConversionIdentityLeaseState.RESERVED.name,
                    createdAtEpochMillis = createdAtEpochMillis,
                    extensionId = stored.extensionId,
                    packageName = stored.packageName,
                    wrapperSignerSha256 = stored.wrapperSignerSha256,
                    crxPublicKeySha256 = crxPublicKeySha256,
                    reservedVersionCode = stored.lastReservedVersionCode,
                ),
            )
        } else {
            check(
                consumeReadyLease(
                    identitySha256 = stored.identitySha256,
                    readyOwnerToken = requireNotNull(readyOwnerToken),
                    reservationOwnerToken = reservationOwnerToken,
                    reservedVersionCode = stored.lastReservedVersionCode,
                ) == 1,
            ) {
                "READY lease 예약을 원자적으로 소비하지 못했습니다."
            }
        }
        return stored
    }

    @Transaction
    suspend fun claimForPackaging(
        requested: ConversionIdentityEntity,
        versionCode: Int,
        ownerToken: String,
        crxPublicKeySha256: String?,
        packagingState: String,
    ) {
        require(
            Regex("PACKAGING:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
                .matches(packagingState),
        ) {
            "package claim state가 올바르지 않습니다."
        }
        val current = get(requested.identitySha256)
            ?: throw IllegalArgumentException("예약된 설치 신원을 찾을 수 없습니다.")
        requireIdentityFieldsMatch(current, requested)
        require(current.lastReservedVersionCode == versionCode) {
            "현재 원장 versionCode와 package 예약이 일치하지 않습니다."
        }
        val lease = getLease(requested.identitySha256)
            ?: throw IllegalArgumentException("package 예약 lease를 찾을 수 없습니다.")
        requireReservedLeaseMatches(
            lease = lease,
            requested = requested,
            ownerToken = ownerToken,
            versionCode = versionCode,
            crxPublicKeySha256 = crxPublicKeySha256,
        )
        check(
            transitionLease(
                identitySha256 = requested.identitySha256,
                ownerToken = ownerToken,
                expectedState = ConversionIdentityLeaseState.RESERVED.name,
                nextState = packagingState,
            ) == 1,
        ) {
            "package 예약을 원자적으로 소비하지 못했습니다."
        }
    }

    @Transaction
    suspend fun claimBundleReadyCommit(
        ownerToken: String,
        expectedGeneration: Long,
        bundleSha256: String,
    ): ConversionIdentityLeaseEntity? {
        val job = getJobForIdentityFinalization()
            ?: throw IllegalArgumentException("BUNDLE_READY 작업을 찾을 수 없습니다.")
        require(
            job.token == ownerToken &&
                job.stage == ConversionJobStage.BUNDLE_READY.name &&
                job.generation == expectedGeneration &&
                job.bundleSha256 == bundleSha256,
        ) {
            "identity commit proof가 committed BUNDLE_READY 작업과 일치하지 않습니다."
        }
        val lease = getLeaseByOwnerToken(ownerToken)
        if (lease == null) {
            require(findByProvisionOwnerToken(ownerToken) == null) {
                "BUNDLE_READY owner의 PROVISIONAL identity가 lease 없이 남아 있습니다."
            }
            return null
        }
        val packaged = when {
            lease.state.isPackagedLeaseState() -> {
                requireValidPackagedLeaseState(lease.state)
                true
            }
            lease.state.isCleaningCommittedLeaseState() -> {
                requireValidCleaningCommittedLeaseState(lease.state)
                false
            }
            else -> throw IllegalArgumentException(
                "BUNDLE_READY commit 대상 identity lease가 PACKAGED 상태가 아닙니다.",
            )
        }
        val current = get(lease.identitySha256)
            ?: throw IllegalArgumentException("commit할 예약 identity를 찾을 수 없습니다.")
        requireCommittedLeaseMatchesIdentity(current, lease)
        val versionCode = requireNotNull(lease.reservedVersionCode) {
            "commit할 identity lease version이 없습니다."
        }
        require(versionCode > 0 && current.lastReservedVersionCode == versionCode) {
            "commit할 identity version이 현재 예약과 일치하지 않습니다."
        }
        if (!packaged) {
            require(
                current.lifecycleState == ConversionIdentityLifecycleState.ACTIVE.name &&
                    current.provisionOwnerToken == null &&
                    current.lastSuccessfulVersionCode == versionCode,
            ) {
                "CLEANING_COMMITTED identity lifecycle proof가 일치하지 않습니다."
            }
            return lease
        }
        when (current.lifecycleState) {
            ConversionIdentityLifecycleState.PROVISIONAL.name -> require(
                current.provisionOwnerToken == ownerToken,
            ) {
                "PROVISIONAL identity owner가 BUNDLE_READY owner와 일치하지 않습니다."
            }
            ConversionIdentityLifecycleState.ACTIVE.name -> require(
                current.provisionOwnerToken == null,
            ) {
                "ACTIVE identity에 PROVISIONAL owner가 남아 있습니다."
            }
            ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name,
            ConversionIdentityLifecycleState.CLEANING.name,
            -> throw IllegalArgumentException(
                "현재 lifecycle의 identity는 BUNDLE_READY로 commit할 수 없습니다.",
            )
            else -> throw IllegalArgumentException("identity lifecycle이 손상되었습니다.")
        }
        update(
            current.copy(
                lifecycleState = ConversionIdentityLifecycleState.ACTIVE.name,
                provisionOwnerToken = null,
                lastSuccessfulVersionCode = versionCode,
                lifecycleUpdatedAtEpochMillis = job.updatedAtEpochMillis,
            ),
        )
        val cleaningState = cleaningCommittedState(lease.state)
        check(
            transitionLease(
                identitySha256 = lease.identitySha256,
                ownerToken = ownerToken,
                expectedState = lease.state,
                nextState = cleaningState,
            ) == 1,
        ) {
            "BUNDLE_READY identity lease를 CLEANING_COMMITTED로 commit하지 못했습니다."
        }
        return requireNotNull(getLease(lease.identitySha256))
    }

}

private fun requireCommittedLeaseMatchesIdentity(
    identity: ConversionIdentityEntity,
    lease: ConversionIdentityLeaseEntity,
) {
    require(
        identity.identitySha256 == lease.identitySha256 &&
            identity.crxKeyAlias == lease.crxKeyAlias &&
            identity.extensionId == lease.extensionId &&
            identity.packageName == lease.packageName &&
            identity.wrapperSignerSha256 == lease.wrapperSignerSha256 &&
            identity.crxPublicKeySha256 == lease.crxPublicKeySha256,
    ) {
        "commit할 identity lease binding이 원장과 일치하지 않습니다."
    }
}

private fun requireNoCrossIdentityBindingCollision(
    requested: ConversionIdentityEntity,
    owner: ConversionIdentityEntity?,
    label: String,
) {
    require(owner == null || owner.identitySha256 == requested.identitySha256) {
        "$label binding이 다른 설치 신원과 충돌합니다."
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

private fun requireReadyLeaseMatches(
    lease: ConversionIdentityLeaseEntity,
    requested: ConversionIdentityEntity,
    ownerToken: String,
    crxPublicKeySha256: String,
) {
    require(
        lease.ownerToken == ownerToken &&
            lease.state == ConversionIdentityLeaseState.READY.name &&
            lease.crxKeyAlias == requested.crxKeyAlias &&
            lease.extensionId == requested.extensionId &&
            lease.packageName == requested.packageName &&
            lease.wrapperSignerSha256 == requested.wrapperSignerSha256 &&
            lease.crxPublicKeySha256 == crxPublicKeySha256 &&
            lease.reservedVersionCode == null,
    ) {
        "READY lease proof가 예약 binding과 일치하지 않습니다."
    }
}

private fun requireReservedLeaseMatches(
    lease: ConversionIdentityLeaseEntity,
    requested: ConversionIdentityEntity,
    ownerToken: String,
    versionCode: Int,
    crxPublicKeySha256: String?,
) {
    require(
        lease.ownerToken == ownerToken &&
            lease.state == ConversionIdentityLeaseState.RESERVED.name &&
            lease.crxKeyAlias == requested.crxKeyAlias &&
            lease.extensionId == requested.extensionId &&
            lease.packageName == requested.packageName &&
            lease.wrapperSignerSha256 == requested.wrapperSignerSha256 &&
            lease.crxPublicKeySha256 == crxPublicKeySha256 &&
            lease.reservedVersionCode == versionCode,
    ) {
        "package 예약 lease proof가 현재 binding 또는 version과 일치하지 않습니다."
    }
}
