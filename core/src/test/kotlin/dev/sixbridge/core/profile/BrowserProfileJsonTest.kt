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

        assertTrue("\"schemaVersion\": 4" in encoded)
        assertTrue("\"installationApkSetSha256\": \"def456\"" in encoded)
        assertEquals(profile, BrowserProfileJson.decode(encoded))
    }

    @Test
    fun `schema v4 capability evidence를 결정론적으로 round trip한다`() {
        val first = capability(
            namespace = "tabs",
            member = "query",
            context = ApiExecutionContext.SERVICE_WORKER,
            permissions = linkedSetOf("tabs", "activeTab"),
            manifestKeys = linkedSetOf("permissions", "host_permissions"),
        )
        val second = capability(
            namespace = "alarms",
            member = "getAll",
            context = ApiExecutionContext.EXTENSION_PAGE,
            permissions = linkedSetOf("alarms"),
            manifestKeys = emptySet(),
        )
        val profile = baseline().copy(apiCapabilities = listOf(first, second))
        val reordered = profile.copy(
            apiCapabilities = listOf(
                second,
                first.copy(
                    permissions = linkedSetOf("activeTab", "tabs"),
                    manifestKeys = linkedSetOf("host_permissions", "permissions"),
                ),
            ),
        )

        val encoded = BrowserProfileJson.encode(profile)

        assertEquals(encoded, BrowserProfileJson.encode(reordered))
        assertTrue(
            encoded.indexOf("\"namespace\": \"alarms\"") <
                encoded.indexOf("\"namespace\": \"tabs\""),
        )
        assertEquals(
            profile,
            BrowserProfileJson.decode(encoded),
        )
    }

    @Test
    fun `profile v1부터 v3 decode는 빈 capability 목록을 부여한다`() {
        (1..3).forEach { schemaVersion ->
            assertEquals(
                emptyList(),
                BrowserProfileJson.decode(legacyProfileJson(schemaVersion)).apiCapabilities,
            )
        }
    }

    @Test
    fun `schema v4 capability는 exact field set과 scalar type을 검증한다`() {
        val encoded = BrowserProfileJson.encode(
            baseline().copy(apiCapabilities = listOf(capability())),
        )
        val unknownEvidenceField = encoded.replace(
            "\"status\": \"verified\"",
            "\"status\": \"verified\",\n            \"unexpected\": true",
        )
        val unknownKeyField = encoded.replace(
            "\"context\": \"service_worker\"",
            "\"context\": \"service_worker\",\n                \"unexpected\": true",
        )
        val missingEvidenceField = encoded.lines()
            .filterNot { "\"detail\"" in it }
            .joinToString("\n")
        val wrongPermissionsType = encoded.replace(
            "\"permissions\": [\n                \"tabs\"\n            ]",
            "\"permissions\": \"tabs\"",
        )

        listOf(
            unknownEvidenceField,
            unknownKeyField,
            missingEvidenceField,
            wrongPermissionsType,
        ).forEach { malformed ->
            assertFailsWith<IllegalArgumentException> {
                BrowserProfileJson.decode(malformed)
            }
        }
    }

    @Test
    fun `schema v4 capability는 duplicate field와 set 원소를 거부한다`() {
        val encoded = BrowserProfileJson.encode(
            baseline().copy(apiCapabilities = listOf(capability())),
        )
        val duplicateField = encoded.replace(
            "\"status\": \"verified\"",
            "\"status\": \"verified\",\n            \"status\": \"absent\"",
        )
        val duplicateSetValue = encoded.replace(
            "\"permissions\": [\n                \"tabs\"\n            ]",
            "\"permissions\": [\n                \"tabs\",\n                \"tabs\"\n            ]",
        )

        assertFailsWith<IllegalArgumentException> {
            BrowserProfileJson.decode(duplicateField)
        }
        assertFailsWith<IllegalArgumentException> {
            BrowserProfileJson.decode(duplicateSetValue)
        }
    }

    @Test
    fun `schema v4 capability는 status context digest를 엄격히 검증한다`() {
        val encoded = BrowserProfileJson.encode(
            baseline().copy(apiCapabilities = listOf(capability())),
        )
        val invalidStatus = encoded.replace("\"status\": \"verified\"", "\"status\": \"VERIFIED\"")
        val invalidContext = encoded.replace(
            "\"context\": \"service_worker\"",
            "\"context\": \"background_page\"",
        )
        val invalidDigest = encoded.replace("\"${"a".repeat(64)}\"", "\"${"A".repeat(64)}\"")

        listOf(invalidStatus, invalidContext, invalidDigest).forEach { malformed ->
            assertFailsWith<IllegalArgumentException> {
                BrowserProfileJson.decode(malformed)
            }
        }
    }

    @Test
    fun `schema v4 decode는 duplicate capability key를 거부한다`() {
        val evidence = capability()
        val encoded = BrowserProfileJson.encode(
            baseline().copy(apiCapabilities = listOf(evidence)),
        )
        val evidenceObject = encoded.substringAfter("\"apiCapabilities\": [\n").substringBefore("\n    ],")
        val duplicateArray = encoded.replace(
            "\"apiCapabilities\": [\n$evidenceObject\n    ]",
            "\"apiCapabilities\": [\n$evidenceObject,\n$evidenceObject\n    ]",
        )

        assertFailsWith<IllegalArgumentException> {
            BrowserProfileJson.decode(duplicateArray)
        }
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
        val encoded = legacyProfileJson(schemaVersion = 3)
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

    private fun capability(
        namespace: String = "tabs",
        member: String = "query",
        context: ApiExecutionContext = ApiExecutionContext.SERVICE_WORKER,
        permissions: Set<String> = setOf("tabs"),
        manifestKeys: Set<String> = setOf("permissions"),
    ) = ApiCapabilityEvidence(
        key = ApiCapabilityKey(namespace, member, context),
        status = ApiCapabilityStatus.VERIFIED,
        permissions = permissions,
        manifestKeys = manifestKeys,
        detail = "안전한 호출 성공",
        probeId = "$namespace-$member",
        probeCatalogSha256 = "a".repeat(64),
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
        val installationField = if (schemaVersion < 3) {
            ""
        } else {
            """,
              "installationApkSetSha256": "${"0".repeat(64)}"
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
          "sourceApkSha256": "${"0".repeat(64)}"$installationField,
          "sourceApkSignerSha256": "${"1".repeat(64)}",
          "profileDifferences": []$featureFields,
          "support": "unknown"
        }
        """.trimIndent()
    }
}
