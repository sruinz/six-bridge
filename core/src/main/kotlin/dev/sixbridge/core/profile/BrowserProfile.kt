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
    val wrapperContract: WrapperContract,
    val sourceApkSha256: String = "",
    val profileDifferences: List<String> = emptyList(),
) {
    val isSupportedBaseline: Boolean
        get() = majorVersion == SUPPORTED_MAJOR_VERSION &&
            wrapperContract.isComplete &&
            profileDifferences.isEmpty()

    val support: ProfileSupport
        get() = if (isSupportedBaseline) ProfileSupport.SUPPORTED else ProfileSupport.UNKNOWN

    companion object {
        const val SUPPORTED_MAJOR_VERSION = 30
        const val BASELINE_VERSION = "30.0.2.61"

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
