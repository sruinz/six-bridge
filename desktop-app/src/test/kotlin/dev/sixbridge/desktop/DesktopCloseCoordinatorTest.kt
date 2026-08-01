package dev.sixbridge.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopCloseCoordinatorTest {
    @Test
    fun `busy 종료는 확인을 요구하고 취소하면 controller를 닫지 않는다`() {
        val calls = mutableListOf<String>()
        val coordinator = DesktopCloseCoordinator(
            isBusy = { true },
            closeProfileImports = { calls += "profile" },
            closeConversion = { calls += "conversion" },
            exit = { calls += "exit" },
        )

        assertEquals(DesktopCloseRequest.CONFIRM_REQUIRED, coordinator.requestClose())
        coordinator.cancelClose()

        assertEquals(emptyList(), calls)
    }

    @Test
    fun `idle 종료와 busy 확정은 controller를 정확히 한 번만 닫고 종료한다`() {
        listOf(false, true).forEach { busy ->
            val calls = mutableListOf<String>()
            val coordinator = DesktopCloseCoordinator(
                isBusy = { busy },
                closeProfileImports = { calls += "profile" },
                closeConversion = { calls += "conversion" },
                exit = { calls += "exit" },
            )

            val request = coordinator.requestClose()
            if (busy) {
                assertEquals(DesktopCloseRequest.CONFIRM_REQUIRED, request)
                coordinator.confirmClose()
                coordinator.confirmClose()
            } else {
                assertEquals(DesktopCloseRequest.EXITED, request)
                coordinator.confirmClose()
            }

            assertEquals(listOf("profile", "conversion", "exit"), calls)
        }
    }
}
