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
        val probeHome = System.getenv(PROBE_HOME_ENVIRONMENT)
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("distribution probe home이 없습니다.")
        val originalHome = System.getProperty("user.home")
        return try {
            System.setProperty("user.home", probeHome)
            DesktopSecureWorkspaceProvider().create().use { }
            val signer = DesktopConversionEngine.desktopSigningIdentityProvider().getOrCreate()
            outputFor(signer.public.encoded)
        } finally {
            if (originalHome == null) {
                System.clearProperty("user.home")
            } else {
                System.setProperty("user.home", originalHome)
            }
        }
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
    private const val PROBE_HOME_ENVIRONMENT = "SIXBRIDGE_DISTRIBUTION_PROBE_HOME"
}
