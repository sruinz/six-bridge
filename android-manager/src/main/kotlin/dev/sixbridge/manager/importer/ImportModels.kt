package dev.sixbridge.manager.importer

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.VerifiedCrx3
import java.io.File

data class ImportedBundle(
    val token: String,
    val descriptor: ContractExtensionDescriptor,
    val report: ContractCompatibilityReport,
    val verifiedApk: VerifiedWrapperApk,
    val wrapperFile: File,
)

data class VerifiedWrapperApk(
    val packageName: String,
    val versionCode: Long,
    val signerSha256: String,
    val extensionId: String,
    val providerAuthority: String,
    val crx: VerifiedCrx3,
)

enum class ImportFailureCode {
    SOURCE_UNREADABLE,
    SOURCE_TOO_LARGE,
    INVALID_BUNDLE,
    WRAPPER_HASH_MISMATCH,
    CACHE_BOUNDARY_INVALID,
    CACHE_IO,
    ARCHIVE_INVALID,
    SIGNER_COUNT_INVALID,
    PACKAGE_MISMATCH,
    SIGNER_MISMATCH,
    VERSION_CODE_MISMATCH,
    METADATA_MISMATCH,
    ACTIVITY_MISMATCH,
    PROVIDER_MISMATCH,
    CRX_ENTRY_INVALID,
    CRX_TOO_LARGE,
    CRX_INVALID,
    CRX_HASH_MISMATCH,
    CRX_ID_MISMATCH,
    REPORT_SCHEMA_UNSUPPORTED,
    REPORT_IDENTITY_MISMATCH,
    REPORT_PROOF_INVALID,
}

data class ImportFailure(val code: ImportFailureCode)

sealed interface ImportResult {
    data class Success(val value: ImportedBundle) : ImportResult
    data class Failure(val reason: ImportFailure) : ImportResult
}
