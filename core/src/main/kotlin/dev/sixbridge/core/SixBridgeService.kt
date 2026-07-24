package dev.sixbridge.core

import dev.sixbridge.core.bundle.ExtensionDescriptor
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.conversion.ConversionPipeline
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.conversion.CrxIdentityPolicy
import dev.sixbridge.core.conversion.LocalSourcePublication
import dev.sixbridge.core.conversion.SecureBuildPublisher
import dev.sixbridge.core.draft.DraftConversionPlan
import dev.sixbridge.core.draft.DraftInputMetadata
import dev.sixbridge.core.draft.DraftMetadata
import dev.sixbridge.core.draft.DraftSelectedAdapter
import dev.sixbridge.core.draft.DraftSigningKeyStore
import dev.sixbridge.core.draft.DraftSourceKind
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.draft.SixDraftPayload
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionInputResolver
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.wrapper.AndroidToolchain
import dev.sixbridge.core.wrapper.WrapperApkBuilder
import dev.sixbridge.core.wrapper.WrapperBuildRequest
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

data class SixBuildResult(
    val extensionId: String,
    val crx: Path,
    val wrapperApk: Path,
    val bundle: Path,
    val compatibility: CompatibilityReport,
    val compatibilityBefore: CompatibilityReport,
    val adapterIds: List<String>,
    val sourceSha256: String,
    val transformedSha256: String,
)

data class SixDraftResult(
    val draft: Path,
    val conversionState: ConversionState,
    val adapterIds: List<String>,
    val sourceSha256: String,
    val transformedSha256: String,
)

class SixBridgeService(
    private val workspaceRoot: Path,
    private val inputResolver: ExtensionInputResolver = ExtensionInputResolver(),
    private val compatibilityAnalyzer: CompatibilityAnalyzer = CompatibilityAnalyzer(),
    private val toolchainProvider: () -> AndroidToolchain = { AndroidToolchain.locate() },
) {
    private val localRoot = workspaceRoot.resolve(".local")

    fun analyze(input: String, profile: BrowserProfile): CompatibilityReport {
        val resolved = inputResolver.resolve(input)
        return ConversionPipeline.create(localRoot, compatibilityAnalyzer).use { pipeline ->
            pipeline.analyze(resolved, profile)
        }
    }

    fun draft(
        input: String,
        profile: BrowserProfile,
        outputDraft: Path,
        projectIdentity: String? = null,
        approvedAdapterIds: Iterable<String> = emptyList(),
    ): SixDraftResult {
        val resolved = inputResolver.resolve(input).copy(projectIdentity = projectIdentity)
        return ConversionPipeline.create(localRoot, compatibilityAnalyzer).use { pipeline ->
            val prepared = pipeline.prepare(resolved, profile, approvedAdapterIds)
            val applied = prepared.compatibilityAfter.adapterDecisions
                .filter { it.applied }
                .sortedWith(compareBy({ it.id }, { it.version }))
            val before = prepared.compatibilityBefore.copy(
                originalExtensionId = resolved.expectedExtensionId
                    ?: prepared.compatibilityBefore.originalExtensionId,
            )
            val after = if (applied.isEmpty()) {
                before.copy(convertedExtensionId = before.originalExtensionId)
            } else {
                prepared.compatibilityAfter
            }
            val sourceIdentity = prepared.logicalIdentity
                ?: resolved.expectedExtensionId
                ?: prepared.extensionId
            val sourceKind = if (applied.isEmpty() && resolved.crxBytes != null) {
                DraftSourceKind.CRX
            } else {
                DraftSourceKind.ZIP
            }
            val source = if (sourceKind == DraftSourceKind.CRX) {
                requireNotNull(resolved.crxBytes).copyOf()
            } else if (applied.isEmpty()) {
                ExtensionZip.createBytes(
                    requireNotNull(resolved.sourceRoot),
                    includeProjectMarker = true,
                )
            } else {
                prepared.sourceZip
            }
            val payload = SixDraftPayload.create(
                draft = DraftMetadata.create(
                    coreVersion = CORE_VERSION,
                    adapterVersions = applied.associate { it.id to it.version },
                    sourceIdentity = sourceIdentity,
                ),
                input = DraftInputMetadata(
                    inputKind = resolved.kind,
                    originalSourceSha256 = requireNotNull(
                        before.sourceSha256,
                    ),
                    browserProfileSha256 = requireNotNull(
                        before.browserProfileSha256,
                    ),
                    originalExtensionId = before.originalExtensionId,
                    originalExtensionVersion = requireNotNull(
                        before.extensionVersion,
                    ),
                    sourceIdentity = sourceIdentity,
                ),
                sourceKind = sourceKind,
                source = source,
                plan = DraftConversionPlan.create(
                    before,
                    applied.map { DraftSelectedAdapter(it.id, it.version) },
                ),
                compatibilityBefore = before,
                compatibilityAfter = after,
            )
            val target = outputDraft.toAbsolutePath().normalize()
            val key = DraftSigningKeyStore(
                trustedRoot = workspaceRoot,
                keyRoot = localRoot.resolve("keystore/draft-transfer"),
            ).getOrCreate()
            SixDraft.write(target, payload, key)
            SixDraft.read(target)
            SixDraftResult(
                draft = target,
                conversionState = after.conversionState,
                adapterIds = applied.map { it.id },
                sourceSha256 = requireNotNull(
                    before.sourceSha256,
                ),
                transformedSha256 = requireNotNull(
                    after.sourceSha256,
                ),
            )
        }
    }

    fun build(
        input: String,
        profile: BrowserProfile,
        outputBundle: Path? = null,
        projectIdentity: String? = null,
        approvedAdapterIds: Iterable<String> = emptyList(),
    ): SixBuildResult {
        val resolved = inputResolver.resolve(input).copy(projectIdentity = projectIdentity)
        return ConversionPipeline.create(localRoot, compatibilityAnalyzer).use { pipeline ->
            val prepared = pipeline.prepare(
                resolved,
                profile,
                approvedAdapterIds,
            )
            val compatibility = prepared.compatibilityAfter
            val outputDirectory = (outputBundle?.toAbsolutePath()?.parent
                ?: workspaceRoot.resolve("dist"))
            val crxOutput = outputDirectory.resolve("${prepared.extensionId}.crx")
            val wrapperOutput = outputDirectory.resolve("${prepared.extensionId}-wrapper.apk")
            val bundleOutput = outputBundle?.toAbsolutePath()
                ?: outputDirectory.resolve("${prepared.extensionId}.six")
            val targets = listOf(crxOutput, wrapperOutput, bundleOutput)
                .map(Path::toAbsolutePath)
            require(targets.toSet().size == targets.size) {
                "CRX, wrapper APK와 SixBundle 출력 경로가 서로 달라야 합니다."
            }
            val wrapperDraft = prepared.artifactDirectory.resolve("wrapper.apk")
            SecureBuildPublisher.copyExistingOutput(wrapperOutput, wrapperDraft)
            val wrapper = WrapperApkBuilder(toolchainProvider(), localRoot).build(
                WrapperBuildRequest(
                    extensionId = prepared.extensionId,
                    extensionName = compatibility.extensionName ?: prepared.extensionId,
                    crxBytes = prepared.crxBytes,
                    outputApk = wrapperDraft,
                ),
            )
            val descriptor = ExtensionDescriptor(
                extensionId = prepared.extensionId,
                name = compatibility.extensionName ?: prepared.extensionId,
                version = compatibility.extensionVersion ?: "0",
                sourceKind = resolved.kind.name,
                crxSha256 = sha256(prepared.crxBytes),
                wrapperSha256 = sha256(wrapperDraft.toFile().readBytes()),
                wrapperPackage = wrapper.packageName,
                wrapperVersionCode = wrapper.versionCode,
                signerSha256 = wrapper.signerSha256,
            )
            val bundleDraft = prepared.artifactDirectory.resolve("bundle.six")
            SixBundle.write(bundleDraft, wrapperDraft, descriptor, compatibility)
            SecureBuildPublisher.publish(
                outputs = linkedMapOf(
                    crxOutput to prepared.crxBytes,
                    wrapperOutput to Files.readAllBytes(wrapperDraft),
                    bundleOutput to Files.readAllBytes(bundleDraft),
                ),
                localSource = if (
                    resolved.kind == ExtensionInputKind.SOURCE_DIRECTORY ||
                    resolved.kind == ExtensionInputKind.ZIP
                ) {
                    LocalSourcePublication(
                        source = prepared.convertedSourceRoot,
                        destination = localRoot.resolve("extensions")
                            .resolve(prepared.extensionId),
                        trustedRoot = workspaceRoot,
                    )
                } else {
                    null
                },
            )
            SixBuildResult(
                extensionId = prepared.extensionId,
                crx = crxOutput,
                wrapperApk = wrapperOutput,
                bundle = bundleOutput,
                compatibility = compatibility,
                compatibilityBefore = prepared.compatibilityBefore,
                adapterIds = compatibility.adapterDecisions
                    .filter { it.applied }
                    .map { it.id }
                    .sorted(),
                sourceSha256 = requireNotNull(
                    prepared.compatibilityBefore.sourceSha256,
                ),
                transformedSha256 = requireNotNull(compatibility.sourceSha256),
            )
        }
    }

    private fun prepareCrx(
        resolved: ResolvedExtensionInput,
        source: Path,
    ): PreparedCrx {
        val packed = CrxIdentityPolicy.create(localRoot).pack(
            resolved = resolved,
            source = source,
            sourceProjectIdentity = null,
            markerVerified = false,
            transformed = false,
            profileMajorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION,
        )
        return PreparedCrx(packed.extensionId, packed.bytes)
    }

    private fun prepareCrx(
        resolved: ResolvedExtensionInput,
        source: Path,
        verifiedMarker: String?,
        markerVerified: Boolean,
    ): PreparedCrx {
        val packed = CrxIdentityPolicy.create(localRoot).pack(
            resolved = resolved,
            source = source,
            sourceProjectIdentity = verifiedMarker,
            markerVerified = markerVerified,
            transformed = false,
            profileMajorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION,
        )
        return PreparedCrx(packed.extensionId, packed.bytes)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class PreparedCrx(
        val extensionId: String,
        val bytes: ByteArray,
    )

    companion object {
        private const val CORE_VERSION = "0.1.0-SNAPSHOT"
    }

}
