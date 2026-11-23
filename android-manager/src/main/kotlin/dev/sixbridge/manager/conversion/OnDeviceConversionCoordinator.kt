package dev.sixbridge.manager.conversion

import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisSafetyPolicy
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.ConversionAuthorization
import dev.sixbridge.core.conversion.ForceCompatibilityPolicy
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class OnDeviceConversionCoordinator(
    private val input: ConversionInputPort,
    private val work: ConversionWorkPort,
    private val profiles: ConversionProfilePort,
    private val runtime: ConversionRuntimePort,
    private val identities: ConversionIdentityPort,
    private val installed: ConversionInstalledPort,
    private val packages: ConversionPackagePort,
    private val bundles: ConversionBundlePort,
    private val imports: ConversionGeneratedImportPort,
    private val analysisCacheCleaner: suspend () -> AnalysisCacheCleanupResult = {
        AnalysisCacheCleanupResult(deleted = 0, failed = 0)
    },
    private val onAnalysisCacheCleanupWarning: (AnalysisCacheCleanupResult) -> Unit = {},
) : ConversionCoordinatorPort {
    private val buildMutex = Mutex()
    private val startupCacheCleanupMutex = Mutex()
    private var startupCacheCleanupAttempted = false
    private val forceCapabilityLock = Any()
    private val forceChallenges = mutableMapOf<String, ForceChallengeRecord>()
    private val forceProofs = mutableMapOf<String, ForceProofRecord>()

    override suspend fun begin(request: OnDeviceInputRequest): ConversionBeginResult {
        val reclaimed = try {
            input.reclaimOrphans()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }
        if (!reclaimed) {
            return ConversionBeginResult.Failure(ConversionFailureCode.INPUT_CLEANUP)
        }
        val callerJob = currentCoroutineContext().job
        val control = AnalysisControl(AnalysisSafetyPolicy.mobile) {
            callerJob.ensureActive()
        }
        val artifact = try {
            input.acquire(request, control)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return ConversionBeginResult.Failure(error.toInputFailureCode())
        }
        var job: RestoredConversionJob? = null
        var createFailure: Throwable? = null
        try {
            work.create(artifact)
                .also { job = it }
        } catch (error: Throwable) {
            createFailure = error
        }
        val released = withContext(NonCancellable) {
            runCatching { input.release(artifact) }.getOrDefault(false)
        }
        val createdJob = job
        val claimedCreateCancellation =
            createFailure as? ConversionWorkClaimedCancellationException
        return try {
            if (claimedCreateCancellation != null) {
                throw claimedCreateCancellation
            }
            currentCoroutineContext().ensureActive()
            if (createFailure is CancellationException) {
                throw createFailure
            }
            val claimedToken =
                (createFailure as? ConversionWorkCreateException)?.claimedToken
            if (!released) {
                return ConversionBeginResult.Failure(
                    ConversionFailureCode.INPUT_CLEANUP,
                    createdJob?.token ?: claimedToken,
                )
            }
            if (createFailure != null) {
                return ConversionBeginResult.Failure(
                    ConversionFailureCode.INPUT_INVALID,
                    claimedToken,
                )
            }
            buildMutex.withLock {
                analyzeJob(requireNotNull(createdJob), markAwaiting = true)
            }
        } catch (error: CancellationException) {
            val claimedCancellation =
                claimedCreateCancellation
                    ?: error as? ConversionWorkClaimedCancellationException
            val cleanupToken =
                createdJob?.token ?: claimedCancellation?.claimedToken ?: throw error
            val originalCancellation =
                claimedCancellation?.originalCancellation ?: error
            when (val cleanup = discardInterrupted(cleanupToken)) {
                RestoredConversion.InterruptedDiscarded -> throw originalCancellation
                is RestoredConversion.InterruptedCleanupPending ->
                    throw ConversionCleanupCancellationException(
                        cleanup.token,
                        originalCancellation,
                    )
                else -> error("취소 cleanup은 완료 또는 pending이어야 합니다.")
            }
        }
    }

    override suspend fun analyze(token: String): ConversionBeginResult = buildMutex.withLock {
        val job = work.restoreBuildAware()
            ?: return@withLock ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)
        if (job.token != token) {
            return@withLock ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)
        }
        if (job.stage == ConversionJobStage.BUILDING) {
            return@withLock when (val reconciled = reconcileBuilding(job)) {
                is RestoredConversion.Analysis -> reconciled.value
                is RestoredConversion.GeneratedPending ->
                    ConversionBeginResult.Failure(ConversionFailureCode.BUNDLE_IO, job.token)
                is RestoredConversion.BundleReady ->
                    ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID, job.token)
                RestoredConversion.InterruptedDiscarded,
                is RestoredConversion.InterruptedCleanupPending,
                ->
                    ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID, job.token)
                null ->
                    ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID, job.token)
            }
        }
        if (job.stage == ConversionJobStage.BUNDLE_READY) {
            return@withLock ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)
        }
        analyzeJob(
            job,
            markAwaiting = job.stage == ConversionJobStage.INPUT_READY,
        )
    }

    override suspend fun build(
        token: String,
        approvedAdapterIds: Set<String>,
        partialConfirmed: Boolean,
        identitySelection: InstallationIdentitySelection?,
    ): ConversionBuildResult = build(
        token,
        approvedAdapterIds,
        partialConfirmed,
        identitySelection,
        ConversionAuthorization.NONE,
    )

    override suspend fun build(
        token: String,
        approvedAdapterIds: Set<String>,
        partialConfirmed: Boolean,
        identitySelection: InstallationIdentitySelection?,
        authorization: ConversionAuthorization,
    ): ConversionBuildResult = if (authorization.forceCompatibility == null) {
        build(
            token,
            approvedAdapterIds,
            partialConfirmed,
            identitySelection,
            OnDeviceConversionApproval.NONE,
        )
    } else {
        ConversionBuildResult.Failure(ConversionFailureCode.UNKNOWN)
    }

    override fun authorizeForce(
        challenge: ConversionForceChallenge,
        approvedAdapterIds: Set<String>,
        identitySelection: InstallationIdentitySelection?,
    ): OnDeviceConversionApproval? = synchronized(forceCapabilityLock) {
        val current = forceChallenges[challenge.token]
            ?.takeIf { it.challenge == challenge }
            ?: return@synchronized null
        if (!current.accepts(approvedAdapterIds, identitySelection)) {
            return@synchronized null
        }
        forceChallenges.remove(challenge.token)
        forceProofs.entries.removeIf {
            it.value.challenge.challenge.token == challenge.token
        }
        val proof = ConversionForceProof(UUID.randomUUID().toString())
        forceProofs[proof.nonce] = ForceProofRecord(
            challenge = current,
            approvedAdapterIds = approvedAdapterIds.toSet(),
            identitySelection = identitySelection,
        )
        OnDeviceConversionApproval(
            forceProof = proof,
        )
    }

    override fun revokeForce(
        challenge: ConversionForceChallenge,
        approval: OnDeviceConversionApproval?,
        reissue: Boolean,
    ): ConversionForceChallenge? = synchronized(forceCapabilityLock) {
        val challengeRecord = forceChallenges[challenge.token]
            ?.takeIf { it.challenge == challenge }
        val proofRecord = approval?.forceProof?.let { forceProofs[it.nonce] }
            ?.takeIf { it.challenge.challenge == challenge }
        val current = when {
            approval == null -> challengeRecord
            proofRecord != null -> proofRecord.challenge
            else -> null
        } ?: return@synchronized null
        forceChallenges.remove(challenge.token, current)
        forceProofs.entries.removeIf { it.value.challenge.challenge.token == challenge.token }
        if (!reissue) return@synchronized null
        val fresh = current.copy(
            challenge = ConversionForceChallenge(challenge.token, UUID.randomUUID().toString()),
        )
        forceChallenges[challenge.token] = fresh
        fresh.challenge
    }

    override suspend fun build(
        token: String,
        approvedAdapterIds: Set<String>,
        partialConfirmed: Boolean,
        identitySelection: InstallationIdentitySelection?,
        approval: OnDeviceConversionApproval,
    ): ConversionBuildResult = buildMutex.withLock {
        var job = work.restoreBuildAware()
            ?: return@withLock ConversionBuildResult.Failure(
                ConversionFailureCode.INPUT_INVALID,
            )
        if (job.token == token && job.stage == ConversionJobStage.BUILDING) {
            when (val reconciled = reconcileBuilding(job)) {
                is RestoredConversion.BundleReady ->
                    return@withLock ConversionBuildResult.BundleReady(
                        reconciled.review,
                        reconciled.generated,
                    )
                is RestoredConversion.GeneratedPending ->
                    return@withLock ConversionBuildResult.Failure(
                        ConversionFailureCode.BUNDLE_IO,
                        reconciled.generated,
                    )
                is RestoredConversion.Analysis -> {
                    job = work.restoreBuildAware()
                        ?: return@withLock ConversionBuildResult.Failure(
                            ConversionFailureCode.INPUT_INVALID,
                        )
                }
                RestoredConversion.InterruptedDiscarded,
                is RestoredConversion.InterruptedCleanupPending,
                ->
                    return@withLock ConversionBuildResult.Failure(
                        ConversionFailureCode.INPUT_INVALID,
                    )
                null ->
                    return@withLock ConversionBuildResult.Failure(
                        ConversionFailureCode.INPUT_INVALID,
                    )
            }
        }
        if (job.token != token || job.stage != ConversionJobStage.AWAITING_APPROVAL) {
            return@withLock ConversionBuildResult.Failure(ConversionFailureCode.INPUT_INVALID)
        }
        val presentedProof = approval.forceProof?.let(::takeForceProof)
        if (approval.forceProof != null && presentedProof == null) {
            return@withLock ConversionBuildResult.Failure(ConversionFailureCode.UNKNOWN)
        }
        val profile = when (val snapshot = profiles.load()) {
            is SamsungProfileSnapshot.Supported -> snapshot.profile
            SamsungProfileSnapshot.Missing ->
                return@withLock ConversionBuildResult.Failure(
                    ConversionFailureCode.BROWSER_MISSING,
                )
            SamsungProfileSnapshot.Unknown ->
                return@withLock ConversionBuildResult.Failure(
                    ConversionFailureCode.PROFILE_UNKNOWN,
                )
        }
        val callerJob = currentCoroutineContext().job
        val control = AnalysisControl(AnalysisSafetyPolicy.mobile) {
            callerJob.ensureActive()
        }
        val analysis = try {
            work.withVerifiedInputFile(token) {
                runtime.analyze(it, job.inputKind, profile, control)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return@withLock ConversionBuildResult.Failure(job.inputKind.readFailureCode())
        }
        val profileSha256 = profile.canonicalSha256()
        if (job.profileSha256 != profileSha256) {
            return@withLock ConversionBuildResult.Failure(ConversionFailureCode.PROFILE_CHANGED)
        }
        val proofMatches = presentedProof?.matches(
            job,
            profileSha256,
            profile.majorVersion,
            analysis,
            approvedAdapterIds,
            identitySelection,
            currentIdentityRequirementSnapshot(analysis, profile.majorVersion),
        ) ?: false
        if (presentedProof != null && !proofMatches) {
            return@withLock ConversionBuildResult.Failure(ConversionFailureCode.UNKNOWN)
        }
        val authorization = if (presentedProof != null) {
            ConversionAuthorization.forceUnknownApiUsage()
        } else {
            ConversionAuthorization.NONE
        }
        val forcedCompatibility = try {
            ForceCompatibilityPolicy.requireProvenance(analysis.report, authorization)
        } catch (_: Throwable) {
            return@withLock ConversionBuildResult.Failure(ConversionFailureCode.UNKNOWN)
        }
        analysis.report.toBlockingFailure()?.let { failure ->
            if (failure != ConversionFailureCode.UNKNOWN || forcedCompatibility == null) {
                return@withLock ConversionBuildResult.Failure(failure)
            }
        }
        val allowedApprovals = analysis.requiredApprovals + analysis.optionalApprovals
        if (!approvedAdapterIds.containsAll(analysis.requiredApprovals) ||
            !allowedApprovals.containsAll(approvedAdapterIds)
        ) {
            return@withLock ConversionBuildResult.Failure(
                ConversionFailureCode.APPROVAL_REQUIRED,
            )
        }
        if (
            analysis.report.status == CompatibilityStatus.PARTIAL &&
            !partialConfirmed
        ) {
            return@withLock ConversionBuildResult.Failure(
                ConversionFailureCode.PARTIAL_CONFIRMATION_REQUIRED,
            )
        }
        val prepared = try {
            work.withVerifiedInputFile(token) {
                runtime.prepare(
                    it,
                    job.inputKind,
                    profile,
                    approvedAdapterIds,
                    control,
                    authorization,
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return@withLock ConversionBuildResult.Failure(
                if (job.inputKind == OnDeviceInputKind.SIX_DRAFT) {
                    ConversionFailureCode.DRAFT_INVALID
                } else {
                    ConversionFailureCode.TRANSFORM_MISMATCH
                },
            )
        }
        if (
            prepared.reportBefore != analysis.report ||
            prepared.identityMode != analysis.identityMode ||
            prepared.forcedCompatibility != forcedCompatibility
        ) {
            return@withLock ConversionBuildResult.Failure(
                ConversionFailureCode.TRANSFORM_MISMATCH,
            )
        }
        if (
            (prepared.logicalSourceIdentity != null && identitySelection != null) ||
            (prepared.logicalSourceIdentity == null && identitySelection == null)
        ) {
            return@withLock ConversionBuildResult.Failure(
                ConversionFailureCode.IDENTITY_MISMATCH,
            )
        }

        var generated: GeneratedBundle? = null
        var building = false
        try {
            val resolved = identities.resolve(
                prepared,
                profile,
                identitySelection,
                token,
            )
            val installedWrapper = installed.find(resolved.identity.packageName)
            if (
                installedWrapper != null &&
                (
                    installedWrapper.packageName != resolved.identity.packageName ||
                        installedWrapper.extensionId != resolved.identity.extensionId ||
                        installedWrapper.signerSha256 != resolved.identity.wrapperSignerSha256
                    )
            ) {
                releaseFailedAttempt(token)
                return@withLock ConversionBuildResult.Failure(
                    ConversionFailureCode.IDENTITY_MISMATCH,
                )
            }
            val bound = runtime.bind(
                prepared,
                profile,
                resolved.identity,
                authorization,
            )
            if (bound.extensionId != resolved.identity.extensionId) {
                releaseFailedAttempt(token)
                return@withLock ConversionBuildResult.Failure(
                    ConversionFailureCode.IDENTITY_MISMATCH,
                )
            }
            if (!bound.matchesAuthorizedCompatibility(
                    analysis.report,
                    authorization,
                    forcedCompatibility,
                    prepared.originalSourceSha256,
                    prepared.planSha256,
                )
            ) {
                releaseFailedAttempt(token)
                return@withLock ConversionBuildResult.Failure(
                    ConversionFailureCode.TRANSFORM_MISMATCH,
                )
            }
            val reservation = identities.reserve(
                resolved,
                prepared.descriptorName,
                installedWrapper?.versionCode,
                token,
            )
            work.markBuilding(token, approvedAdapterIds, profileSha256)
            building = true
            val packaged = packages.packageWrapper(reservation, bound, token)
            generated = bundles.publish(token, packaged)
            val review = imports.importGenerated(generated)
                ?: return@withLock ConversionBuildResult.Failure(
                    ConversionFailureCode.BUNDLE_IO,
                    generated,
                )
            work.markBundleReady(token, generated.file, generated.sha256)
            ConversionBuildResult.BundleReady(review, generated)
        } catch (error: CancellationException) {
            if (generated == null) {
                if (building) {
                    recoverFailedBuild(token, profileSha256)
                } else {
                    releaseFailedAttempt(token)
                }
            }
            throw error
        } catch (error: Throwable) {
            if (generated == null) {
                if (building) {
                    recoverFailedBuild(token, profileSha256)
                } else {
                    releaseFailedAttempt(token)
                }
            }
            ConversionBuildResult.Failure(error.toBuildFailureCode(), generated)
        }
    }

    override suspend fun restore(importReview: Boolean): RestoredConversion? {
        cleanAnalysisCacheOnce()
        val job = when (val restored = work.restoreForStartup()) {
            is ConversionWorkStartupRestore.Active -> restored.job
            is ConversionWorkStartupRestore.Interrupted ->
                return discardInterrupted(restored.token)
            null -> return null
        }
        return when (job.stage) {
            ConversionJobStage.BUNDLE_READY -> restoreBundle(job, importReview)
            ConversionJobStage.BUILDING ->
                restoreCompletedBuildOrDiscard(job, importReview)
            ConversionJobStage.INPUT_READY,
            ConversionJobStage.AWAITING_APPROVAL,
            ConversionJobStage.DISCARDING,
            -> discardInterrupted(job.token)
        }
    }

    override suspend fun discard(token: String): Boolean = buildMutex.withLock {
        invalidateForceCapabilities(token)
        val workDiscarded = work.discard(token)
        if (!workDiscarded) return@withLock false
        bundles.discard(token)
    }

    override fun copyGenerated(token: String, output: OutputStream): String? =
        bundles.recover(token)?.let { bundles.copyTo(token, output) }

    override suspend fun createGeneratedShareSnapshot(token: String): File? =
        bundles.recover(token)?.let { bundles.createShareSnapshot(token) }

    private suspend fun cleanAnalysisCacheOnce() {
        startupCacheCleanupMutex.withLock {
            if (startupCacheCleanupAttempted) return
            startupCacheCleanupAttempted = true
            val result = try {
                analysisCacheCleaner()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                AnalysisCacheCleanupResult(deleted = 0, failed = 1)
            }
            if (result.failed > 0) {
                runCatching { onAnalysisCacheCleanupWarning(result) }
            }
        }
    }

    private suspend fun restoreBundle(
        job: RestoredConversionJob,
        importReview: Boolean,
    ): RestoredConversion? {
        val generated = bundles.recover(job.token) ?: return null
        if (!importReview) return RestoredConversion.GeneratedPending(generated)
        val review = imports.importGenerated(generated) ?: return null
        return RestoredConversion.BundleReady(review, generated)
    }

    private suspend fun restoreCompletedBuildOrDiscard(
        job: RestoredConversionJob,
        importReview: Boolean,
    ): RestoredConversion {
        val generated = bundles.recover(job.token)
            ?: return discardInterrupted(job.token)
        if (!importReview) return RestoredConversion.GeneratedPending(generated)
        val review = imports.importGenerated(generated)
            ?: return RestoredConversion.GeneratedPending(generated)
        work.markBundleReady(job.token, generated.file, generated.sha256)
        return RestoredConversion.BundleReady(review, generated)
    }

    private suspend fun discardInterrupted(token: String): RestoredConversion =
        withContext(NonCancellable) {
            invalidateForceCapabilities(token)
            val workDiscarded = try {
                work.discard(token)
            } catch (_: Exception) {
                false
            }
            val bundleDiscarded = workDiscarded && try {
                bundles.discard(token)
            } catch (_: Exception) {
                false
            }
            if (bundleDiscarded) {
                RestoredConversion.InterruptedDiscarded
            } else {
                RestoredConversion.InterruptedCleanupPending(token)
            }
        }

    private suspend fun reconcileBuilding(
        job: RestoredConversionJob,
        importReview: Boolean = true,
    ): RestoredConversion? {
        val generated = bundles.recover(job.token)
        if (generated != null) {
            if (!importReview) return RestoredConversion.GeneratedPending(generated)
            val review = try {
                imports.importGenerated(generated)
            } catch (error: CancellationException) {
                throw error
            } catch (_: ConversionBundleException) {
                return RestoredConversion.GeneratedPending(generated)
            } ?: return RestoredConversion.GeneratedPending(generated)
            work.markBundleReady(job.token, generated.file, generated.sha256)
            return RestoredConversion.BundleReady(review, generated)
        }
        val profileSha256 = requireNotNull(job.profileSha256)
        recoverFailedBuild(job.token, profileSha256)
        return analyzeJob(
            job.copy(stage = ConversionJobStage.AWAITING_APPROVAL),
            markAwaiting = false,
        ).let {
            (it as? ConversionBeginResult.AnalysisReady)
                ?.let(RestoredConversion::Analysis)
        }
    }

    private suspend fun analyzeJob(
        job: RestoredConversionJob,
        markAwaiting: Boolean,
    ): ConversionBeginResult {
        invalidateForceCapabilities(job.token)
        val profile = when (val snapshot = profiles.load()) {
            is SamsungProfileSnapshot.Supported -> snapshot.profile
            SamsungProfileSnapshot.Missing ->
                return ConversionBeginResult.Failure(
                    ConversionFailureCode.BROWSER_MISSING,
                    job.token,
                )
            SamsungProfileSnapshot.Unknown ->
                return ConversionBeginResult.Failure(
                    ConversionFailureCode.PROFILE_UNKNOWN,
                    job.token,
                )
        }
        val callerJob = currentCoroutineContext().job
        val control = AnalysisControl(AnalysisSafetyPolicy.mobile) {
            callerJob.ensureActive()
        }
        val analysis = try {
            work.withVerifiedInputFile(job.token) {
                runtime.analyze(it, job.inputKind, profile, control)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return ConversionBeginResult.Failure(
                job.inputKind.readFailureCode(),
                job.token,
            )
        }
        if (analysis.report.status == CompatibilityStatus.BLOCKED) {
            return ConversionBeginResult.Failure(ConversionFailureCode.BLOCKED, job.token)
        }
        val profileSha256 = profile.canonicalSha256()
        if (analysis.report.browserProfileSha256 != profileSha256) {
            return ConversionBeginResult.Failure(ConversionFailureCode.PROFILE_CHANGED, job.token)
        }
        if (markAwaiting) {
            work.markAwaitingApproval(job.token, profileSha256)
        } else if (job.profileSha256 != profileSha256) {
            work.refreshAwaitingApproval(
                job.token,
                requireNotNull(job.profileSha256),
                profileSha256,
            )
        }
        val identityRequirement = analysis.logicalSourceIdentity?.let {
            IdentityRequirement.Stable(
                identities.resolveStableIdentityKey(
                    it,
                    profile.majorVersion,
                    analysis.identityMode,
                ).identitySha256.take(12),
            )
        } ?: IdentityRequirement.UserSelectionRequired(
            identities.existingTargets(profile.majorVersion),
        )
        val forceChallenge = issueForceChallenge(
            job,
            profileSha256,
            analysis,
            identityRequirement,
            profile.majorVersion,
        )
        return ConversionBeginResult.AnalysisReady(
            token = job.token,
            profile = profile,
            report = analysis.report,
            selectedAutomaticAdapters = analysis.selectedAutomaticAdapters,
            requiredApprovals = analysis.requiredApprovals,
            optionalApprovals = analysis.optionalApprovals,
            identityRequirement = identityRequirement,
            forceChallenge = forceChallenge,
            profileChanged = analysis.profileChanged,
            draftSignerFingerprint = analysis.draftSignerFingerprint,
        )
    }

    private fun issueForceChallenge(
        job: RestoredConversionJob,
        profileSha256: String,
        analysis: ConversionAnalysisSnapshot,
        identityRequirement: IdentityRequirement,
        profileMajorVersion: Int,
    ): ConversionForceChallenge? {
        if (!ForceCompatibilityPolicy.isEligible(analysis.report)) return null
        val challenge = ConversionForceChallenge(job.token, UUID.randomUUID().toString())
        synchronized(forceCapabilityLock) {
            forceChallenges[job.token] = ForceChallengeRecord(
                challenge = challenge,
                inputKind = job.inputKind,
                inputSha256 = job.inputSha256,
                sourceSha256 = requireNotNull(analysis.report.sourceSha256),
                profileSha256 = profileSha256,
                reportProfileSha256 = requireNotNull(analysis.report.browserProfileSha256),
                reportSha256 = analysis.report.forceCanonicalSha256(),
                selectedAutomaticAdapters = analysis.selectedAutomaticAdapters.toSet(),
                requiredApprovals = analysis.requiredApprovals.toSet(),
                optionalApprovals = analysis.optionalApprovals.toSet(),
                logicalSourceIdentity = analysis.logicalSourceIdentity,
                identityMode = analysis.identityMode,
                identityRequirementSnapshot = identityRequirement.snapshot(),
                profileMajorVersion = profileMajorVersion,
            )
        }
        return challenge
    }

    private fun takeForceProof(
        proof: ConversionForceProof,
    ): ForceProofRecord? = synchronized(forceCapabilityLock) {
        forceProofs.remove(proof.nonce)
    }

    private fun ForceProofRecord.matches(
        job: RestoredConversionJob,
        profileSha256: String,
        profileMajorVersion: Int,
        analysis: ConversionAnalysisSnapshot,
        approvedAdapterIds: Set<String>,
        identitySelection: InstallationIdentitySelection?,
        currentIdentityRequirement: IdentityRequirementSnapshot,
    ): Boolean = challenge.challenge.token == job.token &&
        challenge.inputKind == job.inputKind &&
        challenge.inputSha256 == job.inputSha256 &&
        challenge.sourceSha256 == analysis.report.sourceSha256 &&
        challenge.profileSha256 == profileSha256 &&
        challenge.reportProfileSha256 == analysis.report.browserProfileSha256 &&
        challenge.reportSha256 == analysis.report.forceCanonicalSha256() &&
        challenge.selectedAutomaticAdapters == analysis.selectedAutomaticAdapters &&
        challenge.requiredApprovals == analysis.requiredApprovals &&
        challenge.optionalApprovals == analysis.optionalApprovals &&
        challenge.logicalSourceIdentity == analysis.logicalSourceIdentity &&
        challenge.identityMode == analysis.identityMode &&
        challenge.profileMajorVersion == profileMajorVersion &&
        challenge.identityRequirementSnapshot == currentIdentityRequirement &&
        this.approvedAdapterIds == approvedAdapterIds &&
        this.identitySelection == identitySelection

    private suspend fun currentIdentityRequirementSnapshot(
        analysis: ConversionAnalysisSnapshot,
        profileMajorVersion: Int,
    ): IdentityRequirementSnapshot = analysis.logicalSourceIdentity?.let {
        IdentityRequirementSnapshot.Stable(
            identities.resolveStableIdentityKey(
                it,
                profileMajorVersion,
                analysis.identityMode,
            ).identitySha256.take(12),
        )
    } ?: IdentityRequirementSnapshot.UserSelectionRequired(
        identities.existingTargets(profileMajorVersion).canonicalTargets(),
    )

    private fun IdentityRequirement.snapshot(): IdentityRequirementSnapshot = when (this) {
        is IdentityRequirement.Stable -> IdentityRequirementSnapshot.Stable(identityKeyPreview)
        is IdentityRequirement.UserSelectionRequired ->
            IdentityRequirementSnapshot.UserSelectionRequired(existingTargets.canonicalTargets())
    }

    private fun invalidateForceCapabilities(token: String) {
        synchronized(forceCapabilityLock) {
            forceChallenges.remove(token)
            forceProofs.entries.removeIf { it.value.challenge.challenge.token == token }
        }
    }

    private fun dev.sixbridge.core.compat.CompatibilityReport.toBlockingFailure():
        ConversionFailureCode? = when (status) {
        CompatibilityStatus.BLOCKED -> ConversionFailureCode.BLOCKED
        CompatibilityStatus.UNKNOWN -> ConversionFailureCode.UNKNOWN
        else -> null
    }

    private fun BoundConversionSnapshot.matchesAuthorizedCompatibility(
        analyzed: dev.sixbridge.core.compat.CompatibilityReport,
        authorization: ConversionAuthorization,
        expectedForced: dev.sixbridge.core.conversion.ForcedCompatibilityProvenance?,
        expectedOriginalSourceSha256: String,
        expectedPlanSha256: String,
    ): Boolean = runCatching {
        BundleJson.validateCompatibility(compatibility)
        require(compatibility.schemaVersion == 6)
        val bound = SixBundle.toCoreCompatibility(compatibility)
        require(bound.status == analyzed.status)
        require(bound.manifestVersion == analyzed.manifestVersion)
        require(bound.extensionName == analyzed.extensionName)
        require(bound.extensionVersion == analyzed.extensionVersion)
        require(bound.declaredPermissions == analyzed.declaredPermissions)
        require(bound.usedApis == analyzed.usedApis)
        require(bound.issues == analyzed.issues)
        require(bound.verifiedApis == analyzed.verifiedApis)
        require(bound.unverifiedApis == analyzed.unverifiedApis)
        require(bound.unavailableApis == analyzed.unavailableApis)
        require(bound.browserProfileSha256 == analyzed.browserProfileSha256)
        require(compatibility.originalSourceSha256 == expectedOriginalSourceSha256)
        require(compatibility.planSha256 == expectedPlanSha256)
        require(
            ForceCompatibilityPolicy.requireProvenance(bound, authorization) ==
                expectedForced,
        )
        require(
            SixBundle.toCoreForcedCompatibility(compatibility.forcedCompatibility) ==
                expectedForced,
        )
    }.isSuccess

    private fun Throwable.toInputFailureCode(): ConversionFailureCode = when (this) {
        is OnDeviceInputException -> when (code) {
            OnDeviceInputErrorCode.INVALID_DRAFT -> ConversionFailureCode.DRAFT_INVALID
            OnDeviceInputErrorCode.NETWORK -> ConversionFailureCode.NETWORK
            OnDeviceInputErrorCode.URL -> ConversionFailureCode.URL
            OnDeviceInputErrorCode.SOURCE_UNAVAILABLE,
            OnDeviceInputErrorCode.SOURCE_READ_FAILED,
            OnDeviceInputErrorCode.INPUT_TOO_LARGE,
            OnDeviceInputErrorCode.INVALID_ZIP,
            OnDeviceInputErrorCode.INVALID_CRX3,
            OnDeviceInputErrorCode.UNSUPPORTED_FORMAT,
            OnDeviceInputErrorCode.INPUT_INVALID,
            OnDeviceInputErrorCode.PRIVATE_STORAGE_FAILED,
            -> ConversionFailureCode.INPUT_INVALID
        }
        is java.net.UnknownHostException,
        is java.net.SocketException,
        -> ConversionFailureCode.NETWORK
        is IllegalArgumentException -> ConversionFailureCode.URL
        else -> ConversionFailureCode.INPUT_INVALID
    }

    private fun Throwable.toBuildFailureCode(): ConversionFailureCode = when (this) {
        is ConversionIdentityException -> ConversionFailureCode.IDENTITY_MISMATCH
        is ConversionKeyStoreException -> ConversionFailureCode.KEYSTORE
        is ConversionPackageException -> ConversionFailureCode.PACKAGE
        is ConversionBundleException -> ConversionFailureCode.BUNDLE_IO
        else -> ConversionFailureCode.PACKAGE
    }

    private fun OnDeviceInputKind.readFailureCode(): ConversionFailureCode =
        if (this == OnDeviceInputKind.SIX_DRAFT) {
            ConversionFailureCode.DRAFT_INVALID
        } else {
            ConversionFailureCode.INPUT_INVALID
        }

    private suspend fun releaseFailedAttempt(token: String) {
        withContext(NonCancellable) {
            identities.releaseFailedAttempt(token)
        }
    }

    private suspend fun recoverFailedBuild(token: String, profileSha256: String) {
        withContext(NonCancellable) {
            identities.releaseFailedAttempt(token)
            work.resetAwaitingApproval(token, profileSha256)
        }
    }
}

private data class ForceChallengeRecord(
    val challenge: ConversionForceChallenge,
    val inputKind: OnDeviceInputKind,
    val inputSha256: String,
    val sourceSha256: String,
    val profileSha256: String,
    val reportProfileSha256: String,
    val reportSha256: String,
    val selectedAutomaticAdapters: Set<String>,
    val requiredApprovals: Set<String>,
    val optionalApprovals: Set<String>,
    val logicalSourceIdentity: LogicalSourceIdentity?,
    val identityMode: InstallationIdentityMode,
    val identityRequirementSnapshot: IdentityRequirementSnapshot,
    val profileMajorVersion: Int,
) {
    fun accepts(
        approvedAdapterIds: Set<String>,
        identitySelection: InstallationIdentitySelection?,
    ): Boolean {
        val allowed = requiredApprovals + optionalApprovals
        if (!approvedAdapterIds.containsAll(requiredApprovals) ||
            !allowed.containsAll(approvedAdapterIds)
        ) return false
        return when (val requirement = identityRequirementSnapshot) {
            is IdentityRequirementSnapshot.Stable -> identitySelection == null
            is IdentityRequirementSnapshot.UserSelectionRequired -> when (identitySelection) {
                InstallationIdentitySelection.CreateNew -> true
                is InstallationIdentitySelection.UseExisting ->
                    requirement.targets.any { it.identitySha256 == identitySelection.identitySha256 }
                null -> false
            }
        }
    }
}

private sealed interface IdentityRequirementSnapshot {
    data class Stable(val identityKeyPreview: String) : IdentityRequirementSnapshot
    data class UserSelectionRequired(
        val targets: List<ExistingTargetSnapshot>,
    ) : IdentityRequirementSnapshot
}

private data class ExistingTargetSnapshot(
    val identitySha256: String,
    val displayName: String,
    val extensionId: String,
    val packageName: String,
    val wrapperSignerSha256: String,
)

private fun List<ExistingConversionTarget>.canonicalTargets(): List<ExistingTargetSnapshot> =
    map {
        ExistingTargetSnapshot(
            it.identitySha256,
            it.displayName,
            it.extensionId,
            it.packageName,
            it.wrapperSignerSha256,
        )
    }.sortedWith(
        compareBy<ExistingTargetSnapshot>(
            { it.identitySha256 },
            { it.displayName },
            { it.extensionId },
            { it.packageName },
            { it.wrapperSignerSha256 },
        ),
    ).toList()

private data class ForceProofRecord(
    val challenge: ForceChallengeRecord,
    val approvedAdapterIds: Set<String>,
    val identitySelection: InstallationIdentitySelection?,
)

private fun dev.sixbridge.core.compat.CompatibilityReport.forceCanonicalSha256(): String =
    MessageDigest.getInstance("SHA-256")
        .digest(SixBundle.encodeCompatibility(this).encodeToByteArray())
        .joinToString("") { "%02x".format(it) }
