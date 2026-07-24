package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.crx.ExtensionKeyManager
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.input.SourceProjectIdentityStore
import dev.sixbridge.core.profile.BrowserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.readText

class ConversionPipeline private constructor(
    localRoot: Path,
    compatibilityAnalyzer: CompatibilityAnalyzer,
) : AutoCloseable {
    private val workspace = ConversionWorkspace()
    private val analyzer = MobilePatternAnalyzer(compatibilityAnalyzer)
    private val planner = ConversionPlanner()
    private val packaging = CrxIdentityPolicy.create(localRoot)

    constructor(localRoot: Path) : this(localRoot, CompatibilityAnalyzer())

    fun analyze(
        resolved: ResolvedExtensionInput,
        profile: BrowserProfile,
    ): CompatibilityReport {
        val source = requireNotNull(resolved.sourceRoot) {
            "분석할 확장 소스를 입력에서 추출하지 못했습니다."
        }
        val snapshot = workspace.snapshot(
            source,
            requireOwnerOnlyMarker = resolved.kind == ExtensionInputKind.SOURCE_DIRECTORY,
        )
        return analyzer.analyzeArchive(snapshot.sourceZip, profile)
    }

    fun prepare(
        resolved: ResolvedExtensionInput,
        profile: BrowserProfile,
        approvedAdapterIds: Iterable<String>,
    ): ConversionPipelineResult {
        val source = requireNotNull(resolved.sourceRoot) {
            "빌드할 확장 소스를 입력에서 추출하지 못했습니다."
        }
        val approvals = approvedAdapterIds.toSet()
        val snapshot = workspace.snapshot(
            source,
            requireOwnerOnlyMarker = resolved.kind == ExtensionInputKind.SOURCE_DIRECTORY,
        )
        val before = analyzer.analyzeArchive(snapshot.sourceZip, profile)
        val plan = plan(before, approvals)
        val transformed = plan.selectedAdapters.isNotEmpty()

        val applied = if (transformed) {
            workspace.transform(snapshot, profile, plan)
        } else {
            emptyList()
        }
        requireAllApplied(plan, applied)

        val sourceZip = ExtensionZip.createBytes(
            snapshot.workingRoot,
            includeProjectMarker = true,
        )
        val rawAfter = analyzer.analyzeArchive(sourceZip, profile)
        validateCompatibility(before, rawAfter)

        if (transformed) {
            val replay = workspace.transform(snapshot, profile, plan)
            requireAllApplied(plan, replay)
            val replayedZip = ExtensionZip.createBytes(
                snapshot.workingRoot,
                includeProjectMarker = true,
            )
            if (!sourceZip.contentEquals(replayedZip)) {
                throw ConversionValidationException(
                    "adapter 재적용 결과가 결정적이지 않습니다.",
                )
            }
        }

        val afterWithAdapters = conversionReport(
            before = before,
            rawAfter = rawAfter,
            applied = applied,
            transformed = transformed,
        )
        val verificationPlan = plan(afterWithAdapters, approvals)
        if (transformed &&
            verificationPlan.selectedAdapters.any { !it.applied }
        ) {
            throw ConversionValidationException(
                "변환본 재계획에 미적용 adapter가 남았습니다.",
            )
        }
        val reportSha256 = reportDigest(
            sourceZip,
            plan.proofSha256,
            verificationPlan.proofSha256,
        )
        val packed = packaging.pack(
            resolved = resolved,
            source = snapshot.workingRoot,
            identitySource = snapshot.originalRoot,
            sourceProjectIdentity = snapshot.sourceProjectIdentity,
            markerVerified = true,
            transformed = transformed,
            profileMajorVersion = profile.majorVersion,
        )
        val after = afterWithAdapters.copy(
            originalExtensionId = packed.originalExtensionId,
            convertedExtensionId = packed.extensionId,
        ).also(CompatibilityReport::validate)
        return ConversionPipelineResult.create(
            sourceZip = sourceZip,
            compatibilityBefore = before,
            compatibilityAfter = after,
            planSha256 = plan.proofSha256,
            reportSha256 = reportSha256,
            extensionId = packed.extensionId,
            crxBytes = packed.bytes,
            logicalIdentity = packed.logicalIdentity,
            passthrough = packed.passthrough,
            convertedSourceRoot = snapshot.workingRoot,
            artifactDirectory = snapshot.workingRoot.parent.resolve("artifacts").also {
                java.nio.file.Files.createDirectory(it)
            },
        )
    }

    override fun close() {
        workspace.close()
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
        before: CompatibilityReport,
        rawAfter: CompatibilityReport,
        applied: List<AdapterDecision>,
        transformed: Boolean,
    ): CompatibilityReport {
        if (!transformed) {
            return rawAfter.copy(conversionState = ConversionState.PASSTHROUGH)
        }
        val selectedKeys = applied.mapTo(mutableSetOf()) { it.id to it.version }
        val newDecisions = rawAfter.adapterDecisions.filter {
            (it.id to it.version) !in selectedKeys
        }
        if (newDecisions.isNotEmpty()) {
            throw ConversionValidationException(
                "변환본 재분석에서 새 adapter 계획이 생겼습니다: " +
                    newDecisions.map { it.id }.sorted(),
            )
        }
        val reapplied = rawAfter.adapterDecisions
            .associateBy { it.id to it.version }
        val decisions = applied.map { decision ->
            reapplied[decision.id to decision.version]?.let { current ->
                if (current.applied ||
                    current.risk != decision.risk ||
                    current.disposition != decision.disposition ||
                    current.changes != decision.changes ||
                    current.remainingLimitations != decision.remainingLimitations
                ) {
                    throw ConversionValidationException(
                        "변환본 재분석의 adapter 근거가 원본 계획과 다릅니다: ${decision.id}",
                    )
                }
            }
            decision
        }.sortedWith(compareBy(AdapterDecision::id, AdapterDecision::version))
        return rawAfter.copy(
            conversionState = ConversionState.CONVERTED,
            adapterDecisions = decisions,
            originalExtensionId = before.manifestKeyExtensionId,
        )
    }

    private fun statusRank(status: CompatibilityStatus): Int = when (status) {
        CompatibilityStatus.COMPATIBLE -> 0
        CompatibilityStatus.PARTIAL -> 1
        CompatibilityStatus.BLOCKED -> 2
        CompatibilityStatus.UNKNOWN -> 3
    }

    private fun reportDigest(
        sourceZip: ByteArray,
        planSha256: String,
        afterPlanSha256: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.field(
            "schema",
            "sixbridge-conversion-report-v1".toByteArray(UTF_8),
        )
        digest.field("source.zip", sourceZip)
        digest.field("plan.sha256", planSha256.toByteArray(UTF_8))
        digest.field("after-plan.sha256", afterPlanSha256.toByteArray(UTF_8))
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun MessageDigest.field(name: String, value: ByteArray) {
        listOf(name.toByteArray(UTF_8), value).forEach { bytes ->
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
            update(bytes)
        }
    }

    companion object {
        @JvmSynthetic
        internal fun create(
            localRoot: Path,
            compatibilityAnalyzer: CompatibilityAnalyzer,
        ): ConversionPipeline = ConversionPipeline(
            localRoot,
            compatibilityAnalyzer,
        )
    }
}

class ConversionPipelineResult private constructor(
    sourceZip: ByteArray,
    val compatibilityBefore: CompatibilityReport,
    val compatibilityAfter: CompatibilityReport,
    val planSha256: String,
    val reportSha256: String,
    val extensionId: String,
    crxBytes: ByteArray,
    val logicalIdentity: String?,
    val passthrough: Boolean,
    @get:kotlin.jvm.JvmSynthetic
    internal val convertedSourceRoot: Path,
    @get:kotlin.jvm.JvmSynthetic
    internal val artifactDirectory: Path,
) {
    private val stableSourceZip = sourceZip.copyOf()
    private val stableCrxBytes = crxBytes.copyOf()

    val sourceZip: ByteArray
        get() = stableSourceZip.copyOf()

    val crxBytes: ByteArray
        get() = stableCrxBytes.copyOf()

    companion object {
        @JvmSynthetic
        internal fun create(
            sourceZip: ByteArray,
            compatibilityBefore: CompatibilityReport,
            compatibilityAfter: CompatibilityReport,
            planSha256: String,
            reportSha256: String,
            extensionId: String,
            crxBytes: ByteArray,
            logicalIdentity: String?,
            passthrough: Boolean,
            convertedSourceRoot: Path,
            artifactDirectory: Path,
        ): ConversionPipelineResult = ConversionPipelineResult(
            sourceZip,
            compatibilityBefore,
            compatibilityAfter,
            planSha256,
            reportSha256,
            extensionId,
            crxBytes,
            logicalIdentity,
            passthrough,
            convertedSourceRoot,
            artifactDirectory,
        )
    }
}

internal class CrxIdentityPolicy private constructor(
    private val localRoot: Path,
) {
    @JvmSynthetic
    fun pack(
        resolved: ResolvedExtensionInput,
        source: Path,
        identitySource: Path = source,
        sourceProjectIdentity: String?,
        markerVerified: Boolean,
        transformed: Boolean,
        profileMajorVersion: Int,
    ): PreparedPipelineCrx {
        val original = resolved.crxBytes
        val originalParsed = original?.let(Crx3::parse)
        if (originalParsed != null) {
            require(
                resolved.expectedExtensionId == null ||
                    resolved.expectedExtensionId == originalParsed.extensionId,
            ) {
                "입력 CRX extension ID가 기대한 ID와 다릅니다."
            }
            if (!transformed) {
                return PreparedPipelineCrxValue(
                    extensionId = originalParsed.extensionId,
                    bytes = original.copyOf(),
                    originalExtensionId = originalParsed.extensionId,
                    logicalIdentity = null,
                    passthrough = true,
                )
            }
            val identity = ExtensionKeyManager.storeConversionIdentity(
                originalParsed.extensionId,
                profileMajorVersion,
            )
            val keyManager = ExtensionKeyManager(localRoot.resolve("keystore/crx"))
            val key = keyManager.getOrCreateStoreConversionKey(
                originalParsed.extensionId,
                profileMajorVersion,
            )
            val packed = Crx3.pack(source, key)
            keyManager.persist(packed.extensionId, key)
            return PreparedPipelineCrxValue(
                extensionId = packed.extensionId,
                bytes = packed.bytes,
                originalExtensionId = originalParsed.extensionId,
                logicalIdentity = identity,
                passthrough = false,
            )
        }

        val keyManager = ExtensionKeyManager(localRoot.resolve("keystore/crx"))
        val identityStore = SourceProjectIdentityStore(
            localRoot.resolve("source-identities"),
        )
        val manifest = Json.parseToJsonElement(source.resolve("manifest.json").readText())
            as? JsonObject
            ?: throw IllegalArgumentException("manifest.json의 루트가 객체가 아닙니다.")
        val manifestName = (manifest["name"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("manifest name이 비어 있습니다.")
        val manifestKey = (manifest["key"] as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
        val key = if (manifestKey != null) {
            val publicKey = runCatching { Base64.getDecoder().decode(manifestKey) }
                .getOrElse {
                    throw IllegalArgumentException("manifest key가 올바른 Base64가 아닙니다.")
                }
            val expectedId = Crx3.extensionId(publicKey)
            val existing = keyManager.findByExtensionId(expectedId)
                ?: throw IllegalArgumentException(
                    "manifest key에 대응하는 기존 CRX 개인 키가 없습니다: $expectedId",
                )
            require(existing.public.encoded.contentEquals(publicKey)) {
                "manifest 공개 키가 기존 CRX key pair와 일치하지 않습니다."
            }
            existing
        } else {
            val projectIdentity = identityStore.resolve(
                resolved,
                identitySource,
                sourceProjectIdentity,
                markerVerified = markerVerified,
            )
            val logicalIdentity = "project-$projectIdentity"
            val manifestIdentity =
                "manifest-${sha256(manifestName.lowercase().toByteArray()).take(32)}"
            val legacyInput = when (resolved.kind) {
                ExtensionInputKind.SOURCE_DIRECTORY ->
                    source.toAbsolutePath().normalize().toString()
                ExtensionInputKind.ZIP ->
                    Path.of(resolved.originalInput).toAbsolutePath().normalize().toString()
                else -> resolved.originalInput
            }
            val legacyIdentity = "source-${sha256(legacyInput.toByteArray()).take(24)}"
            val leaf = source.fileName.toString()
            val existing = keyManager.getExisting(logicalIdentity)
                ?: listOfNotNull(
                    leaf.takeIf(EXTENSION_ID::matches),
                    manifestIdentity,
                    legacyIdentity,
                ).firstNotNullOfOrNull { legacy ->
                    keyManager.getExisting(legacy)?.takeIf {
                        identityStore.claimLegacy(legacy, projectIdentity)
                    }
                }
            (existing ?: keyManager.getOrCreate(logicalIdentity)).also {
                keyManager.persist(logicalIdentity, it)
            }
        }
        val packed = Crx3.pack(source, key)
        keyManager.persist(packed.extensionId, key)
        return PreparedPipelineCrxValue(
            extensionId = packed.extensionId,
            bytes = packed.bytes,
            originalExtensionId = manifestKey?.let {
                Crx3.extensionId(Base64.getDecoder().decode(it))
            },
            logicalIdentity = null,
            passthrough = false,
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val EXTENSION_ID = Regex("[a-p]{32}")

        @JvmSynthetic
        fun create(localRoot: Path): CrxIdentityPolicy =
            CrxIdentityPolicy(localRoot)
    }
}

internal sealed interface PreparedPipelineCrx {
    val extensionId: String
    val bytes: ByteArray
    val originalExtensionId: String?
    val logicalIdentity: String?
    val passthrough: Boolean
}

private data class PreparedPipelineCrxValue(
    override val extensionId: String,
    override val bytes: ByteArray,
    override val originalExtensionId: String?,
    override val logicalIdentity: String?,
    override val passthrough: Boolean,
) : PreparedPipelineCrx
