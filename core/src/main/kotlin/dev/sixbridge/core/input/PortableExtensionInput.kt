package dev.sixbridge.core.input

import dev.sixbridge.core.crx.Crx3

class PortableExtensionInput private constructor(
    val kind: ExtensionInputKind,
    canonicalOriginalZip: ByteArray,
    originalCrx: ByteArray?,
    val expectedExtensionId: String?,
    val signedDraftSourceIdentity: String?,
) {
    private val stableZip = canonicalOriginalZip.copyOf()
    private val stableCrx = originalCrx?.copyOf()

    val canonicalOriginalZip: ByteArray
        get() = stableZip.copyOf()

    val originalCrx: ByteArray?
        get() = stableCrx?.copyOf()

    companion object {
        fun fromZip(
            kind: ExtensionInputKind,
            zipBytes: ByteArray,
            signedDraftSourceIdentity: String? = null,
        ): PortableExtensionInput {
            require(
                kind == ExtensionInputKind.ZIP ||
                    kind == ExtensionInputKind.SOURCE_DIRECTORY,
            ) {
                "ZIP 기반 portable input kind가 아닙니다: $kind"
            }
            require(zipBytes.isNotEmpty()) {
                "확장 ZIP은 비어 있을 수 없습니다."
            }
            require(
                signedDraftSourceIdentity == null ||
                    SOURCE_IDENTITY.matches(signedDraftSourceIdentity),
            ) {
                "서명 검증된 draft source identity 형식이 올바르지 않습니다."
            }
            return PortableExtensionInput(
                kind = kind,
                canonicalOriginalZip = zipBytes,
                originalCrx = null,
                expectedExtensionId = null,
                signedDraftSourceIdentity = signedDraftSourceIdentity,
            )
        }

        fun fromCrx(
            kind: ExtensionInputKind,
            crxBytes: ByteArray,
            expectedExtensionId: String? = null,
            signedDraftSourceIdentity: String? = null,
        ): PortableExtensionInput {
            require(
                kind == ExtensionInputKind.CRX3 ||
                    kind == ExtensionInputKind.CHROME_WEB_STORE,
            ) {
                "CRX 기반 portable input kind가 아닙니다: $kind"
            }
            require(
                signedDraftSourceIdentity == null ||
                    SOURCE_IDENTITY.matches(signedDraftSourceIdentity),
            ) {
                "서명 검증된 draft source identity 형식이 올바르지 않습니다."
            }
            val parsed = Crx3.parse(crxBytes)
            require(
                expectedExtensionId == null ||
                    expectedExtensionId == parsed.extensionId,
            ) {
                "입력 CRX extension ID가 검증된 proof ID와 다릅니다."
            }
            return PortableExtensionInput(
                kind = kind,
                canonicalOriginalZip = parsed.zipBytes,
                originalCrx = crxBytes,
                expectedExtensionId = parsed.extensionId,
                signedDraftSourceIdentity = signedDraftSourceIdentity,
            )
        }

        private val SOURCE_IDENTITY =
            Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    }
}
