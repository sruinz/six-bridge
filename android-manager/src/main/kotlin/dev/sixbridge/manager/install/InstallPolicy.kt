package dev.sixbridge.manager.install

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import dev.sixbridge.manager.wrapper.InstalledWrapper

sealed interface InstallDecision {
    data class Allowed(
        val operation: InstallOperation,
        val requiresPartialConfirmation: Boolean,
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
}

class InstallPolicy {
    fun decide(
        report: ContractCompatibilityReport,
        candidate: VerifiedWrapperApk,
        installed: InstalledWrapper?,
    ): InstallDecision {
        val requiresPartialConfirmation = when (report.status) {
            ContractCompatibilityStatus.COMPATIBLE -> false
            ContractCompatibilityStatus.PARTIAL -> true
            ContractCompatibilityStatus.BLOCKED -> {
                return InstallDecision.Denied(InstallDenial.BLOCKED_COMPATIBILITY)
            }
            ContractCompatibilityStatus.UNKNOWN -> {
                return InstallDecision.Denied(InstallDenial.UNKNOWN_COMPATIBILITY)
            }
        }
        if (installed == null) {
            return InstallDecision.Allowed(InstallOperation.INSTALL, requiresPartialConfirmation)
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
        return InstallDecision.Allowed(InstallOperation.UPDATE, requiresPartialConfirmation)
    }
}
