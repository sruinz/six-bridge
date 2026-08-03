package dev.sixbridge.contract.bundle

import java.nio.file.Path

enum class ContractCompatibilityStatus {
    COMPATIBLE,
    PARTIAL,
    BLOCKED,
    UNKNOWN,
}

data class ContractExtensionDescriptor(
    val schemaVersion: Int,
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

data class ContractCompatibilityReport(
    val schemaVersion: Int,
    val status: ContractCompatibilityStatus,
    val manifestVersion: Int?,
    val extensionName: String?,
    val extensionVersion: String?,
    val manifestKeyPresent: Boolean,
    val manifestKeyExtensionId: String?,
    val declaredPermissions: List<String>,
    val usedApis: List<String>,
    val unsupportedApis: List<String>,
    val issues: List<ContractIssue>,
    val conversionState: String,
    val adapterDecisions: List<ContractAdapterDecision>,
    val originalExtensionId: String?,
    val convertedExtensionId: String?,
    val sourceSha256: String?,
    val browserProfileSha256: String?,
    val originalSourceSha256: String? = null,
    val planSha256: String? = null,
    val verifiedApis: List<String> = usedApis.filterNot(unsupportedApis::contains),
    val unverifiedApis: List<String> = emptyList(),
    val unavailableApis: List<String> = unsupportedApis,
)

data class ContractIssue(
    val code: String,
    val message: String,
    val blocking: Boolean,
)

data class ContractSourceEvidence(
    val relativePath: String,
    val line: Int?,
    val detail: String,
)

data class ContractAdapterDecision(
    val id: String,
    val version: Int,
    val risk: String,
    val disposition: String,
    val applied: Boolean,
    val evidence: List<ContractSourceEvidence>,
    val changes: List<String>,
    val remainingLimitations: List<String>,
)

data class ContractBundleContents(
    val wrapperApk: ByteArray,
    val extension: ContractExtensionDescriptor,
    val compatibility: ContractCompatibilityReport,
)

data class ContractBundleFileContents(
    val wrapperApk: Path,
    val extension: ContractExtensionDescriptor,
    val compatibility: ContractCompatibilityReport,
)
