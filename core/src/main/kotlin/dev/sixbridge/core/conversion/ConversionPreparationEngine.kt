package dev.sixbridge.core.conversion

import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.core.bundle.toContractV4
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

class ConversionPreparationEngine private constructor(
    compatibilityAnalyzer: CompatibilityAnalyzer,
) : AutoCloseable {
    private val workspace = ConversionWorkspace()
    private val analyzer = MobilePatternAnalyzer(compatibilityAnalyzer)
    private val planner = ConversionPlanner()

    constructor() : this(CompatibilityAnalyzer())

    fun analyze(
        input: PortableExtensionInput,
        profile: BrowserProfile,
    ): CompatibilityReport {
        val report = analyzer.analyzeArchive(input.canonicalOriginalZip, profile)
        requireCrxManifestBinding(input, report)
        return report
    }

    fun prepare(
        input: PortableExtensionInput,
        profile: BrowserProfile,
        approvedAdapterIds: Iterable<String>,
    ): PreparedConversion {
        val approvals = approvedAdapterIds.toSet()
        val snapshot = workspace.snapshot(input.canonicalOriginalZip)
        val beforeRaw = analyzer.analyzeArchive(snapshot.sourceZip, profile)
        requireCrxManifestBinding(input, beforeRaw)
        val originalExtensionId =
            input.expectedExtensionId ?: beforeRaw.manifestKeyExtensionId
        val plan = plan(beforeRaw, approvals)
        val before = beforeRaw
        val transformed = plan.selectedAdapters.isNotEmpty()
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
        val rawAfter = analyzer.analyzeArchive(transformedArchive, profile)
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
        ).also(CompatibilityReport::validate)
        requireStableReplan(after, approvals, transformed)
        val reportSha256 = sha256(
            BundleJson.encodeCompatibility(
                after.toContractV4(
                    originalSourceSha256 = snapshot.sourceSha256,
                    planSha256 = plan.proofSha256,
                ),
            ).toByteArray(UTF_8),
        )
        val passthrough = input.originalCrx?.takeIf { !transformed }
        return PreparedConversion(
            compatibilityBefore = before,
            compatibilityAfter = after,
            originalSourceSha256 = snapshot.sourceSha256,
            transformedSourceSha256 = sha256(transformedArchive),
            planSha256 = plan.proofSha256,
            reportSha256 = reportSha256,
            appliedAdapterIds = applied.mapTo(sortedSetOf()) { it.id },
            transformedSourceZip = transformedArchive,
            originalExtensionId = originalExtensionId,
            logicalSourceIdentity = logicalSourceIdentity(
                input,
                snapshot.sourceProjectIdentity,
                before,
            ),
            passthroughCrx = passthrough,
        )
    }

    fun bindIdentity(
        prepared: PreparedConversion,
        profile: BrowserProfile,
        publicKeyDer: ByteArray?,
    ): BoundConversion {
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
        ): ConversionPreparationEngine =
            ConversionPreparationEngine(compatibilityAnalyzer)
    }
}
