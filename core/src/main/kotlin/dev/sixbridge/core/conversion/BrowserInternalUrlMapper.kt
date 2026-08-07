package dev.sixbridge.core.conversion

import dev.sixbridge.core.profile.BrowserProfile

internal enum class BrowserInternalUrlStatus { VERIFIED, MANUAL, UNSUPPORTED }

internal data class BrowserInternalUrlRule(
    val id: String,
    val original: String,
    val replacement: String,
    val status: BrowserInternalUrlStatus,
)

internal object BrowserInternalUrlMapper {
    fun classify(value: String, profile: BrowserProfile): BrowserInternalUrlRule? {
        val rule = RULES.firstOrNull { it.original == value } ?: return null
        return rule.takeIf {
            it.status != BrowserInternalUrlStatus.VERIFIED ||
                EXTENSIONS_ROOT_FEATURE in profile.supportedFeatures
        }
    }

    fun evidenceDetail(rule: BrowserInternalUrlRule): String =
        "browser-internal-url:v1;rule=${rule.id};" +
            "status=${rule.status.name.lowercase()};" +
            "from=${rule.original};to=${rule.replacement}"

    fun parseEvidenceDetail(detail: String): BrowserInternalUrlRule? =
        RULES.singleOrNull { evidenceDetail(it) == detail }

    fun ruleById(id: String): BrowserInternalUrlRule? =
        RULES.singleOrNull { it.id == id }

    private val RULES = listOf(
        BrowserInternalUrlRule(
            id = "extensions.chrome.root",
            original = "chrome://extensions",
            replacement = "internet://extension",
            status = BrowserInternalUrlStatus.VERIFIED,
        ),
        BrowserInternalUrlRule(
            id = "extensions.chrome.root-slash",
            original = "chrome://extensions/",
            replacement = "internet://extension",
            status = BrowserInternalUrlStatus.VERIFIED,
        ),
        BrowserInternalUrlRule(
            id = "extensions.edge.root",
            original = "edge://extensions",
            replacement = "internet://extension",
            status = BrowserInternalUrlStatus.VERIFIED,
        ),
        BrowserInternalUrlRule(
            id = "extensions.edge.root-slash",
            original = "edge://extensions/",
            replacement = "internet://extension",
            status = BrowserInternalUrlStatus.VERIFIED,
        ),
        BrowserInternalUrlRule(
            id = "password-manager.chrome.settings",
            original = "chrome://password-manager/settings",
            replacement = "/sixbridge/internal-url-manual-password-manager.v1.html",
            status = BrowserInternalUrlStatus.MANUAL,
        ),
        BrowserInternalUrlRule(
            id = "flags.chrome.root",
            original = "chrome://flags",
            replacement = "/sixbridge/internal-url-unsupported-flags.v1.html",
            status = BrowserInternalUrlStatus.UNSUPPORTED,
        ),
        BrowserInternalUrlRule(
            id = "inspect.chrome.root",
            original = "chrome://inspect",
            replacement = "/sixbridge/internal-url-unsupported-inspect.v1.html",
            status = BrowserInternalUrlStatus.UNSUPPORTED,
        ),
    )

    private const val EXTENSIONS_ROOT_FEATURE =
        "browser.internal-url.extensions-root"
}
