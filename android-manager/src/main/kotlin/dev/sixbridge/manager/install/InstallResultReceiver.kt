package dev.sixbridge.manager.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

data class PackageInstallCallback(
    val result: PackageInstallResult,
    val confirmationIntent: Intent? = null,
)

object PackageInstallEvents {
    private val channel = Channel<PackageInstallCallback>(Channel.UNLIMITED)

    val events: Flow<PackageInstallCallback> = channel.receiveAsFlow()

    internal fun dispatch(callback: PackageInstallCallback) {
        channel.trySend(callback)
    }
}

internal fun packageInstallResultFromStatus(
    sessionId: Int,
    status: Int,
    statusMessage: String?,
): PackageInstallResult? {
    if (sessionId < 0) return null
    return when (status) {
        PackageInstaller.STATUS_SUCCESS -> PackageInstallResult.Success(sessionId)
        PackageInstaller.STATUS_FAILURE_ABORTED -> PackageInstallResult.Cancelled(sessionId)
        in KNOWN_FAILURE_STATUSES -> PackageInstallResult.Failure(
            sessionId = sessionId,
            code = "PACKAGE_INSTALLER_${status.toString().replace('-', 'N')}",
            message = statusMessage
                ?.takeIf(String::isNotBlank)
                ?: "PackageInstaller가 설치를 완료하지 못했습니다.",
        )
        else -> null
    }
}

internal fun matchesPendingInstallSession(pending: PendingInstall?, sessionId: Int): Boolean =
    sessionId >= 0 && pending?.sessionId == sessionId

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val sessionId = intent.getIntExtra(
            PackageInstaller.EXTRA_SESSION_ID,
            intent.getIntExtra(EXTRA_CALLBACK_SESSION_ID, -1),
        )
        if (sessionId < 0) return
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val pending = runCatching {
                    SharedPreferencesPendingInstallStore(context).load()
                }.getOrNull()
                if (!matchesPendingInstallSession(pending, sessionId)) return
                val confirmation = confirmationIntent(intent)
                    ?.let { makeExplicitSystemIntent(context, it) }
                if (confirmation == null) {
                    persistAndNotify(
                        context,
                        PackageInstallResult.Failure(
                            sessionId = sessionId,
                            code = "MISSING_USER_ACTION_INTENT",
                            message = "설치 승인 화면을 확인할 수 없습니다.",
                        ),
                    )
                } else {
                    PackageInstallEvents.dispatch(
                        PackageInstallCallback(
                            result = PackageInstallResult.PendingUserAction(sessionId),
                            confirmationIntent = confirmation,
                        ),
                    )
                }
            }

            else -> packageInstallResultFromStatus(
                sessionId = sessionId,
                status = status,
                statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
            )?.let { persistAndNotify(context, it) }
        }
    }

    private fun persistAndNotify(context: Context, result: PackageInstallResult) {
        val recorded = runCatching {
            SharedPreferencesPendingInstallStore(context).recordTerminalResult(
                result = result,
                terminalAtEpochMillis = System.currentTimeMillis(),
            )
        }.getOrDefault(false)
        if (recorded) {
            PackageInstallEvents.dispatch(PackageInstallCallback(result))
        }
    }

    @Suppress("DEPRECATION")
    private fun confirmationIntent(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    private fun makeExplicitSystemIntent(context: Context, intent: Intent): Intent? {
        val resolved = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?: return null
        val flags = resolved.applicationInfo?.flags ?: return null
        val systemFlags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        if (flags and systemFlags == 0) return null
        return Intent(intent).setClassName(resolved.packageName, resolved.name)
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "dev.sixbridge.manager.action.INSTALL_RESULT"
        const val EXTRA_CALLBACK_SESSION_ID = "dev.sixbridge.manager.extra.CALLBACK_SESSION_ID"
    }
}

private val KNOWN_FAILURE_STATUSES = setOf(
    PackageInstaller.STATUS_FAILURE,
    PackageInstaller.STATUS_FAILURE_BLOCKED,
    PackageInstaller.STATUS_FAILURE_INVALID,
    PackageInstaller.STATUS_FAILURE_CONFLICT,
    PackageInstaller.STATUS_FAILURE_STORAGE,
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
    PackageInstaller.STATUS_FAILURE_TIMEOUT,
)
