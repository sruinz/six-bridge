package dev.sixbridge.probe.evidence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProbeManifestLoadIsolationJsonTest {
    @Test
    fun `manifest 로드 차단 증거는 strict canonical round trip한다`() {
        val decoded = ProbeManifestLoadIsolationJson.decodeStrict(VALID)

        assertEquals("webAuthenticationProxy", decoded.excludedPermission)
        assertEquals(ProbeManifestLoadOutcome.LOAD_BLOCKED, decoded.controlOutcome)
        assertEquals(ProbeManifestLoadOutcome.LOADED, decoded.variantOutcome)
        assertEquals(VALID, ProbeManifestLoadIsolationJson.encodeCanonical(decoded))
    }

    @Test
    fun `manifest 로드 차단 증거는 텍스트 파일의 단일 마지막 LF만 허용한다`() {
        assertEquals(
            ProbeManifestLoadIsolationJson.decodeStrict(VALID),
            ProbeManifestLoadIsolationJson.decodeStrict("$VALID\n"),
        )
        assertFailsWith<IllegalArgumentException> {
            ProbeManifestLoadIsolationJson.decodeStrict("$VALID\n\n")
        }
    }

    @Test
    fun `manifest 로드 차단 증거는 unknown field와 잘못된 outcome을 거부한다`() {
        listOf(
            VALID.replaceFirst("{", "{\"unknown\":true,"),
            VALID.replace("\"controlOutcome\":\"LOAD_BLOCKED\"", "\"controlOutcome\":\"LOADED\""),
            VALID.replace("\"variantOutcome\":\"LOADED\"", "\"variantOutcome\":\"LOAD_BLOCKED\""),
            VALID.replace("\"excludedPermission\":\"webAuthenticationProxy\"", "\"excludedPermission\":\"bad permission\""),
        ).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                ProbeManifestLoadIsolationJson.decodeStrict(invalid)
            }
        }
    }

    private companion object {
        const val VALID = "{\"browserApkSetSha256\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"browserPackage\":\"com.sec.android.app.sbrowser\",\"browserSignerSha256\":\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\",\"browserVersionCode\":300020061,\"browserVersionName\":\"30.0.2.61\",\"catalogSha256\":\"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd\",\"controlExtensionId\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"controlManifestSha256\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"controlOutcome\":\"LOAD_BLOCKED\",\"controlSessionId\":\"session-control\",\"excludedCaseId\":\"web-auth-proxy-session-event-worker\",\"excludedPermission\":\"webAuthenticationProxy\",\"groupId\":\"identity-privacy\",\"schemaVersion\":1,\"variantEvidenceSha256\":\"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee\",\"variantExtensionId\":\"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\",\"variantManifestSha256\":\"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff\",\"variantOutcome\":\"LOADED\",\"variantSessionId\":\"session-variant\",\"wrapperSignerSha256\":\"9999999999999999999999999999999999999999999999999999999999999999\"}"
    }
}
