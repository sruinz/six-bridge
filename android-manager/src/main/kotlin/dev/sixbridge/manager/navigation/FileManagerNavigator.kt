package dev.sixbridge.manager.navigation

import android.content.Intent
import android.content.pm.PackageManager

interface FileManagerNavigator {
    fun launcherIntent(): Intent?
}

internal class DefaultFileManagerNavigator(
    private val canResolve: (Intent) -> Boolean,
    private val chooser: (Intent) -> Intent,
) : FileManagerNavigator {
    override fun launcherIntent(): Intent? {
        val target = filesIntent()
        if (!canResolve(target)) return null
        return chooser(target).apply {
            data = null
            type = null
            clipData = null
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun filesIntent(): Intent =
        Intent.makeMainSelectorActivity(
            Intent.ACTION_MAIN,
            Intent.CATEGORY_APP_FILES,
        ).apply {
            data = null
            type = null
            clipData = null
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
}

class AndroidFileManagerNavigator(
    packageManager: PackageManager,
    chooserTitle: String = "파일 앱 선택",
) : FileManagerNavigator by DefaultFileManagerNavigator(
    canResolve = { packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null },
    chooser = { Intent.createChooser(it, chooserTitle) },
)
