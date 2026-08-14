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

data class LegacyReconciliationResult(
    val promotedIdentitySha256: Set<String>,
    val retainedIdentitySha256: Set<String>,
    val blockedReasons: Map<String, String>,
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
    private val managementMutex = Mutex()

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

    suspend fun reconcileLegacyIdentities(): LegacyReconciliationResult =
        managementMutex.withLock {
            val legacy = dao.listByLifecycle(
                ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name,
            )
            if (legacy.isEmpty()) {
                return@withLock LegacyReconciliationResult(emptySet(), emptySet(), emptyMap())
            }
            val promoted = linkedSetOf<String>()
            val blockedReasons = linkedMapOf<String, String>()
            val installedSnapshot = try {
                InstalledSnapshot.Available(installedWrappers.findAll())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                InstalledSnapshot.Unreadable("설치 wrapper proof를 읽을 수 없습니다.")
            }

            for (identity in legacy) {
                val candidate = installedSnapshot.proofFor(identity)
                if (candidate.proof == null) {
                    blockedReasons[identity.identitySha256] = candidate.reason
                    continue
                }
                val attempt = promoteLegacy(
                    expected = identity,
                    proof = candidate.proof,
                    observedCurrentBundle = null,
                )
                if (attempt.promoted) {
                    promoted += identity.identitySha256
                    blockedReasons -= identity.identitySha256
                } else {
                    blockedReasons[identity.identitySha256] = attempt.reason
                }
            }

            val bundleCandidates = legacy.filterNot { it.identitySha256 in promoted }
            if (bundleCandidates.isNotEmpty()) {
                val currentBundle = try {
                    currentBundleReadyProofReader.read()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    CurrentBundleReadyProof.Unreadable(
                        "현재 BUNDLE_READY proof를 읽을 수 없습니다.",
                    )
                }
                for (identity in bundleCandidates) {
                    when (currentBundle) {
                        CurrentBundleReadyProof.Absent ->
                            blockedReasons.putIfAbsent(
                                identity.identitySha256,
                                "강한 identity proof가 없습니다.",
                            )
                        is CurrentBundleReadyProof.Unreadable ->
                            blockedReasons[identity.identitySha256] = currentBundle.reason
                        is CurrentBundleReadyProof.Verified -> {
                            if (!currentBundle.proof.matches(identity)) {
                                blockedReasons.putIfAbsent(
                                    identity.identitySha256,
                                    "현재 BUNDLE_READY proof가 identity binding과 일치하지 않습니다.",
                                )
                                continue
                            }
                            val attempt = promoteLegacy(
                                expected = identity,
                                proof = currentBundle.proof,
                                observedCurrentBundle = currentBundle,
                            )
                            if (attempt.promoted) {
                                promoted += identity.identitySha256
                                blockedReasons -= identity.identitySha256
                            } else {
                                blockedReasons[identity.identitySha256] = attempt.reason
                            }
                        }
                    }
                }
            }
            val retained = legacy
                .asSequence()
                .map(ConversionIdentityEntity::identitySha256)
                .filterNot(promoted::contains)
                .toCollection(linkedSetOf())
            LegacyReconciliationResult(
                promotedIdentitySha256 = promoted,
                retainedIdentitySha256 = retained,
                blockedReasons = blockedReasons.filterKeys(retained::contains),
            )
        }

    suspend fun promoteLegacyFromVerifiedBundle(
        proof: VerifiedIdentityProof,
    ): Boolean = managementMutex.withLock {
        if (proof.validationIssue() != null) return@withLock false
        val current = dao.findByPackageName(proof.packageName)
            ?: return@withLock false
        if (current.lifecycleState != ConversionIdentityLifecycleState.LEGACY_UNKNOWN.name) {
            return@withLock false
        }
        promoteLegacy(
            expected = current,
            proof = proof,
            observedCurrentBundle = null,
        ).promoted
    }

    suspend fun discardIdentityOwner(
        ownerToken: String,
    ): ConversionIdentityCleanupResult = managementMutex.withLock {
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
    ): ConversionIdentityCleanupResult = managementMutex.withLock {
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

    suspend fun recoverIdentityCleanup(): Boolean = managementMutex.withLock {
        dao.listByLifecycle(ConversionIdentityLifecycleState.CLEANING.name).all { current ->
            cleanupClaimed(current) == ConversionIdentityCleanupResult.Removed
        }
    }

    private suspend fun promoteLegacy(
        expected: ConversionIdentityEntity,
        proof: VerifiedIdentityProof,
        observedCurrentBundle: CurrentBundleReadyProof.Verified?,
    ): LegacyPromotionAttempt {
        proof.validationIssue()?.let { return LegacyPromotionAttempt(false, it) }
        if (!proof.matches(expected)) {
            return LegacyPromotionAttempt(false, "identity binding proof가 일치하지 않습니다.")
        }
        return when (enumValueOrNull<InstallationIdentityMode>(expected.mode)) {
            InstallationIdentityMode.CONVERTED ->
                keyInspector.withIdentityLock(expected.identitySha256) {
                    val verifiedKey = when (
                        val inspected = keyInspector.inspect(expected.identitySha256)
                    ) {
                        ConversionIdentityKeyProof.Absent ->
                            return@withIdentityLock LegacyPromotionAttempt(
                                false,
                                "converted identity alias가 없습니다.",
                            )
                        is ConversionIdentityKeyProof.Unreadable ->
                            return@withIdentityLock LegacyPromotionAttempt(false, inspected.reason)
                        is ConversionIdentityKeyProof.Verified -> inspected
                    }
                    if (expected.crxKeyAlias.isNullOrBlank() ||
                        verifiedKey.alias != expected.crxKeyAlias ||
                        verifiedKey.extensionId != expected.extensionId ||
                        !MANAGEMENT_SHA256.matches(verifiedKey.publicKeySha256) ||
                        expected.crxPublicKeySha256?.let {
                            it != verifiedKey.publicKeySha256
                        } == true
                    ) {
                        return@withIdentityLock LegacyPromotionAttempt(
                            false,
                            "converted identity key binding이 일치하지 않습니다.",
                        )
                    }
                    promoteLegacyCas(
                        expected = expected,
                        proof = proof,
                        verifiedPublicKeySha256 = verifiedKey.publicKeySha256,
                        observedCurrentBundle = observedCurrentBundle,
                    )
                }
            InstallationIdentityMode.PASSTHROUGH,
            InstallationIdentityMode.PREPARED,
            -> if (expected.crxKeyAlias != null || expected.crxPublicKeySha256 != null) {
                LegacyPromotionAttempt(
                    false,
                    "외부 서명 identity key proof가 손상되었습니다.",
                )
            } else {
                promoteLegacyCas(
                    expected = expected,
                    proof = proof,
                    verifiedPublicKeySha256 = null,
                    observedCurrentBundle = observedCurrentBundle,
                )
            }
            null -> LegacyPromotionAttempt(false, "identity mode가 손상되었습니다.")
        }
    }

    private suspend fun promoteLegacyCas(
        expected: ConversionIdentityEntity,
        proof: VerifiedIdentityProof,
        verifiedPublicKeySha256: String?,
        observedCurrentBundle: CurrentBundleReadyProof.Verified?,
    ): LegacyPromotionAttempt {
        val promoted = dao.promoteLegacyIdentity(
            expected = expected,
            proof = proof,
            verifiedPublicKeySha256 = verifiedPublicKeySha256,
            lifecycleUpdatedAtEpochMillis = now(),
            observedCurrentBundle = observedCurrentBundle,
        )
        return LegacyPromotionAttempt(
            promoted = promoted,
            reason = if (promoted) "" else "LEGACY_UNKNOWN ACTIVE CAS가 실패했습니다.",
        )
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

private data class LegacyPromotionAttempt(
    val promoted: Boolean,
    val reason: String,
)

private data class InstalledProofCandidate(
    val proof: VerifiedIdentityProof?,
    val reason: String,
)

private sealed interface InstalledSnapshot {
    data class Available(
        val byPackage: Map<String, List<InstalledWrapper>>,
    ) : InstalledSnapshot {
        constructor(values: List<InstalledWrapper>) : this(values.groupBy(InstalledWrapper::packageName))
    }

    data class Unreadable(val reason: String) : InstalledSnapshot
}

private fun InstalledSnapshot.proofFor(
    identity: ConversionIdentityEntity,
): InstalledProofCandidate = when (this) {
    is InstalledSnapshot.Unreadable -> InstalledProofCandidate(null, reason)
    is InstalledSnapshot.Available -> {
        val wrappers = byPackage[identity.packageName].orEmpty()
        if (wrappers.size > 1) {
            InstalledProofCandidate(null, "설치 wrapper proof가 모호합니다.")
        } else if (wrappers.isEmpty()) {
            InstalledProofCandidate(null, "설치 wrapper proof가 없습니다.")
        } else {
            val wrapper = wrappers.single()
            if (wrapper.packageName != identity.packageName ||
                wrapper.extensionId != identity.extensionId ||
                wrapper.signerSha256 != identity.wrapperSignerSha256
            ) {
                InstalledProofCandidate(null, "설치 wrapper binding proof가 일치하지 않습니다.")
            } else if (wrapper.versionCode !in 1..Int.MAX_VALUE.toLong()) {
                InstalledProofCandidate(null, "설치 wrapper versionCode proof가 올바르지 않습니다.")
            } else {
                val proof = VerifiedIdentityProof(
                    packageName = wrapper.packageName,
                    extensionId = wrapper.extensionId,
                    wrapperSignerSha256 = wrapper.signerSha256,
                    versionCode = wrapper.versionCode.toInt(),
                )
                proof.validationIssue()?.let { issue ->
                    InstalledProofCandidate(null, issue)
                } ?: InstalledProofCandidate(proof, "")
            }
        }
    }
}

private fun VerifiedIdentityProof.matches(
    identity: ConversionIdentityEntity,
): Boolean = packageName == identity.packageName &&
    extensionId == identity.extensionId &&
    wrapperSignerSha256 == identity.wrapperSignerSha256

private fun VerifiedIdentityProof.validationIssue(): String? = when {
    !MANAGEMENT_EXTENSION_ID.matches(extensionId) ->
        "identity proof extension ID가 올바르지 않습니다."
    packageName != "dev.sixbridge.ext.$extensionId" ->
        "identity proof package가 extension ID와 일치하지 않습니다."
    !MANAGEMENT_SHA256.matches(wrapperSignerSha256) ->
        "identity proof wrapper signer가 올바르지 않습니다."
    versionCode <= 0 -> "identity proof versionCode가 올바르지 않습니다."
    else -> null
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

private val MANAGEMENT_EXTENSION_ID = Regex("[a-p]{32}")
private val MANAGEMENT_SHA256 = Regex("[0-9a-f]{64}")
