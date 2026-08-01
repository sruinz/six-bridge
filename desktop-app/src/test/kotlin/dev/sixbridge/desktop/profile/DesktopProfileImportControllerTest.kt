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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopProfileImportControllerTest {
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
