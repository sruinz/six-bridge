package dev.sixbridge.manager.conversion

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.draft.VerifiedPreparedCrxArtifact
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.packager.OnDevicePackageIdentity
import dev.sixbridge.manager.wrapper.InstalledWrapper
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CancellationException

sealed interface IdentityRequirement {
    data class Stable(
        val identityKeyPreview: String,
    ) : IdentityRequirement

    data class UserSelectionRequired(
        val existingTargets: List<ExistingConversionTarget>,
    ) : IdentityRequirement
}

enum class ConversionFailureCode {
    URL,
    NETWORK,
    INPUT_INVALID,
    INPUT_CLEANUP,
    DRAFT_INVALID,
    BROWSER_MISSING,
    PROFILE_UNKNOWN,
    PROFILE_CHANGED,
    BLOCKED,
    UNKNOWN,
    APPROVAL_REQUIRED,
    PARTIAL_CONFIRMATION_REQUIRED,
    TRANSFORM_MISMATCH,
    KEYSTORE,
    PACKAGE,
    IDENTITY_MISMATCH,
    BUNDLE_IO,
}

sealed interface ConversionBeginResult {
    data class AnalysisReady(
        val token: String,
        val profile: BrowserProfile,
        val report: CompatibilityReport,
        val selectedAutomaticAdapters: Set<String>,
        val requiredApprovals: Set<String>,
        val identityRequirement: IdentityRequirement,
        val profileChanged: Boolean = false,
        val draftSignerFingerprint: String? = null,
    ) : ConversionBeginResult

    data class Failure(
        val code: ConversionFailureCode,
        val token: String? = null,
    ) : ConversionBeginResult
}

sealed interface ConversionBuildResult {
    data class BundleReady(
        val review: ReviewBundle,
        val generated: GeneratedBundle,
    ) : ConversionBuildResult

    data class Failure(
        val code: ConversionFailureCode,
        val generated: GeneratedBundle? = null,
    ) : ConversionBuildResult
}

sealed interface RestoredConversion {
    data class Analysis(
        val value: ConversionBeginResult.AnalysisReady,
    ) : RestoredConversion

    data class BundleReady(
        val review: ReviewBundle,
        val generated: GeneratedBundle,
    ) : RestoredConversion

    data class GeneratedPending(
        val generated: GeneratedBundle,
    ) : RestoredConversion

    data object InterruptedDiscarded : RestoredConversion

    data class InterruptedCleanupPending(
        val token: String,
    ) : RestoredConversion
}

internal sealed interface SamsungProfileSnapshot {
    data class Supported(val profile: BrowserProfile) : SamsungProfileSnapshot
    data object Missing : SamsungProfileSnapshot
    data object Unknown : SamsungProfileSnapshot
}

internal data class ConversionAnalysisSnapshot(
    val report: CompatibilityReport,
    val selectedAutomaticAdapters: Set<String>,
    val requiredApprovals: Set<String>,
    val logicalSourceIdentity: LogicalSourceIdentity?,
    val identityMode: InstallationIdentityMode,
    val profileChanged: Boolean = false,
    val draftSignerFingerprint: String? = null,
)

internal data class PreparedConversionSnapshot(
    val reportBefore: CompatibilityReport,
    val originalSourceSha256: String,
    val planSha256: String,
    val transformedSourceZip: ByteArray,
    val passthroughCrx: ByteArray?,
    val logicalSourceIdentity: LogicalSourceIdentity?,
    val identityMode: InstallationIdentityMode,
    val descriptorName: String,
    val descriptorVersion: String,
    val sourceKind: String,
    val opaque: Any,
    val preparedCrx: VerifiedPreparedCrxArtifact? = null,
    val control: AnalysisControl? = null,
)

internal fun installationIdentityMode(
    inputKind: ExtensionInputKind,
    conversionState: ConversionState,
): InstallationIdentityMode =
    if (
        inputKind in setOf(
            ExtensionInputKind.CRX3,
            ExtensionInputKind.CHROME_WEB_STORE,
        ) &&
        conversionState == ConversionState.PASSTHROUGH
    ) {
        InstallationIdentityMode.PASSTHROUGH
    } else {
        InstallationIdentityMode.CONVERTED
    }

internal data class BoundConversionSnapshot(
    val sourceZip: ByteArray,
    val passthroughCrx: ByteArray?,
    val compatibility: ContractCompatibilityReport,
    val extensionId: String,
    val sourceKind: String,
    val preparedCrx: VerifiedPreparedCrxArtifact? = null,
    val control: AnalysisControl? = null,
)

internal data class ResolvedPackageIdentity(
    val key: InstallationIdentityKey,
    val identity: OnDevicePackageIdentity,
    val unreservedLease: Any?,
)

internal data class ReservedPackageIdentity(
    val resolved: ResolvedPackageIdentity,
    val versionCode: Int,
    val opaque: Any,
)

internal data class PackagedConversion(
    val extensionId: String,
    val packageName: String,
    val versionCode: Int,
    val wrapperSha256: String,
    val outputApk: File,
    val opaque: Any,
)

internal interface ConversionInputPort {
    suspend fun acquire(request: OnDeviceInputRequest): OnDeviceInputArtifact
    suspend fun acquire(
        request: OnDeviceInputRequest,
        control: AnalysisControl,
    ): OnDeviceInputArtifact = acquire(request)
    suspend fun release(artifact: OnDeviceInputArtifact): Boolean
    suspend fun reclaimOrphans(): Boolean
}

internal sealed interface ConversionWorkStartupRestore {
    data class Active(
        val job: RestoredConversionJob,
    ) : ConversionWorkStartupRestore

    data class Interrupted(
        val token: String,
        val stage: ConversionJobStage,
    ) : ConversionWorkStartupRestore
}

internal interface ConversionWorkPort {
    suspend fun create(artifact: OnDeviceInputArtifact): RestoredConversionJob
    suspend fun restore(): RestoredConversionJob?
    suspend fun restoreBuildAware(): RestoredConversionJob? = restore()
    suspend fun restoreForStartup(): ConversionWorkStartupRestore? =
        restoreBuildAware()?.let(ConversionWorkStartupRestore::Active)
    suspend fun markAwaitingApproval(token: String, profileSha256: String)
    suspend fun refreshAwaitingApproval(
        token: String,
        expectedProfileSha256: String,
        profileSha256: String,
    )
    suspend fun markBuilding(
        token: String,
        approvedAdapterIds: Set<String>,
        profileSha256: String,
    )
    suspend fun resetAwaitingApproval(token: String, profileSha256: String)
    suspend fun markBundleReady(token: String, bundle: File, expectedSha256: String)
    suspend fun discard(token: String): Boolean
    suspend fun <T> withVerifiedInputFile(token: String, consume: (File) -> T): T
}

internal class ConversionWorkCreateException(
    val claimedToken: String?,
    cause: Throwable? = null,
) : IllegalStateException(
    if (claimedToken == null) {
        "conversion work를 claim하기 전에 만들지 못했습니다."
    } else {
        "claim된 conversion work를 완성하지 못했습니다."
    },
    cause,
)

internal class ConversionWorkClaimedCancellationException(
    val claimedToken: String,
    val originalCancellation: CancellationException,
) : CancellationException(originalCancellation.message) {
    init {
        initCause(originalCancellation)
    }
}

internal class ConversionCleanupCancellationException(
    val token: String,
    cause: CancellationException,
) : CancellationException(cause.message) {
    init {
        initCause(cause)
    }
}

internal fun interface ConversionProfilePort {
    suspend fun load(): SamsungProfileSnapshot
}

internal interface ConversionRuntimePort {
    fun analyze(
        input: File,
        kind: OnDeviceInputKind,
        profile: BrowserProfile,
        control: AnalysisControl,
    ): ConversionAnalysisSnapshot

    fun prepare(
        input: File,
        kind: OnDeviceInputKind,
        profile: BrowserProfile,
        approvedAdapterIds: Set<String>,
    ): PreparedConversionSnapshot

    fun prepare(
        input: File,
        kind: OnDeviceInputKind,
        profile: BrowserProfile,
        approvedAdapterIds: Set<String>,
        control: AnalysisControl,
    ): PreparedConversionSnapshot = prepare(
        input,
        kind,
        profile,
        approvedAdapterIds,
    )

    fun bind(
        prepared: PreparedConversionSnapshot,
        profile: BrowserProfile,
        identity: OnDevicePackageIdentity,
    ): BoundConversionSnapshot
}

internal interface ConversionIdentityPort {
    suspend fun existingTargets(profileMajorVersion: Int): List<ExistingConversionTarget>
    suspend fun resolve(
        prepared: PreparedConversionSnapshot,
        profile: BrowserProfile,
        selection: InstallationIdentitySelection?,
        ownerToken: String,
    ): ResolvedPackageIdentity
    suspend fun reserve(
        resolved: ResolvedPackageIdentity,
        displayName: String,
        installedVersionCode: Long?,
        ownerToken: String,
    ): ReservedPackageIdentity
    suspend fun abort(ownerToken: String)
}

internal fun interface ConversionInstalledPort {
    suspend fun find(packageName: String): InstalledWrapper?
}

internal interface ConversionPackagePort {
    suspend fun packageWrapper(
        reservation: ReservedPackageIdentity,
        bound: BoundConversionSnapshot,
        token: String,
    ): PackagedConversion
}

internal interface ConversionBundlePort {
    fun publish(token: String, packaged: PackagedConversion): GeneratedBundle
    fun recover(token: String): GeneratedBundle?
    fun discard(token: String): Boolean
    fun copyTo(token: String, output: OutputStream): String
    suspend fun createShareSnapshot(token: String): File? = null
}

internal fun interface ConversionGeneratedImportPort {
    suspend fun importGenerated(generated: GeneratedBundle): ReviewBundle?
}

internal interface ConversionCoordinatorPort {
    suspend fun begin(request: OnDeviceInputRequest): ConversionBeginResult
    suspend fun build(
        token: String,
        approvedAdapterIds: Set<String>,
        partialConfirmed: Boolean,
        identitySelection: InstallationIdentitySelection?,
    ): ConversionBuildResult
    suspend fun restore(importReview: Boolean = true): RestoredConversion?
    suspend fun discard(token: String): Boolean
    fun copyGenerated(token: String, output: OutputStream): String?
    suspend fun createGeneratedShareSnapshot(token: String): File? = null
}
