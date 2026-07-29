package dev.sixbridge.manager.navigation

import android.content.Intent
import android.content.pm.PackageManager

interface SamsungMyFilesNavigator {
    fun launcherIntent(): Intent?
}

internal class DefaultSamsungMyFilesNavigator(
    private val getLaunchIntentForPackage: (String) -> Intent?,
) : SamsungMyFilesNavigator {
    override fun launcherIntent(): Intent? {
        val source = getLaunchIntentForPackage(SAMSUNG_MY_FILES_PACKAGE)
            ?: return null
        return Intent(source).apply {
            action = Intent.ACTION_MAIN
            setPackage(SAMSUNG_MY_FILES_PACKAGE)
            data = null
            type = null
            clipData = null
            flags = flags and URI_PERMISSION_FLAGS.inv()
        }
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

class AndroidSamsungMyFilesNavigator(
    packageManager: PackageManager,
) : SamsungMyFilesNavigator by DefaultSamsungMyFilesNavigator(
    packageManager::getLaunchIntentForPackage,
)
