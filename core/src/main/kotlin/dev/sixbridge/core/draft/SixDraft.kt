package dev.sixbridge.core.draft

import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.core.bundle.toContractV4
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.conversion.PublicationGuard
import dev.sixbridge.core.conversion.SecureBuildPublisher
import dev.sixbridge.core.conversion.WorkspaceAccessState
import dev.sixbridge.core.conversion.WorkspacePermissionPolicy
import dev.sixbridge.core.conversion.deepImmutableCopy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestOutputStream
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Collections
import java.util.LinkedHashSet
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class DraftSourceKind(
    val entryName: String,
) {
    ZIP("source.zip"),
    CRX("source.crx"),
    PREPARED_CRX("prepared.crx"),
}

internal data class DraftSourceEvidence(
    val actualSha256: String,
    val crxExtensionId: String?,
)

internal data class DecodedSixDraftMetadata(
    val draft: DraftMetadata,
    val input: DraftInputMetadata,
    val signerIndependentExpected: DraftExpected,
)

class SixDraftPayload private constructor(
    val draft: DraftMetadata,
    val input: DraftInputMetadata,
    val sourceKind: DraftSourceKind,
    source: ByteArray,
    val plan: DraftConversionPlan,
    compatibilityBefore: dev.sixbridge.core.compat.CompatibilityReport,
    compatibilityAfter: dev.sixbridge.core.compat.CompatibilityReport,
    val expected: DraftExpected,
    copySource: Boolean,
) {
    val compatibilityBefore = compatibilityBefore.deepImmutableCopy()
    val compatibilityAfter = compatibilityAfter.deepImmutableCopy()
    private val stableSource = if (copySource) source.copyOf() else source
    private val stableDraftJson = SixDraftJson.encodeDraft(draft)
    private val stableInputJson = SixDraftJson.encodeInput(input)
    private val stableConversionPlanJson = SixDraftJson.encodePlan(plan)
    private val stableCompatibilityBeforeJson =
        SixDraftJson.encodeCompatibility(compatibilityBefore)
    private val stableCompatibilityAfterJson =
        SixDraftJson.encodeCompatibility(compatibilityAfter)
    private val stableExpectedJson = SixDraftJson.encodeExpected(
        expected,
        draft.schemaVersion,
    )

    val draftJson: ByteArray
        get() = stableDraftJson.copyOf()
    val inputJson: ByteArray
        get() = stableInputJson.copyOf()
    val source: ByteArray
        get() = stableSource.copyOf()
    val conversionPlanJson: ByteArray
        get() = stableConversionPlanJson.copyOf()
    val compatibilityBeforeJson: ByteArray
        get() = stableCompatibilityBeforeJson.copyOf()
    val compatibilityAfterJson: ByteArray
        get() = stableCompatibilityAfterJson.copyOf()
    val expectedJson: ByteArray
        get() = stableExpectedJson.copyOf()

    fun openSourceStream(): InputStream = ByteArrayInputStream(stableSource)

    @JvmSynthetic
    internal fun entries(): LinkedHashMap<String, ByteArray> = linkedMapOf(
        "draft.json" to stableDraftJson,
        "input.json" to stableInputJson,
        sourceKind.entryName to stableSource,
        "conversion-plan.json" to stableConversionPlanJson,
        "compatibility-before.json" to stableCompatibilityBeforeJson,
        "compatibility-after.json" to stableCompatibilityAfterJson,
        "expected.json" to stableExpectedJson,
    )

    companion object {
        fun create(
            draft: DraftMetadata,
            input: DraftInputMetadata,
            sourceKind: DraftSourceKind,
            source: ByteArray,
            plan: DraftConversionPlan,
            compatibilityBefore: dev.sixbridge.core.compat.CompatibilityReport,
            compatibilityAfter: dev.sixbridge.core.compat.CompatibilityReport,
        ): SixDraftPayload = try {
            validateSemantics(
                draft,
                input,
                sourceKind,
                sourceEvidence(sourceKind, source),
                plan,
                compatibilityBefore,
                compatibilityAfter,
            )
            val expected = expectedFor(
                draft,
                input,
                plan,
                compatibilityAfter,
            )
            SixDraftPayload(
                draft,
                input,
                sourceKind,
                source,
                plan,
                compatibilityBefore,
                compatibilityAfter,
                expected,
                copySource = true,
            )
        } catch (error: InvalidSixDraftException) {
            throw error
        } catch (error: Exception) {
            throw InvalidSixDraftException(
                "SixDraft payload 의미 결합이 올바르지 않습니다. ${error.message}",
                error,
            )
        }

        @JvmSynthetic
        internal fun decode(
            draftJson: ByteArray,
            inputJson: ByteArray,
            sourceKind: DraftSourceKind,
            source: ByteArray,
            conversionPlanJson: ByteArray,
            compatibilityBeforeJson: ByteArray,
            compatibilityAfterJson: ByteArray,
            expectedJson: ByteArray,
        ): SixDraftPayload {
            val decoded = decodeAndValidate(
                draftJson = draftJson,
                inputJson = inputJson,
                sourceKind = sourceKind,
                sourceEvidence = sourceEvidence(sourceKind, source),
                conversionPlanJson = conversionPlanJson,
                compatibilityBeforeJson = compatibilityBeforeJson,
                compatibilityAfterJson = compatibilityAfterJson,
                expectedJson = expectedJson,
            )
            return SixDraftPayload(
                decoded.draft,
                decoded.input,
                sourceKind,
                source,
                SixDraftJson.decodePlan(conversionPlanJson),
                SixDraftJson.decodeCompatibility(
                    "compatibility-before.json",
                    compatibilityBeforeJson,
                ),
                SixDraftJson.decodeCompatibility(
                    "compatibility-after.json",
                    compatibilityAfterJson,
                ),
                decoded.signerIndependentExpected,
                copySource = false,
            )
        }

        @JvmSynthetic
        internal fun inspect(
            draftJson: ByteArray,
            inputJson: ByteArray,
            sourceKind: DraftSourceKind,
            sourceEvidence: DraftSourceEvidence,
            conversionPlanJson: ByteArray,
            compatibilityBeforeJson: ByteArray,
            compatibilityAfterJson: ByteArray,
            expectedJson: ByteArray,
        ): DecodedSixDraftMetadata = decodeAndValidate(
            draftJson,
            inputJson,
            sourceKind,
            sourceEvidence,
            conversionPlanJson,
            compatibilityBeforeJson,
            compatibilityAfterJson,
            expectedJson,
        )

        private fun decodeAndValidate(
            draftJson: ByteArray,
            inputJson: ByteArray,
            sourceKind: DraftSourceKind,
            sourceEvidence: DraftSourceEvidence,
            conversionPlanJson: ByteArray,
            compatibilityBeforeJson: ByteArray,
            compatibilityAfterJson: ByteArray,
            expectedJson: ByteArray,
        ): DecodedSixDraftMetadata {
            val draft = SixDraftJson.decodeDraft(draftJson)
            val input = SixDraftJson.decodeInput(inputJson)
            val plan = SixDraftJson.decodePlan(conversionPlanJson)
            val before = SixDraftJson.decodeCompatibility(
                "compatibility-before.json",
                compatibilityBeforeJson,
            )
            val after = SixDraftJson.decodeCompatibility(
                "compatibility-after.json",
                compatibilityAfterJson,
            )
            val expected = SixDraftJson.decodeExpected(
                expectedJson,
                draft.schemaVersion,
                input.originalSourceSha256,
            )
            validateSemantics(
                draft,
                input,
                sourceKind,
                sourceEvidence,
                plan,
                before,
                after,
            )
            require(expected == expectedFor(draft, input, plan, after)) {
                "expected.json 의미 결합이 일치하지 않습니다."
            }
            return DecodedSixDraftMetadata(
                draft = draft,
                input = input,
                signerIndependentExpected = expected,
            )
        }

        private fun expectedFor(
            draft: DraftMetadata,
            input: DraftInputMetadata,
            plan: DraftConversionPlan,
            after: dev.sixbridge.core.compat.CompatibilityReport,
        ): DraftExpected {
            val transformed = requireNotNull(after.sourceSha256)
            val report = when (draft.schemaVersion) {
                1 -> DraftSignature.sha256(
                    SixDraftJson.encodeCompatibility(after),
                )
                2, 3 -> DraftSignature.sha256(
                    BundleJson.encodeCompatibility(
                        after.toContractV4(
                            originalSourceSha256 = input.originalSourceSha256,
                            planSha256 = plan.planSha256,
                        ),
                    ).toByteArray(UTF_8),
                )
                else -> error("지원하지 않는 draft schemaVersion입니다.")
            }
            return DraftExpected(
                originalSourceSha256 = input.originalSourceSha256,
                transformedSourceSha256 = transformed,
                reportSha256 = report,
                planSha256 = plan.planSha256,
            )
        }

        private fun validateSemantics(
            draft: DraftMetadata,
            input: DraftInputMetadata,
            sourceKind: DraftSourceKind,
            sourceEvidence: DraftSourceEvidence,
            plan: DraftConversionPlan,
            before: dev.sixbridge.core.compat.CompatibilityReport,
            after: dev.sixbridge.core.compat.CompatibilityReport,
        ) = when (draft.schemaVersion) {
            1 -> validateV1Semantics(
                draft,
                input,
                sourceKind,
                sourceEvidence,
                plan,
                before,
                after,
            )
            2 -> validateV2Semantics(
                draft,
                input,
                sourceKind,
                sourceEvidence,
                plan,
                before,
                after,
            )
            3 -> validateV3Semantics(
                draft,
                input,
                sourceKind,
                sourceEvidence,
                plan,
                before,
                after,
            )
            else -> error("지원하지 않는 draft schemaVersion입니다.")
        }

        private fun validateV1Semantics(
            draft: DraftMetadata,
            input: DraftInputMetadata,
            sourceKind: DraftSourceKind,
            sourceEvidence: DraftSourceEvidence,
            plan: DraftConversionPlan,
            before: dev.sixbridge.core.compat.CompatibilityReport,
            after: dev.sixbridge.core.compat.CompatibilityReport,
        ) {
            before.validate()
            after.validate()
            require(draft.sourceIdentity == input.sourceIdentity) {
                "draft와 input sourceIdentity가 일치하지 않습니다."
            }
            require(before.sourceSha256 == input.originalSourceSha256) {
                "변환 전 보고서가 원본 source와 일치하지 않습니다."
            }
            require(before.browserProfileSha256 == input.browserProfileSha256) {
                "변환 전 보고서가 browser profile과 일치하지 않습니다."
            }
            require(before.extensionVersion == input.originalExtensionVersion) {
                "변환 전 보고서가 원본 extension version과 일치하지 않습니다."
            }
            require(before.originalExtensionId == input.originalExtensionId) {
                "변환 전 보고서가 원본 extension ID와 일치하지 않습니다."
            }
            require(after.browserProfileSha256 == input.browserProfileSha256) {
                "변환 후 보고서가 browser profile과 일치하지 않습니다."
            }
            require(after.originalExtensionId == input.originalExtensionId) {
                "변환 후 보고서가 원본 extension ID와 일치하지 않습니다."
            }
            require(plan.reportSourceSha256 == before.sourceSha256 &&
                plan.browserProfileSha256 == before.browserProfileSha256 &&
                plan.reportSha256 == SixDraftJson.compatibilitySha256(before)
            ) {
                "변환 계획 provenance가 변환 전 보고서와 일치하지 않습니다."
            }
            val beforeDecisions = before.adapterDecisions.associateBy { it.id to it.version }
            val selectedDecisions = plan.selectedAdapters.map { selected ->
                requireNotNull(beforeDecisions[selected.id to selected.version]) {
                    "변환 계획 어댑터가 변환 전 보고서에 없습니다."
                }
            }
            require(
                plan.planSha256 ==
                    dev.sixbridge.core.conversion.ConversionPlanProof.digestDecisions(
                        before,
                        selectedDecisions,
                    ),
            ) {
                "변환 계획 proof가 보고서와 일치하지 않습니다."
            }
            plan.selectedAdapters.forEach {
                require(draft.adapterVersions[it.id] == it.version) {
                    "draft adapter version이 계획과 일치하지 않습니다."
                }
            }
            val selected = plan.selectedAdapters.map { it.id to it.version }
            val applied = after.adapterDecisions.filter { it.applied }
                .map { it.id to it.version }
            require(applied.sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second }) ==
                selected.sortedWith(compareBy<Pair<String, Int>> { it.first }.thenBy { it.second })
            ) {
                "변환 후 적용 어댑터가 계획과 일치하지 않습니다."
            }
            val expectedState = if (selected.isEmpty()) {
                dev.sixbridge.core.conversion.ConversionState.PASSTHROUGH
            } else {
                dev.sixbridge.core.conversion.ConversionState.CONVERTED
            }
            require(after.conversionState == expectedState) {
                "변환 후 conversionState가 계획과 일치하지 않습니다."
            }
            val actualSourceSha256 = sourceEvidence.actualSha256
            if (selected.isEmpty()) {
                require(
                    before.sourceSha256 == actualSourceSha256 &&
                        after.sourceSha256 == before.sourceSha256,
                ) {
                    "passthrough 전후 보고서는 동일한 실제 source를 가리켜야 합니다."
                }
                require(
                    before.conversionState ==
                        dev.sixbridge.core.conversion.ConversionState.PASSTHROUGH &&
                        before.convertedExtensionId == null,
                ) {
                    "passthrough 변환 전 보고서는 미변환 상태여야 합니다."
                }
                require(after.extensionVersion == input.originalExtensionVersion) {
                    "passthrough 결과 version이 원본과 일치하지 않습니다."
                }
                require(after.convertedExtensionId == input.originalExtensionId) {
                    "passthrough 결과 extension ID가 원본과 일치하지 않습니다."
                }
                val expectedAfter = before.copy(
                    convertedExtensionId = input.originalExtensionId,
                )
                require(expectedAfter == after) {
                    "passthrough 전후 보고서의 분석 의미가 일치하지 않습니다."
                }
            } else {
                require(after.convertedExtensionId != null) {
                    "변환 결과 extension ID가 필요합니다."
                }
            }
            if (sourceKind == DraftSourceKind.CRX) {
                require(selected.isEmpty() &&
                    input.inputKind in setOf(
                        dev.sixbridge.core.input.ExtensionInputKind.CRX3,
                        dev.sixbridge.core.input.ExtensionInputKind.CHROME_WEB_STORE,
                    )
                ) {
                    "source.crx는 CRX passthrough에만 사용할 수 있습니다."
                }
            }
            require(after.sourceSha256 == actualSourceSha256) {
                "변환 후 보고서 source가 실제 source entry와 일치하지 않습니다."
            }
        }

        private fun validateV2Semantics(
            draft: DraftMetadata,
            input: DraftInputMetadata,
            sourceKind: DraftSourceKind,
            sourceEvidence: DraftSourceEvidence,
            plan: DraftConversionPlan,
            before: dev.sixbridge.core.compat.CompatibilityReport,
            after: dev.sixbridge.core.compat.CompatibilityReport,
        ) {
            before.validate()
            after.validate()
            require(draft.sourceIdentity == input.sourceIdentity) {
                "draft와 input sourceIdentity가 일치하지 않습니다."
            }
            val actualOriginalSha256 = sourceEvidence.actualSha256
            require(
                input.originalSourceSha256 == actualOriginalSha256 &&
                    before.sourceSha256 == actualOriginalSha256,
            ) {
                "schema v2 source entry와 원본 provenance가 일치하지 않습니다."
            }
            require(before.browserProfileSha256 == input.browserProfileSha256) {
                "변환 전 보고서가 browser profile과 일치하지 않습니다."
            }
            require(before.extensionVersion == input.originalExtensionVersion) {
                "변환 전 보고서가 원본 extension version과 일치하지 않습니다."
            }
            require(before.originalExtensionId == input.originalExtensionId) {
                "변환 전 보고서가 원본 extension ID와 일치하지 않습니다."
            }
            require(after.browserProfileSha256 == input.browserProfileSha256) {
                "변환 후 보고서가 browser profile과 일치하지 않습니다."
            }
            require(after.originalExtensionId == input.originalExtensionId) {
                "변환 후 보고서가 원본 extension ID와 일치하지 않습니다."
            }
            require(
                plan.reportSourceSha256 == before.sourceSha256 &&
                    plan.browserProfileSha256 == before.browserProfileSha256 &&
                    plan.reportSha256 == SixDraftJson.compatibilitySha256(before),
            ) {
                "변환 계획 provenance가 변환 전 보고서와 일치하지 않습니다."
            }
            val beforeDecisions = before.adapterDecisions.associateBy {
                it.id to it.version
            }
            val selectedDecisions = plan.selectedAdapters.map { selected ->
                requireNotNull(beforeDecisions[selected.id to selected.version]) {
                    "변환 계획 어댑터가 변환 전 보고서에 없습니다."
                }
            }
            require(
                plan.planSha256 ==
                    dev.sixbridge.core.conversion.ConversionPlanProof.digestDecisions(
                        before,
                        selectedDecisions,
                    ),
            ) {
                "변환 계획 proof가 보고서와 일치하지 않습니다."
            }
            require(
                draft.adapterVersions ==
                    plan.selectedAdapters.associate { it.id to it.version },
            ) {
                "draft adapter version 집합이 계획과 일치하지 않습니다."
            }
            val selected = plan.selectedAdapters.map { it.id to it.version }
            val applied = after.adapterDecisions
                .filter { it.applied }
                .map { it.id to it.version }
            require(
                applied.sortedWith(ADAPTER_KEY_ORDER) ==
                    selected.sortedWith(ADAPTER_KEY_ORDER),
            ) {
                "변환 후 적용 어댑터가 계획과 일치하지 않습니다."
            }
            val expectedState = if (selected.isEmpty()) {
                dev.sixbridge.core.conversion.ConversionState.PASSTHROUGH
            } else {
                dev.sixbridge.core.conversion.ConversionState.CONVERTED
            }
            require(after.conversionState == expectedState) {
                "변환 후 conversionState가 계획과 일치하지 않습니다."
            }
            if (selected.isEmpty()) {
                require(after.sourceSha256 == actualOriginalSha256) {
                    "schema v2 passthrough 결과가 원본 source와 다릅니다."
                }
                require(
                    before.conversionState ==
                        dev.sixbridge.core.conversion.ConversionState.PASSTHROUGH &&
                        before.convertedExtensionId == null,
                ) {
                    "passthrough 변환 전 보고서는 미변환 상태여야 합니다."
                }
                require(after.extensionVersion == input.originalExtensionVersion) {
                    "passthrough 결과 version이 원본과 일치하지 않습니다."
                }
                val passthroughExtensionId = input.originalExtensionId
                    .takeIf { sourceKind == DraftSourceKind.CRX }
                require(
                    before.copy(
                        originalExtensionId = input.originalExtensionId,
                        convertedExtensionId = passthroughExtensionId,
                    ) ==
                        after,
                ) {
                    "passthrough 전후 보고서의 분석 의미가 일치하지 않습니다."
                }
            } else {
                require(after.convertedExtensionId == null) {
                    "schema v2 key-neutral 변환 결과에는 최종 extension ID가 없습니다."
                }
            }
            if (sourceKind == DraftSourceKind.CRX) {
                require(
                    input.inputKind in setOf(
                        dev.sixbridge.core.input.ExtensionInputKind.CRX3,
                        dev.sixbridge.core.input.ExtensionInputKind.CHROME_WEB_STORE,
                    ),
                ) {
                    "source.crx input kind가 올바르지 않습니다."
                }
                val proofId = requireNotNull(sourceEvidence.crxExtensionId) {
                    "source.crx proof ID가 없습니다."
                }
                require(input.originalExtensionId == proofId) {
                    "source.crx proof ID가 원본 extension ID와 다릅니다."
                }
            } else {
                require(
                    input.inputKind in setOf(
                        dev.sixbridge.core.input.ExtensionInputKind.ZIP,
                        dev.sixbridge.core.input.ExtensionInputKind.SOURCE_DIRECTORY,
                    ),
                ) {
                    "source.zip input kind가 올바르지 않습니다."
                }
            }
        }

        private fun validateV3Semantics(
            draft: DraftMetadata,
            input: DraftInputMetadata,
            sourceKind: DraftSourceKind,
            sourceEvidence: DraftSourceEvidence,
            plan: DraftConversionPlan,
            before: dev.sixbridge.core.compat.CompatibilityReport,
            after: dev.sixbridge.core.compat.CompatibilityReport,
        ) {
            before.validate()
            after.validate()
            require(sourceKind == DraftSourceKind.PREPARED_CRX) {
                "schema v3에는 prepared.crx가 필요합니다."
            }
            require(draft.sourceIdentity == input.sourceIdentity) {
                "draft와 input sourceIdentity가 일치하지 않습니다."
            }
            require(before.sourceSha256 == input.originalSourceSha256) {
                "schema v3 원본 provenance가 변환 전 보고서와 다릅니다."
            }
            require(
                before.browserProfileSha256 == input.browserProfileSha256 &&
                    after.browserProfileSha256 == input.browserProfileSha256,
            ) {
                "schema v3 browser profile provenance가 일치하지 않습니다."
            }
            require(
                before.extensionVersion == input.originalExtensionVersion &&
                    before.originalExtensionId == input.originalExtensionId &&
                    after.originalExtensionId == input.originalExtensionId,
            ) {
                "schema v3 원본 extension provenance가 일치하지 않습니다."
            }
            require(
                after.sourceSha256 == sourceEvidence.actualSha256 &&
                    after.convertedExtensionId == sourceEvidence.crxExtensionId,
            ) {
                "prepared CRX proof가 변환 후 source 또는 extension ID와 다릅니다."
            }
            require(
                plan.reportSourceSha256 == before.sourceSha256 &&
                    plan.browserProfileSha256 == before.browserProfileSha256 &&
                    plan.reportSha256 == SixDraftJson.compatibilitySha256(before),
            ) {
                "schema v3 변환 계획 provenance가 변환 전 보고서와 다릅니다."
            }
            val decisions = before.adapterDecisions.associateBy { it.id to it.version }
            val selectedDecisions = plan.selectedAdapters.map { selected ->
                requireNotNull(decisions[selected.id to selected.version]) {
                    "schema v3 변환 계획 어댑터가 변환 전 보고서에 없습니다."
                }
            }
            require(
                plan.planSha256 ==
                    dev.sixbridge.core.conversion.ConversionPlanProof.digestDecisions(
                        before,
                        selectedDecisions,
                    ),
            ) {
                "schema v3 변환 계획 proof가 일치하지 않습니다."
            }
            require(
                draft.adapterVersions ==
                    plan.selectedAdapters.associate { it.id to it.version },
            ) {
                "schema v3 adapter version 집합이 계획과 다릅니다."
            }
            val applied = after.adapterDecisions.filter { it.applied }
                .map { it.id to it.version }
            val selected = plan.selectedAdapters.map { it.id to it.version }
            require(
                applied.sortedWith(ADAPTER_KEY_ORDER) ==
                    selected.sortedWith(ADAPTER_KEY_ORDER),
            ) {
                "schema v3 적용 어댑터가 계획과 다릅니다."
            }
            val expectedState = if (selected.isEmpty()) {
                dev.sixbridge.core.conversion.ConversionState.PASSTHROUGH
            } else {
                dev.sixbridge.core.conversion.ConversionState.CONVERTED
            }
            require(after.conversionState == expectedState) {
                "schema v3 conversionState가 계획과 다릅니다."
            }
        }

        private fun sourceEvidence(
            sourceKind: DraftSourceKind,
            source: ByteArray,
        ): DraftSourceEvidence = if (
            sourceKind == DraftSourceKind.CRX ||
            sourceKind == DraftSourceKind.PREPARED_CRX
        ) {
            val verified = Crx3Verifier.verify(source)
            DraftSourceEvidence(
                actualSha256 = verified.zipSha256,
                crxExtensionId = verified.extensionId,
            )
        } else {
            DraftSourceEvidence(
                actualSha256 = DraftSignature.sha256(source),
                crxExtensionId = null,
            )
        }

        private val ADAPTER_KEY_ORDER =
            compareBy<Pair<String, Int>> { it.first }.thenBy { it.second }
    }
}

sealed interface VerifiedSixDraft {
    val payload: SixDraftPayload
    val signerFingerprint: String
    val signerPublicKey: ByteArray
    val entryNames: Set<String>
    fun openSourceStream(): InputStream
}

internal object DraftSourceStreams {
    private const val BUFFER_SIZE = 64 * 1024

    fun contentEquals(first: InputStream, second: InputStream): Boolean =
        first.use { left ->
            second.use { right ->
                val leftBuffer = ByteArray(BUFFER_SIZE)
                val rightBuffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val leftCount = fill(left, leftBuffer)
                    val rightCount = fill(right, rightBuffer)
                    if (leftCount != rightCount) return@use false
                    if (leftCount < 0) return@use true
                    if (!leftBuffer.contentEquals(
                            rightBuffer,
                            0,
                            leftCount,
                        )
                    ) {
                        return@use false
                    }
                }
                @Suppress("UNREACHABLE_CODE")
                false
            }
        }

    private fun fill(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val count = input.read(buffer, total, buffer.size - total)
            require(count != 0) {
                "SixDraft source stream 읽기가 진행되지 않습니다."
            }
            if (count < 0) break
            total += count
        }
        return if (total == 0) -1 else total
    }

    private fun ByteArray.contentEquals(
        other: ByteArray,
        offset: Int,
        length: Int,
    ): Boolean {
        for (index in offset until offset + length) {
            if (this[index] != other[index]) return false
        }
        return true
    }
}

class InvalidSixDraftException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object SixDraft {
    fun createBytes(
        payload: SixDraftPayload,
        signingKey: KeyPair,
    ): ByteArray = invalidDraftBoundary("SixDraft를 만들 수 없습니다.") {
        val entries = signedEntries(payload, signingKey)
        writeStored(entries)
    }

    fun write(
        output: Path,
        payload: SixDraftPayload,
        signingKey: KeyPair,
    ): Path = writeInternal(output, payload, signingKey, null)

    @JvmSynthetic
    internal fun writeGuarded(
        output: Path,
        payload: SixDraftPayload,
        signingKey: KeyPair,
        expectedGuard: PublicationGuard,
    ): Path = writeInternal(output, payload, signingKey, expectedGuard)

    private fun writeInternal(
        output: Path,
        payload: SixDraftPayload,
        signingKey: KeyPair,
        expectedGuard: PublicationGuard?,
    ): Path {
        return invalidDraftBoundary("SixDraft 파일을 쓸 수 없습니다.") {
            val entries = signedEntries(payload, signingKey)
            val digest = MessageDigest.getInstance("SHA-256")
            val counter = CountingOutputStream(
                DigestOutputStream(OutputStream.nullOutputStream(), digest),
            )
            writeStored(entries, counter)
            val expectedHash = digest.digest().joinToString("") {
                "%02x".format(it)
            }
            val absolute = output.toAbsolutePath().normalize()
            SecureBuildPublisher.publishSingleStream(
                output = absolute,
                expectedSize = counter.count,
                expectedSha256 = expectedHash,
                expectedGuard = expectedGuard,
            ) { channel ->
                val raw = Channels.newOutputStream(channel)
                writeStored(
                    entries,
                    object : OutputStream() {
                        override fun write(value: Int) = raw.write(value)
                        override fun write(bytes: ByteArray, offset: Int, length: Int) =
                            raw.write(bytes, offset, length)
                        override fun flush() = raw.flush()
                        override fun close() = flush()
                    },
                )
            }
            absolute
        }
    }

    fun read(input: Path): VerifiedSixDraft =
        invalidDraftBoundary("SixDraft 파일을 읽을 수 없습니다.") {
            val absolute = input.toAbsolutePath().normalize()
            val before = pathState(absolute)
            require(before.size <= MAX_ARCHIVE_SIZE) {
                "SixDraft archive 크기가 제한을 초과합니다."
            }
            require(before.size <= Int.MAX_VALUE) {
                "SixDraft archive를 메모리에서 검증할 수 없습니다."
            }
            val firstDigest = MessageDigest.getInstance("SHA-256")
            val bytes = FileChannel.open(absolute, READ, NOFOLLOW_LINKS).use { channel ->
                require(pathState(absolute) == before && channel.size() == before.size) {
                    "SixDraft 파일이 읽기 전에 변경되었습니다."
                }
                val buffer = ByteBuffer.allocate(before.size.toInt())
                while (buffer.hasRemaining()) {
                    val start = buffer.position()
                    val count = channel.read(buffer)
                    require(count >= 0) {
                        "SixDraft 파일을 완전히 읽지 못했습니다."
                    }
                    if (count > 0) {
                        firstDigest.update(buffer.array(), start, count)
                    }
                }
                require(channel.read(ByteBuffer.allocate(1)) < 0) {
                    "SixDraft 파일이 읽는 동안 커졌습니다."
                }
                require(pathState(absolute) == before) {
                    "SixDraft 파일이 읽는 동안 변경되었습니다."
                }
                buffer.array()
            }
            require(pathState(absolute) == before) {
                "SixDraft 파일이 읽는 동안 변경되었습니다."
            }
            verifyStablePathHash(absolute, before, firstDigest.digest())
            readVerified(bytes)
        }

    fun read(bytes: ByteArray): VerifiedSixDraft =
        invalidDraftBoundary("SixDraft bytes를 읽을 수 없습니다.") {
            require(bytes.size.toLong() <= MAX_ARCHIVE_SIZE) {
                "SixDraft archive 크기가 제한을 초과합니다."
            }
            readVerified(bytes)
        }

    fun readControlled(
        input: Path,
        control: AnalysisControl,
    ): ControlledSixDraft = StrictDraftPathProbe.readControlled(
        input.toAbsolutePath().normalize(),
        MAX_ARCHIVE_SIZE,
        control,
    )

    fun probe(input: Path, maximumBytes: Long): SixDraftProbe =
        StrictDraftPathProbe.probe(input, maximumBytes)

    private fun readVerified(bytes: ByteArray): VerifiedSixDraft {
        require(bytes.size.toLong() <= MAX_ARCHIVE_SIZE) {
            "SixDraft archive 크기가 제한을 초과합니다."
        }
        val entries = StrictDraftArchive.read(bytes)
        validateEntrySet(entries.keys)
        validateEntrySizes(entries)
        val sourceKind = when {
            "source.zip" in entries -> DraftSourceKind.ZIP
            "source.crx" in entries -> DraftSourceKind.CRX
            "prepared.crx" in entries -> DraftSourceKind.PREPARED_CRX
            else -> throw IllegalArgumentException("SixDraft source entry가 없습니다.")
        }
        val signature = SixDraftJson.decodeSignature(
            entries.getValue(DraftSignature.ENTRY_NAME),
        )
        require(DraftSignature.verify(entries, signature)) {
            "SixDraft Ed25519 전달 서명이 일치하지 않습니다."
        }
        val payload = SixDraftPayload.decode(
            draftJson = entries.getValue("draft.json"),
            inputJson = entries.getValue("input.json"),
            sourceKind = sourceKind,
            source = entries.getValue(sourceKind.entryName),
            conversionPlanJson = entries.getValue("conversion-plan.json"),
            compatibilityBeforeJson = entries.getValue("compatibility-before.json"),
            compatibilityAfterJson = entries.getValue("compatibility-after.json"),
            expectedJson = entries.getValue("expected.json"),
        )
        return VerifiedImpl(
            payload = payload,
            signerFingerprint = DraftSignature.fingerprint(signature.publicKey),
            signerPublicKey = signature.publicKey,
            names = entries.keys,
        )
    }

    private fun pathState(path: Path): DraftPathState {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(
            attributes.isRegularFile &&
                !attributes.isSymbolicLink &&
                attributes.fileKey() != null,
        ) {
            "SixDraft 입력은 identity가 확인되는 일반 파일이어야 합니다."
        }
        val links = (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number)
            .toLong()
        require(links == 1L) {
            "SixDraft 입력 hardlink는 허용되지 않습니다."
        }
        return DraftPathState(
            fileKey = requireNotNull(attributes.fileKey()),
            device = (Files.getAttribute(path, "unix:dev", NOFOLLOW_LINKS) as Number)
                .toLong(),
            inode = (Files.getAttribute(path, "unix:ino", NOFOLLOW_LINKS) as Number)
                .toLong(),
            links = links,
            size = attributes.size(),
            modified = attributes.lastModifiedTime().toMillis(),
            access = WorkspacePermissionPolicy.captureState(path),
        )
    }

    private fun verifyStablePathHash(
        path: Path,
        expected: DraftPathState,
        firstHash: ByteArray,
    ) {
        require(pathState(path) == expected) {
            "SixDraft 파일이 두 번째 읽기 전에 변경되었습니다."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            require(pathState(path) == expected && channel.size() == expected.size) {
                "SixDraft 파일이 두 번째 open 중 변경되었습니다."
            }
            val buffer = ByteBuffer.allocate(64 * 1024)
            var total = 0L
            while (true) {
                buffer.clear()
                val count = channel.read(buffer)
                if (count < 0) break
                require(count > 0) {
                    "SixDraft 파일의 두 번째 읽기가 진행되지 않습니다."
                }
                total += count
                require(total <= expected.size && total <= MAX_ARCHIVE_SIZE) {
                    "SixDraft 파일이 두 번째 읽기 중 커졌습니다."
                }
                digest.update(buffer.array(), 0, count)
            }
            require(total == expected.size && pathState(path) == expected) {
                "SixDraft 파일이 두 번째 읽기 중 변경되었습니다."
            }
        }
        require(pathState(path) == expected) {
            "SixDraft 파일이 두 번째 읽기 후 변경되었습니다."
        }
        require(firstHash.contentEquals(digest.digest())) {
            "SixDraft 파일이 경로 재검증 중 교체되었습니다."
        }
    }

    private fun signedEntries(
        payload: SixDraftPayload,
        signingKey: KeyPair,
    ): LinkedHashMap<String, ByteArray> {
        val entries = payload.entries()
        validateUnsignedEntrySet(entries.keys)
        validateEntrySizes(entries)
        val signature = DraftSignature.sign(entries, signingKey)
        entries[DraftSignature.ENTRY_NAME] = SixDraftJson.encodeSignature(signature)
        validateEntrySet(entries.keys)
        validateEntrySizes(entries)
        return entries
    }

    private fun writeStored(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { writeStored(entries, it) }.toByteArray()

    private fun writeStored(
        entries: Map<String, ByteArray>,
        output: OutputStream,
    ) {
        ZipOutputStream(output).use { zip ->
                entries.toSortedMap().forEach { (name, bytes) ->
                    val crc = CRC32().apply { update(bytes) }
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            time = 0L
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            this.crc = crc.value
                        },
                    )
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
    }

    private fun validateEntrySet(names: Set<String>) {
        require(names.size <= MAX_ENTRIES) {
            "SixDraft entry 수가 제한을 초과합니다."
        }
        val sourceNames = names.intersect(SOURCE_ENTRIES)
        require(sourceNames.size == 1) {
            "SixDraft는 source.zip 또는 source.crx 중 하나만 포함해야 합니다."
        }
        val expected = METADATA_ENTRIES + sourceNames.single()
        require(names == expected) {
            "SixDraft entry allowlist가 일치하지 않습니다: ${names.sorted()}"
        }
    }

    private fun validateUnsignedEntrySet(names: Set<String>) {
        val sourceNames = names.intersect(SOURCE_ENTRIES)
        require(sourceNames.size == 1) {
            "SixDraft는 source.zip 또는 source.crx 중 하나만 포함해야 합니다."
        }
        val expected = (METADATA_ENTRIES - DraftSignature.ENTRY_NAME) +
            sourceNames.single()
        require(names == expected) {
            "SixDraft unsigned entry allowlist가 일치하지 않습니다: ${names.sorted()}"
        }
    }

    private fun validateEntrySizes(entries: Map<String, ByteArray>) {
        var total = 0L
        entries.forEach { (name, bytes) ->
            val size = bytes.size.toLong()
            val maximum = if (name in SOURCE_ENTRIES) {
                MAX_SOURCE_SIZE
            } else {
                MAX_METADATA_SIZE
            }
            require(size <= maximum) {
                "SixDraft entry 크기가 제한을 초과합니다: $name"
            }
            require(size <= MAX_TOTAL_SIZE - total) {
                "SixDraft 전체 압축 해제 크기가 제한을 초과합니다."
            }
            total += size
        }
    }

    private inline fun <T> invalidDraftBoundary(
        message: String,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: InvalidSixDraftException) {
        throw error
    } catch (error: Exception) {
        throw InvalidSixDraftException("$message ${error.message}", error)
    }

    internal val SOURCE_ENTRIES = setOf(
        "source.zip",
        "source.crx",
        "prepared.crx",
    )
    internal val METADATA_ENTRIES = setOf(
        "compatibility-after.json",
        "compatibility-before.json",
        "conversion-plan.json",
        DraftSignature.ENTRY_NAME,
        "draft.json",
        "expected.json",
        "input.json",
    )
    internal const val MAX_ENTRIES = 8
    internal const val MAX_METADATA_SIZE = 1L * 1024 * 1024
    internal const val MAX_SOURCE_SIZE = 512L * 1024 * 1024
    internal const val MAX_TOTAL_SIZE = 520L * 1024 * 1024
    internal const val MAX_ARCHIVE_SIZE = MAX_TOTAL_SIZE + 16L * 1024 * 1024

    private data class DraftPathState(
        val fileKey: Any,
        val device: Long,
        val inode: Long,
        val links: Long,
        val size: Long,
        val modified: Long,
        val access: WorkspaceAccessState,
    )

    private class VerifiedImpl(
        override val payload: SixDraftPayload,
        override val signerFingerprint: String,
        signerPublicKey: ByteArray,
        names: Set<String>,
    ) : VerifiedSixDraft {
        private val stablePublicKey = signerPublicKey.copyOf()
        override val signerPublicKey: ByteArray
            get() = stablePublicKey.copyOf()
        override val entryNames: Set<String> = Collections.unmodifiableSet(
            LinkedHashSet(names.sorted()),
        )

        override fun openSourceStream(): InputStream = payload.openSourceStream()
    }

    private class CountingOutputStream(
        private val delegate: OutputStream,
    ) : OutputStream() {
        var count: Long = 0L
            private set

        override fun write(value: Int) {
            delegate.write(value)
            count++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            delegate.write(bytes, offset, length)
            count += length
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }
}

private object StrictDraftArchive {
    fun read(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        require(bytes.size >= EOCD_SIZE) {
            "SixDraft ZIP 중앙 디렉터리를 찾지 못했습니다."
        }
        val eocd = bytes.size - EOCD_SIZE
        require(le32(bytes, eocd) == EOCD_SIGNATURE) {
            "SixDraft ZIP 중앙 디렉터리를 찾지 못했습니다."
        }
        require(le16(bytes, eocd + 4) == 0 && le16(bytes, eocd + 6) == 0) {
            "SixDraft multi-disk ZIP은 허용되지 않습니다."
        }
        val diskEntries = le16(bytes, eocd + 8)
        val entryCount = le16(bytes, eocd + 10)
        require(diskEntries == entryCount && entryCount == SixDraft.MAX_ENTRIES) {
            "SixDraft ZIP entry 수가 정확하지 않습니다."
        }
        require(le16(bytes, eocd + 20) == 0) {
            "SixDraft ZIP comment는 허용되지 않습니다."
        }
        val centralSize = unsigned32(bytes, eocd + 12)
        val centralOffset = unsigned32(bytes, eocd + 16)
        require(
            centralSize != ZIP64_SENTINEL &&
                centralOffset != ZIP64_SENTINEL &&
                centralOffset <= Int.MAX_VALUE &&
                centralSize <= Int.MAX_VALUE &&
                centralOffset + centralSize == eocd.toLong(),
        ) {
            "SixDraft ZIP64 또는 손상된 중앙 디렉터리를 거부했습니다."
        }

        val central = readCentral(
            bytes,
            centralOffset.toInt(),
            centralSize.toInt(),
            entryCount,
        )
        val local = readLocal(bytes, centralOffset.toInt())
        require(local.size == central.size) {
            "SixDraft 로컬/중앙 entry 수가 일치하지 않습니다."
        }
        val verified = central.zip(local).map { (expected, actual) ->
            require(expected.matches(actual)) {
                "SixDraft 로컬/중앙 entry가 일치하지 않습니다: ${expected.name}"
            }
            expected.copy(dataOffset = actual.dataOffset)
        }

        var total = 0L
        val result = linkedMapOf<String, ByteArray>()
        verified.forEach { entry ->
            val isSource = entry.name in SixDraft.SOURCE_ENTRIES
            if (isSource) {
                require(entry.method == ZipEntry.STORED) {
                    "SixDraft source entry는 STORED method여야 합니다: ${entry.name}"
                }
            }
            val maximum = if (isSource) {
                SixDraft.MAX_SOURCE_SIZE
            } else {
                SixDraft.MAX_METADATA_SIZE
            }
            require(entry.uncompressedSize <= maximum) {
                "SixDraft entry 크기가 제한을 초과합니다: ${entry.name}"
            }
            require(entry.uncompressedSize <= SixDraft.MAX_TOTAL_SIZE - total) {
                "SixDraft 전체 압축 해제 크기가 제한을 초과합니다."
            }
            total += entry.uncompressedSize
            require(
                entry.uncompressedSize == 0L ||
                    entry.compressedSize > 0L,
            ) {
                "SixDraft entry 압축 크기가 올바르지 않습니다: ${entry.name}"
            }
            require(
                entry.compressedSize == 0L ||
                    entry.uncompressedSize <=
                    entry.compressedSize * MAX_COMPRESSION_RATIO,
            ) {
                "SixDraft entry 압축 비율이 제한을 초과합니다: ${entry.name}"
            }
            val decoded = decodeEntry(bytes, entry)
            require(decoded.size.toLong() == entry.uncompressedSize) {
                "SixDraft entry 압축 해제 크기가 일치하지 않습니다: ${entry.name}"
            }
            val crc = CRC32().apply { update(decoded) }.value
            require(crc == entry.crc) {
                "SixDraft entry CRC가 일치하지 않습니다: ${entry.name}"
            }
            require(result.put(entry.name, decoded) == null) {
                "SixDraft 중복 entry를 거부했습니다: ${entry.name}"
            }
        }
        return result
    }

    private fun readCentral(
        bytes: ByteArray,
        offset: Int,
        size: Int,
        count: Int,
    ): List<CentralEntry> {
        val end = offset + size
        var cursor = offset
        val names = mutableSetOf<String>()
        val entries = ArrayList<CentralEntry>(count)
        repeat(count) {
            requireRange(bytes, cursor, CENTRAL_HEADER_SIZE)
            require(le32(bytes, cursor) == CENTRAL_SIGNATURE) {
                "SixDraft 중앙 directory entry가 손상되었습니다."
            }
            val flags = le16(bytes, cursor + 8)
            validateFlags(flags)
            val method = le16(bytes, cursor + 10)
            validateMethod(method)
            val compressedSize = unsigned32(bytes, cursor + 20)
            val uncompressedSize = unsigned32(bytes, cursor + 24)
            require(
                compressedSize != ZIP64_SENTINEL &&
                    uncompressedSize != ZIP64_SENTINEL,
            ) {
                "SixDraft entry의 unknown 또는 ZIP64 크기를 거부했습니다."
            }
            val nameLength = le16(bytes, cursor + 28)
            val extraLength = le16(bytes, cursor + 30)
            val commentLength = le16(bytes, cursor + 32)
            require(le16(bytes, cursor + 34) == 0) {
                "SixDraft entry disk 번호가 올바르지 않습니다."
            }
            val bodySize = nameLength.toLong() + extraLength + commentLength
            require(bodySize <= end.toLong() - cursor - CENTRAL_HEADER_SIZE) {
                "SixDraft 중앙 directory entry 길이가 손상되었습니다."
            }
            val name = decodeName(bytes, cursor + CENTRAL_HEADER_SIZE, nameLength)
            validateName(name)
            validateExtra(
                bytes,
                cursor + CENTRAL_HEADER_SIZE + nameLength,
                extraLength,
            )
            require(names.add(name)) {
                "SixDraft 중앙 directory 중복 entry를 거부했습니다: $name"
            }
            val madeBy = le16(bytes, cursor + 4)
            val host = madeBy ushr 8
            val externalAttributes = unsigned32(bytes, cursor + 38)
            require(externalAttributes and DOS_SPECIAL_ATTRIBUTES == 0L) {
                "SixDraft DOS directory 또는 특수 entry를 거부했습니다: $name"
            }
            if (host == UNIX_HOST) {
                val fileType = ((externalAttributes ushr 16).toInt()) and UNIX_TYPE_MASK
                require(fileType == 0 || fileType == UNIX_REGULAR) {
                    "SixDraft symlink 또는 특수 entry를 거부했습니다: $name"
                }
            }
            val localOffset = unsigned32(bytes, cursor + 42)
            require(localOffset != ZIP64_SENTINEL && localOffset <= Int.MAX_VALUE) {
                "SixDraft local entry offset이 올바르지 않습니다."
            }
            if (method == ZipEntry.STORED) {
                require(compressedSize == uncompressedSize) {
                    "SixDraft STORED entry 크기가 일치하지 않습니다: $name"
                }
            }
            entries += CentralEntry(
                name = name,
                flags = flags,
                method = method,
                crc = unsigned32(bytes, cursor + 16),
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                localOffset = localOffset.toInt(),
                dataOffset = -1,
            )
            cursor += CENTRAL_HEADER_SIZE + bodySize.toInt()
        }
        require(cursor == end) {
            "SixDraft 중앙 directory 크기가 실제 entry와 일치하지 않습니다."
        }
        require(entries.map(CentralEntry::name) == entries.map(CentralEntry::name).sorted()) {
            "SixDraft 중앙 directory entry는 이름순이어야 합니다."
        }
        return entries
    }

    private fun readLocal(bytes: ByteArray, centralOffset: Int): List<CentralEntry> {
        var cursor = 0
        val names = mutableSetOf<String>()
        val entries = mutableListOf<CentralEntry>()
        while (cursor < centralOffset) {
            require(entries.size < SixDraft.MAX_ENTRIES) {
                "SixDraft 로컬 entry 수가 제한을 초과합니다."
            }
            requireRange(bytes, cursor, LOCAL_HEADER_SIZE)
            require(le32(bytes, cursor) == LOCAL_SIGNATURE) {
                "SixDraft orphan 또는 손상된 로컬 entry를 거부했습니다."
            }
            val flags = le16(bytes, cursor + 6)
            validateFlags(flags)
            val method = le16(bytes, cursor + 8)
            validateMethod(method)
            val compressedSize = unsigned32(bytes, cursor + 18)
            val uncompressedSize = unsigned32(bytes, cursor + 22)
            require(
                compressedSize != ZIP64_SENTINEL &&
                    uncompressedSize != ZIP64_SENTINEL,
            ) {
                "SixDraft 로컬 entry의 unknown 또는 ZIP64 크기를 거부했습니다."
            }
            val nameLength = le16(bytes, cursor + 26)
            val extraLength = le16(bytes, cursor + 28)
            val headerBody = nameLength.toLong() + extraLength
            require(headerBody <= centralOffset.toLong() - cursor - LOCAL_HEADER_SIZE) {
                "SixDraft 로컬 entry 길이가 손상되었습니다."
            }
            val name = decodeName(bytes, cursor + LOCAL_HEADER_SIZE, nameLength)
            validateName(name)
            validateExtra(
                bytes,
                cursor + LOCAL_HEADER_SIZE + nameLength,
                extraLength,
            )
            require(names.add(name)) {
                "SixDraft 로컬 중복 entry를 거부했습니다: $name"
            }
            val dataOffset = cursor + LOCAL_HEADER_SIZE + headerBody.toInt()
            require(compressedSize <= centralOffset.toLong() - dataOffset) {
                "SixDraft entry 데이터 길이가 손상되었습니다: $name"
            }
            entries += CentralEntry(
                name = name,
                flags = flags,
                method = method,
                crc = unsigned32(bytes, cursor + 14),
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                localOffset = cursor,
                dataOffset = dataOffset,
            )
            cursor = dataOffset + compressedSize.toInt()
        }
        require(cursor == centralOffset) {
            "SixDraft 로컬 entry와 중앙 directory 사이에 잔여 데이터가 있습니다."
        }
        require(entries.map(CentralEntry::name) == entries.map(CentralEntry::name).sorted()) {
            "SixDraft 로컬 entry는 이름순이어야 합니다."
        }
        return entries
    }

    private fun decodeEntry(bytes: ByteArray, central: CentralEntry): ByteArray {
        val compressed = bytes.copyOfRange(
            central.dataOffset,
            central.dataOffset + central.compressedSize.toInt(),
        )
        if (central.method == ZipEntry.STORED) return compressed

        val inflater = Inflater(true)
        return try {
            inflater.setInput(compressed)
            val output = ByteArrayOutputStream(
                minOf(central.uncompressedSize, 8192L).toInt(),
            )
            val buffer = ByteArray(8192)
            var written = 0L
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (error: DataFormatException) {
                    throw IllegalArgumentException(
                        "SixDraft DEFLATED entry가 손상되었습니다: ${central.name}",
                        error,
                    )
                }
                if (count == 0) {
                    require(!inflater.needsDictionary() && !inflater.needsInput()) {
                        "SixDraft DEFLATED entry를 완전히 해제할 수 없습니다: ${central.name}"
                    }
                    throw IllegalArgumentException(
                        "SixDraft DEFLATED entry가 진행되지 않습니다: ${central.name}",
                    )
                }
                require(count.toLong() <= central.uncompressedSize - written) {
                    "SixDraft entry 실제 크기가 선언값을 초과합니다: ${central.name}"
                }
                written += count
                output.write(buffer, 0, count)
            }
            require(inflater.remaining == 0) {
                "SixDraft DEFLATED entry 뒤에 잔여 데이터가 있습니다: ${central.name}"
            }
            output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun CentralEntry.matches(local: CentralEntry): Boolean =
        name == local.name &&
            flags == local.flags &&
            method == local.method &&
            crc == local.crc &&
            compressedSize == local.compressedSize &&
            uncompressedSize == local.uncompressedSize &&
            localOffset == local.localOffset &&
            local.dataOffset >= 0

    private fun validateFlags(flags: Int) {
        require(flags and ALLOWED_FLAGS.inv() == 0) {
            "SixDraft 암호화, reserved 또는 지원하지 않는 flag는 허용되지 않습니다."
        }
    }

    private fun validateExtra(bytes: ByteArray, offset: Int, length: Int) {
        requireRange(bytes, offset, length)
        var cursor = offset
        val end = offset + length
        while (cursor < end) {
            require(end - cursor >= 4) {
                "SixDraft ZIP extra field가 손상되었습니다."
            }
            val id = le16(bytes, cursor)
            val size = le16(bytes, cursor + 2)
            cursor += 4
            require(size <= end - cursor) {
                "SixDraft ZIP extra field 길이가 손상되었습니다."
            }
            require(id != ZIP64_EXTRA_ID) {
                "SixDraft ZIP64 extra field는 허용되지 않습니다."
            }
            cursor += size
        }
    }

    private fun validateMethod(method: Int) {
        require(method == ZipEntry.STORED || method == ZipEntry.DEFLATED) {
            "SixDraft ZIP 압축 method를 지원하지 않습니다."
        }
    }

    private fun validateName(name: String) {
        require(
            name.isNotEmpty() &&
                '/' !in name &&
                '\\' !in name &&
                '\u0000' !in name &&
                name != "." &&
                name != "..",
        ) {
            "SixDraft 경로 탈출 entry를 거부했습니다: $name"
        }
    }

    private fun decodeName(bytes: ByteArray, offset: Int, length: Int): String {
        requireRange(bytes, offset, length)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, length))
            .toString()
    }

    private fun requireRange(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "SixDraft ZIP 구조가 archive 경계를 벗어났습니다."
        }
    }

    private fun le16(bytes: ByteArray, offset: Int): Int {
        requireRange(bytes, offset, 2)
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun le32(bytes: ByteArray, offset: Int): Int {
        requireRange(bytes, offset, 4)
        return le16(bytes, offset) or (le16(bytes, offset + 2) shl 16)
    }

    private fun unsigned32(bytes: ByteArray, offset: Int): Long =
        le32(bytes, offset).toLong() and 0xffff_ffffL

    private data class CentralEntry(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localOffset: Int,
        val dataOffset: Int,
    )

    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val LOCAL_HEADER_SIZE = 30
    private const val CENTRAL_HEADER_SIZE = 46
    private const val EOCD_SIZE = 22
    private const val ZIP64_SENTINEL = 0xffff_ffffL
    private const val UTF8_FLAG = 0x0800
    private const val ALLOWED_FLAGS = UTF8_FLAG
    private const val ZIP64_EXTRA_ID = 0x0001
    private const val DOS_SPECIAL_ATTRIBUTES = 0x10L or 0x40L
    private const val UNIX_HOST = 3
    private const val UNIX_TYPE_MASK = 0xf000
    private const val UNIX_REGULAR = 0x8000
    private const val MAX_COMPRESSION_RATIO = 100L
}
