package dev.sixbridge.desktop

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopCloseCoordinatorTest {
    @Test
    fun `busy 종료는 확인을 요구하고 취소하면 controller를 닫지 않는다`() {
        val calls = mutableListOf<String>()
        val background = mutableListOf<() -> Unit>()
        val coordinator = DesktopCloseCoordinator(
            isBusy = { true },
            closeProfileImports = { calls += "profile" },
            closeConversion = { calls += "conversion" },
            exit = { calls += "exit" },
            launchInBackground = background::add,
            postToUi = { it() },
        )

        assertEquals(DesktopCloseRequest.CONFIRM_REQUIRED, coordinator.requestClose())
        coordinator.cancelClose()

        assertEquals(emptyList(), calls)
    }

    @Test
    fun `idle 종료와 busy 확정은 controller를 정확히 한 번만 닫고 종료한다`() {
        listOf(false, true).forEach { busy ->
            val calls = mutableListOf<String>()
            val background = mutableListOf<() -> Unit>()
            val coordinator = DesktopCloseCoordinator(
                isBusy = { busy },
                closeProfileImports = { calls += "profile" },
                closeConversion = { calls += "conversion" },
                exit = { calls += "exit" },
                launchInBackground = background::add,
                postToUi = { it() },
            )

            val request = coordinator.requestClose()
            if (busy) {
                assertEquals(DesktopCloseRequest.CONFIRM_REQUIRED, request)
                coordinator.confirmClose()
                coordinator.confirmClose()
            } else {
                assertEquals(DesktopCloseRequest.CLOSE_STARTED, request)
                coordinator.confirmClose()
            }

            assertEquals(emptyList(), calls)
            assertEquals(1, background.size)
            background.single().invoke()

            assertEquals(listOf("profile", "conversion", "exit"), calls)
        }
    }

    @Test
    fun `close callback 예외에도 나머지 정리와 exit을 실행한다`() {
        val calls = mutableListOf<String>()
        val background = mutableListOf<() -> Unit>()
        val coordinator = DesktopCloseCoordinator(
            isBusy = { false },
            closeProfileImports = {
                calls += "profile"
                throw IllegalStateException("profile")
            },
            closeConversion = {
                calls += "conversion"
                throw IllegalStateException("conversion")
            },
            exit = { calls += "exit" },
            launchInBackground = background::add,
            postToUi = { it() },
        )

        assertEquals(DesktopCloseRequest.CLOSE_STARTED, coordinator.requestClose())
        background.single().invoke()
        assertEquals(listOf("profile", "conversion", "exit"), calls)
        assertEquals(DesktopCloseRequest.EXITED, coordinator.requestClose())
        assertEquals(1, background.size)
    }

    @Test
    fun `지연되는 cleanup은 종료 action handler를 막지 않는다`() {
        val cleanupEntered = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        val callerReturned = CountDownLatch(1)
        val request = AtomicReference<DesktopCloseRequest>()
        val background = Executors.newSingleThreadExecutor()
        val caller = Executors.newSingleThreadExecutor()
        val coordinator = DesktopCloseCoordinator(
            isBusy = { false },
            closeProfileImports = {},
            closeConversion = {
                cleanupEntered.countDown()
                releaseCleanup.await()
            },
            exit = {},
            launchInBackground = { background.execute(it) },
            postToUi = { it() },
        )

        try {
            caller.execute {
                request.set(coordinator.requestClose())
                callerReturned.countDown()
            }

            assertTrue(cleanupEntered.await(5, TimeUnit.SECONDS))
            assertTrue(
                callerReturned.await(1, TimeUnit.SECONDS),
                "종료 action handler는 cleanup 완료를 기다리면 안 됩니다.",
            )
            assertEquals(DesktopCloseRequest.CLOSE_STARTED, request.get())
        } finally {
            releaseCleanup.countDown()
            caller.shutdownNow()
            background.shutdownNow()
        }
    }
}
