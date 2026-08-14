package dev.sixbridge.manager.conversion

import android.database.sqlite.SQLiteConstraintException
import dev.sixbridge.manager.packager.OnDevicePackageIdentity
import dev.sixbridge.manager.packager.OnDeviceWrapperPathRequest
import dev.sixbridge.manager.packager.OnDeviceWrapperPackager
import dev.sixbridge.manager.packager.OnDeviceWrapperRequest
import dev.sixbridge.manager.packager.OnDeviceWrapperResult
import java.util.UUID
import dev.sixbridge.manager.packager.UnreservedIdentityLease
import java.security.MessageDigest
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ConversionIdentityLedger(
    private val dao: ConversionIdentityDao,
    private val localIdentityFactory: () -> UUID = UUID::randomUUID,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val reserveMutex = Mutex()
    private val issuer = Any()

    sealed interface Recovery

    sealed interface Reservation : Recovery {
        val identityKey: InstallationIdentityKey
        val identitySha256: String
        val extensionId: String
        val packageName: String
        val wrapperSignerSha256: String
        val crxKeyAlias: String?
        val crxPublicKeySha256: String?
        val versionCode: Int
    }

    class UnreservedRecovery internal constructor(
        val identitySha256: String,
        val state: String,
    ) : Recovery

    class PackagedRecovery internal constructor(
        val identitySha256: String,
        val claimUuid: UUID,
        val versionCode: Int,
        val wrapperSha256: String,
        val outputFileName: String,
    ) : Recovery

    internal sealed interface PackagingReceipt

    internal fun ownerLifecycle(
        packager: OnDeviceWrapperPackager,
    ): ConversionIdentityOwnerLifecycle = ConversionIdentityOwnerLifecycle(
        this,
        OwnerLifecyclePackager.Full(packager),
    )

    internal fun ownerLifecycleForReservationTests(): ConversionIdentityOwnerLifecycle =
        ConversionIdentityOwnerLifecycle(this, OwnerLifecyclePackager.ReservationTests)

    private class IssuedReservation(
        override val identityKey: InstallationIdentityKey,
        override val extensionId: String,
        override val packageName: String,
        override val wrapperSignerSha256: String,
        override val crxKeyAlias: String?,
        override val crxPublicKeySha256: String?,
        override val versionCode: Int,
        val ownerToken: String,
        val issuer: Any,
    ) : Reservation {
        override val identitySha256: String
            get() = identityKey.identitySha256
    }

    private class PackagingClaim(
        val reservation: IssuedReservation,
        var durableState: String,
    )

    private class IssuedPackagingReceipt(
        val claim: PackagingClaim,
        val issuer: Any,
    ) : PackagingReceipt

    private class StartedPackaging(
        val claim: PackagingClaim,
        val receipt: IssuedPackagingReceipt,
    )

    suspend fun resolveSelection(
        logicalSourceIdentity: LogicalSourceIdentity?,
        profileMajorVersion: Int,
        mode: InstallationIdentityMode,
        selection: InstallationIdentitySelection?,
        ownerToken: String,
    ): InstallationIdentityKey {
        requireOwnerToken(ownerToken)
        require(profileMajorVersion > 0) { "프로필 주 버전이 올바르지 않습니다." }
        dao.findByProvisionOwnerToken(ownerToken)?.let { provisional ->
            return restoreOwnedProvisionalSelection(
                provisional = provisional,
                logicalSourceIdentity = logicalSourceIdentity,
                profileMajorVersion = profileMajorVersion,
                mode = mode,
                selection = selection,
                ownerToken = ownerToken,
            )
        }
        return when {
            logicalSourceIdentity != null -> {
                require(selection == null) {
                    "안정적 신원과 수동 대상 선택을 함께 사용할 수 없습니다."
                }
                InstallationIdentityKey.create(
                    logicalSourceIdentity,
                    profileMajorVersion,
                    mode,
                )
            }
            selection == InstallationIdentitySelection.CreateNew -> {
                require(mode == InstallationIdentityMode.CONVERTED) {
                    "불안정 ZIP은 converted 신원으로만 만들 수 있습니다."
                }
                InstallationIdentityKey.create(
                    LogicalSourceIdentity.local(localIdentityFactory()),
                    profileMajorVersion,
                    mode,
                )
            }
            selection is InstallationIdentitySelection.UseExisting -> {
                require(mode == InstallationIdentityMode.CONVERTED) {
                    "불안정 ZIP은 converted 신원으로만 선택할 수 있습니다."
                }
                requireLowerHexSha256(selection.identitySha256, "기존 대상 identity")
                val current = dao.get(selection.identitySha256)
                    ?: throw IllegalArgumentException("선택한 기존 대상을 찾을 수 없습니다.")
                require(
                    current.lifecycleState == ConversionIdentityLifecycleState.ACTIVE.name &&
                        current.provisionOwnerToken == null,
                ) {
                    "ACTIVE 상태의 기존 대상만 선택할 수 있습니다."
                }
                val restored = restoreIdentityKey(current, "선택한 기존 대상")
                require(restored.mode == InstallationIdentityMode.CONVERTED) {
                    "passthrough 신원은 불안정 ZIP 대상이 될 수 없습니다."
                }
                require(restored.profileMajorVersion == profileMajorVersion) {
                    "다른 프로필 주 버전의 대상은 선택할 수 없습니다."
                }
                restored
            }
            else -> throw IllegalArgumentException(
                "안정적 신원이 없는 ZIP은 CreateNew 또는 UseExisting 선택이 필요합니다.",
            )
        }
    }

    private fun restoreOwnedProvisionalSelection(
        provisional: ConversionIdentityEntity,
        logicalSourceIdentity: LogicalSourceIdentity?,
        profileMajorVersion: Int,
        mode: InstallationIdentityMode,
        selection: InstallationIdentitySelection?,
        ownerToken: String,
    ): InstallationIdentityKey {
        require(
            provisional.lifecycleState == ConversionIdentityLifecycleState.PROVISIONAL.name &&
                provisional.provisionOwnerToken == ownerToken,
        ) {
            "job owner의 PROVISIONAL 설치 신원이 손상되었습니다."
        }
        val restored = restoreIdentityKey(provisional, "job owner의 PROVISIONAL 대상")
        require(
            restored.profileMajorVersion == profileMajorVersion && restored.mode == mode,
        ) {
            "기존 PROVISIONAL 설치 신원과 같은 profile과 mode로만 재시도할 수 있습니다."
        }
        when {
            logicalSourceIdentity != null -> {
                require(selection == null) {
                    "안정적 신원과 수동 대상 선택을 함께 사용할 수 없습니다."
                }
                require(restored.logicalSourceIdentity == logicalSourceIdentity) {
                    "PROVISIONAL 설치 신원의 논리 원본을 바꿀 수 없습니다."
                }
            }
            selection == InstallationIdentitySelection.CreateNew -> {
                require(mode == InstallationIdentityMode.CONVERTED) {
                    "불안정 ZIP은 converted 신원으로만 만들 수 있습니다."
                }
                require(
                    restored.logicalSourceIdentity.canonicalValue.startsWith("local-"),
                ) {
                    "CreateNew 재시도 대상이 local 신원이 아닙니다."
                }
            }
            else -> throw IllegalArgumentException(
                "PROVISIONAL 설치 신원을 남긴 채 다른 대상을 선택할 수 없습니다.",
            )
        }
        return restored
    }

    private fun restoreIdentityKey(
        entity: ConversionIdentityEntity,
        label: String,
    ): InstallationIdentityKey = runCatching {
        InstallationIdentityKey.restore(
            logicalSourceIdentity = entity.logicalSourceIdentity,
            profileMajorVersion = entity.profileMajorVersion,
            mode = entity.mode,
            identitySha256 = entity.identitySha256,
        )
    }.getOrElse {
        throw IllegalArgumentException("$label 신원이 손상되었습니다.")
    }

    suspend fun reserve(
        request: ConversionIdentityReservation,
        ownerToken: String,
        unreservedLease: UnreservedIdentityLease? = null,
    ): Reservation = reserveMutex.withLock {
        validateRequest(request)
        requireOwnerToken(ownerToken)
        require(
            unreservedLease == null ||
                (
                    unreservedLease.identitySha256 == request.identityKey.identitySha256 &&
                        unreservedLease.identity.toBinding() == request.packageBinding &&
                        unreservedLease.ownerToken.toString() == ownerToken
                    )
        ) {
            "READY lease capability가 예약 identity 또는 binding과 일치하지 않습니다."
        }
        val createdAtEpochMillis = currentTimeMillis()
        val requested = request.toEntity(ownerToken, createdAtEpochMillis)
        val stored = try {
            dao.reserve(
                requested = requested,
                installedVersionCode = request.installedVersionCode,
                readyOwnerToken = unreservedLease?.ownerToken?.toString(),
                reservationOwnerToken = ownerToken,
                createdAtEpochMillis = createdAtEpochMillis,
                crxPublicKeySha256 = request.packageBinding.crxPublicKeySha256,
            )
        } catch (_: SQLiteConstraintException) {
            throw IllegalArgumentException("package identity binding이 다른 설치 신원과 충돌합니다.")
        }
        stored.toReservation(
            ownerToken = ownerToken,
            crxPublicKeySha256 = request.packageBinding.crxPublicKeySha256,
        )
    }

    suspend fun resolveUnreservedIdentity(
        identityKey: InstallationIdentityKey,
        ownerToken: String,
        packager: OnDeviceWrapperPackager,
    ): UnreservedIdentityLease = reserveMutex.withLock {
        packager.withIdentityMutationLockForLedger(identityKey.identitySha256) {
            val ownerUuid = requireOwnerToken(ownerToken)
            require(identityKey.mode == InstallationIdentityMode.CONVERTED) {
                "converted CreateNew 신원만 미예약 lease를 만들 수 있습니다."
            }
            val job = dao.getJobForIdentityFinalization()
            require(
                job?.token == ownerToken &&
                    job.stage != ConversionJobStage.DISCARDING.name,
            ) {
                "미예약 identity owner 작업이 활성 상태가 아닙니다."
            }
            require(dao.get(identityKey.identitySha256) == null) {
                "이미 예약된 identity에는 미예약 lease를 만들 수 없습니다."
            }
            val plannedAlias = packager.plannedCrxAlias(identityKey)
            var stored = dao.getLease(identityKey.identitySha256)
            if (stored == null) {
                try {
                    dao.insertLease(
                        ConversionIdentityLeaseEntity(
                            identitySha256 = identityKey.identitySha256,
                            crxKeyAlias = plannedAlias,
                            ownerToken = ownerToken,
                            state = ConversionIdentityLeaseState.PENDING.name,
                            createdAtEpochMillis = currentTimeMillis(),
                            extensionId = null,
                            packageName = null,
                            wrapperSignerSha256 = null,
                            crxPublicKeySha256 = null,
                            reservedVersionCode = null,
                        ),
                    )
                } catch (_: SQLiteConstraintException) {
                    throw IllegalArgumentException("다른 작업이 identity lease를 먼저 생성했습니다.")
                }
                stored = requireNotNull(dao.getLease(identityKey.identitySha256))
            }
            require(
                stored.crxKeyAlias == plannedAlias &&
                    stored.ownerToken == ownerToken &&
                    stored.state in setOf(
                        ConversionIdentityLeaseState.PENDING.name,
                        ConversionIdentityLeaseState.READY.name,
                    ),
            ) {
                "identity lease가 key 생성 또는 예약 가능한 상태가 아닙니다."
            }
            val lease = packager.createOrRecoverUnreservedIdentity(identityKey, ownerUuid)
            val identity = lease.identity
            val publicKeySha256 = identity.crxPublicKeyDer?.let(::sha256Hex)
                ?: throw IllegalArgumentException("converted identity 공개 키 proof가 없습니다.")
            if (stored.state == ConversionIdentityLeaseState.PENDING.name) {
                check(
                    dao.markLeaseReady(
                        identitySha256 = identityKey.identitySha256,
                        ownerToken = stored.ownerToken,
                        extensionId = identity.extensionId,
                        packageName = identity.packageName,
                        wrapperSignerSha256 = identity.wrapperSignerSha256,
                        crxPublicKeySha256 = publicKeySha256,
                    ) == 1,
                ) {
                    "PENDING identity lease를 READY로 전이하지 못했습니다."
                }
                stored = requireNotNull(dao.getLease(identityKey.identitySha256))
            }
            requireReadyIdentityMatches(stored, identity, publicKeySha256)
            lease
        }
    }

    suspend fun discardUnreservedIdentity(
        lease: UnreservedIdentityLease,
        packager: OnDeviceWrapperPackager,
    ): Boolean = reserveMutex.withLock {
        packager.withIdentityMutationLockForLedger(lease.identitySha256) {
            dao.claimCleanup(lease.identitySha256, lease.ownerToken.toString())
            val stored = requireNotNull(dao.getLease(lease.identitySha256))
            val deleted = packagerDelete(stored, packager, lease)
            check(deleted) { "CLEANING CRX Android Keystore alias 삭제에 실패했습니다." }
            check(
                dao.deleteLease(
                    identitySha256 = lease.identitySha256,
                    ownerToken = lease.ownerToken.toString(),
                    state = ConversionIdentityLeaseState.CLEANING.name,
                ) == 1,
            ) {
                "CLEANING identity lease finalize에 실패했습니다."
            }
            deleted
        }
    }

    internal suspend fun packageWrapper(
        packager: OnDeviceWrapperPackager,
        request: OnDeviceWrapperRequest,
    ): OnDeviceWrapperResult = reserveMutex.withLock {
        packager.withIdentityMutationLockForLedger(request.reservation.identitySha256) {
            val started = startPackaging(request.reservation)
            val result = try {
                packager.packageClaimed(this, started.receipt, request)
            } catch (error: Throwable) {
                restoreFailedPackaging(started.claim, error)
            }
            finishPublishedPackaging(packager, started.claim, result)
        }
    }

    internal suspend fun packageWrapper(
        packager: OnDeviceWrapperPackager,
        request: OnDeviceWrapperPathRequest,
    ): OnDeviceWrapperResult = reserveMutex.withLock {
        packager.withIdentityMutationLockForLedger(request.reservation.identitySha256) {
            val started = startPackaging(request.reservation)
            val result = try {
                packager.packageClaimed(this, started.receipt, request)
            } catch (error: Throwable) {
                restoreFailedPackaging(started.claim, error)
            }
            finishPublishedPackaging(packager, started.claim, result)
        }
    }

    internal fun requirePackagingReceipt(
        receipt: PackagingReceipt,
        reservation: Reservation,
    ) {
        val issuedReceipt = receipt as? IssuedPackagingReceipt
            ?: throw IllegalArgumentException("ledger가 발급하지 않은 package receipt입니다.")
        require(issuedReceipt.issuer === issuer) {
            "package receipt를 발급한 ledger가 아닙니다."
        }
        require(issuedReceipt.claim.reservation === reservation) {
            "package receipt와 예약 capability가 일치하지 않습니다."
        }
    }

    private suspend fun startPackaging(
        reservation: Reservation,
    ): StartedPackaging {
        val issued = reservation as? IssuedReservation
            ?: throw IllegalArgumentException("ledger가 발급하지 않은 package 예약 capability입니다.")
        require(issued.issuer === issuer) {
            "package 예약 capability를 발급한 ledger가 아닙니다."
        }
        val packagingState = packagingState(UUID.randomUUID())
        val claim = PackagingClaim(issued, packagingState)
        dao.claimForPackaging(
            requested = issued.toEntity(),
            versionCode = issued.versionCode,
            ownerToken = issued.ownerToken,
            crxPublicKeySha256 = issued.crxPublicKeySha256,
            packagingState = packagingState,
        )
        val receipt = IssuedPackagingReceipt(claim, issuer)
        return StartedPackaging(claim, receipt)
    }

    internal suspend fun markPublishing(
        receipt: PackagingReceipt,
        reservation: Reservation,
        wrapperSha256: String,
        outputFileName: String,
    ) {
        requirePackagingReceipt(receipt, reservation)
        val issued = receipt as IssuedPackagingReceipt
        val claim = issued.claim
        val publishingState = publishingState(
            packagingState = claim.durableState,
            wrapperSha256 = wrapperSha256,
            outputFileName = outputFileName,
        )
        check(
            dao.transitionLease(
                identitySha256 = claim.reservation.identitySha256,
                ownerToken = claim.reservation.ownerToken,
                expectedState = claim.durableState,
                nextState = publishingState,
            ) == 1,
        ) {
            "wrapper publish proof를 원자적으로 claim하지 못했습니다."
        }
        claim.durableState = publishingState
    }

    private suspend fun restoreFailedPackaging(
        claim: PackagingClaim,
        error: Throwable,
    ): Nothing {
        val finishFailure = runCatching {
            withContext(NonCancellable) {
                finishPackagingFailure(claim)
            }
        }.exceptionOrNull()
        if (finishFailure != null) error.addSuppressed(finishFailure)
        throw error
    }

    private suspend fun finishPublishedPackaging(
        packager: OnDeviceWrapperPackager,
        claim: PackagingClaim,
        result: OnDeviceWrapperResult,
    ): OnDeviceWrapperResult {
        try {
            withContext(NonCancellable) {
                finishPackagingSuccess(claim, result)
            }
        } catch (finishError: Throwable) {
            val cleanupError = runCatching {
                withContext(NonCancellable) {
                    packager.discardPublishedResult(
                        result = result,
                        identitySha256 = claim.reservation.identitySha256,
                        claimUuid = UUID.fromString(claim.durableState.split(':')[1]),
                    )
                }
            }.exceptionOrNull()
            if (cleanupError != null) finishError.addSuppressed(cleanupError)
            throw IllegalStateException(
                "package 성공 상태를 원장에 완료하지 못했습니다.",
                finishError,
            )
        }
        return result
    }

    suspend fun recoverByOwnerToken(
        ownerToken: String,
        packager: OnDeviceWrapperPackager,
    ): Recovery = recoverByOwnerTokenInternal(ownerToken, packager)

    internal suspend fun recoverNonPublishedByOwnerTokenForTests(
        ownerToken: String,
    ): Recovery = recoverByOwnerTokenInternal(ownerToken, null)

    private suspend fun recoverByOwnerTokenInternal(
        ownerToken: String,
        packager: OnDeviceWrapperPackager?,
    ): Recovery = reserveMutex.withLock {
        requireOwnerToken(ownerToken)
        var lease = dao.getLeaseByOwnerToken(ownerToken)
            ?: throw IllegalArgumentException("복구할 identity lease를 찾을 수 없습니다.")
        when {
            lease.state in setOf(
                ConversionIdentityLeaseState.PENDING.name,
                ConversionIdentityLeaseState.READY.name,
            ) -> UnreservedRecovery(
                identitySha256 = lease.identitySha256,
                state = lease.state,
            )
            lease.state.isPackagingState() -> {
                check(
                    dao.transitionLease(
                        identitySha256 = lease.identitySha256,
                        ownerToken = ownerToken,
                        expectedState = lease.state,
                        nextState = ConversionIdentityLeaseState.RESERVED.name,
                    ) == 1,
                ) {
                    "중단된 PACKAGING lease를 RESERVED로 복구하지 못했습니다."
                }
                lease = requireNotNull(dao.getLeaseByOwnerToken(ownerToken))
                recoverReservation(lease)
            }
            lease.state.isPublishingState() -> {
                val publishing = lease.toPackagedRecovery()
                val requiredPackager = requireNotNull(packager) {
                    "PUBLISHING wrapper 복구에는 packager가 필요합니다."
                }
                if (!requiredPackager.verifyPublishedOutput(
                        identitySha256 = publishing.identitySha256,
                        outputFileName = publishing.outputFileName,
                        wrapperSha256 = publishing.wrapperSha256,
                    )
                ) {
                    check(
                        dao.transitionLease(
                            identitySha256 = lease.identitySha256,
                            ownerToken = ownerToken,
                            expectedState = lease.state,
                            nextState = ConversionIdentityLeaseState.RESERVED.name,
                        ) == 1,
                    ) {
                        "publish 전 PUBLISHING lease를 RESERVED로 복구하지 못했습니다."
                    }
                    lease = requireNotNull(dao.getLeaseByOwnerToken(ownerToken))
                    recoverReservation(lease)
                } else {
                    val packagedState = packagedState(lease.state)
                    check(
                        dao.transitionLease(
                            identitySha256 = lease.identitySha256,
                            ownerToken = ownerToken,
                            expectedState = lease.state,
                            nextState = packagedState,
                        ) == 1,
                    ) {
                        "publish 완료 PUBLISHING lease를 PACKAGED로 복구하지 못했습니다."
                    }
                    publishing
                }
            }
            lease.state.isPackagedState() -> {
                val packaged = lease.toPackagedRecovery()
                val requiredPackager = requireNotNull(packager) {
                    "PACKAGED wrapper 복구에는 packager가 필요합니다."
                }
                if (!requiredPackager.verifyPublishedOutput(
                        identitySha256 = packaged.identitySha256,
                        outputFileName = packaged.outputFileName,
                        wrapperSha256 = packaged.wrapperSha256,
                    )
                ) {
                    check(
                        dao.transitionLease(
                            identitySha256 = lease.identitySha256,
                            ownerToken = ownerToken,
                            expectedState = lease.state,
                            nextState = ConversionIdentityLeaseState.RESERVED.name,
                        ) == 1,
                    ) {
                        "유실된 PACKAGED lease를 RESERVED로 복구하지 못했습니다."
                    }
                    lease = requireNotNull(dao.getLeaseByOwnerToken(ownerToken))
                    recoverReservation(lease)
                } else {
                    packaged
                }
            }
            lease.state == ConversionIdentityLeaseState.RESERVED.name -> recoverReservation(lease)
            lease.state.isCleaningPackagedState() ->
                throw IllegalArgumentException("wrapper cleanup 중인 identity lease는 복구할 수 없습니다.")
            else -> throw IllegalArgumentException("복구할 수 없는 identity lease 상태입니다.")
        }
    }

    suspend fun releaseFailedAttempt(
        ownerToken: String,
        packager: OnDeviceWrapperPackager,
    ): Boolean = releaseFailedAttemptInternal(ownerToken, packager)

    internal suspend fun releaseFailedAttempt(
        ownerToken: String,
    ): Boolean = releaseFailedAttemptInternal(ownerToken, null)

    private suspend fun releaseFailedAttemptInternal(
        ownerToken: String,
        packager: OnDeviceWrapperPackager?,
    ): Boolean = reserveMutex.withLock {
        requireOwnerToken(ownerToken)
        var lease = dao.getLeaseByOwnerToken(ownerToken) ?: return@withLock false
        when {
            lease.state in setOf(
                ConversionIdentityLeaseState.PENDING.name,
                ConversionIdentityLeaseState.READY.name,
                ConversionIdentityLeaseState.CLEANING.name,
            ) -> {
                val requiredPackager = requireNotNull(packager) {
                    "미예약 identity abort에는 packager가 필요합니다."
                }
                requiredPackager.withIdentityMutationLockForLedger(lease.identitySha256) {
                    dao.claimCleanup(lease.identitySha256, ownerToken)
                    lease = requireNotNull(dao.getLeaseByOwnerToken(ownerToken))
                    check(deleteUnreservedIdentity(lease, requiredPackager)) {
                        "CLEANING CRX Android Keystore alias 삭제에 실패했습니다."
                    }
                    check(
                        dao.deleteLease(
                            identitySha256 = lease.identitySha256,
                            ownerToken = ownerToken,
                            state = ConversionIdentityLeaseState.CLEANING.name,
                        ) == 1,
                    ) {
                        "CLEANING identity lease abort finalize에 실패했습니다."
                    }
                }
            }
            lease.state == ConversionIdentityLeaseState.RESERVED.name -> check(
                dao.deleteLease(
                    identitySha256 = lease.identitySha256,
                    ownerToken = ownerToken,
                    state = ConversionIdentityLeaseState.RESERVED.name,
                ) == 1,
            ) {
                "RESERVED identity lease abort에 실패했습니다."
            }
            lease.state.isPackagingState() -> {
                check(
                    dao.transitionLease(
                        identitySha256 = lease.identitySha256,
                        ownerToken = ownerToken,
                        expectedState = lease.state,
                        nextState = ConversionIdentityLeaseState.RESERVED.name,
                    ) == 1,
                ) {
                    "PACKAGING identity lease abort rollback에 실패했습니다."
                }
                check(
                    dao.deleteLease(
                        identitySha256 = lease.identitySha256,
                        ownerToken = ownerToken,
                        state = ConversionIdentityLeaseState.RESERVED.name,
                    ) == 1,
                ) {
                    "PACKAGING identity lease abort finalize에 실패했습니다."
                }
            }
            lease.state.isPublishingState() || lease.state.isPackagedState() -> {
                val requiredPackager = requireNotNull(packager) {
                    "완료된 wrapper abort에는 packager가 필요합니다."
                }
                val packaged = lease.toPackagedRecovery()
                val cleanupState = cleaningPackagedState(lease.state)
                check(
                    dao.transitionLease(
                        identitySha256 = lease.identitySha256,
                        ownerToken = ownerToken,
                        expectedState = lease.state,
                        nextState = cleanupState,
                    ) == 1,
                ) {
                    "published wrapper cleanup을 원자적으로 claim하지 못했습니다."
                }
                lease = requireNotNull(dao.getLeaseByOwnerToken(ownerToken))
                requiredPackager.discardPublishedOutput(
                    identitySha256 = packaged.identitySha256,
                    claimUuid = packaged.claimUuid,
                    outputFileName = packaged.outputFileName,
                    wrapperSha256 = packaged.wrapperSha256,
                )
                check(
                    dao.deleteLease(
                        identitySha256 = lease.identitySha256,
                        ownerToken = ownerToken,
                        state = cleanupState,
                    ) == 1,
                ) {
                    "CLEANING_PACKAGED identity lease abort finalize에 실패했습니다."
                }
            }
            lease.state.isCleaningPackagedState() -> {
                val requiredPackager = requireNotNull(packager) {
                    "완료된 wrapper abort에는 packager가 필요합니다."
                }
                val packaged = lease.toPackagedRecovery()
                requiredPackager.discardPublishedOutput(
                    identitySha256 = packaged.identitySha256,
                    claimUuid = packaged.claimUuid,
                    outputFileName = packaged.outputFileName,
                    wrapperSha256 = packaged.wrapperSha256,
                )
                check(
                    dao.deleteLease(
                        identitySha256 = lease.identitySha256,
                        ownerToken = ownerToken,
                        state = lease.state,
                    ) == 1,
                ) {
                    "CLEANING_PACKAGED identity lease abort finalize에 실패했습니다."
                }
            }
            else -> throw IllegalArgumentException("abort할 수 없는 identity lease 상태입니다.")
        }
        true
    }

    private suspend fun isOwnerDiscardComplete(
        ownerToken: String,
    ): Boolean = reserveMutex.withLock {
        requireOwnerToken(ownerToken)
        dao.findByProvisionOwnerToken(ownerToken) == null &&
            dao.getLeaseByOwnerToken(ownerToken) == null
    }

    private suspend fun finalizePackagedForBundleReady(
        ownerToken: String,
        expectedGeneration: Long,
        bundleSha256: String,
    ): Boolean = reserveMutex.withLock {
        requireOwnerToken(ownerToken)
        requireLowerHexSha256(bundleSha256, "완료 bundle")
        dao.finalizePackagedForBundleReady(ownerToken, expectedGeneration, bundleSha256)
    }

    suspend fun listExistingTargets(
        profileMajorVersion: Int,
    ): List<ExistingConversionTarget> {
        require(profileMajorVersion > 0) { "프로필 주 버전이 올바르지 않습니다." }
        return dao.listByModeProfileAndLifecycle(
            mode = InstallationIdentityMode.CONVERTED.name,
            profileMajorVersion = profileMajorVersion,
            lifecycleState = ConversionIdentityLifecycleState.ACTIVE.name,
        ).map { entity ->
            ExistingConversionTarget(
                identitySha256 = entity.identitySha256,
                displayName = entity.displayName,
                extensionId = entity.extensionId,
                packageName = entity.packageName,
                wrapperSignerSha256 = entity.wrapperSignerSha256,
            )
        }
    }

    internal suspend fun hasIdentity(identityKey: InstallationIdentityKey): Boolean =
        dao.get(identityKey.identitySha256) != null

    private fun validateRequest(request: ConversionIdentityReservation) {
        val binding = request.packageBinding
        require(request.displayName.isNotBlank()) { "표시 이름이 비어 있습니다." }
        require(EXTENSION_ID.matches(binding.extensionId)) { "extension ID가 올바르지 않습니다." }
        require(binding.packageName == "dev.sixbridge.ext.${binding.extensionId}") {
            "package가 extension ID와 일치하지 않습니다."
        }
        requireLowerHexSha256(binding.wrapperSignerSha256, "wrapper signer")
        require(request.installedVersionCode == null || request.installedVersionCode >= 0) {
            "설치본 versionCode가 올바르지 않습니다."
        }
        when (request.identityKey.mode) {
            InstallationIdentityMode.PASSTHROUGH,
            InstallationIdentityMode.PREPARED,
            -> require(binding.crxKeyAlias == null) {
                "외부 서명 CRX 신원에는 CRX key alias를 저장할 수 없습니다."
            }
            InstallationIdentityMode.CONVERTED -> require(
                !binding.crxKeyAlias.isNullOrBlank() &&
                    binding.crxPublicKeySha256?.matches(SHA256) == true,
            ) {
                "converted 신원에는 CRX key alias와 공개 키 proof가 필요합니다."
            }
        }
        if (request.identityKey.mode != InstallationIdentityMode.CONVERTED) {
            require(binding.crxPublicKeySha256 == null) {
                "외부 서명 CRX 신원에는 CRX 공개 키 proof를 저장할 수 없습니다."
            }
        }
    }

    private fun ConversionIdentityReservation.toEntity(
        ownerToken: String,
        lifecycleUpdatedAtEpochMillis: Long,
    ): ConversionIdentityEntity {
        val binding = packageBinding
        return ConversionIdentityEntity(
            identitySha256 = identityKey.identitySha256,
            logicalSourceIdentity = identityKey.logicalSourceIdentity.canonicalValue,
            profileMajorVersion = identityKey.profileMajorVersion,
            mode = identityKey.mode.name,
            displayName = displayName,
            extensionId = binding.extensionId,
            packageName = binding.packageName,
            wrapperSignerSha256 = binding.wrapperSignerSha256,
            crxKeyAlias = binding.crxKeyAlias,
            lastReservedVersionCode = 0,
            lifecycleState = ConversionIdentityLifecycleState.PROVISIONAL.name,
            provisionOwnerToken = ownerToken,
            crxPublicKeySha256 = binding.crxPublicKeySha256,
            lastSuccessfulVersionCode = null,
            lifecycleUpdatedAtEpochMillis = lifecycleUpdatedAtEpochMillis,
        )
    }

    private suspend fun finishPackagingSuccess(
        claim: PackagingClaim,
        result: OnDeviceWrapperResult,
    ) {
        val reservation = claim.reservation
        require(
            result.extensionId == reservation.extensionId &&
                result.packageName == reservation.packageName &&
                result.versionCode == reservation.versionCode &&
                result.wrapperSha256.matches(SHA256),
        ) {
            "package 결과가 claim identity와 일치하지 않습니다."
        }
        val packagedState = packagedState(
            publishingState = claim.durableState,
        )
        check(
            dao.transitionLease(
                identitySha256 = reservation.identitySha256,
                ownerToken = reservation.ownerToken,
                expectedState = claim.durableState,
                nextState = packagedState,
            ) == 1,
        ) {
            "package 성공 lease를 PACKAGED로 완료하지 못했습니다."
        }
    }

    private suspend fun finishPackagingFailure(claim: PackagingClaim) {
        val reservation = claim.reservation
        if (claim.durableState.isPublishingState()) {
            return
        }
        check(
            dao.transitionLease(
                identitySha256 = reservation.identitySha256,
                ownerToken = reservation.ownerToken,
                expectedState = claim.durableState,
                nextState = ConversionIdentityLeaseState.RESERVED.name,
            ) == 1,
        ) {
            "package 실패 lease를 RESERVED로 복구하지 못했습니다."
        }
    }

    private fun ConversionIdentityLeaseEntity.toPackagedRecovery(): PackagedRecovery {
        val parts = state.split(':')
        require(
            parts.size == 4 &&
                (
                    state.isPublishingState() ||
                        state.isPackagedState() ||
                        state.isCleaningPackagedState()
                    ),
        ) {
            "PACKAGED identity lease proof가 손상되었습니다."
        }
        return PackagedRecovery(
            identitySha256 = identitySha256,
            claimUuid = UUID.fromString(parts[1]),
            versionCode = requireNotNull(reservedVersionCode) {
                "PACKAGED identity version이 없습니다."
            },
            wrapperSha256 = parts[2],
            outputFileName = decodeHex(parts[3]),
        )
    }

    private suspend fun recoverReservation(
        lease: ConversionIdentityLeaseEntity,
    ): Reservation {
        require(lease.state == ConversionIdentityLeaseState.RESERVED.name) {
            "복구할 package lease가 RESERVED 상태가 아닙니다."
        }
        val current = dao.get(lease.identitySha256)
            ?: throw IllegalArgumentException("복구할 예약 identity를 찾을 수 없습니다.")
        require(lease.reservedVersionCode == current.lastReservedVersionCode) {
            "복구할 package lease version이 현재 identity와 일치하지 않습니다."
        }
        return current.toReservation(
            ownerToken = lease.ownerToken,
            crxPublicKeySha256 = lease.crxPublicKeySha256,
        )
    }

    private fun ConversionIdentityEntity.toReservation(
        ownerToken: String,
        crxPublicKeySha256: String?,
    ): Reservation {
        val key = InstallationIdentityKey.restore(
            logicalSourceIdentity,
            profileMajorVersion,
            mode,
            identitySha256,
        )
        return IssuedReservation(
            identityKey = key,
            extensionId = extensionId,
            packageName = packageName,
            wrapperSignerSha256 = wrapperSignerSha256,
            crxKeyAlias = crxKeyAlias,
            crxPublicKeySha256 = crxPublicKeySha256,
            versionCode = lastReservedVersionCode,
            ownerToken = ownerToken,
            issuer = issuer,
        )
    }

    private fun IssuedReservation.toEntity() = ConversionIdentityEntity(
        identitySha256 = identitySha256,
        logicalSourceIdentity = identityKey.logicalSourceIdentity.canonicalValue,
        profileMajorVersion = identityKey.profileMajorVersion,
        mode = identityKey.mode.name,
        displayName = "",
        extensionId = extensionId,
        packageName = packageName,
        wrapperSignerSha256 = wrapperSignerSha256,
        crxKeyAlias = crxKeyAlias,
        lastReservedVersionCode = versionCode,
        lifecycleState = ConversionIdentityLifecycleState.PROVISIONAL.name,
        provisionOwnerToken = ownerToken,
        crxPublicKeySha256 = crxPublicKeySha256,
        lastSuccessfulVersionCode = null,
        lifecycleUpdatedAtEpochMillis = 0,
    )

    private fun OnDevicePackageIdentity.toBinding() = ConversionPackageBinding(
        extensionId = extensionId,
        packageName = packageName,
        wrapperSignerSha256 = wrapperSignerSha256,
        crxKeyAlias = crxKeyAlias,
        crxPublicKeySha256 = crxPublicKeyDer?.let(::sha256Hex),
    )

    private fun requireReadyIdentityMatches(
        stored: ConversionIdentityLeaseEntity,
        identity: OnDevicePackageIdentity,
        publicKeySha256: String,
    ) {
        require(
            stored.state == ConversionIdentityLeaseState.READY.name &&
                stored.crxKeyAlias == identity.crxKeyAlias &&
                stored.extensionId == identity.extensionId &&
                stored.packageName == identity.packageName &&
                stored.wrapperSignerSha256 == identity.wrapperSignerSha256 &&
                stored.crxPublicKeySha256 == publicKeySha256 &&
                stored.reservedVersionCode == null,
        ) {
            "READY identity lease proof가 현재 Keystore identity와 일치하지 않습니다."
        }
    }

    private fun packagerDelete(
        lease: ConversionIdentityLeaseEntity,
        packager: OnDeviceWrapperPackager,
        resolvedLease: UnreservedIdentityLease,
    ): Boolean {
        require(
            lease.state == ConversionIdentityLeaseState.CLEANING.name &&
                lease.ownerToken == resolvedLease.ownerToken.toString() &&
                lease.crxKeyAlias == resolvedLease.identity.crxKeyAlias,
        ) {
            "CLEANING identity lease proof가 cleanup capability와 일치하지 않습니다."
        }
        return packager.deleteUnreservedIdentity(
            identitySha256 = lease.identitySha256,
            expectedAlias = requireNotNull(lease.crxKeyAlias),
            expectedExtensionId = lease.extensionId,
            expectedPublicKeySha256 = lease.crxPublicKeySha256,
        )
    }

    private fun deleteUnreservedIdentity(
        lease: ConversionIdentityLeaseEntity,
        packager: OnDeviceWrapperPackager,
    ): Boolean {
        require(lease.state == ConversionIdentityLeaseState.CLEANING.name) {
            "미예약 identity cleanup lease가 CLEANING 상태가 아닙니다."
        }
        return packager.deleteUnreservedIdentity(
            identitySha256 = lease.identitySha256,
            expectedAlias = requireNotNull(lease.crxKeyAlias),
            expectedExtensionId = lease.extensionId,
            expectedPublicKeySha256 = lease.crxPublicKeySha256,
        )
    }

    private companion object {
        val EXTENSION_ID = Regex("[a-p]{32}")
    }

    private fun requireOwnerToken(ownerToken: String): UUID {
        val parsed = runCatching { UUID.fromString(ownerToken) }
            .getOrElse { throw IllegalArgumentException("conversion job owner token이 올바르지 않습니다.") }
        require(parsed.toString() == ownerToken) {
            "conversion job owner token은 canonical UUID여야 합니다."
        }
        return parsed
    }

    internal class ConversionIdentityOwnerLifecycle internal constructor(
        private val ledger: ConversionIdentityLedger,
        private val packager: OwnerLifecyclePackager,
    ) {
        suspend fun releaseFailedAttempt(ownerToken: String) {
            when (val binding = packager) {
                is OwnerLifecyclePackager.Full ->
                    ledger.releaseFailedAttempt(ownerToken, binding.packager)
                OwnerLifecyclePackager.ReservationTests ->
                    ledger.releaseFailedAttempt(ownerToken)
            }
        }

        suspend fun isOwnerDiscardComplete(ownerToken: String): Boolean =
            ledger.isOwnerDiscardComplete(ownerToken)

        suspend fun finalizeBundleReady(
            ownerToken: String,
            expectedGeneration: Long,
            bundleSha256: String,
        ) {
            ledger.finalizePackagedForBundleReady(
                ownerToken,
                expectedGeneration,
                bundleSha256,
            )
        }
    }

    internal sealed interface OwnerLifecyclePackager {
        class Full(val packager: OnDeviceWrapperPackager) : OwnerLifecyclePackager
        data object ReservationTests : OwnerLifecyclePackager
    }
}

internal typealias ConversionIdentityOwnerLifecycle =
    ConversionIdentityLedger.ConversionIdentityOwnerLifecycle

private val SHA256 = Regex("[0-9a-f]{64}")
private val PACKAGING_STATE = Regex(
    "PACKAGING:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
)
private val PUBLISHING_STATE = Regex(
    "PUBLISHING:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}:[0-9a-f]{64}:[0-9a-f]{2,510}",
)
private val PACKAGED_STATE = Regex(
    "PACKAGED:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}:[0-9a-f]{64}:[0-9a-f]{2,510}",
)
private val CLEANING_PACKAGED_STATE = Regex(
    "CLEANING_PACKAGED:[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}:[0-9a-f]{64}:[0-9a-f]{2,510}",
)

private fun packagingState(claimUuid: UUID): String = "PACKAGING:$claimUuid"

private fun publishingState(
    packagingState: String,
    wrapperSha256: String,
    outputFileName: String,
): String {
    require(PACKAGING_STATE.matches(packagingState)) { "PACKAGING claim state가 올바르지 않습니다." }
    requireLowerHexSha256(wrapperSha256, "wrapper")
    require(
        outputFileName.isNotBlank() &&
            '/' !in outputFileName &&
            '\\' !in outputFileName &&
            outputFileName.endsWith(".apk") &&
            outputFileName.encodeToByteArray().size <= MAX_PUBLISHED_FILE_NAME_BYTES,
    ) {
        "published wrapper 파일명이 올바르지 않습니다."
    }
    return "PUBLISHING:${packagingState.substringAfter(':')}:$wrapperSha256:${encodeHex(outputFileName)}"
}

private fun String.isPackagingState(): Boolean = PACKAGING_STATE.matches(this)

private fun String.isPublishingState(): Boolean = PUBLISHING_STATE.matches(this)

private fun String.isPackagedState(): Boolean = PACKAGED_STATE.matches(this)

private fun String.isCleaningPackagedState(): Boolean = CLEANING_PACKAGED_STATE.matches(this)

private fun packagedState(publishingState: String): String {
    require(publishingState.isPublishingState()) {
        "PUBLISHING identity lease proof가 손상되었습니다."
    }
    return "PACKAGED:${publishingState.substringAfter(':')}"
}

private fun cleaningPackagedState(publishedState: String): String {
    require(publishedState.isPublishingState() || publishedState.isPackagedState()) {
        "published identity lease proof가 손상되었습니다."
    }
    return "CLEANING_PACKAGED:${publishedState.substringAfter(':')}"
}

internal fun requireValidPackagedLeaseState(state: String) {
    require(state.isPackagedState()) {
        "BUNDLE_READY finalize 대상 identity lease가 PACKAGED 상태가 아닙니다."
    }
    val parts = state.split(':')
    require(parts.size == 4) { "PACKAGED identity lease proof가 손상되었습니다." }
    UUID.fromString(parts[1])
    requireLowerHexSha256(parts[2], "PACKAGED wrapper")
    decodeHex(parts[3])
}

private fun encodeHex(value: String): String =
    value.encodeToByteArray().joinToString("") { "%02x".format(it) }

private fun decodeHex(value: String): String {
    require(value.length in 2..(MAX_PUBLISHED_FILE_NAME_BYTES * 2) && value.length % 2 == 0) {
        "PACKAGED output 파일명 proof가 손상되었습니다."
    }
    val bytes = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
    val decoded = runCatching {
        bytes.decodeToString(throwOnInvalidSequence = true)
    }.getOrElse {
        throw IllegalArgumentException("PACKAGED output 파일명 UTF-8 proof가 손상되었습니다.")
    }
    require(
        decoded.isNotBlank() &&
            '/' !in decoded &&
            '\\' !in decoded &&
            decoded.endsWith(".apk") &&
            decoded.encodeToByteArray().contentEquals(bytes),
    ) {
        "PACKAGED output 파일명 proof가 올바르지 않습니다."
    }
    return decoded
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun requireLowerHexSha256(value: String, label: String) {
    require(Regex("[0-9a-f]{64}").matches(value)) { "$label SHA-256이 올바르지 않습니다." }
}

private const val MAX_PUBLISHED_FILE_NAME_BYTES = 255
