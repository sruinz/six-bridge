package dev.sixbridge.desktop

import java.util.concurrent.CancellationException
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
                val fatalFailures = FatalFailures()
                attemptCleanup(closeProfileImports, fatalFailures)
                attemptCleanup(closeConversion, fatalFailures)
                try {
                    postToUi {
                        try {
                            exit()
                            lifecycle.set(Lifecycle.EXITED)
                        } catch (error: Throwable) {
                            lifecycle.set(Lifecycle.OPEN)
                            fatalFailures.record(error)
                        }
                        fatalFailures.throwIfPresent()
                    }
                } catch (error: Throwable) {
                    if (lifecycle.get() != Lifecycle.EXITED) {
                        lifecycle.set(Lifecycle.OPEN)
                    }
                    fatalFailures.record(error)
                    fatalFailures.throwIfPresent()
                }
            }
        } catch (error: Throwable) {
            if (lifecycle.get() != Lifecycle.EXITED) {
                lifecycle.set(Lifecycle.OPEN)
            }
            if (error.isDesktopCloseFatal()) throw error
            return DesktopCloseRequest.CONFIRM_REQUIRED
        }
        return DesktopCloseRequest.CLOSE_STARTED
    }

    private fun attemptCleanup(action: () -> Unit, fatalFailures: FatalFailures) {
        try {
            action()
        } catch (error: Throwable) {
            fatalFailures.record(error)
        }
    }

    private class FatalFailures {
        private var first: Throwable? = null

        fun record(error: Throwable) {
            if (!error.isDesktopCloseFatal()) return
            val original = first
            if (original == null) {
                first = error
            } else if (error !== original && original.suppressed.none { it === error }) {
                original.addSuppressed(error)
            }
        }

        fun throwIfPresent() {
            first?.let { throw it }
        }
    }

    private enum class Lifecycle { OPEN, CLOSING, EXITED }
}

private fun Throwable.isDesktopCloseFatal(): Boolean =
    this is CancellationException || this is Error
