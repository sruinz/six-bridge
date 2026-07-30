package dev.sixbridge.core.profile

import java.security.KeyPairGenerator
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SignedBrowserProfileTest {
    private val profile = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = setOf("storage"),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    )

    @Test
    fun `서명 profile은 원문 key id와 hash를 함께 검증한다`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val encoded = SignedBrowserProfile.sign(
            BrowserProfileJson.encode(profile).encodeToByteArray(),
            "profile-signing-2026-01",
            pair.private,
        )

        val verified = SignedBrowserProfile.verify(
            encoded,
            mapOf("profile-signing-2026-01" to pair.public),
        )

        assertEquals(profile, verified.profile)
        assertEquals(
            MessageDigest.getInstance("SHA-256").digest(
                BrowserProfileJson.encode(profile).encodeToByteArray(),
            ).joinToString("") { "%02x".format(it) },
            verified.profileSha256,
        )
        assertFailsWith<IllegalArgumentException> {
            SignedBrowserProfile.verify(
                encoded.replace("\"profileSha256\":\"", "\"profileSha256\":\"0"),
                mapOf("profile-signing-2026-01" to pair.public),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SignedBrowserProfile.verify(encoded, emptyMap())
        }
    }

    @Test
    fun `서명 profile envelope는 schema 경계의 변조를 fail closed로 거부한다`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val encoded = SignedBrowserProfile.sign(
            BrowserProfileJson.encode(profile).encodeToByteArray(),
            "test-key",
            pair.private,
        )
        val trusted = mapOf("test-key" to pair.public)

        assertFailsWith<IllegalArgumentException> {
            SignedBrowserProfile.verify(
                encoded.dropLast(1) + ",\"keyId\":\"test-key\"}",
                trusted,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SignedBrowserProfile.verify(
                encoded.dropLast(1) + ",\"unexpected\":true}",
                trusted,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SignedBrowserProfile.verify(
                encoded.replace("\"profileBase64\":\"", "\"profileBase64\":\"%%%"),
                trusted,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SignedBrowserProfile.verify(
                encoded.replace("\"algorithm\":\"Ed25519\"", "\"algorithm\":\"RSA\""),
                trusted,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            SignedBrowserProfile.verify(
                encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                trusted,
            )
        }
        val signature = encoded.substringAfter("\"signatureBase64\":\"").substringBefore('"')
        val tamperedSignature = "${if (signature.first() == 'A') 'B' else 'A'}${signature.drop(1)}"
        assertFailsWith<IllegalArgumentException> {
            SignedBrowserProfile.verify(encoded.replace(signature, tamperedSignature), trusted)
        }
    }

    @Test
    fun `서명 전 profile 원문 크기는 제한한다`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

        assertFailsWith<IllegalArgumentException> {
            SignedBrowserProfile.sign(ByteArray(524_289), "test-key", pair.private)
        }
    }
}
