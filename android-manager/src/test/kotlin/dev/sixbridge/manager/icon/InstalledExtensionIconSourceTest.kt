package dev.sixbridge.manager.icon

import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class InstalledExtensionIconSourceTest {
    @Test
    fun `lease close는 여러 번 호출해도 stream을 한 번만 닫는다`() {
        val stream = CountingCloseInputStream()
        val lease = ProviderStreamLease(stream)

        lease.close()
        lease.close()

        assertEquals(1, stream.closeCount.get())
    }

    @Test
    fun `검증된 wrapper package의 정확한 provider root만 연다`() = runTest {
        val opened = mutableListOf<String>()
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openLease = { uri ->
                opened += uri
                ProviderStreamLease("Cr24fixture".byteInputStream())
            },
        )

        assertContentEquals(PNG, source.load(PACKAGE))
        assertEquals(
            listOf("content://$PACKAGE.contentprovider"),
            opened,
        )
    }

    @Test
    fun `검증된 wrapper package 형식이 아니면 provider를 열지 않는다`() = runTest {
        val opened = mutableListOf<String>()
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openLease = { uri ->
                opened += uri
                ProviderStreamLease("unused".byteInputStream())
            },
        )

        listOf(
            "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmn0p",
            "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnoq",
            "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmno",
            "other.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop",
        ).forEach { packageName ->
            assertNull(source.load(packageName))
        }

        assertEquals(emptyList(), opened)
    }

    @Test
    fun `provider stream이 없거나 열기 예외가 나면 icon 없음으로 격리한다`() = runTest {
        val missing = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openLease = { null },
        )
        val failed = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openLease = { throw IllegalStateException("provider unavailable") },
        )

        assertNull(missing.load(PACKAGE))
        assertNull(failed.load(PACKAGE))
    }

    @Test
    fun `extractor 예외가 나면 icon 없음으로 격리한다`() = runTest {
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { throw IllegalArgumentException("invalid CRX") },
            openLease = { ProviderStreamLease("Cr24fixture".byteInputStream()) },
        )

        assertNull(source.load(PACKAGE))
    }

    @Test
    fun `provider와 extractor cancellation은 재전파한다`() = runTest {
        val providerCancellation = CancellationException("provider cancelled")
        val provider = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openLease = { throw providerCancellation },
        )
        val extractorCancellation = CancellationException("extractor cancelled")
        val extractor = ContentProviderInstalledExtensionIconSource(
            extract = { throw extractorCancellation },
            openLease = { ProviderStreamLease("Cr24fixture".byteInputStream()) },
        )

        assertFailsWith<CancellationException> { provider.load(PACKAGE) }
        assertFailsWith<CancellationException> { extractor.load(PACKAGE) }
    }

    @Test
    fun `provider open suspend 취소는 opener에 전달된다`() = runTest {
        val openStarted = CompletableDeferred<Unit>()
        val openCancelled = CompletableDeferred<Unit>()
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openLease = {
                openStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    openCancelled.complete(Unit)
                }
            },
        )
        val load = launch { source.load(PACKAGE) }
        openStarted.await()

        load.cancelAndJoin()

        openCancelled.await()
    }

    @Test
    fun `stream open 뒤 extraction body 시작 전 취소해도 lease를 닫는다`() = runTest {
        val stream = CountingCloseInputStream()
        val leaseOpened = CompletableDeferred<Unit>()
        val extractEntered = AtomicBoolean()
        val extractionDispatcher = StandardTestDispatcher(testScheduler)
        val source = ContentProviderInstalledExtensionIconSource(
            extract = {
                extractEntered.set(true)
                PNG
            },
            openLease = {
                leaseOpened.complete(Unit)
                ProviderStreamLease(stream)
            },
            extractionDispatcher = extractionDispatcher,
        )
        val load = launch { source.load(PACKAGE) }
        leaseOpened.await()

        load.cancelAndJoin()

        assertEquals(1, stream.closeCount.get())
        assertFalse(extractEntered.get())
    }

    @Test
    fun `blocking extract 취소는 worker를 interrupt하고 stream을 닫는다`() = runBlocking {
        val stream = CloseUnblocksInputStream()
        val source = ContentProviderInstalledExtensionIconSource(
            extract = {
                it.read()
                PNG
            },
            openLease = { ProviderStreamLease(stream) },
        )
        val load = launch(Dispatchers.Default) { source.load(PACKAGE) }
        assertTrue(stream.readStarted.await(1, TimeUnit.SECONDS))

        try {
            load.cancel()
            withTimeout(1_000) { load.join() }
            assertTrue(stream.interrupted.get())
            assertTrue(stream.closed.get())
            assertEquals(1, stream.closeCount.get())
        } finally {
            if (!stream.closed.get()) {
                stream.close()
            }
            load.join()
        }
    }

    @Test
    fun `cleanup 일반 Exception은 icon 결과를 막지 않는다`() = runTest {
        val stream = CloseFailureInputStream(IllegalStateException("close failed"))
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openLease = { ProviderStreamLease(stream) },
        )

        assertContentEquals(PNG, source.load(PACKAGE))
        assertEquals(1, stream.closeCount.get())
    }

    @Test
    fun `cleanup CancellationException은 재전파한다`() = runTest {
        val cancellation = CancellationException("close cancelled")
        val stream = CloseFailureInputStream(cancellation)
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openLease = { ProviderStreamLease(stream) },
        )

        assertFailsWith<CancellationException> {
            source.load(PACKAGE)
        }
        assertEquals(1, stream.closeCount.get())
    }

    @Test
    fun `cleanup Error는 icon 없음으로 축약하지 않는다`() = runTest {
        val failure = AssertionError("close invariant broken")
        val stream = CloseFailureInputStream(failure)
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openLease = { ProviderStreamLease(stream) },
        )

        assertFailsWith<AssertionError> {
            source.load(PACKAGE)
        }
        assertEquals(1, stream.closeCount.get())
    }

    @Test
    fun `cleanup Error는 extractor 실패를 suppressed로 보존한다`() = runTest {
        val extractionFailure = IllegalArgumentException("extract failed")
        val cleanupFailure = AssertionError("close invariant broken")
        val stream = CloseFailureInputStream(cleanupFailure)
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { throw extractionFailure },
            openLease = { ProviderStreamLease(stream) },
        )

        val thrown = assertFailsWith<AssertionError> {
            source.load(PACKAGE)
        }

        assertTrue(
            thrown.suppressed.any {
                it is IllegalArgumentException && it.message == "extract failed"
            },
        )
        assertEquals(1, stream.closeCount.get())
    }

    @Test
    fun `부모 취소 중 cleanup Error도 primary로 보존한다`() = runTest {
        val cleanupFailure = AssertionError("close during cancellation")
        val stream = CloseFailureInputStream(cleanupFailure)
        val leaseOpened = CompletableDeferred<Unit>()
        val extractionDispatcher = StandardTestDispatcher(testScheduler)
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openLease = {
                leaseOpened.complete(Unit)
                ProviderStreamLease(stream)
            },
            extractionDispatcher = extractionDispatcher,
        )
        val observed = CompletableDeferred<Throwable>()
        val load = launch {
            try {
                source.load(PACKAGE)
            } catch (error: Throwable) {
                observed.complete(error)
            }
        }
        leaseOpened.await()

        load.cancel()
        val thrown = observed.await()
        load.join()

        assertTrue(thrown is AssertionError)
        assertEquals("close during cancellation", thrown.message)
        assertTrue(
            thrown.suppressed.any { it is CancellationException },
        )
        assertEquals(1, stream.closeCount.get())
    }

    @Test
    fun `cleanup collector는 모든 작업을 시도하고 Error를 우선한다`() {
        val events = mutableListOf<String>()
        val collector = CleanupFailureCollector()
        collector.attempt {
            events += "signal"
            throw CancellationException("signal cancelled")
        }
        collector.attempt {
            events += "lease"
            throw IllegalStateException("best effort close")
        }
        collector.attempt {
            events += "interrupt"
            throw AssertionError("interrupt invariant")
        }
        collector.attempt {
            events += "after"
        }

        val thrown = assertFailsWith<AssertionError> {
            collector.throwWith(IllegalArgumentException("open failed"))
        }

        assertEquals(
            listOf("signal", "lease", "interrupt", "after"),
            events,
        )
        assertTrue(
            thrown.suppressed.any {
                it is CancellationException && it.message == "signal cancelled"
            },
        )
        assertTrue(
            thrown.suppressed.any {
                it is IllegalArgumentException && it.message == "open failed"
            },
        )
        assertFalse(
            thrown.suppressed.any {
                it is IllegalStateException && it.message == "best effort close"
            },
        )
    }

    @Test
    fun `opening lifetime은 active thread만 실제 interrupt한다`() {
        val ready = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val releaseWorker = CountDownLatch(1)
        val lifetimeReference = AtomicReference<OpeningThreadLifetime>()
        val worker = Thread {
            val lifetime = OpeningThreadLifetime(Thread.currentThread())
            lifetimeReference.set(lifetime)
            ready.countDown()
            try {
                releaseWorker.await()
            } catch (_: InterruptedException) {
                interrupted.countDown()
            } finally {
                lifetime.deactivate()
            }
        }
        worker.start()
        try {
            assertTrue(ready.await(1, TimeUnit.SECONDS))

            lifetimeReference.get().interruptIfActive()

            assertTrue(interrupted.await(1, TimeUnit.SECONDS))
            worker.join(1_000)
            assertFalse(worker.isAlive)
        } finally {
            releaseWorker.countDown()
            worker.interrupt()
            worker.join(1_000)
        }

        val lateInterrupts = AtomicInteger()
        val inactive = OpeningThreadLifetime(
            Thread.currentThread(),
            interruptThread = { lateInterrupts.incrementAndGet() },
        )
        inactive.deactivate()
        inactive.interruptIfActive()
        assertEquals(0, lateInterrupts.get())
    }

    @Test
    fun `opening lifetime은 시작 전 취소도 activate 시 정확히 한 번 interrupt한다`() {
        val interruptCount = AtomicInteger()
        val lifetime = OpeningThreadLifetime(
            interruptThread = { thread ->
                assertEquals(Thread.currentThread(), thread)
                interruptCount.incrementAndGet()
            },
        )

        lifetime.interruptIfActive()
        lifetime.interruptIfActive()
        assertEquals(0, interruptCount.get())

        lifetime.activate(Thread.currentThread())
        lifetime.interruptIfActive()
        lifetime.interruptIfActive()
        assertEquals(1, interruptCount.get())

        lifetime.deactivate()
        lifetime.interruptIfActive()
        assertEquals(1, interruptCount.get())
    }

    private class CountingCloseInputStream : InputStream() {
        val closeCount = AtomicInteger()

        override fun read(): Int = -1

        override fun close() {
            closeCount.incrementAndGet()
        }
    }

    private class CloseFailureInputStream(
        private val failure: Throwable,
    ) : InputStream() {
        val closeCount = AtomicInteger()

        override fun read(): Int = -1

        override fun close() {
            closeCount.incrementAndGet()
            throw failure
        }
    }

    private class CloseUnblocksInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val closed = AtomicBoolean()
        val closeCount = AtomicInteger()
        private val closeSignal = CountDownLatch(1)

        override fun read(): Int {
            readStarted.countDown()
            while (!closed.get()) {
                try {
                    closeSignal.await()
                } catch (_: InterruptedException) {
                    interrupted.set(true)
                }
            }
            return -1
        }

        override fun close() {
            closeCount.incrementAndGet()
            closed.set(true)
            closeSignal.countDown()
        }
    }

    private companion object {
        const val PACKAGE =
            "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop"
        val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    }
}
