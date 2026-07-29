package dev.sixbridge.manager.install

import android.content.Intent
import android.app.NotificationManager
import android.content.pm.PackageInstaller
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallResultReceiverTest {
    @Test
    fun `invalid recovery snapshot은 receiver side effect 경계를 열지 않는다`() {
        var notificationWrites = 0
        var markerWrites = 0
        var durableWrites = 0

        val accepted = withValidRecoveryStoreSnapshot(RecoveryStoreSnapshot.Invalid) {
            notificationWrites += 1
            markerWrites += 1
            durableWrites += 1
        }

        assertFalse(accepted)
        assertEquals(0, notificationWrites)
        assertEquals(0, markerWrites)
        assertEquals(0, durableWrites)
    }

    @Test
    fun `valid recovery snapshot은 receiver side effect 경계를 정확히 한 번 연다`() {
        val pending = pendingInstall()
        val snapshot = RecoveryStoreSnapshot.Valid(
            pending = pending,
            terminal = null,
            finalReceipt = null,
            normalHandoff = null,
        )
        var acceptedPending: PendingInstall? = null
        var sideEffects = 0

        val accepted = withValidRecoveryStoreSnapshot(snapshot) { valid ->
            acceptedPending = valid.pending
            sideEffects += 1
        }

        assertTrue(accepted)
        assertEquals(pending, acceptedPending)
        assertEquals(1, sideEffects)
    }

    @Test
    fun `rejects missing sessions and unknown package installer statuses`() {
        assertNull(packageInstallResultFromStatus(-1, PackageInstaller.STATUS_SUCCESS, null))
        assertNull(packageInstallResultFromStatus(41, Int.MIN_VALUE, null))
        assertNull(packageInstallResultFromStatus(41, 99, null))
    }

    @Test
    fun `maps only known terminal package installer statuses`() {
        assertEquals(
            PackageInstallResult.Success(41),
            packageInstallResultFromStatus(41, PackageInstaller.STATUS_SUCCESS, null),
        )
        assertEquals(
            PackageInstallResult.Cancelled(41),
            packageInstallResultFromStatus(41, PackageInstaller.STATUS_FAILURE_ABORTED, null),
        )
        assertEquals(
            PackageInstallResult.Failure(
                sessionId = 41,
                code = "PACKAGE_INSTALLER_2",
                message = "blocked",
            ),
            packageInstallResultFromStatus(
                41,
                PackageInstaller.STATUS_FAILURE_BLOCKED,
                "blocked",
            ),
        )
    }

    @Test
    fun `accepts pending user action only for the current pending session`() {
        val pending = PendingInstall(
            sessionId = 41,
            packageName = "dev.sixbridge.wrapper.example",
            importToken = "123e4567-e89b-12d3-a456-426614174000",
            startedAtEpochMillis = 1_000L,
        )

        assertEquals(true, matchesPendingInstallSession(pending, 41))
        assertEquals(false, matchesPendingInstallSession(pending, 42))
        assertEquals(false, matchesPendingInstallSession(null, 41))
    }

    @Test
    fun `pending confirmation 두 번은 system activity를 한 번만 launch한다`() {
        val pending = pendingInstall()
        var delivered = false
        val launches = mutableListOf<Intent>()
        val handoff = PendingConfirmationHandoff()

        assertTrue(
            handoff.handle(
                pending,
                41,
                Intent("confirm"),
                isDelivered = { delivered },
                markDelivered = { delivered = true; true },
                launch = { launches += it },
            ),
        )
        assertFalse(
            handoff.handle(
                pending,
                41,
                Intent("confirm"),
                isDelivered = { delivered },
                markDelivered = { delivered = true; true },
                launch = { launches += it },
            ),
        )

        assertEquals(1, launches.size)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK,
            pendingConfirmationFlags(0),
        )
    }

    @Test
    fun `confirmation launch 실패는 marker 없이 다음 callback에서 재시도한다`() {
        val pending = pendingInstall()
        var launchAttempts = 0
        var markerWrites = 0
        val handoff = PendingConfirmationHandoff()

        assertFalse(
            handoff.handle(
                pending,
                41,
                Intent("confirm"),
                isDelivered = { false },
                markDelivered = { markerWrites += 1; true },
                launch = {
                    launchAttempts += 1
                    if (launchAttempts == 1) error("background launch rejected")
                },
            ),
        )
        assertTrue(
            handoff.handle(
                pending,
                41,
                Intent("confirm"),
                isDelivered = { false },
                markDelivered = { markerWrites += 1; true },
                launch = { launchAttempts += 1 },
            ),
        )

        assertEquals(2, launchAttempts)
        assertEquals(1, markerWrites)
    }

    @Test
    fun `confirmation marker 조회 실패는 중복 launch 없이 fail closed한다`() {
        var launches = 0
        var markerWrites = 0

        assertFalse(
            PendingConfirmationHandoff().handle(
                pendingInstall(),
                41,
                Intent("confirm"),
                isDelivered = { error("marker read failed") },
                markDelivered = { markerWrites += 1; true },
                launch = { launches += 1 },
            ),
        )

        assertEquals(0, launches)
        assertEquals(0, markerWrites)
    }

    @Test
    fun `foreground는 즉시 launch하고 background는 durable notification으로 handoff한다`() {
        val foregroundDeliveries = mutableListOf<Intent>()
        val backgroundDeliveries = mutableListOf<Pair<Int, Intent>>()
        val foreground = PendingConfirmationDelivery(
            isAppVisible = { true },
            launchForeground = { foregroundDeliveries += it },
            postBackgroundNotification = { sessionId, intent ->
                backgroundDeliveries += sessionId to intent
            },
        )
        val background = PendingConfirmationDelivery(
            isAppVisible = { false },
            launchForeground = { foregroundDeliveries += it },
            postBackgroundNotification = { sessionId, intent ->
                backgroundDeliveries += sessionId to intent
            },
        )

        val foregroundIntent = Intent("foreground")
        val backgroundIntent = Intent("background")
        foreground.deliver(41, foregroundIntent)
        background.deliver(42, backgroundIntent)

        assertTrue(foregroundDeliveries.single() === foregroundIntent)
        assertEquals(listOf(42), backgroundDeliveries.map(Pair<Int, Intent>::first))
        assertTrue(backgroundDeliveries.single().second === backgroundIntent)
    }

    @Test
    fun `app notification은 켜져 있어도 install channel이 꺼져 있으면 handoff 불가다`() {
        assertFalse(
            notificationHandoffAvailable(
                appNotificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_NONE,
            ),
        )
        assertFalse(
            notificationHandoffAvailable(
                appNotificationsEnabled = true,
                channelImportance = null,
            ),
        )
        assertTrue(
            notificationHandoffAvailable(
                appNotificationsEnabled = true,
                channelImportance = NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    @Test
    fun `marker commit 실패는 confirmation을 다시 launch하지 않고 marker만 재시도한다`() {
        val pending = pendingInstall()
        var launches = 0
        var markerAttempts = 0
        var delivered = false
        val firstReceiver = InstallResultReceiver()
        val secondReceiver = InstallResultReceiver()

        assertFalse(
            firstReceiver.handoffPendingConfirmation(
                pending,
                41,
                Intent("confirm"),
                isDelivered = { delivered },
                markDelivered = {
                    markerAttempts += 1
                    false
                },
                launch = { launches += 1 },
            ),
        )
        assertTrue(
            secondReceiver.handoffPendingConfirmation(
                pending,
                41,
                Intent("confirm"),
                isDelivered = { delivered },
                markDelivered = {
                    markerAttempts += 1
                    delivered = true
                    true
                },
                launch = { launches += 1 },
            ),
        )

        assertEquals(1, launches)
        assertEquals(2, markerAttempts)
    }

    private fun pendingInstall() = PendingInstall(
        sessionId = 41,
        packageName = "dev.sixbridge.wrapper.example",
        importToken = "123e4567-e89b-12d3-a456-426614174000",
        startedAtEpochMillis = 1_000L,
    )
}
