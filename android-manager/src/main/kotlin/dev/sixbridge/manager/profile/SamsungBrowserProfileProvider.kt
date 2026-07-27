package dev.sixbridge.manager.profile

import dev.sixbridge.core.profile.BrowserProfile

sealed interface SamsungBrowserProfileResult {
    data class Supported(val profile: BrowserProfile) : SamsungBrowserProfileResult
    data object Missing : SamsungBrowserProfileResult
    data class Unknown(
        val profile: BrowserProfile?,
        val reasons: List<String>,
    ) : SamsungBrowserProfileResult
}

fun interface SamsungBrowserProfileProvider {
    suspend fun load(): SamsungBrowserProfileResult
}
