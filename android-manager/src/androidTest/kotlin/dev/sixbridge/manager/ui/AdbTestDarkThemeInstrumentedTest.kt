package dev.sixbridge.manager.ui

import android.content.ComponentName
import android.graphics.Color
import android.os.Build
import android.view.ContextThemeWrapper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sixbridge.manager.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbTestDarkThemeInstrumentedTest {
    @Test
    fun androidx_test_bridge_activities_use_black_starting_windows() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val bridgeActivities = listOf(
            "androidx.test.core.app.InstrumentationActivityInvoker\$BootstrapActivity",
            "androidx.test.core.app.InstrumentationActivityInvoker\$EmptyActivity",
            "androidx.test.core.app.InstrumentationActivityInvoker\$EmptyFloatingActivity",
        )

        bridgeActivities.forEach { className ->
            val component = ComponentName(testContext.packageName, className)
            @Suppress("DEPRECATION")
            val activityInfo = testContext.packageManager.getActivityInfo(component, 0)
            val themedContext = ContextThemeWrapper(testContext, activityInfo.theme)
            val attributes = buildList {
                add(android.R.attr.windowBackground)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    add(android.R.attr.windowSplashScreenBackground)
                }
            }.toIntArray()
            val values = themedContext.obtainStyledAttributes(attributes)
            try {
                assertEquals(
                    "$className window background",
                    Color.BLACK,
                    values.getColor(0, Color.MAGENTA),
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    assertEquals(
                        "$className splash background",
                        Color.BLACK,
                        values.getColor(1, Color.MAGENTA),
                    )
                }
            } finally {
                values.recycle()
            }
        }
    }

    @Test
    fun adb_test_activity_uses_black_window_and_dark_system_bars() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val component = ComponentName(context, ComponentActivity::class.java)
        @Suppress("DEPRECATION")
        val activityInfo = context.packageManager.getActivityInfo(component, 0)

        assertEquals(R.style.Theme_SixBridgeAdbTestDark, activityInfo.theme)

        val themedContext = ContextThemeWrapper(context, activityInfo.theme)
        val values = themedContext.obtainStyledAttributes(
            intArrayOf(
                android.R.attr.windowBackground,
                android.R.attr.statusBarColor,
                android.R.attr.navigationBarColor,
                android.R.attr.windowLightStatusBar,
                android.R.attr.windowLightNavigationBar,
            ),
        )
        try {
            assertEquals(Color.BLACK, values.getColor(0, Color.MAGENTA))
            assertEquals(Color.BLACK, values.getColor(1, Color.MAGENTA))
            assertEquals(Color.BLACK, values.getColor(2, Color.MAGENTA))
            assertFalse(values.getBoolean(3, true))
            assertFalse(values.getBoolean(4, true))
        } finally {
            values.recycle()
        }
    }
}
