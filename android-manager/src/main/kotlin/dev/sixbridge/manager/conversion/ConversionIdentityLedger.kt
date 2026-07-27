package dev.sixbridge.manager.conversion

import android.database.sqlite.SQLiteConstraintException
import dev.sixbridge.manager.packager.OnDevicePackageIdentity
import dev.sixbridge.manager.packager.OnDeviceWrapperPackager
import java.util.UUID
import dev.sixbridge.manager.packager.UnreservedIdentityLease
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConversionIdentityLedger(
    private val dao: ConversionIdentityDao,
    private val localIdentityFactory: () -> UUID = UUID::randomUUID,
    private val ownerTokenFactory: () -> UUID = UUID::randomUUID,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) {
    private val reserveMutex = Mutex()

    suspend fun resolveSelection(
        logicalSourceIdentity: LogicalSourceIdentity?,
        profileMajorVersion: Int,
        mode: InstallationIdentityMode,
        selection: InstallationIdentitySelection?,
    ): InstallationIdentityKey {
        require(profileMajorVersion > 0) { "프로필 주 버전이 올바르지 않습니다." }
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
                val restored = runCatching {
                    InstallationIdentityKey.restore(
                        logicalSourceIdentity = current.logicalSourceIdentity,
                        profileMajorVersion = current.profileMajorVersion,
                        mode = current.mode,
                        identitySha256 = current.identitySha256,
                    )
                }.getOrElse {
                    throw IllegalArgumentException("선택한 기존 대상 신원이 손상되었습니다.")
                }
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

    suspend fun reserve(
        request: ConversionIdentityReservation,
        unreservedLease: UnreservedIdentityLease? = null,
    ): ReservedConversionIdentity = reserveMutex.withLock {
        validateRequest(request)
        require(
            unreservedLease == null ||
                (
                    unreservedLease.identitySha256 == request.identityKey.identitySha256 &&
                        unreservedLease.identity.toBinding() == request.packageBinding
                    )
        ) {
            "READY lease capability가 예약 identity 또는 binding과 일치하지 않습니다."
        }
        val requested = request.toEntity()
        val reservationOwnerToken = ownerTokenFactory().toString()
        val stored = try {
            dao.reserve(
                requested = requested,
                installedVersionCode = request.installedVersionCode,
                readyOwnerToken = unreservedLease?.ownerToken?.toString(),
                reservationOwnerToken = reservationOwnerToken,
                createdAtEpochMillis = currentTimeMillis(),
                crxPublicKeySha256 = request.packageBinding.crxPublicKeySha256,
            )
        } catch (_: SQLiteConstraintException) {
            throw IllegalArgumentException("package identity binding이 다른 설치 신원과 충돌합니다.")
        }
        stored.toReserved(
            ownerToken = reservationOwnerToken,
            crxPublicKeySha256 = request.packageBinding.crxPublicKeySha256,
        )
    }

    suspend fun resolveUnreservedIdentity(
        identityKey: InstallationIdentityKey,
        packager: OnDeviceWrapperPackager,
    ): UnreservedIdentityLease = reserveMutex.withLock {
        require(identityKey.mode == InstallationIdentityMode.CONVERTED) {
            "converted CreateNew 신원만 미예약 lease를 만들 수 있습니다."
        }
        require(dao.get(identityKey.identitySha256) == null) {
            "이미 예약된 identity에는 미예약 lease를 만들 수 없습니다."
        }
        val plannedAlias = packager.plannedCrxAlias(identityKey)
        var stored = dao.getLease(identityKey.identitySha256)
        if (stored == null) {
            val ownerToken = ownerTokenFactory()
            try {
                dao.insertLease(
                    ConversionIdentityLeaseEntity(
                        identitySha256 = identityKey.identitySha256,
                        crxKeyAlias = plannedAlias,
                        ownerToken = ownerToken.toString(),
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
                stored.state in setOf(
                    ConversionIdentityLeaseState.PENDING.name,
                    ConversionIdentityLeaseState.READY.name,
                ),
        ) {
            "identity lease가 key 생성 또는 예약 가능한 상태가 아닙니다."
        }
        val ownerToken = runCatching { UUID.fromString(stored.ownerToken) }
            .getOrElse { throw IllegalArgumentException("identity lease owner token이 손상되었습니다.") }
        val lease = packager.createOrRecoverUnreservedIdentity(identityKey, ownerToken)
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

    suspend fun discardUnreservedIdentity(
        lease: UnreservedIdentityLease,
        packager: OnDeviceWrapperPackager,
    ): Boolean = reserveMutex.withLock {
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

    suspend fun resumeUnreservedIdentityCleanup(
        identityKey: InstallationIdentityKey,
        packager: OnDeviceWrapperPackager,
    ): Boolean = reserveMutex.withLock {
        require(dao.get(identityKey.identitySha256) == null) {
            "예약된 identity의 cleanup은 재개할 수 없습니다."
        }
        val lease = dao.getLease(identityKey.identitySha256)
            ?: throw IllegalArgumentException("재개할 CLEANING identity lease가 없습니다.")
        require(
            lease.state == ConversionIdentityLeaseState.CLEANING.name &&
                lease.crxKeyAlias == packager.plannedCrxAlias(identityKey),
        ) {
            "identity lease가 CLEANING 상태가 아니거나 alias가 identity와 일치하지 않습니다."
        }
        val deleted = packager.deleteUnreservedIdentity(
            identitySha256 = lease.identitySha256,
            expectedAlias = requireNotNull(lease.crxKeyAlias),
            expectedExtensionId = lease.extensionId,
            expectedPublicKeySha256 = lease.crxPublicKeySha256,
        )
        check(deleted) { "CLEANING CRX Android Keystore alias 삭제 재개에 실패했습니다." }
        check(
            dao.deleteLease(
                identitySha256 = lease.identitySha256,
                ownerToken = lease.ownerToken,
                state = ConversionIdentityLeaseState.CLEANING.name,
            ) == 1,
        ) {
            "CLEANING identity lease 재개 finalize에 실패했습니다."
        }
        deleted
    }

    suspend fun claimForPackaging(
        reservation: ReservedConversionIdentity,
    ): PackagingConversionIdentity = reserveMutex.withLock {
        val requested = reservation.toEntity()
        dao.claimForPackaging(
            requested = requested,
            versionCode = reservation.versionCode,
            ownerToken = reservation.ownerToken,
            crxPublicKeySha256 = reservation.crxPublicKeySha256,
        )
        PackagingConversionIdentity(reservation)
    }

    suspend fun finishPackaging(
        capability: PackagingConversionIdentity,
        succeeded: Boolean,
    ) = reserveMutex.withLock {
        val reservation = capability.reservation
        val changed = if (succeeded) {
            dao.deleteLease(
                identitySha256 = reservation.identitySha256,
                ownerToken = reservation.ownerToken,
                state = ConversionIdentityLeaseState.PACKAGING.name,
            )
        } else {
            dao.transitionLease(
                identitySha256 = reservation.identitySha256,
                ownerToken = reservation.ownerToken,
                expectedState = ConversionIdentityLeaseState.PACKAGING.name,
                nextState = ConversionIdentityLeaseState.RESERVED.name,
            )
        }
        check(changed == 1) { "package lease 완료 상태를 기록하지 못했습니다." }
    }

    suspend fun recoverInterruptedReservation(
        identityKey: InstallationIdentityKey,
        recoveryToken: String,
    ): ReservedConversionIdentity = reserveMutex.withLock {
        runCatching { UUID.fromString(recoveryToken) }
            .getOrElse { throw IllegalArgumentException("package recovery token이 올바르지 않습니다.") }
        val current = dao.get(identityKey.identitySha256)
            ?: throw IllegalArgumentException("복구할 예약 identity를 찾을 수 없습니다.")
        val restored = InstallationIdentityKey.restore(
            logicalSourceIdentity = current.logicalSourceIdentity,
            profileMajorVersion = current.profileMajorVersion,
            mode = current.mode,
            identitySha256 = current.identitySha256,
        )
        require(restored == identityKey) { "복구할 예약 identity tuple이 일치하지 않습니다." }
        var lease = dao.getLease(identityKey.identitySha256)
            ?: throw IllegalArgumentException("복구할 package lease를 찾을 수 없습니다.")
        require(
            lease.state in setOf(
                ConversionIdentityLeaseState.RESERVED.name,
                ConversionIdentityLeaseState.PACKAGING.name,
            ) &&
                lease.ownerToken == recoveryToken &&
                lease.reservedVersionCode == current.lastReservedVersionCode,
        ) {
            "복구할 package lease 상태 또는 version이 올바르지 않습니다."
        }
        if (lease.state == ConversionIdentityLeaseState.PACKAGING.name) {
            check(
                dao.transitionLease(
                    identitySha256 = identityKey.identitySha256,
                    ownerToken = lease.ownerToken,
                    expectedState = ConversionIdentityLeaseState.PACKAGING.name,
                    nextState = ConversionIdentityLeaseState.RESERVED.name,
                ) == 1,
            ) {
                "중단된 PACKAGING lease를 RESERVED로 복구하지 못했습니다."
            }
            lease = requireNotNull(dao.getLease(identityKey.identitySha256))
        }
        current.toReserved(
            ownerToken = lease.ownerToken,
            crxPublicKeySha256 = lease.crxPublicKeySha256,
        )
    }

    suspend fun listExistingTargets(
        profileMajorVersion: Int,
    ): List<ExistingConversionTarget> {
        require(profileMajorVersion > 0) { "프로필 주 버전이 올바르지 않습니다." }
        return dao.listByModeAndProfile(
            mode = InstallationIdentityMode.CONVERTED.name,
            profileMajorVersion = profileMajorVersion,
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
            InstallationIdentityMode.PASSTHROUGH -> require(binding.crxKeyAlias == null) {
                "passthrough 신원에는 CRX key alias를 저장할 수 없습니다."
            }
            InstallationIdentityMode.CONVERTED -> require(
                !binding.crxKeyAlias.isNullOrBlank() &&
                    binding.crxPublicKeySha256?.matches(SHA256) == true,
            ) {
                "converted 신원에는 CRX key alias와 공개 키 proof가 필요합니다."
            }
        }
        if (request.identityKey.mode == InstallationIdentityMode.PASSTHROUGH) {
            require(binding.crxPublicKeySha256 == null) {
                "passthrough 신원에는 CRX 공개 키 proof를 저장할 수 없습니다."
            }
        }
    }

    private fun ConversionIdentityReservation.toEntity(): ConversionIdentityEntity {
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
        )
    }

    private fun ConversionIdentityEntity.toReserved(
        ownerToken: String,
        crxPublicKeySha256: String?,
    ): ReservedConversionIdentity {
        val key = InstallationIdentityKey.restore(
            logicalSourceIdentity,
            profileMajorVersion,
            mode,
            identitySha256,
        )
        return ReservedConversionIdentity(
            identityKey = key,
            extensionId = extensionId,
            packageName = packageName,
            wrapperSignerSha256 = wrapperSignerSha256,
            crxKeyAlias = crxKeyAlias,
            crxPublicKeySha256 = crxPublicKeySha256,
            versionCode = lastReservedVersionCode,
            ownerToken = ownerToken,
        )
    }

    private fun ReservedConversionIdentity.toEntity() = ConversionIdentityEntity(
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

    private companion object {
        val EXTENSION_ID = Regex("[a-p]{32}")
    }
}

private val SHA256 = Regex("[0-9a-f]{64}")

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

private fun requireLowerHexSha256(value: String, label: String) {
    require(Regex("[0-9a-f]{64}").matches(value)) { "$label SHA-256이 올바르지 않습니다." }
}
