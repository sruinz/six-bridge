package dev.sixbridge.core.bundle

import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractBundleContents
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.bundle.ContractIssue
import dev.sixbridge.contract.bundle.ContractSourceEvidence
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.AdapterRisk
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.conversion.SourceEvidence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes

data class ExtensionDescriptor(
    val extensionId: String,
    val name: String,
    val version: String,
    val sourceKind: String,
    val crxSha256: String,
    val wrapperSha256: String,
    val wrapperPackage: String,
    val wrapperVersionCode: Int,
    val signerSha256: String,
)

data class SixBundleContents(
    val wrapperApk: ByteArray,
    val extension: ExtensionDescriptor,
    val compatibility: CompatibilityReport,
)

class InvalidSixBundleException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object SixBundle {
    fun write(
        output: Path,
        wrapperApk: Path,
        extension: ExtensionDescriptor,
        compatibility: CompatibilityReport,
    ) {
        Files.createDirectories(output.toAbsolutePath().parent)
        Files.newOutputStream(output).use { stream ->
            SixBundleContract.write(
                stream,
                ContractBundleContents(
                    wrapperApk = wrapperApk.readBytes(),
                    extension = extension.toContract(),
                    compatibility = compatibility.toContract(),
                ),
            )
        }
    }

    fun read(input: Path): SixBundleContents = try {
        Files.newInputStream(input).use(SixBundleContract::read).toCore()
    } catch (error: InvalidSixBundleException) {
        throw error
    } catch (error: Exception) {
        throw InvalidSixBundleException("SixBundle을 읽을 수 없습니다: ${error.message}", error)
    }

    fun encodeExtension(value: ExtensionDescriptor): String =
        BundleJson.encodeExtension(value.toContract()).prettyJson()

    fun encodeCompatibility(value: CompatibilityReport): String =
        BundleJson.encodeCompatibility(value.toContract()).prettyJson()

    fun toContractV4(
        compatibility: CompatibilityReport,
        originalSourceSha256: String,
        planSha256: String,
    ): ContractCompatibilityReport = compatibility.toContract().copy(
        schemaVersion = 4,
        originalSourceSha256 = originalSourceSha256,
        planSha256 = planSha256,
    )

    private fun ExtensionDescriptor.toContract() = ContractExtensionDescriptor(
        schemaVersion = 1,
        extensionId = extensionId,
        name = name,
        version = version,
        sourceKind = sourceKind,
        crxSha256 = crxSha256,
        wrapperSha256 = wrapperSha256,
        wrapperPackage = wrapperPackage,
        wrapperVersionCode = wrapperVersionCode,
        signerSha256 = signerSha256,
    )

    private fun CompatibilityReport.toContract(): ContractCompatibilityReport {
        validate()
        return ContractCompatibilityReport(
        schemaVersion = 3,
        status = ContractCompatibilityStatus.valueOf(status.name),
        manifestVersion = manifestVersion,
        extensionName = extensionName,
        extensionVersion = extensionVersion,
        manifestKeyPresent = manifestKeyPresent,
        manifestKeyExtensionId = manifestKeyExtensionId,
        declaredPermissions = declaredPermissions.sorted(),
        usedApis = usedApis.sorted(),
        unsupportedApis = unsupportedApis.sorted(),
        issues = issues.map { ContractIssue(it.code, it.message, it.blocking) },
        conversionState = conversionState.jsonValue(),
        adapterDecisions = adapterDecisions.map { decision ->
            ContractAdapterDecision(
                id = decision.id,
                version = decision.version,
                risk = decision.risk.jsonValue(),
                disposition = decision.disposition.jsonValue(),
                applied = decision.applied,
                evidence = decision.evidence.map { evidence ->
                    ContractSourceEvidence(evidence.relativePath, evidence.line, evidence.detail)
                },
                changes = decision.changes,
                remainingLimitations = decision.remainingLimitations,
            )
        },
        originalExtensionId = originalExtensionId,
        convertedExtensionId = convertedExtensionId,
        sourceSha256 = sourceSha256,
        browserProfileSha256 = browserProfileSha256,
        )
    }

    private fun ContractBundleContents.toCore() = SixBundleContents(
        wrapperApk = wrapperApk,
        extension = ExtensionDescriptor(
            extensionId = extension.extensionId,
            name = extension.name,
            version = extension.version,
            sourceKind = extension.sourceKind,
            crxSha256 = extension.crxSha256,
            wrapperSha256 = extension.wrapperSha256,
            wrapperPackage = extension.wrapperPackage,
            wrapperVersionCode = extension.wrapperVersionCode,
            signerSha256 = extension.signerSha256,
        ),
        compatibility = compatibility.toCore(),
    )

    private fun ContractCompatibilityReport.toCore() = CompatibilityReport(
        status = CompatibilityStatus.valueOf(status.name),
        manifestVersion = manifestVersion,
        extensionName = extensionName,
        extensionVersion = extensionVersion,
        manifestKeyPresent = manifestKeyPresent,
        manifestKeyExtensionId = manifestKeyExtensionId,
        declaredPermissions = declaredPermissions.toSet(),
        usedApis = usedApis.toSet(),
        unsupportedApis = unsupportedApis.toSet(),
        issues = issues.map { CompatibilityIssue(it.code, it.message, it.blocking) },
        conversionState = conversionState.toConversionState(),
        adapterDecisions = adapterDecisions.map { decision ->
            AdapterDecision(
                id = decision.id,
                version = decision.version,
                risk = decision.risk.toAdapterRisk(),
                disposition = decision.disposition.toAdapterDisposition(),
                applied = decision.applied,
                evidence = decision.evidence.map { evidence ->
                    SourceEvidence(evidence.relativePath, evidence.line, evidence.detail)
                },
                changes = decision.changes,
                remainingLimitations = decision.remainingLimitations,
            )
        },
        originalExtensionId = originalExtensionId,
        convertedExtensionId = convertedExtensionId,
        sourceSha256 = sourceSha256,
        browserProfileSha256 = browserProfileSha256,
    )

    private fun Enum<*>.jsonValue(): String = name.lowercase().replace('_', '-')

    private fun String.toConversionState(): ConversionState = enumValues<ConversionState>()
        .firstOrNull { it.jsonValue() == this }
        ?: throw IllegalArgumentException("지원하지 않는 conversionState입니다.")

    private fun String.toAdapterRisk(): AdapterRisk = enumValues<AdapterRisk>()
        .firstOrNull { it.jsonValue() == this }
        ?: throw IllegalArgumentException("지원하지 않는 adapter risk입니다.")

    private fun String.toAdapterDisposition(): AdapterDisposition = enumValues<AdapterDisposition>()
        .firstOrNull { it.jsonValue() == this }
        ?: throw IllegalArgumentException("지원하지 않는 adapter disposition입니다.")

    private fun String.prettyJson(): String = presentationJson.encodeToString(
        JsonObject.serializer(),
        Json.parseToJsonElement(this).jsonObject,
    )

    private val presentationJson = Json { prettyPrint = true }
}

fun CompatibilityReport.toContractV4(
    originalSourceSha256: String,
    planSha256: String,
): ContractCompatibilityReport = SixBundle.toContractV4(
    this,
    originalSourceSha256,
    planSha256,
)
