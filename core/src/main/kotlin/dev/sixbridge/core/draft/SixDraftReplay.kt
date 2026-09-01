package dev.sixbridge.core.draft

import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.core.bundle.toContractV4
import dev.sixbridge.core.bundle.toContractV6
import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.conversion.ConversionPreparationEngine
import dev.sixbridge.core.conversion.ConversionTempTreeFactory
import dev.sixbridge.core.conversion.LogicalIdentityEvidence
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.core.conversion.PreparedConversion
import dev.sixbridge.core.conversion.CompatibilityForceScope
import dev.sixbridge.core.conversion.ConversionAuthorization
import dev.sixbridge.core.input.PortableExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import java.nio.charset.StandardCharsets.UTF_8

sealed interface DraftReplayResult {
    data class Ready(
        val prepared: PreparedConversion,
    ) : DraftReplayResult

    data class ProfileChanged(
        val signedProfileSha256: String,
        val currentProfileSha256: String,
        val currentAnalysis: CompatibilityReport,
    ) : DraftReplayResult
}

class DraftReplayMismatchException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)

class LegacyDraftNotReplayableException(message: String) :
    IllegalArgumentException(message)

class SixDraftReplay {
    fun replay(
        draft: VerifiedSixDraft,
        profile: BrowserProfile,
        approvedAdapterIds: Set<String>,
        control: AnalysisControl = AnalysisControl(),
    ): DraftReplayResult = replay(
        draft,
        profile,
        approvedAdapterIds,
        control,
        ConversionTempTreeFactory(),
    )

    @JvmSynthetic
    internal fun replay(
        draft: VerifiedSixDraft,
        profile: BrowserProfile,
        approvedAdapterIds: Set<String>,
        control: AnalysisControl,
        tempTreeFactory: ConversionTempTreeFactory,
    ): DraftReplayResult {
        control.checkCancelled()
        val payload = draft.payload
        if (
            payload.draft.schemaVersion == 3 ||
            (
                payload.draft.schemaVersion == 4 &&
                    payload.sourceKind == DraftSourceKind.PREPARED_CRX
            )
        ) {
            throw LegacyDraftNotReplayableException(
                "prepared CRX draft는 replay 대상이 아닙니다.",
            )
        }
        if (payload.draft.schemaVersion == 1 &&
            (
                payload.plan.selectedAdapters.isNotEmpty() ||
                    payload.sourceKind != DraftSourceKind.CRX
                )
        ) {
            throw LegacyDraftNotReplayableException(
                "schema v1 transformed 또는 ZIP draft는 원본 재실행 proof가 없습니다.",
            )
        }
        val input = portableInput(payload)
        return ConversionPreparationEngine.create(
            CompatibilityAnalyzer(),
            tempTreeFactory,
        ).use { engine ->
            val signedProfile = payload.input.browserProfileSha256
            val currentProfile = profile.canonicalSha256()
            if (signedProfile != currentProfile) {
                return@use DraftReplayResult.ProfileChanged(
                    signedProfileSha256 = signedProfile,
                    currentProfileSha256 = currentProfile,
                    currentAnalysis = replayBoundary("현재 profile 분석에 실패했습니다.") {
                        engine.analyzeWithIdentity(input, profile, control).report
                    },
                )
            }

            val authorization = authorizationFromSignedPlan(payload)
            val replayed = replayBoundary("signed draft 재실행에 실패했습니다.") {
                engine.prepare(
                    input,
                    profile,
                    approvedAdapterIds,
                    control,
                    authorization,
                )
            }
            control.checkCancelled()
            val prepared = normalizePrepared(payload, replayed)
            requireExpectedProof(payload, prepared)
            DraftReplayResult.Ready(prepared)
        }
    }

    private fun portableInput(payload: SixDraftPayload): PortableExtensionInput {
        val signedIdentity = payload.draft.sourceIdentity
            .takeIf {
                payload.draft.schemaVersion == 2 ||
                    (
                        payload.draft.schemaVersion == 4 &&
                            payload.sourceKind != DraftSourceKind.PREPARED_CRX
                        )
            }
        return when (payload.sourceKind) {
            DraftSourceKind.ZIP -> PortableExtensionInput.fromZip(
                kind = payload.input.inputKind,
                zipBytes = payload.source,
                signedDraftSourceIdentity = signedIdentity,
            )
            DraftSourceKind.CRX -> PortableExtensionInput.fromCrx(
                kind = payload.input.inputKind,
                crxBytes = payload.source,
                expectedExtensionId = payload.input.originalExtensionId,
                signedDraftSourceIdentity = signedIdentity,
            )
            DraftSourceKind.PREPARED_CRX ->
                throw LegacyDraftNotReplayableException(
                    "prepared CRX draft는 portable source로 재실행할 수 없습니다.",
                )
        }
    }

    private fun normalizePrepared(
        payload: SixDraftPayload,
        prepared: PreparedConversion,
    ): PreparedConversion {
        if (
            payload.draft.schemaVersion != 2 &&
            !(
                payload.draft.schemaVersion == 4 &&
                    payload.sourceKind != DraftSourceKind.PREPARED_CRX
                )
        ) return prepared
        val before = prepared.compatibilityBefore.copy(
            originalExtensionId = payload.input.originalExtensionId,
        )
        val selected = prepared.compatibilityAfter.adapterDecisions
            .filter { it.applied }
            .map { DraftSelectedAdapter(it.id, it.version) }
        val replayedPlan = DraftConversionPlan.create(
            before,
            selected,
            payload.plan.forcedCompatibility,
        )
        val planSha256 = replayedPlan.planSha256
        val reportSha256 = DraftSignature.sha256(
            BundleJson.encodeCompatibility(
                if (payload.draft.schemaVersion == 4) {
                    prepared.compatibilityAfter.toContractV6(
                        originalSourceSha256 = prepared.originalSourceSha256,
                        planSha256 = planSha256,
                        forcedCompatibility = replayedPlan.forcedCompatibility,
                    )
                } else {
                    prepared.compatibilityAfter.toContractV4(
                        originalSourceSha256 = prepared.originalSourceSha256,
                        planSha256 = planSha256,
                    )
                },
            ).toByteArray(UTF_8),
        )
        return PreparedConversion(
            compatibilityBefore = before,
            compatibilityAfter = prepared.compatibilityAfter,
            originalSourceSha256 = prepared.originalSourceSha256,
            transformedSourceSha256 = prepared.transformedSourceSha256,
            planSha256 = planSha256,
            reportSha256 = reportSha256,
            appliedAdapterIds = prepared.appliedAdapterIds,
            transformedSourceZip = prepared.transformedSourceZip,
            originalExtensionId = prepared.originalExtensionId,
            logicalSourceIdentity = prepared.logicalSourceIdentity,
            forcedCompatibility = prepared.forcedCompatibility,
            passthroughCrx = prepared.passthroughCrx,
        )
    }

    private fun requireExpectedProof(
        payload: SixDraftPayload,
        prepared: PreparedConversion,
    ) {
        val expected = payload.expected
        val signedAdapters = payload.plan.selectedAdapters
            .map { it.id to it.version }
        val replayedAdapters = prepared.compatibilityAfter.adapterDecisions
            .filter { it.applied }
            .map { it.id to it.version }
        val identity = prepared.logicalSourceIdentity
        val identityMatches = when (payload.draft.schemaVersion) {
            1 -> identity is LogicalSourceIdentityResult.Stable &&
                identity.evidence == LogicalIdentityEvidence.VERIFIED_STORE_EXTENSION_ID &&
                identity.value == "store:${payload.input.originalExtensionId}"
            2 -> identity is LogicalSourceIdentityResult.Stable &&
                identity.evidence == LogicalIdentityEvidence.SIGNED_DRAFT_METADATA &&
                identity.value == payload.draft.sourceIdentity
            4 -> payload.sourceKind != DraftSourceKind.PREPARED_CRX &&
                identity is LogicalSourceIdentityResult.Stable &&
                identity.evidence == LogicalIdentityEvidence.SIGNED_DRAFT_METADATA &&
                identity.value == payload.draft.sourceIdentity
            else -> false
        }
        val reportMatches = payload.draft.schemaVersion == 1 ||
            expected.reportSha256 == prepared.reportSha256
        val replayedPlanSha256 = if (payload.draft.schemaVersion == 1) {
            DraftConversionPlan.create(
                prepared.compatibilityBefore.copy(
                    originalExtensionId = payload.input.originalExtensionId,
                ),
                emptyList(),
            ).planSha256
        } else {
            prepared.planSha256
        }
        if (
            expected.originalSourceSha256 != prepared.originalSourceSha256 ||
            expected.transformedSourceSha256 != prepared.transformedSourceSha256 ||
            expected.planSha256 != replayedPlanSha256 ||
            !reportMatches ||
            signedAdapters != replayedAdapters ||
            prepared.forcedCompatibility != payload.plan.forcedCompatibility ||
            !identityMatches
        ) {
            throw DraftReplayMismatchException(
                "signed draft의 원본, adapter, plan, report 또는 변환 결과 proof가 " +
                    "현재 코어 재실행과 다릅니다.",
            )
        }
    }

    private fun authorizationFromSignedPlan(
        payload: SixDraftPayload,
    ): ConversionAuthorization = when (payload.plan.forcedCompatibility?.scope) {
        null -> ConversionAuthorization.NONE
        CompatibilityForceScope.UNKNOWN_API_USAGE ->
            ConversionAuthorization.forceUnknownApiUsage()
    }

    private inline fun <T> replayBoundary(
        message: String,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: DraftReplayMismatchException) {
        throw error
    } catch (error: IllegalArgumentException) {
        throw DraftReplayMismatchException("$message ${error.message}", error)
    } catch (error: IllegalStateException) {
        throw DraftReplayMismatchException("$message ${error.message}", error)
    }
}
