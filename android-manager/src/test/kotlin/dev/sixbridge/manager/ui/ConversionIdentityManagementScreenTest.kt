package dev.sixbridge.manager.ui

import dev.sixbridge.manager.conversion.InstallationIdentityMode
import dev.sixbridge.manager.conversion.LegacyConversionIdentity
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class ConversionIdentityManagementScreenTest {
    @Test
    fun `confirm 대상은 현재 legacy snapshot의 exact row로만 선택한다`() {
        val identity = legacyIdentity("a".repeat(64))
        val state = ConversionIdentityManagementUiState(
            identities = listOf(identity),
        )

        assertEquals(
            identity,
            state.requestCleanup(identity.identitySha256).pendingCleanupIdentity,
        )
        assertNull(state.requestCleanup("b".repeat(64)).pendingCleanupIdentity)
    }

    @Test
    fun `signer와 identity 요약은 양끝을 보존하고 전체 hash를 노출하지 않는다`() {
        val sha256 = "0123456789abcdef".repeat(4)

        assertEquals(
            "0123456789ab…456789abcdef",
            summarizeIdentitySha256(sha256),
        )
    }

    private fun legacyIdentity(identitySha256: String) = LegacyConversionIdentity(
        identitySha256 = identitySha256,
        displayName = "이전 변환",
        mode = InstallationIdentityMode.CONVERTED,
        packageName = "dev.sixbridge.ext.${"b".repeat(32)}",
        extensionId = "b".repeat(32),
        wrapperSignerSha256 = "c".repeat(64),
    )
}
