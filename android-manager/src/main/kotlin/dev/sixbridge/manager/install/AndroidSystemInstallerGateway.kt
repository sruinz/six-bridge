package dev.sixbridge.manager.install

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

class AndroidSystemInstallerGateway(
    context: Context,
) : SystemInstallerGateway {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager

    override fun canRequestPackageInstalls(): Boolean =
        packageManager.canRequestPackageInstalls()

    override fun unknownSourcesSettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${applicationContext.packageName}"),
        )

    override fun createLaunch(apk: File): SystemInstallerLaunchResult {
        val uri = uriFor(apk)
        val resolveInfo = packageManager.resolveActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .setPackage(SAMSUNG_ALLOWED_INSTALLER),
            PackageManager.MATCH_DEFAULT_ONLY,
        ) ?: return SystemInstallerLaunchResult.MyFilesFallbackRequired
        val activityInfo = resolveInfo.activityInfo
            ?: return SystemInstallerLaunchResult.MyFilesFallbackRequired
        val target = SystemInstallerTarget(
            packageName = activityInfo.packageName,
            activityName = activityInfo.name,
            isSystemApp = activityInfo.applicationInfo.flags and
                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
        )
        if (!isSamsungAllowedInstaller(target)) {
            return SystemInstallerLaunchResult.MyFilesFallbackRequired
        }
        return SystemInstallerLaunchResult.Ready(
            SystemInstallerLaunch(
                uri = uri,
                intent = systemInstallerIntent(uri, target),
            ),
        )
    }

    override fun revoke(apk: File) {
        applicationContext.revokeUriPermission(
            uriFor(apk),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
    }

    private fun uriFor(apk: File): Uri = FileProvider.getUriForFile(
        applicationContext,
        "${applicationContext.packageName}.install",
        apk,
    )
}

class InstallHandoffFileProvider : FileProvider()
