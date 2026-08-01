package dev.sixbridge.desktop.profile

import dev.sixbridge.desktop.ui.DesktopProfileImportKind
import dev.sixbridge.desktop.ui.DesktopProfileImportOutcome
import dev.sixbridge.desktop.ui.DesktopProfileImportTicket
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal class DesktopProfileImportController private constructor(
    private val scope: CoroutineScope,
    private val prepare: (
        DesktopProfileImportKind,
        Path,
        () -> Unit,
    ) -> DesktopPreparedProfileImport,
    private val commit: (
        DesktopPreparedProfileImport,
        () -> Unit,
    ) -> DesktopProfileOption,
    private val onOutcome: (
        DesktopProfileImportTicket,
        DesktopProfileImportOutcome,
    ) -> Unit,
    private val ioDispatcher: CoroutineDispatcher,
    private val beforeOutcomeDelivery: () -> Unit,
) : AutoCloseable {
    constructor(
        scope: CoroutineScope,
        catalog: DesktopProfileCatalog,
        reader: DesktopProfileJsonReader,
        onOutcome: (DesktopProfileImportTicket, DesktopProfileImportOutcome) -> Unit,
    ) : this(
        scope = scope,
        prepare = { kind, path, checkCancelled ->
            checkCancelled()
            when (kind) {
                DesktopProfileImportKind.JSON -> catalog.prepareJson(reader.read(path))
                DesktopProfileImportKind.BROWSER_APK ->
                    catalog.prepareApk(path, checkCancelled)
            }
        },
        commit = catalog::commit,
        onOutcome = onOutcome,
        ioDispatcher = Dispatchers.IO,
        beforeOutcomeDelivery = {},
    )

    private val lock = Any()
    private var current: ImportOperation? = null
    private var closed = false

    fun start(ticket: DesktopProfileImportTicket, path: Path): Job {
        val operation = ImportOperation(ticket)
        val previous = synchronized(lock) {
            check(!closed) { "profile import controller가 닫혔습니다." }
            current.also { current = operation }
        }
        previous?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            execute(operation, path)
        }
        synchronized(lock) {
            if (current === operation && !closed) {
                operation.job = job
            } else {
                job.cancel()
            }
        }
        job.start()
        return job
    }

    fun invalidate() {
        val operation = synchronized(lock) {
            current.also { current = null }
        }
        operation?.cancel()
    }

    override fun close() {
        val operation = synchronized(lock) {
            if (closed) return
            closed = true
            current.also { current = null }
        }
        operation?.cancel()
    }

    private suspend fun execute(operation: ImportOperation, path: Path) {
        try {
            val prepared = runInterruptible(ioDispatcher) {
                operation.checkCancelled()
                prepare(operation.ticket.kind, path, operation::checkCancelled)
            }
            operation.checkCancelled()
            requireCurrent(operation)
            val option = runInterruptible(ioDispatcher) {
                operation.checkCancelled()
                requireCurrent(operation)
                commit(prepared, operation::checkCancelled)
            }
            operation.checkCancelled()
            deliverIfCurrent(
                operation,
                DesktopProfileImportOutcome.Success(option),
            )
        } catch (_: CancellationException) {
        } catch (_: InterruptedException) {
        } catch (_: Exception) {
            deliverIfCurrent(operation, DesktopProfileImportOutcome.Failure)
        } finally {
            synchronized(lock) {
                if (current === operation) current = null
            }
        }
    }

    private fun requireCurrent(operation: ImportOperation) {
        if (!isCurrent(operation)) throw CancellationException()
    }

    private fun deliverIfCurrent(
        operation: ImportOperation,
        outcome: DesktopProfileImportOutcome,
    ) {
        beforeOutcomeDelivery()
        val accepted = synchronized(lock) {
            if (!closed && current === operation) {
                current = null
                true
            } else {
                false
            }
        }
        if (accepted) onOutcome(operation.ticket, outcome)
    }

    private fun isCurrent(operation: ImportOperation): Boolean = synchronized(lock) {
        !closed && current === operation
    }

    private class ImportOperation(
        val ticket: DesktopProfileImportTicket,
    ) {
        private val cancelled = AtomicBoolean()
        @Volatile
        var job: Job? = null

        fun cancel() {
            cancelled.set(true)
            job?.cancel()
        }

        fun checkCancelled() {
            if (cancelled.get() || Thread.currentThread().isInterrupted) {
                throw CancellationException()
            }
        }
    }

    internal companion object {
        fun testing(
            scope: CoroutineScope,
            prepare: (
                DesktopProfileImportKind,
                Path,
                () -> Unit,
            ) -> DesktopPreparedProfileImport,
            commit: (
                DesktopPreparedProfileImport,
                () -> Unit,
            ) -> DesktopProfileOption,
            onOutcome: (
                DesktopProfileImportTicket,
                DesktopProfileImportOutcome,
            ) -> Unit,
            beforeOutcomeDelivery: () -> Unit = {},
        ): DesktopProfileImportController = DesktopProfileImportController(
            scope,
            prepare,
            commit,
            onOutcome,
            Dispatchers.IO,
            beforeOutcomeDelivery,
        )
    }
}
