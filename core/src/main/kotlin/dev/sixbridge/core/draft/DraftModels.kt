package dev.sixbridge.core.draft

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.conversion.ConversionPlanProof
import dev.sixbridge.core.conversion.ForcedCompatibilityProvenance
import dev.sixbridge.core.conversion.ForceCompatibilityPolicy
import dev.sixbridge.core.conversion.ConversionAuthorization
import dev.sixbridge.core.conversion.CompatibilityForceScope
import dev.sixbridge.core.conversion.deepImmutableCopy
import dev.sixbridge.core.input.ExtensionInputKind
import java.util.Collections

class DraftMetadata private constructor(
    val schemaVersion: Int,
    val coreVersion: String,
    val adapterVersions: Map<String, Int>,
    val sourceIdentity: String,
) {
    companion object {
        fun create(
            coreVersion: String,
            adapterVersions: Map<String, Int>,
            sourceIdentity: String,
        ): DraftMetadata = create(
            schemaVersion = 1,
            coreVersion = coreVersion,
            adapterVersions = adapterVersions,
            sourceIdentity = sourceIdentity,
        )

        fun createV2(
            coreVersion: String,
            adapterVersions: Map<String, Int>,
            sourceIdentity: String,
        ): DraftMetadata = create(
            schemaVersion = 2,
            coreVersion = coreVersion,
            adapterVersions = adapterVersions,
            sourceIdentity = sourceIdentity,
        )

        fun createV3(
            coreVersion: String,
            adapterVersions: Map<String, Int>,
            sourceIdentity: String,
        ): DraftMetadata = create(
            schemaVersion = 3,
            coreVersion = coreVersion,
            adapterVersions = adapterVersions,
            sourceIdentity = sourceIdentity,
        )

        fun createV4(
            coreVersion: String,
            adapterVersions: Map<String, Int>,
            sourceIdentity: String,
        ): DraftMetadata = create(
            schemaVersion = 4,
            coreVersion = coreVersion,
            adapterVersions = adapterVersions,
            sourceIdentity = sourceIdentity,
        )

        @JvmSynthetic
        internal fun decoded(
            schemaVersion: Int,
            coreVersion: String,
            adapterVersions: Map<String, Int>,
            sourceIdentity: String,
        ): DraftMetadata = create(
            schemaVersion = schemaVersion,
            coreVersion = coreVersion,
            adapterVersions = adapterVersions,
            sourceIdentity = sourceIdentity,
        )

        private fun create(
            schemaVersion: Int,
            coreVersion: String,
            adapterVersions: Map<String, Int>,
            sourceIdentity: String,
        ): DraftMetadata {
            require(schemaVersion in 1..4) {
                "지원하지 않는 draft schemaVersion입니다."
            }
            require(coreVersion.isNotBlank()) { "coreVersion은 비어 있을 수 없습니다." }
            require(SOURCE_IDENTITY.matches(sourceIdentity)) {
                "sourceIdentity 형식이 올바르지 않습니다."
            }
            require(adapterVersions.all { (id, version) ->
                ID.matches(id) && version > 0
            }) {
                "adapterVersions 항목이 올바르지 않습니다."
            }
            return DraftMetadata(
                schemaVersion = schemaVersion,
                coreVersion = coreVersion,
                adapterVersions = Collections.unmodifiableMap(
                    adapterVersions.toSortedMap(),
                ),
                sourceIdentity = sourceIdentity,
            )
        }
    }
}

data class DraftInputMetadata(
    val inputKind: ExtensionInputKind,
    val originalSourceSha256: String,
    val browserProfileSha256: String,
    val originalExtensionId: String?,
    val originalExtensionVersion: String,
    val sourceIdentity: String,
) {
    init {
        require(SHA256.matches(originalSourceSha256)) {
            "originalSourceSha256 형식이 올바르지 않습니다."
        }
        require(SHA256.matches(browserProfileSha256)) {
            "browserProfileSha256 형식이 올바르지 않습니다."
        }
        require(originalExtensionId == null || EXTENSION_ID.matches(originalExtensionId)) {
            "originalExtensionId 형식이 올바르지 않습니다."
        }
        require(originalExtensionVersion.isNotBlank()) {
            "originalExtensionVersion은 비어 있을 수 없습니다."
        }
        require(SOURCE_IDENTITY.matches(sourceIdentity)) {
            "sourceIdentity 형식이 올바르지 않습니다."
        }
    }
}

data class DraftSelectedAdapter(
    val id: String,
    val version: Int,
) {
    init {
        require(ID.matches(id) && version > 0) {
            "선택한 어댑터 식별자가 올바르지 않습니다."
        }
    }
}

class DraftConversionPlan private constructor(
    val schemaVersion: Int,
    selectedAdapters: List<DraftSelectedAdapter>,
    val reportSourceSha256: String,
    val browserProfileSha256: String,
    val reportSha256: String,
    val planSha256: String,
    forcedCompatibility: ForcedCompatibilityProvenance?,
) {
    val selectedAdapters: List<DraftSelectedAdapter> = Collections.unmodifiableList(
        selectedAdapters.map(DraftSelectedAdapter::copy),
    )
    val forcedCompatibility: ForcedCompatibilityProvenance? =
        forcedCompatibility.deepImmutableCopy()

    companion object {
        fun create(
            report: CompatibilityReport,
            selectedAdapters: List<DraftSelectedAdapter>,
            forcedCompatibility: ForcedCompatibilityProvenance? = null,
        ): DraftConversionPlan {
            val immutable = report.deepImmutableCopy().also(CompatibilityReport::validate)
            val source = requireNotNull(immutable.sourceSha256) {
                "변환 계획 report에는 sourceSha256이 필요합니다."
            }
            val profile = requireNotNull(immutable.browserProfileSha256) {
                "변환 계획 report에는 browserProfileSha256이 필요합니다."
            }
            require(selectedAdapters.distinct().size == selectedAdapters.size) {
                "선택한 어댑터는 중복될 수 없습니다."
            }
            val decisions = immutable.adapterDecisions.associateBy { it.id to it.version }
            val selectedDecisions = selectedAdapters.map { selected ->
                requireNotNull(decisions[selected.id to selected.version]) {
                    "선택한 어댑터가 report에 없습니다: ${selected.id}@${selected.version}"
                }
            }
            val canonicalForced = forcedCompatibility?.let { forced ->
                val authorization = when (forced.scope) {
                    CompatibilityForceScope.UNKNOWN_API_USAGE ->
                        ConversionAuthorization.forceUnknownApiUsage()
                }
                ForceCompatibilityPolicy.requireProvenance(
                    immutable,
                    authorization,
                ).also {
                    require(it == forced) {
                        "강제 호환성 provenance가 report와 일치하지 않습니다."
                    }
                }
            }
            return DraftConversionPlan(
                schemaVersion = if (canonicalForced == null) 1 else 2,
                selectedAdapters = selectedAdapters.sortedWith(
                    compareBy(DraftSelectedAdapter::id, DraftSelectedAdapter::version),
                ),
                reportSourceSha256 = source,
                browserProfileSha256 = profile,
                reportSha256 = SixDraftJson.compatibilitySha256(immutable),
                planSha256 = if (canonicalForced == null) {
                    ConversionPlanProof.digestDecisions(immutable, selectedDecisions)
                } else {
                    ConversionPlanProof.digestDecisions(
                        immutable,
                        selectedDecisions,
                        canonicalForced,
                    )
                },
                forcedCompatibility = canonicalForced,
            )
        }

        internal fun decoded(
            schemaVersion: Int,
            selectedAdapters: List<DraftSelectedAdapter>,
            reportSourceSha256: String,
            browserProfileSha256: String,
            reportSha256: String,
            planSha256: String,
            forcedCompatibility: ForcedCompatibilityProvenance?,
        ): DraftConversionPlan = DraftConversionPlan(
            schemaVersion = schemaVersion,
            selectedAdapters = selectedAdapters,
            reportSourceSha256 = reportSourceSha256,
            browserProfileSha256 = browserProfileSha256,
            reportSha256 = reportSha256,
            planSha256 = planSha256,
            forcedCompatibility = forcedCompatibility,
        )
    }
}

data class DraftExpected(
    val originalSourceSha256: String,
    val transformedSourceSha256: String,
    val reportSha256: String,
    val planSha256: String,
) {
    init {
        require(
            listOf(
                originalSourceSha256,
                transformedSourceSha256,
                reportSha256,
                planSha256,
            ).all(SHA256::matches),
        ) {
            "DraftExpected hash 형식이 올바르지 않습니다."
        }
    }
}

internal val SHA256 = Regex("[0-9a-f]{64}")
internal val EXTENSION_ID = Regex("[a-p]{32}")
internal val SOURCE_IDENTITY = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
internal val ID = Regex("[a-z0-9][a-z0-9._-]{0,127}")
