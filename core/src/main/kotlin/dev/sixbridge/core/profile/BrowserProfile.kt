package dev.sixbridge.core.profile

import java.util.Collections

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
    val detectedApis: Set<String>,
    val wrapperContract: WrapperContract,
    val sourceApkSha256: String,
    val installationApkSetSha256: String,
    val sourceApkSignerSha256: String,
    val profileDifferences: List<String>,
    val supportedFeatures: Set<String>,
    val unavailableFeatures: Set<String>,
    apiCapabilities: List<ApiCapabilityEvidence>,
) {
    constructor(
        browserPackage: String,
        versionName: String,
        versionCode: Long,
        majorVersion: Int,
        supportedApis: Set<String>,
        detectedApis: Set<String> = supportedApis,
        wrapperContract: WrapperContract,
        sourceApkSha256: String = "",
        installationApkSetSha256: String = sourceApkSha256,
        sourceApkSignerSha256: String = "",
        profileDifferences: List<String> = emptyList(),
        supportedFeatures: Set<String> = emptySet(),
        unavailableFeatures: Set<String> = emptySet(),
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
        apiCapabilities: List<ApiCapabilityEvidence>,
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
