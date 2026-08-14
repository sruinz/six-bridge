package dev.sixbridge.manager.conversion

import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LegacyConversionIdentity(
    val identitySha256: String,
    val displayName: String,
    val mode: InstallationIdentityMode,
    val packageName: String,
    val extensionId: String,
    val wrapperSignerSha256: String,
)

sealed interface ConversionIdentityCleanupResult {
    data object Removed : ConversionIdentityCleanupResult
    data object NothingOwned : ConversionIdentityCleanupResult
    data class Blocked(val reason: String) : ConversionIdentityCleanupResult
}

internal sealed interface ConversionIdentityKeyProof {
    data object Absent : ConversionIdentityKeyProof

    data class Verified(
        val alias: String,
        val extensionId: String,
        val publicKeySha256: String,
    ) : ConversionIdentityKeyProof

    data class Unreadable(
        val reason: String,
    ) : ConversionIdentityKeyProof
}

internal interface ConversionIdentityKeyInspector {
    suspend fun <T> withIdentityLock(
        identitySha256: String,
        block: suspend () -> T,
    ): T

    fun inspect(identitySha256: String): ConversionIdentityKeyProof
}

internal data class ConversionIdentityManagementHooks(
    val afterCleanupClaim: () -> Unit = {},
    val afterFinalProof: () -> Unit = {},
    val beforeRowRetire: () -> Unit = {},
)

class ConversionIdentityManagement internal constructor(
    private val dao: ConversionIdentityDao,
    private val installedWrappers: InstalledWrapperRepository,
    private val currentBundleReadyProofReader: CurrentBundleReadyProofReader,
    private val keyInspector: ConversionIdentityKeyInspector,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
    private val hooks: ConversionIdentityManagementHooks = ConversionIdentityManagementHooks(),
) {
    private val cleanupMutex = Mutex()

    suspend fun listLegacyUnknown(): List<LegacyConversionIdentity> =
        dao.listByLifecycle(ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name).map { entity ->
            LegacyConversionIdentity(
                identitySha256 = entity.identitySha256,
                displayName = entity.displayName,
                mode = requireNotNull(enumValueOrNull<InstallationIdentityMode>(entity.mode)) {
                    "이전 버전 설치 신원 mode가 손상되었습니다."
                },
                packageName = entity.packageName,
                extensionId = entity.extensionId,
                wrapperSignerSha256 = entity.wrapperSignerSha256,
            )
        }

    suspend fun discardIdentityOwner(
        ownerToken: String,
    ): ConversionIdentityCleanupResult = cleanupMutex.withLock {
        requireCanonicalOwnerToken(ownerToken)
        val current = dao.findByProvisionOwnerToken(ownerToken)
            ?: return@withLock ConversionIdentityCleanupResult.NothingOwned
        if (current.provisionOwnerToken != ownerToken ||
            current.lifecycleState !in setOf(
                ConversionIdentityLifecycleState.PROVISIONAL.name,
                ConversionIdentityLifecycleState.CLEANING.name,
            )
        ) {
            return@withLock blocked("owner lifecycle이 cleanup 대상이 아닙니다.")
        }
        cleanup(current, ConversionIdentityLifecycleState.PROVISIONAL)
    }

    suspend fun discardLegacyUnknown(
        identitySha256: String,
    ): ConversionIdentityCleanupResult = cleanupMutex.withLock {
        requireSha256(identitySha256, "이전 버전 identity")
        val current = dao.get(identitySha256)
            ?: return@withLock ConversionIdentityCleanupResult.NothingOwned
        if (current.lifecycleState == ConversionIdentityLifecycleState.RETIRED.name &&
            current.provisionOwnerToken == null
        ) {
            return@withLock ConversionIdentityCleanupResult.NothingOwned
        }
        if (current.provisionOwnerToken != null ||
            current.lifecycleState !in setOf(
                ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name,
                ConversionIdentityLifecycleState.CLEANING.name,
            )
        ) {
            return@withLock blocked("LEGACY_UNKNOWN lifecycle이 아닙니다.")
        }
        cleanup(current, ConversionIdentityLifecycleState.LEGACY_UNKNOWN)
    }

    suspend fun recoverIdentityCleanup(): Boolean = cleanupMutex.withLock {
        dao.listByLifecycle(ConversionIdentityLifecycleState.CLEANING.name).all { current ->
            cleanupClaimed(current) == ConversionIdentityCleanupResult.Removed
        }
    }

    private suspend fun cleanup(
        current: ConversionIdentityEntity,
        expectedLifecycle: ConversionIdentityLifecycleState,
    ): ConversionIdentityCleanupResult {
        if (current.lifecycleState == ConversionIdentityLifecycleState.CLEANING.name) {
            return cleanupClaimed(current)
        }
        if (current.lifecycleState != expectedLifecycle.name) {
            return blocked("identity lifecycle이 cleanup 명령과 일치하지 않습니다.")
        }
        return when (enumValueOrNull<InstallationIdentityMode>(current.mode)) {
            InstallationIdentityMode.CONVERTED ->
                keyInspector.withIdentityLock(current.identitySha256) {
                    claimAndCleanupConverted(current, expectedLifecycle)
                }
            InstallationIdentityMode.PASSTHROUGH,
            InstallationIdentityMode.PREPARED,
            -> claimAndCleanupExternal(current, expectedLifecycle)
            null -> blocked("identity mode가 손상되었습니다.")
        }
    }

    private suspend fun claimAndCleanupConverted(
        current: ConversionIdentityEntity,
        expectedLifecycle: ConversionIdentityLifecycleState,
    ): ConversionIdentityCleanupResult {
        val alias = current.crxKeyAlias
            ?.takeIf(String::isNotBlank)
            ?: return blocked("converted identity alias가 없습니다.")
        val key = when (val inspected = keyInspector.inspect(current.identitySha256)) {
            ConversionIdentityKeyProof.Absent -> return blocked("converted identity alias가 없습니다.")
            is ConversionIdentityKeyProof.Unreadable -> return blocked(inspected.reason)
            is ConversionIdentityKeyProof.Verified -> inspected
        }
        if (key.alias != alias || key.extensionId != current.extensionId) {
            return blocked("converted identity alias binding이 일치하지 않습니다.")
        }
        val storedPublicKey = current.crxPublicKeySha256
        if (storedPublicKey != null && storedPublicKey != key.publicKeySha256) {
            return blocked("converted identity 공개 키 proof가 일치하지 않습니다.")
        }
        if (storedPublicKey == null && expectedLifecycle != ConversionIdentityLifecycleState.LEGACY_UNKNOWN) {
            return blocked("converted identity 공개 키 proof가 없습니다.")
        }
        val firstBoundary = readExternalBoundary(current)
        if (firstBoundary is CleanupBoundary.Blocked) return blocked(firstBoundary.reason)
        val allowed = firstBoundary as CleanupBoundary.Allowed
        val claimed = dao.claimIdentityCleanup(
            expected = current,
            cleanupCrxPublicKeySha256 = key.publicKeySha256,
            lifecycleUpdatedAtEpochMillis = now(),
            observedBundleProof = allowed.bundleProof,
        ) ?: return blocked("identity CLEANING CAS가 실패했습니다.")
        hooks.afterCleanupClaim()
        return cleanupClaimedLocked(claimed)
    }

    private suspend fun claimAndCleanupExternal(
        current: ConversionIdentityEntity,
        expectedLifecycle: ConversionIdentityLifecycleState,
    ): ConversionIdentityCleanupResult {
        if (current.crxKeyAlias != null || current.crxPublicKeySha256 != null) {
            return blocked("외부 서명 identity에 local key proof가 있습니다.")
        }
        val firstBoundary = readExternalBoundary(current)
        if (firstBoundary is CleanupBoundary.Blocked) return blocked(firstBoundary.reason)
        val allowed = firstBoundary as CleanupBoundary.Allowed
        val claimed = dao.claimIdentityCleanup(
            expected = current,
            cleanupCrxPublicKeySha256 = null,
            lifecycleUpdatedAtEpochMillis = now(),
            observedBundleProof = allowed.bundleProof,
        ) ?: return blocked("identity CLEANING CAS가 실패했습니다.")
        hooks.afterCleanupClaim()
        return cleanupClaimedLocked(claimed)
    }

    private suspend fun cleanupClaimed(
        current: ConversionIdentityEntity,
    ): ConversionIdentityCleanupResult =
        when (enumValueOrNull<InstallationIdentityMode>(current.mode)) {
            InstallationIdentityMode.CONVERTED ->
                keyInspector.withIdentityLock(current.identitySha256) {
                    cleanupClaimedLocked(current)
                }
            InstallationIdentityMode.PASSTHROUGH,
            InstallationIdentityMode.PREPARED,
            -> cleanupClaimedLocked(current)
            null -> blocked("CLEANING identity mode가 손상되었습니다.")
        }

    private suspend fun cleanupClaimedLocked(
        claimedSnapshot: ConversionIdentityEntity,
    ): ConversionIdentityCleanupResult {
        val current = dao.get(claimedSnapshot.identitySha256)
            ?: return ConversionIdentityCleanupResult.Removed
        if (current.lifecycleState != ConversionIdentityLifecycleState.CLEANING.name ||
            current != claimedSnapshot
        ) {
            return blocked("CLEANING identity가 변경되었습니다.")
        }
        val mode = enumValueOrNull<InstallationIdentityMode>(current.mode)
            ?: return blocked("CLEANING identity mode가 손상되었습니다.")
        when (mode) {
            InstallationIdentityMode.CONVERTED -> {
                val alias = current.crxKeyAlias
                    ?.takeIf(String::isNotBlank)
                    ?: return blocked("CLEANING converted identity alias가 없습니다.")
                val publicKeySha256 = current.crxPublicKeySha256
                    ?.takeIf(CLEANUP_SHA256::matches)
                    ?: return blocked("CLEANING converted identity 공개 키 proof가 없습니다.")
                when (val inspected = keyInspector.inspect(current.identitySha256)) {
                    ConversionIdentityKeyProof.Absent -> Unit
                    is ConversionIdentityKeyProof.Unreadable -> return blocked(inspected.reason)
                    is ConversionIdentityKeyProof.Verified -> {
                        if (inspected.alias != alias ||
                            inspected.extensionId != current.extensionId ||
                            inspected.publicKeySha256 != publicKeySha256
                        ) {
                            return blocked("CLEANING converted identity key binding이 일치하지 않습니다.")
                        }
                    }
                }
            }
            InstallationIdentityMode.PASSTHROUGH,
            InstallationIdentityMode.PREPARED,
            -> {
                if (current.crxKeyAlias != null || current.crxPublicKeySha256 != null) {
                    return blocked("CLEANING 외부 서명 identity key proof가 손상되었습니다.")
                }
            }
        }
        val boundary = readExternalBoundary(current)
        if (boundary is CleanupBoundary.Blocked) return blocked(boundary.reason)
        hooks.afterFinalProof()
        hooks.beforeRowRetire()
        val retired = dao.retireClaimedIdentity(
            expected = current,
            observedBundleProof = (boundary as CleanupBoundary.Allowed).bundleProof,
            lifecycleUpdatedAtEpochMillis = now(),
        )
        return if (retired) {
            ConversionIdentityCleanupResult.Removed
        } else {
            blocked("CLEANING identity를 안전하게 retire하지 못했습니다.")
        }
    }

    private suspend fun readExternalBoundary(
        identity: ConversionIdentityEntity,
    ): CleanupBoundary {
        if (dao.getLease(identity.identitySha256) != null) {
            return CleanupBoundary.Blocked("identity에 active lease가 있습니다.")
        }
        val installed = try {
            installedWrappers.find(identity.packageName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return CleanupBoundary.Blocked("설치 wrapper proof를 읽을 수 없습니다.")
        }
        if (installed.matches(identity)) {
            return CleanupBoundary.Blocked("설치된 exact wrapper가 있습니다.")
        }
        val bundleProof = try {
            currentBundleReadyProofReader.read()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return CleanupBoundary.Blocked("현재 BUNDLE_READY proof를 읽을 수 없습니다.")
        }
        when (bundleProof) {
            is CurrentBundleReadyProof.Unreadable ->
                return CleanupBoundary.Blocked(bundleProof.reason)
            is CurrentBundleReadyProof.Verified -> if (bundleProof.matches(identity)) {
                return CleanupBoundary.Blocked("현재 exact BUNDLE_READY proof가 있습니다.")
            }
            CurrentBundleReadyProof.Absent -> Unit
        }
        return CleanupBoundary.Allowed(bundleProof)
    }

    private fun now(): Long = currentTimeMillis().also {
        require(it >= 0) { "identity lifecycle 시간이 올바르지 않습니다." }
    }

    private sealed interface CleanupBoundary {
        data class Allowed(
            val bundleProof: CurrentBundleReadyProof,
        ) : CleanupBoundary

        data class Blocked(
            val reason: String,
        ) : CleanupBoundary
    }

    private companion object {
        val CLEANUP_SHA256 = Regex("[0-9a-f]{64}")
    }
}

private fun InstalledWrapper?.matches(identity: ConversionIdentityEntity): Boolean =
    this != null &&
        packageName == identity.packageName &&
        extensionId == identity.extensionId &&
        signerSha256 == identity.wrapperSignerSha256

private fun CurrentBundleReadyProof.Verified.matches(
    identity: ConversionIdentityEntity,
): Boolean = proof.packageName == identity.packageName &&
    proof.extensionId == identity.extensionId &&
    proof.wrapperSignerSha256 == identity.wrapperSignerSha256 &&
    proof.versionCode == identity.lastReservedVersionCode

private fun blocked(reason: String) = ConversionIdentityCleanupResult.Blocked(reason)

private fun requireCanonicalOwnerToken(ownerToken: String) {
    require(runCatching { UUID.fromString(ownerToken).toString() == ownerToken }.getOrDefault(false)) {
        "conversion job owner token이 올바르지 않습니다."
    }
}

private fun requireSha256(value: String, label: String) {
    require(Regex("[0-9a-f]{64}").matches(value)) { "$label SHA-256이 올바르지 않습니다." }
}

private inline fun <reified T : Enum<T>> enumValueOrNull(value: String): T? =
    enumValues<T>().firstOrNull { it.name == value }
