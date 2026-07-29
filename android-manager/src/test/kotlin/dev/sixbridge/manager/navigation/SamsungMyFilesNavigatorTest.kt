package dev.sixbridge.manager.navigation

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SamsungMyFilesNavigatorTest {
    @Test
    fun `삼성 내 파일 launcher만 열고 APK URI와 권한을 넣지 않는다`() {
        val apkUri = Uri.parse("content://exports/wrapper.apk")
        val source = Intent(Intent.ACTION_VIEW, apkUri)
            .setPackage("com.sec.android.app.myfiles")
            .setType("application/vnd.android.package-archive")
        source.clipData = ClipData.newRawUri("apk", apkUri)
        source.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        val navigator = DefaultSamsungMyFilesNavigator { packageName ->
            assertEquals("com.sec.android.app.myfiles", packageName)
            source
        }

        val intent = requireNotNull(navigator.launcherIntent())

        assertNotSame(source, intent)
        assertEquals("com.sec.android.app.myfiles", intent.`package`)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertNull(intent.data)
        assertNull(intent.type)
        assertNull(intent.clipData)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK, intent.flags)
    }

    @Test
    fun `삼성 내 파일 launcher가 없으면 null을 반환한다`() {
        val navigator = DefaultSamsungMyFilesNavigator { null }

        assertNull(navigator.launcherIntent())
    }
}
