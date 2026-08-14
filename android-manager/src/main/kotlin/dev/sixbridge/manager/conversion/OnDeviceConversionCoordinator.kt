package dev.sixbridge.manager.conversion

import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisSafetyPolicy
import dev.sixbridge.core.compat.CompatibilityStatus
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CancellationException
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
            analyzeJob(requireNotNull(createdJob), markAwaiting = true)
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

    suspend fun analyze(token: String): ConversionBeginResult {
        val job = work.restoreBuildAware()
            ?: return ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)
        if (job.token != token) {
            return ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)
        }
        if (job.stage == ConversionJobStage.BUILDING) {
            return when (val reconciled = reconcileBuilding(job)) {
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
            return ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)
        }
        return analyzeJob(
            job,
            markAwaiting = job.stage == ConversionJobStage.INPUT_READY,
        )
    }

    override suspend fun build(
        token: String,
        approvedAdapterIds: Set<String>,
        partialConfirmed: Boolean,
        identitySelection: InstallationIdentitySelection?,
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
            runCatching {
                work.refreshAwaitingApproval(
                    token,
                    requireNotNull(job.profileSha256),
                    profileSha256,
                )
            }
            return@withLock ConversionBuildResult.Failure(ConversionFailureCode.PROFILE_CHANGED)
        }
        analysis.report.toBlockingFailure()?.let {
            return@withLock ConversionBuildResult.Failure(it)
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
            prepared.reportBefore.browserProfileSha256 != profileSha256 ||
            prepared.reportBefore.sourceSha256 != analysis.report.sourceSha256 ||
            prepared.identityMode != analysis.identityMode
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
            val resolved = try {
                identities.resolve(
                    prepared,
                    profile,
                    identitySelection,
                    token,
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: IllegalArgumentException) {
                releaseFailedAttempt(token)
                return@withLock ConversionBuildResult.Failure(
                    ConversionFailureCode.IDENTITY_MISMATCH,
                )
            } catch (_: Throwable) {
                releaseFailedAttempt(token)
                return@withLock ConversionBuildResult.Failure(
                    ConversionFailureCode.KEYSTORE,
                )
            }
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
            val reservation = identities.reserve(
                resolved,
                prepared.descriptorName,
                installedWrapper?.versionCode,
                token,
            )
            work.markBuilding(token, approvedAdapterIds, profileSha256)
            building = true
            val bound = runtime.bind(prepared, profile, resolved.identity)
            if (bound.extensionId != resolved.identity.extensionId) {
                recoverFailedBuild(token, profileSha256)
                return@withLock ConversionBuildResult.Failure(
                    ConversionFailureCode.IDENTITY_MISMATCH,
                )
            }
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

    override suspend fun discard(token: String): Boolean {
        val workDiscarded = work.discard(token)
        if (!workDiscarded) return false
        return bundles.discard(token)
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
            val review = imports.importGenerated(generated)
                ?: return RestoredConversion.GeneratedPending(generated)
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
                InstallationIdentityKey.create(
                    it,
                    profile.majorVersion,
                    analysis.identityMode,
                ).identitySha256.take(12),
            )
        } ?: IdentityRequirement.UserSelectionRequired(
            identities.existingTargets(profile.majorVersion),
        )
        return ConversionBeginResult.AnalysisReady(
            token = job.token,
            profile = profile,
            report = analysis.report,
            selectedAutomaticAdapters = analysis.selectedAutomaticAdapters,
            requiredApprovals = analysis.requiredApprovals,
            optionalApprovals = analysis.optionalApprovals,
            identityRequirement = identityRequirement,
            profileChanged = analysis.profileChanged,
            draftSignerFingerprint = analysis.draftSignerFingerprint,
        )
    }

    private fun dev.sixbridge.core.compat.CompatibilityReport.toBlockingFailure():
        ConversionFailureCode? = when (status) {
        CompatibilityStatus.BLOCKED -> ConversionFailureCode.BLOCKED
        CompatibilityStatus.UNKNOWN -> ConversionFailureCode.UNKNOWN
        else -> null
    }

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
        is java.io.IOException -> ConversionFailureCode.BUNDLE_IO
        is IllegalArgumentException -> ConversionFailureCode.IDENTITY_MISMATCH
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
