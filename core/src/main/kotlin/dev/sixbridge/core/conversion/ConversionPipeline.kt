package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.StableExtensionAnalysis
import dev.sixbridge.core.bundle.SixBundle
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.crx.ExtensionKeyManager
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.PortableExtensionInput
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.input.SourceProjectIdentityStore
import dev.sixbridge.core.profile.BrowserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import java.security.KeyPair
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.readText

class ConversionPipeline private constructor(
    localRoot: Path,
    compatibilityAnalyzer: CompatibilityAnalyzer,
) : AutoCloseable {
    private val engine = ConversionPreparationEngine.create(compatibilityAnalyzer)
    private val materialization = ConversionWorkspace()
    private val packaging = CrxIdentityPolicy.create(localRoot)

    constructor(localRoot: Path) : this(localRoot, CompatibilityAnalyzer())

    fun analyze(
        resolved: ResolvedExtensionInput,
        profile: BrowserProfile,
    ): CompatibilityReport = engine.analyze(portableInput(resolved), profile)

    fun preflight(
        resolved: ResolvedExtensionInput,
        profile: BrowserProfile,
        approvedAdapterIds: Iterable<String>,
    ): CompatibilityReport {
        val prepared = engine.prepare(
            portableInput(resolved),
            profile,
            approvedAdapterIds,
        )
        return prepared.compatibilityBefore
    }

    fun prepare(
        resolved: ResolvedExtensionInput,
        profile: BrowserProfile,
        approvedAdapterIds: Iterable<String>,
    ): ConversionPipelineResult {
        val source = requireNotNull(resolved.sourceRoot) {
            "빌드할 확장 소스를 입력에서 추출하지 못했습니다."
        }
        val prepared = engine.prepare(
            portableInput(resolved),
            profile,
            approvedAdapterIds,
        )
        val verifiedMarker = SourceProjectIdentityStore.readSourceMarker(
            source,
            requireOwnerOnly = resolved.kind == ExtensionInputKind.SOURCE_DIRECTORY,
        )
        val identity = packaging.select(
            resolved = resolved,
            source = source,
            identitySource = source,
            sourceProjectIdentity = verifiedMarker,
            markerVerified = true,
            transformed = resolved.crxBytes != null && prepared.passthroughCrx == null,
            profileMajorVersion = profile.majorVersion,
        )
        val bound = engine.bindIdentity(
            prepared = prepared,
            profile = profile,
            publicKeyDer = identity.keyPair?.public?.encoded,
        )
        val reportSha256 = sha256(
            SixBundle.encodeCompatibility(bound.compatibility).toByteArray(UTF_8),
        )
        val crxBytes = if (bound.passthroughCrx != null) {
            requireNotNull(bound.passthroughCrx)
        } else {
            Crx3.packCanonicalZip(
                bound.sourceZip,
                requireNotNull(identity.keyPair),
            ).bytes
        }
        check(Crx3.parse(crxBytes).zipBytes.contentEquals(bound.sourceZip)) {
            "최종 CRX payload가 sealed canonical sourceZip과 다릅니다."
        }
        val convertedSourceRoot = materialization.snapshot(bound.sourceZip).workingRoot
        val logicalIdentity = (
            prepared.logicalSourceIdentity as? LogicalSourceIdentityResult.Stable
        )?.value
        return ConversionPipelineResult.create(
            sourceZip = bound.sourceZip,
            compatibilityBefore = prepared.compatibilityBefore,
            compatibilityAfter = bound.compatibility,
            planSha256 = prepared.planSha256,
            reportSha256 = reportSha256,
            extensionId = bound.extensionId,
            crxBytes = crxBytes,
            logicalIdentity = logicalIdentity,
            passthrough = bound.passthroughCrx != null,
            convertedSourceRoot = convertedSourceRoot,
            artifactDirectory = convertedSourceRoot.parent.resolve("artifacts").also {
                java.nio.file.Files.createDirectory(it)
            },
        )
    }

    override fun close() {
        try {
            materialization.close()
        } finally {
            engine.close()
        }
    }

    private fun portableInput(
        resolved: ResolvedExtensionInput,
    ): PortableExtensionInput {
        val source = requireNotNull(resolved.sourceRoot) {
            "확장 소스를 입력에서 추출하지 못했습니다."
        }
        SourceProjectIdentityStore.readSourceMarker(
            source,
            requireOwnerOnly = resolved.kind == ExtensionInputKind.SOURCE_DIRECTORY,
        )
        val crx = resolved.crxBytes
        if (crx != null) {
            requireCrxSourceBinding(resolved, source)
            return PortableExtensionInput.fromCrx(
                kind = resolved.kind,
                crxBytes = crx,
                expectedExtensionId = resolved.expectedExtensionId,
            )
        }
        return PortableExtensionInput.fromZip(
            kind = resolved.kind,
            zipBytes = ExtensionZip.createBytes(
                source,
                includeProjectMarker = true,
            ),
        )
    }

    private fun requireCrxSourceBinding(
        resolved: ResolvedExtensionInput,
        source: Path,
    ) {
        val crxBytes = resolved.crxBytes ?: return
        val parsed = Crx3.parse(crxBytes)
        if (resolved.expectedExtensionId != null &&
            resolved.expectedExtensionId != parsed.extensionId
        ) {
            throw CrxSourceBindingException()
        }
        val publicKey = parsed.publicKey
        val canonical = try {
            StableExtensionAnalysis.fromArchive(parsed.zipBytes).use { stable ->
                ExtensionZip.createCanonicalManifestBytes(
                    stable.root,
                    publicKey,
                    includeProjectMarker = true,
                )
            }
        } catch (_: IllegalArgumentException) {
            throw CrxSourceBindingException()
        }
        if (Crx3.extensionId(publicKey) != parsed.extensionId) {
            throw CrxSourceBindingException()
        }
        val expected = try {
            ExtensionZip.createCanonicalManifestBytes(
                source,
                publicKey,
                includeProjectMarker = true,
            )
        } catch (_: IllegalArgumentException) {
            throw CrxSourceBindingException()
        }
        if (!canonical.contentEquals(expected)) {
            throw CrxSourceBindingException()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

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

internal fun reconcileAdapterDecisions(
    beforeDecisions: List<AdapterDecision>,
    appliedDecisions: List<AdapterDecision>,
    rawAfterDecisions: List<AdapterDecision>,
): List<AdapterDecision> {
    val beforeByKey = beforeDecisions.associateBy { it.id to it.version }
    if (beforeByKey.size != beforeDecisions.size) {
        throw ConversionValidationException(
            "변환 전 adapter 계획에 중복 identity가 있습니다.",
        )
    }
    val appliedKeys = appliedDecisions.mapTo(mutableSetOf()) { it.id to it.version }
    if (appliedKeys.size != appliedDecisions.size ||
        appliedDecisions.any { !it.applied } ||
        appliedKeys.any { it !in beforeByKey }
    ) {
        throw ConversionValidationException(
            "sealed adapter application proof가 변환 전 계획과 일치하지 않습니다.",
        )
    }
    val allowedKeys = beforeByKey.keys + appliedKeys
    val newDecisions = rawAfterDecisions.filter {
        (it.id to it.version) !in allowedKeys
    }
    if (newDecisions.isNotEmpty()) {
        throw ConversionValidationException(
            "변환본 재분석에서 새 adapter 계획이 생겼습니다: " +
                newDecisions.map { it.id }.sorted(),
        )
    }
    val rawByKey = rawAfterDecisions.associateBy { it.id to it.version }
    if (rawByKey.size != rawAfterDecisions.size) {
        throw ConversionValidationException(
            "변환본 재분석에 중복 adapter identity가 있습니다.",
        )
    }
    val verifiedApplied = appliedDecisions.map { decision ->
        val current = rawByKey[decision.id to decision.version]
        if (current != null) {
            if (!current.applied ||
                current.risk != decision.risk ||
                current.disposition != decision.disposition ||
                current.changes != decision.changes ||
                current.remainingLimitations != decision.remainingLimitations
            ) {
                throw ConversionValidationException(
                    "변환본 재분석의 adapter 근거가 원본 계획과 다릅니다: " +
                        "${decision.id}; raw=$current; proof=$decision",
                )
            }
            current
        } else {
            decision
        }
    }
    val preserved = (beforeByKey.keys - appliedKeys).map { key ->
        val before = beforeByKey.getValue(key)
        val current = rawByKey[key]
            ?: throw ConversionValidationException(
                "변환본 재분석에서 미선택 adapter 근거가 사라졌습니다: ${before.id}",
            )
        if (current != before) {
            throw ConversionValidationException(
                "변환본 재분석에서 미선택 adapter 근거가 달라졌습니다: ${before.id}",
            )
        }
        current
    }
    return (verifiedApplied + preserved)
        .sortedWith(compareBy(AdapterDecision::id, AdapterDecision::version))
}

class ConversionPipelineResult private constructor(
    sourceZip: ByteArray,
    compatibilityBefore: CompatibilityReport,
    compatibilityAfter: CompatibilityReport,
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
    val compatibilityBefore: CompatibilityReport =
        compatibilityBefore.deepImmutableCopy()
    val compatibilityAfter: CompatibilityReport =
        compatibilityAfter.deepImmutableCopy()

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
        val identity = select(
            resolved,
            source,
            identitySource,
            sourceProjectIdentity,
            markerVerified,
            transformed,
            profileMajorVersion,
        )
        val bytes = if (identity.passthrough) {
            requireNotNull(identity.originalCrxBytes).copyOf()
        } else {
            Crx3.pack(source, requireNotNull(identity.keyPair)).bytes
        }
        return PreparedPipelineCrxValue(
            extensionId = identity.extensionId,
            bytes = bytes,
            originalExtensionId = identity.originalExtensionId,
            logicalIdentity = identity.logicalIdentity,
            passthrough = identity.passthrough,
        )
    }

    @JvmSynthetic
    fun select(
        resolved: ResolvedExtensionInput,
        source: Path,
        identitySource: Path = source,
        sourceProjectIdentity: String?,
        markerVerified: Boolean,
        transformed: Boolean,
        profileMajorVersion: Int,
    ): PreparedCrxIdentity {
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
                return PreparedCrxIdentityValue(
                    extensionId = originalParsed.extensionId,
                    originalExtensionId = originalParsed.extensionId,
                    logicalIdentity = null,
                    passthrough = true,
                    keyPair = null,
                    originalCrxBytes = original.copyOf(),
                    originalCrxZip = originalParsed.zipBytes,
                )
            }
            val identity = ExtensionKeyManager.storeConversionIdentity(
                originalParsed.extensionId,
                profileMajorVersion,
            )
            val keyManager = ExtensionKeyManager(
                requireNotNull(localRoot.parent),
                localRoot.resolve("keystore/crx"),
            )
            val key = keyManager.getOrCreateStoreConversionKey(
                originalParsed.extensionId,
                profileMajorVersion,
            )
            val extensionId = Crx3.extensionId(key.public.encoded)
            keyManager.persist(extensionId, key)
            return PreparedCrxIdentityValue(
                extensionId = extensionId,
                originalExtensionId = originalParsed.extensionId,
                logicalIdentity = identity,
                passthrough = false,
                keyPair = key,
                originalCrxBytes = original.copyOf(),
                originalCrxZip = originalParsed.zipBytes,
            )
        }

        val keyManager = ExtensionKeyManager(
            requireNotNull(localRoot.parent),
            localRoot.resolve("keystore/crx"),
        )
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
        val extensionId = Crx3.extensionId(key.public.encoded)
        keyManager.persist(extensionId, key)
        return PreparedCrxIdentityValue(
            extensionId = extensionId,
            originalExtensionId = manifestKey?.let {
                Crx3.extensionId(Base64.getDecoder().decode(it))
            },
            logicalIdentity = null,
            passthrough = false,
            keyPair = key,
            originalCrxBytes = null,
            originalCrxZip = null,
        )
    }

    @JvmSynthetic
    fun packVerified(
        identity: PreparedCrxIdentity,
        payload: VerifiedCrxPayload,
    ): ByteArray {
        check(!identity.passthrough) {
            "passthrough CRX는 verified payload로 재서명하지 않습니다."
        }
        val packed = Crx3.packVerified(
            payload,
            requireNotNull(identity.keyPair),
        )
        check(packed.extensionId == identity.extensionId) {
            "선택된 CRX identity와 verified pack 결과가 다릅니다."
        }
        return packed.bytes
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

internal sealed interface PreparedCrxIdentity {
    val extensionId: String
    val originalExtensionId: String?
    val logicalIdentity: String?
    val passthrough: Boolean

    @get:JvmSynthetic
    val keyPair: KeyPair?

    @get:JvmSynthetic
    val originalCrxBytes: ByteArray?

    @get:JvmSynthetic
    val originalCrxZip: ByteArray?
}

private data class PreparedCrxIdentityValue(
    override val extensionId: String,
    override val originalExtensionId: String?,
    override val logicalIdentity: String?,
    override val passthrough: Boolean,
    override val keyPair: KeyPair?,
    override val originalCrxBytes: ByteArray?,
    override val originalCrxZip: ByteArray?,
) : PreparedCrxIdentity
