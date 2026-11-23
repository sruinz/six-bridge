package dev.sixbridge.core

import dev.sixbridge.core.bundle.ExtensionDescriptor
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.conversion.AdapterApprovalException
import dev.sixbridge.core.conversion.ConversionPipeline
import dev.sixbridge.core.conversion.ConversionAuthorization
import dev.sixbridge.core.conversion.ForcedCompatibilityProvenance
import dev.sixbridge.core.conversion.deepImmutableCopy
import dev.sixbridge.core.conversion.ConversionTempTreeFactory
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.conversion.CrxIdentityPolicy
import dev.sixbridge.core.conversion.LocalSourcePublication
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.core.conversion.PublicationGuard
import dev.sixbridge.core.conversion.SecureBuildPublisher
import dev.sixbridge.core.conversion.SecureConversionRoot
import dev.sixbridge.core.conversion.WorkspacePathIdentity
import dev.sixbridge.core.conversion.WorkspacePermissionPolicy
import dev.sixbridge.core.draft.DraftConversionPlan
import dev.sixbridge.core.draft.DraftInputMetadata
import dev.sixbridge.core.draft.DraftMetadata
import dev.sixbridge.core.draft.DraftSelectedAdapter
import dev.sixbridge.core.draft.DraftSigningKeyStore
import dev.sixbridge.core.draft.DraftSigningIdentityProvider
import dev.sixbridge.core.draft.DraftSourceKind
import dev.sixbridge.core.draft.DraftSourceStreams
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.draft.SixDraftPayload
import dev.sixbridge.core.draft.VerifiedSixDraft
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionInputResolver
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.wrapper.AndroidToolchain
import dev.sixbridge.core.wrapper.WrapperApkBuilder
import dev.sixbridge.core.wrapper.WrapperBuildRequest
import dev.sixbridge.contract.bundle.CompatibilityApkBinding
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.KeyPair
import java.security.MessageDigest
import java.util.ArrayDeque

class SixBuildResult(
    val extensionId: String,
    val crx: Path,
    val wrapperApk: Path,
    val bundle: Path,
    val compatibility: CompatibilityReport,
    val compatibilityBefore: CompatibilityReport,
    val adapterIds: List<String>,
    val sourceSha256: String,
    val transformedSha256: String,
    forcedCompatibility: ForcedCompatibilityProvenance?,
) {
    val forcedCompatibility: ForcedCompatibilityProvenance? =
        forcedCompatibility.deepImmutableCopy()
}

data class SixDraftResult(
    val draft: Path,
    val conversionState: ConversionState,
    val adapterIds: List<String>,
    val sourceSha256: String,
    val transformedSha256: String,
)

data class SixDraftAnalysisResult(
    val report: CompatibilityReport,
    val logicalSourceIdentity: LogicalSourceIdentityResult,
)

internal typealias GuardedDraftPublisher = (
    Path,
    SixDraftPayload,
    KeyPair,
    PublicationGuard,
) -> Unit

class SixBridgeService(
    private val workspaceRoot: Path,
    inputResolver: ExtensionInputResolver? = null,
    private val compatibilityAnalyzer: CompatibilityAnalyzer = CompatibilityAnalyzer(),
    private val draftReader: (Path) -> VerifiedSixDraft = SixDraft::read,
    private val draftSourceStreamObserver: (InputStream) -> InputStream = { it },
    private val outputGuardHook: () -> Unit = {},
    draftSigningIdentityProvider: DraftSigningIdentityProvider? = null,
    private val toolchainProvider: () -> AndroidToolchain = { AndroidToolchain.locate() },
    secureConversionRoot: SecureConversionRoot? = null,
) {
    constructor(
        workspaceRoot: Path,
        toolchainProvider: () -> AndroidToolchain,
    ) : this(
        workspaceRoot,
        null,
        CompatibilityAnalyzer(),
        SixDraft::read,
        { it },
        {},
        null,
        toolchainProvider,
        null,
    )

    private val localRoot = workspaceRoot.resolve(".local")
    private val tempTreeFactory = ConversionTempTreeFactory(secureConversionRoot)
    private val inputResolver = inputResolver ?: secureConversionRoot?.let { root ->
        ExtensionInputResolver(
            extractionRootFactory = {
                Files.createDirectory(
                    root.path.resolve("sixbridge-extension-${java.util.UUID.randomUUID()}"),
                )
            },
        )
    } ?: ExtensionInputResolver()
    private val signingIdentityProvider =
        draftSigningIdentityProvider ?: DraftSigningIdentityProvider {
            DraftSigningKeyStore(
                trustedRoot = workspaceRoot,
                keyRoot = localRoot.resolve("keystore/draft-transfer"),
            ).getOrCreate()
        }

    fun analyze(input: String, profile: BrowserProfile): CompatibilityReport {
        val resolved = inputResolver.resolve(input)
        return ConversionPipeline.create(
            localRoot,
            compatibilityAnalyzer,
            tempTreeFactory,
        ).use { pipeline ->
            userInputBoundary("확장 입력을 분석할 수 없습니다.") {
                pipeline.analyze(resolved, profile)
            }
        }
    }

    fun analyzeDraft(
        input: String,
        profile: BrowserProfile,
        control: AnalysisControl = AnalysisControl(),
    ): SixDraftAnalysisResult {
        val resolved = runControlledDraftStage(control) {
            inputResolver.resolve(input)
        }
        return analyzeResolvedDraft(resolved, profile, control)
    }

    fun analyzeResolvedDraft(
        resolved: ResolvedExtensionInput,
        profile: BrowserProfile,
        control: AnalysisControl = AnalysisControl(),
    ): SixDraftAnalysisResult {
        control.checkCancelled()
        return ConversionPipeline.create(
            localRoot,
            compatibilityAnalyzer,
            tempTreeFactory,
        ).use { pipeline ->
            val analysis = runControlledDraftStage(control) {
                userInputBoundary("확장 입력을 분석할 수 없습니다.") {
                    pipeline.analyzeWithIdentity(resolved, profile, control)
                }
            }
            SixDraftAnalysisResult(analysis.report, analysis.logicalSourceIdentity)
        }
    }

    fun draft(
        input: String,
        profile: BrowserProfile,
        outputDraft: Path,
        projectIdentity: String? = null,
        approvedAdapterIds: Iterable<String> = emptyList(),
        protectedInputs: Iterable<Path> = emptyList(),
        control: AnalysisControl = AnalysisControl(),
        authorization: ConversionAuthorization = ConversionAuthorization.NONE,
    ): SixDraftResult {
        val resolved = runControlledDraftStage(control) {
            inputResolver.resolve(input)
        }.copy(projectIdentity = projectIdentity)
        return draftPortableResolved(
            resolved = resolved,
            profile = profile,
            outputDraft = outputDraft,
            projectIdentity = projectIdentity,
            approvedAdapterIds = approvedAdapterIds,
            protectedInputs = protectedInputs,
            control = control,
            authorization = authorization,
        )
    }

    fun draftResolved(
        resolved: ResolvedExtensionInput,
        profile: BrowserProfile,
        outputDraft: Path,
        projectIdentity: String? = null,
        approvedAdapterIds: Iterable<String> = emptyList(),
        protectedInputs: Iterable<Path> = emptyList(),
        control: AnalysisControl = AnalysisControl(),
        authorization: ConversionAuthorization = ConversionAuthorization.NONE,
    ): SixDraftResult = draftResolved(
        resolved,
        profile,
        outputDraft,
        projectIdentity,
        approvedAdapterIds,
        protectedInputs,
        control,
        authorization,
    ) { target, payload, key, publicationGuard ->
        SixDraft.writeGuarded(target, payload, key, publicationGuard)
    }

    @JvmSynthetic
    internal fun draftResolved(
        resolved: ResolvedExtensionInput,
        profile: BrowserProfile,
        outputDraft: Path,
        projectIdentity: String?,
        approvedAdapterIds: Iterable<String>,
        protectedInputs: Iterable<Path>,
        control: AnalysisControl,
        authorization: ConversionAuthorization = ConversionAuthorization.NONE,
        draftPublisher: GuardedDraftPublisher,
    ): SixDraftResult {
        control.checkCancelled()
        val requestedProjectIdentity = projectIdentity ?: resolved.projectIdentity
        val draftInput = resolved.copy(projectIdentity = requestedProjectIdentity)
        validateProjectIdentity(requestedProjectIdentity)
        val target = outputDraft.toAbsolutePath().normalize()
        val outputGuard = ProtectedOutputPolicy.capture(
            workspaceRoot = workspaceRoot,
            localRoot = localRoot,
            resolved = draftInput,
            outputs = listOf(target),
            protectedInputs = protectedInputs,
        )
        outputGuardHook()
        outputGuard.requireCurrent()
        val preflightIdentity = runControlledDraftStage(control) {
            preflightAnalyze(
                draftInput,
                profile,
                approvedAdapterIds,
                control,
                authorization,
            )
        }
        val sourceIdentity = resolveDraftSourceIdentity(
            preflightIdentity,
            requestedProjectIdentity,
        )
        outputGuard.requireCurrent()
        return ConversionPipeline.create(
            localRoot,
            compatibilityAnalyzer,
            tempTreeFactory,
        ).use { pipeline ->
            outputGuard.requireCurrent()
            val prepared = runControlledDraftStage(control) {
                pipeline.prepare(
                    draftInput,
                    profile,
                    approvedAdapterIds,
                    control,
                    authorization,
                )
            }
            check(prepared.logicalSourceIdentity == preflightIdentity) {
                "draft 준비 사이에 논리 원본 신원이 달라졌습니다."
            }
            outputGuard.requireCurrent()
            val targetGuard = outputGuard.captureTargets(listOf(target))
            val applied = prepared.compatibilityAfter.adapterDecisions
                .filter { it.applied }
                .sortedWith(compareBy({ it.id }, { it.version }))
            val before = prepared.compatibilityBefore.copy(
                originalExtensionId = prepared.compatibilityAfter.originalExtensionId,
            )
            val after = prepared.compatibilityAfter
            val sourceKind = DraftSourceKind.PREPARED_CRX
            val source = prepared.crxBytes
            val payload = SixDraftPayload.create(
                draft = if (prepared.forcedCompatibility == null) {
                    DraftMetadata.createV3(
                        coreVersion = CORE_VERSION,
                        adapterVersions = applied.associate { it.id to it.version },
                        sourceIdentity = sourceIdentity,
                    )
                } else {
                    DraftMetadata.createV4(
                        coreVersion = CORE_VERSION,
                        adapterVersions = applied.associate { it.id to it.version },
                        sourceIdentity = sourceIdentity,
                    )
                },
                input = DraftInputMetadata(
                    inputKind = draftInput.kind,
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
                    prepared.forcedCompatibility,
                ),
                compatibilityBefore = before,
                compatibilityAfter = after,
            )
            targetGuard.requireCurrent()
            val key = signingIdentityForDraft(control)
            targetGuard.requireCurrent()
            runControlledDraftStage(control) {
                draftPublisher(
                    target,
                    payload,
                    key,
                    targetGuard.publicationGuard,
                )
                verifyWrittenDraft(payload, key, draftReader(target))
            }
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

    fun draftPortableResolved(
        resolved: ResolvedExtensionInput,
        profile: BrowserProfile,
        outputDraft: Path,
        projectIdentity: String? = null,
        approvedAdapterIds: Iterable<String> = emptyList(),
        protectedInputs: Iterable<Path> = emptyList(),
        control: AnalysisControl = AnalysisControl(),
        authorization: ConversionAuthorization = ConversionAuthorization.NONE,
    ): SixDraftResult = draftPortableResolved(
        resolved,
        profile,
        outputDraft,
        projectIdentity,
        approvedAdapterIds,
        protectedInputs,
        control,
        authorization,
    ) { target, payload, key, publicationGuard ->
        SixDraft.writeGuarded(target, payload, key, publicationGuard)
    }

    @JvmSynthetic
    internal fun draftPortableResolved(
        resolved: ResolvedExtensionInput,
        profile: BrowserProfile,
        outputDraft: Path,
        projectIdentity: String?,
        approvedAdapterIds: Iterable<String>,
        protectedInputs: Iterable<Path>,
        control: AnalysisControl,
        authorization: ConversionAuthorization = ConversionAuthorization.NONE,
        draftPublisher: GuardedDraftPublisher,
    ): SixDraftResult {
        control.checkCancelled()
        val requestedProjectIdentity = projectIdentity ?: resolved.projectIdentity
        val draftInput = resolved.copy(projectIdentity = requestedProjectIdentity)
        validateProjectIdentity(requestedProjectIdentity)
        val target = outputDraft.toAbsolutePath().normalize()
        val outputGuard = ProtectedOutputPolicy.capture(
            workspaceRoot = workspaceRoot,
            localRoot = localRoot,
            resolved = draftInput,
            outputs = listOf(target),
            protectedInputs = protectedInputs,
        )
        outputGuardHook()
        outputGuard.requireCurrent()
        return ConversionPipeline.create(
            localRoot,
            compatibilityAnalyzer,
            tempTreeFactory,
        ).use { pipeline ->
            val prepared = runControlledDraftStage(control) {
                pipeline.preflightPreparation(
                    draftInput,
                    profile,
                    approvedAdapterIds,
                    control,
                    authorization,
                )
            }
            val sourceIdentity = resolveDraftSourceIdentity(
                prepared.logicalSourceIdentity,
                requestedProjectIdentity,
            )
            outputGuard.requireCurrent()
            val targetGuard = outputGuard.captureTargets(listOf(target))
            val applied = prepared.compatibilityAfter.adapterDecisions
                .filter { it.applied }
                .sortedWith(compareBy({ it.id }, { it.version }))
            val before = prepared.compatibilityBefore.copy(
                originalExtensionId = prepared.originalExtensionId,
            )
            val after = prepared.compatibilityAfter
            val sourceKind = if (draftInput.crxBytes == null) {
                DraftSourceKind.ZIP
            } else {
                DraftSourceKind.CRX
            }
            val source = runControlledDraftStage(control) {
                draftInput.crxBytes?.copyOf()
                    ?: ExtensionZip.createBytes(
                        requireNotNull(draftInput.sourceRoot),
                        includeProjectMarker = true,
                    )
            }
            val payload = SixDraftPayload.create(
                draft = if (prepared.forcedCompatibility == null) {
                    DraftMetadata.createV2(
                        coreVersion = CORE_VERSION,
                        adapterVersions = applied.associate { it.id to it.version },
                        sourceIdentity = sourceIdentity,
                    )
                } else {
                    DraftMetadata.createV4(
                        coreVersion = CORE_VERSION,
                        adapterVersions = applied.associate { it.id to it.version },
                        sourceIdentity = sourceIdentity,
                    )
                },
                input = DraftInputMetadata(
                    inputKind = draftInput.kind,
                    originalSourceSha256 = requireNotNull(before.sourceSha256),
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
                    prepared.forcedCompatibility,
                ),
                compatibilityBefore = before,
                compatibilityAfter = after,
            )
            targetGuard.requireCurrent()
            val key = signingIdentityForDraft(control)
            targetGuard.requireCurrent()
            runControlledDraftStage(control) {
                draftPublisher(
                    target,
                    payload,
                    key,
                    targetGuard.publicationGuard,
                )
                verifyWrittenDraft(payload, key, draftReader(target))
            }
            SixDraftResult(
                draft = target,
                conversionState = after.conversionState,
                adapterIds = applied.map { it.id },
                sourceSha256 = requireNotNull(before.sourceSha256),
                transformedSha256 = requireNotNull(after.sourceSha256),
            )
        }
    }

    @JvmSynthetic
    internal fun signingIdentityForDraft(
        control: AnalysisControl = AnalysisControl(),
    ): KeyPair = runControlledDraftStage(control) {
        signingIdentityProvider.getOrCreate()
    }

    fun build(
        input: String,
        profile: BrowserProfile,
        outputBundle: Path? = null,
        projectIdentity: String? = null,
        approvedAdapterIds: Iterable<String> = emptyList(),
        protectedInputs: Iterable<Path> = emptyList(),
        authorization: ConversionAuthorization = ConversionAuthorization.NONE,
    ): SixBuildResult {
        val resolved = inputResolver.resolve(input).copy(projectIdentity = projectIdentity)
        validateProjectIdentity(projectIdentity)
        val outputs = buildOutputPaths(input, outputBundle)
        val outputGuard = ProtectedOutputPolicy.capture(
            workspaceRoot = workspaceRoot,
            localRoot = localRoot,
            resolved = resolved,
            outputs = outputs.all,
            protectedInputs = protectedInputs,
        )
        outputGuardHook()
        outputGuard.requireCurrent()
        preflightAnalyze(resolved, profile, approvedAdapterIds, authorization = authorization)
        outputGuard.requireCurrent()
        val targetGuard = outputGuard.captureTargets(outputs.all)
        return ConversionPipeline.create(
            localRoot,
            compatibilityAnalyzer,
            tempTreeFactory,
        ).use { pipeline ->
            targetGuard.requireCurrent()
            val prepared = pipeline.prepare(
                resolved,
                profile,
                approvedAdapterIds,
                authorization = authorization,
            )
            targetGuard.requireCurrent()
            val compatibility = prepared.compatibilityAfter
            val wrapperDraft = prepared.artifactDirectory.resolve("wrapper.apk")
            targetGuard.requireCurrent()
            SecureBuildPublisher.copyExistingOutput(outputs.wrapperApk, wrapperDraft)
            val wrapper = WrapperApkBuilder(toolchainProvider(), localRoot).build(
                WrapperBuildRequest(
                    extensionId = prepared.extensionId,
                    extensionName = compatibility.extensionName ?: prepared.extensionId,
                    crxBytes = prepared.crxBytes,
                    outputApk = wrapperDraft,
                    compatibilitySha256 = CompatibilityApkBinding
                        .canonicalSha256(prepared.compatibilityContract),
                ),
            )
            targetGuard.requireCurrent()
            val descriptor = ExtensionDescriptor(
                extensionId = prepared.extensionId,
                name = compatibility.extensionName ?: prepared.extensionId,
                version = compatibility.extensionVersion ?: "0",
                sourceKind = resolved.kind.name,
                crxSha256 = sha256(prepared.crxBytes),
                wrapperSha256 = sha256(Files.readAllBytes(wrapperDraft)),
                wrapperPackage = wrapper.packageName,
                wrapperVersionCode = wrapper.versionCode,
                signerSha256 = wrapper.signerSha256,
            )
            val bundleDraft = prepared.artifactDirectory.resolve("bundle.six")
            SixBundle.write(
                bundleDraft,
                wrapperDraft,
                descriptor,
                prepared.compatibilityContract,
            )
            targetGuard.requireCurrent()
            SecureBuildPublisher.publish(
                outputs = linkedMapOf(
                    outputs.crx to prepared.crxBytes,
                    outputs.wrapperApk to Files.readAllBytes(wrapperDraft),
                    outputs.bundle to Files.readAllBytes(bundleDraft),
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
                expectedGuard = targetGuard.publicationGuard,
            )
            SixBuildResult(
                extensionId = prepared.extensionId,
                crx = outputs.crx,
                wrapperApk = outputs.wrapperApk,
                bundle = outputs.bundle,
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
                forcedCompatibility = prepared.forcedCompatibility,
            )
        }
    }

    private fun buildOutputPaths(
        input: String,
        outputBundle: Path?,
    ): BuildOutputPaths {
        val bundle = outputBundle?.toAbsolutePath()?.normalize() ?: run {
            val seed = if (
                input.startsWith("http://") ||
                input.startsWith("https://")
            ) {
                input
            } else {
                Path.of(input).toAbsolutePath().normalize().toString()
            }
            val basename = "sixbridge-${sha256(seed.toByteArray(UTF_8)).take(16)}"
            workspaceRoot.resolve("dist/$basename.six").toAbsolutePath().normalize()
        }
        val filename = requireNotNull(bundle.fileName).toString()
        val basename = if (filename.endsWith(".six", ignoreCase = true)) {
            filename.dropLast(4)
        } else {
            filename
        }
        require(basename.isNotBlank()) {
            "SixBundle output basename이 비어 있습니다."
        }
        val parent = requireNotNull(bundle.parent)
        return BuildOutputPaths(
            crx = parent.resolve("$basename.crx"),
            wrapperApk = parent.resolve("$basename-wrapper.apk"),
            bundle = bundle,
        ).also {
            require(it.all.toSet().size == it.all.size) {
                "CRX, wrapper APK와 SixBundle 출력 경로가 서로 달라야 합니다."
            }
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

    private fun verifyWrittenDraft(
        expected: SixDraftPayload,
        signingKey: KeyPair,
        actual: VerifiedSixDraft,
    ) {
        val payload = actual.payload
        val matches = actual.signerPublicKey.contentEquals(signingKey.public.encoded) &&
            actual.signerFingerprint == sha256(signingKey.public.encoded) &&
            payload.sourceKind == expected.sourceKind &&
            payload.draftJson.contentEquals(expected.draftJson) &&
            payload.inputJson.contentEquals(expected.inputJson) &&
            DraftSourceStreams.contentEquals(
                expected.openSourceStream(),
                draftSourceStreamObserver(actual.openSourceStream()),
            ) &&
            payload.conversionPlanJson.contentEquals(expected.conversionPlanJson) &&
            payload.compatibilityBeforeJson.contentEquals(
                expected.compatibilityBeforeJson,
            ) &&
            payload.compatibilityAfterJson.contentEquals(
                expected.compatibilityAfterJson,
            ) &&
            payload.expectedJson.contentEquals(expected.expectedJson)
        if (!matches) {
            throw DraftSelfVerificationException(
                "게시된 SixDraft가 생성 의도 또는 서명 key와 다릅니다.",
            )
        }
    }

    private fun preflightAnalyze(
        resolved: ResolvedExtensionInput,
        profile: BrowserProfile,
        approvedAdapterIds: Iterable<String>,
        control: AnalysisControl = AnalysisControl(),
        authorization: ConversionAuthorization = ConversionAuthorization.NONE,
    ): LogicalSourceIdentityResult =
        ConversionPipeline.create(
            localRoot,
            compatibilityAnalyzer,
            tempTreeFactory,
        ).use { pipeline ->
            userInputBoundary("확장 입력을 분석할 수 없습니다.") {
                pipeline.preflightPreparation(
                    resolved,
                    profile,
                    approvedAdapterIds,
                    control,
                    authorization,
                ).logicalSourceIdentity
            }
        }

    private fun resolveDraftSourceIdentity(
        logicalSourceIdentity: LogicalSourceIdentityResult,
        projectIdentity: String?,
    ): String = when (logicalSourceIdentity) {
        is LogicalSourceIdentityResult.Stable -> logicalSourceIdentity.value
        LogicalSourceIdentityResult.NeedsUserSelection ->
            projectIdentity?.let { "project:$it" }
                ?: throw SixBridgeUserInputException(
                    "표식 없는 확장 소스에는 source project identity가 필요합니다.",
                )
    }

    private fun validateProjectIdentity(value: String?) {
        if (value != null && !Regex("[0-9a-f]{32}").matches(value)) {
            throw SixBridgeUserInputException(
                "source project identity는 소문자 16진수 32자리여야 합니다.",
            )
        }
    }

    private inline fun <T> userInputBoundary(
        message: String,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: AdapterApprovalException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw SixBridgeUserInputException(
            "$message ${error.message}",
            error,
        )
    }

    private data class PreparedCrx(
        val extensionId: String,
        val bytes: ByteArray,
    )

    private data class BuildOutputPaths(
        val crx: Path,
        val wrapperApk: Path,
        val bundle: Path,
    ) {
        val all: List<Path> = listOf(crx, wrapperApk, bundle)
            .map { it.toAbsolutePath().normalize() }
    }

    companion object {
        private const val CORE_VERSION = "0.1.3-SNAPSHOT"
    }

}

internal inline fun <T> runControlledDraftStage(
    control: AnalysisControl,
    operation: () -> T,
): T {
    control.checkCancelled()
    val result = operation()
    control.checkCancelled()
    return result
}

open class SixBridgeUserInputException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class UnsafeOutputException(message: String) :
    SixBridgeUserInputException(message)

class DraftSelfVerificationException(message: String) :
    IllegalStateException(message)

private object ProtectedOutputPolicy {
    private val persistentRoots = listOf(
        "keystore",
        "source-identities",
        "extensions",
        "versions",
    )

    fun capture(
        workspaceRoot: Path,
        localRoot: Path,
        resolved: ResolvedExtensionInput,
        outputs: Collection<Path>,
        protectedInputs: Iterable<Path>,
    ): ProtectedOutputGuard {
        val protectedInputList = protectedInputs.toList()
        requireSafeRelationships(
            workspaceRoot,
            localRoot,
            resolved,
            outputs,
            protectedInputList,
        )
        val outputParent = outputs.map {
            requireNotNull(it.toAbsolutePath().normalize().parent)
        }.distinct().singleOrNull() ?: throw UnsafeOutputException(
            "모든 출력은 같은 디렉터리에 있어야 합니다.",
        )
        if (
            !Files.isDirectory(outputParent, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(outputParent)
        ) {
            throw UnsafeOutputException(
                "출력 parent가 없습니다. 먼저 실행하세요: mkdir -p \"$outputParent\"",
            )
        }
        val watchedPaths = buildList {
            resolved.sourceRoot?.let(::add)
            if (
                resolved.kind == ExtensionInputKind.ZIP ||
                resolved.kind == ExtensionInputKind.CRX3
            ) {
                add(resolved.archivePath ?: Path.of(resolved.originalInput))
            }
            addAll(protectedInputList)
            persistentRoots.forEach { add(localRoot.resolve(it)) }
            add(outputParent)
        }.map { it.toAbsolutePath().normalize() }
            .distinct()
            .filter { Files.exists(it, NOFOLLOW_LINKS) }
        return unsafeBoundary("출력 보호 identity를 만들 수 없습니다.") {
            val watched = watchedPaths.map(WorkspacePermissionPolicy::captureIdentity)
            ProtectedOutputGuard(
                workspaceRoot = workspaceRoot,
                localRoot = localRoot,
                resolved = resolved,
                protectedInputs = protectedInputList,
                watched = watched,
                publicationGuard = SecureBuildPublisher.capturePublicationGuard(outputs),
            )
        }
    }

    fun captureTargets(
        previous: ProtectedOutputGuard,
        outputs: Collection<Path>,
    ): ProtectedOutputGuard {
        previous.requireCurrent()
        requireSafeRelationships(
            previous.workspaceRoot,
            previous.localRoot,
            previous.resolved,
            outputs,
            previous.protectedInputs,
        )
        return unsafeBoundary("파생 출력 보호 identity를 만들 수 없습니다.") {
            ProtectedOutputGuard(
                workspaceRoot = previous.workspaceRoot,
                localRoot = previous.localRoot,
                resolved = previous.resolved,
                protectedInputs = previous.protectedInputs,
                watched = previous.watched,
                publicationGuard = SecureBuildPublisher.capturePublicationGuard(
                    outputs,
                    previous.publicationGuard,
                ),
            )
        }
    }

    private fun requireSafeRelationships(
        workspaceRoot: Path,
        localRoot: Path,
        resolved: ResolvedExtensionInput,
        outputs: Collection<Path>,
        protectedInputs: Collection<Path>,
    ) {
        val candidates = outputs.map(::anchored)
        val protectedTrees = buildList {
            if (resolved.kind == ExtensionInputKind.SOURCE_DIRECTORY) {
                add(requireNotNull(resolved.sourceRoot))
            }
            persistentRoots.forEach { add(localRoot.resolve(it)) }
        }.map(::anchored)
        val protectedFiles = buildList {
            if (
                resolved.kind == ExtensionInputKind.ZIP ||
                resolved.kind == ExtensionInputKind.CRX3
            ) {
                add(resolved.archivePath ?: Path.of(resolved.originalInput))
            }
            addAll(protectedInputs)
        }.map(::anchored)
        val workspace = anchored(workspaceRoot)
        val local = anchored(localRoot)
        check(local.startsWith(workspace)) {
            "SIX Bridge local root가 workspace 밖에 있습니다."
        }
        candidates.forEach { candidate ->
            protectedTrees.forEach { protected ->
                if (candidate.startsWith(protected) || protected.startsWith(candidate)) {
                    throw UnsafeOutputException(
                        "출력 경로가 보호된 source 또는 영구 상태와 겹칩니다: $candidate",
                    )
                }
            }
            protectedFiles.forEach { protected ->
                val exactOverlap = candidate == protected || protected.startsWith(candidate)
                if (exactOverlap) {
                    throw UnsafeOutputException(
                        "출력 경로가 입력 archive 또는 profile과 겹칩니다: $candidate",
                    )
                }
            }
        }
    }

    private inline fun <T> unsafeBoundary(
        message: String,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: UnsafeOutputException) {
        throw error
    } catch (error: Exception) {
        throw UnsafeOutputException("$message ${error.message}")
    }

    private fun anchored(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        var existing = absolute
        val missing = ArrayDeque<String>()
        while (!Files.exists(existing, NOFOLLOW_LINKS)) {
            val name = existing.fileName ?: throw UnsafeOutputException(
                "출력 경로를 기준점에 고정할 수 없습니다: $absolute",
            )
            missing.addFirst(name.toString())
            existing = existing.parent ?: throw UnsafeOutputException(
                "출력 경로를 기준점에 고정할 수 없습니다: $absolute",
            )
        }
        if (Files.isSymbolicLink(existing)) {
            throw UnsafeOutputException(
                "출력 또는 보호 경로 자체가 symlink입니다: $existing",
            )
        }
        var anchored = existing.toRealPath()
        missing.forEach { anchored = anchored.resolve(it) }
        return anchored.normalize()
    }

}

private class ProtectedOutputGuard(
    val workspaceRoot: Path,
    val localRoot: Path,
    val resolved: ResolvedExtensionInput,
    val protectedInputs: List<Path>,
    val watched: List<WorkspacePathIdentity>,
    val publicationGuard: PublicationGuard,
) {
    fun requireCurrent() {
        try {
            watched.forEach(WorkspacePermissionPolicy::requireSameIdentity)
            SecureBuildPublisher.requirePublicationGuard(publicationGuard)
        } catch (error: UnsafeOutputException) {
            throw error
        } catch (error: Exception) {
            throw UnsafeOutputException(
                "출력 parent, 보호 입력 또는 기존 출력 identity가 변경되었습니다. " +
                    error.message,
            )
        }
    }

    fun captureTargets(outputs: Collection<Path>): ProtectedOutputGuard =
        ProtectedOutputPolicy.captureTargets(this, outputs)
}
