package dev.sixbridge.manager.install

import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import dev.sixbridge.manager.wrapper.InstalledWrapper
import org.junit.Test
import kotlin.test.assertEquals

class InstallPolicyTest {
    private val policy = InstallPolicy()
    private val candidate = VerifiedWrapperApk(
        packageName = "dev.sixbridge.ext.${EXTENSION_ID}",
        versionCode = 20,
        signerSha256 = SIGNER,
        wrapperSha256 = "a".repeat(64),
        extensionId = EXTENSION_ID,
        providerAuthority = "dev.sixbridge.ext.${EXTENSION_ID}.contentprovider",
        crx = VerifiedCrx3(
            extensionId = EXTENSION_ID,
            publicKeySha256 = "b".repeat(64),
            crxSha256 = "c".repeat(64),
            zipOffset = 0,
            zipLength = 0,
        ),
    )

    @Test
    fun `allows a compatible new wrapper install`() {
        assertEquals(
            InstallDecision.Allowed(InstallOperation.INSTALL, requiresPartialConfirmation = false),
            policy.decide(report(ContractCompatibilityStatus.COMPATIBLE), candidate, installed = null),
        )
    }

    @Test
    fun `allows a partial new wrapper install after confirmation`() {
        assertEquals(
            InstallDecision.Allowed(InstallOperation.INSTALL, requiresPartialConfirmation = true),
            policy.decide(report(ContractCompatibilityStatus.PARTIAL), candidate, installed = null),
        )
    }

    @Test
    fun `denies a blocked report regardless of installed wrapper`() {
        assertEquals(
            InstallDecision.Denied(InstallDenial.BLOCKED_COMPATIBILITY),
            policy.decide(report(ContractCompatibilityStatus.BLOCKED), candidate, installed()),
        )
    }

    @Test
    fun `denies an unknown report regardless of installed wrapper`() {
        assertEquals(
            InstallDecision.Denied(InstallDenial.UNKNOWN_COMPATIBILITY),
            policy.decide(report(ContractCompatibilityStatus.UNKNOWN), candidate, installed()),
        )
    }

    @Test
    fun `allows a compatible newer signed wrapper update`() {
        assertEquals(
            InstallDecision.Allowed(InstallOperation.UPDATE, requiresPartialConfirmation = false),
            policy.decide(report(ContractCompatibilityStatus.COMPATIBLE), candidate, installed(versionCode = 19)),
        )
    }

    @Test
    fun `allows a partial newer signed wrapper update after confirmation`() {
        assertEquals(
            InstallDecision.Allowed(InstallOperation.UPDATE, requiresPartialConfirmation = true),
            policy.decide(report(ContractCompatibilityStatus.PARTIAL), candidate, installed(versionCode = 19)),
        )
    }

    @Test
    fun `denies an update signed by a different signer`() {
        assertEquals(
            InstallDecision.Denied(InstallDenial.SIGNER_MISMATCH),
            policy.decide(
                report(ContractCompatibilityStatus.COMPATIBLE),
                candidate,
                installed(signerSha256 = "b".repeat(64), versionCode = 1),
            ),
        )
    }

    @Test
    fun `denies an installed wrapper from another package`() {
        assertEquals(
            InstallDecision.Denied(InstallDenial.PACKAGE_MISMATCH),
            policy.decide(
                report(ContractCompatibilityStatus.COMPATIBLE),
                candidate,
                installed().copy(packageName = "dev.sixbridge.ext.${"b".repeat(32)}"),
            ),
        )
    }

    @Test
    fun `denies an update that is older`() {
        assertEquals(
            InstallDecision.Denied(InstallDenial.VERSION_NOT_NEWER),
            policy.decide(report(ContractCompatibilityStatus.COMPATIBLE), candidate, installed(versionCode = 21)),
        )
    }

    @Test
    fun `같은 versionCode의 같은 signer 업데이트를 출처 교정용으로 허용한다`() {
        assertEquals(
            InstallDecision.Allowed(
                InstallOperation.UPDATE,
                requiresPartialConfirmation = false,
            ),
            policy.decide(
                report(ContractCompatibilityStatus.COMPATIBLE),
                candidate,
                installed(versionCode = candidate.versionCode),
            ),
        )
    }

    private fun installed(
        signerSha256: String = SIGNER,
        versionCode: Long = 20,
    ) = InstalledWrapper(
        packageName = candidate.packageName,
        extensionId = EXTENSION_ID,
        name = "Example",
        versionCode = versionCode,
        signerSha256 = signerSha256,
    )

    private fun report(status: ContractCompatibilityStatus) = ContractCompatibilityReport(
        schemaVersion = 3,
        status = status,
        manifestVersion = 3,
        extensionName = "Example",
        extensionVersion = "1.0",
        manifestKeyPresent = true,
        manifestKeyExtensionId = EXTENSION_ID,
        declaredPermissions = emptyList(),
        usedApis = emptyList(),
        unsupportedApis = emptyList(),
        issues = emptyList(),
        conversionState = "converted",
        adapterDecisions = emptyList<ContractAdapterDecision>(),
        originalExtensionId = EXTENSION_ID,
        convertedExtensionId = EXTENSION_ID,
        sourceSha256 = "c".repeat(64),
        browserProfileSha256 = "d".repeat(64),
    )

    private companion object {
        const val EXTENSION_ID = "aabbccddeeffgghhiijjkkllmmnnoopp"
        val SIGNER = "1".repeat(64)
    }
}
