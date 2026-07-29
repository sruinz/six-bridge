package dev.sixbridge.manager.navigation

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SamsungMyFilesNavigatorInstrumentedTest {
    @Test
    fun production_navigator_finds_real_samsung_my_files_launcher_without_apk_grants() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = requireNotNull(
            AndroidSamsungMyFilesNavigator(context.packageManager).launcherIntent(),
        )

        assertEquals(SAMSUNG_MY_FILES_PACKAGE, intent.`package`)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertNull(intent.data)
        assertNull(intent.type)
        assertNull(intent.clipData)
        assertEquals(0, intent.flags and URI_PERMISSION_FLAGS)
        assertNotNull(
            context.packageManager.resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY,
            ),
        )
    }

    private companion object {
        const val SAMSUNG_MY_FILES_PACKAGE = "com.sec.android.app.myfiles"
        const val URI_PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
