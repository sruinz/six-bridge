package dev.sixbridge.desktop

import java.util.concurrent.atomic.AtomicBoolean

internal enum class DesktopCloseRequest {
    CONFIRM_REQUIRED,
    EXITED,
}

internal class DesktopCloseCoordinator(
    private val isBusy: () -> Boolean,
    private val closeProfileImports: () -> Unit,
    private val closeConversion: () -> Unit,
    private val exit: () -> Unit,
) {
    private val exited = AtomicBoolean()

    fun requestClose(): DesktopCloseRequest {
        if (exited.get()) return DesktopCloseRequest.EXITED
        if (isBusy()) return DesktopCloseRequest.CONFIRM_REQUIRED
        closeAndExit()
        return DesktopCloseRequest.EXITED
    }

    fun cancelClose() = Unit

    fun confirmClose() {
        closeAndExit()
    }

    private fun closeAndExit() {
        if (!exited.compareAndSet(false, true)) return
        closeProfileImports()
        closeConversion()
        exit()
    }
}
