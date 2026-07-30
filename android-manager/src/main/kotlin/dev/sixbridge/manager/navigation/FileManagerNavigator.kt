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

class AndroidFileManagerNavigator(
    packageManager: PackageManager,
) : FileManagerNavigator by DefaultFileManagerNavigator(
    viewDownloadsHandlers = {
        packageManager.queryIntentActivities(
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                .addCategory(Intent.CATEGORY_DEFAULT),
            PackageManager.MATCH_DEFAULT_ONLY,
        ).map { resolved ->
            FileManagerHandler(
                component = ComponentName(
                    resolved.activityInfo.packageName,
                    resolved.activityInfo.name,
                ),
                exported = resolved.activityInfo.exported,
                enabled = resolved.activityInfo.enabled,
                managesDocuments = packageManager.checkPermission(
                    MANAGE_DOCUMENTS_PERMISSION,
                    resolved.activityInfo.packageName,
                ) == PackageManager.PERMISSION_GRANTED,
            )
        }
    },
    myFilesLauncherComponent = {
        packageManager.getLaunchIntentForPackage(SAMSUNG_MY_FILES_PACKAGE)
            ?.component
    },
)

internal const val MANAGE_DOCUMENTS_PERMISSION = "android.permission.MANAGE_DOCUMENTS"
internal const val SAMSUNG_MY_FILES_PACKAGE = "com.sec.android.app.myfiles"
