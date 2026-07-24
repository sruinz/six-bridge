package dev.sixbridge.core.profile

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

data class BrowserProfile(
    val browserPackage: String,
    val versionName: String,
    val versionCode: Long,
    val majorVersion: Int,
    val supportedApis: Set<String>,
    val detectedApis: Set<String> = supportedApis,
    val wrapperContract: WrapperContract,
    val sourceApkSha256: String = "",
    val sourceApkSignerSha256: String = "",
    val profileDifferences: List<String> = emptyList(),
    val supportedFeatures: Set<String> = emptySet(),
    val unavailableFeatures: Set<String> = emptySet(),
) {
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

    fun validate() {
        require((supportedFeatures intersect unavailableFeatures).isEmpty()) {
            "지원 기능과 미지원 기능 증거는 겹칠 수 없습니다."
        }
    }

    fun canonicalSha256(): String {
        validate()
        return BrowserProfileDigest.sha256(this)
    }

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
