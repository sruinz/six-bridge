package dev.sixbridge.core.conversion

import dev.sixbridge.core.profile.BrowserProfile
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
}
