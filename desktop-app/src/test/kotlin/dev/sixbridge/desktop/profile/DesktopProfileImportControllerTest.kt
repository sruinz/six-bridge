package dev.sixbridge.desktop.profile

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.BrowserProfileJson
import dev.sixbridge.core.profile.SignedBrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.desktop.ui.DesktopProfileImportKind
import dev.sixbridge.desktop.ui.DesktopProfileImportOutcome
import dev.sixbridge.desktop.ui.DesktopProfileImportTicket
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopProfileImportControllerTest {
    @Test
    fun `profile import는 prepare 중에만 busy로 보고 invalidate 후 즉시 해제한다`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DesktopProfileImportController.testing(
            scope = scope,
            prepare = { _, _, _ ->
                entered.countDown()
                release.await()
                DesktopPreparedProfileImport.WithoutPersistence(option("ok"))
            },
            commit = { prepared, _ -> prepared.option },
            onOutcome = { _, _ -> },
        )

        try {
            controller.start(ticket(1), Path.of("slow.json"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(controller.isBusy())

            controller.invalidate()
            assertFalse(controller.isBusy())
        } finally {
            release.countDown()
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun `raw InterruptedException 취소 뒤 UI dispatcher 다음 task는 interrupt되지 않는다`() {
        val dispatcher = NonClearingSingleThreadDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val nextTaskRan = CountDownLatch(1)
        val nextTaskInterrupted = AtomicBoolean(true)
        val controller = DesktopProfileImportController.testing(
            scope = scope,
            prepare = { _, _, _ -> throw InterruptedException("worker interrupt") },
            commit = { prepared, _ -> prepared.option },
            onOutcome = { _, _ -> error("취소는 outcome을 게시하지 않아야 합니다.") },
        )

        try {
            val job = controller.start(ticket(1), Path.of("interrupted.json"))
            scope.launch {
                job.join()
                nextTaskInterrupted.set(Thread.currentThread().isInterrupted)
                nextTaskRan.countDown()
            }

            assertTrue(nextTaskRan.await(5, TimeUnit.SECONDS))
            assertFalse(nextTaskInterrupted.get())
        } finally {
            controller.close()
            scope.cancel()
            dispatcher.close()
        }
    }

    @Test
    fun `close가 outcome 직전에 실행되면 callback을 게시하지 않는다`() {
        val beforeOutcome = CountDownLatch(1)
        val releaseOutcome = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(
            mutableListOf<Pair<DesktopProfileImportTicket, DesktopProfileImportOutcome>>(),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DesktopProfileImportController.testing(
            scope = scope,
            prepare = { _, _, _ -> DesktopPreparedProfileImport.WithoutPersistence(option("ok")) },
            commit = { prepared, _ -> prepared.option },
            onOutcome = { ticket, outcome -> outcomes += ticket to outcome },
            beforeOutcomeDelivery = {
                beforeOutcome.countDown()
                releaseOutcome.await()
            },
        )

        try {
            val job = controller.start(ticket(1), Path.of("close-race.json"))
            assertTrue(beforeOutcome.await(5, TimeUnit.SECONDS))

            controller.close()
            releaseOutcome.countDown()

            runBlocking { withTimeout(5_000) { job.join() } }
            assertTrue(outcomes.isEmpty())
        } finally {
            releaseOutcome.countDown()
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun `outcome callback does not hold controller lock while another thread invalidates`() {
        val callbackEntered = CountDownLatch(1)
        val invalidationReturned = CountDownLatch(1)
        val callbackObservedInvalidation = AtomicBoolean()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val invalidator = Executors.newSingleThreadExecutor()
        val controller = DesktopProfileImportController.testing(
            scope = scope,
            prepare = { _, _, _ -> DesktopPreparedProfileImport.WithoutPersistence(option("ok")) },
            commit = { prepared, _ -> prepared.option },
            onOutcome = { _, _ ->
                callbackEntered.countDown()
                callbackObservedInvalidation.set(
                    invalidationReturned.await(1, TimeUnit.SECONDS),
                )
            },
        )

        try {
            val job = controller.start(ticket(1), Path.of("cross-thread.json"))
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS))

            invalidator.submit {
                controller.invalidate()
                invalidationReturned.countDown()
            }

            runBlocking { withTimeout(5_000) { job.join() } }
            assertTrue(callbackObservedInvalidation.get())
        } finally {
            controller.close()
            scope.cancel()
            invalidator.shutdownNow()
        }
    }

    @Test
    fun `outcome callback may reenter invalidate and close without deadlock`() {
        val completed = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var controller: DesktopProfileImportController
        controller = DesktopProfileImportController.testing(
            scope = scope,
            prepare = { _, _, _ -> DesktopPreparedProfileImport.WithoutPersistence(option("ok")) },
            commit = { prepared, _ -> prepared.option },
            onOutcome = { _, _ ->
                controller.invalidate()
                controller.close()
                completed.countDown()
            },
        )

        try {
            val job = controller.start(ticket(1), Path.of("reentrant.json"))

            assertTrue(completed.await(5, TimeUnit.SECONDS))
            runBlocking { withTimeout(5_000) { job.join() } }
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun `invalidate는 현재 장시간 prepare를 interrupt하고 outcome을 게시하지 않는다`() {
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(
            mutableListOf<Pair<DesktopProfileImportTicket, DesktopProfileImportOutcome>>(),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DesktopProfileImportController.testing(
            scope = scope,
            prepare = { _, _, _ ->
                entered.countDown()
                try {
                    CountDownLatch(1).await()
                    error("도달할 수 없음")
                } catch (error: InterruptedException) {
                    interrupted.countDown()
                    throw error
                }
            },
            commit = { prepared, _ -> prepared.option },
            onOutcome = { ticket, outcome -> outcomes += ticket to outcome },
        )

        try {
            val job = controller.start(ticket(1), Path.of("slow.json"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            controller.invalidate()

            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            runBlocking { withTimeout(5_000) { job.join() } }
            assertTrue(outcomes.isEmpty())
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun `새 import는 이전 job을 interrupt하고 stale prepare는 persist하지 않는다`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val dataRoot = Files.createTempDirectory("profile-import-stale-")
        val checkpoints = AtomicInteger()
        val catalog = DesktopProfileCatalog.testing(
            dataRoot = dataRoot,
            trustedKeys = mapOf("test-key" to pair.public),
            persistenceCheckpoint = { _, _ -> checkpoints.incrementAndGet() },
        )
        val stalePrepared = catalog.prepareJson(signedEnvelope(pair.private, profile))
        val currentOption = option("current")
        val currentPrepared = DesktopPreparedProfileImport.WithoutPersistence(currentOption)
        val first = Path.of("first.json")
        val second = Path.of("second.json")
        val firstEntered = CountDownLatch(1)
        val firstInterrupted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val currentCompleted = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(
            mutableListOf<Pair<DesktopProfileImportTicket, DesktopProfileImportOutcome>>(),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DesktopProfileImportController.testing(
            scope = scope,
            prepare = { _, path, _ ->
                if (path == first) {
                    firstEntered.countDown()
                    while (true) {
                        try {
                            releaseFirst.await()
                            break
                        } catch (_: InterruptedException) {
                            firstInterrupted.countDown()
                        }
                    }
                    stalePrepared
                } else {
                    currentPrepared
                }
            },
            commit = catalog::commit,
            onOutcome = { ticket, outcome ->
                outcomes += ticket to outcome
                if (ticket.generation == 2L) currentCompleted.countDown()
            },
        )

        try {
            val firstJob = controller.start(ticket(1), first)
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            val secondJob = controller.start(ticket(2), second)

            assertTrue(firstInterrupted.await(5, TimeUnit.SECONDS))
            assertTrue(currentCompleted.await(5, TimeUnit.SECONDS))
            releaseFirst.countDown()
            runBlocking {
                withTimeout(5_000) {
                    firstJob.join()
                    secondJob.join()
                }
            }

            assertEquals(listOf(2L), outcomes.map { it.first.generation })
            assertEquals(currentOption, (outcomes.single().second as DesktopProfileImportOutcome.Success).option)
            assertEquals(0, checkpoints.get())
            assertFalse(Files.exists(dataRoot.resolve("profiles")))
        } finally {
            releaseFirst.countDown()
            controller.close()
            scope.cancel()
        }
    }

    @Test
    fun `current prepared 결과만 commit하고 UI outcome을 게시한다`() {
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val dataRoot = Files.createTempDirectory("profile-import-current-")
        val checkpoints = AtomicInteger()
        val catalog = DesktopProfileCatalog.testing(
            dataRoot = dataRoot,
            trustedKeys = mapOf("test-key" to pair.public),
            persistenceCheckpoint = { _, _ -> checkpoints.incrementAndGet() },
        )
        val prepared = catalog.prepareJson(signedEnvelope(pair.private, profile))
        val completed = CountDownLatch(1)
        val outcomes = Collections.synchronizedList(
            mutableListOf<Pair<DesktopProfileImportTicket, DesktopProfileImportOutcome>>(),
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = DesktopProfileImportController.testing(
            scope = scope,
            prepare = { _, _, _ -> prepared },
            commit = catalog::commit,
            onOutcome = { ticket, outcome ->
                outcomes += ticket to outcome
                completed.countDown()
            },
        )

        try {
            val job = controller.start(ticket(7), Path.of("current.json"))
            assertTrue(completed.await(5, TimeUnit.SECONDS))
            runBlocking { withTimeout(5_000) { job.join() } }

            assertEquals(listOf(7L), outcomes.map { it.first.generation })
            assertEquals(DesktopProfileTrust.SIGNED, catalog.loadPersisted().single().trust)
            assertTrue(checkpoints.get() > 0)
        } finally {
            controller.close()
            scope.cancel()
        }
    }

    private fun ticket(generation: Long) = DesktopProfileImportTicket(
        generation = generation,
        kind = DesktopProfileImportKind.JSON,
    )

    private fun signedEnvelope(
        privateKey: java.security.PrivateKey,
        value: BrowserProfile,
    ): ByteArray = SignedBrowserProfile.sign(
        BrowserProfileJson.encode(value).encodeToByteArray(),
        "test-key",
        privateKey,
    ).encodeToByteArray()

    private fun option(id: String) = DesktopProfileOption(
        id = id,
        profile = null,
        trust = DesktopProfileTrust.BUILT_IN,
        selectable = true,
        explanation = id,
    )

    private class NonClearingSingleThreadDispatcher : CoroutineDispatcher(), AutoCloseable {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        private val running = AtomicBoolean(true)
        private val thread = Thread({
            while (running.get() || tasks.isNotEmpty()) {
                tasks.poll()?.run() ?: Thread.onSpinWait()
            }
        }, "profile-import-ui-test").also(Thread::start)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            tasks.add(block)
        }

        override fun close() {
            running.set(false)
            thread.join(5_000)
            check(!thread.isAlive) { "test dispatcher가 종료되지 않았습니다." }
        }
    }

    private val profile = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
        detectedApis = BrowserProfile.BASELINE_APIS,
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    )
}
