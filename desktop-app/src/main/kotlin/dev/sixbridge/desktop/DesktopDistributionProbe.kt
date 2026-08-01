package dev.sixbridge.desktop

import dev.sixbridge.core.draft.DraftSignature
import dev.sixbridge.desktop.conversion.DesktopConversionEngine
import dev.sixbridge.desktop.conversion.DesktopSecureWorkspaceProvider
import dev.sixbridge.desktop.security.DesktopDraftSigningIdentityStore

internal object DesktopDistributionProbe {
    private const val ARGUMENT = "--distribution-probe"
    private const val OUTPUT_PREFIX =
        "SIXBRIDGE_DISTRIBUTION_PROBE_V1 jimfs=OK signer="

    fun isRequested(arguments: Array<String>): Boolean =
        arguments.contentEquals(arrayOf(ARGUMENT))

    fun run(): String {
        DesktopSecureWorkspaceProvider().create().use { }
        val signer = DesktopConversionEngine.desktopSigningIdentityProvider().getOrCreate()
        return outputFor(signer.public.encoded)
    }

    internal fun run(
        paths: DesktopApplicationPaths,
        hostOs: DesktopHostOs,
    ): String {
        DesktopSecureWorkspaceProvider().create().use { }
        val signer = DesktopDraftSigningIdentityStore(paths, hostOs).getOrCreate()
        return outputFor(signer.public.encoded)
    }

    private fun outputFor(publicKey: ByteArray): String {
        val fingerprint = DraftSignature.fingerprint(publicKey)
        check(FINGERPRINT.matches(fingerprint)) {
            "distribution probe signer fingerprint 형식이 올바르지 않습니다."
        }
        return OUTPUT_PREFIX + fingerprint
    }

    private val FINGERPRINT = Regex("^[0-9a-f]{64}$")
}
