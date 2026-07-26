package dev.sixbridge.manager.icon

import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class InstalledExtensionIconSourceTest {
    @Test
    fun `검증된 wrapper package의 정확한 provider root만 연다`() = runTest {
        val opened = mutableListOf<String>()
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openStream = { uri ->
                opened += uri
                "Cr24fixture".byteInputStream()
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
            openStream = { uri ->
                opened += uri
                "unused".byteInputStream()
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
            openStream = { null },
        )
        val failed = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openStream = { throw IllegalStateException("provider unavailable") },
        )

        assertNull(missing.load(PACKAGE))
        assertNull(failed.load(PACKAGE))
    }

    @Test
    fun `extractor 예외가 나면 icon 없음으로 격리한다`() = runTest {
        val source = ContentProviderInstalledExtensionIconSource(
            extract = { throw IllegalArgumentException("invalid CRX") },
            openStream = { "Cr24fixture".byteInputStream() },
        )

        assertNull(source.load(PACKAGE))
    }

    @Test
    fun `provider와 extractor cancellation은 재전파한다`() = runTest {
        val providerCancellation = CancellationException("provider cancelled")
        val provider = ContentProviderInstalledExtensionIconSource(
            extract = { PNG },
            openStream = { throw providerCancellation },
        )
        val extractorCancellation = CancellationException("extractor cancelled")
        val extractor = ContentProviderInstalledExtensionIconSource(
            extract = { throw extractorCancellation },
            openStream = { "Cr24fixture".byteInputStream() },
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
            openStream = {
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
    fun `blocking extract 취소는 worker를 interrupt하고 stream을 닫는다`() = runBlocking {
        val stream = CloseUnblocksInputStream()
        val source = ContentProviderInstalledExtensionIconSource(
            extract = {
                it.read()
                PNG
            },
            openStream = { stream },
        )
        val load = launch(Dispatchers.Default) { source.load(PACKAGE) }
        assertTrue(stream.readStarted.await(1, TimeUnit.SECONDS))

        try {
            load.cancel()
            withTimeout(1_000) { load.join() }
            assertTrue(stream.interrupted.get())
            assertTrue(stream.closed.get())
        } finally {
            stream.close()
            load.join()
        }
    }

    private class CloseUnblocksInputStream : InputStream() {
        val readStarted = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val closed = AtomicBoolean()
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
