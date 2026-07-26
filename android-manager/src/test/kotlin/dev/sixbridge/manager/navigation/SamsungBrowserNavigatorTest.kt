package dev.sixbridge.manager.navigation

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test

class SamsungBrowserNavigatorTest {
    @Test
    fun `Add-ons intent uses Samsung Internet public extension route`() {
        val navigator = DefaultSamsungBrowserNavigator(
            browserInstalled = { true },
            resolvesIntent = { true },
        )

        val route = navigator.addOnsRoute()

        assertEquals(Intent.ACTION_VIEW, route.action)
        assertEquals("internet://extension", route.data)
        assertEquals("com.sec.android.app.sbrowser", route.packageName)
    }

    @Test
    fun `availability reports missing browser before resolving Add-ons route`() {
        val navigator = DefaultSamsungBrowserNavigator(
            browserInstalled = { false },
            resolvesIntent = { error("must not resolve a missing browser") },
        )

        assertEquals(SamsungBrowserAvailability.SamsungBrowserMissing, navigator.availability())
    }

    @Test
    fun `availability reports unavailable when installed browser cannot resolve public route`() {
        val navigator = DefaultSamsungBrowserNavigator(
            browserInstalled = { true },
            resolvesIntent = { false },
        )

        assertEquals(SamsungBrowserAvailability.AddOnsIntentUnavailable, navigator.availability())
    }

    @Test
    fun `availability reports available when Samsung Internet resolves public route`() {
        val navigator = DefaultSamsungBrowserNavigator(
            browserInstalled = { true },
            resolvesIntent = { route ->
                route.action == Intent.ACTION_VIEW &&
                    route.data == "internet://extension" &&
                    route.packageName == "com.sec.android.app.sbrowser"
            },
        )

        assertEquals(SamsungBrowserAvailability.Available, navigator.availability())
    }
}
