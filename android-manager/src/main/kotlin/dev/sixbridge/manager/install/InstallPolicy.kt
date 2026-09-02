package dev.sixbridge.manager.install

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.core.conversion.ForceCompatibilityPolicy
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import dev.sixbridge.manager.wrapper.InstalledWrapper

sealed interface InstallDecision {
    data class Allowed(
        val operation: InstallOperation,
        val requiresPartialConfirmation: Boolean,
        val requiresForcedCompatibilityConfirmation: Boolean = false,
    ) : InstallDecision

    data class Denied(val reason: InstallDenial) : InstallDecision
}

enum class InstallOperation {
    INSTALL,
    UPDATE,
}

enum class InstallDenial {
    BLOCKED_COMPATIBILITY,
    UNKNOWN_COMPATIBILITY,
    PACKAGE_MISMATCH,
    SIGNER_MISMATCH,
    VERSION_NOT_NEWER,
    REPORT_PROOF_INVALID,
}

class InstallPolicy {
    fun decide(
        report: ContractCompatibilityReport,
        candidate: VerifiedWrapperApk,
        installed: InstalledWrapper?,
    ): InstallDecision {
        val forced = if (report.schemaVersion == 6) {
            val digest = candidate.compatibilityCanonicalSha256
                ?: return InstallDecision.Denied(InstallDenial.REPORT_PROOF_INVALID)
            val provenance = runCatching {
                ForceCompatibilityPolicy.requireBoundContractProvenance(report, digest)
            }.getOrElse {
                return InstallDecision.Denied(InstallDenial.REPORT_PROOF_INVALID)
            }
            if (provenance != candidate.forcedCompatibility) {
                return InstallDecision.Denied(InstallDenial.REPORT_PROOF_INVALID)
            }
            provenance
        } else {
            if (candidate.compatibilityCanonicalSha256 != null ||
                candidate.forcedCompatibility != null
            ) {
                return InstallDecision.Denied(InstallDenial.REPORT_PROOF_INVALID)
            }
            null
        }
        val requiresPartialConfirmation = when (report.status) {
            ContractCompatibilityStatus.COMPATIBLE -> false
            ContractCompatibilityStatus.PARTIAL -> true
            ContractCompatibilityStatus.BLOCKED -> {
                return InstallDecision.Denied(InstallDenial.BLOCKED_COMPATIBILITY)
            }
            ContractCompatibilityStatus.UNKNOWN -> {
                if (forced == null) {
                    return InstallDecision.Denied(InstallDenial.UNKNOWN_COMPATIBILITY)
                }
                false
            }
        }
        val requiresForcedCompatibilityConfirmation = forced != null
        if (installed == null) {
            return InstallDecision.Allowed(
                InstallOperation.INSTALL,
                requiresPartialConfirmation,
                requiresForcedCompatibilityConfirmation,
            )
        }
        if (installed.packageName != candidate.packageName) {
            return InstallDecision.Denied(InstallDenial.PACKAGE_MISMATCH)
        }
        if (installed.signerSha256 != candidate.signerSha256) {
            return InstallDecision.Denied(InstallDenial.SIGNER_MISMATCH)
        }
        if (candidate.versionCode < installed.versionCode) {
            return InstallDecision.Denied(InstallDenial.VERSION_NOT_NEWER)
        }
        return InstallDecision.Allowed(
            InstallOperation.UPDATE,
            requiresPartialConfirmation,
            requiresForcedCompatibilityConfirmation,
        )
    }
}
