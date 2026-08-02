@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package dev.sixbridge.core.profile

import java.util.Collections
import kotlin.jvm.internal.DefaultConstructorMarker

data class WrapperContract(
    val discoveryUri: Boolean,
    val extensionMetadata: Boolean,
    val contentProvider: Boolean,
    val devExtensionPath: Boolean,
) {
    val isComplete: Boolean
        get() = discoveryUri && extensionMetadata && contentProvider && devExtensionPath

    companion object {
        fun complete() = WrapperContract(
            discoveryUri = true,
            extensionMetadata = true,
            contentProvider = true,
            devExtensionPath = true,
        )
    }
}

enum class ProfileSupport {
    SUPPORTED,
    UNKNOWN,
}

class BrowserProfile(
    val browserPackage: String,
    val versionName: String,
    val versionCode: Long,
    val majorVersion: Int,
    val supportedApis: Set<String>,
    val detectedApis: Set<String> = supportedApis,
    val wrapperContract: WrapperContract,
    val sourceApkSha256: String = "",
    val installationApkSetSha256: String = sourceApkSha256,
    val sourceApkSignerSha256: String = "",
    val profileDifferences: List<String> = emptyList(),
    val supportedFeatures: Set<String> = emptySet(),
    val unavailableFeatures: Set<String> = emptySet(),
    apiCapabilities: List<ApiCapabilityEvidence> = emptyList(),
) {
    @Deprecated("legacy binary ABI bridge", level = DeprecationLevel.HIDDEN)
    constructor(
        browserPackage: String,
        versionName: String,
        versionCode: Long,
        majorVersion: Int,
        supportedApis: Set<String>,
        detectedApis: Set<String>,
        wrapperContract: WrapperContract,
        sourceApkSha256: String,
        installationApkSetSha256: String,
        sourceApkSignerSha256: String,
        profileDifferences: List<String>,
        supportedFeatures: Set<String>,
        unavailableFeatures: Set<String>,
    ) : this(
        browserPackage,
        versionName,
        versionCode,
        majorVersion,
        supportedApis,
        detectedApis,
        wrapperContract,
        sourceApkSha256,
        installationApkSetSha256,
        sourceApkSignerSha256,
        profileDifferences,
        supportedFeatures,
        unavailableFeatures,
        emptyList(),
    )

    @Deprecated("legacy binary ABI bridge", level = DeprecationLevel.HIDDEN)
    constructor(
        browserPackage: String,
        versionName: String,
        versionCode: Long,
        majorVersion: Int,
        supportedApis: Set<String>,
        detectedApis: Set<String>?,
        wrapperContract: WrapperContract,
        sourceApkSha256: String?,
        installationApkSetSha256: String?,
        sourceApkSignerSha256: String?,
        profileDifferences: List<String>?,
        supportedFeatures: Set<String>?,
        unavailableFeatures: Set<String>?,
        mask: Int,
        @Suppress("UNUSED_PARAMETER") marker: DefaultConstructorMarker?,
    ) : this(
        browserPackage,
        versionName,
        versionCode,
        majorVersion,
        supportedApis,
        if (mask and LEGACY_DETECTED_APIS_MASK != 0) {
            supportedApis
        } else {
            requireNotNull(detectedApis)
        },
        wrapperContract,
        if (mask and LEGACY_SOURCE_APK_MASK != 0) "" else requireNotNull(sourceApkSha256),
        if (mask and LEGACY_INSTALLATION_APK_SET_MASK != 0) {
            if (mask and LEGACY_SOURCE_APK_MASK != 0) "" else requireNotNull(sourceApkSha256)
        } else {
            requireNotNull(installationApkSetSha256)
        },
        if (mask and LEGACY_SIGNER_MASK != 0) "" else requireNotNull(sourceApkSignerSha256),
        if (mask and LEGACY_DIFFERENCES_MASK != 0) emptyList() else requireNotNull(profileDifferences),
        if (mask and LEGACY_SUPPORTED_FEATURES_MASK != 0) {
            emptySet()
        } else {
            requireNotNull(supportedFeatures)
        },
        if (mask and LEGACY_UNAVAILABLE_FEATURES_MASK != 0) {
            emptySet()
        } else {
            requireNotNull(unavailableFeatures)
        },
        emptyList(),
    )

    val apiCapabilities: List<ApiCapabilityEvidence> = Collections.unmodifiableList(
        apiCapabilities.sortedWith(API_CAPABILITY_COMPARATOR),
    )

    val isSupportedBaseline: Boolean
        get() = majorVersion == SUPPORTED_MAJOR_VERSION &&
            wrapperContract.isComplete &&
            sourceApkSignerSha256 == SAMSUNG_SIGNER_SHA256 &&
            profileDifferences.isEmpty() &&
            (
                versionName != BASELINE_VERSION ||
                    sourceApkSha256 == BASELINE_APK_SHA256
                )

    val support: ProfileSupport
        get() = if (isSupportedBaseline) ProfileSupport.SUPPORTED else ProfileSupport.UNKNOWN

    fun copy(
        browserPackage: String = this.browserPackage,
        versionName: String = this.versionName,
        versionCode: Long = this.versionCode,
        majorVersion: Int = this.majorVersion,
        supportedApis: Set<String> = this.supportedApis,
        detectedApis: Set<String> = this.detectedApis,
        wrapperContract: WrapperContract = this.wrapperContract,
        sourceApkSha256: String = this.sourceApkSha256,
        installationApkSetSha256: String = this.installationApkSetSha256,
        sourceApkSignerSha256: String = this.sourceApkSignerSha256,
        profileDifferences: List<String> = this.profileDifferences,
        supportedFeatures: Set<String> = this.supportedFeatures,
        unavailableFeatures: Set<String> = this.unavailableFeatures,
        apiCapabilities: List<ApiCapabilityEvidence> = this.apiCapabilities,
    ) = BrowserProfile(
        browserPackage,
        versionName,
        versionCode,
        majorVersion,
        supportedApis,
        detectedApis,
        wrapperContract,
        sourceApkSha256,
        installationApkSetSha256,
        sourceApkSignerSha256,
        profileDifferences,
        supportedFeatures,
        unavailableFeatures,
        apiCapabilities,
    )

    @Deprecated("legacy binary ABI bridge", level = DeprecationLevel.HIDDEN)
    fun copy(
        browserPackage: String,
        versionName: String,
        versionCode: Long,
        majorVersion: Int,
        supportedApis: Set<String>,
        detectedApis: Set<String>,
        wrapperContract: WrapperContract,
        sourceApkSha256: String,
        installationApkSetSha256: String,
        sourceApkSignerSha256: String,
        profileDifferences: List<String>,
        supportedFeatures: Set<String>,
        unavailableFeatures: Set<String>,
    ) = BrowserProfile(
        browserPackage,
        versionName,
        versionCode,
        majorVersion,
        supportedApis,
        detectedApis,
        wrapperContract,
        sourceApkSha256,
        installationApkSetSha256,
        sourceApkSignerSha256,
        profileDifferences,
        supportedFeatures,
        unavailableFeatures,
        apiCapabilities,
    )

    operator fun component1(): String = browserPackage
    operator fun component2(): String = versionName
    operator fun component3(): Long = versionCode
    operator fun component4(): Int = majorVersion
    operator fun component5(): Set<String> = supportedApis
    operator fun component6(): Set<String> = detectedApis
    operator fun component7(): WrapperContract = wrapperContract
    operator fun component8(): String = sourceApkSha256
    operator fun component9(): String = installationApkSetSha256
    operator fun component10(): String = sourceApkSignerSha256
    operator fun component11(): List<String> = profileDifferences
    operator fun component12(): Set<String> = supportedFeatures
    operator fun component13(): Set<String> = unavailableFeatures

    fun validate() {
        require((supportedFeatures intersect unavailableFeatures).isEmpty()) {
            "지원 기능과 미지원 기능 증거는 겹칠 수 없습니다."
        }
        require(
            apiCapabilities.map(ApiCapabilityEvidence::key).distinct().size ==
                apiCapabilities.size,
        ) {
            "API capability key는 중복될 수 없습니다."
        }
        require(
            apiCapabilities.none {
                it.status == ApiCapabilityStatus.INTERNAL && it.key.namespace in supportedApis
            },
        ) {
            "INTERNAL API namespace는 supportedApis에 포함될 수 없습니다."
        }
    }

    fun canonicalSha256(): String {
        validate()
        return BrowserProfileDigest.sha256(this)
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is BrowserProfile &&
            browserPackage == other.browserPackage &&
            versionName == other.versionName &&
            versionCode == other.versionCode &&
            majorVersion == other.majorVersion &&
            supportedApis == other.supportedApis &&
            detectedApis == other.detectedApis &&
            wrapperContract == other.wrapperContract &&
            sourceApkSha256 == other.sourceApkSha256 &&
            installationApkSetSha256 == other.installationApkSetSha256 &&
            sourceApkSignerSha256 == other.sourceApkSignerSha256 &&
            profileDifferences == other.profileDifferences &&
            supportedFeatures == other.supportedFeatures &&
            unavailableFeatures == other.unavailableFeatures &&
            apiCapabilities == other.apiCapabilities

    override fun hashCode(): Int {
        var result = browserPackage.hashCode()
        result = 31 * result + versionName.hashCode()
        result = 31 * result + versionCode.hashCode()
        result = 31 * result + majorVersion
        result = 31 * result + supportedApis.hashCode()
        result = 31 * result + detectedApis.hashCode()
        result = 31 * result + wrapperContract.hashCode()
        result = 31 * result + sourceApkSha256.hashCode()
        result = 31 * result + installationApkSetSha256.hashCode()
        result = 31 * result + sourceApkSignerSha256.hashCode()
        result = 31 * result + profileDifferences.hashCode()
        result = 31 * result + supportedFeatures.hashCode()
        result = 31 * result + unavailableFeatures.hashCode()
        return 31 * result + apiCapabilities.hashCode()
    }

    override fun toString(): String =
        "BrowserProfile(browserPackage=$browserPackage, versionName=$versionName, " +
            "versionCode=$versionCode, majorVersion=$majorVersion, " +
            "supportedApis=$supportedApis, detectedApis=$detectedApis, " +
            "wrapperContract=$wrapperContract, sourceApkSha256=$sourceApkSha256, " +
            "installationApkSetSha256=$installationApkSetSha256, " +
            "sourceApkSignerSha256=$sourceApkSignerSha256, " +
            "profileDifferences=$profileDifferences, supportedFeatures=$supportedFeatures, " +
            "unavailableFeatures=$unavailableFeatures, apiCapabilities=$apiCapabilities)"

    companion object {
        private const val LEGACY_DETECTED_APIS_MASK = 1 shl 5
        private const val LEGACY_SOURCE_APK_MASK = 1 shl 7
        private const val LEGACY_INSTALLATION_APK_SET_MASK = 1 shl 8
        private const val LEGACY_SIGNER_MASK = 1 shl 9
        private const val LEGACY_DIFFERENCES_MASK = 1 shl 10
        private const val LEGACY_SUPPORTED_FEATURES_MASK = 1 shl 11
        private const val LEGACY_UNAVAILABLE_FEATURES_MASK = 1 shl 12

        @JvmStatic
        @JvmName("copy\$default")
        @Deprecated("legacy binary ABI bridge", level = DeprecationLevel.HIDDEN)
        fun legacyCopyDefault(
            profile: BrowserProfile,
            browserPackage: String?,
            versionName: String?,
            versionCode: Long,
            majorVersion: Int,
            supportedApis: Set<String>?,
            detectedApis: Set<String>?,
            wrapperContract: WrapperContract?,
            sourceApkSha256: String?,
            installationApkSetSha256: String?,
            sourceApkSignerSha256: String?,
            profileDifferences: List<String>?,
            supportedFeatures: Set<String>?,
            unavailableFeatures: Set<String>?,
            mask: Int,
            @Suppress("UNUSED_PARAMETER") marker: Any?,
        ): BrowserProfile = profile.copy(
            browserPackage = if (mask and (1 shl 0) != 0) {
                profile.browserPackage
            } else {
                requireNotNull(browserPackage)
            },
            versionName = if (mask and (1 shl 1) != 0) {
                profile.versionName
            } else {
                requireNotNull(versionName)
            },
            versionCode = if (mask and (1 shl 2) != 0) profile.versionCode else versionCode,
            majorVersion = if (mask and (1 shl 3) != 0) profile.majorVersion else majorVersion,
            supportedApis = if (mask and (1 shl 4) != 0) {
                profile.supportedApis
            } else {
                requireNotNull(supportedApis)
            },
            detectedApis = if (mask and (1 shl 5) != 0) {
                profile.detectedApis
            } else {
                requireNotNull(detectedApis)
            },
            wrapperContract = if (mask and (1 shl 6) != 0) {
                profile.wrapperContract
            } else {
                requireNotNull(wrapperContract)
            },
            sourceApkSha256 = if (mask and (1 shl 7) != 0) {
                profile.sourceApkSha256
            } else {
                requireNotNull(sourceApkSha256)
            },
            installationApkSetSha256 = if (mask and (1 shl 8) != 0) {
                profile.installationApkSetSha256
            } else {
                requireNotNull(installationApkSetSha256)
            },
            sourceApkSignerSha256 = if (mask and (1 shl 9) != 0) {
                profile.sourceApkSignerSha256
            } else {
                requireNotNull(sourceApkSignerSha256)
            },
            profileDifferences = if (mask and (1 shl 10) != 0) {
                profile.profileDifferences
            } else {
                requireNotNull(profileDifferences)
            },
            supportedFeatures = if (mask and (1 shl 11) != 0) {
                profile.supportedFeatures
            } else {
                requireNotNull(supportedFeatures)
            },
            unavailableFeatures = if (mask and (1 shl 12) != 0) {
                profile.unavailableFeatures
            } else {
                requireNotNull(unavailableFeatures)
            },
            apiCapabilities = profile.apiCapabilities,
        )

        const val SUPPORTED_MAJOR_VERSION = 30
        const val BASELINE_VERSION = "30.0.2.61"
        const val BASELINE_APK_SHA256 =
            "6fad5501a3f9823c7dd9fafdfe1204f7246e487c8dea9c192426c07c8c48c981"
        const val SAMSUNG_SIGNER_SHA256 =
            "34df0e7a9f1cf1892e45c056b4973cd81ccf148a4050d11aea4ac5a65f900a42"

        val POC_SUPPORTED_APIS = setOf(
            "action",
            "contextMenus",
            "i18n",
            "offscreen",
            "runtime",
            "storage",
            "tabs",
            "webRequest",
        )

        val BASELINE_APIS = setOf(
            "action",
            "alarms",
            "app",
            "app.runtime",
            "appstoreService",
            "automation",
            "browserAction",
            "clientService",
            "clipboard",
            "commands",
            "contentSettings",
            "contextMenus",
            "cookies",
            "debugger",
            "declarativeContent",
            "declarativeNetRequest",
            "declarativeWebRequest",
            "dom",
            "events",
            "extension",
            "extensionTypes",
            "fileHandlers",
            "gcm",
            "history",
            "i18n",
            "iconVariants",
            "identity",
            "idle",
            "inputMethodPrivate",
            "management",
            "notifications",
            "offscreen",
            "pageAction",
            "permissions",
            "privacy",
            "protocolHandlers",
            "proxy",
            "runtime",
            "scripting",
            "scriptsInternal",
            "search",
            "sidePanel",
            "sixLogging",
            "storage",
            "tabGroups",
            "tabs",
            "trans",
            "tts",
            "ttsEngine",
            "types",
            "userScripts",
            "webAuthenticationProxy",
            "webNavigation",
            "webRequest",
            "webRequestInternal",
            "webViewRequest",
            "webstorePrivate",
            "windows",
        )
    }
}
