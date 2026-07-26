package dev.sixbridge.manager.install

import android.content.pm.PackageInstaller
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InstallResultReceiverTest {
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
}
