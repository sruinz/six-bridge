package dev.sixbridge.core.conversion

import dev.sixbridge.core.profile.ApiCapabilityEvidence
import dev.sixbridge.core.profile.ApiCapabilityKey
import dev.sixbridge.core.profile.ApiCapabilityStatus
import dev.sixbridge.core.profile.ApiExecutionContext
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileDigest
import dev.sixbridge.core.profile.WrapperContract
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class BrowserProfileDigestTest {
    private val base = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = linkedSetOf("runtime", "storage"),
        detectedApis = linkedSetOf("runtime", "storage", "tabs"),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        profileDifferences = listOf("difference-a", "difference-b"),
        supportedFeatures = linkedSetOf("storage.local", "feature.a"),
        unavailableFeatures = linkedSetOf("storage.sync", "feature.b"),
    )

    @Test
    fun `is deterministic for unordered profile sets`() {
        val reordered = base.copy(
            supportedApis = linkedSetOf("storage", "runtime"),
            detectedApis = linkedSetOf("tabs", "storage", "runtime"),
            supportedFeatures = linkedSetOf("feature.a", "storage.local"),
            unavailableFeatures = linkedSetOf("feature.b", "storage.sync"),
        )

        assertEquals(
            BrowserProfileDigest.sha256(base),
            BrowserProfileDigest.sha256(reordered),
        )
    }

    @Test
    fun `seals every browser profile field that affects conversion`() {
        val variations = listOf(
            base.copy(browserPackage = "other.package"),
            base.copy(versionName = "30.0.2.62"),
            base.copy(versionCode = base.versionCode + 1),
            base.copy(majorVersion = 31),
            base.copy(supportedApis = base.supportedApis + "downloads"),
            base.copy(detectedApis = base.detectedApis + "downloads"),
            base.copy(
                wrapperContract = base.wrapperContract.copy(
                    discoveryUri = false,
                ),
            ),
            base.copy(sourceApkSha256 = "a".repeat(64)),
            base.copy(sourceApkSignerSha256 = "b".repeat(64)),
            base.copy(profileDifferences = base.profileDifferences.reversed()),
            base.copy(supportedFeatures = base.supportedFeatures + "feature.c"),
            base.copy(unavailableFeatures = base.unavailableFeatures + "feature.c"),
        )
        val digest = BrowserProfileDigest.sha256(base)

        variations.forEach { changed ->
            assertNotEquals(digest, BrowserProfileDigest.sha256(changed), changed.toString())
        }
    }

    @Test
    fun `canonical profile digest is injective for collection boundaries`() {
        val twoValues = base.copy(
            supportedApis = setOf("a", "b"),
            profileDifferences = emptyList(),
        )
        val embeddedSeparator = base.copy(
            supportedApis = setOf("a\u0000b"),
            profileDifferences = emptyList(),
        )
        val emptySet = base.copy(
            supportedApis = emptySet(),
            profileDifferences = emptyList(),
        )
        val oneEmptyValue = base.copy(
            supportedApis = setOf(""),
            profileDifferences = emptyList(),
        )

        assertNotEquals(
            twoValues.canonicalSha256(),
            embeddedSeparator.canonicalSha256(),
        )
        assertNotEquals(
            emptySet.canonicalSha256(),
            oneEmptyValue.canonicalSha256(),
        )
        assertEquals(
            BrowserProfileDigest.sha256(twoValues),
            twoValues.canonicalSha256(),
        )
    }

    @Test
    fun `capability evidence digest는 key 순서와 set 순서에 결정적이다`() {
        val tabs = capability(
            namespace = "tabs",
            member = "query",
            permissions = linkedSetOf("tabs", "activeTab"),
            manifestKeys = linkedSetOf("permissions", "host_permissions"),
        )
        val alarms = capability(namespace = "alarms", member = "getAll")
        val first = base.copy(apiCapabilities = listOf(tabs, alarms))
        val reordered = base.copy(
            apiCapabilities = listOf(
                alarms,
                tabs.copy(
                    permissions = linkedSetOf("activeTab", "tabs"),
                    manifestKeys = linkedSetOf("host_permissions", "permissions"),
                ),
            ),
        )

        assertEquals(first.canonicalSha256(), reordered.canonicalSha256())
    }

    @Test
    fun `canonical profile digest는 capability evidence의 모든 필드를 봉인한다`() {
        val evidence = capability()
        val profile = base.copy(apiCapabilities = listOf(evidence))
        val variations = listOf(
            evidence.copy(key = evidence.key.copy(namespace = "alarms")),
            evidence.copy(key = evidence.key.copy(member = "getAll")),
            evidence.copy(key = evidence.key.copy(context = ApiExecutionContext.EXTENSION_PAGE)),
            evidence.copy(status = ApiCapabilityStatus.EXPOSED),
            evidence.copy(permissions = evidence.permissions + "activeTab"),
            evidence.copy(manifestKeys = evidence.manifestKeys + "host_permissions"),
            evidence.copy(detail = "다른 결과"),
            evidence.copy(probeId = "tabs-query-other"),
            evidence.copy(probeCatalogSha256 = "b".repeat(64)),
        )
        val digest = profile.canonicalSha256()

        variations.forEach { changed ->
            assertNotEquals(
                digest,
                base.copy(apiCapabilities = listOf(changed)).canonicalSha256(),
                changed.toString(),
            )
        }
    }

    private fun capability(
        namespace: String = "tabs",
        member: String = "query",
        permissions: Set<String> = setOf("tabs"),
        manifestKeys: Set<String> = setOf("permissions"),
    ) = ApiCapabilityEvidence(
        key = ApiCapabilityKey(namespace, member, ApiExecutionContext.SERVICE_WORKER),
        status = ApiCapabilityStatus.VERIFIED,
        permissions = permissions,
        manifestKeys = manifestKeys,
        detail = "안전한 호출 성공",
        probeId = "$namespace-$member",
        probeCatalogSha256 = "a".repeat(64),
    )
}
