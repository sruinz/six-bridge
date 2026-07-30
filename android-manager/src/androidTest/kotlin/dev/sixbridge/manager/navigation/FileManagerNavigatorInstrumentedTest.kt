package dev.sixbridge.manager.navigation

import android.app.DownloadManager
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
    fun production_navigator_finds_privileged_view_downloads_handler_without_apk_grants() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = requireNotNull(
            AndroidFileManagerNavigator(context.packageManager).launcherIntent(),
        )

        assertEquals(DownloadManager.ACTION_VIEW_DOWNLOADS, intent.action)
        assertNotNull(intent.component)
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            context.packageManager.checkPermission(
                MANAGE_DOCUMENTS_PERMISSION,
                requireNotNull(intent.component).packageName,
            ),
        )
        assertNull(intent.data)
        assertNull(intent.type)
        assertNull(intent.clipData)
        assertNull(intent.extras)
        assertEquals(0, intent.flags and URI_PERMISSION_FLAGS)
    }

    private companion object {
        const val MANAGE_DOCUMENTS_PERMISSION = "android.permission.MANAGE_DOCUMENTS"
        const val URI_PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
