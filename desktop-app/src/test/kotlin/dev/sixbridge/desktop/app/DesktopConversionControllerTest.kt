package dev.sixbridge.desktop.app

import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.draft.DraftSigningIdentityProvider
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.desktop.conversion.DesktopConversionEngine
import dev.sixbridge.desktop.conversion.DesktopConversionEngineTestHooks
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import dev.sixbridge.desktop.conversion.DesktopWorkspaceManager
import dev.sixbridge.desktop.input.DesktopInputSealer
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DesktopConversionControllerTest {
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
        Fixture(
            DesktopConversionEngineTestHooks(
                beforeContextRegistration = {
                    if (firstOnly.compareAndSet(true, false)) {
                        entered.countDown()
                        check(finishFirst.await(10, TimeUnit.SECONDS))
                    }
                },
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

            controller.reset()
            await { !fixture.engine.isRegisteredForTesting(draft.artifact.token) }
            assertIs<DesktopConversionState.Input>(controller.state.value)
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

    private class Fixture(
        hooks: DesktopConversionEngineTestHooks = DesktopConversionEngineTestHooks(),
    ) : AutoCloseable {
        private val manager = DesktopWorkspaceManager.testing {}
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
