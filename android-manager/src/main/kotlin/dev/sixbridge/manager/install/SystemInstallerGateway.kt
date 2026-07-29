package dev.sixbridge.manager.install

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import java.io.File

data class SystemInstallerTarget(
    val packageName: String,
    val activityName: String,
    val isSystemApp: Boolean,
)

data class SystemInstallerLaunch(
    val uri: Uri,
    val intent: Intent,
)

sealed interface SystemInstallerLaunchResult {
    data class Ready(val launch: SystemInstallerLaunch) : SystemInstallerLaunchResult

    data object MyFilesFallbackRequired : SystemInstallerLaunchResult
}

interface SystemInstallerGateway {
    fun canRequestPackageInstalls(): Boolean
    fun unknownSourcesSettingsIntent(): Intent
    fun createLaunch(apk: File): SystemInstallerLaunchResult
    fun revoke(apk: File)
}

internal const val SAMSUNG_ALLOWED_INSTALLER = "com.google.android.packageinstaller"
internal const val APK_MIME = "application/vnd.android.package-archive"

internal fun isSamsungAllowedInstaller(target: SystemInstallerTarget): Boolean =
    target.isSystemApp && target.packageName == SAMSUNG_ALLOWED_INSTALLER

internal fun systemInstallerIntent(
    uri: Uri,
    target: SystemInstallerTarget,
): Intent {
    require(isSamsungAllowedInstaller(target))
    return Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, APK_MIME)
        .setClassName(target.packageName, target.activityName)
        .apply {
            clipData = ClipData.newRawUri("wrapper.apk", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
}
