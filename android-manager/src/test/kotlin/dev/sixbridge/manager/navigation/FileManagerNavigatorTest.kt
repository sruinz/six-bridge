package dev.sixbridge.manager.navigation

import android.app.DownloadManager
import android.content.ComponentName
import android.content.Intent
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileManagerNavigatorTest {
    @Test
    fun `시스템 문서 앱은 package와 activity 이름 순으로 선택한다`() {
        val navigator = DefaultFileManagerNavigator(
            viewDownloadsHandlers = {
                listOf(
                    handler("z.documents", "ZActivity", managesDocuments = true),
                    handler("a.documents", "BActivity", managesDocuments = true),
                    handler("a.documents", "AActivity", managesDocuments = true),
                )
            },
            myFilesLauncherComponent = { ComponentName("my.files", "Launcher") },
        )

        val intent = requireNotNull(navigator.launcherIntent())

        assertEquals(ComponentName("a.documents", "AActivity"), intent.component)
        assertEquals(DownloadManager.ACTION_VIEW_DOWNLOADS, intent.action)
        assertEquals(setOf(Intent.CATEGORY_DEFAULT), intent.categories)
        assertSafeFileLaunch(intent)
    }

    @Test
    fun `exported enabled MANAGE_DOCUMENTS 후보만 선택한다`() {
        val navigator = DefaultFileManagerNavigator(
            viewDownloadsHandlers = {
                listOf(
                    handler(
                        "not.exported",
                        "Files",
                        exported = false,
                        managesDocuments = true,
                    ),
                    handler(
                        "not.enabled",
                        "Files",
                        enabled = false,
                        managesDocuments = true,
                    ),
                    handler("a.third.party", "Files", managesDocuments = false),
                    handler("system.documents", "Files", managesDocuments = true),
                )
            },
            myFilesLauncherComponent = { null },
        )

        assertEquals(
            ComponentName("system.documents", "Files"),
            requireNotNull(navigator.launcherIntent()).component,
        )
    }

    @Test
    fun `시스템 문서 앱이 없으면 Samsung My Files launcher로 대체한다`() {
        val navigator = DefaultFileManagerNavigator(
            viewDownloadsHandlers = { emptyList() },
            myFilesLauncherComponent = {
                ComponentName(
                    "com.sec.android.app.myfiles",
                    "com.sec.android.app.myfiles.ui.MainActivity",
                )
            },
        )

        val intent = requireNotNull(navigator.launcherIntent())

        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertEquals(setOf(Intent.CATEGORY_LAUNCHER), intent.categories)
        assertEquals("com.sec.android.app.myfiles", intent.component?.packageName)
        assertSafeFileLaunch(intent)
    }

    @Test
    fun `두 설치 경로가 모두 없으면 null을 반환한다`() {
        val navigator = DefaultFileManagerNavigator(
            viewDownloadsHandlers = { emptyList() },
            myFilesLauncherComponent = { null },
        )

        assertNull(navigator.launcherIntent())
    }

    private fun handler(
        packageName: String,
        className: String,
        exported: Boolean = true,
        enabled: Boolean = true,
        managesDocuments: Boolean,
    ) = FileManagerHandler(
        component = ComponentName(packageName, className),
        exported = exported,
        enabled = enabled,
        managesDocuments = managesDocuments,
    )

    private fun assertSafeFileLaunch(intent: Intent) {
        assertNull(intent.data)
        assertNull(intent.type)
        assertNull(intent.clipData)
        assertNull(intent.extras)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
        assertEquals(0, intent.flags and URI_PERMISSION_FLAGS)
    }

    private companion object {
        const val URI_PERMISSION_FLAGS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
    }
}
