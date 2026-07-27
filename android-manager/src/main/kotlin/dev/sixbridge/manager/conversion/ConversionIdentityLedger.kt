package dev.sixbridge.manager.conversion

import android.database.sqlite.SQLiteConstraintException
import java.util.UUID
import dev.sixbridge.manager.packager.UnreservedIdentityLease
import dev.sixbridge.manager.packager.UnreservedIdentityCleaner
import dev.sixbridge.manager.packager.OnDeviceWrapperPackager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConversionIdentityLedger(
    private val dao: ConversionIdentityDao,
    private val localIdentityFactory: () -> UUID = UUID::randomUUID,
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
    ): ReservedConversionIdentity = reserveMutex.withLock {
        validateRequest(request)
        val requested = request.toEntity()
        val stored = try {
            dao.reserve(requested, request.installedVersionCode)
        } catch (_: SQLiteConstraintException) {
            throw IllegalArgumentException("package identity binding이 다른 설치 신원과 충돌합니다.")
        }
        stored.toReserved()
    }

    internal suspend fun discardUnreservedIdentity(
        lease: UnreservedIdentityLease,
        packager: OnDeviceWrapperPackager,
    ): Boolean = discardUnreservedIdentity(lease, packager.unreservedIdentityCleaner)

    internal suspend fun discardUnreservedIdentity(
        lease: UnreservedIdentityLease,
        cleaner: UnreservedIdentityCleaner,
    ): Boolean = reserveMutex.withLock {
        require(dao.get(lease.identitySha256) == null) {
            "원장에 예약된 CRX 신원은 미예약 cleanup으로 삭제할 수 없습니다."
        }
        cleaner.discardUnreservedIdentity(lease)
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
            InstallationIdentityMode.CONVERTED -> require(!binding.crxKeyAlias.isNullOrBlank()) {
                "converted 신원에는 CRX key alias가 필요합니다."
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

    private fun ConversionIdentityEntity.toReserved(): ReservedConversionIdentity {
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
            versionCode = lastReservedVersionCode,
        )
    }

    private companion object {
        val EXTENSION_ID = Regex("[a-p]{32}")
    }
}

private fun requireLowerHexSha256(value: String, label: String) {
    require(Regex("[0-9a-f]{64}").matches(value)) { "$label SHA-256이 올바르지 않습니다." }
}
