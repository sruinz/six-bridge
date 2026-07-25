package dev.sixbridge.manager.wrapper

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

sealed interface WrapperVerificationResult {
    data class Success(val value: VerifiedWrapperApk) : WrapperVerificationResult
    data class Failure(val reason: ImportFailure) : WrapperVerificationResult
}

class AndroidWrapperVerifier(
    private val inspector: ArchiveInspector,
) {
    fun verify(
        apk: File,
        descriptor: ContractExtensionDescriptor,
        report: ContractCompatibilityReport,
    ): WrapperVerificationResult {
        val wrapperSha256 = runCatching { sha256(apk) }
            .getOrElse { return failure(ImportFailureCode.ARCHIVE_INVALID) }
        if (wrapperSha256 != descriptor.wrapperSha256) {
            return failure(ImportFailureCode.WRAPPER_HASH_MISMATCH)
        }
        val facts = try {
            inspector.inspect(apk)
        } catch (error: ArchiveInspectionException) {
            return WrapperVerificationResult.Failure(error.failure)
        } catch (_: Exception) {
            return failure(ImportFailureCode.ARCHIVE_INVALID)
        }
        val expectedPackage = "dev.sixbridge.ext.${descriptor.extensionId}"
        if (descriptor.wrapperPackage != expectedPackage ||
            facts.packageName != descriptor.wrapperPackage
        ) {
            return failure(ImportFailureCode.PACKAGE_MISMATCH)
        }
        if (facts.versionCode != descriptor.wrapperVersionCode.toLong()) {
            return failure(ImportFailureCode.VERSION_CODE_MISMATCH)
        }
        if (facts.signerSha256 != descriptor.signerSha256) {
            return failure(ImportFailureCode.SIGNER_MISMATCH)
        }
        if (facts.metadata[EXTENSION_ID_METADATA] != descriptor.extensionId) {
            return failure(ImportFailureCode.METADATA_MISMATCH)
        }
        val hasDiscoveryActivity = facts.activities.any { activity ->
            activity.exported && activity.intentFilters.any { filter ->
                VIEW_ACTION in filter.actions &&
                    BROWSABLE_CATEGORY in filter.categories &&
                    SIX_SCHEME in filter.schemes &&
                    EX_HOST in filter.hosts
            }
        }
        if (!hasDiscoveryActivity) return failure(ImportFailureCode.ACTIVITY_MISMATCH)

        val expectedAuthority = "${facts.packageName}.contentprovider"
        val provider = facts.providers.firstOrNull {
            it.exported && it.authority == expectedAuthority
        } ?: return failure(ImportFailureCode.PROVIDER_MISMATCH)

        val crx = try {
            Crx3Verifier.verify(facts.crxBytes)
        } catch (_: IllegalArgumentException) {
            return failure(ImportFailureCode.CRX_INVALID)
        }
        if (crx.crxSha256 != descriptor.crxSha256) {
            return failure(ImportFailureCode.CRX_HASH_MISMATCH)
        }
        if (crx.extensionId != descriptor.extensionId) {
            return failure(ImportFailureCode.CRX_ID_MISMATCH)
        }
        if (report.schemaVersion != REQUIRED_REPORT_SCHEMA) {
            return failure(ImportFailureCode.REPORT_SCHEMA_UNSUPPORTED)
        }
        if (report.convertedExtensionId != descriptor.extensionId ||
            report.extensionName != descriptor.name ||
            report.extensionVersion != descriptor.version
        ) {
            return failure(ImportFailureCode.REPORT_IDENTITY_MISMATCH)
        }
        if (!SHA256.matches(report.sourceSha256.orEmpty()) ||
            !SHA256.matches(report.browserProfileSha256.orEmpty())
        ) {
            return failure(ImportFailureCode.REPORT_PROOF_INVALID)
        }
        return WrapperVerificationResult.Success(
            VerifiedWrapperApk(
                packageName = facts.packageName,
                versionCode = facts.versionCode,
                signerSha256 = facts.signerSha256,
                extensionId = descriptor.extensionId,
                providerAuthority = provider.authority,
                crx = crx,
            ),
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun failure(code: ImportFailureCode) =
        WrapperVerificationResult.Failure(ImportFailure(code))

    private companion object {
        const val REQUIRED_REPORT_SCHEMA = 3
        const val EXTENSION_ID_METADATA = "extension-id"
        const val VIEW_ACTION = "android.intent.action.VIEW"
        const val BROWSABLE_CATEGORY = "android.intent.category.BROWSABLE"
        const val SIX_SCHEME = "six"
        const val EX_HOST = "ex"
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
