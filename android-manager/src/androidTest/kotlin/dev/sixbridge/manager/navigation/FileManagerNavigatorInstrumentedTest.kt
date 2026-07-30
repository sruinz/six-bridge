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
class FileManagerNavigatorInstrumentedTest {
    @Test
    fun production_navigator_finds_files_category_handler_without_apk_grants() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chooser = requireNotNull(
            AndroidFileManagerNavigator(
                context.packageManager,
                "파일 앱 선택",
            ).launcherIntent(),
        )
        val target = requireNotNull(
            chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT),
        )

        assertEquals(Intent.ACTION_MAIN, target.action)
        assertEquals(Intent.CATEGORY_APP_FILES, target.selector?.categories?.single())
        assertNull(target.data)
        assertNull(target.type)
        assertNull(target.clipData)
        assertEquals(0, target.flags and URI_PERMISSION_FLAGS)
        assertEquals(0, chooser.flags and URI_PERMISSION_FLAGS)
        assertNotNull(
            context.packageManager.resolveActivity(
                target,
                PackageManager.MATCH_DEFAULT_ONLY,
            ),
        )
    }

    private companion object {
        const val URI_PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
