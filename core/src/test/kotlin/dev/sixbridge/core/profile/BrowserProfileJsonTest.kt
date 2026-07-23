package dev.sixbridge.core.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun `fails closed for a baseline profile with an untrusted apk or signer`() {
        val profile = BrowserProfileJson.decode(
            """
            {
              "schemaVersion": 1,
              "browserPackage": "com.sec.android.app.sbrowser",
              "versionName": "30.0.2.61",
              "versionCode": 1300261502,
              "majorVersion": 30,
              "detectedApis": ["storage"],
              "supportedApis": ["storage"],
              "wrapperContract": {
                "discoveryUri": true,
                "extensionMetadata": true,
                "contentProvider": true,
                "devExtensionPath": true
              },
              "sourceApkSha256": "${"0".repeat(64)}",
              "sourceApkSignerSha256": "${"1".repeat(64)}",
              "profileDifferences": []
            }
            """.trimIndent(),
        )

        assertEquals(ProfileSupport.UNKNOWN, profile.support)
    }

    @Test
    fun `accepts only the exact trusted baseline evidence`() {
        val profile = BrowserProfileJson.decode(
            """
            {
              "schemaVersion": 1,
              "browserPackage": "com.sec.android.app.sbrowser",
              "versionName": "30.0.2.61",
              "versionCode": 1300261502,
              "majorVersion": 30,
              "detectedApis": ["storage"],
              "supportedApis": ["storage"],
              "wrapperContract": {
                "discoveryUri": true,
                "extensionMetadata": true,
                "contentProvider": true,
                "devExtensionPath": true
              },
              "sourceApkSha256":
                "6fad5501a3f9823c7dd9fafdfe1204f7246e487c8dea9c192426c07c8c48c981",
              "sourceApkSignerSha256":
                "34df0e7a9f1cf1892e45c056b4973cd81ccf148a4050d11aea4ac5a65f900a42",
              "profileDifferences": []
            }
            """.trimIndent(),
        )

        assertEquals(ProfileSupport.SUPPORTED, profile.support)
        assertTrue("\"detectedApis\"" in BrowserProfileJson.encode(profile))
    }
}
