package dev.sixbridge.desktop

import java.util.concurrent.atomic.AtomicReference

internal enum class DesktopCloseRequest {
    CONFIRM_REQUIRED,
    CLOSE_STARTED,
    EXITED,
}

internal class DesktopCloseCoordinator(
    private val isBusy: () -> Boolean,
    private val closeProfileImports: () -> Unit,
    private val closeConversion: () -> Unit,
    private val exit: () -> Unit,
    private val launchInBackground: ((() -> Unit) -> Unit),
    private val postToUi: ((() -> Unit) -> Unit),
) {
    private val lifecycle = AtomicReference(Lifecycle.OPEN)

    fun requestClose(): DesktopCloseRequest {
        when (lifecycle.get()) {
            Lifecycle.CLOSING -> return DesktopCloseRequest.CLOSE_STARTED
            Lifecycle.EXITED -> return DesktopCloseRequest.EXITED
            Lifecycle.OPEN -> Unit
        }
        if (isBusy()) return DesktopCloseRequest.CONFIRM_REQUIRED
        return startClose()
    }

    fun cancelClose() = Unit

    fun confirmClose() {
        startClose()
    }

    private fun startClose(): DesktopCloseRequest {
        if (!lifecycle.compareAndSet(Lifecycle.OPEN, Lifecycle.CLOSING)) {
            return if (lifecycle.get() == Lifecycle.EXITED) {
                DesktopCloseRequest.EXITED
            } else {
                DesktopCloseRequest.CLOSE_STARTED
            }
        }
        try {
            launchInBackground {
                // 종료 중 cleanup은 best-effort다. 한 단계가 실패해도 다음 controller와
                // exit을 반드시 시도하며, exit 자체가 실패한 경우에만 재시도 가능하게 연다.
                runCatching(closeProfileImports)
                runCatching(closeConversion)
                try {
                    postToUi {
                        try {
                            exit()
                            lifecycle.set(Lifecycle.EXITED)
                        } catch (_: Throwable) {
                            lifecycle.set(Lifecycle.OPEN)
                        }
                    }
                } catch (_: Throwable) {
                    lifecycle.set(Lifecycle.OPEN)
                }
            }
        } catch (_: Throwable) {
            lifecycle.set(Lifecycle.OPEN)
            return DesktopCloseRequest.CONFIRM_REQUIRED
        }
        return DesktopCloseRequest.CLOSE_STARTED
    }

    private enum class Lifecycle { OPEN, CLOSING, EXITED }
}
