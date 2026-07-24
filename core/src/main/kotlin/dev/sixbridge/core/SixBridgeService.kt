package dev.sixbridge.core

import dev.sixbridge.core.bundle.ExtensionDescriptor
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.conversion.ConversionPipeline
import dev.sixbridge.core.conversion.CrxIdentityPolicy
import dev.sixbridge.core.conversion.LocalSourcePublication
import dev.sixbridge.core.conversion.SecureBuildPublisher
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionInputResolver
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

}
