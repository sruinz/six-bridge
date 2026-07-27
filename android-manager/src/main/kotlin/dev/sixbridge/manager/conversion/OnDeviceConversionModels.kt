package dev.sixbridge.manager.conversion

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.packager.OnDevicePackageIdentity
import dev.sixbridge.manager.wrapper.InstalledWrapper
import java.io.File

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
)

internal data class PreparedConversionSnapshot(
    val reportBefore: CompatibilityReport,
    val originalSourceSha256: String,
    val planSha256: String,
    val transformedSourceZip: ByteArray,
    val passthroughCrx: ByteArray?,
    val logicalSourceIdentity: LogicalSourceIdentity?,
    val descriptorName: String,
    val descriptorVersion: String,
    val sourceKind: String,
    val opaque: Any,
)

internal data class BoundConversionSnapshot(
    val sourceZip: ByteArray,
    val passthroughCrx: ByteArray?,
    val compatibility: ContractCompatibilityReport,
    val extensionId: String,
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

internal fun interface ConversionInputPort {
    suspend fun acquire(request: OnDeviceInputRequest): OnDeviceInputArtifact
}

internal interface ConversionWorkPort {
    suspend fun create(artifact: OnDeviceInputArtifact): RestoredConversionJob
    suspend fun restore(): RestoredConversionJob?
    suspend fun restoreBuildAware(): RestoredConversionJob? = restore()
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

internal fun interface ConversionProfilePort {
    suspend fun load(): SamsungProfileSnapshot
}

internal interface ConversionRuntimePort {
    fun analyze(
        input: File,
        kind: OnDeviceInputKind,
        profile: BrowserProfile,
    ): ConversionAnalysisSnapshot

    fun prepare(
        input: File,
        kind: OnDeviceInputKind,
        profile: BrowserProfile,
        approvedAdapterIds: Set<String>,
    ): PreparedConversionSnapshot

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
}

internal fun interface ConversionGeneratedImportPort {
    suspend fun importGenerated(generated: GeneratedBundle): ReviewBundle?
}
