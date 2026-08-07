package dev.sixbridge.core.conversion

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrowserInternalUrlMapperTest {
    private val verifiedProfile = profile(
        supportedFeatures = setOf("browser.internal-url.extensions-root"),
    )
    private val unverified30Profile = profile()

    @Test
    fun `검증 profile은 extensions root 네 개만 verified로 매핑한다`() {
        val values = listOf(
            "chrome://extensions",
            "chrome://extensions/",
            "edge://extensions",
            "edge://extensions/",
        )

        values.forEach { value ->
            val rule = BrowserInternalUrlMapper.classify(value, verifiedProfile)

            assertEquals(BrowserInternalUrlStatus.VERIFIED, rule?.status, value)
            assertEquals("internet://extension", rule?.replacement, value)
        }
    }

    @Test
    fun `query fragment와 미검증 profile은 자동 매핑하지 않는다`() {
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
        assertNull(
            BrowserInternalUrlMapper.classify(
                "chrome://extensions",
                unverified30Profile,
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
