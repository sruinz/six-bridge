package dev.sixbridge.manager.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPackageInstallGateway(
    context: Context,
) : PackageInstallGateway {
    private val applicationContext = context.applicationContext
    private val packageManager = applicationContext.packageManager
    private val installer = packageManager.packageInstaller

    override fun canRequestPackageInstalls(): Boolean =
        packageManager.canRequestPackageInstalls()

    override fun unknownSourcesSettingsIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${applicationContext.packageName}"),
        )

    override suspend fun createSession(packageName: String): Int = withContext(Dispatchers.IO) {
        val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
            }
        try {
            installer.createSession(parameters)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw PackageInstallSessionException(
                sessionId = null,
                code = "SESSION_CREATE_FAILED",
                message = "설치 session을 만들지 못했습니다.",
                cause = error,
            )
        }
    }

    override suspend fun writeSession(sessionId: Int, apk: File) {
        withContext(Dispatchers.IO) {
            require(apk.isFile && apk.length() > 0) { "설치할 APK가 올바르지 않습니다." }
            try {
                installer.openSession(sessionId).use { session ->
                    apk.inputStream().use { input ->
                        session.openWrite(BASE_APK_NAME, 0, apk.length()).use { output ->
                            input.copyTo(output)
                            session.fsync(output)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                throw PackageInstallSessionException(
                    sessionId = sessionId,
                    code = "WRITE_FAILED",
                    message = "설치 session에 APK를 기록하지 못했습니다.",
                    cause = error,
                )
            }
        }
    }

    override fun commit(sessionId: Int) {
        val callback = Intent(applicationContext, InstallResultReceiver::class.java)
            .setAction(InstallResultReceiver.ACTION_INSTALL_RESULT)
            .putExtra(InstallResultReceiver.EXTRA_CALLBACK_SESSION_ID, sessionId)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val intentSender = PendingIntent.getBroadcast(
            applicationContext,
            sessionId,
            callback,
            flags,
        ).intentSender
        installer.openSession(sessionId).use { session ->
            session.commit(intentSender)
        }
    }

    override fun abandon(sessionId: Int) {
        installer.abandonSession(sessionId)
    }

    override fun ownedSessions(): List<Int> =
        installer.mySessions.map(PackageInstaller.SessionInfo::getSessionId)

    private companion object {
        const val BASE_APK_NAME = "base.apk"
    }
}
