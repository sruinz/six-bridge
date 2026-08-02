package dev.sixbridge.core.conversion

import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.core.bundle.toContractV4
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisStage
import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.PortableExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Collections

class PreparedConversion internal constructor(
    compatibilityBefore: CompatibilityReport,
    compatibilityAfter: CompatibilityReport,
    val originalSourceSha256: String,
    val transformedSourceSha256: String,
    val planSha256: String,
    val reportSha256: String,
    appliedAdapterIds: Set<String>,
    transformedSourceZip: ByteArray,
    val originalExtensionId: String?,
    val logicalSourceIdentity: LogicalSourceIdentityResult,
    passthroughCrx: ByteArray?,
) {
    private val stableTransformedSourceZip = transformedSourceZip.copyOf()
    private val stablePassthroughCrx = passthroughCrx?.copyOf()
    val browserProfileSha256: String =
        requireNotNull(compatibilityBefore.browserProfileSha256) {
            "준비 결과에 browser profile provenance가 없습니다."
        }

    val compatibilityBefore: CompatibilityReport =
        compatibilityBefore.deepImmutableCopy()
    val compatibilityAfter: CompatibilityReport =
        compatibilityAfter.deepImmutableCopy()
    val appliedAdapterIds: Set<String> = Collections.unmodifiableSet(
        appliedAdapterIds.toSortedSet(),
    )

    val transformedSourceZip: ByteArray
        get() = stableTransformedSourceZip.copyOf()

    val passthroughCrx: ByteArray?
        get() = stablePassthroughCrx?.copyOf()
}

data class AnalyzedConversion(
    val report: CompatibilityReport,
    val logicalSourceIdentity: LogicalSourceIdentityResult,
)

internal fun retainAnalyzedOriginalExtensionId(
    report: CompatibilityReport,
    proofExtensionId: String?,
): CompatibilityReport = report.copy(
    originalExtensionId = proofExtensionId ?: report.manifestKeyExtensionId,
).also(CompatibilityReport::validate)

internal class PreparationAnalysisOperation(
    private val control: AnalysisControl,
) {
    init {
        control.beginOperation()
    }

    fun <T> pass(analyze: (emitFinalizingStage: Boolean) -> T): T = analyze(false)

    fun <T> complete(result: T): T {
        control.checkCancelled()
        control.stage(AnalysisStage.FINALIZING)
        return result
    }
}

class ConversionPreparationEngine private constructor(
    private val compatibilityAnalyzer: CompatibilityAnalyzer,
    private val tempTreeFactory: ConversionTempTreeFactory,
) : AutoCloseable {
    private val workspace = ConversionWorkspace(tempTreeFactory)
    private val analyzer = MobilePatternAnalyzer(
        control = AnalysisControl(),
        compatibilityAnalyzer = compatibilityAnalyzer,
        tempTreeFactory = tempTreeFactory,
    )
    private val planner = ConversionPlanner()

    constructor() : this(CompatibilityAnalyzer(), ConversionTempTreeFactory())

    fun analyze(
        input: PortableExtensionInput,
        profile: BrowserProfile,
    ): CompatibilityReport = analyzeWithIdentity(input, profile).report

    fun analyzeWithIdentity(
        input: PortableExtensionInput,
        profile: BrowserProfile,
        control: AnalysisControl = AnalysisControl(),
    ): AnalyzedConversion {
        val analyzed = MobilePatternAnalyzer(
            control = control,
            compatibilityAnalyzer = compatibilityAnalyzer.withControl(control),
            tempTreeFactory = tempTreeFactory,
        )
            .analyzeArchiveWithIdentity(input.canonicalOriginalZip, profile)
        requireCrxManifestBinding(input, analyzed.report)
        val report = retainAnalyzedOriginalExtensionId(
            analyzed.report,
            input.expectedExtensionId,
        )
        return AnalyzedConversion(
            report = report,
            logicalSourceIdentity = logicalSourceIdentity(
                input,
                analyzed.sourceProjectIdentity,
                report,
            ),
        )
    }

    fun prepare(
        input: PortableExtensionInput,
        profile: BrowserProfile,
        approvedAdapterIds: Iterable<String>,
        control: AnalysisControl = AnalysisControl(),
    ): PreparedConversion {
        control.checkCancelled()
        val analysisOperation = PreparationAnalysisOperation(control)
        val controlledAnalyzer = MobilePatternAnalyzer(
            control = control,
            compatibilityAnalyzer = compatibilityAnalyzer.withControl(control),
            tempTreeFactory = tempTreeFactory,
        )
        val approvals = approvedAdapterIds.toSet()
        val snapshot = workspace.snapshot(input.canonicalOriginalZip)
        control.checkCancelled()
        val beforeRaw = analysisOperation.pass { emitFinalizingStage ->
            controlledAnalyzer.analyzeArchive(
                snapshot.sourceZip,
                profile,
                emitFinalizingStage = emitFinalizingStage,
                beginOperation = false,
            )
        }
        requireCrxManifestBinding(input, beforeRaw)
        val originalSourceSha256 =
            input.verifiedCrxPayloadSha256 ?: snapshot.sourceSha256
        val before = beforeRaw.copy(
            sourceSha256 = originalSourceSha256,
        ).also(CompatibilityReport::validate)
        val originalExtensionId =
            input.expectedExtensionId ?: beforeRaw.manifestKeyExtensionId
        val plan = plan(beforeRaw, approvals)
        val proofPlan = if (before == beforeRaw) {
            plan
        } else {
            plan(before, approvals).also {
                check(
                    it.selectedAdapters.map(SelectedAdapter::toDecision) ==
                        plan.selectedAdapters.map(SelectedAdapter::toDecision),
                ) {
                    "CRX payload provenance 정규화로 adapter 선택이 달라졌습니다."
                }
            }
        }
        val transformed = plan.selectedAdapters.isNotEmpty()
        control.checkCancelled()
        val applied = if (transformed) {
            workspace.transform(snapshot, profile, plan)
        } else {
            emptyList()
        }
        requireAllApplied(plan, applied)

        val transformedArchive = ExtensionZip.createBytes(
            snapshot.workingRoot,
            includeProjectMarker = true,
        )
        control.checkCancelled()
        val applicationProof = if (transformed) {
            workspace.verifyApplied(
                snapshot = snapshot,
                profile = profile,
                plan = plan,
                applied = applied,
                expectedArchive = transformedArchive,
            )
        } else {
            null
        }
        val rawAfter = analysisOperation.pass { emitFinalizingStage ->
            controlledAnalyzer.analyzeArchive(
                transformedArchive,
                profile,
                emitFinalizingStage = emitFinalizingStage,
                beginOperation = false,
            )
        }
        control.checkCancelled()
        validateCompatibility(before, rawAfter)
        val after = conversionReport(
            rawAfter = rawAfter,
            applied = applicationProof?.decisions.orEmpty(),
            transformed = transformed,
            beforeDecisions = before.adapterDecisions,
        ).copy(
            originalExtensionId = originalExtensionId,
            convertedExtensionId = if (input.originalCrx != null && !transformed) {
                originalExtensionId
            } else {
                null
            },
            sourceSha256 = if (input.originalCrx != null && !transformed) {
                originalSourceSha256
            } else {
                rawAfter.sourceSha256
            },
        ).also(CompatibilityReport::validate)
        requireStableReplan(after, approvals, transformed)
        val reportSha256 = sha256(
            BundleJson.encodeCompatibility(
                after.toContractV4(
                    originalSourceSha256 = originalSourceSha256,
                    planSha256 = proofPlan.proofSha256,
                ),
            ).toByteArray(UTF_8),
        )
        val passthrough = input.originalCrx?.takeIf { !transformed }
        val resultSourceZip = if (passthrough != null) {
            input.canonicalOriginalZip
        } else {
            transformedArchive
        }
        return analysisOperation.complete(
            PreparedConversion(
                compatibilityBefore = before,
                compatibilityAfter = after,
                originalSourceSha256 = originalSourceSha256,
                transformedSourceSha256 = sha256(resultSourceZip),
                planSha256 = proofPlan.proofSha256,
                reportSha256 = reportSha256,
                appliedAdapterIds = applied.mapTo(sortedSetOf()) { it.id },
                transformedSourceZip = resultSourceZip,
                originalExtensionId = originalExtensionId,
                logicalSourceIdentity = logicalSourceIdentity(
                    input,
                    snapshot.sourceProjectIdentity,
                    before,
                ),
                passthroughCrx = passthrough,
            ),
        )
    }

    fun bindIdentity(
        prepared: PreparedConversion,
        profile: BrowserProfile,
        publicKeyDer: ByteArray?,
    ): BoundConversion {
        require(profile.canonicalSha256() == prepared.browserProfileSha256) {
            "prepare와 bindIdentity의 browser profile provenance가 다릅니다."
        }
        val passthrough = prepared.passthroughCrx
        if (passthrough != null) {
            require(publicKeyDer == null) {
                "passthrough CRX에는 새 공개 키를 결합할 수 없습니다."
            }
            val parsed = Crx3.parse(passthrough)
            require(parsed.extensionId == prepared.originalExtensionId) {
                "passthrough CRX proof ID가 준비 결과와 다릅니다."
            }
            val raw = analyzer.analyzeArchive(parsed.zipBytes, profile)
            requirePreparedProfile(prepared, raw)
            validateCompatibility(prepared.compatibilityBefore, raw)
            val compatibility = raw.copy(
                conversionState = ConversionState.PASSTHROUGH,
                originalExtensionId = parsed.extensionId,
                convertedExtensionId = parsed.extensionId,
            ).also(CompatibilityReport::validate)
            return bound(
                sourceZip = parsed.zipBytes,
                extensionId = parsed.extensionId,
                compatibility = compatibility,
                passthroughCrx = passthrough,
            )
        }

        val publicKey = requireNotNull(publicKeyDer) {
            "변환본에는 플랫폼 CRX 공개 키가 필요합니다."
        }.copyOf()
        require(publicKey.isNotEmpty()) {
            "플랫폼 CRX 공개 키는 비어 있을 수 없습니다."
        }
        val preparedSnapshot = workspace.snapshot(prepared.transformedSourceZip)
        val sourceZip = ExtensionZip.createBytes(
            sourceRoot = preparedSnapshot.workingRoot,
            publicKey = publicKey,
            includeProjectMarker = false,
        )
        val rawAfter = analyzer.analyzeArchive(sourceZip, profile)
        requirePreparedProfile(prepared, rawAfter)
        validateCompatibility(prepared.compatibilityBefore, rawAfter)
        val applied = prepared.compatibilityAfter.adapterDecisions
            .filter(AdapterDecision::applied)
        val compatibility = conversionReport(
            rawAfter = rawAfter,
            applied = applied,
            transformed = applied.isNotEmpty(),
            beforeDecisions = prepared.compatibilityBefore.adapterDecisions,
        ).copy(
            originalExtensionId = prepared.originalExtensionId,
            convertedExtensionId = Crx3.extensionId(publicKey),
        ).also(CompatibilityReport::validate)
        requireStableReplan(
            compatibility,
            prepared.appliedAdapterIds,
            applied.isNotEmpty(),
        )
        return bound(
            sourceZip = sourceZip,
            extensionId = Crx3.extensionId(publicKey),
            compatibility = compatibility,
            passthroughCrx = null,
        )
    }

    override fun close() {
        workspace.close()
    }

    private fun bound(
        sourceZip: ByteArray,
        extensionId: String,
        compatibility: CompatibilityReport,
        passthroughCrx: ByteArray?,
    ): BoundConversion {
        return BoundConversion(
            sourceZip = sourceZip,
            sourceSha256 = sha256(sourceZip),
            extensionId = extensionId,
            compatibility = compatibility,
            passthroughCrx = passthroughCrx,
        )
    }

    private fun requireCrxManifestBinding(
        input: PortableExtensionInput,
        report: CompatibilityReport,
    ) {
        val proofId = input.expectedExtensionId ?: return
        if (report.manifestKeyExtensionId != null &&
            report.manifestKeyExtensionId != proofId
        ) {
            throw CrxSourceBindingException()
        }
    }

    private fun requirePreparedProfile(
        prepared: PreparedConversion,
        report: CompatibilityReport,
    ) {
        require(report.browserProfileSha256 == prepared.browserProfileSha256) {
            "bindIdentity 재분석의 browser profile provenance가 준비 결과와 다릅니다."
        }
    }

    private fun logicalSourceIdentity(
        input: PortableExtensionInput,
        projectIdentity: String?,
        report: CompatibilityReport,
    ): LogicalSourceIdentityResult {
        input.signedDraftSourceIdentity?.let {
            return LogicalSourceIdentityResult.Stable(
                value = it,
                evidence = LogicalIdentityEvidence.SIGNED_DRAFT_METADATA,
            )
        }
        if (input.kind == ExtensionInputKind.CRX3 ||
            input.kind == ExtensionInputKind.CHROME_WEB_STORE
        ) {
            return LogicalSourceIdentityResult.Stable(
                value = "store:${requireNotNull(input.expectedExtensionId)}",
                evidence = LogicalIdentityEvidence.VERIFIED_STORE_EXTENSION_ID,
            )
        }
        projectIdentity?.let {
            return LogicalSourceIdentityResult.Stable(
                value = "project:$it",
                evidence = LogicalIdentityEvidence.PROJECT_MARKER,
            )
        }
        report.manifestKeyExtensionId?.let {
            return LogicalSourceIdentityResult.Stable(
                value = "manifest:$it",
                evidence = LogicalIdentityEvidence.MANIFEST_KEY,
            )
        }
        return LogicalSourceIdentityResult.NeedsUserSelection
    }

    private fun plan(
        report: CompatibilityReport,
        approvals: Set<String>,
    ): ConversionPlan = try {
        planner.plan(report, approvals)
    } catch (error: ConversionPlanningException) {
        when (error.failure) {
            ConversionPlanningFailure.MISSING_REQUIRED_APPROVAL ->
                throw MissingAdapterApprovalException(error.adapterIds)
            ConversionPlanningFailure.UNKNOWN_APPROVAL ->
                throw UnknownAdapterApprovalException(error.adapterIds)
            ConversionPlanningFailure.UNDETECTED_APPROVAL ->
                throw InapplicableAdapterApprovalException(error.adapterIds)
            ConversionPlanningFailure.INELIGIBLE_COMPATIBILITY_STATUS,
            ConversionPlanningFailure.NO_APPLICABLE_ADAPTER,
            -> throw ConversionUnavailableException()
        }
    }

    private fun requireAllApplied(
        plan: ConversionPlan,
        applied: List<AdapterDecision>,
    ) {
        if (applied.size != plan.selectedAdapters.size ||
            applied.map { it.id to it.version } !=
            plan.selectedAdapters.map { it.id to it.version } ||
            applied.any { !it.applied }
        ) {
            throw ConversionValidationException(
                "선택한 adapter가 모두 적용되지 않았습니다.",
            )
        }
    }

    private fun validateCompatibility(
        before: CompatibilityReport,
        after: CompatibilityReport,
    ) {
        if (statusRank(after.status) > statusRank(before.status)) {
            throw ConversionValidationException(
                "변환 후 호환성 상태가 ${before.status}에서 ${after.status}로 악화되었습니다.",
            )
        }
        if (after.issues.any { it.blocking }) {
            throw ConversionValidationException(
                "변환본 재분석에서 blocking issue가 발견되었습니다.",
            )
        }
        val newUnsupported = after.unsupportedApis - before.unsupportedApis
        if (newUnsupported.isNotEmpty()) {
            throw ConversionValidationException(
                "변환으로 새 unsupported API가 생겼습니다: ${newUnsupported.sorted()}",
            )
        }
    }

    private fun conversionReport(
        rawAfter: CompatibilityReport,
        applied: List<AdapterDecision>,
        transformed: Boolean,
        beforeDecisions: List<AdapterDecision>,
    ): CompatibilityReport {
        if (!transformed) {
            return rawAfter.copy(conversionState = ConversionState.PASSTHROUGH)
        }
        check(applied.all(AdapterDecision::applied)) {
            "sealed adapter application proof가 완전하지 않습니다."
        }
        return rawAfter.copy(
            conversionState = ConversionState.CONVERTED,
            adapterDecisions = reconcileAdapterDecisions(
                beforeDecisions,
                applied,
                rawAfter.adapterDecisions,
            ),
        )
    }

    private fun requireStableReplan(
        report: CompatibilityReport,
        approvals: Set<String>,
        transformed: Boolean,
    ) {
        val verificationPlan = plan(report, approvals)
        if (transformed &&
            verificationPlan.selectedAdapters.any { !it.applied }
        ) {
            throw ConversionValidationException(
                "변환본 재계획에 미적용 adapter가 남았습니다.",
            )
        }
    }

    private fun statusRank(status: CompatibilityStatus): Int = when (status) {
        CompatibilityStatus.COMPATIBLE -> 0
        CompatibilityStatus.PARTIAL -> 1
        CompatibilityStatus.BLOCKED -> 2
        CompatibilityStatus.UNKNOWN -> 3
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        @JvmSynthetic
        internal fun create(
            compatibilityAnalyzer: CompatibilityAnalyzer,
            tempTreeFactory: ConversionTempTreeFactory = ConversionTempTreeFactory(),
        ): ConversionPreparationEngine =
            ConversionPreparationEngine(compatibilityAnalyzer, tempTreeFactory)
    }
}
