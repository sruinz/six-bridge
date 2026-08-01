package dev.sixbridge.desktop.app

import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.draft.DraftSigningIdentityProvider
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.desktop.conversion.DesktopConversionEngine
import dev.sixbridge.desktop.conversion.DesktopConversionEngineTestHooks
import dev.sixbridge.desktop.conversion.DesktopDraftSaver
import dev.sixbridge.desktop.conversion.DesktopDraftSaverTestHooks
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import dev.sixbridge.desktop.conversion.DesktopWorkspaceManager
import dev.sixbridge.desktop.input.DesktopInputSealer
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopConversionControllerTest {
    @Test
    fun `markerless input은 엄격한 project identity 선택 전에 draft를 생성하지 않는다`() {
        Fixture().use { fixture ->
            val controller = DesktopConversionController(fixture.engine)
            controller.analyze(DesktopExtensionInput.Local(fixture.markerlessArchive), profile)
            val ready = awaitState(controller) { it is DesktopConversionState.AnalysisReady }
                as DesktopConversionState.AnalysisReady
            assertIs<LogicalSourceIdentityResult.NeedsUserSelection>(ready.session.logicalSourceIdentity)
            controller.continueToApproval()

            controller.selectProjectIdentity("A".repeat(32))
            assertEquals(null, (controller.state.value as DesktopConversionState.AnalysisReady).projectIdentity)
            controller.buildDraft()
            assertEquals(
                DesktopFailureStage.APPROVAL,
                (awaitState(controller) { it is DesktopConversionState.Failed } as DesktopConversionState.Failed).stage,
            )

            controller.selectProjectIdentity("c".repeat(32))
            assertEquals(
                "c".repeat(32),
                (controller.state.value as DesktopConversionState.AnalysisReady).projectIdentity,
            )
            controller.buildDraft()
            assertIs<DesktopConversionState.DraftReady>(
                awaitState(controller) { it is DesktopConversionState.DraftReady },
            )
            controller.close()
        }
    }

    @Test
    fun `stable identity input은 사용자 project identity로 덮어쓰지 않는다`() {
        Fixture().use { fixture ->
            val controller = DesktopConversionController(fixture.engine)
            controller.analyze(DesktopExtensionInput.Local(fixture.firstSource), profile)
            val ready = awaitState(controller) { it is DesktopConversionState.AnalysisReady }
                as DesktopConversionState.AnalysisReady
            assertIs<LogicalSourceIdentityResult.Stable>(ready.session.logicalSourceIdentity)
            controller.continueToApproval()

            controller.selectProjectIdentity("d".repeat(32))

            assertEquals(null, (controller.state.value as DesktopConversionState.AnalysisReady).projectIdentity)
            controller.close()
        }
    }

    @Test
    fun `registered AnalysisReady와 DraftReady의 새 분석 취소 종료는 이전 lease를 정확히 한 번 해제한다`() {
        listOf("analysis", "draft").forEach { phase ->
            listOf("new", "cancel", "close").forEach { boundary ->
                Fixture().use { fixture ->
                    val controller = DesktopConversionController(fixture.engine)
                    controller.analyze(DesktopExtensionInput.Local(fixture.firstSource), profile)
                    val ready = awaitState(controller) { it is DesktopConversionState.AnalysisReady } as DesktopConversionState.AnalysisReady
                    val token = if (phase == "analysis") {
                        ready.session.token
                    } else {
                        controller.continueToApproval()
                        controller.buildDraft()
                        (awaitState(controller) { it is DesktopConversionState.DraftReady } as DesktopConversionState.DraftReady).artifact.token
                    }

                    when (boundary) {
                        "new" -> controller.analyze(DesktopExtensionInput.Local(fixture.secondSource), profile)
                        "cancel" -> controller.cancel()
                        else -> controller.close()
                    }
                    await { !fixture.engine.isRegisteredForTesting(token) }
                    assertFalse(fixture.engine.release(token), "$phase/$boundary token은 두 번 해제될 수 없습니다.")
                    assertEquals(1, fixture.closeCount.get(), "$phase/$boundary lease는 정확히 한 번 닫혀야 합니다.")
                    if (boundary == "new") controller.close()
                }
            }
        }
    }

    @Test
    fun `BuildingDraft의 새 분석 취소 종료는 lease를 정확히 한 번 해제한다`() {
        listOf("new", "cancel", "close").forEach { boundary ->
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            Fixture(DesktopConversionEngineTestHooks(beforeDraft = {
                entered.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
            })).use { fixture ->
                val controller = DesktopConversionController(fixture.engine)
                controller.analyze(DesktopExtensionInput.Local(fixture.firstSource), profile)
                val ready = awaitState(controller) { it is DesktopConversionState.AnalysisReady } as DesktopConversionState.AnalysisReady
                controller.continueToApproval()
                controller.buildDraft()
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertIs<DesktopConversionState.BuildingDraft>(controller.state.value)
                val token = ready.session.token

                when (boundary) {
                    "new" -> controller.analyze(DesktopExtensionInput.Local(fixture.secondSource), profile)
                    "cancel" -> controller.cancel()
                    else -> controller.close()
                }
                proceed.countDown()
                await { !fixture.engine.isRegisteredForTesting(token) }
                assertFalse(fixture.engine.release(token), "BuildingDraft/$boundary token은 두 번 해제될 수 없습니다.")
                assertEquals(1, fixture.closeCount.get(), "BuildingDraft/$boundary lease는 정확히 한 번 닫혀야 합니다.")
                if (boundary == "new") {
                    val current = awaitState(controller) { it is DesktopConversionState.AnalysisReady } as DesktopConversionState.AnalysisReady
                    assertEquals(DesktopExtensionInput.Local(fixture.secondSource), current.session.input)
                    controller.close()
                }
            }
        }
    }

    @Test
    fun `승인 실패 뒤 adapter 선택을 고쳐 draft 생성을 재시도한다`() {
        Fixture().use { fixture ->
            val controller = DesktopConversionController(fixture.engine)
            controller.analyze(DesktopExtensionInput.Local(fixture.popupSource), profile)
            val ready = awaitState(controller) { it is DesktopConversionState.AnalysisReady }
                as DesktopConversionState.AnalysisReady
            assertTrue(ready.session.requiredApprovals.isNotEmpty())
            controller.continueToApproval()
            controller.buildDraft()
            assertEquals(DesktopFailureStage.APPROVAL, (awaitState(controller) { it is DesktopConversionState.Failed } as DesktopConversionState.Failed).stage)

            controller.toggleAdapter(ready.session.requiredApprovals.single())
            controller.buildDraft()
            assertIs<DesktopConversionState.DraftReady>(awaitState(controller) { it is DesktopConversionState.DraftReady })
            controller.close()
        }
    }

    @Test
    fun `취소된 이전 분석 결과는 새 분석 상태를 덮어쓰지 않는다`() {
        val entered = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        val firstOnly = AtomicBoolean(true)
        val oldToken = AtomicReference<String>()
        val staleReleaseLookups = AtomicInteger()
        Fixture(
            DesktopConversionEngineTestHooks(
                afterAnalysisRegistration = { session ->
                    if (firstOnly.compareAndSet(true, false)) {
                        oldToken.set(session.token)
                        entered.countDown()
                        awaitIgnoringInterrupts(finishFirst)
                    }
                },
                beforeRelease = { staleReleaseLookups.incrementAndGet() },
            ),
        ).use { fixture ->
            val controller = DesktopConversionController(fixture.engine)
            val first = DesktopExtensionInput.Local(fixture.firstSource)
            val second = DesktopExtensionInput.Local(fixture.secondSource)

            controller.analyze(first, profile)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            controller.cancel()
            controller.analyze(second, profile)
            finishFirst.countDown()

            val ready = awaitState(controller) { it is DesktopConversionState.AnalysisReady }
            assertEquals(second, (ready as DesktopConversionState.AnalysisReady).session.input)
            await { staleReleaseLookups.get() == 1 }
            await { !fixture.engine.isRegisteredForTesting(oldToken.get()) }
            assertFalse(fixture.engine.release(oldToken.get()), "stale analysis token은 두 번 해제될 수 없습니다.")
            assertEquals(1, fixture.closeCount.get(), "stale analysis lease는 정확히 한 번 닫혀야 합니다.")
            controller.close()
        }
    }

    @Test
    fun `새 분석 뒤 늦게 성공한 이전 draft 결과는 새 분석 상태를 덮어쓰지 않는다`() {
        val entered = CountDownLatch(1)
        val finishFirst = CountDownLatch(1)
        val oldToken = AtomicReference<String>()
        val artifactCreated = AtomicBoolean(false)
        val staleReleaseLookups = AtomicInteger()
        Fixture(
            DesktopConversionEngineTestHooks(
                afterDraftCreation = { artifact ->
                    oldToken.set(artifact.token)
                    artifactCreated.set(true)
                    entered.countDown()
                    awaitIgnoringInterrupts(finishFirst)
                },
                beforeRelease = {
                    if (artifactCreated.get()) staleReleaseLookups.incrementAndGet()
                },
            ),
        ).use { fixture ->
            val controller = DesktopConversionController(fixture.engine)
            val second = DesktopExtensionInput.Local(fixture.secondSource)

            controller.analyze(DesktopExtensionInput.Local(fixture.firstSource), profile)
            awaitState(controller) { it is DesktopConversionState.AnalysisReady }
            controller.continueToApproval()
            controller.buildDraft()
            assertTrue(entered.await(10, TimeUnit.SECONDS))

            controller.analyze(second, profile)
            finishFirst.countDown()

            val ready = awaitState(controller) { it is DesktopConversionState.AnalysisReady }
            assertEquals(second, (ready as DesktopConversionState.AnalysisReady).session.input)
            await { staleReleaseLookups.get() == 2 }
            await { !fixture.engine.isRegisteredForTesting(oldToken.get()) }
            assertFalse(fixture.engine.release(oldToken.get()), "stale draft token은 두 번 해제될 수 없습니다.")
            assertEquals(1, fixture.closeCount.get(), "stale draft lease는 정확히 한 번 닫혀야 합니다.")
            controller.close()
        }
    }

    @Test
    fun `저장 성공은 lease를 유지하고 저장 실패 뒤 재시도할 수 있다`() {
        Fixture().use { fixture ->
            val controller = DesktopConversionController(fixture.engine)
            controller.analyze(DesktopExtensionInput.Local(fixture.firstSource), profile)
            awaitState(controller) { it is DesktopConversionState.AnalysisReady }
            controller.continueToApproval()
            controller.buildDraft()
            val draft = awaitState(controller) { it is DesktopConversionState.DraftReady }
                as DesktopConversionState.DraftReady
            val existing = Files.createTempDirectory("controller-save-").resolve("existing.sixdraft")
            Files.writeString(existing, "user-data")

            controller.saveDraft(existing)
            val failed = awaitState(controller) { it is DesktopConversionState.Failed }
                as DesktopConversionState.Failed
            assertEquals(DesktopFailureStage.SAVE, failed.stage)
            assertEquals("user-data", Files.readString(existing))
            assertTrue(fixture.engine.isRegisteredForTesting(draft.artifact.token))

            val saved = Files.createTempDirectory("controller-save-").resolve("saved.sixdraft")
            controller.saveDraft(saved)
            val savedState = awaitState(controller) {
                it is DesktopConversionState.DraftReady && it.savedPath == saved.toAbsolutePath().normalize()
            } as DesktopConversionState.DraftReady
            assertEquals(draft.artifact, savedState.artifact)
            assertTrue(fixture.engine.isRegisteredForTesting(draft.artifact.token))

            controller.saveDraft(saved)
            val secondFailure = awaitState(controller) { it is DesktopConversionState.Failed }
                as DesktopConversionState.Failed
            val preserved = secondFailure.previous as DesktopConversionState.DraftReady
            assertEquals(saved.toAbsolutePath().normalize(), preserved.savedPath)
            assertTrue(fixture.engine.isRegisteredForTesting(draft.artifact.token))

            controller.reset()
            await { !fixture.engine.isRegisteredForTesting(draft.artifact.token) }
            assertIs<DesktopConversionState.Input>(controller.state.value)
            controller.close()
        }
    }

    @Test
    fun `실제 저장 작업을 실행하는 동안 busy이고 취소하면 lease를 해제한다`() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        Fixture().use { fixture ->
            val saver = DesktopDraftSaver(
                DesktopDraftSaverTestHooks(
                    beforeCopy = {
                        entered.countDown()
                        awaitIgnoringInterrupts(proceed)
                    },
                ),
            )
            val controller = DesktopConversionController(fixture.engine, saver)
            controller.analyze(DesktopExtensionInput.Local(fixture.firstSource), profile)
            awaitState(controller) { it is DesktopConversionState.AnalysisReady }
            controller.continueToApproval()
            controller.buildDraft()
            val draft = awaitState(controller) { it is DesktopConversionState.DraftReady }
                as DesktopConversionState.DraftReady
            val saved = Files.createTempDirectory("controller-save-").resolve("saved.sixdraft")

            controller.saveDraft(saved)
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(controller.isBusy())
            controller.cancel()
            proceed.countDown()

            await { !fixture.engine.isRegisteredForTesting(draft.artifact.token) }
            assertFalse(controller.isBusy())
            controller.close()
        }
    }

    private fun awaitState(
        controller: DesktopConversionController,
        matches: (DesktopConversionState) -> Boolean,
    ): DesktopConversionState {
        await { matches(controller.state.value) }
        return controller.state.value
    }

    private fun await(matches: () -> Boolean) {
        repeat(200) {
            if (matches()) return
            Thread.sleep(25)
        }
        assertTrue(matches(), "상태 전이를 기다리는 시간이 초과되었습니다.")
    }

    private fun awaitIgnoringInterrupts(latch: CountDownLatch) {
        while (latch.count != 0L) {
            try {
                check(latch.await(10, TimeUnit.SECONDS))
            } catch (_: InterruptedException) {
                // Controller cancellation must not turn this test-only completion barrier into cancellation.
            }
        }
    }

    private class Fixture(
        hooks: DesktopConversionEngineTestHooks = DesktopConversionEngineTestHooks(),
    ) : AutoCloseable {
        val closeCount = AtomicInteger()
        private val manager = DesktopWorkspaceManager.testing(closeCount::incrementAndGet)
        private val signer = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val engine = DesktopConversionEngine.testing(
            manager,
            DesktopInputSealer { error("Chrome input은 사용하지 않습니다.") },
            DraftSigningIdentityProvider { signer },
            hooks,
        )
        private val root = Files.createTempDirectory("desktop-controller-")
        val firstSource = source("first")
        val secondSource = source("second")
        val markerlessArchive = root.resolve("markerless.zip").also {
            Files.write(it, ExtensionZip.createBytes(firstSource, includeProjectMarker = false))
        }
        val popupSource = Files.createDirectory(root.resolve("popup")).also {
            it.resolve("popup.html").writeText("<p>popup</p>")
            it.resolve("manifest.json").writeText("""{"manifest_version":3,"name":"popup","version":"1","action":{"default_popup":"popup.html"}}""")
        }

        private fun source(name: String) = Files.createDirectory(root.resolve(name)).also {
            it.resolve("manifest.json").writeText("""{"manifest_version":3,"name":"$name","version":"1"}""")
        }

        override fun close() = engine.close()
    }

    private companion object {
        val profile = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = BrowserProfile.BASELINE_VERSION,
            versionCode = 1_300_261_502,
            majorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION,
            supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
            wrapperContract = WrapperContract.complete(),
            sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
            sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
            supportedFeatures = setOf("extension.options-page.user-accessible"),
        )
    }
}
