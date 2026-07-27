package dev.sixbridge.manager.conversion

import dev.sixbridge.core.compat.CompatibilityStatus
import java.io.OutputStream
import java.util.concurrent.CancellationException
import kotlinx.coroutines.NonCancellable
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
) : ConversionCoordinatorPort {
    private val buildMutex = Mutex()

    override suspend fun begin(request: OnDeviceInputRequest): ConversionBeginResult {
        val artifact = try {
            input.acquire(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            return ConversionBeginResult.Failure(error.toInputFailureCode())
        }
        val job = try {
            work.create(artifact)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)
        }
        return analyzeJob(job, markAwaiting = true)
    }

    suspend fun analyze(token: String): ConversionBeginResult {
        val job = work.restore()
            ?: return ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)
        if (job.token != token || job.stage == ConversionJobStage.BUNDLE_READY) {
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
        val job = work.restore()
            ?: return@withLock ConversionBuildResult.Failure(
                ConversionFailureCode.INPUT_INVALID,
            )
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
        val analysis = try {
            work.withVerifiedInputFile(token) {
                runtime.analyze(it, job.inputKind, profile)
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
        if (approvedAdapterIds != analysis.requiredApprovals) {
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
                runtime.prepare(it, job.inputKind, profile, approvedAdapterIds)
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
            prepared.reportBefore.sourceSha256 != analysis.report.sourceSha256
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
                abortOwner(token)
                return@withLock ConversionBuildResult.Failure(
                    ConversionFailureCode.IDENTITY_MISMATCH,
                )
            } catch (_: Throwable) {
                abortOwner(token)
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
                abortOwner(token)
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
            abortOwner(token)
            work.markBundleReady(token, generated.file, generated.sha256)
            ConversionBuildResult.BundleReady(review, generated)
        } catch (error: CancellationException) {
            if (generated == null) {
                if (building) recoverFailedBuild(token, profileSha256) else abortOwner(token)
            }
            throw error
        } catch (error: Throwable) {
            if (generated == null) {
                if (building) recoverFailedBuild(token, profileSha256) else abortOwner(token)
            }
            ConversionBuildResult.Failure(error.toBuildFailureCode(), generated)
        }
    }

    override suspend fun restore(): RestoredConversion? {
        val job = work.restoreBuildAware() ?: return null
        if (job.stage == ConversionJobStage.BUILDING) {
            val generated = bundles.recover(job.token)
            if (generated != null) {
                val review = imports.importGenerated(generated)
                    ?: return RestoredConversion.GeneratedPending(generated)
                abortOwner(job.token)
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
        if (job.stage == ConversionJobStage.BUNDLE_READY) {
            val generated = bundles.recover(job.token) ?: return null
            val review = imports.importGenerated(generated) ?: return null
            return RestoredConversion.BundleReady(review, generated)
        }
        val analyzed = analyzeJob(
            job,
            markAwaiting = job.stage == ConversionJobStage.INPUT_READY,
        )
        return (analyzed as? ConversionBeginResult.AnalysisReady)
            ?.let(RestoredConversion::Analysis)
    }

    override suspend fun discard(token: String): Boolean {
        abortOwner(token)
        bundles.recover(token)?.let {
            if (!bundles.discard(token)) return false
        }
        return work.discard(token)
    }

    override fun copyGenerated(token: String, output: OutputStream): String? =
        bundles.recover(token)?.let { bundles.copyTo(token, output) }

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
        val analysis = try {
            work.withVerifiedInputFile(job.token) {
                runtime.analyze(it, job.inputKind, profile)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            return ConversionBeginResult.Failure(
                job.inputKind.readFailureCode(),
                job.token,
            )
        }
        analysis.report.toBlockingFailure()?.let {
            return ConversionBeginResult.Failure(it, job.token)
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
            val mode = if (
                job.inputKind in setOf(
                    OnDeviceInputKind.CRX3,
                    OnDeviceInputKind.CHROME_WEB_STORE,
                ) &&
                analysis.report.conversionState.name == "PASSTHROUGH"
            ) {
                InstallationIdentityMode.PASSTHROUGH
            } else {
                InstallationIdentityMode.CONVERTED
            }
            IdentityRequirement.Stable(
                InstallationIdentityKey.create(
                    it,
                    profile.majorVersion,
                    mode,
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
            identityRequirement = identityRequirement,
            profileChanged = analysis.profileChanged,
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

    private suspend fun abortOwner(token: String) {
        withContext(NonCancellable) {
            identities.abort(token)
        }
    }

    private suspend fun recoverFailedBuild(token: String, profileSha256: String) {
        withContext(NonCancellable) {
            identities.abort(token)
            work.resetAwaitingApproval(token, profileSha256)
        }
    }
}
