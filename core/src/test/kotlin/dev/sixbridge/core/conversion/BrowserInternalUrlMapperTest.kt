package dev.sixbridge.core.conversion

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserInternalUrlMapperTest {
    private val verifiedProfile = profile()
    private val unverified30Profile = profile()

    @Test
    fun `extensions root 네 개는 안전한 local 안내로 manual 매핑한다`() {
        val values = listOf(
            "chrome://extensions",
            "chrome://extensions/",
            "edge://extensions",
            "edge://extensions/",
        )

        values.forEach { value ->
            val rule = BrowserInternalUrlMapper.classify(value, verifiedProfile)

            assertEquals(BrowserInternalUrlStatus.MANUAL, rule?.status, value)
            assertEquals(
                "/sixbridge/internal-url-manual-extensions.v1.html",
                rule?.replacement,
                value,
            )
        }
    }

    @Test
    fun `Fold7 30 0 0 67 profile은 extensions direct 지원을 선언하지 않고 manual 매핑한다`() {
        val fold7Profile = BrowserProfileJson.decode(
            Path.of("../profiles/sbrowser-30.0.0.67.json").readText(),
        )

        val rule = BrowserInternalUrlMapper.classify(
            "chrome://extensions",
            fold7Profile,
        )

        assertEquals(false, "browser.internal-url.extensions-root" in fold7Profile.supportedFeatures)
        assertEquals(BrowserInternalUrlStatus.MANUAL, rule?.status)
        assertEquals(
            "/sixbridge/internal-url-manual-extensions.v1.html",
            rule?.replacement,
        )
    }

    @Test
    fun `query와 fragment는 자동 매핑하지 않는다`() {
        assertNull(
            BrowserInternalUrlMapper.classify(
                "chrome://extensions/?id=x",
                verifiedProfile,
            ),
        )
        assertNull(
            BrowserInternalUrlMapper.classify(
                "chrome://extensions#x",
                verifiedProfile,
            ),
        )
    }

    @Test
    fun `manual과 unsupported 주소는 고정된 local 안내로 exact 분류한다`() {
        val cases = listOf(
            Triple(
                "chrome://password-manager/settings",
                BrowserInternalUrlStatus.MANUAL,
                "/sixbridge/internal-url-manual-password-manager.v1.html",
            ),
            Triple(
                "chrome://flags",
                BrowserInternalUrlStatus.UNSUPPORTED,
                "/sixbridge/internal-url-unsupported-flags.v1.html",
            ),
            Triple(
                "chrome://inspect",
                BrowserInternalUrlStatus.UNSUPPORTED,
                "/sixbridge/internal-url-unsupported-inspect.v1.html",
            ),
        )

        cases.forEach { (value, status, replacement) ->
            val rule = BrowserInternalUrlMapper.classify(value, unverified30Profile)

            assertEquals(status, rule?.status, value)
            assertEquals(replacement, rule?.replacement, value)
        }
        assertNull(
            BrowserInternalUrlMapper.classify(
                "chrome://password-manager/settings?section=passwords",
                unverified30Profile,
            ),
        )
        assertNull(BrowserInternalUrlMapper.classify("Chrome://flags", unverified30Profile))
    }

    private fun profile(supportedFeatures: Set<String> = emptySet()) = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = BrowserProfile.BASELINE_VERSION,
        versionCode = 1300261502,
        majorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION,
        supportedApis = emptySet(),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        supportedFeatures = supportedFeatures,
    )
}
