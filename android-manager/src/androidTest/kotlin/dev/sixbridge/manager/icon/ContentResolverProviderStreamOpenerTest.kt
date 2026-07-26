package dev.sixbridge.manager.icon

import android.content.ContentResolver
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ContentResolverProviderStreamOpenerTest {
    private lateinit var resolver: ContentResolver

    @Before
    fun setUp() {
        resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        assertTrue(callProvider("reset"))
    }

    @After
    fun tearDown() {
        callProvider("release")
    }

    @Test
    fun `open coroutine 취소는 ContentResolver CancellationSignal에 전달된다`() =
        runBlocking {
            val interruptCount = AtomicInteger()
            val openingInactive = CountDownLatch(1)
            val opener = AndroidProviderStreamOpener(
                contentResolver = resolver,
                interruptThread = { thread ->
                    interruptCount.incrementAndGet()
                    thread.interrupt()
                },
                afterOpeningThreadInactive = openingInactive::countDown,
            )
            var open: Job? = null
            try {
                val job = launch(Dispatchers.Default) {
                    opener.open("$PROVIDER_URI/cancel-aware")
                }
                open = job
                assertTrue(callProvider("await-open"))

                job.cancel()
                assertTrue(callProvider("await-cancellation"))
                assertTrue(callProvider("release"))
                assertTrue(openingInactive.await(1, TimeUnit.SECONDS))
                job.join()

                assertEquals(1, interruptCount.get())
            } finally {
                releaseCancelAndJoin(open, openingInactive)
            }
        }

    @Test
    fun `취소된 provider가 stream을 늦게 반환해도 한 번 닫는다`() =
        runBlocking {
            val closeCount = AtomicInteger()
            val openingInactive = CountDownLatch(1)
            val opener = AndroidProviderStreamOpener(
                contentResolver = resolver,
                leaseFactory = { stream ->
                    ProviderStreamLease(
                        CountingCloseInputStream(stream, closeCount),
                    )
                },
                afterOpeningThreadInactive = openingInactive::countDown,
            )
            var open: Job? = null
            try {
                val job = launch(Dispatchers.Default) {
                    opener.open("$PROVIDER_URI/late-return")
                }
                open = job
                assertTrue(callProvider("await-open"))

                job.cancel()
                assertTrue(callProvider("await-cancellation"))
                assertTrue(callProvider("release"))
                assertTrue(openingInactive.await(1, TimeUnit.SECONDS))
                job.join()

                assertEquals(1, closeCount.get())
            } finally {
                releaseCancelAndJoin(open, openingInactive)
            }
        }

    @Test
    fun `resume 직후 소비 전 취소 경합은 lease를 한 번만 닫는다`() =
        runBlocking {
            val callerDispatcher = ManualDispatcher()
            val openingInactive = CountDownLatch(1)
            val closeCount = AtomicInteger()
            val lateInterruptCount = AtomicInteger()
            val opener = AndroidProviderStreamOpener(
                contentResolver = resolver,
                interruptThread = { thread ->
                    lateInterruptCount.incrementAndGet()
                    thread.interrupt()
                },
                leaseFactory = { stream ->
                    ProviderStreamLease(
                        CountingCloseInputStream(stream, closeCount),
                    )
                },
                afterOpeningThreadInactive = openingInactive::countDown,
            )
            var returnedLease: ProviderStreamLease? = null
            var open: Job? = null
            try {
                val job = launch(callerDispatcher) {
                    returnedLease = opener.open("$PROVIDER_URI/late-return")
                }
                open = job
                callerDispatcher.runNext()
                assertTrue(callProvider("await-open"))
                assertTrue(callProvider("release"))
                assertTrue(openingInactive.await(1, TimeUnit.SECONDS))

                job.cancel()
                callerDispatcher.runUntilComplete(job)
                job.join()

                assertNull(returnedLease)
                assertEquals(1, closeCount.get())
                assertEquals(0, lateInterruptCount.get())
            } finally {
                open?.cancel()
                callProvider("release")
                awaitOpeningInactive(openingInactive)
                callerDispatcher.runUntilComplete(open)
                withContext(NonCancellable) {
                    open?.join()
                }
            }
        }

    @Test
    fun `dispatch 전 취소도 opening thread를 정확히 한 번 interrupt한다`() =
        runBlocking {
            val openingDispatcher = ManualDispatcher()
            val openingInactive = CountDownLatch(1)
            val interruptCount = AtomicInteger()
            val opener = AndroidProviderStreamOpener(
                contentResolver = resolver,
                openingDispatcher = openingDispatcher,
                interruptThread = {
                    interruptCount.incrementAndGet()
                },
                afterOpeningThreadInactive = openingInactive::countDown,
            )
            var open: Job? = null
            try {
                val job = launch(start = CoroutineStart.UNDISPATCHED) {
                    opener.open("$PROVIDER_URI/cancel-aware")
                }
                open = job

                job.cancel()
                openingDispatcher.runNext()
                assertTrue(openingInactive.await(1, TimeUnit.SECONDS))
                job.join()

                assertEquals(1, interruptCount.get())
            } finally {
                open?.cancel()
                callProvider("release")
                openingDispatcher.runAll()
                awaitOpeningInactive(openingInactive)
                withContext(NonCancellable) {
                    open?.join()
                }
            }
        }

    private suspend fun releaseCancelAndJoin(
        open: Job?,
        openingInactive: CountDownLatch,
    ) {
        open?.cancel()
        callProvider("release")
        awaitOpeningInactive(openingInactive)
        withContext(NonCancellable) {
            open?.join()
        }
    }

    private fun awaitOpeningInactive(openingInactive: CountDownLatch) {
        assertTrue(
            "opening Runnable이 제한 시간 안에 종료되지 않았습니다.",
            openingInactive.await(5, TimeUnit.SECONDS),
        )
    }

    private fun callProvider(method: String): Boolean =
        resolver.call(PROVIDER_URI, method, null, null)
            ?.getBoolean("completed")
            ?: false

    private class CountingCloseInputStream(
        stream: InputStream,
        private val closeCount: AtomicInteger,
    ) : FilterInputStream(stream) {
        override fun close() {
            closeCount.incrementAndGet()
            super.close()
        }
    }

    private class ManualDispatcher : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.put(block)
        }

        fun runNext() {
            val task = tasks.poll(1, TimeUnit.SECONDS)
            assertTrue("예약된 coroutine 작업이 없습니다.", task != null)
            task?.run()
        }

        fun runUntilComplete(job: Job?) {
            while (job != null && !job.isCompleted) {
                runNext()
            }
        }

        fun runAll() {
            while (true) {
                val task = tasks.poll() ?: return
                task.run()
            }
        }
    }

    private companion object {
        val PROVIDER_URI: Uri =
            Uri.parse("content://dev.sixbridge.manager.test.iconprovider")
    }
}
