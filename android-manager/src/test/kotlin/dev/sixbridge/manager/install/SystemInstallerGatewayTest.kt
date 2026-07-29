package dev.sixbridge.manager.install

import android.content.Intent
import android.net.Uri
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SystemInstallerGatewayTest {
    @Test
    fun `삼성 허용 installer만 명시하고 APK URI 읽기 권한을 부여한다`() {
        val uri = Uri.parse("content://dev.sixbridge.manager.install/import/wrapper.apk")
        val target = SystemInstallerTarget(
            packageName = "com.google.android.packageinstaller",
            activityName = "com.android.packageinstaller.PackageInstallerActivity",
            isSystemApp = true,
        )

        val intent = systemInstallerIntent(uri, target)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data?.scheme)
        assertEquals(target.packageName, intent.component?.packageName)
        assertEquals(target.activityName, intent.component?.className)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertNotNull(intent.clipData)
    }

    @Test
    fun `다른 system installer는 삼성 허용 대상으로 인정하지 않는다`() {
        assertFalse(
            isSamsungAllowedInstaller(
                SystemInstallerTarget("com.vendor.installer", "Installer", true),
            ),
        )
    }

    @Test
    fun `non-system Samsung installer는 허용 대상으로 인정하지 않는다`() {
        assertFalse(
            isSamsungAllowedInstaller(
                SystemInstallerTarget(
                    "com.google.android.packageinstaller",
                    "Installer",
                    isSystemApp = false,
                ),
            ),
        )
    }
}
