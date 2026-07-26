package dev.sixbridge.manager.navigation

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

data class AddOnsRoute(
    val action: String,
    val data: String,
    val packageName: String,
) {
    fun toIntent(): Intent = Intent(action, Uri.parse(data)).setPackage(packageName)
}

sealed interface SamsungBrowserAvailability {
    data object Available : SamsungBrowserAvailability
    data object SamsungBrowserMissing : SamsungBrowserAvailability
    data object AddOnsIntentUnavailable : SamsungBrowserAvailability
}

interface SamsungBrowserNavigator {
    fun addOnsIntent(): Intent
    fun availability(): SamsungBrowserAvailability
}

class DefaultSamsungBrowserNavigator(
    private val browserInstalled: () -> Boolean,
    private val resolvesIntent: (AddOnsRoute) -> Boolean,
) : SamsungBrowserNavigator {
    override fun addOnsIntent(): Intent = addOnsRoute().toIntent()

    internal fun addOnsRoute(): AddOnsRoute = AddOnsRoute(
        action = Intent.ACTION_VIEW,
        data = "internet://extension",
        packageName = SAMSUNG_BROWSER_PACKAGE,
    )

    override fun availability(): SamsungBrowserAvailability {
        if (!browserInstalled()) return SamsungBrowserAvailability.SamsungBrowserMissing
        return if (resolvesIntent(addOnsRoute())) {
            SamsungBrowserAvailability.Available
        } else {
            SamsungBrowserAvailability.AddOnsIntentUnavailable
        }
    }

    private companion object {
        const val SAMSUNG_BROWSER_PACKAGE = "com.sec.android.app.sbrowser"
    }
}

class AndroidSamsungBrowserNavigator(
    packageManager: PackageManager,
) : SamsungBrowserNavigator by DefaultSamsungBrowserNavigator(
    browserInstalled = {
        try {
            packageManager.getApplicationInfo(SAMSUNG_BROWSER_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    },
    resolvesIntent = { route ->
        packageManager.resolveActivity(route.toIntent(), PackageManager.MATCH_DEFAULT_ONLY) != null
    },
) {
    private companion object {
        const val SAMSUNG_BROWSER_PACKAGE = "com.sec.android.app.sbrowser"
    }
}
