package dev.sixbridge.manager

import dev.sixbridge.manager.ui.ManagerEffect
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class NotificationPermissionInstallGateTest {
    private val installEffect = ManagerEffect.RequestInstallNotificationPermission(
        reviewToken = "11111111-1111-1111-1111-111111111111",
    )

    @Test
    fun `권한 요청 중 중복 설치 탭은 요청과 설치를 중복하지 않는다`() {
        var permissionRequests = 0
        val continued = mutableListOf<ManagerEffect.RequestInstallNotificationPermission>()
        val gate = NotificationPermissionInstallGate(
            pendingInstall = null,
            needsPermission = { true },
            requestPermission = { permissionRequests += 1 },
            continueInstall = continued::add,
        )

        gate.requestInstall(installEffect)
        gate.requestInstall(
            installEffect.copy(
                reviewToken = "22222222-2222-2222-2222-222222222222",
            ),
        )

        assertEquals(1, permissionRequests)
        assertEquals(installEffect, gate.pendingInstall)
        assertEquals(emptyList(), continued)
    }

    @Test
    fun `권한 결과는 recreation 후에도 exact 보류 identity를 정확히 한 번 계속한다`() {
        val continued = mutableListOf<ManagerEffect.RequestInstallNotificationPermission>()
        val gate = NotificationPermissionInstallGate(
            pendingInstall = installEffect,
            needsPermission = { true },
            requestPermission = {},
            continueInstall = continued::add,
        )

        gate.onPermissionResult()
        gate.onPermissionResult()

        assertEquals(listOf(installEffect), continued)
        assertNull(gate.pendingInstall)
    }

    @Test
    fun `권한이 필요 없으면 설치를 즉시 한 번 전달한다`() {
        var permissionRequests = 0
        val continued = mutableListOf<ManagerEffect.RequestInstallNotificationPermission>()
        val gate = NotificationPermissionInstallGate(
            pendingInstall = null,
            needsPermission = { false },
            requestPermission = { permissionRequests += 1 },
            continueInstall = continued::add,
        )

        gate.requestInstall(installEffect)

        assertEquals(0, permissionRequests)
        assertEquals(listOf(installEffect), continued)
        assertNull(gate.pendingInstall)
    }
}
