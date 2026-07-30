package dev.sixbridge.manager.navigation

import android.app.DownloadManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager

interface FileManagerNavigator {
    fun launcherIntent(): Intent?
}

internal data class FileManagerHandler(
    val component: ComponentName,
    val exported: Boolean,
    val enabled: Boolean,
    val managesDocuments: Boolean,
)

internal class DefaultFileManagerNavigator(
    private val viewDownloadsHandlers: () -> List<FileManagerHandler>,
    private val myFilesLauncherComponent: () -> ComponentName?,
) : FileManagerNavigator {
    override fun launcherIntent(): Intent? {
        val systemDocuments = viewDownloadsHandlers()
            .asSequence()
            .filter {
                it.exported &&
                    it.enabled &&
                    it.managesDocuments
            }
            .sortedWith(
                compareBy<FileManagerHandler>(
                    { it.component.packageName },
                    { it.component.className },
                ),
            )
            .firstOrNull()

        return systemDocuments?.let {
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setComponent(it.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } ?: myFilesLauncherComponent()?.let {
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(it)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

private class LegacyFileManagerNavigator(
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
) : FileManagerNavigator by LegacyFileManagerNavigator(
    canResolve = { packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null },
    chooser = { Intent.createChooser(it, chooserTitle) },
)
