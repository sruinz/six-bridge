package dev.sixbridge.core.profile

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserProfileJsonTest {
    @Test
    fun `round trips a browser profile`() {
        val profile = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = "30.0.2.61",
            versionCode = 1_300_261_502,
            majorVersion = 30,
            supportedApis = setOf("storage", "tabs"),
            wrapperContract = WrapperContract.complete(),
            sourceApkSha256 = "abc123",
        )

        assertEquals(profile, BrowserProfileJson.decode(BrowserProfileJson.encode(profile)))
    }
}
