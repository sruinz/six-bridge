package dev.sixbridge.manager.install

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import dev.sixbridge.manager.R
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

data class PackageInstallCallback(
    val result: PackageInstallResult,
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

internal fun pendingConfirmationFlags(existingFlags: Int): Int =
    existingFlags or Intent.FLAG_ACTIVITY_NEW_TASK

internal fun notificationHandoffAvailable(
    appNotificationsEnabled: Boolean,
    channelImportance: Int?,
): Boolean = appNotificationsEnabled &&
    channelImportance != null &&
    channelImportance != NotificationManager.IMPORTANCE_NONE

internal object ManagerAppVisibility {
    private val visibleActivities = AtomicInteger()

    fun onActivityStarted() {
        visibleActivities.incrementAndGet()
    }

    fun onActivityStopped() {
        visibleActivities.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }

    fun isVisible(): Boolean = visibleActivities.get() > 0
}

internal class PendingConfirmationDelivery(
    private val isAppVisible: () -> Boolean,
    private val launchForeground: (Intent) -> Unit,
    private val postBackgroundNotification: (Int, Intent) -> Unit,
) {
    fun deliver(sessionId: Int, confirmationIntent: Intent) {
        if (isAppVisible()) {
            launchForeground(confirmationIntent)
        } else {
            postBackgroundNotification(sessionId, confirmationIntent)
        }
    }
}

internal class PendingConfirmationHandoff {
    private var launched: Pair<Int, String>? = null

    @Synchronized
    fun handle(
        pending: PendingInstall?,
        sessionId: Int,
        confirmationIntent: Intent,
        isDelivered: (Int) -> Boolean,
        markDelivered: (Int) -> Boolean,
        launch: (Intent) -> Unit,
    ): Boolean {
        if (!matchesPendingInstallSession(pending, sessionId)) return false
        val owned = requireNotNull(pending)
        val key = sessionId to owned.importToken
        val alreadyDelivered = try {
            isDelivered(sessionId)
        } catch (_: Exception) {
            return false
        }
        if (alreadyDelivered) {
            if (launched == key) launched = null
            return false
        }
        if (launched != key) {
            try {
                launch(confirmationIntent)
            } catch (_: Exception) {
                return false
            }
            launched = key
        }
        val marked = runCatching { markDelivered(sessionId) }.getOrDefault(false)
        if (marked) launched = null
        return marked
    }
}

private val processPendingConfirmationHandoff = PendingConfirmationHandoff()

internal inline fun withValidRecoveryStoreSnapshot(
    snapshot: RecoveryStoreSnapshot,
    block: (RecoveryStoreSnapshot.Valid) -> Unit,
): Boolean {
    val valid = snapshot as? RecoveryStoreSnapshot.Valid ?: return false
    block(valid)
    return true
}

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val sessionId = intent.getIntExtra(
            PackageInstaller.EXTRA_SESSION_ID,
            intent.getIntExtra(EXTRA_CALLBACK_SESSION_ID, -1),
        )
        if (sessionId < 0) return
        val store = SharedPreferencesPendingInstallStore(context)
        val snapshot = runCatching { store.recoveryStoreSnapshot() }
            .getOrDefault(RecoveryStoreSnapshot.Invalid)
        withValidRecoveryStoreSnapshot(snapshot) { recovery ->
            when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val pending = recovery.pending
                    if (!matchesPendingInstallSession(pending, sessionId)) return
                    val confirmation = confirmationIntent(intent)
                        ?.let { makeExplicitSystemIntent(context, it) }
                    if (confirmation == null) {
                        persistAndNotify(
                            context = context,
                            store = store,
                            result = PackageInstallResult.Failure(
                                sessionId = sessionId,
                                code = "MISSING_USER_ACTION_INTENT",
                                message = "설치 승인 화면을 확인할 수 없습니다.",
                            ),
                        )
                    } else {
                        val delivery = PendingConfirmationDelivery(
                            isAppVisible = ManagerAppVisibility::isVisible,
                            launchForeground = { target ->
                                context.startActivity(
                                    Intent(target).addFlags(
                                        pendingConfirmationFlags(target.flags),
                                    ),
                                )
                            },
                            postBackgroundNotification = { ownedSessionId, target ->
                                postPendingConfirmationNotification(
                                    context = context,
                                    sessionId = ownedSessionId,
                                    confirmationIntent = target,
                                )
                            },
                        )
                        handoffPendingConfirmation(
                            pending = pending,
                            sessionId = sessionId,
                            confirmationIntent = confirmation,
                            isDelivered = store::isConfirmationDelivered,
                            markDelivered = store::markConfirmationDelivered,
                            launch = { target -> delivery.deliver(sessionId, target) },
                        )
                    }
                }

                else -> packageInstallResultFromStatus(
                    sessionId = sessionId,
                    status = status,
                    statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE),
                )?.let { result ->
                    persistAndNotify(context = context, store = store, result = result)
                }
            }
        }
    }

    internal fun handoffPendingConfirmation(
        pending: PendingInstall?,
        sessionId: Int,
        confirmationIntent: Intent,
        isDelivered: (Int) -> Boolean,
        markDelivered: (Int) -> Boolean,
        launch: (Intent) -> Unit,
    ): Boolean = processPendingConfirmationHandoff.handle(
        pending = pending,
        sessionId = sessionId,
        confirmationIntent = confirmationIntent,
        isDelivered = isDelivered,
        markDelivered = markDelivered,
        launch = launch,
    )

    private fun persistAndNotify(
        context: Context,
        store: PendingInstallStore,
        result: PackageInstallResult,
    ) {
        context.getSystemService(NotificationManager::class.java)
            ?.cancel(INSTALL_CONFIRMATION_NOTIFICATION_BASE + result.sessionId)
        val recorded = runCatching {
            store.recordTerminalResult(
                result = result,
                terminalAtEpochMillis = System.currentTimeMillis(),
            )
        }.getOrDefault(false)
        if (recorded) {
            PackageInstallEvents.dispatch(PackageInstallCallback(result))
        }
    }

    private fun postPendingConfirmationNotification(
        context: Context,
        sessionId: Int,
        confirmationIntent: Intent,
    ) {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: error("notification service unavailable")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            error("notification permission unavailable")
        }
        manager.createNotificationChannel(
            NotificationChannel(
                INSTALL_CONFIRMATION_CHANNEL,
                context.getString(R.string.install_confirmation_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        if (!notificationHandoffAvailable(
                appNotificationsEnabled = manager.areNotificationsEnabled(),
                channelImportance = manager
                    .getNotificationChannel(INSTALL_CONFIRMATION_CHANNEL)
                    ?.importance,
            )
        ) {
            error("notification handoff unavailable")
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            sessionId,
            Intent(confirmationIntent).addFlags(
                pendingConfirmationFlags(confirmationIntent.flags),
            ),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, INSTALL_CONFIRMATION_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.install_confirmation_title))
            .setContentText(context.getString(R.string.install_confirmation_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(INSTALL_CONFIRMATION_NOTIFICATION_BASE + sessionId, notification)
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
        private const val INSTALL_CONFIRMATION_CHANNEL = "install_confirmation"
        private const val INSTALL_CONFIRMATION_NOTIFICATION_BASE = 10_000
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
