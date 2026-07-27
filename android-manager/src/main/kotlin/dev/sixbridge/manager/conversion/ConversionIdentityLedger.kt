package dev.sixbridge.manager.conversion

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConversionIdentityLedger(
    private val dao: ConversionIdentityDao,
    private val localIdentityFactory: () -> UUID = UUID::randomUUID,
) {
    private val reserveMutex = Mutex()

    suspend fun reserve(
        request: ConversionIdentityRequest,
    ): ReservedConversionIdentity = reserveMutex.withLock {
        validateRequest(request)
        val requested = when (val selection = request.selection) {
            null -> stableEntity(request)
            InstallationIdentitySelection.CreateNew -> newLocalEntity(request)
            is InstallationIdentitySelection.UseExisting -> existingEntity(request, selection)
        }
        dao.reserve(requested, request.installedVersionCode).toReserved()
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

    private fun stableEntity(request: ConversionIdentityRequest): ConversionIdentityEntity {
        val logicalSourceIdentity = requireNotNull(request.logicalSourceIdentity) {
            "안정적 신원이 없는 ZIP은 CreateNew 또는 UseExisting 선택이 필요합니다."
        }
        return request.toEntity(
            InstallationIdentityKey.create(
                logicalSourceIdentity = logicalSourceIdentity,
                profileMajorVersion = request.profileMajorVersion,
                mode = request.mode,
            ),
        )
    }

    private fun newLocalEntity(request: ConversionIdentityRequest): ConversionIdentityEntity {
        require(request.logicalSourceIdentity == null) {
            "안정적 신원이 있는 입력에는 CreateNew를 사용할 수 없습니다."
        }
        require(request.mode == InstallationIdentityMode.CONVERTED) {
            "불안정 ZIP은 converted 신원으로만 만들 수 있습니다."
        }
        val logicalSourceIdentity = "local-${localIdentityFactory()}"
        return request.toEntity(
            InstallationIdentityKey.create(
                logicalSourceIdentity = logicalSourceIdentity,
                profileMajorVersion = request.profileMajorVersion,
                mode = request.mode,
            ),
        )
    }

    private suspend fun existingEntity(
        request: ConversionIdentityRequest,
        selection: InstallationIdentitySelection.UseExisting,
    ): ConversionIdentityEntity {
        require(request.logicalSourceIdentity == null) {
            "안정적 신원이 있는 입력에는 UseExisting을 사용할 수 없습니다."
        }
        requireLowerHexSha256(selection.identitySha256, "기존 대상 identity")
        val current = dao.get(selection.identitySha256)
            ?: throw IllegalArgumentException("선택한 기존 대상을 찾을 수 없습니다.")
        require(current.mode == InstallationIdentityMode.CONVERTED.name) {
            "passthrough 신원은 불안정 ZIP 대상이 될 수 없습니다."
        }
        require(current.profileMajorVersion == request.profileMajorVersion) {
            "다른 프로필 주 버전의 대상은 선택할 수 없습니다."
        }
        val requested = current.copy(
            displayName = request.displayName,
            extensionId = request.extensionId,
            packageName = request.packageName,
            wrapperSignerSha256 = request.wrapperSignerSha256,
            crxKeyAlias = request.crxKeyAlias,
            lastReservedVersionCode = 0,
        )
        requireIdentityFieldsMatchForSelection(current, requested)
        return requested
    }

    private fun validateRequest(request: ConversionIdentityRequest) {
        require(request.profileMajorVersion > 0) { "프로필 주 버전이 올바르지 않습니다." }
        require(request.displayName.isNotBlank()) { "표시 이름이 비어 있습니다." }
        require(EXTENSION_ID.matches(request.extensionId)) { "extension ID가 올바르지 않습니다." }
        require(request.packageName == "dev.sixbridge.ext.${request.extensionId}") {
            "package가 extension ID와 일치하지 않습니다."
        }
        requireLowerHexSha256(request.wrapperSignerSha256, "wrapper signer")
        require(request.installedVersionCode == null || request.installedVersionCode >= 0) {
            "설치본 versionCode가 올바르지 않습니다."
        }
        when (request.mode) {
            InstallationIdentityMode.PASSTHROUGH -> require(request.crxKeyAlias == null) {
                "passthrough 신원에는 CRX key alias를 저장할 수 없습니다."
            }
            InstallationIdentityMode.CONVERTED -> require(!request.crxKeyAlias.isNullOrBlank()) {
                "converted 신원에는 CRX key alias가 필요합니다."
            }
        }
        if (request.logicalSourceIdentity != null) {
            require(request.logicalSourceIdentity.isNotBlank()) { "논리 원본 신원이 비어 있습니다." }
            require(request.selection == null) {
                "안정적 신원과 수동 대상 선택을 함께 사용할 수 없습니다."
            }
        } else {
            require(request.selection != null) {
                "안정적 신원이 없는 ZIP은 CreateNew 또는 UseExisting 선택이 필요합니다."
            }
        }
    }

    private fun ConversionIdentityRequest.toEntity(
        key: InstallationIdentityKey,
    ) = ConversionIdentityEntity(
        identitySha256 = key.identitySha256,
        logicalSourceIdentity = key.logicalSourceIdentity,
        profileMajorVersion = key.profileMajorVersion,
        mode = key.mode.name,
        displayName = displayName,
        extensionId = extensionId,
        packageName = packageName,
        wrapperSignerSha256 = wrapperSignerSha256,
        crxKeyAlias = crxKeyAlias,
        lastReservedVersionCode = 0,
    )

    private fun ConversionIdentityEntity.toReserved() = ReservedConversionIdentity(
        identitySha256 = identitySha256,
        extensionId = extensionId,
        packageName = packageName,
        wrapperSignerSha256 = wrapperSignerSha256,
        versionCode = lastReservedVersionCode,
    )

    private companion object {
        val EXTENSION_ID = Regex("[a-p]{32}")
    }
}

private fun requireIdentityFieldsMatchForSelection(
    current: ConversionIdentityEntity,
    requested: ConversionIdentityEntity,
) {
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

private fun requireLowerHexSha256(value: String, label: String) {
    require(Regex("[0-9a-f]{64}").matches(value)) { "$label SHA-256이 올바르지 않습니다." }
}
