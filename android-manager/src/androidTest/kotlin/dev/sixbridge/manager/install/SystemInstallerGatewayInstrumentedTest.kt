package dev.sixbridge.manager.install

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemInstallerGatewayInstrumentedTest {
    @Test
    fun install_provider_cannot_serve_cache_files_outside_retained_imports() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apk = File(context.cacheDir, "outside-imports-${UUID.randomUUID()}.apk").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        try {
            assertThrows(IllegalArgumentException::class.java) {
                AndroidSystemInstallerGateway(context).createLaunch(apk)
            }
        } finally {
            apk.delete()
        }
    }

    @Test
    fun retained_import_APK_uses_install_provider_and_the_allowed_system_installer() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val apk = File(context.cacheDir, "imports/system-installer-${UUID.randomUUID()}.apk").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3))
        }

        try {
            val result = AndroidSystemInstallerGateway(context).createLaunch(apk)
            assertTrue(result is SystemInstallerLaunchResult.Ready)
            val launch = (result as SystemInstallerLaunchResult.Ready).launch

            assertEquals("content", launch.uri.scheme)
            assertEquals("${context.packageName}.install", launch.uri.authority)
            assertEquals(Intent.ACTION_VIEW, launch.intent.action)
            assertEquals("application/vnd.android.package-archive", launch.intent.type)
            assertEquals("com.google.android.packageinstaller", launch.intent.component?.packageName)
            assertNotNull(launch.intent.component?.className)
            assertEquals(launch.uri, launch.intent.clipData?.getItemAt(0)?.uri)
            assertTrue(launch.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            assertEquals(0, launch.intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            assertTrue(context.contentResolver.openInputStream(launch.uri)?.use { it.readBytes() }
                ?.contentEquals(byteArrayOf(1, 2, 3)) == true)
        } finally {
            apk.delete()
        }
    }
}
