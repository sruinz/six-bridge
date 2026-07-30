package dev.sixbridge.core.profile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
            installationApkSetSha256 = "def456",
        )

        val encoded = BrowserProfileJson.encode(profile)

        assertTrue("\"schemaVersion\": 3" in encoded)
        assertTrue("\"installationApkSetSha256\": \"def456\"" in encoded)
        assertEquals(profile, BrowserProfileJson.decode(encoded))
    }

    @Test
    fun `profile JSON은 unknown과 duplicate root field를 거부한다`() {
        val encoded = BrowserProfileJson.encode(baseline())
        val unknown = encoded.dropLast(1) + ",\n    \"unexpected\": true\n}"
        val duplicate = encoded.replaceFirst(
            "{",
            "{\n    \"versionName\": \"spoofed\",",
        )

        assertFailsWith<IllegalArgumentException> {
            BrowserProfileJson.decode(unknown)
        }
        assertFailsWith<IllegalArgumentException> {
            BrowserProfileJson.decode(duplicate)
        }
    }

    @Test
    fun `profile JSON은 missing field와 wrong scalar type을 거부한다`() {
        val encoded = BrowserProfileJson.encode(baseline())
        val missing = encoded.lines()
            .filterNot { "\"browserPackage\"" in it }
            .joinToString("\n")
        val wrongType = encoded.replace(
            "\"versionCode\": 1300261502",
            "\"versionCode\": \"1300261502\"",
        )

        assertFailsWith<IllegalArgumentException> {
            BrowserProfileJson.decode(missing)
        }
        assertFailsWith<IllegalArgumentException> {
            BrowserProfileJson.decode(wrongType)
        }
    }

    @Test
    fun `profile JSON은 wrapper contract exact schema와 boolean type을 검증한다`() {
        val encoded = BrowserProfileJson.encode(baseline())
        val unknown = encoded.replace(
            "\"devExtensionPath\": true",
            "\"devExtensionPath\": true,\n        \"unexpected\": true",
        )
        val wrongType = encoded.replace(
            "\"discoveryUri\": true",
            "\"discoveryUri\": \"true\"",
        )

        assertFailsWith<IllegalArgumentException> {
            BrowserProfileJson.decode(unknown)
        }
        assertFailsWith<IllegalArgumentException> {
            BrowserProfileJson.decode(wrongType)
        }
    }

    @Test
    fun `profile JSON support는 검증된 evidence 계산과 일치해야 한다`() {
        val encoded = BrowserProfileJson.encode(baseline())
        val forged = encoded.replace(
            "\"support\": \"unknown\"",
            "\"support\": \"supported\"",
        )

        assertFailsWith<IllegalArgumentException> {
            BrowserProfileJson.decode(forged)
        }
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
              "profileDifferences": [],
              "support": "unknown"
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
              "profileDifferences": [],
              "support": "supported"
            }
            """.trimIndent(),
        )

        assertEquals(ProfileSupport.SUPPORTED, profile.support)
        assertTrue("\"detectedApis\"" in BrowserProfileJson.encode(profile))
    }

    @Test
    fun `profile v2 preserves verified feature evidence`() {
        val profile = baseline().copy(
            supportedFeatures = setOf("storage.local"),
            unavailableFeatures = setOf("storage.sync"),
        )

        assertEquals(profile, BrowserProfileJson.decode(BrowserProfileJson.encode(profile)))
    }

    @Test
    fun `profile v1과 v2는 source APK hash를 installation set hash로 사용한다`() {
        val v1 = legacyProfileJson(schemaVersion = 1)
        val v2 = legacyProfileJson(schemaVersion = 2)

        assertEquals("0".repeat(64), BrowserProfileJson.decode(v1).installationApkSetSha256)
        assertEquals("0".repeat(64), BrowserProfileJson.decode(v2).installationApkSetSha256)
    }

    @Test
    fun `profile v3는 installation set hash 누락을 거부한다`() {
        val encoded = BrowserProfileJson.encode(
            baseline().copy(installationApkSetSha256 = "2".repeat(64)),
        )
        val missing = encoded.lines()
            .filterNot { "\"installationApkSetSha256\"" in it }
            .joinToString("\n")

        assertFailsWith<IllegalArgumentException> {
            BrowserProfileJson.decode(missing)
        }
    }

    @Test
    fun `installation set hash가 바뀌면 canonical profile hash가 바뀐다`() {
        val first = baseline().copy(installationApkSetSha256 = "2".repeat(64))
        val second = first.copy(installationApkSetSha256 = "3".repeat(64))

        assertTrue(first.canonicalSha256() != second.canonicalSha256())
    }

    @Test
    fun `profile rejects conflicting feature evidence`() {
        assertFailsWith<IllegalArgumentException> {
            baseline().copy(
                supportedFeatures = setOf("storage.sync"),
                unavailableFeatures = setOf("storage.sync"),
            ).validate()
        }
    }

    private fun baseline() = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = setOf("storage", "tabs"),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = "abc123",
    )

    private fun legacyProfileJson(schemaVersion: Int): String {
        val featureFields = if (schemaVersion == 1) {
            ""
        } else {
            """
            ,
              "supportedFeatures": [],
              "unavailableFeatures": []
            """.trimIndent()
        }
        return """
        {
          "schemaVersion": $schemaVersion,
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
          "profileDifferences": []$featureFields,
          "support": "unknown"
        }
        """.trimIndent()
    }
}
