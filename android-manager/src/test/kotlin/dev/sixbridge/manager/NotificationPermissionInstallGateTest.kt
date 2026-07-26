package dev.sixbridge.manager

import kotlin.test.assertEquals
import org.junit.Test

class NotificationPermissionInstallGateTest {
    @Test
    fun `권한 요청 중 중복 설치 탭은 요청과 설치를 중복하지 않는다`() {
        var permissionRequests = 0
        var installRequests = 0
        val gate = NotificationPermissionInstallGate(
            awaitingPermissionResult = false,
            needsPermission = { true },
            requestPermission = { permissionRequests += 1 },
            continueInstall = { installRequests += 1 },
        )

        gate.requestInstall()
        gate.requestInstall()

        assertEquals(1, permissionRequests)
        assertEquals(0, installRequests)
    }

    @Test
    fun `권한 결과는 허용 여부와 무관하게 보류 설치를 정확히 한 번 계속한다`() {
        var installRequests = 0
        val gate = NotificationPermissionInstallGate(
            awaitingPermissionResult = true,
            needsPermission = { true },
            requestPermission = {},
            continueInstall = { installRequests += 1 },
        )

        gate.onPermissionResult()
        gate.onPermissionResult()

        assertEquals(1, installRequests)
    }

    @Test
    fun `권한이 필요 없으면 설치를 즉시 한 번 전달한다`() {
        var permissionRequests = 0
        var installRequests = 0
        val gate = NotificationPermissionInstallGate(
            awaitingPermissionResult = false,
            needsPermission = { false },
            requestPermission = { permissionRequests += 1 },
            continueInstall = { installRequests += 1 },
        )

        gate.requestInstall()

        assertEquals(0, permissionRequests)
        assertEquals(1, installRequests)
    }
}
