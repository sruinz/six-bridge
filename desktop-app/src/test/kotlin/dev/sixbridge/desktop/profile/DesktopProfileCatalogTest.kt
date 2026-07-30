package dev.sixbridge.desktop.profile

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.SignedBrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopProfileCatalogTest {
    private val profile = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
        detectedApis = BrowserProfile.BASELINE_APIS,
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    )

    @Test
    fun `기본 profile은 최신 내장 30점0점2점61이다`() {
        val catalog = catalog()

        assertEquals("30.0.2.61", catalog.default().profile?.versionName)
        assertEquals(DesktopProfileTrust.BUILT_IN, catalog.default().trust)
        assertTrue(catalog.default().selectable)
    }

    @Test
    fun `미서명 임의 JSON과 unknown APK profile은 선택할 수 없다`() {
        val catalog = catalog(
            extract = { profile.copy(profileDifferences = listOf("unknown_apk_evidence")) },
        )

        val raw = catalog.importJson(
            BrowserProfileJson.encode(profile.copy(versionName = "31.0.0.1")).encodeToByteArray(),
        )
        val apk = catalog.importApk(Files.createTempFile("unknown-sbrowser", ".apk"))

        assertFalse(raw.selectable)
        assertEquals(DesktopProfileTrust.UNTRUSTED, raw.trust)
        assertFalse(apk.selectable)
        assertEquals(DesktopProfileTrust.UNTRUSTED, apk.trust)
    }

    @Test
    fun `검증된 envelope와 지원 APK profile만 원자 저장 후 다시 불러온다`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val catalog = catalog(
            dataRoot = dataRoot,
            trustedKeys = mapOf("test-key" to pair.public),
            extract = { profile.copy(supportedFeatures = setOf("storage.local")) },
        )
        val envelope = SignedBrowserProfile.sign(
            BrowserProfileJson.encode(profile).encodeToByteArray(),
            "test-key",
            pair.private,
        )

        val signed = catalog.importJson(envelope.encodeToByteArray())
        val extracted = catalog.importApk(Files.createTempFile("supported-sbrowser", ".apk"))
        val reloaded = catalog.loadPersisted()

        assertEquals(DesktopProfileTrust.SIGNED, signed.trust)
        assertTrue(signed.selectable)
        assertEquals(DesktopProfileTrust.APK_EXTRACTED_SUPPORTED, extracted.trust)
        assertTrue(extracted.selectable)
        assertEquals(
            setOf(DesktopProfileTrust.SIGNED, DesktopProfileTrust.APK_EXTRACTED_SUPPORTED),
            reloaded.map(DesktopProfileOption::trust).toSet(),
        )
        assertEquals(2, Files.list(dataRoot.resolve("profiles")).use { it.count() })
    }

    @Test
    fun `변조 파일명과 symlink 영속 profile은 다시 불러오지 않는다`() {
        val dataRoot = Files.createTempDirectory("six-bridge-catalog")
        val profiles = Files.createDirectories(dataRoot.resolve("profiles"))
        val outside = Files.createTempFile("outside-profile", ".json")
        Files.writeString(outside, "{}")
        Files.createSymbolicLink(profiles.resolve("${"0".repeat(64)}.json"), outside)
        Files.writeString(profiles.resolve("not-a-sha.json"), "{}")

        assertTrue(catalog(dataRoot = dataRoot).loadPersisted().isEmpty())
    }

    private fun catalog(
        dataRoot: Path = Files.createTempDirectory("six-bridge-catalog"),
        trustedKeys: Map<String, java.security.PublicKey> = emptyMap(),
        extract: (Path) -> BrowserProfile = { profile.copy(versionName = "31.0.0.1") },
    ) = DesktopProfileCatalog(dataRoot, trustedKeys, extract)
}
