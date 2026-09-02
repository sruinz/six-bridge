package dev.sixbridge.manager.ui

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractIssue
import dev.sixbridge.contract.bundle.ContractForcedCompatibility
import dev.sixbridge.manager.GeneratedDocumentSaveResult
import dev.sixbridge.manager.app.ManagerImportResult
import dev.sixbridge.manager.app.ManagerRepository
import dev.sixbridge.manager.app.ManagerRestoreResult
import dev.sixbridge.manager.app.RestoredInstallTerminal
import dev.sixbridge.manager.app.RepositoryLaunch
import dev.sixbridge.manager.app.RepositoryNavigationResult
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.app.InstallApkExport
import dev.sixbridge.manager.app.TerminalReceiptOwnerResult
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.conversion.ConversionBeginResult
import dev.sixbridge.manager.conversion.ConversionBuildResult
import dev.sixbridge.manager.conversion.ConversionCleanupCancellationException
import dev.sixbridge.manager.conversion.ConversionFailureCode
import dev.sixbridge.manager.conversion.ConversionForceChallenge
import dev.sixbridge.manager.conversion.ConversionForceProof
import dev.sixbridge.manager.conversion.ConversionIdentityCleanupResult
import dev.sixbridge.manager.conversion.ExistingConversionTarget
import dev.sixbridge.manager.conversion.GeneratedBundle
import dev.sixbridge.manager.conversion.IdentityRequirement
import dev.sixbridge.manager.conversion.InstallationIdentitySelection
import dev.sixbridge.manager.conversion.InstallationIdentityMode
import dev.sixbridge.manager.conversion.LegacyConversionIdentity
import dev.sixbridge.manager.conversion.OnDeviceInputRequest
import dev.sixbridge.manager.conversion.OnDeviceConversionApproval
import dev.sixbridge.manager.conversion.RestoredConversion
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.install.InstallCleanupResult
import dev.sixbridge.manager.install.FinalReceiptStage
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.install.InstallTerminalOwnerResult
import dev.sixbridge.manager.install.NormalHandoffStage
import dev.sixbridge.manager.install.NormalInstallHandoff
import dev.sixbridge.manager.install.SamsungInternetSupportStatus
import dev.sixbridge.manager.navigation.FileManagerNavigator
import dev.sixbridge.manager.uninstall.UninstallOutcome
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.conversion.ConversionAuthorization
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ManagerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun `시작 정리와 reconcile 뒤 catalog Home을 표시한다`() = runTest(dispatcher.scheduler) {
        val repository = FakeManagerRepository().apply {
            catalogFlow.tryEmit(listOf(catalogRecord()))
        }

        val viewModel = ManagerViewModel(repository, SavedStateHandle())
        advanceUntilIdle()

        assertTrue(repository.initialized)
        assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        assertEquals(listOf("확장"), viewModel.state.value.installed.map(ManagedExtensionItem::name))
        assertContentEquals(
            ICON,
            viewModel.state.value.installed.single().iconPng,
        )
    }

    @Test
    fun `시작에서 폐기한 미완료 변환은 saved owner를 지우고 일회성 안내를 표시한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                    ManagerViewModel.CONVERSION_ROUTE_KEY to "analysis",
                ),
            )
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.InterruptedDiscarded
            }

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.READY,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertIs<ConversionUiState.Idle>(viewModel.state.value.conversionState)
            assertNull(savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
            assertNull(savedState.get<String>(ManagerViewModel.CONVERSION_ROUTE_KEY))
            assertEquals(UserMessageKind.CONVERSION_FAILURE, viewModel.state.value.message?.kind)
            assertEquals(RecoveryAction.NONE, viewModel.state.value.message?.recoveryAction)
            assertTrue(repository.discardedConversions.isEmpty())

            val recreated = ManagerViewModel(FakeManagerRepository(), savedState)
            advanceUntilIdle()

            assertNull(recreated.state.value.message)
        }

    @Test
    fun `시작 미완료 변환 cleanup 실패는 exact token retry 상태를 유지한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                    ManagerViewModel.CONVERSION_ROUTE_KEY to "analysis",
                ),
            )
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.InterruptedCleanupPending(TOKEN)
            }

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.READY,
                viewModel.state.value.startupRecoveryState,
            )
            assertTrue(viewModel.state.value.conversionCleanupPending)
            assertEquals(
                TOKEN,
                assertIs<ConversionUiState.Cancelling>(
                    viewModel.state.value.conversionState,
                ).token,
            )
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(RecoveryAction.RETRY, viewModel.state.value.message?.recoveryAction)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
            assertTrue(repository.discardedConversions.isEmpty())

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertFalse(viewModel.state.value.conversionCleanupPending)
            assertIs<ConversionUiState.Idle>(viewModel.state.value.conversionState)
            assertNull(savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
        }

    @Test
    fun `시작 BUNDLE_READY는 generated result를 폐기하지 않고 복구한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                    ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
                ),
            )
            val restoredReview = review(ContractCompatibilityStatus.COMPATIBLE)
            assertTrue(Regex("^[0-9a-f]{64}$").matches(restoredReview.wrapperSha256))
            assertTrue(Regex("^[0-9a-f]{64}$").matches(restoredReview.signerSha256))
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    restoredReview,
                    generatedBundle(),
                )
            }

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.READY,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertEquals(
                TOKEN,
                assertIs<ConversionUiState.ResultReady>(
                    viewModel.state.value.conversionState,
                ).token,
            )
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
            assertTrue(repository.discardedConversions.isEmpty())
        }

    @Test
    fun `initialize 전 import와 conversion은 PREPARED normal handoff보다 먼저 시작하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val initializeResult = CompletableDeferred<ManagerRestoreResult>()
            val handoff = NormalInstallHandoff(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Cancelled,
                stage = NormalHandoffStage.PREPARED,
            )
            val repository = FakeManagerRepository().apply {
                initializeDeferred = initializeResult
                normalHandoff = handoff
                currentTerminalReceiptResult = TerminalReceiptOwnerResult.OwnerMismatch
            }
            var importCalled = false
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            dispatcher.scheduler.runCurrent()

            viewModel.startImport {
                importCalled = true
                ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE, SECOND_TOKEN),
                )
            }
            viewModel.accept(ManagerAction.OpenConversionInput)
            dispatcher.scheduler.runCurrent()

            assertFalse(importCalled)
            assertEquals(
                StartupRecoveryUiState.INITIALIZING,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertTrue(repository.discardedTokens.isEmpty())
            assertEquals(0, repository.cancelInstallRequests)

            initializeResult.complete(
                ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    normalHandoff = handoff,
                ),
            )
            advanceUntilIdle()

            assertEquals(handoff, repository.normalHandoff)
            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
            assertFalse(importCalled)
        }

    @Test
    fun `initialize transient 예외는 bounded retry 뒤 READY로 복구한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                initializeFailuresRemaining = 2
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val savedState = SavedStateHandle(
                mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN),
            )

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(3, repository.initializeRequests)
            assertEquals(
                StartupRecoveryUiState.READY,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
        }

    @Test
    fun `initialize persistent 예외는 bounded retry 뒤 owner를 보존하고 fail closed한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.INSTALL_SESSION_ID_KEY to 41,
                ),
            )
            val repository = FakeManagerRepository().apply {
                initializeException = IllegalStateException("persistent")
            }

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(3, repository.initializeRequests)
            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
            assertEquals(41, savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))
            assertTrue(repository.discardAttempts.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
        }

    @Test
    fun `startup normal handoff proof 실패는 durable owner를 보존하고 READY를 열지 않는다`() =
        runTest(dispatcher.scheduler) {
            val handoff = NormalInstallHandoff(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Cancelled,
                stage = NormalHandoffStage.PREPARED,
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    normalHandoff = handoff,
                )
                normalHandoff = handoff
                currentTerminalReceiptResult = TerminalReceiptOwnerResult.OwnerMismatch
            }

            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(handoff, repository.normalHandoff)
            assertTrue(repository.discardAttempts.isEmpty())
        }

    @Test
    fun `startup normal handoff rebase 실패는 durable handoff와 receipt를 보존한다`() =
        runTest(dispatcher.scheduler) {
            val handoff = NormalInstallHandoff(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Cancelled,
                stage = NormalHandoffStage.PREPARED,
            )
            val terminal = RestoredInstallTerminal(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Failed("LATE_FAILURE", "late failure"),
                stage = FinalReceiptStage.READY,
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    terminal = terminal,
                    normalHandoff = handoff,
                )
                normalHandoff = handoff
                rawReadyTerminal = terminal
                normalHandoffRebaseResult = false
                terminalReceiptActive = true
            }

            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(handoff, repository.normalHandoff)
            assertTrue(repository.terminalReceiptActive)
        }

    @Test
    fun `startup restored terminal finalize 실패는 receipt와 generated owner를 보존한다`() =
        runTest(dispatcher.scheduler) {
            val terminal = RestoredInstallTerminal(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Cancelled,
                stage = FinalReceiptStage.READY,
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    installSessionId = 41,
                    terminal = terminal,
                )
                installFlow.value = InstallState.Cancelled
                terminalReceiptActive = true
                terminalFinalizeResult = false
                terminalAcknowledgeResult = false
            }
            val savedState = SavedStateHandle(
                mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN),
            )

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
            assertTrue(repository.terminalReceiptActive)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
        }

    @Test
    fun `startup generated terminal handoff finalize 실패는 handoff와 owner를 보존한다`() =
        runTest(dispatcher.scheduler) {
            val handoffJson = """
                {
                  "version": 1,
                  "token": "$TOKEN",
                  "reviewToken": "$TOKEN",
                  "sessionId": 41,
                  "state": "CANCELLED"
                }
            """.trimIndent()
            val terminal = RestoredInstallTerminal(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Cancelled,
                stage = FinalReceiptStage.READY,
            )
            val savedState = generatedInstallSavedState(
                mapOf(ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY to handoffJson),
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    installSessionId = 41,
                    terminal = terminal,
                )
                restoredConversion = RestoredConversion.GeneratedPending(generatedBundle())
                installFlow.value = InstallState.AwaitingUserApproval(41)
                terminalReceiptActive = true
                terminalFinalizeResult = false
                terminalAcknowledgeResult = false
            }

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(
                handoffJson,
                savedState.get<String>(ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY),
            )
            assertEquals(
                TOKEN,
                savedState.get<String>(
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                ),
            )
            assertTrue(repository.terminalReceiptActive)
        }

    @Test
    fun `malformed generated terminal handoff는 repository mutation 전 원본을 보존하고 blocked한다`() =
        runTest(dispatcher.scheduler) {
            val malformed = "{not-valid-json"
            val savedState = generatedInstallSavedState(
                mapOf(ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY to malformed),
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    installSessionId = 41,
                    terminal = restoredCancelledTerminal(),
                )
                restoredConversion = RestoredConversion.GeneratedPending(generatedBundle())
                installFlow.value = InstallState.AwaitingUserApproval(41)
            }

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(
                malformed,
                savedState.get<Any>(ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY),
            )
            assertFalse(repository.initialized)
            assertTrue(repository.terminalReceiptActive)
        }

    @Test
    fun `wrong type generated terminal handoff는 constructor crash 없이 원본을 보존하고 blocked한다`() =
        runTest(dispatcher.scheduler) {
            val wrongType: Any = 7
            val savedState = generatedInstallSavedState(
                mapOf(ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY to wrongType),
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    installSessionId = 41,
                    terminal = restoredCancelledTerminal(),
                )
                restoredConversion = RestoredConversion.GeneratedPending(generatedBundle())
                installFlow.value = InstallState.AwaitingUserApproval(41)
            }

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(
                wrongType,
                savedState.get<Any>(ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY),
            )
            assertFalse(repository.initialized)
            assertTrue(repository.terminalReceiptActive)
        }

    @Test
    fun `recovery blocked는 보고서 외 mutation과 callback을 모두 차단한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(recoveryBlocked = true)
                catalogFlow.tryEmit(listOf(catalogRecord()))
            }
            val savedState = SavedStateHandle()
            val effects = mutableListOf<ManagerEffect>()
            val viewModel = ManagerViewModel(repository, savedState)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.effects.toList(effects)
            }
            advanceUntilIdle()

            listOf(
                ManagerAction.OpenConversionInput,
                ManagerAction.OpenSamsungAddOns,
                ManagerAction.Remove(PACKAGE),
                ManagerAction.InstallReviewedBundle,
                ManagerAction.ReturnedToForeground,
                ManagerAction.DismissMessage,
                ManagerAction.RetryRecovery,
            ).forEach(viewModel::accept)
            viewModel.startImport { error("blocked import must not run") }
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(
                UserMessageKind.RECOVERY_BLOCKED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(RecoveryAction.NONE, viewModel.state.value.message?.recoveryAction)
            assertTrue(effects.isEmpty())
            assertEquals(0, repository.cleanupRequestCount())
            assertTrue(repository.discardedTokens.isEmpty())

            viewModel.accept(ManagerAction.OpenReport(PACKAGE))
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
        }

    @Test
    fun `OpenSixFilePicker는 READY에서 PickSixDocument를 발생시키고 blocked에서는 차단된다`() =
        runTest(dispatcher.scheduler) {
            val viewModel = ManagerViewModel(FakeManagerRepository(), SavedStateHandle())
            val effects = mutableListOf<ManagerEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.effects.toList(effects)
            }
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenSixFilePicker)
            dispatcher.scheduler.runCurrent()

            val picker = assertIs<ManagerEffect.PickSixDocument>(effects.single())
            assertTrue(picker.ownerGeneration > 0)
            assertTrue(picker.launchId > 0)

            val blocked = ManagerViewModel(
                FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(recoveryBlocked = true)
                },
                SavedStateHandle(),
            )
            val blockedEffects = mutableListOf<ManagerEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                blocked.effects.toList(blockedEffects)
            }
            advanceUntilIdle()

            blocked.accept(ManagerAction.OpenSixFilePicker)
            dispatcher.scheduler.runCurrent()

            assertTrue(blockedEffects.isEmpty())
        }

    @Test
    fun `홈의 데스크탑 변환 파일 버튼은 draft 전용 picker를 요청한다`() =
        runTest(dispatcher.scheduler) {
            val viewModel = ManagerViewModel(FakeManagerRepository(), SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenDraftFilePicker)

            val picker = assertIs<ManagerEffect.PickDraftDocument>(
                viewModel.effects.first(),
            )
            assertTrue(picker.ownerGeneration > 0)
            assertTrue(picker.launchId > 0)
        }

    @Test
    fun `draft picker 취소 뒤 홈에서 새 picker를 다시 열 수 있다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenDraftFilePicker)
            val cancelled = assertIs<ManagerEffect.PickDraftDocument>(
                viewModel.effects.first(),
            )

            viewModel.accept(
                ManagerAction.DraftFileSelected(
                    uri = null,
                    ownerGeneration = cancelled.ownerGeneration,
                    launchId = cancelled.launchId,
                ),
            )
            viewModel.accept(ManagerAction.OpenDraftFilePicker)
            val reopened = assertIs<ManagerEffect.PickDraftDocument>(
                viewModel.effects.first(),
            )

            assertTrue(cancelled.ownerGeneration != reopened.ownerGeneration)
            assertTrue(cancelled.launchId != reopened.launchId)
            assertTrue(repository.conversionRequests.isEmpty())
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        }

    @Test
    fun `draft 선택은 SAF 분석 route와 검증된 signer를 UI까지 유지한다`() =
        runTest(dispatcher.scheduler) {
            val fingerprint = "a".repeat(64)
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis().copy(
                    draftSignerFingerprint = fingerprint,
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenDraftFilePicker)
            val effect = assertIs<ManagerEffect.PickDraftDocument>(
                viewModel.effects.first(),
            )
            val uri = unitTestUri()

            viewModel.accept(
                ManagerAction.DraftFileSelected(
                    uri = uri,
                    ownerGeneration = effect.ownerGeneration,
                    launchId = effect.launchId,
                ),
            )
            advanceUntilIdle()

            assertTrue(
                uri === assertIs<OnDeviceInputRequest.Saf>(
                    repository.conversionRequests.single(),
                ).uri,
            )
            assertEquals(ManagerRoute.ConversionAnalysis(TOKEN), viewModel.state.value.route)
            assertEquals(
                fingerprint,
                assertIs<ConversionUiState.AnalysisReady>(
                    viewModel.state.value.conversionState,
                ).draftSignerFingerprint,
            )
        }

    @Test
    fun `새 draft picker B 뒤 늦은 A result는 분석을 시작하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis()
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenDraftFilePicker)
            val first = assertIs<ManagerEffect.PickDraftDocument>(viewModel.effects.first())
            viewModel.accept(ManagerAction.OpenDraftFilePicker)
            val second = assertIs<ManagerEffect.PickDraftDocument>(viewModel.effects.first())

            viewModel.accept(
                ManagerAction.DraftFileSelected(
                    unitTestUri(),
                    first.ownerGeneration,
                    first.launchId,
                ),
            )
            dispatcher.scheduler.runCurrent()
            assertTrue(repository.conversionRequests.isEmpty())

            viewModel.accept(
                ManagerAction.DraftFileSelected(
                    unitTestUri(),
                    second.ownerGeneration,
                    second.launchId,
                ),
            )
            advanceUntilIdle()
            assertEquals(1, repository.conversionRequests.size)
        }

    @Test
    fun `pending draft picker ownership은 ViewModel 재생성 뒤 exact callback을 한 번 소비한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis()
            }
            val savedState = SavedStateHandle()
            val first = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            first.accept(ManagerAction.OpenDraftFilePicker)
            val effect = assertIs<ManagerEffect.PickDraftDocument>(first.effects.first())

            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            val callback = ManagerAction.DraftFileSelected(
                unitTestUri(),
                effect.ownerGeneration,
                effect.launchId,
            )
            recreated.accept(callback)
            advanceUntilIdle()
            recreated.accept(callback)
            advanceUntilIdle()

            assertEquals(1, repository.conversionRequests.size)
        }

    @Test
    fun `Six OpenDocument callback은 exact launch identity만 한 번 import한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenSixFilePicker)
            val effect = assertIs<ManagerEffect.PickSixDocument>(
                viewModel.effects.first(),
            )
            val uri = unitTestUri()
            val callback = ManagerAction.ImportSelected(
                uri = uri,
                ownerGeneration = effect.ownerGeneration,
                launchId = effect.launchId,
            )

            viewModel.accept(callback)
            advanceUntilIdle()
            viewModel.accept(callback)
            advanceUntilIdle()

            assertEquals(1, repository.uriImportRequests)
            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
        }

    @Test
    fun `stale Six picker result는 새 review owner를 덮지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenSixFilePicker)
            val stale = assertIs<ManagerEffect.PickSixDocument>(
                viewModel.effects.first(),
            )

            viewModel.startImport {
                ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE, SECOND_TOKEN),
                )
            }
            advanceUntilIdle()
            viewModel.accept(
                ManagerAction.ImportSelected(
                    uri = unitTestUri(),
                    ownerGeneration = stale.ownerGeneration,
                    launchId = stale.launchId,
                ),
            )
            advanceUntilIdle()

            assertEquals(0, repository.uriImportRequests)
            assertEquals(ManagerRoute.Review(SECOND_TOKEN), viewModel.state.value.route)
        }

    @Test
    fun `stale Six picker effect는 새 user action 뒤 수집되지 않는다`() =
        runTest(dispatcher.scheduler) {
            val viewModel = ManagerViewModel(FakeManagerRepository(), SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenSixFilePicker)
            viewModel.accept(ManagerAction.ReturnHome)
            val effects = mutableListOf<ManagerEffect>()
            val collector = launch { viewModel.effects.collect(effects::add) }

            dispatcher.scheduler.runCurrent()

            assertTrue(effects.isEmpty())
            collector.cancel()
        }

    @Test
    fun `새 Six picker B 뒤 늦은 A callback은 B launch로 오인하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenSixFilePicker)
            val first = assertIs<ManagerEffect.PickSixDocument>(
                viewModel.effects.first(),
            )
            viewModel.accept(ManagerAction.OpenSixFilePicker)
            val second = assertIs<ManagerEffect.PickSixDocument>(
                viewModel.effects.first(),
            )

            viewModel.accept(
                ManagerAction.ImportSelected(
                    unitTestUri(),
                    first.ownerGeneration,
                    first.launchId,
                ),
            )
            dispatcher.scheduler.runCurrent()
            assertEquals(0, repository.uriImportRequests)

            viewModel.accept(
                ManagerAction.ImportSelected(
                    unitTestUri(),
                    second.ownerGeneration,
                    second.launchId,
                ),
            )
            advanceUntilIdle()
            assertEquals(1, repository.uriImportRequests)
        }

    @Test
    fun `PrepareReviewedInstall은 알림 permission 없이 exact review 설치를 시작한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
            )
            val effects = mutableListOf<ManagerEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.effects.toList(effects)
            }
            advanceUntilIdle()

            viewModel.accept(ManagerAction.PrepareReviewedInstall)
            advanceUntilIdle()

            assertEquals(1, repository.installRequests)
            assertTrue(effects.isEmpty())
            val blocked = ManagerViewModel(
                FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(recoveryBlocked = true)
                },
                SavedStateHandle(),
            )
            val blockedEffects = mutableListOf<ManagerEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                blocked.effects.toList(blockedEffects)
            }
            advanceUntilIdle()

            blocked.accept(ManagerAction.PrepareReviewedInstall)
            dispatcher.scheduler.runCurrent()

            assertTrue(blockedEffects.isEmpty())
        }

    @Test
    fun `PrepareReviewedInstall은 다른 manual install을 정리한 뒤 exact review를 시작한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
            )
            advanceUntilIdle()
            repository.installFlow.value = InstallState.AwaitingManualInstall(SECOND_TOKEN)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.PrepareReviewedInstall)
            advanceUntilIdle()

            assertEquals(
                listOf<Pair<String, Int?>>(SECOND_TOKEN to null),
                repository.cancelInstallArguments,
            )
            assertEquals(1, repository.installRequests)
            assertEquals(
                listOf("cancelInstall", "install:start", "install:end"),
                repository.lifecycleEvents,
            )
        }

    @Test
    fun `PrepareReviewedInstall 직후 owner가 바뀌면 기존 설치 결과를 적용하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                suspendInstall = true
                installResult = InstallState.Failed("STALE", "stale")
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
            )
            advanceUntilIdle()

            viewModel.accept(ManagerAction.PrepareReviewedInstall)
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.ReturnHome)
            repository.completeInstall()
            advanceUntilIdle()

            assertEquals(1, repository.installRequests)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
        }

    @Test
    fun `generated install은 알림 permission 없이 기존 install action을 진행한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(
                    mapOf(
                        ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                        ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
                    ),
                ),
            )
            advanceUntilIdle()
            val effects = mutableListOf<ManagerEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.effects.toList(effects)
            }

            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            viewModel.accept(ManagerAction.PrepareReviewedInstall)
            advanceUntilIdle()

            assertTrue(effects.isEmpty())
            assertTrue(
                assertIs<ConversionUiState.ResultReady>(
                    viewModel.state.value.conversionState,
                ).installOwnerActive,
            )
            assertEquals(1, repository.installRequests)
        }

    @Test
    fun `generated install은 다른 manual install을 정리한 뒤 current review를 시작한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(
                    mapOf(
                        ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                        ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
                    ),
                ),
            )
            advanceUntilIdle()
            repository.installFlow.value = InstallState.AwaitingManualInstall(SECOND_TOKEN)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            advanceUntilIdle()

            assertEquals(
                listOf<Pair<String, Int?>>(SECOND_TOKEN to null),
                repository.cancelInstallArguments,
            )
            assertEquals(1, repository.installRequests)
            assertEquals(
                listOf("cancelInstall", "install:start", "install:end"),
                repository.lifecycleEvents,
            )
        }

    @Test
    fun `conversion restore suspend 중 도착한 exact generated terminal은 READY 전에 reconcile한다`() =
        runTest(dispatcher.scheduler) {
            val conversionResult = CompletableDeferred<RestoredConversion?>()
            val installed = InstallState.Installed(PACKAGE)
            val savedState = generatedInstallSavedState(emptyMap())
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    installSessionId = 41,
                )
                restoreConversionDeferred = conversionResult
                installFlow.value = InstallState.AwaitingUserApproval(41)
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = installed
                terminalOwnerProofConfigured = true
            }
            val viewModel = ManagerViewModel(repository, savedState)
            dispatcher.scheduler.runCurrent()

            assertEquals(1, repository.restoreConversionRequests)
            assertEquals(
                StartupRecoveryUiState.INITIALIZING,
                viewModel.state.value.startupRecoveryState,
            )

            repository.installFlow.value = installed
            dispatcher.scheduler.runCurrent()

            assertEquals(
                StartupRecoveryUiState.INITIALIZING,
                viewModel.state.value.startupRecoveryState,
            )
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())

            conversionResult.complete(
                RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.READY,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
            assertEquals("COMMIT_DESTINATION", generatedCleanupPhase(savedState))
            assertEquals(TOKEN, viewModel.state.value.destinationCommitToken)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }

    @Test
    fun `terminal owner proof suspend 중 새 exact terminal은 READY 전에 적용한다`() =
        runTest(dispatcher.scheduler) {
            val conversionResult = CompletableDeferred<RestoredConversion?>()
            val stale = InstallState.Failed("STALE", "stale terminal")
            val savedState = generatedInstallSavedState(emptyMap())
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    installSessionId = 41,
                )
                restoreConversionDeferred = conversionResult
                installFlow.value = InstallState.AwaitingUserApproval(41)
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = InstallState.Cancelled
                terminalOwnerProofConfigured = true
                suspendTerminalOwnerAfterRequests = 0
            }
            val viewModel = ManagerViewModel(repository, savedState)
            val states = mutableListOf<ManagerUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                viewModel.state.toList(states)
            }
            dispatcher.scheduler.runCurrent()

            repository.installFlow.value = stale
            dispatcher.scheduler.runCurrent()
            conversionResult.complete(
                RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                ),
            )
            dispatcher.scheduler.runCurrent()

            assertEquals(
                listOf<Pair<String, Int?>>(TOKEN to 41),
                repository.terminalOwnerArguments,
            )
            assertEquals(
                StartupRecoveryUiState.INITIALIZING,
                viewModel.state.value.startupRecoveryState,
            )

            repository.installFlow.value = InstallState.Cancelled
            dispatcher.scheduler.runCurrent()
            repository.completeTerminalOwnerProof()
            advanceUntilIdle()

            val firstReady = states.first {
                it.startupRecoveryState == StartupRecoveryUiState.READY
            }
            assertEquals(ManagerRoute.ConversionResult(TOKEN), firstReady.route)
            assertEquals(UserMessageKind.INSTALL_CANCELLED, firstReady.message?.kind)
            assertFalse(
                assertIs<ConversionUiState.ResultReady>(
                    firstReady.conversionState,
                ).installOwnerActive,
            )
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                ),
            )
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY,
                ),
            )
        }

    @Test
    fun `초기 terminal snapshot 뒤 conversion restore 중 새 exact terminal은 owner 복원 후 reconcile한다`() =
        runTest(dispatcher.scheduler) {
            val initializeResult = CompletableDeferred<ManagerRestoreResult>()
            val conversionResult = CompletableDeferred<RestoredConversion?>()
            val installed = InstallState.Installed(PACKAGE)
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.INSTALL_SESSION_ID_KEY to 41,
                    ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                    ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY to TOKEN,
                ),
            )
            val repository = FakeManagerRepository().apply {
                initializeDeferred = initializeResult
                restoreConversionDeferred = conversionResult
                installFlow.value = InstallState.Cancelled
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = installed
                terminalOwnerProofConfigured = true
            }
            val viewModel = ManagerViewModel(repository, savedState)
            dispatcher.scheduler.runCurrent()

            initializeResult.complete(
                ManagerRestoreResult(
                    review = null,
                    installSessionId = 41,
                ),
            )
            dispatcher.scheduler.runCurrent()

            assertEquals(1, repository.restoreConversionRequests)
            assertEquals(
                StartupRecoveryUiState.INITIALIZING,
                viewModel.state.value.startupRecoveryState,
            )
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())

            repository.installFlow.value = installed
            dispatcher.scheduler.runCurrent()

            assertEquals(
                StartupRecoveryUiState.INITIALIZING,
                viewModel.state.value.startupRecoveryState,
            )
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())

            conversionResult.complete(
                RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.READY,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
            assertEquals("COMMIT_DESTINATION", generatedCleanupPhase(savedState))
            assertEquals(TOKEN, viewModel.state.value.destinationCommitToken)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }

    @Test
    fun `conversion restore suspend 중 반복 terminal은 bounded 최신 exact만 owner 복원 뒤 적용한다`() =
        runTest(dispatcher.scheduler) {
            val initializeResult = CompletableDeferred<ManagerRestoreResult>()
            val conversionResult = CompletableDeferred<RestoredConversion?>()
            val installed = InstallState.Installed(PACKAGE)
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.INSTALL_SESSION_ID_KEY to 41,
                    ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                    ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY to TOKEN,
                ),
            )
            val repository = FakeManagerRepository().apply {
                initializeDeferred = initializeResult
                restoreConversionDeferred = conversionResult
                installFlow.value = InstallState.Cancelled
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = installed
                terminalOwnerProofConfigured = true
                suspendTerminalOwnerAfterRequests = 2
            }
            val viewModel = ManagerViewModel(repository, savedState)
            dispatcher.scheduler.runCurrent()

            initializeResult.complete(
                ManagerRestoreResult(
                    review = null,
                    installSessionId = 41,
                ),
            )
            dispatcher.scheduler.runCurrent()

            repeat(12) { index ->
                repository.installFlow.value = InstallState.Failed(
                    code = "STALE_$index",
                    message = "stale terminal $index",
                )
                dispatcher.scheduler.runCurrent()
            }
            repository.installFlow.value = installed
            dispatcher.scheduler.runCurrent()

            conversionResult.complete(
                RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.READY,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(installed, viewModel.state.value.installState)
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
            assertEquals("COMMIT_DESTINATION", generatedCleanupPhase(savedState))
            assertEquals(TOKEN, viewModel.state.value.destinationCommitToken)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }

    @Test
    fun `owner 복원 실패 READY는 deferred terminal을 폐기해 다음 owner를 오염시키지 않는다`() =
        runTest(dispatcher.scheduler) {
            val initializeResult = CompletableDeferred<ManagerRestoreResult>()
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.INSTALL_SESSION_ID_KEY to 41,
                    ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                    ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY to TOKEN,
                ),
            )
            val repository = FakeManagerRepository().apply {
                initializeDeferred = initializeResult
                restoredConversion = null
                installFlow.value = InstallState.Cancelled
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = InstallState.Cancelled
                terminalOwnerProofConfigured = true
            }
            val viewModel = ManagerViewModel(repository, savedState)
            dispatcher.scheduler.runCurrent()
            initializeResult.complete(
                ManagerRestoreResult(
                    review = null,
                    installSessionId = 41,
                ),
            )
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.READY,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
            assertFalse(hasDeferredInitialTerminal(viewModel))
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                ),
            )
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())

            viewModel.startImport {
                ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE, SECOND_TOKEN),
                )
            }
            advanceUntilIdle()
            repository.installFlow.value = InstallState.Idle
            dispatcher.scheduler.runCurrent()
            repository.installFlow.value = InstallState.Cancelled
            advanceUntilIdle()

            assertEquals(ManagerRoute.Review(SECOND_TOKEN), viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(
                    viewModel.state.value.importState,
                ).review.token,
            )
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
        }

    @Test
    fun `recovery blocked 보고서 왕복은 고정 메시지를 보존한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(recoveryBlocked = true)
                catalogFlow.tryEmit(listOf(catalogRecord()))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenReport(PACKAGE))

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertEquals(
                UserMessageKind.RECOVERY_BLOCKED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(
                "저장된 설치 복구 정보를 읽을 수 없어 복구 자료를 보존했습니다. " +
                    "앱을 업데이트한 뒤 다시 확인해 주세요.",
                viewModel.state.value.message?.text,
            )
            assertEquals(RecoveryAction.NONE, viewModel.state.value.message?.recoveryAction)

            viewModel.accept(ManagerAction.ReturnHome)

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals(
                UserMessageKind.RECOVERY_BLOCKED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(
                "저장된 설치 복구 정보를 읽을 수 없어 복구 자료를 보존했습니다. " +
                    "앱을 업데이트한 뒤 다시 확인해 주세요.",
                viewModel.state.value.message?.text,
            )
            assertEquals(RecoveryAction.NONE, viewModel.state.value.message?.recoveryAction)
        }

    @Test
    fun `설치 목록의 보고서만 열고 홈으로 돌아온다`() = runTest(dispatcher.scheduler) {
        val repository = FakeManagerRepository().apply {
            catalogFlow.tryEmit(listOf(catalogRecord()))
        }
        val viewModel = ManagerViewModel(repository, SavedStateHandle())
        advanceUntilIdle()

        viewModel.accept(ManagerAction.OpenReport("설치되지.않은.package"))
        assertEquals(ManagerRoute.Home, viewModel.state.value.route)

        viewModel.accept(ManagerAction.OpenReport(PACKAGE))
        assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)

        viewModel.accept(ManagerAction.ReturnHome)
        assertEquals(ManagerRoute.Home, viewModel.state.value.route)
    }

    @Test
    fun `legacy 관리 진입은 startup gate를 지키고 재진입 중 목록을 중복 요청하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val blockedRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(recoveryBlocked = true)
                legacyIdentities = listOf(legacyIdentity())
            }
            val blocked = ManagerViewModel(blockedRepository, SavedStateHandle())
            advanceUntilIdle()

            blocked.accept(ManagerAction.OpenConversionIdentityManagement)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, blocked.state.value.route)
            assertEquals(0, blockedRepository.legacyListRequests)

            val repository = FakeManagerRepository().apply {
                legacyIdentities = listOf(legacyIdentity())
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenConversionIdentityManagement)
            viewModel.accept(ManagerAction.OpenConversionIdentityManagement)
            advanceUntilIdle()

            assertEquals(
                ManagerRoute.ConversionIdentityManagement,
                viewModel.state.value.route,
            )
            assertEquals(1, repository.legacyListRequests)
            assertEquals(
                repository.legacyIdentities,
                viewModel.state.value.conversionIdentityManagementState.identities,
            )
        }

    @Test
    fun `legacy 목록 요청 중 back 후 재진입해도 기존 요청을 공유한다`() =
        runTest(dispatcher.scheduler) {
            val pendingList = CompletableDeferred<List<LegacyConversionIdentity>>()
            val repository = FakeManagerRepository().apply {
                legacyListDeferred = pendingList
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenConversionIdentityManagement)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, repository.legacyListRequests)

            viewModel.accept(ManagerAction.ReturnHome)
            viewModel.accept(ManagerAction.OpenConversionIdentityManagement)
            dispatcher.scheduler.runCurrent()

            assertEquals(
                ManagerRoute.ConversionIdentityManagement,
                viewModel.state.value.route,
            )
            assertEquals(1, repository.legacyListRequests)

            pendingList.complete(listOf(legacyIdentity()))
            advanceUntilIdle()
            assertEquals(
                listOf(legacyIdentity()),
                viewModel.state.value.conversionIdentityManagementState.identities,
            )
        }

    @Test
    fun `legacy cleanup은 exact confirm을 한 번만 위임하고 blocked 결과를 보존으로 표시한 뒤 reload한다`() =
        runTest(dispatcher.scheduler) {
            val identity = legacyIdentity()
            val repository = FakeManagerRepository().apply {
                legacyIdentities = listOf(identity)
                legacyCleanupResult = ConversionIdentityCleanupResult.Blocked(
                    "강한 identity proof가 없습니다.",
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionIdentityManagement)
            advanceUntilIdle()

            viewModel.accept(
                ManagerAction.RequestLegacyIdentityCleanup(identity.identitySha256),
            )
            assertEquals(
                identity,
                viewModel.state.value.conversionIdentityManagementState
                    .pendingCleanupIdentity,
            )

            viewModel.accept(ManagerAction.ConfirmLegacyIdentityCleanup("b".repeat(64)))
            viewModel.accept(
                ManagerAction.ConfirmLegacyIdentityCleanup(identity.identitySha256),
            )
            viewModel.accept(
                ManagerAction.ConfirmLegacyIdentityCleanup(identity.identitySha256),
            )
            advanceUntilIdle()

            assertEquals(listOf(identity.identitySha256), repository.legacyCleanupRequests)
            assertEquals(2, repository.legacyListRequests)
            val state = viewModel.state.value.conversionIdentityManagementState
            assertEquals(
                ConversionIdentityCleanupUiOutcome.Preserved(
                    identity.displayName,
                    "강한 identity proof가 없습니다.",
                ),
                state.outcome,
            )
            assertEquals(listOf(identity), state.identities)
            assertNull(state.cleanupInProgressIdentitySha256)
        }

    @Test
    fun `legacy cleanup Removed 후 reload한 목록에서 exact row가 사라진다`() =
        runTest(dispatcher.scheduler) {
            val identity = legacyIdentity()
            val repository = FakeManagerRepository().apply {
                legacyIdentities = listOf(identity)
                legacyCleanupResult = ConversionIdentityCleanupResult.Removed
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionIdentityManagement)
            advanceUntilIdle()

            viewModel.accept(
                ManagerAction.RequestLegacyIdentityCleanup(identity.identitySha256),
            )
            viewModel.accept(
                ManagerAction.ConfirmLegacyIdentityCleanup(identity.identitySha256),
            )
            advanceUntilIdle()

            val state = viewModel.state.value.conversionIdentityManagementState
            assertTrue(state.identities.isEmpty())
            assertEquals(
                ConversionIdentityCleanupUiOutcome.Removed(identity.displayName),
                state.outcome,
            )
            assertEquals(2, repository.legacyListRequests)
        }

    @Test
    fun `catalog의 정상 report status와 내용만 표시 모델로 사용한다`() =
        runTest(dispatcher.scheduler) {
            val report = compatibilityReport(ContractCompatibilityStatus.PARTIAL)
            val repository = FakeManagerRepository().apply {
                catalogFlow.tryEmit(
                    listOf(
                        catalogRecord(
                            compatibilityStatus = "COMPATIBLE",
                            reportJson = BundleJson.encodeCompatibility(report),
                        ),
                    ),
                )
            }

            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            val item = viewModel.state.value.installed.single()
            assertEquals(ContractCompatibilityStatus.PARTIAL, item.compatibilityStatus)
            assertEquals(ReportAvailability.AVAILABLE, item.reportAvailability)
            assertEquals(listOf("storage"), item.report?.declaredPermissions)
            assertEquals(listOf("API_LIMIT"), item.report?.issues?.map(ContractIssue::code))
            assertEquals(listOf("tabs"), item.report?.adapterDecisions?.map(ContractAdapterDecision::id))
        }

    @Test
    fun `catalog report가 없거나 깨졌으면 stored compatible을 신뢰하지 않고 unknown으로 표시한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                catalogFlow.tryEmit(
                    listOf(
                        catalogRecord(reportJson = null),
                        catalogRecord(
                            packageName = "$PACKAGE.invalid",
                            reportJson = "{broken",
                        ),
                    ),
                )
            }

            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            val missing = viewModel.state.value.installed.first()
            assertEquals(ContractCompatibilityStatus.UNKNOWN, missing.compatibilityStatus)
            assertEquals(ReportAvailability.MISSING, missing.reportAvailability)
            assertNull(missing.report)
            val unreadable = viewModel.state.value.installed.last()
            assertEquals(ContractCompatibilityStatus.UNKNOWN, unreadable.compatibilityStatus)
            assertEquals(ReportAvailability.UNREADABLE, unreadable.reportAvailability)
            assertNull(unreadable.report)
        }

    @Test
    fun `initialize transient 실패는 saved review token을 지우지 않고 재생성에서 복원한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle(mapOf("review_token" to TOKEN))
            val repository = FakeManagerRepository().apply {
                initializeException = IllegalStateException("transient retained load")
            }

            ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(TOKEN, savedState.get<String>("review_token"))

            repository.initializeException = null
            repository.restoreResult = ManagerRestoreResult(
                review = review(ContractCompatibilityStatus.COMPATIBLE),
            )
            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Review(TOKEN), recreated.state.value.route)
            assertEquals(TOKEN, savedState.get<String>("review_token"))
        }

    @Test
    fun `늦은 initialize review는 먼저 도착한 terminal install 상태를 덮지 않는다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle(mapOf("review_token" to TOKEN))
            val repository = FakeManagerRepository().apply {
                suspendInitialize = true
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val viewModel = ManagerViewModel(repository, savedState)
            dispatcher.scheduler.runCurrent()

            repository.installFlow.value = InstallState.Installed(PACKAGE)
            dispatcher.scheduler.runCurrent()
            launch { repository.completeInitialize() }
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertEquals(ImportUiState.Idle, viewModel.state.value.importState)
            assertNull(savedState.get<String>("review_token"))
        }

    @Test
    fun `이전 terminal 상태는 새 B review의 ViewModel 재생성을 지우지 않는다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle(mapOf("review_token" to SECOND_TOKEN))
            val repository = FakeManagerRepository().apply {
                installFlow.value = InstallState.Installed(PACKAGE)
                restoreResult = ManagerRestoreResult(
                    review = review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                )
            }

            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Review(SECOND_TOKEN), recreated.state.value.route)
            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(recreated.state.value.importState)
                    .review.token,
            )
            assertEquals(SECOND_TOKEN, savedState.get<String>("review_token"))
        }

    @Test
    fun `install 반환 전에 observer가 적용한 다른 terminal route를 늦은 결과가 덮지 않는다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle(mapOf("review_token" to TOKEN))
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                suspendInstall = true
                installResult = InstallState.Failed("WRITE_FAILED", "late B failure")
            }
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.InstallReviewedBundle)
            dispatcher.scheduler.runCurrent()
            repository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()
            launch { repository.completeInstall() }
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertEquals(ImportUiState.Idle, viewModel.state.value.importState)
            assertNull(savedState.get<String>("review_token"))
        }

    @Test
    fun `가져오기 중 표시 후 compatible은 즉시 설치할 수 있다`() = runTest(dispatcher.scheduler) {
        val repository = FakeManagerRepository().apply {
            importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            suspendImport = true
            ignoreImportCancellation = true
        }
        val viewModel = ManagerViewModel(repository, SavedStateHandle())
        advanceUntilIdle()

        viewModel.startImport(repository::nextImport)
        dispatcher.scheduler.runCurrent()
        assertEquals(ImportUiState.Importing, viewModel.state.value.importState)

        repository.completeImport()
        advanceUntilIdle()

        val ready = assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState)
        assertTrue(ready.installEnabled)
        assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
    }

    @Test
    fun `partial은 명시 확인 전 설치할 수 없고 확인 뒤 가능하다`() = runTest(dispatcher.scheduler) {
        val repository = FakeManagerRepository().apply {
            importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.PARTIAL))
        }
        val viewModel = ManagerViewModel(repository, SavedStateHandle())
        advanceUntilIdle()

        viewModel.startImport(repository::nextImport)
        advanceUntilIdle()
        assertFalse(assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState).installEnabled)

        viewModel.accept(ManagerAction.InstallReviewedBundle)
        advanceUntilIdle()
        assertEquals(0, repository.installRequests)

        viewModel.accept(ManagerAction.ConfirmPartial)
        assertTrue(assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState).installEnabled)
        viewModel.accept(ManagerAction.InstallReviewedBundle)
        advanceUntilIdle()
        assertEquals(1, repository.installRequests)
        assertTrue(repository.lastPartialConfirmed)
    }

    @Test
    fun `blocked와 unknown은 설치를 막고 서로 다른 한국어 설명을 표시한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            for ((status, expectedKind) in listOf(
                ContractCompatibilityStatus.BLOCKED to UserMessageKind.BLOCKED_COMPATIBILITY,
                ContractCompatibilityStatus.UNKNOWN to UserMessageKind.UNKNOWN_COMPATIBILITY,
            )) {
                repository.importResult = ManagerImportResult.Success(review(status))
                viewModel.startImport(repository::nextImport)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.InstallReviewedBundle)
                advanceUntilIdle()

                assertEquals(0, repository.installRequests)
                assertEquals(expectedKind, viewModel.state.value.message?.kind)
                assertTrue(viewModel.state.value.message?.text.orEmpty().isNotBlank())
                viewModel.accept(ManagerAction.CancelReview)
                advanceUntilIdle()
            }
        }

    @Test
    fun `알 수 없는 출처 설정 effect 뒤 foreground 복귀에서 설치를 resume한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()
            repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
            advanceUntilIdle()

            assertEquals(
                LaunchDestination.UNKNOWN_SOURCES_SETTINGS,
                assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first()).destination,
            )
            assertEquals(UserMessageKind.UNKNOWN_SOURCES_PERMISSION, viewModel.state.value.message?.kind)
            val repeatedEffects = mutableListOf<ManagerEffect>()
            val collector = launch { viewModel.effects.collect(repeatedEffects::add) }
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.ReturnedToForeground)
            advanceUntilIdle()
            assertEquals(1, repository.resumeRequests)
            assertEquals(1, repository.reconcileRequests)
            assertTrue(repeatedEffects.isEmpty())

            viewModel.accept(ManagerAction.OpenUnknownSourcesSettings)
            dispatcher.scheduler.runCurrent()
            assertEquals(
                LaunchDestination.UNKNOWN_SOURCES_SETTINGS,
                assertIs<ManagerEffect.LaunchIntent>(repeatedEffects.single()).destination,
            )
            collector.cancel()
        }

    @Test
    fun `unknown sources에서 writing 또는 approval로 진행하면 권한 메시지를 지운다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
            advanceUntilIdle()
            assertEquals(UserMessageKind.UNKNOWN_SOURCES_PERMISSION, viewModel.state.value.message?.kind)

            repository.installFlow.value = InstallState.WritingSession(40)
            advanceUntilIdle()
            assertNull(viewModel.state.value.message)

            repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
            advanceUntilIdle()
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            advanceUntilIdle()
            assertNull(viewModel.state.value.message)
        }

    @Test
    fun `설치 성공 취소 실패를 각각 상태와 한국어 메시지로 반영한다`() =
        runTest(dispatcher.scheduler) {
            val scenarios = listOf(
                InstallState.Installed(PACKAGE) to null,
                InstallState.Cancelled to UserMessageKind.INSTALL_CANCELLED,
                InstallState.Failed("SIGNER_MISMATCH", "raw") to
                    UserMessageKind.UPDATE_IDENTITY,
                InstallState.Failed("VERSION_NOT_NEWER", "raw") to
                    UserMessageKind.UPDATE_IDENTITY,
                InstallState.Failed("WRITE_FAILED", "raw") to
                    UserMessageKind.INSTALL_FAILED,
            )
            scenarios.forEach { (terminal, expectedMessage) ->
                val repository = FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(
                        review = review(ContractCompatibilityStatus.COMPATIBLE),
                    )
                }
                val viewModel = ManagerViewModel(
                    repository,
                    SavedStateHandle(mapOf("review_token" to TOKEN)),
                )
                advanceUntilIdle()

                repository.installFlow.value = terminal
                advanceUntilIdle()

                if (terminal is InstallState.Installed) {
                    assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
                    assertNull(viewModel.state.value.message)
                    assertEquals(
                        SamsungInternetSupportStatus.TRUSTED_INSTALL_SOURCE,
                        viewModel.state.value.reportInstallSupportStatus,
                    )
                } else {
                    assertEquals(expectedMessage, viewModel.state.value.message?.kind)
                }
                if (terminal is InstallState.Failed && terminal.code == "WRITE_FAILED") {
                    assertEquals(
                        RecoveryAction.SELECT_ANOTHER_FILE,
                        viewModel.state.value.message?.recoveryAction,
                    )
                }
            }
        }

    @Test
    fun `다른 파일 앱 normal handoff만 report 지원 미확인 status를 전달한다`() =
        runTest(dispatcher.scheduler) {
            val installed = InstallState.Installed(
                PACKAGE,
                SamsungInternetSupportStatus.UNVERIFIED_FILE_APP,
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
            )
            advanceUntilIdle()

            repository.installFlow.value = installed
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
            assertEquals(
                SamsungInternetSupportStatus.UNVERIFIED_FILE_APP,
                viewModel.state.value.reportInstallSupportStatus,
            )
        }

    @Test
    fun `지원하지 않거나 확인할 수 없는 설치 출처를 구분해 안내한다`() =
        runTest(dispatcher.scheduler) {
            val scenarios = listOf(
                "UNSUPPORTED_INSTALL_SOURCE" to
                    "확장 APK는 설치됐지만 삼성 인터넷이 허용하는 설치 출처가 아닙니다.\n" +
                    "저장한 APK를 파일 앱에서 직접 열어 다시 설치해 주세요.",
                "INSTALL_SOURCE_UNKNOWN" to "설치 출처를 확인하지 못했습니다.",
            )
            scenarios.forEach { (code, expectedText) ->
                val repository = FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(
                        review = review(ContractCompatibilityStatus.COMPATIBLE),
                    )
                }
                val viewModel = ManagerViewModel(
                    repository,
                    SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
                )
                advanceUntilIdle()

                repository.installFlow.value = InstallState.Failed(code, "raw")
                advanceUntilIdle()

                assertEquals(expectedText, viewModel.state.value.message?.text)
            }
        }

    @Test
    fun `정규화 후 빈 export 버전은 wrapper version code로 파일명을 만든다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE).copy(
                        wrapperVersionCode = 7,
                    ),
                )
                installResult = InstallState.PreparingApkExport(TOKEN)
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = File("/retained/$TOKEN/wrapper.apk"),
                    expectedSha256 = "a".repeat(64),
                    extensionName = "확장",
                    extensionVersion = ".",
                    extensionId = EXTENSION_ID,
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
            )
            advanceUntilIdle()

            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()

            val effect = assertIs<ManagerEffect.CreateInstallApkDocument>(
                viewModel.effects.first(),
            )
            assertEquals("확장-7-aaaaaa.apk", effect.suggestedFileName)
        }

    @Test
    fun `검토 설치는 항상 APK 문서 생성 effect를 발행하고 명시 확인에서만 조정한다`() =
        runTest(dispatcher.scheduler) {
            val source = File("/retained/$SECOND_TOKEN/wrapper.apk")
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE).copy(
                        wrapperVersionCode = 7,
                    ),
                )
                installResult = InstallState.PreparingApkExport(TOKEN)
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = source,
                    expectedSha256 = "a".repeat(64),
                    extensionName = "smart:context/menu",
                    extensionVersion = "",
                    extensionId = EXTENSION_ID,
                )
                manualSystemInstallResult = InstallState.Installed(PACKAGE)
                terminalOwnerProofConfigured = true
                terminalOwnerImportToken = TOKEN
                terminalOwnerPackageName = PACKAGE
                terminalOwnerState = InstallState.Installed(PACKAGE)
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
            )
            advanceUntilIdle()

            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()
            val effect = assertIs<ManagerEffect.CreateInstallApkDocument>(
                viewModel.effects.first(),
            )
            assertEquals(TOKEN, effect.importToken)
            assertEquals(source, effect.source)
            assertEquals("a".repeat(64), effect.expectedSha256)
            assertEquals(
                "smart_context_menu-7-aaaaaa.apk",
                effect.suggestedFileName,
            )

            viewModel.accept(
                ManagerAction.InstallApkCreated(
                    importToken = TOKEN,
                    uri = unitTestUri(),
                    ownerGeneration = effect.ownerGeneration,
                    launchId = effect.launchId,
                ),
            )
            assertEquals(listOf(TOKEN), repository.installApkExportedTokens)
            assertNull(viewModel.state.value.message)

            viewModel.accept(ManagerAction.ReturnedToForeground)
            advanceUntilIdle()
            assertEquals(0, repository.reconcileManualCalls)

            viewModel.accept(ManagerAction.ConfirmManualInstallStatus)
            advanceUntilIdle()
            assertEquals(1, repository.reconcileManualCalls)
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
        }

    @Test
    fun `My Files picker 취소와 stale token은 EXPORTED로 전이하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installResult = InstallState.PreparingApkExport(TOKEN)
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = File("/retained/$TOKEN/wrapper.apk"),
                    expectedSha256 = "a".repeat(64),
                    extensionName = "확장",
                    extensionVersion = "1.0.0",
                    extensionId = EXTENSION_ID,
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()
            val effect = assertIs<ManagerEffect.CreateInstallApkDocument>(
                viewModel.effects.first(),
            )

            viewModel.accept(ManagerAction.InstallApkCreated(SECOND_TOKEN, null, 0, 0))
            viewModel.accept(
                ManagerAction.InstallApkCreated(
                    TOKEN,
                    null,
                    effect.ownerGeneration,
                    effect.launchId,
                ),
            )

            assertTrue(repository.installApkExportedTokens.isEmpty())
            assertEquals(RecoveryAction.RETRY, viewModel.state.value.message?.recoveryAction)
        }

    @Test
    fun `APK picker retry는 새 launch ID를 만들고 이전 callback을 거부한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installResult = InstallState.PreparingApkExport(TOKEN)
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = File("/retained/$TOKEN/wrapper.apk"),
                    expectedSha256 = "a".repeat(64),
                    extensionName = "확장",
                    extensionVersion = "1.0",
                    extensionId = EXTENSION_ID,
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()
            val first = assertIs<ManagerEffect.CreateInstallApkDocument>(
                viewModel.effects.first(),
            )
            viewModel.accept(
                ManagerAction.InstallApkCreated(
                    TOKEN,
                    null,
                    first.ownerGeneration,
                    first.launchId,
                ),
            )
            viewModel.accept(ManagerAction.RetryRecovery)
            val second = assertIs<ManagerEffect.CreateInstallApkDocument>(
                viewModel.effects.first(),
            )

            assertTrue(first.launchId != second.launchId)
            viewModel.accept(
                ManagerAction.InstallApkCreated(
                    TOKEN,
                    unitTestUri(),
                    first.ownerGeneration,
                    first.launchId,
                ),
            )
            assertTrue(repository.installApkExportedTokens.isEmpty())
            viewModel.accept(
                ManagerAction.InstallApkCreated(
                    TOKEN,
                    unitTestUri(),
                    second.ownerGeneration,
                    second.launchId,
                ),
            )
            assertEquals(listOf(TOKEN), repository.installApkExportedTokens)
        }

    @Test
    fun `파일 앱 열기는 지원되는 파일 앱 Intent를 현재 세대 effect로 발행한다`() =
        runTest(dispatcher.scheduler) {
            val launcher = Intent()
            val viewModel = ManagerViewModel(
                FakeManagerRepository(),
                SavedStateHandle(),
                fileManagerNavigator = object : FileManagerNavigator {
                    override fun launcherIntent(): Intent = launcher
                },
            )
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenFileManager)
            val effect = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())

            assertEquals(LaunchDestination.FILE_MANAGER, effect.destination)
            assertTrue(launcher === effect.intentFactory())
            assertNull(viewModel.state.value.message)
        }

    @Test
    fun `지원되는 파일 앱이 없으면 launch 없이 지정 메시지만 표시한다`() =
        runTest(dispatcher.scheduler) {
            val viewModel = ManagerViewModel(
                FakeManagerRepository(),
                SavedStateHandle(),
                fileManagerNavigator = object : FileManagerNavigator {
                    override fun launcherIntent(): Intent? = null
                },
            )
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenFileManager)

            assertEquals(
                "지원되는 파일 앱을 찾을 수 없습니다.\n" +
                    "APK를 파일로 저장한 뒤 다른 기기에서 설치 호환성을 확인해 주세요.",
                viewModel.state.value.message?.text,
            )
            assertEquals(
                UserMessageKind.FILE_MANAGER_MISSING,
                viewModel.state.value.message?.kind,
            )
            assertEquals(RecoveryAction.NONE, viewModel.state.value.message?.recoveryAction)
        }

    @Test
    fun `파일 앱 launch 실패 RETRY는 navigator를 다시 조회해 My Files로 대체한다`() =
        runTest(dispatcher.scheduler) {
            val documentsUi = Intent()
            val myFiles = Intent()
            var launcherIntentCalls = 0
            val viewModel = ManagerViewModel(
                FakeManagerRepository(),
                SavedStateHandle(),
                fileManagerNavigator = object : FileManagerNavigator {
                    override fun launcherIntent(): Intent {
                        launcherIntentCalls += 1
                        return if (launcherIntentCalls == 1) documentsUi else myFiles
                    }
                },
            )
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenFileManager)
            val failed = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            assertTrue(documentsUi === failed.intentFactory())
            viewModel.accept(ManagerAction.LaunchFailed(failed))

            viewModel.accept(ManagerAction.RetryRecovery)
            val retried = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())

            assertEquals(2, launcherIntentCalls)
            assertEquals(LaunchDestination.FILE_MANAGER, retried.destination)
            assertTrue(myFiles === retried.intentFactory())
            assertTrue(failed.ownerGeneration != retried.ownerGeneration)
            viewModel.accept(ManagerAction.LaunchFailed(failed))
            assertNull(viewModel.state.value.message)
        }

    @Test
    fun `파일 앱 launch 실패 RETRY 재조회가 null이면 지원 앱 부재를 표시한다`() =
        runTest(dispatcher.scheduler) {
            val documentsUi = Intent()
            var launcherIntentCalls = 0
            val viewModel = ManagerViewModel(
                FakeManagerRepository(),
                SavedStateHandle(),
                fileManagerNavigator = object : FileManagerNavigator {
                    override fun launcherIntent(): Intent? {
                        launcherIntentCalls += 1
                        return documentsUi.takeIf { launcherIntentCalls == 1 }
                    }
                },
            )
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenFileManager)
            val failed = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            viewModel.accept(ManagerAction.LaunchFailed(failed))

            viewModel.accept(ManagerAction.RetryRecovery)

            assertEquals(2, launcherIntentCalls)
            assertEquals(
                UserMessageKind.FILE_MANAGER_MISSING,
                viewModel.state.value.message?.kind,
            )
            assertEquals(RecoveryAction.NONE, viewModel.state.value.message?.recoveryAction)
        }

    @Test
    fun `process recreation은 EXPORTED manual install 확인 버튼을 복원하고 자동 조정하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                installFlow.value = InstallState.AwaitingManualInstall(TOKEN)
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                manualSystemInstallResult = InstallState.Cancelled
            }
            val recreated = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
            )
            advanceUntilIdle()

            assertNull(recreated.state.value.message)
            recreated.accept(ManagerAction.ReturnedToForeground)
            advanceUntilIdle()
            assertEquals(0, repository.reconcileManualCalls)

            recreated.accept(ManagerAction.ConfirmManualInstallStatus)
            advanceUntilIdle()
            assertEquals(1, repository.reconcileManualCalls)
        }

    @Test
    fun `picker 대기 중 process recreation은 exact fallback metadata를 복원해 결과를 한 번 수락한다`() =
        runTest(dispatcher.scheduler) {
            val source = File("/retained/$TOKEN/wrapper.apk")
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installResult = InstallState.PreparingApkExport(TOKEN)
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = source,
                    expectedSha256 = "a".repeat(64),
                    extensionName = "smart/context",
                    extensionVersion = "1.0.0",
                    extensionId = EXTENSION_ID,
                )
            }
            val savedState = SavedStateHandle(
                mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN),
            )
            val sourceViewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            sourceViewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()
            val effect = assertIs<ManagerEffect.CreateInstallApkDocument>(
                sourceViewModel.effects.first(),
            )

            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            val activityRestoredEffect = ManagerEffect.CreateSystemInstallApkDocument(
                importToken = effect.importToken,
                source = effect.source,
                expectedSha256 = effect.expectedSha256,
                suggestedFileName = effect.suggestedFileName,
                ownerGeneration = effect.ownerGeneration,
                launchId = effect.launchId,
            )
            assertTrue(recreated.ownsSystemInstallApkDocument(activityRestoredEffect))
            recreated.accept(
                ManagerAction.InstallApkCreated(SECOND_TOKEN, unitTestUri(), 0, 0),
            )
            recreated.accept(
                ManagerAction.InstallApkCreated(
                    TOKEN,
                    unitTestUri(),
                    effect.ownerGeneration,
                    effect.launchId,
                ),
            )
            recreated.accept(
                ManagerAction.InstallApkCreated(
                    TOKEN,
                    unitTestUri(),
                    effect.ownerGeneration,
                    effect.launchId,
                ),
            )
            advanceUntilIdle()

            assertEquals(TOKEN, effect.importToken)
            assertEquals(source, effect.source)
            assertEquals("a".repeat(64), effect.expectedSha256)
            assertEquals(listOf(TOKEN), repository.installApkExportedTokens)
        }

    @Test
    fun `PREPARED fallback cold start는 retained metadata로 exact retry UX를 복원한다`() =
        runTest(dispatcher.scheduler) {
            val source = File("/retained/$TOKEN/wrapper.apk")
            val repository = FakeManagerRepository().apply {
                installFlow.value = InstallState.PreparingApkExport(TOKEN)
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = source,
                    expectedSha256 = "b".repeat(64),
                    extensionName = "복구/확장",
                    extensionVersion = "1.0.0",
                    extensionId = EXTENSION_ID,
                )
            }
            val recreated = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
            )
            advanceUntilIdle()

            assertEquals(RecoveryAction.RETRY, recreated.state.value.message?.recoveryAction)
            recreated.accept(ManagerAction.RetryRecovery)
            val effect = assertIs<ManagerEffect.CreateInstallApkDocument>(
                recreated.effects.first(),
            )
            assertEquals(TOKEN, effect.importToken)
            assertEquals(source, effect.source)
            assertEquals("b".repeat(64), effect.expectedSha256)
            assertEquals("복구_확장-1.0.0-aaaaaa.apk", effect.suggestedFileName)
        }

    @Test
    fun `제거 화면 복귀 뒤 실제 재조회 결과만 반영한다`() = runTest(dispatcher.scheduler) {
        val repository = FakeManagerRepository()
        val viewModel = ManagerViewModel(repository, SavedStateHandle())
        advanceUntilIdle()
        viewModel.accept(ManagerAction.Remove(PACKAGE))
        advanceUntilIdle()
        val firstEffect = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
        assertEquals(
            LaunchDestination.UNINSTALL,
            firstEffect.destination,
        )
        viewModel.accept(ManagerAction.LaunchSucceeded(firstEffect))

        repository.uninstallOutcome = UninstallOutcome.CancelledOrStillInstalled
        viewModel.accept(ManagerAction.ReturnedToForeground)
        advanceUntilIdle()
        assertEquals(UserMessageKind.UNINSTALL_CANCELLED, viewModel.state.value.message?.kind)
        assertEquals(
            "제거가 취소되었거나 확장 래퍼가 아직 설치되어 있습니다.",
            viewModel.state.value.message?.text,
        )

        viewModel.accept(ManagerAction.Remove(PACKAGE))
        val secondEffect = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
        viewModel.accept(ManagerAction.LaunchSucceeded(secondEffect))
        repository.uninstallOutcome = UninstallOutcome.Removed
        viewModel.accept(ManagerAction.ReturnedToForeground)
        advanceUntilIdle()
        assertEquals(UserMessageKind.UNINSTALLED, viewModel.state.value.message?.kind)
        assertEquals(2, repository.reconcileRequests)
    }

    @Test
    fun `제거 취소 RETRY는 동일 package 제거 effect를 다시 요청한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.Remove(PACKAGE))
            assertEquals(PACKAGE, repository.uninstallPackages.single())
            val effect = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            viewModel.accept(ManagerAction.LaunchSucceeded(effect))
            repository.uninstallOutcome = UninstallOutcome.CancelledOrStillInstalled
            viewModel.accept(ManagerAction.ReturnedToForeground)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.RetryRecovery)

            assertEquals(listOf(PACKAGE, PACKAGE), repository.uninstallPackages)
            assertEquals(
                LaunchDestination.UNINSTALL,
                assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first()).destination,
            )
            assertNull(viewModel.state.value.message)
        }

    @Test
    fun `uninstall launch 실패와 즉시 foreground 복귀는 제거 취소로 오분류하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.Remove(PACKAGE))
            val failed = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            viewModel.accept(ManagerAction.LaunchFailed(failed))
            viewModel.accept(ManagerAction.ReturnedToForeground)
            advanceUntilIdle()

            assertTrue(repository.reconcileUninstallPackages.isEmpty())
            assertEquals(UserMessageKind.LAUNCH_FAILED, viewModel.state.value.message?.kind)
            assertEquals(
                "시스템 제거 화면을 열 수 없습니다.",
                viewModel.state.value.message?.text,
            )

            viewModel.accept(ManagerAction.RetryRecovery)
            val retried = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            assertFalse(failed === retried)
            assertTrue(failed.intentFactory === retried.intentFactory)
            assertTrue(failed.ownerGeneration != retried.ownerGeneration)
            viewModel.accept(ManagerAction.ReturnedToForeground)
            advanceUntilIdle()
            assertTrue(repository.reconcileUninstallPackages.isEmpty())

            repository.uninstallOutcome = UninstallOutcome.Removed
            viewModel.accept(ManagerAction.LaunchSucceeded(retried))
            viewModel.accept(ManagerAction.ReturnedToForeground)
            advanceUntilIdle()

            assertEquals(listOf(PACKAGE), repository.reconcileUninstallPackages)
            assertEquals(UserMessageKind.UNINSTALLED, viewModel.state.value.message?.kind)
        }

    @Test
    fun `uninstall success ACK는 pending effect identity가 정확히 일치할 때만 승격한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                uninstallOutcome = UninstallOutcome.Removed
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.Remove(PACKAGE))
            val expected = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            val unrelated = ManagerEffect.LaunchIntent(
                destination = LaunchDestination.UNINSTALL,
                intentFactory = { error("unrelated") },
            )

            viewModel.accept(ManagerAction.LaunchSucceeded(unrelated))
            viewModel.accept(ManagerAction.ReturnedToForeground)
            advanceUntilIdle()
            assertTrue(repository.reconcileUninstallPackages.isEmpty())

            viewModel.accept(ManagerAction.LaunchSucceeded(expected))
            viewModel.accept(ManagerAction.ReturnedToForeground)
            advanceUntilIdle()
            assertEquals(listOf(PACKAGE), repository.reconcileUninstallPackages)
        }

    @Test
    fun `지연된 uninstall reconcile은 새 report action 뒤 메시지와 RETRY를 재생성하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                catalogFlow.tryEmit(listOf(catalogRecord()))
                suspendUninstallReconcile = true
                ignoreUninstallReconcileCancellation = true
                uninstallOutcome = UninstallOutcome.CancelledOrStillInstalled
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.Remove(PACKAGE))
            val effect = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            viewModel.accept(ManagerAction.LaunchSucceeded(effect))
            viewModel.accept(ManagerAction.ReturnedToForeground)
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf(PACKAGE), repository.reconcileUninstallPackages)

            viewModel.accept(ManagerAction.OpenReport(PACKAGE))
            repository.completeUninstallReconcile()
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
            assertEquals(1, repository.reconcileRequests)
            val effects = mutableListOf<ManagerEffect>()
            val collector = launch { viewModel.effects.collect(effects::add) }
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.RetryRecovery)
            dispatcher.scheduler.runCurrent()
            assertTrue(effects.isEmpty())
            collector.cancel()
        }

    @Test
    fun `Add-ons 불가 RETRY는 foreground reconcile이 아니라 Add-ons를 다시 연다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                navigationResult = RepositoryNavigationResult.AddOnsIntentUnavailable
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenSamsungAddOns)
            assertEquals(UserMessageKind.SAMSUNG_ADD_ONS_UNAVAILABLE, viewModel.state.value.message?.kind)

            repository.navigationResult = RepositoryNavigationResult.Launch(
                RepositoryLaunch(
                    LaunchDestination.SAMSUNG_ADD_ONS,
                    intentFactory = { error("unit test에서는 Intent를 만들지 않습니다.") },
                ),
            )
            viewModel.accept(ManagerAction.RetryRecovery)

            assertEquals(0, repository.reconcileRequests)
            assertEquals(
                LaunchDestination.SAMSUNG_ADD_ONS,
                assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first()).destination,
            )
            assertNull(viewModel.state.value.message)
        }

    @Test
    fun `unknown sources launch 실패는 writing 또는 approval 진행 시 message와 RETRY를 지운다`() =
        runTest(dispatcher.scheduler) {
            for (progressed in listOf(
                InstallState.WritingSession(40),
                InstallState.AwaitingUserApproval(41),
            )) {
                val repository = FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(
                        review = review(ContractCompatibilityStatus.COMPATIBLE),
                    )
                }
                val viewModel = ManagerViewModel(
                    repository,
                    SavedStateHandle(mapOf("review_token" to TOKEN)),
                )
                advanceUntilIdle()
                repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
                advanceUntilIdle()
                val effect = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
                viewModel.accept(ManagerAction.LaunchFailed(effect))
                assertEquals(UserMessageKind.LAUNCH_FAILED, viewModel.state.value.message?.kind)

                viewModel.accept(ManagerAction.ReturnedToForeground)
                repository.installFlow.value = progressed
                advanceUntilIdle()

                assertNull(viewModel.state.value.message)
                val effects = mutableListOf<ManagerEffect>()
                val collector = launch { viewModel.effects.collect(effects::add) }
                dispatcher.scheduler.runCurrent()
                viewModel.accept(ManagerAction.RetryRecovery)
                dispatcher.scheduler.runCurrent()
                assertTrue(effects.isEmpty())
                collector.cancel()
            }
        }

    @Test
    fun `terminal install은 settings launch recovery를 제거한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf("review_token" to TOKEN)),
            )
            advanceUntilIdle()
            repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
            advanceUntilIdle()
            val effect = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            viewModel.accept(ManagerAction.LaunchFailed(effect))

            repository.installFlow.value = InstallState.Cancelled
            advanceUntilIdle()
            assertEquals(UserMessageKind.INSTALL_CANCELLED, viewModel.state.value.message?.kind)
            val effects = mutableListOf<ManagerEffect>()
            val collector = launch { viewModel.effects.collect(effects::add) }
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.RetryRecovery)
            dispatcher.scheduler.runCurrent()
            assertTrue(effects.isEmpty())
            collector.cancel()
        }

    @Test
    fun `다른 destination launch 실패는 install 진행 중에도 유지한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf("review_token" to TOKEN)),
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenSamsungAddOns)
            val effect = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            viewModel.accept(ManagerAction.LaunchFailed(effect))

            repository.installFlow.value = InstallState.WritingSession(40)
            advanceUntilIdle()

            assertEquals(UserMessageKind.LAUNCH_FAILED, viewModel.state.value.message?.kind)
            viewModel.accept(ManagerAction.RetryRecovery)
            val retried = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            assertFalse(effect === retried)
            assertTrue(effect.intentFactory === retried.intentFactory)
        }

    @Test
    fun `구독 전 A 제거 effect는 B 제거가 시작되면 폐기되고 B만 전달된다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.Remove(PACKAGE))
            viewModel.accept(ManagerAction.Remove(SECOND_PACKAGE))

            val delivered = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            assertEquals(LaunchDestination.UNINSTALL, delivered.destination)
            assertEquals(SECOND_PACKAGE, repository.uninstallPackages.last())
            assertTrue(delivered.intentFactory === repository.uninstallLaunches.last().intentFactory)
            assertTrue(delivered.ownerGeneration != null)

            val reportRepository = FakeManagerRepository().apply {
                catalogFlow.tryEmit(listOf(catalogRecord()))
            }
            val reportViewModel = ManagerViewModel(reportRepository, SavedStateHandle())
            advanceUntilIdle()
            reportViewModel.accept(ManagerAction.Remove(PACKAGE))
            reportViewModel.accept(ManagerAction.OpenReport(PACKAGE))
            val reportEffects = mutableListOf<ManagerEffect>()
            val reportCollector = launch { reportViewModel.effects.collect(reportEffects::add) }
            dispatcher.scheduler.runCurrent()

            assertEquals(ManagerRoute.Report(PACKAGE), reportViewModel.state.value.route)
            assertTrue(reportEffects.isEmpty())
            reportCollector.cancel()
        }

    @Test
    fun `구독 전 Add-ons와 settings effect는 새 작업 뒤 전달되지 않는다`() =
        runTest(dispatcher.scheduler) {
            val addOnsRepository = FakeManagerRepository()
            val addOnsViewModel = ManagerViewModel(addOnsRepository, SavedStateHandle())
            advanceUntilIdle()
            addOnsViewModel.accept(ManagerAction.OpenSamsungAddOns)
            addOnsViewModel.accept(ManagerAction.ReturnHome)
            val addOnsEffects = mutableListOf<ManagerEffect>()
            val addOnsCollector = launch { addOnsViewModel.effects.collect(addOnsEffects::add) }
            dispatcher.scheduler.runCurrent()
            assertTrue(addOnsEffects.isEmpty())
            addOnsCollector.cancel()

            val settingsRepository = FakeManagerRepository()
            val settingsViewModel = ManagerViewModel(settingsRepository, SavedStateHandle())
            advanceUntilIdle()
            settingsRepository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
            advanceUntilIdle()
            settingsViewModel.accept(ManagerAction.ReturnHome)
            val settingsEffects = mutableListOf<ManagerEffect>()
            val settingsCollector = launch { settingsViewModel.effects.collect(settingsEffects::add) }
            dispatcher.scheduler.runCurrent()
            assertTrue(settingsEffects.isEmpty())
            settingsCollector.cancel()
        }

    @Test
    fun `동일 Add-ons 버튼의 새 시도는 이전 launch 실패 카드와 소유권을 함께 지운다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenSamsungAddOns)
            val failed = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            viewModel.accept(ManagerAction.LaunchFailed(failed))
            assertEquals(UserMessageKind.LAUNCH_FAILED, viewModel.state.value.message?.kind)

            viewModel.accept(ManagerAction.OpenSamsungAddOns)
            val current = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())

            assertNull(viewModel.state.value.message)
            assertFalse(failed === current)
            assertTrue(failed.ownerGeneration != current.ownerGeneration)
        }

    @Test
    fun `terminal install은 다른 destination 복구도 폐기하고 terminal 메시지만 소유한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf("review_token" to TOKEN)),
            )
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenSamsungAddOns)
            val failed = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            viewModel.accept(ManagerAction.LaunchFailed(failed))
            repository.installFlow.value = InstallState.Cancelled
            advanceUntilIdle()

            assertEquals(UserMessageKind.INSTALL_CANCELLED, viewModel.state.value.message?.kind)
            val effects = mutableListOf<ManagerEffect>()
            val collector = launch { viewModel.effects.collect(effects::add) }
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.RetryRecovery)
            dispatcher.scheduler.runCurrent()
            assertTrue(effects.isEmpty())
            collector.cancel()
        }

    @Test
    fun `새 작업 뒤 늦은 uninstall reconcile은 취소 카드와 RETRY를 만들지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                suspendUninstallReconcile = true
                ignoreUninstallReconcileCancellation = true
                uninstallOutcome = UninstallOutcome.CancelledOrStillInstalled
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.Remove(PACKAGE))
            val effect = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            viewModel.accept(ManagerAction.LaunchSucceeded(effect))
            viewModel.accept(ManagerAction.ReturnedToForeground)
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.ReturnHome)
            repository.completeUninstallReconcile()
            advanceUntilIdle()

            assertNull(viewModel.state.value.message)
            assertEquals(1, repository.reconcileRequests)
            val effects = mutableListOf<ManagerEffect>()
            val collector = launch { viewModel.effects.collect(effects::add) }
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.RetryRecovery)
            dispatcher.scheduler.runCurrent()
            assertTrue(effects.isEmpty())
            collector.cancel()
        }

    @Test
    fun `새 작업 뒤 stale launch 성공과 실패 ACK는 상태를 만들지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.Remove(PACKAGE))
            val stale = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            viewModel.accept(ManagerAction.ReturnHome)
            viewModel.accept(ManagerAction.LaunchSucceeded(stale))
            viewModel.accept(ManagerAction.LaunchFailed(stale))
            viewModel.accept(ManagerAction.ReturnedToForeground)
            advanceUntilIdle()

            assertNull(viewModel.state.value.message)
            assertTrue(repository.reconcileUninstallPackages.isEmpty())
        }

    @Test
    fun `message dismiss와 새 user action은 stale RETRY 소유권을 지운다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                navigationResult = RepositoryNavigationResult.AddOnsIntentUnavailable
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            val effects = mutableListOf<ManagerEffect>()
            val collector = launch { viewModel.effects.collect(effects::add) }
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.OpenSamsungAddOns)
            viewModel.accept(ManagerAction.DismissMessage)
            repository.navigationResult = RepositoryNavigationResult.Launch(
                RepositoryLaunch(
                    LaunchDestination.SAMSUNG_ADD_ONS,
                    intentFactory = { error("unit test에서는 Intent를 만들지 않습니다.") },
                ),
            )
            viewModel.accept(ManagerAction.RetryRecovery)
            dispatcher.scheduler.runCurrent()
            assertTrue(effects.isEmpty())

            repository.navigationResult = RepositoryNavigationResult.AddOnsIntentUnavailable
            viewModel.accept(ManagerAction.OpenSamsungAddOns)
            viewModel.accept(ManagerAction.ReturnHome)
            repository.navigationResult = RepositoryNavigationResult.Launch(
                RepositoryLaunch(
                    LaunchDestination.SAMSUNG_ADD_ONS,
                    intentFactory = { error("unit test에서는 Intent를 만들지 않습니다.") },
                ),
            )
            viewModel.accept(ManagerAction.RetryRecovery)
            dispatcher.scheduler.runCurrent()
            assertTrue(effects.isEmpty())
            collector.cancel()
        }

    @Test
    fun `SavedStateHandle token과 session ID는 repository 검증 뒤에만 복원한다`() =
        runTest(dispatcher.scheduler) {
            val saved = SavedStateHandle(
                mapOf(
                    ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.INSTALL_SESSION_ID_KEY to 42,
                ),
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(review(ContractCompatibilityStatus.PARTIAL), 42)
            }

            val viewModel = ManagerViewModel(repository, saved)
            advanceUntilIdle()

            assertEquals(TOKEN to 42, repository.restoreArguments)
            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
            assertEquals(42, saved.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))

            val invalidSaved = SavedStateHandle(
                mapOf(
                    ManagerViewModel.REVIEW_TOKEN_KEY to "missing",
                    ManagerViewModel.INSTALL_SESSION_ID_KEY to 99,
                ),
            )
            val invalid = ManagerViewModel(FakeManagerRepository(), invalidSaved)
            advanceUntilIdle()
            assertEquals(ManagerRoute.Home, invalid.state.value.route)
            assertNull(invalidSaved.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
            assertNull(invalidSaved.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))
        }

    @Test
    fun `review가 아닌 import 진행 중 CancelReview는 소유 route를 바꾸지 않는다`() = runTest(dispatcher.scheduler) {
        val repository = FakeManagerRepository().apply {
            importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            suspendImport = true
            ignoreImportCancellation = true
        }
        val viewModel = ManagerViewModel(repository, SavedStateHandle())
        advanceUntilIdle()

        viewModel.startImport(repository::nextImport)
        dispatcher.scheduler.runCurrent()
        viewModel.accept(ManagerAction.CancelReview)
        repository.completeImport()
        advanceUntilIdle()

        assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
        assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState)
        assertTrue(repository.discardedTokens.isEmpty())
    }

    @Test
    fun `가져오기 오류군은 복구 action이 있는 서로 다른 한국어 메시지로 매핑한다`() =
        runTest(dispatcher.scheduler) {
            val expected = listOf(
                ImportFailureCode.SOURCE_TOO_LARGE to UserMessageKind.FILE_READ_OR_SIZE,
                ImportFailureCode.INVALID_BUNDLE to UserMessageKind.ZIP_OR_SCHEMA,
                ImportFailureCode.REPORT_SCHEMA_UNSUPPORTED to UserMessageKind.ZIP_OR_SCHEMA,
                ImportFailureCode.CRX_ID_MISMATCH to UserMessageKind.IDENTITY_VERIFICATION,
            )
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            for ((code, kind) in expected) {
                repository.importResult = ManagerImportResult.Failure(ImportFailure(code))
                viewModel.startImport(repository::nextImport)
                advanceUntilIdle()
                val message = requireNotNull(viewModel.state.value.message)
                assertEquals(kind, message.kind)
                assertTrue(message.text.any { it.code in 0xAC00..0xD7A3 })
                assertEquals(RecoveryAction.SELECT_ANOTHER_FILE, message.recoveryAction)
            }
        }

    @Test
    fun `새 import는 기존 review를 버리고 취소를 무시한 stale 성공 token도 버린다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            repository.importResult = ManagerImportResult.Success(
                review(ContractCompatibilityStatus.PARTIAL).copy(token = SECOND_TOKEN),
            )
            repository.suspendImport = true
            repository.ignoreImportCancellation = true
            viewModel.startImport(repository::nextImport)
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf(TOKEN), repository.discardedTokens)
            viewModel.accept(ManagerAction.CancelReview)
            repository.completeImport()
            advanceUntilIdle()
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState).review.token,
            )
        }

    @Test
    fun `install 중 새 import를 시작하면 superseded install job을 취소한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.suspendInstall = true
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, repository.installRequests)

            repository.importResult = ManagerImportResult.Success(
                review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
            )
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            repository.completeInstall()
            advanceUntilIdle()

            assertEquals(0, repository.completedInstallRequests)
            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState).review.token,
            )
        }

    @Test
    fun `install double tap 직후 import하면 어떤 install job도 시작하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.InstallReviewedBundle)
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            repository.importResult = ManagerImportResult.Success(
                review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
            )
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            assertEquals(0, repository.installRequests)
            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState).review.token,
            )
        }

    @Test
    fun `연속 import는 취소된 cleanup의 기존 review token 소유권을 이어받는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.suspendCancelInstall = true
            repository.importResult = ManagerImportResult.Success(
                review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
            )
            viewModel.startImport(repository::nextImport)
            dispatcher.scheduler.runCurrent()
            viewModel.startImport(repository::nextImport)
            dispatcher.scheduler.runCurrent()

            assertTrue(repository.discardedTokens.isEmpty())
            repository.completeCancelInstall()
            advanceUntilIdle()

            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState).review.token,
            )
        }

    @Test
    fun `review 취소는 coordinator cleanup 완료 뒤 token을 폐기한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.suspendCancelInstall = true
            viewModel.accept(ManagerAction.CancelReview)
            dispatcher.scheduler.runCurrent()
            assertTrue(repository.discardedTokens.isEmpty())

            repository.completeCancelInstall()
            advanceUntilIdle()
            assertEquals(listOf(TOKEN), repository.discardedTokens)
        }

    @Test
    fun `새 import를 위한 install 취소 상태는 새 review 상태를 덮지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
            repository.emitCancelledOnCancelInstall = true
            repository.importResult = ManagerImportResult.Success(
                review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
            )
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState).review.token,
            )
            assertNull(viewModel.state.value.message)
        }

    @Test
    fun `새 six import는 소유 화면이 사라진 manual install을 먼저 정리한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            repository.installFlow.value = InstallState.AwaitingManualInstall(TOKEN)
            advanceUntilIdle()

            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            assertEquals(
                listOf<Pair<String, Int?>>(TOKEN to null),
                repository.cancelInstallArguments,
            )
            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState)
                    .review
                    .token,
            )
        }

    @Test
    fun `느린 초기 restore 중 새 import는 무시하고 restored review를 유지한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                suspendInitialize = true
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            dispatcher.scheduler.runCurrent()

            var importCalled = false
            viewModel.startImport {
                importCalled = true
                repository.nextImport()
            }
            advanceUntilIdle()
            assertFalse(importCalled)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)

            repository.completeInitialize()
            advanceUntilIdle()

            assertEquals(
                TOKEN,
                assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState).review.token,
            )
            assertTrue(repository.discardedTokens.isEmpty())
            assertEquals(
                StartupRecoveryUiState.READY,
                viewModel.state.value.startupRecoveryState,
            )
        }

    @Test
    fun `replacement cancel이 소비한 old terminal은 새 review 상태를 덮지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            repository.terminalStateOnCancelInstall = InstallState.Installed(PACKAGE)
            repository.importResult = ManagerImportResult.Success(
                review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
            )
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState).review.token,
            )
            assertEquals(ManagerRoute.Review(SECOND_TOKEN), viewModel.state.value.route)
        }

    @Test
    fun `coordinator cleanup 실패 시 pending review token을 폐기하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.cancelInstallResult = false
            viewModel.accept(ManagerAction.CancelReview)
            advanceUntilIdle()

            assertTrue(repository.discardedTokens.isEmpty())
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(savedState.get<String>("review_token"))

            repository.restoreResult = ManagerRestoreResult()
            var importCalls = 0
            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            recreated.startImport {
                importCalls += 1
                ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
                )
            }
            advanceUntilIdle()

            assertEquals(1, importCalls)
            assertEquals(ManagerRoute.Review(SECOND_TOKEN), recreated.state.value.route)
            assertEquals(SECOND_TOKEN, savedState.get<String>("review_token"))
        }

    @Test
    fun `review 파일 삭제 실패 token은 다음 cleanup 성공까지 소유한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.discardReviewResult = false
            viewModel.accept(ManagerAction.CancelReview)
            advanceUntilIdle()
            assertEquals(listOf(TOKEN), repository.discardAttempts)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(savedState.get<String>("review_token"))

            repository.restoreResult = ManagerRestoreResult()
            var blockedImportCalls = 0
            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            recreated.startImport {
                blockedImportCalls += 1
                ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
                )
            }
            advanceUntilIdle()
            assertEquals(1, blockedImportCalls)
            assertEquals(ManagerRoute.Review(SECOND_TOKEN), recreated.state.value.route)
            assertEquals(SECOND_TOKEN, savedState.get<String>("review_token"))

            repository.discardReviewResult = true
            repository.importResult = ManagerImportResult.Success(
                review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
            )
            recreated.startImport(repository::nextImport)
            advanceUntilIdle()

            assertEquals(listOf(TOKEN, SECOND_TOKEN), repository.discardAttempts)
            assertEquals(listOf(SECOND_TOKEN), repository.discardedTokens)
            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(recreated.state.value.importState).review.token,
            )
        }

    @Test
    fun `cleanup 계열 install failure는 review route와 saved token을 유지한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val savedState = SavedStateHandle(mapOf("review_token" to TOKEN))
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            repository.installFlow.value = InstallState.Failed(
                code = "CLEANUP_FAILED",
                message = "cleanup retry required",
            )
            advanceUntilIdle()

            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
            assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState)
            assertEquals(TOKEN, savedState.get<String>("review_token"))
        }

    @Test
    fun `느린 초기 restore 중 import와 cancel은 무시하고 restored review를 유지한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                suspendInitialize = true
                ignoreInitializeCancellation = true
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE).copy(
                        token = SECOND_TOKEN,
                    ),
                )
                discardReviewResults[TOKEN] = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            dispatcher.scheduler.runCurrent()

            var importCalled = false
            viewModel.startImport {
                importCalled = true
                ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            dispatcher.scheduler.runCurrent()
            assertFalse(importCalled)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)

            viewModel.accept(ManagerAction.CancelReview)
            dispatcher.scheduler.runCurrent()
            assertTrue(repository.discardAttempts.isEmpty())
            assertNull(savedState.get<String>("review_token"))

            repository.completeInitialize()
            advanceUntilIdle()

            assertTrue(repository.discardedTokens.isEmpty())
            assertEquals(ManagerRoute.Review(SECOND_TOKEN), viewModel.state.value.route)
            assertEquals(SECOND_TOKEN, savedState.get<String>("review_token"))
            assertEquals(
                StartupRecoveryUiState.READY,
                viewModel.state.value.startupRecoveryState,
            )
        }

    @Test
    fun `replacement cancel 대기 중 도착한 old terminal은 UI 적용을 보류한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            repository.suspendCancelInstall = true
            repository.importResult = ManagerImportResult.Success(
                review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
            )
            viewModel.startImport(repository::nextImport)
            dispatcher.scheduler.runCurrent()
            repository.installFlow.value = InstallState.Installed(PACKAGE)
            dispatcher.scheduler.runCurrent()

            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)

            repository.completeCancelInstall()
            advanceUntilIdle()
            assertEquals(ManagerRoute.Review(SECOND_TOKEN), viewModel.state.value.route)
        }

    @Test
    fun `observer보다 먼저 시작한 replacement는 이미 도착한 old terminal을 무시한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.installFlow.value = InstallState.Installed(PACKAGE)
            repository.importResult = ManagerImportResult.Success(
                review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
            )
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Review(SECOND_TOKEN), viewModel.state.value.route)
        }

    @Test
    fun `replacement cancel 예외는 marker를 정리해 이후 terminal을 적용한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            repository.cancelInstallException = IllegalStateException("cancel failed")
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            repository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
        }

    @Test
    fun `cancel 대기 중 deferred terminal은 cancel 예외 뒤 import failure에 덮이지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            repository.suspendCancelInstall = true
            repository.cancelInstallException = IllegalStateException("cancel failed")
            viewModel.startImport(repository::nextImport)
            dispatcher.scheduler.runCurrent()
            repository.installFlow.value = InstallState.Installed(PACKAGE)
            dispatcher.scheduler.runCurrent()
            repository.completeCancelInstall()
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
            assertEquals(
                SamsungInternetSupportStatus.TRUSTED_INSTALL_SOURCE,
                viewModel.state.value.reportInstallSupportStatus,
            )
        }

    @Test
    fun `삼성 인터넷과 Add-ons 불가는 서로 다른 한국어 오류로 매핑한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            repository.navigationResult = RepositoryNavigationResult.SamsungBrowserMissing
            viewModel.accept(ManagerAction.OpenSamsungAddOns)
            assertEquals(UserMessageKind.SAMSUNG_BROWSER_MISSING, viewModel.state.value.message?.kind)

            repository.navigationResult = RepositoryNavigationResult.AddOnsIntentUnavailable
            viewModel.accept(ManagerAction.OpenSamsungAddOns)
            assertEquals(UserMessageKind.SAMSUNG_ADD_ONS_UNAVAILABLE, viewModel.state.value.message?.kind)
            assertTrue(viewModel.state.value.message?.text.orEmpty().any { it.code in 0xAC00..0xD7A3 })
        }

    @Test
    fun `새 신원 선택 안내는 identity mismatch에만 사용한다`() {
        val identity = conversionFailureMessage(ConversionFailureCode.IDENTITY_MISMATCH)
        val packageFailure = conversionFailureMessage(ConversionFailureCode.PACKAGE)

        assertTrue(identity.text.contains("새 변환 신원"))
        assertEquals(RecoveryAction.NONE, identity.recoveryAction)
        assertFalse(packageFailure.text.contains("새 변환 신원"))
        assertTrue(packageFailure.text.contains("같은 신원"))
        assertEquals(RecoveryAction.RETRY, packageFailure.recoveryAction)
    }

    @Test
    fun `Chrome URL 분석 성공은 token만 저장하고 분석 route로 이동한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis()
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://chrome.example/private"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()

            assertIs<OnDeviceInputRequest.ChromeStore>(repository.conversionRequests.single())
            assertEquals(ManagerRoute.ConversionAnalysis(TOKEN), viewModel.state.value.route)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
            assertEquals(
                "analysis",
                savedState.get<String>(ManagerViewModel.CONVERSION_ROUTE_KEY),
            )
            assertNull(savedState.get<String>("conversion_url"))
        }

    @Test
    fun `recreation은 SavedState payload 대신 repository restore 결과를 재검증한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.Analysis(conversionAnalysis())
            }
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                    ManagerViewModel.CONVERSION_ROUTE_KEY to "analysis",
                    "conversion_url" to "https://leak.invalid/private",
                ),
            )
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(1, repository.restoreConversionRequests)
            assertEquals(ManagerRoute.ConversionAnalysis(TOKEN), viewModel.state.value.route)
            assertEquals("", viewModel.state.value.conversionUrl)
            assertNull(savedState.get<String>("conversion_url"))
        }

    @Test
    fun `build 중 중복 클릭은 coordinator를 한 번만 호출한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis()
                conversionBuildResult =
                    ConversionBuildResult.Failure(ConversionFailureCode.PACKAGE)
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.BuildConversion)
            viewModel.accept(ManagerAction.BuildConversion)
            advanceUntilIdle()

            assertEquals(1, repository.conversionBuildRequests.size)
        }

    @Test
    fun `강제 생성 확인은 build 클릭 즉시 소비되고 실패 뒤 재사용되지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = forcedConversionAnalysis()
                conversionBuildResult =
                    ConversionBuildResult.Failure(ConversionFailureCode.PACKAGE)
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.ConfirmConversionForce)
            assertTrue(
                assertIs<ConversionUiState.AnalysisReady>(
                    viewModel.state.value.conversionState,
                ).forceCompatibilityConfirmed,
            )
            viewModel.accept(ManagerAction.BuildConversion)
            assertFalse(
                assertIs<ConversionUiState.Building>(
                    viewModel.state.value.conversionState,
                ).previous.forceCompatibilityConfirmed,
            )
            advanceUntilIdle()

            assertEquals(
                repository.conversionForceApproval,
                repository.conversionBuildRequests.single().approval,
            )
            assertEquals(1, repository.conversionForceRequests.size)
            assertFalse(
                assertIs<ConversionUiState.AnalysisReady>(
                    viewModel.state.value.conversionState,
                ).forceCompatibilityConfirmed,
            )
        }

    @Test
    fun `강제 생성 확인은 process recreation에서 복원되지 않는다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                    ManagerViewModel.CONVERSION_ROUTE_KEY to "analysis",
                ),
            )
            val firstRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.Analysis(forcedConversionAnalysis())
            }
            val first = ManagerViewModel(firstRepository, savedState)
            advanceUntilIdle()
            first.accept(ManagerAction.ConfirmConversionForce)
            assertTrue(
                assertIs<ConversionUiState.AnalysisReady>(
                    first.state.value.conversionState,
                ).forceCompatibilityConfirmed,
            )

            val recreatedRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.Analysis(forcedConversionAnalysis())
            }
            val recreated = ManagerViewModel(recreatedRepository, savedState)
            advanceUntilIdle()

            assertFalse(
                assertIs<ConversionUiState.AnalysisReady>(
                    recreated.state.value.conversionState,
                ).forceCompatibilityConfirmed,
            )
            assertTrue(savedState.keys().none { "force" in it.lowercase() })
        }

    @Test
    fun `강제 생성 확인 뒤 adapter와 identity 선택을 바꾸면 proof가 즉시 소멸한다`() =
        runTest(dispatcher.scheduler) {
            val target = existingTarget()
            val cases = listOf(
                forcedConversionAnalysis().copy(requiredApprovals = setOf("adapter")) to
                    ManagerAction.ToggleConversionAdapter("adapter"),
                forcedConversionAnalysis().copy(optionalApprovals = setOf("optional")) to
                    ManagerAction.ChooseOptionalConversionAdapter("optional", true),
                forcedConversionAnalysis().copy(
                    identityRequirement = IdentityRequirement.UserSelectionRequired(
                        listOf(target),
                    ),
                ) to ManagerAction.SelectNewConversionIdentity,
                forcedConversionAnalysis().copy(
                    identityRequirement = IdentityRequirement.UserSelectionRequired(
                        listOf(target),
                    ),
                ) to ManagerAction.SelectExistingConversionIdentity(target.identitySha256),
            )

            cases.forEach { (analysis, mutation) ->
                val repository = FakeManagerRepository().apply {
                    conversionBeginResult = analysis
                }
                val viewModel = ManagerViewModel(repository, SavedStateHandle())
                advanceUntilIdle()
                viewModel.accept(ManagerAction.OpenConversionInput)
                viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
                viewModel.accept(ManagerAction.AnalyzeConversionUrl)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.ConfirmConversionForce)
                assertTrue(
                    assertIs<ConversionUiState.AnalysisReady>(
                        viewModel.state.value.conversionState,
                    ).forceCompatibilityConfirmed,
                )

                viewModel.accept(mutation)

                assertFalse(
                    assertIs<ConversionUiState.AnalysisReady>(
                        viewModel.state.value.conversionState,
                    ).forceCompatibilityConfirmed,
                )
            }
        }

    @Test
    fun `강제 생성 확인은 새 import 시작 즉시 소멸한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = forcedConversionAnalysis()
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.ConfirmConversionForce)

            viewModel.startImport {
                ManagerImportResult.Failure(
                    ImportFailure(ImportFailureCode.SOURCE_UNREADABLE),
                )
            }

            assertFalse(
                assertIs<ConversionUiState.AnalysisReady>(
                    viewModel.state.value.conversionState,
                ).forceCompatibilityConfirmed,
            )
            advanceUntilIdle()
        }

    @Test
    fun `강제 설치 확인은 process recreation에서 복원되지 않는다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle()
            val review = forcedReview()
            val firstRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(review = review)
            }
            val first = ManagerViewModel(firstRepository, savedState)
            advanceUntilIdle()
            first.accept(ManagerAction.ConfirmForcedInstall)
            assertTrue(
                assertIs<ImportUiState.ReviewReady>(
                    first.state.value.importState,
                ).forceCompatibilityConfirmed,
            )

            val recreatedRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(review = review)
            }
            val recreated = ManagerViewModel(recreatedRepository, savedState)
            advanceUntilIdle()

            assertFalse(
                assertIs<ImportUiState.ReviewReady>(
                    recreated.state.value.importState,
                ).forceCompatibilityConfirmed,
            )
        }

    @Test
    fun `generated forced 결과는 실제 Review action에서 새 확인 후 한 번만 설치한다`() =
        runTest(dispatcher.scheduler) {
            val forcedReview = forcedReview()
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = forcedConversionAnalysis()
                conversionBuildResult = ConversionBuildResult.BundleReady(
                    forcedReview,
                    generatedBundle(),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.ConfirmConversionForce)
            viewModel.accept(ManagerAction.BuildConversion)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            viewModel.accept(ManagerAction.PrepareReviewedInstall)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Review(forcedReview.token), viewModel.state.value.route)
            assertEquals(0, repository.installRequests)
            val ready = assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState)
            assertFalse(ready.forceCompatibilityConfirmed)
            assertFalse(ready.installEnabled)

            viewModel.accept(ManagerAction.ConfirmForcedInstall)
            viewModel.accept(ManagerAction.PrepareReviewedInstall)
            advanceUntilIdle()
            assertEquals(1, repository.installRequests)
            assertTrue(repository.lastForceCompatibilityConfirmed)
            assertFalse(
                assertIs<ImportUiState.ReviewReady>(
                    viewModel.state.value.importState,
                ).forceCompatibilityConfirmed,
            )
            viewModel.accept(ManagerAction.PrepareReviewedInstall)
            advanceUntilIdle()
            assertEquals(1, repository.installRequests)
        }

    @Test
    fun `imported forced review 취소 직후 stale 확인과 설치는 side effect가 없다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(forcedReview())
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.ConfirmForcedInstall)

            viewModel.accept(ManagerAction.CancelReview)
            viewModel.accept(ManagerAction.ReturnHome)
            viewModel.accept(ManagerAction.ConfirmForcedInstall)
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()

            assertEquals(0, repository.installRequests)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals(ImportUiState.Idle, viewModel.state.value.importState)
            assertNull(savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
            assertNull(savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))
        }

    @Test
    fun `review 취소는 이전 manual owner와 현재 review 폐기 owner를 분리한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(forcedReview())
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            repository.installFlow.value = InstallState.AwaitingManualInstall(SECOND_TOKEN)

            viewModel.accept(ManagerAction.CancelReview)
            advanceUntilIdle()

            assertEquals(
                listOf<Pair<String, Int?>>(SECOND_TOKEN to null),
                repository.cancelInstallArguments,
            )
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        }

    @Test
    fun `generated forced review 재생성 뒤 다시 진입해 새 확인으로 설치한다`() =
        runTest(dispatcher.scheduler) {
            val forcedReview = forcedReview()
            val generated = generatedBundle()
            val savedState = SavedStateHandle()
            val firstRepository = FakeManagerRepository().apply {
                conversionBeginResult = forcedConversionAnalysis()
                conversionBuildResult = ConversionBuildResult.BundleReady(forcedReview, generated)
            }
            val first = ManagerViewModel(firstRepository, savedState)
            advanceUntilIdle()
            first.accept(ManagerAction.OpenConversionInput)
            first.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
            first.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()
            first.accept(ManagerAction.ConfirmConversionForce)
            first.accept(ManagerAction.BuildConversion)
            advanceUntilIdle()
            first.accept(ManagerAction.PrepareGeneratedInstall)
            assertEquals(ManagerRoute.Review(forcedReview.token), first.state.value.route)

            val restoredRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(review = forcedReview)
                restoredConversion = RestoredConversion.BundleReady(forcedReview, generated)
            }
            val restored = ManagerViewModel(restoredRepository, savedState)
            advanceUntilIdle()
            val result = assertIs<ConversionUiState.ResultReady>(
                restored.state.value.conversionState,
            )
            assertFalse(result.installOwnerActive)
            assertEquals(ManagerRoute.ConversionResult(generated.token), restored.state.value.route)

            restored.accept(ManagerAction.PrepareGeneratedInstall)
            val ready = assertIs<ImportUiState.ReviewReady>(restored.state.value.importState)
            assertFalse(ready.forceCompatibilityConfirmed)
            assertEquals(ManagerRoute.Review(forcedReview.token), restored.state.value.route)
            restored.accept(ManagerAction.ConfirmForcedInstall)
            restored.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()
            assertEquals(1, restoredRepository.installRequests)
        }

    @Test
    fun `generated와 imported install owner는 saved G 불일치에서 모두 거부한다`() =
        runTest(dispatcher.scheduler) {
            listOf<String?>(null, SECOND_TOKEN).forEach { staleSavedOwner ->
                val forcedReview = forcedReview()
                val repository = FakeManagerRepository().apply {
                    conversionBeginResult = forcedConversionAnalysis()
                    conversionBuildResult = ConversionBuildResult.BundleReady(
                        forcedReview,
                        generatedBundle(),
                    )
                }
                val savedState = SavedStateHandle()
                val viewModel = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.OpenConversionInput)
                viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
                viewModel.accept(ManagerAction.AnalyzeConversionUrl)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.ConfirmConversionForce)
                viewModel.accept(ManagerAction.BuildConversion)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.PrepareGeneratedInstall)
                savedState[ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY] = staleSavedOwner

                viewModel.accept(ManagerAction.ConfirmForcedInstall)
                viewModel.accept(ManagerAction.InstallReviewedBundle)
                viewModel.accept(
                    ManagerAction.ContinueInstallAfterNotificationPermission(
                        forcedReview.token,
                        generatedBundle().token,
                    ),
                )
                advanceUntilIdle()

                assertFalse(
                    assertIs<ImportUiState.ReviewReady>(
                        viewModel.state.value.importState,
                    ).forceCompatibilityConfirmed,
                )
                assertEquals(0, repository.installRequests)
            }

            val importedRepository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val importedSavedState = SavedStateHandle()
            val imported = ManagerViewModel(importedRepository, importedSavedState)
            advanceUntilIdle()
            imported.startImport(importedRepository::nextImport)
            advanceUntilIdle()
            importedSavedState[ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY] = SECOND_TOKEN
            imported.accept(ManagerAction.InstallReviewedBundle)
            imported.accept(
                ManagerAction.ContinueInstallAfterNotificationPermission(TOKEN, null),
            )
            advanceUntilIdle()
            assertEquals(0, importedRepository.installRequests)
        }

    @Test
    fun `install APK export suspend 중 route 이탈은 effect와 callback을 폐기한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle()
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installResult = InstallState.PreparingApkExport(TOKEN)
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = File("/retained/$TOKEN/wrapper.apk"),
                    expectedSha256 = "a".repeat(64),
                    extensionName = "확장",
                    extensionVersion = "1.0",
                    extensionId = EXTENSION_ID,
                )
                suspendInstallApkExport = true
            }
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            testScheduler.runCurrent()
            assertEquals(1, repository.installApkExportRequests)

            viewModel.accept(ManagerAction.ReturnHome)
            repository.completeInstallApkExport()
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallApkCreated(TOKEN, unitTestUri(), 0, 0))
            advanceUntilIdle()

            assertNull(savedState.get<String>("pending_system_apk_token"))
            assertTrue(repository.installApkExportedTokens.isEmpty())
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        }

    @Test
    fun `install APK export suspend 중 route 이탈은 늦은 terminal도 적용하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installResult = InstallState.Failed("PACKAGE_WRITE", "late")
                suspendInstallApkExport = true
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            testScheduler.runCurrent()

            viewModel.accept(ManagerAction.ReturnHome)
            repository.completeInstallApkExport()
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
        }

    @Test
    fun `queued APK effect는 saved generated owner drift 뒤 전달되지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installResult = InstallState.PreparingApkExport(TOKEN)
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = File("/retained/$TOKEN/wrapper.apk"),
                    expectedSha256 = "a".repeat(64),
                    extensionName = "확장",
                    extensionVersion = "1.0",
                    extensionId = EXTENSION_ID,
                )
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()

            savedState[ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY] = SECOND_TOKEN
            val effects = mutableListOf<ManagerEffect>()
            backgroundScope.launch { viewModel.effects.collect(effects::add) }
            testScheduler.runCurrent()

            assertTrue(effects.isEmpty())
        }

    @Test
    fun `startup APK export는 같은 generated review owner ABA 뒤 복원되지 않는다`() =
        runTest(dispatcher.scheduler) {
            val review = forcedReview()
            val generated = generatedBundle()
            val failed = InstallState.Failed("PACKAGE_WRITE", "late")
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.REVIEW_TOKEN_KEY to review.token,
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY to generated.token,
                    ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY to generated.token,
                    ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY to review.token,
                    ManagerViewModel.CONVERSION_TOKEN_KEY to generated.token,
                    ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
                ),
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(review = review)
                restoredConversion = RestoredConversion.BundleReady(review, generated)
                installFlow.value = InstallState.PreparingApkExport(review.token)
                installApkExport = InstallApkExport(
                    importToken = review.token,
                    source = File("/retained/${review.token}/wrapper.apk"),
                    expectedSha256 = "a".repeat(64),
                    extensionName = "확장",
                    extensionVersion = "1.0",
                    extensionId = EXTENSION_ID,
                )
                suspendInstallApkExport = true
                terminalOwnerProofConfigured = true
                terminalOwnerImportToken = review.token
                terminalOwnerPackageName = review.packageName
                terminalOwnerState = failed
            }
            val viewModel = ManagerViewModel(repository, savedState)
            testScheduler.runCurrent()
            assertEquals(1, repository.installApkExportRequests)

            viewModel.accept(ManagerAction.ReturnHome)
            repository.installFlow.value = failed
            advanceUntilIdle()
            assertEquals(ManagerRoute.ConversionResult(generated.token), viewModel.state.value.route)
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            assertEquals(ManagerRoute.Review(review.token), viewModel.state.value.route)
            repository.completeInstallApkExport()
            advanceUntilIdle()

            assertNull(savedState.get<String>("pending_system_apk_token"))
            assertFalse(
                assertIs<ImportUiState.ReviewReady>(
                    viewModel.state.value.importState,
                ).forceCompatibilityConfirmed,
            )
        }

    @Test
    fun `owner 없는 APK cleanup 실패 action은 receipt 없이 메시지를 만들지 않는다`() =
        runTest(dispatcher.scheduler) {
            val viewModel = ManagerViewModel(FakeManagerRepository(), SavedStateHandle())
            advanceUntilIdle()

            val failure = ManagerAction.InstallApkCleanupFailed(
                ownerGeneration = 7,
                launchId = 11,
            )
            viewModel.accept(failure)

            assertNull(viewModel.state.value.message)
            viewModel.accept(failure.copy(launchId = 12))
            assertNull(viewModel.state.value.message)
        }

    @Test
    fun `ReturnHome은 production APK copy owner flow를 즉시 폐기한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installResult = InstallState.PreparingApkExport(TOKEN)
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = File("/retained/$TOKEN/wrapper.apk"),
                    expectedSha256 = "a".repeat(64),
                    extensionName = "확장",
                    extensionVersion = "1.0",
                    extensionId = EXTENSION_ID,
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()
            assertNotNull(viewModel.installApkDocumentOwner.value)

            viewModel.accept(ManagerAction.ReturnHome)

            assertNull(viewModel.installApkDocumentOwner.value)
        }

    @Test
    fun `APK URI callback은 ViewModel transaction journal 뒤 copy와 mark를 완료한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installResult = InstallState.PreparingApkExport(TOKEN)
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = File("/retained/$TOKEN/wrapper.apk"),
                    expectedSha256 = "a".repeat(64),
                    extensionName = "확장",
                    extensionVersion = "1.0",
                    extensionId = EXTENSION_ID,
                )
            }
            val savedState = SavedStateHandle(
                mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN),
            )
            var copied = 0
            var deleted = 0
            val viewModel = ManagerViewModel(
                repository,
                savedState,
                saveInstallApkDocument = { _, _ ->
                    copied += 1
                    GeneratedDocumentSaveResult.Success
                },
                deleteInstallApkDocument = { deleted += 1; true },
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()
            val effect = assertIs<ManagerEffect.CreateInstallApkDocument>(
                viewModel.effects.first(),
            )

            assertTrue(
                viewModel.beginInstallApkDocumentTransaction(effect, "content://apk/one"),
            )
            assertFalse(
                viewModel.beginInstallApkDocumentTransaction(effect, "content://apk/one"),
            )
            assertEquals(0, copied)
            assertEquals(0, deleted)
            assertTrue(
                viewModel.startInstallApkDocumentTransaction(effect, "content://apk/one"),
            )
            assertEquals(TOKEN, savedState.get<String>("install_apk_transaction_token"))
            advanceUntilIdle()

            assertEquals(1, copied)
            assertEquals(0, deleted)
            val terminal = assertIs<InstallApkDocumentTransactionState.Terminal>(
                viewModel.installApkDocumentTransaction.value,
            )
            assertIs<InstallState.AwaitingManualInstall>(repository.installFlow.value)
            assertTrue(viewModel.prepareInstallApkDocumentTerminalHandoff(terminal.key))
            assertTrue(viewModel.acknowledgeInstallApkDocumentTerminal(terminal.key))
            assertFalse(savedState.contains(ManagerViewModel.INSTALL_APK_TRANSACTION_TOKEN_KEY))
            assertFalse(savedState.contains(ManagerViewModel.INSTALL_APK_TRANSACTION_OWNER_KEY))
            assertFalse(savedState.contains(ManagerViewModel.INSTALL_APK_TRANSACTION_LAUNCH_KEY))
            assertFalse(savedState.contains(ManagerViewModel.INSTALL_APK_TRANSACTION_DESTINATION_KEY))
            assertFalse(savedState.contains(ManagerViewModel.INSTALL_APK_TRANSACTION_PHASE_KEY))
            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            assertEquals(StartupRecoveryUiState.READY, recreated.state.value.startupRecoveryState)
        }

    @Test
    fun `startup INITIALIZING 중 APK callback은 복원 완료 전 copy나 삭제를 시작하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val review = review(ContractCompatibilityStatus.COMPATIBLE)
            val source = File("/retained/${review.token}/wrapper.apk")
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.REVIEW_TOKEN_KEY to review.token,
                    "pending_system_apk_token" to review.token,
                    "pending_system_apk_source" to source.path,
                    "pending_system_apk_sha256" to "a".repeat(64),
                    "pending_system_apk_name" to "wrapper.apk",
                    "pending_system_apk_owner_generation" to 7L,
                    "pending_system_apk_launch_id" to 11L,
                    "document_owner_generation" to 7L,
                ),
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(review = review)
                installFlow.value = InstallState.PreparingApkExport(review.token)
                suspendInitialize = true
            }
            var copies = 0
            var deletes = 0
            val viewModel = ManagerViewModel(
                repository,
                savedState,
                saveInstallApkDocument = { _, _ ->
                    copies += 1
                    GeneratedDocumentSaveResult.Success
                },
                deleteInstallApkDocument = { deletes += 1; true },
            )
            testScheduler.runCurrent()
            val effect = ManagerEffect.CreateInstallApkDocument(
                review.token,
                source,
                "a".repeat(64),
                "wrapper.apk",
                7L,
                11L,
            )

            assertTrue(
                viewModel.beginInstallApkDocumentTransaction(
                    effect,
                    "content://apk/initializing",
                ),
            )
            assertTrue(
                viewModel.startInstallApkDocumentTransaction(
                    effect,
                    "content://apk/initializing",
                ),
            )
            testScheduler.runCurrent()
            assertEquals(0, copies)
            assertEquals(0, deletes)

            repository.completeInitialize()
            advanceUntilIdle()
            assertEquals(1, copies)
            assertEquals(0, deletes)
        }

    @Test
    fun `runtime APK 저장 cleanup 실패는 exact receipt 경고를 표시한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installResult = InstallState.PreparingApkExport(TOKEN)
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = File("/retained/$TOKEN/wrapper.apk"),
                    expectedSha256 = "a".repeat(64),
                    extensionName = "확장",
                    extensionVersion = "1.0",
                    extensionId = EXTENSION_ID,
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
                saveInstallApkDocument = { _, _ ->
                    GeneratedDocumentSaveResult.Failure(cleanupFailed = true)
                },
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()
            val effect = assertIs<ManagerEffect.CreateInstallApkDocument>(
                viewModel.effects.first(),
            )

            assertTrue(
                viewModel.beginInstallApkDocumentTransaction(effect, "content://apk/failed"),
            )
            assertTrue(
                viewModel.startInstallApkDocumentTransaction(effect, "content://apk/failed"),
            )
            advanceUntilIdle()

            val message = requireNotNull(viewModel.state.value.message)
            assertEquals(RecoveryAction.RETRY_INSTALL_APK_CLEANUP, message.recoveryAction)
            assertEquals("content://apk/failed", message.installApkCleanupReceipt?.destination)
            assertEquals(
                InstallApkDocumentTransactionPhase.CLEANUP_FAILED,
                assertIs<InstallApkDocumentTransactionState.Terminal>(
                    viewModel.installApkDocumentTransaction.value,
                ).phase,
            )
        }

    @Test
    fun `owner 폐기 뒤 도착한 APK URI는 exact destination만 정리하고 mark하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installResult = InstallState.PreparingApkExport(TOKEN)
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = File("/retained/$TOKEN/wrapper.apk"),
                    expectedSha256 = "a".repeat(64),
                    extensionName = "확장",
                    extensionVersion = "1.0",
                    extensionId = EXTENSION_ID,
                )
            }
            val deleted = mutableListOf<String>()
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
                deleteInstallApkDocument = { deleted += it; true },
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()
            val effect = assertIs<ManagerEffect.CreateInstallApkDocument>(
                viewModel.effects.first(),
            )
            viewModel.accept(ManagerAction.ReturnHome)

            assertTrue(
                viewModel.beginInstallApkDocumentTransaction(effect, "content://apk/stale"),
            )
            assertTrue(
                viewModel.startInstallApkDocumentTransaction(effect, "content://apk/stale"),
            )
            advanceUntilIdle()

            assertEquals(listOf("content://apk/stale"), deleted)
            assertFalse(repository.installFlow.value is InstallState.AwaitingManualInstall)
        }

    @Test
    fun `복원 APK transaction은 exact Preparing에서만 URI를 정리한다`() =
        runTest(dispatcher.scheduler) {
            val review = review(ContractCompatibilityStatus.COMPATIBLE)
            val deleted = mutableListOf<String>()
            val savedState = SavedStateHandle(
                apkTransactionJournal(review.token, "COPY_IN_FLIGHT") +
                    mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to review.token),
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(review = review)
                installFlow.value = InstallState.PreparingApkExport(review.token)
            }

            val viewModel = ManagerViewModel(
                repository,
                savedState,
                deleteInstallApkDocument = { deleted += it; true },
            )
            advanceUntilIdle()

            assertEquals(listOf("content://apk/restored"), deleted)
            val terminal = assertIs<InstallApkDocumentTransactionState.Terminal>(
                viewModel.installApkDocumentTransaction.value,
            )
            assertEquals(InstallApkDocumentTransactionPhase.CLEANED, terminal.phase)
        }

    @Test
    fun `복원 APK transaction의 repo state 불일치는 차단하고 URI를 보존한다`() =
        runTest(dispatcher.scheduler) {
            val review = review(ContractCompatibilityStatus.COMPATIBLE)
            var deletes = 0
            val viewModel = ManagerViewModel(
                FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(review = review)
                    installFlow.value = InstallState.Idle
                },
                SavedStateHandle(
                    apkTransactionJournal(review.token, "COPY_IN_FLIGHT") +
                        mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to review.token),
                ),
                deleteInstallApkDocument = { deletes += 1; true },
            )
            advanceUntilIdle()

            assertEquals(0, deletes)
            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
        }

    @Test
    fun `잘못된 타입과 빈 token의 APK transaction journal은 crash 없이 복구를 차단한다`() =
        runTest(dispatcher.scheduler) {
            val review = review(ContractCompatibilityStatus.COMPATIBLE)
            var deletes = 0
            val malformed = apkTransactionJournal("", "COPY_IN_FLIGHT").toMutableMap().apply {
                put(ManagerViewModel.INSTALL_APK_TRANSACTION_OWNER_KEY, "잘못된 타입")
            }
            val viewModel = ManagerViewModel(
                FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(review = review)
                    installFlow.value = InstallState.Idle
                },
                SavedStateHandle(malformed + mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to review.token)),
                deleteInstallApkDocument = { deletes += 1; true },
            )
            advanceUntilIdle()

            assertEquals(0, deletes)
            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
        }

    @Test
    fun `귀속할 수 없는 APK URI receipt는 process recreation 뒤에도 복구를 차단한다`() =
        runTest(dispatcher.scheduler) {
            val saved = SavedStateHandle(
                mapOf(
                    ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.INSTALL_APK_UNATTRIBUTED_DESTINATION_KEY to
                        "content://apk/unattributed",
                ),
            )
            val viewModel = ManagerViewModel(
                FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(
                        review = review(ContractCompatibilityStatus.COMPATIBLE),
                    )
                },
                saved,
            )
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
        }

    @Test
    fun `COMMITTED journal과 Preparing 불일치는 APK를 삭제하지 않고 복구를 차단한다`() =
        runTest(dispatcher.scheduler) {
            val review = review(ContractCompatibilityStatus.COMPATIBLE)
            var deletes = 0
            val viewModel = ManagerViewModel(
                FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(review = review)
                    installFlow.value = InstallState.PreparingApkExport(review.token)
                },
                SavedStateHandle(
                    apkTransactionJournal(review.token, "COMMITTED") +
                        mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to review.token),
                ),
                deleteInstallApkDocument = { deletes += 1; true },
            )
            advanceUntilIdle()

            assertEquals(0, deletes)
            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
        }

    @Test
    fun `CLEANED journal은 repo 상태와 무관하게 재삭제하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val review = review(ContractCompatibilityStatus.COMPATIBLE)
            var deletes = 0
            val viewModel = ManagerViewModel(
                FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(review = review)
                    installFlow.value = InstallState.Idle
                },
                SavedStateHandle(
                    apkTransactionJournal(review.token, "CLEANED") +
                        mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to review.token),
                ),
                deleteInstallApkDocument = { deletes += 1; true },
            )
            advanceUntilIdle()

            assertEquals(0, deletes)
            val terminal = assertIs<InstallApkDocumentTransactionState.Terminal>(
                viewModel.installApkDocumentTransaction.value,
            )
            assertEquals(InstallApkDocumentTransactionPhase.CLEANED, terminal.phase)
        }

    @Test
    fun `복원 CLEANUP_FAILED journal은 삭제 없이 durable 경고를 다시 표시한다`() =
        runTest(dispatcher.scheduler) {
            val review = review(ContractCompatibilityStatus.COMPATIBLE)
            var deletes = 0
            val savedState = SavedStateHandle(
                apkTransactionJournal(review.token, "CLEANUP_FAILED") +
                    mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to review.token),
            )
            val viewModel = ManagerViewModel(
                FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(review = review)
                    installFlow.value = InstallState.Idle
                },
                savedState,
                deleteInstallApkDocument = { deletes += 1; true },
            )
            advanceUntilIdle()

            assertEquals(0, deletes)
            assertEquals(UserMessageKind.INSTALL_FAILED, viewModel.state.value.message?.kind)
            val receipt = requireNotNull(
                viewModel.state.value.message?.installApkCleanupReceipt,
            )
            val ownerGenerationBefore =
                savedState.get<Long>(ManagerViewModel.DOCUMENT_OWNER_GENERATION_KEY)
            viewModel.accept(ManagerAction.RetryInstallApkCleanup(receipt))
            viewModel.accept(ManagerAction.RetryInstallApkCleanup(receipt))
            advanceUntilIdle()
            assertEquals(1, deletes)
            assertEquals(
                ownerGenerationBefore,
                savedState.get<Long>(ManagerViewModel.DOCUMENT_OWNER_GENERATION_KEY),
            )
            assertEquals(
                InstallApkDocumentTransactionPhase.CLEANUP_RESOLVED_HANDOFF_PENDING,
                assertIs<InstallApkDocumentTransactionState.Terminal>(
                    viewModel.installApkDocumentTransaction.value,
                ).phase,
            )
        }

    @Test
    fun `복원 cleanup 재삭제 진행 journal은 자동 삭제 없이 경고로 돌아간다`() =
        runTest(dispatcher.scheduler) {
            val review = review(ContractCompatibilityStatus.COMPATIBLE)
            var deletes = 0
            val viewModel = ManagerViewModel(
                FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(review = review)
                    installFlow.value = InstallState.Idle
                },
                SavedStateHandle(
                    apkTransactionJournal(review.token, "CLEANUP_RETRY_IN_FLIGHT") +
                        mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to review.token),
                ),
                deleteInstallApkDocument = { deletes += 1; true },
            )
            advanceUntilIdle()

            assertEquals(0, deletes)
            assertEquals(
                RecoveryAction.RETRY_INSTALL_APK_CLEANUP,
                viewModel.state.value.message?.recoveryAction,
            )
            assertEquals(
                InstallApkDocumentTransactionPhase.CLEANUP_FAILED,
                assertIs<InstallApkDocumentTransactionState.Terminal>(
                    viewModel.installApkDocumentTransaction.value,
                ).phase,
            )
        }

    @Test
    fun `APK cleanup 재삭제 실패 뒤 exact 수동 삭제 확인만 receipt를 해소한다`() =
        runTest(dispatcher.scheduler) {
            val review = review(ContractCompatibilityStatus.COMPATIBLE)
            var deletes = 0
            val viewModel = ManagerViewModel(
                FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(review = review)
                    installFlow.value = InstallState.PreparingApkExport(review.token)
                },
                SavedStateHandle(
                    apkTransactionJournal(review.token, "CLEANUP_FAILED") +
                        mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to review.token),
                ),
                deleteInstallApkDocument = { deletes += 1; false },
            )
            advanceUntilIdle()
            val receipt = requireNotNull(
                viewModel.state.value.message?.installApkCleanupReceipt,
            )

            viewModel.accept(ManagerAction.RetryInstallApkCleanup(receipt))
            advanceUntilIdle()
            assertEquals(1, deletes)
            assertEquals(
                RecoveryAction.CONFIRM_INSTALL_APK_CLEANUP,
                viewModel.state.value.message?.recoveryAction,
            )
            viewModel.accept(ManagerAction.ConfirmInstallApkCleanup(receipt))
            assertEquals(1, deletes)
            assertEquals(
                InstallApkDocumentTransactionPhase.CLEANUP_RESOLVED_HANDOFF_PENDING,
                assertIs<InstallApkDocumentTransactionState.Terminal>(
                    viewModel.installApkDocumentTransaction.value,
                ).phase,
            )
        }

    @Test
    fun `stale receipt와 generic Retry는 APK cleanup 파일을 건드리지 않는다`() =
        runTest(dispatcher.scheduler) {
            val review = review(ContractCompatibilityStatus.COMPATIBLE)
            var deletes = 0
            val viewModel = ManagerViewModel(
                FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(review = review)
                    installFlow.value = InstallState.PreparingApkExport(review.token)
                },
                SavedStateHandle(
                    apkTransactionJournal(review.token, "CLEANUP_FAILED") +
                        mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to review.token),
                ),
                deleteInstallApkDocument = { deletes += 1; true },
            )
            advanceUntilIdle()
            val receipt = requireNotNull(
                viewModel.state.value.message?.installApkCleanupReceipt,
            )

            viewModel.accept(ManagerAction.RetryRecovery)
            viewModel.accept(
                ManagerAction.RetryInstallApkCleanup(
                    receipt.copy(destination = "content://apk/other"),
                ),
            )
            advanceUntilIdle()

            assertEquals(0, deletes)
            assertEquals(
                InstallApkDocumentTransactionPhase.CLEANUP_FAILED,
                assertIs<InstallApkDocumentTransactionState.Terminal>(
                    viewModel.installApkDocumentTransaction.value,
                ).phase,
            )
        }

    @Test
    fun `늦은 cleanup 실패 경고는 현재 APK document owner를 폐기하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installResult = InstallState.PreparingApkExport(TOKEN)
                installApkExport = InstallApkExport(
                    importToken = TOKEN,
                    source = File("/retained/$TOKEN/wrapper.apk"),
                    expectedSha256 = "a".repeat(64),
                    extensionName = "확장",
                    extensionVersion = "1.0",
                    extensionId = EXTENSION_ID,
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN)),
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()
            val current = assertIs<ManagerEffect.CreateInstallApkDocument>(
                viewModel.effects.first(),
            )

            viewModel.accept(ManagerAction.InstallApkCleanupFailed(1, 999))

            assertTrue(viewModel.ownsSystemInstallApkDocument(current))
        }

    @Test
    fun `startup export owner 불일치는 복원된 pending APK effect를 폐기한다`() =
        runTest(dispatcher.scheduler) {
            val source = File("/retained/$TOKEN/wrapper.apk")
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN,
                    "pending_system_apk_token" to SECOND_TOKEN,
                    "pending_system_apk_source" to source.path,
                    "pending_system_apk_sha256" to "a".repeat(64),
                    "pending_system_apk_name" to "wrapper.apk",
                ),
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installFlow.value = InstallState.PreparingApkExport(TOKEN)
            }

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertNull(savedState.get<String>("pending_system_apk_token"))
            val stale = ManagerEffect.CreateInstallApkDocument(
                importToken = SECOND_TOKEN,
                source = source,
                expectedSha256 = "a".repeat(64),
                suggestedFileName = "wrapper.apk",
                ownerGeneration = 0,
                launchId = 0,
            )
            assertFalse(viewModel.ownsSystemInstallApkDocument(stale))
        }

    @Test
    fun `forced generated 설치가 시작된 재생성은 exact G R owner를 보존한다`() =
        runTest(dispatcher.scheduler) {
            val forcedReview = forcedReview()
            val generated = generatedBundle()
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.REVIEW_TOKEN_KEY to forcedReview.token,
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY to generated.token,
                    ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY to generated.token,
                    ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY to forcedReview.token,
                    ManagerViewModel.CONVERSION_TOKEN_KEY to generated.token,
                    ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
                ),
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(review = forcedReview)
                restoredConversion = RestoredConversion.BundleReady(forcedReview, generated)
                installFlow.value = InstallState.PreparingApkExport(forcedReview.token)
                installApkExport = InstallApkExport(
                    importToken = forcedReview.token,
                    source = File("/retained/${forcedReview.token}/wrapper.apk"),
                    expectedSha256 = "a".repeat(64),
                    extensionName = "확장",
                    extensionVersion = "1.0",
                    extensionId = EXTENSION_ID,
                )
            }

            val restored = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(generated.token, savedState.get<String>(
                ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
            ))
            assertEquals(forcedReview.token, savedState.get<String>(
                ManagerViewModel.REVIEW_TOKEN_KEY,
            ))
            assertEquals(ManagerRoute.Review(forcedReview.token), restored.state.value.route)
            assertTrue(
                assertIs<ConversionUiState.ResultReady>(
                    restored.state.value.conversionState,
                ).installOwnerActive,
            )
            assertFalse(
                assertIs<ImportUiState.ReviewReady>(
                    restored.state.value.importState,
                ).forceCompatibilityConfirmed,
            )
        }

    @Test
    fun `profile 또는 report drift 실패는 stale 강제 분석을 복원해 두 번째 build하지 않는다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                ConversionFailureCode.PROFILE_CHANGED,
                ConversionFailureCode.TRANSFORM_MISMATCH,
            ).forEach { failureCode ->
                val repository = FakeManagerRepository().apply {
                    conversionBeginResult = forcedConversionAnalysis()
                    conversionBuildResult = ConversionBuildResult.Failure(failureCode)
                }
                val viewModel = ManagerViewModel(repository, SavedStateHandle())
                advanceUntilIdle()
                viewModel.accept(ManagerAction.OpenConversionInput)
                viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
                viewModel.accept(ManagerAction.AnalyzeConversionUrl)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.ConfirmConversionForce)
                viewModel.accept(ManagerAction.BuildConversion)
                advanceUntilIdle()

                val failed = assertIs<ConversionUiState.Failed>(
                    viewModel.state.value.conversionState,
                )
                assertEquals(failureCode, failed.code)
                viewModel.accept(ManagerAction.ConfirmConversionForce)
                viewModel.accept(ManagerAction.BuildConversion)
                advanceUntilIdle()
                assertEquals(1, repository.conversionBuildRequests.size)
            }
        }

    @Test
    fun `build는 exact approval set과 partial 확인 payload를 전달한다`() =
        runTest(dispatcher.scheduler) {
            val approvals = setOf("action-popup-entry", "tabs")
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis().copy(
                    report = conversionAnalysis().report.copy(
                        status = CompatibilityStatus.PARTIAL,
                    ),
                    requiredApprovals = approvals,
                )
                conversionBuildResult =
                    ConversionBuildResult.Failure(ConversionFailureCode.PACKAGE)
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()

            approvals.forEach {
                viewModel.accept(ManagerAction.ToggleConversionAdapter(it))
            }
            viewModel.accept(ManagerAction.ConfirmConversionPartial)
            viewModel.accept(ManagerAction.BuildConversion)
            advanceUntilIdle()

            assertEquals(
                ConversionBuildRequest(
                    token = TOKEN,
                    approvedAdapterIds = approvals,
                    partialConfirmed = true,
                    identitySelection = null,
                ),
                repository.conversionBuildRequests.single(),
            )
        }

    @Test
    fun `markerless CreateNew payload 값을 그대로 전달한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis().copy(
                    identityRequirement =
                        IdentityRequirement.UserSelectionRequired(listOf(existingTarget())),
                )
                conversionBuildResult =
                    ConversionBuildResult.Failure(ConversionFailureCode.PACKAGE)
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.SelectNewConversionIdentity)
            viewModel.accept(ManagerAction.BuildConversion)
            advanceUntilIdle()

            assertEquals(
                InstallationIdentitySelection.CreateNew,
                repository.conversionBuildRequests.single().identitySelection,
            )
        }

    @Test
    fun `markerless UseExisting payload는 확인한 identity hash를 그대로 전달한다`() =
        runTest(dispatcher.scheduler) {
            val target = existingTarget()
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis().copy(
                    identityRequirement =
                        IdentityRequirement.UserSelectionRequired(listOf(target)),
                )
                conversionBuildResult =
                    ConversionBuildResult.Failure(ConversionFailureCode.PACKAGE)
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()

            viewModel.accept(
                ManagerAction.SelectExistingConversionIdentity(target.identitySha256),
            )
            viewModel.accept(ManagerAction.ConfirmExistingConversionIdentity)
            viewModel.accept(ManagerAction.BuildConversion)
            advanceUntilIdle()

            assertEquals(
                InstallationIdentitySelection.UseExisting(target.identitySha256),
                repository.conversionBuildRequests.single().identitySelection,
            )
        }

    @Test
    fun `generated proof가 있는 모든 build failure는 원래 code와 export 동작을 유지한다`() =
        runTest(dispatcher.scheduler) {
            ConversionFailureCode.entries.forEach { code ->
                val generated = generatedBundle()
                val repository = FakeManagerRepository().apply {
                    conversionBeginResult = conversionAnalysis()
                    conversionBuildResult = ConversionBuildResult.Failure(code, generated)
                    generatedShareSnapshot = generated.file
                }
                val viewModel = ManagerViewModel(repository, SavedStateHandle())
                advanceUntilIdle()
                viewModel.accept(ManagerAction.OpenConversionInput)
                viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
                viewModel.accept(ManagerAction.AnalyzeConversionUrl)
                advanceUntilIdle()

                viewModel.accept(ManagerAction.BuildConversion)
                advanceUntilIdle()

                val expectedMessage = conversionFailureMessage(code).copy(
                    recoveryAction = RecoveryAction.NONE,
                )
                fun assertFailureProof() {
                    val ready = assertIs<ConversionUiState.ResultReady>(
                        viewModel.state.value.conversionState,
                        "code=$code",
                    )
                    assertEquals(code, ready.failureCode, "code=$code")
                    assertEquals(generated, ready.generated, "code=$code")
                    assertEquals(expectedMessage, viewModel.state.value.message, "code=$code")
                }
                assertFailureProof()

                viewModel.accept(ManagerAction.SaveGeneratedConversion)
                assertIs<ManagerEffect.CreateConversionDocument>(viewModel.effects.first())
                assertFailureProof()

                viewModel.accept(ManagerAction.ShareGeneratedConversion)
                dispatcher.scheduler.runCurrent()
                val share = assertIs<ManagerEffect.ShareGeneratedBundle>(
                    viewModel.effects.first(),
                )
                viewModel.accept(
                    ManagerAction.GeneratedShareLaunchSucceeded(
                        share.token,
                        share.handoffId,
                    ),
                )
                dispatcher.scheduler.runCurrent()
                assertFailureProof()

                viewModel.accept(ManagerAction.PrepareGeneratedInstall)
                assertFailureProof()
            }
        }

    @Test
    fun `공유 chooser 요청만으로 generated와 work를 삭제하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val generated = generatedBundle()
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generated,
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(
                    mapOf(
                        ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                        ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
                    ),
                ),
            )
            advanceUntilIdle()

            viewModel.accept(ManagerAction.ShareGeneratedConversion)
            val effect = assertIs<ManagerEffect.ShareGeneratedBundle>(viewModel.effects.first())
            viewModel.accept(
                ManagerAction.GeneratedShareLaunchSucceeded(
                    effect.token,
                    effect.handoffId,
                ),
            )
            dispatcher.scheduler.runCurrent()

            assertEquals(TOKEN, effect.token)
            assertTrue(repository.discardedConversions.isEmpty())
        }

    @Test
    fun `저장은 repository copy hash가 generated hash와 같을 때만 성공한다`() =
        runTest(dispatcher.scheduler) {
            val generated = generatedBundle()
            val copied = ByteArrayOutputStream()
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generated,
                )
                copiedConversionHash = generated.sha256
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(),
                saveGeneratedDocument = documentSaver(copied),
            )
            advanceUntilIdle()

            viewModel.accept(ManagerAction.SaveGeneratedConversion)
            val effect = assertIs<ManagerEffect.CreateConversionDocument>(
                viewModel.effects.first(),
            )
            viewModel.accept(
                ManagerAction.SaveGeneratedConversionTo(
                    token = TOKEN,
                    destination = "content://save/result.six",
                    ownerGeneration = effect.ownerGeneration,
                    launchId = effect.launchId,
                ),
            )
            advanceUntilIdle()

            assertEquals(listOf(TOKEN), repository.copiedConversionTokens)
            assertEquals(UserMessageKind.CONVERSION_SAVED, viewModel.state.value.message?.kind)
        }

    @Test
    fun `저장 실패는 부분 파일 정리 실패 여부를 별도 메시지로 전달한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                false to UserMessageKind.CONVERSION_SAVE_FAILED,
                true to UserMessageKind.CONVERSION_SAVE_CLEANUP_FAILED,
            ).forEach { (cleanupFailed, expectedKind) ->
                val repository = FakeManagerRepository().apply {
                    restoredConversion = RestoredConversion.BundleReady(
                        review(ContractCompatibilityStatus.COMPATIBLE),
                        generatedBundle(),
                    )
                }
                val viewModel = ManagerViewModel(
                    repository,
                    SavedStateHandle(),
                    saveGeneratedDocument = { _, _, _ ->
                        GeneratedDocumentSaveResult.Failure(cleanupFailed)
                    },
                )
                advanceUntilIdle()

                viewModel.accept(ManagerAction.SaveGeneratedConversion)
                val effect = assertIs<ManagerEffect.CreateConversionDocument>(
                    viewModel.effects.first(),
                )
                viewModel.accept(
                    ManagerAction.SaveGeneratedConversionTo(
                        token = effect.token,
                        destination = "content://save/failure.six",
                        ownerGeneration = effect.ownerGeneration,
                        launchId = effect.launchId,
                    ),
                )
                advanceUntilIdle()

                assertEquals(expectedKind, viewModel.state.value.message?.kind)
                assertTrue(viewModel.state.value.message?.text?.contains("content://") == false)

                val recoveryEffects = mutableListOf<ManagerEffect>()
                backgroundScope.launch {
                    viewModel.effects.collect(recoveryEffects::add)
                }
                dispatcher.scheduler.runCurrent()
                viewModel.accept(ManagerAction.SaveGeneratedConversion)
                dispatcher.scheduler.runCurrent()

                assertIs<ManagerEffect.CreateConversionDocument>(recoveryEffects.single())
                assertTrue(repository.copiedConversionTokens.isEmpty())
            }
        }

    @Test
    fun `review 없는 generated failure는 Retry 소유권과 effect를 만들지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis()
                conversionBuildResult = ConversionBuildResult.Failure(
                    ConversionFailureCode.BUNDLE_IO,
                    generatedBundle(),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.BuildConversion)
            advanceUntilIdle()

            val ready = assertIs<ConversionUiState.ResultReady>(
                viewModel.state.value.conversionState,
            )
            assertEquals(null, ready.review)
            assertEquals(ConversionFailureCode.BUNDLE_IO, ready.failureCode)
            assertEquals(RecoveryAction.NONE, viewModel.state.value.message?.recoveryAction)

            viewModel.accept(ManagerAction.RetryRecovery)
            advanceUntilIdle()

            assertEquals(1, repository.conversionBuildRequests.size)
            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
        }

    @Test
    fun `save cancellation delete failure는 typed 결과까지 기다리고 generated owner를 유지한다`() =
        runTest(dispatcher.scheduler) {
            val saveStarted = CompletableDeferred<Unit>()
            val cancellationResult =
                CompletableDeferred<GeneratedDocumentSaveResult.Cancelled>()
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(
                repository,
                savedState,
                saveGeneratedDocument = { _, _, _ ->
                    saveStarted.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                        error("save gate가 취소되어야 합니다.")
                    } catch (_: CancellationException) {
                        withContext(NonCancellable) {
                            cancellationResult.await()
                        }
                    }
                },
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.SaveGeneratedConversion)
            val effect = assertIs<ManagerEffect.CreateConversionDocument>(
                viewModel.effects.first(),
            )
            viewModel.accept(
                ManagerAction.SaveGeneratedConversionTo(
                    token = effect.token,
                    destination = "content://save/cancel-delete-failure.six",
                    ownerGeneration = effect.ownerGeneration,
                    launchId = effect.launchId,
                ),
            )
            dispatcher.scheduler.runCurrent()
            saveStarted.await()

            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            assertTrue(repository.discardAttempts.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())

            cancellationResult.complete(
                GeneratedDocumentSaveResult.Cancelled(cleanupFailed = true),
            )
            advanceUntilIdle()

            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertTrue(repository.discardAttempts.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
            assertEquals(
                UserMessageKind.CONVERSION_SAVE_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(
                TOKEN,
                savedState.get<String>(ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY),
            )
        }

    @Test
    fun `save failure cleanup 실패도 generated owner를 유지한다`() =
        runTest(dispatcher.scheduler) {
            val saveStarted = CompletableDeferred<Unit>()
            val failureResult = CompletableDeferred<GeneratedDocumentSaveResult>()
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(
                repository,
                savedState,
                saveGeneratedDocument = { _, _, _ ->
                    saveStarted.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                        error("save gate가 취소되어야 합니다.")
                    } catch (_: CancellationException) {
                        withContext(NonCancellable) {
                            failureResult.await()
                        }
                    }
                },
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.SaveGeneratedConversion)
            val effect = assertIs<ManagerEffect.CreateConversionDocument>(
                viewModel.effects.first(),
            )
            viewModel.accept(
                ManagerAction.SaveGeneratedConversionTo(
                    token = effect.token,
                    destination = "content://save/failure-delete-failure.six",
                    ownerGeneration = effect.ownerGeneration,
                    launchId = effect.launchId,
                ),
            )
            dispatcher.scheduler.runCurrent()
            saveStarted.await()
            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            failureResult.complete(
                GeneratedDocumentSaveResult.Failure(cleanupFailed = true),
            )
            advanceUntilIdle()

            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertTrue(repository.discardAttempts.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
            assertEquals(
                UserMessageKind.CONVERSION_SAVE_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(
                TOKEN,
                savedState.get<String>(ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY),
            )
        }

    @Test
    fun `save gate 중 Installed cleanup은 typed completion 뒤에만 discard한다`() =
        runTest(dispatcher.scheduler) {
            val saveStarted = CompletableDeferred<Unit>()
            val saveResult = CompletableDeferred<GeneratedDocumentSaveResult>()
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val viewModel = ManagerViewModel(
                repository,
                SavedStateHandle(),
                saveGeneratedDocument = { _, _, _ ->
                    saveStarted.complete(Unit)
                    try {
                        CompletableDeferred<Unit>().await()
                        error("save gate가 취소되어야 합니다.")
                    } catch (_: CancellationException) {
                        withContext(NonCancellable) { saveResult.await() }
                    }
                },
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.SaveGeneratedConversion)
            val effect = assertIs<ManagerEffect.CreateConversionDocument>(
                viewModel.effects.first(),
            )
            viewModel.accept(
                ManagerAction.SaveGeneratedConversionTo(
                    token = effect.token,
                    destination = "content://save/installed-race.six",
                    ownerGeneration = effect.ownerGeneration,
                    launchId = effect.launchId,
                ),
            )
            dispatcher.scheduler.runCurrent()
            saveStarted.await()

            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.Installed(PACKAGE)
            dispatcher.scheduler.runCurrent()
            assertTrue(repository.discardedConversions.isEmpty())

            saveResult.complete(GeneratedDocumentSaveResult.Cancelled(cleanupFailed = false))
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }

    @Test
    fun `cleanup pending은 ViewModel Save와 Share effect를 차단한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardReviewResult = false
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            val effects = mutableListOf<ManagerEffect>()
            backgroundScope.launch {
                viewModel.effects.collect(effects::add)
            }
            advanceUntilIdle()
            viewModel.accept(ManagerAction.FinishConversion)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.SaveGeneratedConversion)
            viewModel.accept(ManagerAction.ShareGeneratedConversion)
            dispatcher.scheduler.runCurrent()

            assertTrue(effects.isEmpty())
        }

    @Test
    fun `share effect는 original cleanup과 독립된 repository snapshot을 사용한다`() =
        runTest(dispatcher.scheduler) {
            val snapshot = File.createTempFile("share-snapshot", ".six").apply {
                writeText("immutable snapshot")
                deleteOnExit()
            }
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                generatedShareSnapshot = snapshot
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.ShareGeneratedConversion)
            advanceUntilIdle()
            val effect = assertIs<ManagerEffect.ShareGeneratedBundle>(
                viewModel.effects.first(),
            )
            viewModel.accept(
                ManagerAction.GeneratedShareLaunchSucceeded(
                    effect.token,
                    effect.handoffId,
                ),
            )
            viewModel.accept(ManagerAction.FinishConversion)
            advanceUntilIdle()

            assertEquals(listOf(TOKEN), repository.generatedShareSnapshotRequests)
            assertEquals(snapshot, effect.file)
            assertEquals("immutable snapshot", effect.file.readText())
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        }

    @Test
    fun `share snapshot 생성 중 Finish는 snapshot 완료 뒤 original을 정리한다`() =
        runTest(dispatcher.scheduler) {
            val snapshot = File.createTempFile("share-race", ".six").apply {
                writeText("immutable race snapshot")
                deleteOnExit()
            }
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                generatedShareSnapshot = snapshot
                suspendGeneratedShareSnapshot = true
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            val effects = mutableListOf<ManagerEffect>()
            backgroundScope.launch {
                viewModel.effects.collect(effects::add)
            }
            advanceUntilIdle()

            viewModel.accept(ManagerAction.ShareGeneratedConversion)
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf(TOKEN), repository.generatedShareSnapshotRequests)
            viewModel.accept(ManagerAction.FinishConversion)
            dispatcher.scheduler.runCurrent()
            assertTrue(repository.discardedConversions.isEmpty())

            repository.completeGeneratedShareSnapshot()
            dispatcher.scheduler.runCurrent()

            val effect = assertIs<ManagerEffect.ShareGeneratedBundle>(effects.single())
            viewModel.accept(
                ManagerAction.GeneratedShareLaunchSucceeded(
                    effect.token,
                    effect.handoffId,
                ),
            )
            advanceUntilIdle()
            assertEquals(snapshot, effect.file)
            assertEquals("immutable race snapshot", effect.file.readText())
            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        }

    @Test
    fun `share snapshot exception becomes message without effect`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                generatedShareSnapshotException = IOException("snapshot failure")
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            val effects = mutableListOf<ManagerEffect>()
            backgroundScope.launch {
                viewModel.effects.collect(effects::add)
            }
            advanceUntilIdle()

            viewModel.accept(ManagerAction.ShareGeneratedConversion)
            advanceUntilIdle()

            assertTrue(effects.isEmpty())
            assertEquals(
                UserMessageKind.CONVERSION_SHARE_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(
                ManagerRoute.ConversionResult(TOKEN),
                viewModel.state.value.route,
            )
        }

    @Test
    fun `stopped share collector waits exact handoff ACK`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.ShareGeneratedConversion)
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.FinishConversion)
            dispatcher.scheduler.runCurrent()

            assertEquals(
                ManagerRoute.ConversionResult(TOKEN),
                viewModel.state.value.route,
            )
            assertTrue(repository.discardedConversions.isEmpty())
            val effect = assertIs<ManagerEffect.ShareGeneratedBundle>(
                viewModel.effects.first(),
            )

            viewModel.accept(
                ManagerAction.GeneratedShareLaunchSucceeded(
                    token = SECOND_TOKEN,
                    handoffId = effect.handoffId,
                ),
            )
            viewModel.accept(
                ManagerAction.GeneratedShareLaunchSucceeded(
                    token = TOKEN,
                    handoffId = effect.handoffId + 1,
                ),
            )
            dispatcher.scheduler.runCurrent()

            assertEquals(
                ManagerRoute.ConversionResult(TOKEN),
                viewModel.state.value.route,
            )
            assertTrue(repository.discardedConversions.isEmpty())

            viewModel.accept(
                ManagerAction.GeneratedShareLaunchSucceeded(
                    token = TOKEN,
                    handoffId = effect.handoffId,
                ),
            )
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }

    @Test
    fun `exact share failure ACK는 message를 표시하고 handoff를 종료한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.ShareGeneratedConversion)
            val effect = assertIs<ManagerEffect.ShareGeneratedBundle>(
                viewModel.effects.first(),
            )

            viewModel.accept(
                ManagerAction.GeneratedShareLaunchFailed(
                    effect.token,
                    effect.handoffId,
                ),
            )
            advanceUntilIdle()

            assertEquals(
                UserMessageKind.CONVERSION_SHARE_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(
                ManagerRoute.ConversionResult(TOKEN),
                viewModel.state.value.route,
            )
            assertTrue(repository.discardedConversions.isEmpty())
        }

    @Test
    fun `share failure ACK도 pending cleanup을 unblock한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.ShareGeneratedConversion)
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.FinishConversion)
            dispatcher.scheduler.runCurrent()
            val effect = assertIs<ManagerEffect.ShareGeneratedBundle>(
                viewModel.effects.first(),
            )

            viewModel.accept(
                ManagerAction.GeneratedShareLaunchFailed(
                    effect.token,
                    effect.handoffId,
                ),
            )
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }

    @Test
    fun `generated cleanup gates wait share ACK`() =
        runTest(dispatcher.scheduler) {
            listOf("Installed", "Retry", "Finish", "Cancel").forEach { gate ->
                val repository = FakeManagerRepository().apply {
                    restoredConversion = RestoredConversion.BundleReady(
                        review(ContractCompatibilityStatus.COMPATIBLE),
                        generatedBundle(),
                    )
                }
                val viewModel = ManagerViewModel(repository, SavedStateHandle())
                advanceUntilIdle()
                if (gate == "Retry") {
                    viewModel.accept(ManagerAction.PrepareGeneratedInstall)
                    dispatcher.scheduler.runCurrent()
                    repository.installFlow.value =
                        InstallState.Failed("ABANDON_FAILED", "cleanup retry")
                    advanceUntilIdle()
                }

                viewModel.accept(ManagerAction.ShareGeneratedConversion)
                dispatcher.scheduler.runCurrent()
                val cancelRequestsBeforeGate = repository.cancelInstallRequests
                when (gate) {
                    "Installed" -> {
                        viewModel.accept(ManagerAction.PrepareGeneratedInstall)
                        repository.installFlow.value = InstallState.Installed(PACKAGE)
                    }
                    "Retry" -> viewModel.accept(ManagerAction.RetryGeneratedCleanup)
                    "Finish" -> viewModel.accept(ManagerAction.FinishConversion)
                    "Cancel" -> viewModel.accept(ManagerAction.CancelConversion)
                }
                dispatcher.scheduler.runCurrent()

                assertEquals(
                    ManagerRoute.ConversionResult(TOKEN),
                    viewModel.state.value.route,
                    "gate=$gate",
                )
                assertTrue(repository.discardedConversions.isEmpty(), "gate=$gate")
                if (gate == "Retry") {
                    assertEquals(
                        cancelRequestsBeforeGate,
                        repository.cancelInstallRequests,
                        "gate=$gate",
                    )
                }

                val effect = assertIs<ManagerEffect.ShareGeneratedBundle>(
                    viewModel.effects.first(),
                )
                viewModel.accept(
                    ManagerAction.GeneratedShareLaunchSucceeded(
                        token = effect.token,
                        handoffId = effect.handoffId,
                    ),
                )
                advanceUntilIdle()

                when (gate) {
                    "Installed" ->
                        assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
                    "Retry" -> {
                        assertEquals(
                            ManagerRoute.ConversionResult(TOKEN),
                            viewModel.state.value.route,
                        )
                        assertEquals(
                            cancelRequestsBeforeGate + 1,
                            repository.cancelInstallRequests,
                        )
                    }
                    "Finish",
                    "Cancel",
                    -> assertEquals(ManagerRoute.Home, viewModel.state.value.route)
                }
            }
        }

    @Test
    fun `결과 완료는 generated work와 retained review를 명시적으로 정리한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.FinishConversion)
            advanceUntilIdle()

            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        }

    @Test
    fun `Finish cleanup은 review first 실패에서 conversion proof를 건드리지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardReviewResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.FinishConversion)
            advanceUntilIdle()

            assertEquals(listOf("discardReview:$TOKEN"), repository.lifecycleEvents)
            assertTrue(repository.generatedExists)
            assertEquals(
                TOKEN,
                assertIs<ConversionUiState.ResultReady>(
                    viewModel.state.value.conversionState,
                ).review?.token,
            )
            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
        }

    @Test
    fun `새 변환 cleanup은 review first 실패에서 conversion proof를 건드리지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardReviewResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenConversionInput)
            advanceUntilIdle()

            assertEquals(listOf("discardReview:$TOKEN"), repository.lifecycleEvents)
            assertTrue(repository.generatedExists)
            assertEquals(
                TOKEN,
                assertIs<ConversionUiState.ResultReady>(
                    viewModel.state.value.conversionState,
                ).review?.token,
            )
            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
        }

    @Test
    fun `Installed cleanup은 review first 실패에서 conversion proof를 건드리지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardReviewResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)

            repository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()

            assertEquals(
                listOf("install:start", "install:end", "discardReview:$TOKEN"),
                repository.lifecycleEvents,
            )
            assertTrue(repository.generatedExists)
            val ready = assertIs<ConversionUiState.ResultReady>(
                viewModel.state.value.conversionState,
            )
            assertTrue(ready.cleanupPending)
            assertFalse(ready.cleanupInProgress)
            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
        }

    @Test
    fun `installed finalizing review 중 Cancel은 cleanup과 Report destination을 바꾸지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            lateinit var viewModel: ManagerViewModel
            var cleanupInProgressObserved = false
            repository.beforeDiscardReview = {
                cleanupInProgressObserved = assertIs<ConversionUiState.ResultReady>(
                    viewModel.state.value.conversionState,
                ).cleanupInProgress
                viewModel.accept(ManagerAction.CancelConversion)
            }
            viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)

            repository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()

            assertTrue(cleanupInProgressObserved)
            assertEquals(
                listOf(
                    "install:start",
                    "install:end",
                    "discardReview:$TOKEN",
                    "discardConversion:$TOKEN",
                ),
                repository.lifecycleEvents,
            )
            assertEquals(1, repository.discardAttempts.size)
            assertEquals(1, repository.discardedConversions.size)
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
        }

    @Test
    fun `installed finalizing conversion gate 중 Cancel은 두 번째 cleanup을 만들지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                suspendDiscardConversion = true
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)

            repository.installFlow.value = InstallState.Installed(PACKAGE)
            dispatcher.scheduler.runCurrent()

            val finalizing = assertIs<ConversionUiState.ResultReady>(
                viewModel.state.value.conversionState,
            )
            assertTrue(finalizing.cleanupPending)
            assertTrue(finalizing.cleanupInProgress)
            assertEquals(1, repository.discardAttempts.size)
            assertEquals(1, repository.discardedConversions.size)

            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            assertEquals(1, repository.discardAttempts.size)
            assertEquals(1, repository.discardedConversions.size)

            repository.completeDiscardConversion()
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "install:start",
                    "install:end",
                    "discardReview:$TOKEN",
                    "discardConversion:$TOKEN",
                ),
                repository.lifecycleEvents,
            )
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
        }

    @Test
    fun `review 성공 conversion 실패는 CONVERSION phase와 physical generated를 복원한다`() =
        runTest(dispatcher.scheduler) {
            val generated = generatedBundle()
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generated,
                )
                discardConversionResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.FinishConversion)
            advanceUntilIdle()

            assertEquals(
                listOf("discardReview:$TOKEN", "discardConversion:$TOKEN"),
                repository.lifecycleEvents,
            )
            val ready = assertIs<ConversionUiState.ResultReady>(
                viewModel.state.value.conversionState,
            )
            assertNull(ready.review)
            assertTrue(repository.generatedExists)
            assertNull(savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
            assertEquals(
                "CONVERSION",
                generatedCleanupPhase(savedState),
            )

            repository.lifecycleEvents.clear()
            repository.restoreResult = ManagerRestoreResult()
            repository.restoredConversion = RestoredConversion.GeneratedPending(generated)
            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            val restoredReady = assertIs<ConversionUiState.ResultReady>(
                recreated.state.value.conversionState,
            )
            assertEquals(TOKEN, restoredReady.token)
            assertNull(restoredReady.review)
            assertEquals(ManagerRoute.ConversionResult(TOKEN), recreated.state.value.route)
            assertTrue(repository.generatedExists)
            assertEquals(
                "CONVERSION",
                generatedCleanupPhase(savedState),
            )
            assertEquals(false, repository.restoreConversionImportReviewRequests.last())
        }

    @Test
    fun `generated active install 중 완료는 owner와 결과를 유지한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                InstallState.UnknownSourcesPermissionRequired,
                InstallState.WritingSession(40),
                InstallState.AwaitingUserApproval(41),
            ).forEach { active ->
                val repository = FakeManagerRepository().apply {
                    restoredConversion = RestoredConversion.BundleReady(
                        review(ContractCompatibilityStatus.COMPATIBLE),
                        generatedBundle(),
                    )
                }
                val savedState = SavedStateHandle()
                val viewModel = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.PrepareGeneratedInstall)
                repository.installFlow.value = active
                dispatcher.scheduler.runCurrent()

                viewModel.accept(ManagerAction.FinishConversion)
                dispatcher.scheduler.runCurrent()

                assertEquals(
                    ManagerRoute.ConversionResult(TOKEN),
                    viewModel.state.value.route,
                    "active=$active",
                )
                assertIs<ConversionUiState.ResultReady>(
                    viewModel.state.value.conversionState,
                    "active=$active",
                )
                assertTrue(repository.discardedConversions.isEmpty(), "active=$active")
                assertTrue(repository.discardedTokens.isEmpty(), "active=$active")
            }
        }

    @Test
    fun `generated cleanup retryable failure 네 code는 active owner와 proof를 유지한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                "CLEANUP_FAILED",
                "ABANDON_FAILED",
                "SESSION_RECOVERY_FAILED",
                "MISSING_OBSERVATION_RESET_FAILED",
            ).forEach { code ->
                val repository = FakeManagerRepository().apply {
                    restoredConversion = RestoredConversion.BundleReady(
                        review(ContractCompatibilityStatus.COMPATIBLE),
                        generatedBundle(),
                    )
                }
                val savedState = SavedStateHandle()
                val viewModel = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.PrepareGeneratedInstall)

                repository.installFlow.value = InstallState.Failed(code, "cleanup retry")
                advanceUntilIdle()

                val ready = assertIs<ConversionUiState.ResultReady>(
                    viewModel.state.value.conversionState,
                    "code=$code",
                )
                assertTrue(ready.installOwnerActive, "code=$code")
                assertEquals(
                    TOKEN,
                    savedState.get<String>(
                        ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                    ),
                    "code=$code",
                )
                assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
                assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))

                viewModel.accept(ManagerAction.FinishConversion)
                advanceUntilIdle()

                assertEquals(
                    ManagerRoute.ConversionResult(TOKEN),
                    viewModel.state.value.route,
                    "code=$code",
                )
                assertTrue(repository.discardedConversions.isEmpty(), "code=$code")
                assertTrue(repository.discardedTokens.isEmpty(), "code=$code")
            }
        }

    @Test
    fun `generated cleanup retry action은 install cleanup만 다시 하고 result proof를 유지한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value =
                InstallState.Failed("ABANDON_FAILED", "cleanup retry")
            advanceUntilIdle()

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            val ready = assertIs<ConversionUiState.ResultReady>(
                viewModel.state.value.conversionState,
            )
            assertFalse(ready.installOwnerActive)
            assertEquals(1, repository.cancelInstallRequests)
            assertTrue(repository.discardedConversions.isEmpty())
            assertTrue(repository.discardedTokens.isEmpty())
            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                ),
            )
        }

    @Test
    fun `generated cleanup retry는 REVIEW 재실패를 유지하고 성공하면 Home으로 commit한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardReviewResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.FinishConversion)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(2, repository.discardAttempts.size)
            assertTrue(repository.discardedConversions.isEmpty())
            assertEquals(
                "REVIEW",
                generatedCleanupPhase(savedState),
            )
            val failed = assertIs<ConversionUiState.ResultReady>(
                viewModel.state.value.conversionState,
            )
            assertTrue(failed.cleanupPending)
            assertFalse(failed.cleanupInProgress)
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )

            repository.discardReviewResult = true
            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(3, repository.discardAttempts.size)
            assertEquals(1, repository.discardedConversions.size)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(savedState.get<String>(ManagerViewModel.GENERATED_CLEANUP_TOKEN_KEY))
            assertNull(savedState.get<String>(ManagerViewModel.GENERATED_CLEANUP_PHASE_KEY))
            assertNull(savedState.get<String>(ManagerViewModel.GENERATED_CLEANUP_DESTINATION_KEY))
        }

    @Test
    fun `generated cleanup retry는 CONVERSION 재실패를 유지하고 성공하면 Report로 commit한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardConversionResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)

            repository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(1, repository.discardAttempts.size)
            assertEquals(2, repository.discardedConversions.size)
            assertEquals(
                "CONVERSION",
                generatedCleanupPhase(savedState),
            )
            val failed = assertIs<ConversionUiState.ResultReady>(
                viewModel.state.value.conversionState,
            )
            assertTrue(failed.cleanupPending)
            assertFalse(failed.cleanupInProgress)
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )

            repository.discardConversionResult = true
            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(1, repository.discardAttempts.size)
            assertEquals(3, repository.discardedConversions.size)
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertNull(savedState.get<String>(ManagerViewModel.GENERATED_CLEANUP_TOKEN_KEY))
            assertNull(savedState.get<String>(ManagerViewModel.GENERATED_CLEANUP_PHASE_KEY))
            assertNull(savedState.get<String>(ManagerViewModel.GENERATED_CLEANUP_DESTINATION_KEY))
        }

    @Test
    fun `conversion delete 직후 process death는 idempotent retry로 destination을 commit한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardConversionResult = false
            }
            val savedState = SavedStateHandle()
            val source = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            source.accept(ManagerAction.FinishConversion)
            advanceUntilIdle()
            assertEquals(
                "CONVERSION",
                generatedCleanupPhase(savedState),
            )
            repository.discardConversionResult = true
            repository.restoredConversion = null

            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(2, repository.discardedConversions.size)
            assertEquals(ManagerRoute.Home, recreated.state.value.route)
            assertNull(savedState.get<String>(ManagerViewModel.GENERATED_CLEANUP_TOKEN_KEY))
            assertNull(savedState.get<String>(ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY))
        }

    @Test
    fun `COMMIT_DESTINATION owner clear process death는 exact report를 복원한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val savedState = SavedStateHandle()
            val snapshots =
                linkedMapOf<GeneratedCleanupCompletionStep, SavedStateHandle>()
            val source = ManagerViewModel(
                repository = repository,
                savedStateHandle = savedState,
                afterGeneratedCleanupCompletionStep = { step ->
                    snapshots[step] = generatedCleanupDeathSnapshot(savedState)
                },
            )
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()

            repository.installFlow.value = InstallState.Installed(
                PACKAGE,
                SamsungInternetSupportStatus.UNVERIFIED_FILE_APP,
            )
            advanceUntilIdle()

            assertEquals(
                GeneratedCleanupCompletionStep.entries.toSet(),
                snapshots.keys,
            )
            snapshots.forEach { (step, deathState) ->
                assertEquals(
                    "COMMIT_DESTINATION",
                    generatedCleanupPhase(deathState),
                    "step=$step",
                )
                assertEquals(
                    "REPORT",
                    generatedCleanupDestinationKind(deathState),
                    "step=$step",
                )
                assertEquals(
                    PACKAGE,
                    generatedCleanupDestinationPackage(deathState),
                    "step=$step",
                )
                assertGeneratedCleanupCompletionSnapshot(step, deathState)

                val recreatedRepository = currentGeneratedInstallRepository().apply {
                    restoredConversion = null
                }
                val recreated = ManagerViewModel(recreatedRepository, deathState)
                advanceUntilIdle()

                assertEquals(
                    ManagerRoute.Report(PACKAGE),
                    recreated.state.value.route,
                    "step=$step",
                )
                assertEquals(
                    TOKEN,
                    recreated.state.value.destinationCommitToken,
                    "step=$step",
                )
                assertEquals(
                    SamsungInternetSupportStatus.UNVERIFIED_FILE_APP,
                    recreated.state.value.reportInstallSupportStatus,
                    "step=$step",
                )
                assertEquals(0, recreatedRepository.cleanupRequestCount(), "step=$step")
                assertNotNull(deathState.get<String>(CLEANUP_JOURNAL_KEY), "step=$step")
            }
        }

    @Test
    fun `destination presented ACK는 route mismatch와 stale token을 무시하고 exact만 commit한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val savedState = SavedStateHandle()
            var routeMismatchPreserved = false
            lateinit var viewModel: ManagerViewModel
            viewModel = ManagerViewModel(
                repository = repository,
                savedStateHandle = savedState,
                afterGeneratedCleanupCompletionStep = { step ->
                    if (step == GeneratedCleanupCompletionStep.CONVERSION_OWNER_CLEARED) {
                        assertEquals(
                            ManagerRoute.ConversionResult(TOKEN),
                            viewModel.state.value.route,
                        )
                        viewModel.accept(
                            ManagerAction.GeneratedCleanupDestinationPresented(TOKEN),
                        )
                        routeMismatchPreserved =
                            savedState.get<String>(CLEANUP_JOURNAL_KEY) != null
                    }
                },
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)

            repository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()

            assertTrue(routeMismatchPreserved)
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertEquals(TOKEN, viewModel.state.value.destinationCommitToken)
            assertNotNull(savedState.get<String>(CLEANUP_JOURNAL_KEY))

            viewModel.accept(
                ManagerAction.GeneratedCleanupDestinationPresented(SECOND_TOKEN),
            )

            assertNotNull(savedState.get<String>(CLEANUP_JOURNAL_KEY))
            assertEquals(TOKEN, viewModel.state.value.destinationCommitToken)

            viewModel.accept(
                ManagerAction.GeneratedCleanupDestinationPresented(TOKEN),
            )

            assertNull(savedState.get<String>(CLEANUP_JOURNAL_KEY))
            assertNull(viewModel.state.value.destinationCommitToken)
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
        }

    @Test
    fun `destination presented 전 현재 목적지 사용자 동작은 ACK 뒤 실행한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()
            assertEquals(TOKEN, viewModel.state.value.destinationCommitToken)
            assertNotNull(savedState.get<String>(CLEANUP_JOURNAL_KEY))

            viewModel.accept(ManagerAction.ReturnHome)

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(viewModel.state.value.destinationCommitToken)
            assertNull(savedState.get<String>(CLEANUP_JOURNAL_KEY))
        }

    @Test
    fun `stale pending X는 current Result Y와 destination을 오염시키지 않는다`() =
        runTest(dispatcher.scheduler) {
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardConversionResult = false
            }
            val savedState = SavedStateHandle()
            val source = ManagerViewModel(sourceRepository, savedState)
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            sourceRepository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()
            assertEquals(
                "CONVERSION",
                generatedCleanupPhase(savedState),
            )

            savedState[ManagerViewModel.CONVERSION_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.CONVERSION_ROUTE_KEY] = "result"
            savedState[ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.REVIEW_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY] = null
            val currentRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                    generatedBundle(SECOND_TOKEN),
                )
            }
            val recreated = ManagerViewModel(currentRepository, savedState)
            advanceUntilIdle()

            recreated.accept(ManagerAction.FinishConversion)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "discardConversion:$TOKEN",
                    "discardReview:$SECOND_TOKEN",
                    "discardConversion:$SECOND_TOKEN",
                ),
                currentRepository.lifecycleEvents,
            )
            assertEquals(ManagerRoute.Home, recreated.state.value.route)
            assertNull(savedState.get<String>(ManagerViewModel.GENERATED_CLEANUP_TOKEN_KEY))
        }

    @Test
    fun `stale pending X reconciliation 실패는 current Result Y owner를 변경하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardConversionResult = false
            }
            val savedState = SavedStateHandle()
            val source = ManagerViewModel(sourceRepository, savedState)
            advanceUntilIdle()
            source.accept(ManagerAction.FinishConversion)
            advanceUntilIdle()

            savedState[ManagerViewModel.CONVERSION_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.CONVERSION_ROUTE_KEY] = "result"
            savedState[ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.REVIEW_TOKEN_KEY] = SECOND_TOKEN
            val currentRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                    generatedBundle(SECOND_TOKEN),
                )
                discardConversionResults[TOKEN] = false
            }
            val recreated = ManagerViewModel(currentRepository, savedState)
            advanceUntilIdle()

            assertEquals(
                listOf("discardConversion:$TOKEN"),
                currentRepository.lifecycleEvents,
            )
            assertEquals(
                ManagerRoute.ConversionResult(SECOND_TOKEN),
                recreated.state.value.route,
            )
            assertEquals(
                SECOND_TOKEN,
                assertIs<ConversionUiState.ResultReady>(
                    recreated.state.value.conversionState,
                ).token,
            )
            assertEquals(
                SECOND_TOKEN,
                savedState.get<String>(ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY),
            )
            assertEquals(
                SECOND_TOKEN,
                savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY),
            )
            assertNull(savedState.get<String>(ManagerViewModel.GENERATED_CLEANUP_TOKEN_KEY))
        }

    @Test
    fun `quarantine cross owner overlap은 current review와 session을 건드리지 않는다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle()
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val source = ManagerViewModel(sourceRepository, savedState)
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            sourceRepository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            source.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            rewriteGeneratedCleanupReviewToken(savedState, SECOND_TOKEN)
            savedState[ManagerViewModel.CONVERSION_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.CONVERSION_ROUTE_KEY] = "result"
            savedState[ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.REVIEW_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY] = SECOND_TOKEN
            val currentRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                    installSessionId = 41,
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                    generatedBundle(SECOND_TOKEN),
                )
                installFlow.value = InstallState.AwaitingUserApproval(41)
            }

            val recreated = ManagerViewModel(currentRepository, savedState)
            advanceUntilIdle()

            assertEquals(0, currentRepository.cancelInstallRequests)
            assertTrue(currentRepository.discardAttempts.isEmpty())
            assertEquals(listOf(TOKEN), currentRepository.discardedConversions)
            assertEquals(
                ManagerRoute.ConversionResult(SECOND_TOKEN),
                recreated.state.value.route,
            )
            assertNull(savedState.get<String>(ManagerViewModel.GENERATED_CLEANUP_TOKEN_KEY))
            assertEquals(
                null,
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY,
                ),
            )
            assertEquals(
                null,
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_PHASE_KEY,
                ),
            )
            assertEquals(
                null,
                savedState.get<Int>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY,
                ),
            )
            assertEquals(
                null,
                savedState.get<Boolean>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY,
                ),
            )
        }

    @Test
    fun `startup blocked 동안 restored quarantine은 어떤 cleanup mutation도 시작하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY to TOKEN,
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_PHASE_KEY to "REVIEW",
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY to 41,
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY to false,
                ),
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(recoveryBlocked = true)
            }

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
            assertTrue(repository.discardAttempts.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
            assertEquals(
                TOKEN,
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY,
                ),
            )
            assertEquals(
                "REVIEW",
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_PHASE_KEY,
                ),
            )
        }

    @Test
    fun `quarantine reconcile은 initialize가 복원한 durable handoff owner를 침범하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val initializeResult = CompletableDeferred<ManagerRestoreResult>()
            val handoff = NormalInstallHandoff(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Cancelled,
                stage = NormalHandoffStage.OUTCOME_READY,
            )
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY to SECOND_TOKEN,
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_PHASE_KEY to "REVIEW",
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY to false,
                ),
            )
            val repository = FakeManagerRepository().apply {
                initializeDeferred = initializeResult
                normalHandoff = handoff
            }

            val viewModel = ManagerViewModel(repository, savedState)
            dispatcher.scheduler.runCurrent()

            assertEquals(
                StartupRecoveryUiState.INITIALIZING,
                viewModel.state.value.startupRecoveryState,
            )
            assertTrue(repository.discardAttempts.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())

            initializeResult.complete(ManagerRestoreResult(normalHandoff = handoff))
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.READY,
                viewModel.state.value.startupRecoveryState,
            )
            assertTrue(repository.discardAttempts.isEmpty())
            assertEquals(listOf(SECOND_TOKEN), repository.discardedConversions)
        }

    @Test
    fun `wrong type quarantine marker는 원본을 보존하고 fail closed한다`() =
        runTest(dispatcher.scheduler) {
            val wrongToken: Any = 7
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY to wrongToken,
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_PHASE_KEY to "CONVERSION",
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY to false,
                ),
            )
            val repository = FakeManagerRepository()

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(
                wrongToken,
                savedState.get<Any>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY,
                ),
            )
            assertTrue(repository.discardAttempts.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
        }

    @Test
    fun `wrong type quarantine marker와 cleanup journal 공존 시 원본을 덮지 않고 fail closed한다`() =
        runTest(dispatcher.scheduler) {
            val wrongToken: Any = 7
            val cleanupJournal = """
                {
                  "version": 1,
                  "token": "$SECOND_TOKEN",
                  "reviewToken": null,
                  "phase": "CONVERSION",
                  "destination": {
                    "kind": "HOME",
                    "packageName": null
                  },
                  "installSessionId": null,
                  "autoReplayPending": false
                }
            """.trimIndent()
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY to wrongToken,
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_PHASE_KEY to "CONVERSION",
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY to false,
                    CLEANUP_JOURNAL_KEY to cleanupJournal,
                ),
            )
            val repository = FakeManagerRepository()

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
            assertEquals(
                wrongToken,
                savedState.get<Any>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY,
                ),
            )
            assertEquals(cleanupJournal, savedState.get<String>(CLEANUP_JOURNAL_KEY))
            assertTrue(repository.discardAttempts.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
        }

    @Test
    fun `quarantine failure survives recreation 뒤 safe conversion만 다시 시도한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle()
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val source = ManagerViewModel(sourceRepository, savedState)
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            sourceRepository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            source.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            rewriteGeneratedCleanupReviewToken(savedState, SECOND_TOKEN)
            savedState[ManagerViewModel.CONVERSION_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.CONVERSION_ROUTE_KEY] = "result"
            savedState[ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.REVIEW_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY] = SECOND_TOKEN
            val failingRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                    installSessionId = 41,
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                    generatedBundle(SECOND_TOKEN),
                )
                installFlow.value = InstallState.AwaitingUserApproval(41)
                discardConversionResults[TOKEN] = false
            }
            ManagerViewModel(failingRepository, savedState)
            advanceUntilIdle()

            assertEquals(listOf("discardConversion:$TOKEN"), failingRepository.lifecycleEvents)
            assertEquals(
                TOKEN,
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY,
                ),
            )
            assertEquals(
                false,
                savedState.get<Boolean>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY,
                ),
            )
            assertEquals(
                "CONVERSION",
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_PHASE_KEY,
                ),
            )
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_REVIEW_TOKEN_KEY,
                ),
            )
            assertNull(
                savedState.get<Int>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY,
                ),
            )

            val retryRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                    installSessionId = 41,
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                    generatedBundle(SECOND_TOKEN),
                )
                installFlow.value = InstallState.AwaitingUserApproval(41)
            }
            val recreated = ManagerViewModel(retryRepository, savedState)
            advanceUntilIdle()

            assertEquals(listOf("discardConversion:$TOKEN"), retryRepository.lifecycleEvents)
            assertEquals(0, retryRepository.cancelInstallRequests)
            assertTrue(retryRepository.discardAttempts.isEmpty())
            assertEquals(
                ManagerRoute.ConversionResult(SECOND_TOKEN),
                recreated.state.value.route,
            )
            assertEquals(
                null,
                savedState.get<Boolean>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY,
                ),
            )
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY,
                ),
            )
        }

    @Test
    fun `quarantine current conversion owner는 conversion을 discard하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle()
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardReviewResult = false
            }
            val source = ManagerViewModel(sourceRepository, savedState)
            advanceUntilIdle()
            source.accept(ManagerAction.FinishConversion)
            advanceUntilIdle()

            savedState[ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY] = SECOND_TOKEN
            savedState[ManagerViewModel.REVIEW_TOKEN_KEY] = SECOND_TOKEN
            val currentRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(
                        ContractCompatibilityStatus.COMPATIBLE,
                        token = SECOND_TOKEN,
                    ),
                    generatedBundle(),
                )
            }

            val recreated = ManagerViewModel(currentRepository, savedState)
            advanceUntilIdle()

            assertEquals(listOf(TOKEN), currentRepository.discardAttempts)
            assertTrue(currentRepository.discardedConversions.isEmpty())
            assertEquals(
                TOKEN,
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY,
                ),
            )
            assertEquals(
                "CONVERSION",
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_PHASE_KEY,
                ),
            )
            assertEquals(
                false,
                savedState.get<Boolean>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY,
                ),
            )
            assertEquals(
                ManagerRoute.ConversionResult(TOKEN),
                recreated.state.value.route,
            )
        }

    @Test
    fun `generated active install 취소는 install job과 coordinator를 먼저 정리한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                suspendInstall = true
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            dispatcher.scheduler.runCurrent()
            repository.installFlow.value = InstallState.WritingSession(40)
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            assertEquals(
                listOf(
                    "install:start",
                    "install:end",
                    "cancelInstall",
                    "discardReview:$TOKEN",
                    "discardConversion:$TOKEN",
                ),
                repository.lifecycleEvents,
            )
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals("COMMIT_DESTINATION", generatedCleanupPhase(savedState))
            assertEquals("HOME", generatedCleanupDestinationKind(savedState))
            assertEquals(TOKEN, viewModel.state.value.destinationCommitToken)
        }

    @Test
    fun `generated active install cancel 거부는 review와 conversion owner를 보존한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            assertEquals(1, repository.cancelInstallRequests)
            assertTrue(repository.discardedConversions.isEmpty())
            assertTrue(repository.discardedTokens.isEmpty())
            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertIs<ConversionUiState.ResultReady>(viewModel.state.value.conversionState)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
        }

    @Test
    fun `pending INSTALL Retry는 cancelInstall을 다시 호출하고 cleanup을 완료한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()
            repository.cancelInstallResult = true

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(2, repository.cancelInstallRequests)
            assertEquals(
                listOf(
                    "install:start",
                    "install:end",
                    "cancelInstall",
                    "cancelInstall",
                    "discardReview:$TOKEN",
                    "discardConversion:$TOKEN",
                ),
                repository.lifecycleEvents,
            )
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        }

    @Test
    fun `torn legacy cleanup은 current owner를 보존하고 retry를 차단한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                mapOf<String, Any?>(
                    ManagerViewModel.GENERATED_CLEANUP_TOKEN_KEY to TOKEN,
                    ManagerViewModel.GENERATED_CLEANUP_REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.GENERATED_CLEANUP_PHASE_KEY to "REVIEW",
                    ManagerViewModel.GENERATED_CLEANUP_DESTINATION_KEY to "HOME",
                    ManagerViewModel.GENERATED_CLEANUP_PACKAGE_KEY to null,
                    ManagerViewModel.GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY to 41,
                ),
                mapOf<String, Any?>(
                    ManagerViewModel.GENERATED_CLEANUP_TOKEN_KEY to TOKEN,
                    ManagerViewModel.GENERATED_CLEANUP_REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.GENERATED_CLEANUP_PHASE_KEY to "REVIEW",
                    ManagerViewModel.GENERATED_CLEANUP_DESTINATION_KEY to "REPORT",
                    ManagerViewModel.GENERATED_CLEANUP_PACKAGE_KEY to null,
                    ManagerViewModel.GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY to 41,
                ),
            ).forEach { legacy ->
                val savedState = generatedInstallSavedState(legacy)
                val repository = currentGeneratedInstallRepository()
                val viewModel = ManagerViewModel(repository, savedState)
                advanceUntilIdle()

                val messageKind = viewModel.state.value.message?.kind
                viewModel.accept(ManagerAction.RetryGeneratedCleanup)
                advanceUntilIdle()

                assertEquals(
                    TOKEN,
                    savedState.get<String>(
                        ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                    ),
                )
                assertEquals(UserMessageKind.CONVERSION_CLEANUP_FAILED, messageKind)
                assertEquals(
                    ManagerRoute.Home,
                    viewModel.state.value.route,
                )
                assertEquals(41, savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))
                assertEquals(0, repository.cancelInstallRequests)
                assertTrue(repository.discardedTokens.isEmpty())
                assertTrue(repository.discardedConversions.isEmpty())
            }
        }

    @Test
    fun `single cleanup journal Installed supersede는 persistence key 하나만 commit한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle()
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            var persistedSnapshot: SavedStateHandle? = null
            val viewModel = ManagerViewModel(
                repository = repository,
                savedStateHandle = savedState,
                afterGeneratedInstallCleanupTransitionPersisted = {
                    persistedSnapshot = generatedCleanupDeathSnapshot(savedState)
                },
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()
            val before = generatedCleanupPersistenceValues(savedState)

            repository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()

            val deathState = requireNotNull(persistedSnapshot)
            val after = generatedCleanupPersistenceValues(deathState)
            assertEquals(
                setOf(CLEANUP_JOURNAL_KEY),
                after.keys.filterTo(mutableSetOf()) { before[it] != after[it] },
            )
            val journal = Json.parseToJsonElement(
                requireNotNull(deathState.get<String>(CLEANUP_JOURNAL_KEY)),
            ).jsonObject
            assertEquals(1, journal.getValue("version").jsonPrimitive.int)
            assertEquals("REVIEW", journal.getValue("phase").jsonPrimitive.content)
            val destination = journal.getValue("destination").jsonObject
            assertEquals("REPORT", destination.getValue("kind").jsonPrimitive.content)
            assertEquals(PACKAGE, destination.getValue("packageName").jsonPrimitive.content)
            assertEquals(
                TOKEN,
                deathState.get<String>(ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY),
            )
            assertEquals(41, deathState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))

            val recreatedRepository = currentGeneratedInstallRepository().apply {
                installFlow.value = InstallState.Installed(PACKAGE)
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = InstallState.Installed(PACKAGE)
                terminalOwnerProofConfigured = true
                terminalReceiptActive = true
            }
            val recreated = ManagerViewModel(recreatedRepository, deathState)
            advanceUntilIdle()
            recreated.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), recreated.state.value.route)
        }

    @Test
    fun `single cleanup journal INSTALL cancel transition은 exact HOME을 복원한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle()
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val source = ManagerViewModel(sourceRepository, savedState)
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            sourceRepository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            source.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            val before = generatedCleanupPersistenceValues(savedState)
            val dying = ManagerViewModel(
                repository = currentGeneratedInstallRepository(),
                savedStateHandle = savedState,
                afterGeneratedInstallCleanupTransitionPersisted = {
                    error("simulated process death")
                },
            )
            advanceUntilIdle()
            dying.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            val after = generatedCleanupPersistenceValues(savedState)
            assertEquals(
                setOf(CLEANUP_JOURNAL_KEY),
                after.keys.filterTo(mutableSetOf()) { before[it] != after[it] },
            )
            val journal = Json.parseToJsonElement(
                requireNotNull(savedState.get<String>(CLEANUP_JOURNAL_KEY)),
            ).jsonObject
            assertEquals("REVIEW", journal.getValue("phase").jsonPrimitive.content)
            assertEquals(
                "HOME",
                journal.getValue("destination").jsonObject
                    .getValue("kind").jsonPrimitive.content,
            )
            assertEquals(
                TOKEN,
                savedState.get<String>(ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY),
            )

            val recreated = ManagerViewModel(currentGeneratedInstallRepository(), savedState)
            advanceUntilIdle()
            recreated.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, recreated.state.value.route)
        }

    @Test
    fun `invalid cleanup journal은 valid legacy fallback 없이 owner를 보존한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                "{broken",
                """{"version":99}""",
            ).forEach { invalidJournal ->
                val legacy = mapOf<String, Any?>(
                    ManagerViewModel.GENERATED_CLEANUP_TOKEN_KEY to TOKEN,
                    ManagerViewModel.GENERATED_CLEANUP_REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.GENERATED_CLEANUP_PHASE_KEY to "INSTALL",
                    ManagerViewModel.GENERATED_CLEANUP_DESTINATION_KEY to "HOME",
                    ManagerViewModel.GENERATED_CLEANUP_PACKAGE_KEY to null,
                    ManagerViewModel.GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY to 41,
                    CLEANUP_JOURNAL_KEY to invalidJournal,
                )
                val savedState = generatedInstallSavedState(legacy)
                val repository = currentGeneratedInstallRepository()
                val viewModel = ManagerViewModel(repository, savedState)
                advanceUntilIdle()

                assertEquals(
                    UserMessageKind.CONVERSION_CLEANUP_FAILED,
                    viewModel.state.value.message?.kind,
                )
                viewModel.accept(ManagerAction.RetryGeneratedCleanup)
                advanceUntilIdle()

                assertEquals(invalidJournal, savedState.get<String>(CLEANUP_JOURNAL_KEY))
                assertEquals(
                    TOKEN,
                    savedState.get<String>(
                        ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                    ),
                )
                assertEquals(0, repository.cancelInstallRequests)
                assertTrue(repository.discardedTokens.isEmpty())
                assertTrue(repository.discardedConversions.isEmpty())
            }
        }

    @Test
    fun `invalid cleanup journal restore null은 모든 owner와 session을 보존한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = currentGeneratedInstallSavedState(
                cleanupJournal = """{"version":99}""",
            )
            val repository = currentGeneratedInstallRepository().apply {
                restoredConversion = null
            }

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertGeneratedOwnersAndSessionPreserved(savedState)
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(RecoveryAction.NONE, viewModel.state.value.message?.recoveryAction)
            assertEquals(0, repository.cancelInstallRequests)
            assertEquals(0, repository.restoreConversionRequests)
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
        }

    @Test
    fun `invalid cleanup journal restore exception은 모든 owner와 session을 보존한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = currentGeneratedInstallSavedState(
                cleanupJournal = """{"version":1,"phase":"BROKEN"}""",
            )
            val repository = currentGeneratedInstallRepository().apply {
                restoreConversionFailure = IOException("transient")
            }

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertGeneratedOwnersAndSessionPreserved(savedState)
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(RecoveryAction.NONE, viewModel.state.value.message?.recoveryAction)
            assertEquals(0, repository.restoreConversionRequests)
            assertEquals(0, repository.cleanupRequestCount())
        }

    @Test
    fun `invalid cleanup journal initialize exception은 모든 owner와 session을 보존한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = currentGeneratedInstallSavedState(
                cleanupJournal = "{broken",
            )
            val repository = currentGeneratedInstallRepository().apply {
                initializeException = IllegalStateException("transient")
            }

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertGeneratedOwnersAndSessionPreserved(savedState)
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(RecoveryAction.NONE, viewModel.state.value.message?.recoveryAction)
            assertEquals(0, repository.restoreConversionRequests)
            assertEquals(0, repository.cleanupRequestCount())
        }

    @Test
    fun `invalid cleanup journal은 restored quarantine과 repository artifact를 보존한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = currentGeneratedInstallSavedState(
                cleanupJournal = "{broken",
            ).apply {
                this[ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY] = SECOND_TOKEN
                this[ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_PHASE_KEY] = "CONVERSION"
                this[
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY
                ] = false
            }
            val repository = currentGeneratedInstallRepository()

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertGeneratedOwnersAndSessionPreserved(savedState)
            assertGeneratedCleanupRestoreBlocked(viewModel)
            assertEquals(0, repository.cleanupRequestCount())
            assertTrue(repository.generatedExists)
            assertEquals(
                SECOND_TOKEN,
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY,
                ),
            )
            assertEquals(
                "CONVERSION",
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_PHASE_KEY,
                ),
            )
            assertEquals(
                false,
                savedState.get<Boolean>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_CONVERSION_CLEANED_KEY,
                ),
            )
        }

    @Test
    fun `invalid cleanup journal은 initialize 성공과 exception 모두 install observer를 차단한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                "initialize success" to null,
                "initialize exception" to IllegalStateException("transient"),
            ).forEach { (caseName, initializeFailure) ->
                val savedState = currentGeneratedInstallSavedState(
                    cleanupJournal = "{broken",
                )
                val repository = currentGeneratedInstallRepository().apply {
                    initializeException = initializeFailure
                }
                val viewModel = ManagerViewModel(repository, savedState)
                val effects = mutableListOf<ManagerEffect>()
                backgroundScope.launch {
                    viewModel.effects.collect(effects::add)
                }
                advanceUntilIdle()

                assertGeneratedOwnersAndSessionPreserved(savedState)
                assertGeneratedCleanupRestoreBlocked(viewModel)

                repository.installFlow.value = InstallState.AwaitingUserApproval(99)
                repository.emitLaunch(
                    LaunchDestination.UNKNOWN_SOURCES_SETTINGS,
                    installSessionId = 99,
                )
                advanceUntilIdle()

                assertGeneratedOwnersAndSessionPreserved(savedState)
                assertGeneratedCleanupRestoreBlocked(viewModel)

                repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
                advanceUntilIdle()

                assertGeneratedOwnersAndSessionPreserved(savedState)
                assertGeneratedCleanupRestoreBlocked(viewModel)

                listOf(
                    InstallState.Installed(PACKAGE),
                    InstallState.Cancelled,
                    InstallState.Failed("WRITE_FAILED", "terminal"),
                ).forEach { terminal ->
                    repository.installFlow.value = terminal
                    advanceUntilIdle()

                    assertGeneratedOwnersAndSessionPreserved(savedState)
                    assertGeneratedCleanupRestoreBlocked(viewModel)
                }
                assertTrue(effects.isEmpty(), "case=$caseName, effects=$effects")
                assertEquals(0, repository.cleanupRequestCount(), "case=$caseName")
            }
        }

    @Test
    fun `single cleanup journal absent valid legacy는 migration 뒤 old keys를 지운다`() =
        runTest(dispatcher.scheduler) {
            val legacy = mapOf<String, Any?>(
                ManagerViewModel.GENERATED_CLEANUP_TOKEN_KEY to TOKEN,
                ManagerViewModel.GENERATED_CLEANUP_REVIEW_TOKEN_KEY to TOKEN,
                ManagerViewModel.GENERATED_CLEANUP_PHASE_KEY to "INSTALL",
                ManagerViewModel.GENERATED_CLEANUP_DESTINATION_KEY to "HOME",
                ManagerViewModel.GENERATED_CLEANUP_PACKAGE_KEY to null,
                ManagerViewModel.GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY to 41,
            )
            val savedState = generatedInstallSavedState(legacy)
            ManagerViewModel(currentGeneratedInstallRepository(), savedState)
            advanceUntilIdle()

            val journal = Json.parseToJsonElement(
                requireNotNull(savedState.get<String>(CLEANUP_JOURNAL_KEY)),
            ).jsonObject
            assertEquals(1, journal.getValue("version").jsonPrimitive.int)
            assertEquals("INSTALL", journal.getValue("phase").jsonPrimitive.content)
            generatedCleanupLegacyKeys().forEach { key ->
                assertNull(savedState.get<Any>(key), "key=$key")
            }
        }

    @Test
    fun `pending INSTALL same session terminal은 cancel 재호출 없이 destination을 supersede한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                InstallState.Installed(PACKAGE) to ManagerRoute.Report(PACKAGE),
                InstallState.Cancelled to ManagerRoute.Home,
                InstallState.Failed("WRITE_FAILED", "terminal") to ManagerRoute.Home,
            ).forEach { (terminal, expectedRoute) ->
                val repository = FakeManagerRepository().apply {
                    restoredConversion = RestoredConversion.BundleReady(
                        review(ContractCompatibilityStatus.COMPATIBLE),
                        generatedBundle(),
                    )
                    cancelInstallResult = false
                }
                val savedState = SavedStateHandle()
                val viewModel = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.PrepareGeneratedInstall)
                repository.installFlow.value = InstallState.AwaitingUserApproval(41)
                dispatcher.scheduler.runCurrent()
                viewModel.accept(ManagerAction.CancelConversion)
                dispatcher.scheduler.runCurrent()
                assertEquals(
                    "INSTALL",
                    generatedCleanupPhase(savedState),
                )

                repository.installFlow.value = terminal
                advanceUntilIdle()

                assertEquals(expectedRoute, viewModel.state.value.route)
                assertEquals(1, repository.cancelInstallRequests)
                assertEquals(listOf(TOKEN), repository.discardedConversions)
                assertEquals(listOf(TOKEN), repository.discardedTokens)
            }
        }

    @Test
    fun `pending INSTALL cancel 성공 phase commit 직후 process death는 recreation에서 완료한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle()
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val source = ManagerViewModel(sourceRepository, savedState)
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            sourceRepository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            source.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            val dyingRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    installSessionId = 41,
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                installFlow.value = InstallState.AwaitingUserApproval(41)
            }
            val dying = ManagerViewModel(
                repository = dyingRepository,
                savedStateHandle = savedState,
                afterGeneratedInstallCleanupTransitionPersisted = {
                    error("simulated process death")
                },
            )
            advanceUntilIdle()
            dying.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(
                "REVIEW",
                generatedCleanupPhase(savedState),
            )
            assertEquals(
                TOKEN,
                savedState.get<String>(ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY),
            )
            assertEquals(41, savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))

            val recreatedRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    installSessionId = null,
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                installFlow.value = InstallState.Idle
                terminalReceiptActive = false
            }
            val recreated = ManagerViewModel(recreatedRepository, savedState)
            advanceUntilIdle()
            recreated.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, recreated.state.value.route)
            assertEquals(listOf(TOKEN), recreatedRepository.discardedTokens)
            assertEquals(listOf(TOKEN), recreatedRepository.discardedConversions)
        }

    @Test
    fun `INSTALL transition 직후 process death는 tombstone owner와 HOME을 유지한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
                discardReviewResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()
            repository.cancelInstallResult = true
            var deathState: SavedStateHandle? = null
            repository.beforeDiscardReview = {
                deathState = generatedCleanupDeathSnapshot(savedState)
            }

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            val snapshot = requireNotNull(deathState)
            assertEquals("REVIEW", generatedCleanupPhase(snapshot))
            assertEquals("HOME", generatedCleanupDestinationKind(snapshot))
            assertGeneratedInstallOwnerCleared(snapshot)

            val recreatedRepository = currentGeneratedInstallRepository().apply {
                installFlow.value = InstallState.Installed(PACKAGE)
            }
            val recreated = ManagerViewModel(recreatedRepository, snapshot)
            advanceUntilIdle()

            assertEquals(
                ManagerRoute.ConversionResult(TOKEN),
                recreated.state.value.route,
            )
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                recreated.state.value.message?.kind,
            )
            assertEquals(
                RecoveryAction.RETRY,
                recreated.state.value.message?.recoveryAction,
            )
            assertEquals(0, recreatedRepository.cancelInstallRequests)
            assertTrue(recreatedRepository.discardedTokens.isEmpty())
            assertTrue(recreatedRepository.discardedConversions.isEmpty())
            assertEquals("REVIEW", generatedCleanupPhase(snapshot))
            assertGeneratedInstallOwnerCleared(snapshot)

            recreated.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, recreated.state.value.route)
            assertEquals(TOKEN, recreated.state.value.destinationCommitToken)
            assertEquals(listOf(TOKEN), recreatedRepository.discardedTokens)
            assertEquals(listOf(TOKEN), recreatedRepository.discardedConversions)
        }

    @Test
    fun `pending REVIEW initialize 전 conversion action은 무시하고 Retry 화면을 복원한다`() =
        runTest(dispatcher.scheduler) {
            val initializeResult = CompletableDeferred<ManagerRestoreResult>()
            val savedState = pendingReviewReportSavedState()
            val journalBefore = savedState.get<String>(CLEANUP_JOURNAL_KEY)
            val repository = currentGeneratedInstallRepository().apply {
                initializeDeferred = initializeResult
                installFlow.value = InstallState.Idle
                restoreResult = restoreResult.copy(installSessionId = null)
                terminalReceiptActive = false
            }
            val viewModel = ManagerViewModel(repository, savedState)
            dispatcher.scheduler.runCurrent()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
            viewModel.accept(ManagerAction.OpenConversionInput)
            dispatcher.scheduler.runCurrent()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
            assertGeneratedOwnersAndSessionPreserved(savedState)
            assertEquals(journalBefore, savedState.get<String>(CLEANUP_JOURNAL_KEY))
            assertEquals("REVIEW", generatedCleanupPhase(savedState))
            assertEquals("REPORT", generatedCleanupDestinationKind(savedState))
            assertEquals(PACKAGE, generatedCleanupDestinationPackage(savedState))
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY,
                ),
            )
            assertEquals(0, repository.cleanupRequestCount())

            initializeResult.complete(repository.restoreResult)
            advanceUntilIdle()

            assertEquals(
                ManagerRoute.ConversionResult(TOKEN),
                viewModel.state.value.route,
            )
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(
                RecoveryAction.RETRY,
                viewModel.state.value.message?.recoveryAction,
            )
            assertEquals(
                TOKEN,
                assertIs<ConversionUiState.ResultReady>(
                    viewModel.state.value.conversionState,
                ).token,
            )
            assertGeneratedInstallOwnerCleared(savedState)
            assertEquals(journalBefore, savedState.get<String>(CLEANUP_JOURNAL_KEY))
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY,
                ),
            )
            assertEquals(0, repository.cleanupRequestCount())

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertEquals(TOKEN, viewModel.state.value.destinationCommitToken)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }

    @Test
    fun `pending REVIEW conversion restore 중 conversion action은 무시하고 Retry 화면을 유지한다`() =
        runTest(dispatcher.scheduler) {
            val conversionResult = CompletableDeferred<RestoredConversion?>()
            val savedState = pendingReviewReportSavedState()
            val journalBefore = savedState.get<String>(CLEANUP_JOURNAL_KEY)
            val repository = currentGeneratedInstallRepository().apply {
                restoreConversionDeferred = conversionResult
                installFlow.value = InstallState.Idle
                restoreResult = restoreResult.copy(installSessionId = null)
                terminalReceiptActive = false
            }
            val viewModel = ManagerViewModel(repository, savedState)
            dispatcher.scheduler.runCurrent()
            assertEquals(1, repository.restoreConversionRequests)

            viewModel.accept(ManagerAction.OpenConversionInput)
            dispatcher.scheduler.runCurrent()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(viewModel.state.value.message)
            assertGeneratedInstallOwnerCleared(savedState)
            assertEquals(journalBefore, savedState.get<String>(CLEANUP_JOURNAL_KEY))
            assertEquals(0, repository.cleanupRequestCount())

            conversionResult.complete(repository.restoredConversion)
            advanceUntilIdle()

            assertEquals(
                ManagerRoute.ConversionResult(TOKEN),
                viewModel.state.value.route,
            )
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(
                RecoveryAction.RETRY,
                viewModel.state.value.message?.recoveryAction,
            )
            assertGeneratedInstallOwnerCleared(savedState)
            assertEquals(journalBefore, savedState.get<String>(CLEANUP_JOURNAL_KEY))
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.QUARANTINED_GENERATED_CLEANUP_TOKEN_KEY,
                ),
            )
            assertEquals(0, repository.cleanupRequestCount())

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertEquals(TOKEN, viewModel.state.value.destinationCommitToken)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }

    @Test
    fun `pending REVIEW initialize persistent exception은 journal을 보존하고 fail closed한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = pendingReviewReportSavedState()
            val journalBefore = savedState.get<String>(CLEANUP_JOURNAL_KEY)
            val repository = currentGeneratedInstallRepository().apply {
                initializeException = IllegalStateException("transient")
            }
            val viewModel = ManagerViewModel(repository, savedState)

            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals(
                UserMessageKind.RECOVERY_BLOCKED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(
                RecoveryAction.NONE,
                viewModel.state.value.message?.recoveryAction,
            )
            assertGeneratedOwnersAndSessionPreserved(savedState)
            assertEquals(journalBefore, savedState.get<String>(CLEANUP_JOURNAL_KEY))
            assertEquals(0, repository.cleanupRequestCount())
            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
        }

    @Test
    fun `pending REVIEW restore null과 exception은 Home Retry로 exact Report를 완료한다`() =
        runTest(dispatcher.scheduler) {
            listOf("null", "exception").forEach { caseName ->
                val savedState = pendingReviewReportSavedState()
                val journalBefore = savedState.get<String>(CLEANUP_JOURNAL_KEY)
                val repository = currentGeneratedInstallRepository().apply {
                    restoredConversion = null
                    installFlow.value = InstallState.Idle
                    restoreResult = restoreResult.copy(installSessionId = null)
                    terminalReceiptActive = false
                    restoreConversionFailure = if (caseName == "exception") {
                        IOException("transient")
                    } else {
                        null
                    }
                }
                val viewModel = ManagerViewModel(repository, savedState)

                advanceUntilIdle()

                assertEquals(ManagerRoute.Home, viewModel.state.value.route, caseName)
                assertEquals(
                    UserMessageKind.CONVERSION_CLEANUP_FAILED,
                    viewModel.state.value.message?.kind,
                    caseName,
                )
                assertEquals(
                    RecoveryAction.RETRY,
                    viewModel.state.value.message?.recoveryAction,
                    caseName,
                )
                assertGeneratedInstallOwnerCleared(savedState)
                assertEquals(
                    journalBefore,
                    savedState.get<String>(CLEANUP_JOURNAL_KEY),
                    caseName,
                )
                assertEquals(0, repository.cleanupRequestCount(), caseName)

                viewModel.accept(ManagerAction.RetryGeneratedCleanup)
                advanceUntilIdle()

                assertEquals(
                    ManagerRoute.Report(PACKAGE),
                    viewModel.state.value.route,
                    caseName,
                )
                assertEquals(
                    TOKEN,
                    viewModel.state.value.destinationCommitToken,
                    caseName,
                )
                assertEquals(listOf(TOKEN), repository.discardedTokens, caseName)
                assertEquals(listOf(TOKEN), repository.discardedConversions, caseName)
            }
        }

    @Test
    fun `pending REVIEW initialize 전 import는 무시하고 exception 뒤 fail closed한다`() =
        runTest(dispatcher.scheduler) {
            val initializeResult = CompletableDeferred<ManagerRestoreResult>()
            val savedState = pendingReviewReportSavedState()
            val journalBefore = savedState.get<String>(CLEANUP_JOURNAL_KEY)
            val repository = currentGeneratedInstallRepository().apply {
                initializeDeferred = initializeResult
                cancelInstallResult = false
            }
            val viewModel = ManagerViewModel(repository, savedState)
            dispatcher.scheduler.runCurrent()

            viewModel.startImport {
                error("stale import operation must not start")
            }
            dispatcher.scheduler.runCurrent()
            assertNull(viewModel.state.value.message)
            assertGeneratedOwnersAndSessionPreserved(savedState)
            assertEquals(journalBefore, savedState.get<String>(CLEANUP_JOURNAL_KEY))

            initializeResult.completeExceptionally(
                IllegalStateException("late transient"),
            )
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals(
                UserMessageKind.RECOVERY_BLOCKED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(RecoveryAction.NONE, viewModel.state.value.message?.recoveryAction)
            assertGeneratedOwnersAndSessionPreserved(savedState)
            assertEquals(journalBefore, savedState.get<String>(CLEANUP_JOURNAL_KEY))
            assertEquals(0, repository.cancelInstallRequests)
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
            assertEquals(
                StartupRecoveryUiState.RECOVERY_BLOCKED,
                viewModel.state.value.startupRecoveryState,
            )
        }

    @Test
    fun `pending REVIEW initialize 중 CONVERSION 전환은 현재 phase로 복원한다`() =
        runTest(dispatcher.scheduler) {
            val initializeResult = CompletableDeferred<ManagerRestoreResult>()
            val savedState = pendingReviewReportSavedState()
            val repository = currentGeneratedInstallRepository().apply {
                initializeDeferred = initializeResult
                installFlow.value = InstallState.Idle
                restoreResult = restoreResult.copy(installSessionId = null)
                terminalReceiptActive = false
                beforeDiscardConversion = {
                    error("stop after conversion phase commit")
                }
            }
            val viewModel = ManagerViewModel(repository, savedState)
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            dispatcher.scheduler.runCurrent()

            assertEquals("REVIEW", generatedCleanupPhase(savedState))
            assertGeneratedOwnersAndSessionPreserved(savedState)
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())

            initializeResult.complete(repository.restoreResult)
            advanceUntilIdle()

            assertEquals(
                StartupRecoveryUiState.READY,
                viewModel.state.value.startupRecoveryState,
            )

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals("CONVERSION", generatedCleanupPhase(savedState))
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertEquals(listOf(true), repository.restoreConversionImportReviewRequests)
            assertEquals(
                null,
                assertIs<ConversionUiState.ResultReady>(
                    viewModel.state.value.conversionState,
                ).review,
            )
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(
                RecoveryAction.RETRY,
                viewModel.state.value.message?.recoveryAction,
            )
            assertEquals("CONVERSION", generatedCleanupPhase(savedState))
            assertGeneratedInstallOwnerCleared(savedState)
        }

    @Test
    fun `REVIEW discard 뒤 CONVERSION process death는 owner를 유지하고 cleanup만 replay한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardConversionResult = false
            }
            val savedState = SavedStateHandle()
            var conversionDeathState: SavedStateHandle? = null
            repository.beforeDiscardConversion = {
                conversionDeathState = generatedCleanupDeathSnapshot(savedState)
            }
            var reviewDeathState: SavedStateHandle? = null
            val source = ManagerViewModel(
                repository = repository,
                savedStateHandle = savedState,
                afterGeneratedReviewCleanupCompleted = {
                    reviewDeathState = generatedCleanupDeathSnapshot(savedState)
                },
            )
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()

            repository.installFlow.value = InstallState.Installed(
                PACKAGE,
                SamsungInternetSupportStatus.UNVERIFIED_FILE_APP,
            )
            advanceUntilIdle()

            val reviewSnapshot = requireNotNull(reviewDeathState)
            assertEquals("REVIEW", generatedCleanupPhase(reviewSnapshot))
            assertEquals("REPORT", generatedCleanupDestinationKind(reviewSnapshot))
            assertEquals(PACKAGE, generatedCleanupDestinationPackage(reviewSnapshot))
            assertGeneratedInstallOwnerCleared(reviewSnapshot)

            val conversionSnapshot = requireNotNull(conversionDeathState)
            assertEquals("CONVERSION", generatedCleanupPhase(conversionSnapshot))
            assertEquals("REPORT", generatedCleanupDestinationKind(conversionSnapshot))
            assertEquals(PACKAGE, generatedCleanupDestinationPackage(conversionSnapshot))
            assertEquals(
                TOKEN,
                generatedCleanupJournal(conversionSnapshot)
                    ?.get("reviewToken")
                    ?.jsonPrimitive
                    ?.contentOrNull,
            )
            assertGeneratedInstallOwnerCleared(conversionSnapshot)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)

            val completionSnapshots =
                linkedMapOf<GeneratedCleanupCompletionStep, SavedStateHandle>()
            val reviewRecreatedRepository = FakeManagerRepository().apply {
                installFlow.value = InstallState.Idle
                terminalReceiptActive = false
                restoredConversion = null
            }
            val reviewRecreated = ManagerViewModel(
                repository = reviewRecreatedRepository,
                savedStateHandle = reviewSnapshot,
                afterGeneratedCleanupCompletionStep = { step ->
                    completionSnapshots[step] =
                        generatedCleanupDeathSnapshot(reviewSnapshot)
                },
            )
            advanceUntilIdle()
            assertEquals("REVIEW", generatedCleanupPhase(reviewSnapshot))
            assertGeneratedInstallOwnerCleared(reviewSnapshot)

            reviewRecreated.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), reviewRecreated.state.value.route)
            assertEquals(
                SamsungInternetSupportStatus.UNVERIFIED_FILE_APP,
                reviewRecreated.state.value.reportInstallSupportStatus,
            )
            assertEquals(TOKEN, reviewRecreated.state.value.destinationCommitToken)
            assertEquals(0, reviewRecreatedRepository.cancelInstallRequests)
            assertEquals(listOf(TOKEN), reviewRecreatedRepository.discardedTokens)
            assertEquals(listOf(TOKEN), reviewRecreatedRepository.discardedConversions)
            assertEquals(
                GeneratedCleanupCompletionStep.entries.toSet(),
                completionSnapshots.keys,
            )
            completionSnapshots.forEach { (step, completionState) ->
                assertEquals(
                    "COMMIT_DESTINATION",
                    generatedCleanupPhase(completionState),
                    "step=$step",
                )
                assertGeneratedCleanupCompletionSnapshot(step, completionState)
            }

            val conversionRecreatedRepository = FakeManagerRepository().apply {
                installFlow.value = InstallState.Installed(
                    PACKAGE,
                    SamsungInternetSupportStatus.TRUSTED_INSTALL_SOURCE,
                )
                restoredConversion = null
            }
            val conversionRecreated = ManagerViewModel(
                conversionRecreatedRepository,
                conversionSnapshot,
            )
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), conversionRecreated.state.value.route)
            assertEquals(
                SamsungInternetSupportStatus.UNVERIFIED_FILE_APP,
                conversionRecreated.state.value.reportInstallSupportStatus,
            )
            assertEquals(TOKEN, conversionRecreated.state.value.destinationCommitToken)
            assertEquals(0, conversionRecreatedRepository.cancelInstallRequests)
            assertTrue(conversionRecreatedRepository.discardedTokens.isEmpty())
            assertEquals(
                listOf(TOKEN),
                conversionRecreatedRepository.discardedConversions,
            )
        }

    @Test
    fun `pending INSTALL cleanup retryable terminal은 exact session과 owner를 유지한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                "CLEANUP_FAILED",
                "ABANDON_FAILED",
                "SESSION_RECOVERY_FAILED",
                "MISSING_OBSERVATION_RESET_FAILED",
            ).forEach { code ->
                val repository = FakeManagerRepository().apply {
                    restoredConversion = RestoredConversion.BundleReady(
                        review(ContractCompatibilityStatus.COMPATIBLE),
                        generatedBundle(),
                    )
                    cancelInstallResult = false
                }
                val savedState = SavedStateHandle()
                val viewModel = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.PrepareGeneratedInstall)
                repository.installFlow.value = InstallState.AwaitingUserApproval(41)
                dispatcher.scheduler.runCurrent()
                viewModel.accept(ManagerAction.CancelConversion)
                dispatcher.scheduler.runCurrent()

                repository.installFlow.value = InstallState.Failed(code, "retryable")
                advanceUntilIdle()

                assertEquals(
                    "INSTALL",
                    generatedCleanupPhase(savedState),
                    "code=$code",
                )
                assertEquals(
                    41,
                    generatedCleanupInstallSession(savedState),
                    "code=$code",
                )
                assertEquals(
                    TOKEN,
                    savedState.get<String>(
                        ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                    ),
                    "code=$code",
                )
                assertEquals(1, repository.cancelInstallRequests, "code=$code")
                assertTrue(repository.discardedTokens.isEmpty(), "code=$code")
                assertTrue(repository.discardedConversions.isEmpty(), "code=$code")
                assertEquals(
                    ManagerRoute.ConversionResult(TOKEN),
                    viewModel.state.value.route,
                    "code=$code",
                )
            }
        }

    @Test
    fun `pending INSTALL cleanup은 exact install session을 durable 저장한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            assertEquals(
                41,
                generatedCleanupInstallSession(savedState),
            )
        }

    @Test
    fun `null-session INSTALL cancel 실패는 journal을 보존하고 Retry가 exact destination까지 전진한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)

            viewModel.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()

            assertEquals("INSTALL", generatedCleanupPhase(savedState))
            assertNull(generatedCleanupInstallSession(savedState))
            assertNull(savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))
            assertEquals(
                listOf(TOKEN to (null as Int?)),
                repository.cancelInstallArguments,
            )
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())

            repository.cancelInstallResult = true
            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertEquals(
                listOf(TOKEN to (null as Int?), TOKEN to (null as Int?)),
                repository.cancelInstallArguments,
            )
        }

    @Test
    fun `null-session INSTALL repository owner 불일치는 journal과 owner를 보존한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallOutcome = InstallCleanupResult.OwnerMismatch
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)

            viewModel.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()

            assertEquals("INSTALL", generatedCleanupPhase(savedState))
            assertNull(generatedCleanupInstallSession(savedState))
            assertEquals(
                TOKEN,
                savedState.get<String>(
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                ),
            )
            assertEquals(
                listOf(TOKEN to (null as Int?)),
                repository.cancelInstallArguments,
            )
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
        }

    @Test
    fun `null-session INSTALL promotion은 journal과 saved session을 먼저 봉합하고 exact cancel한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
            advanceUntilIdle()
            viewModel.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()
            repository.cancelInstallOutcomes += InstallCleanupResult.OwnerSessionAssigned(41)
            repository.cancelInstallOutcomes += InstallCleanupResult.Cleaned

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(
                listOf(TOKEN to null, TOKEN to null, TOKEN to 41),
                repository.cancelInstallArguments,
            )
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))
        }

    @Test
    fun `generated promotion의 두 cancel 사이 exact terminal은 journal owner로 회수한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallOutcome = InstallCleanupResult.CleanupFailed
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
            advanceUntilIdle()
            viewModel.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()

            repository.cancelInstallOutcome = null
            repository.cancelInstallOutcomes += InstallCleanupResult.OwnerSessionAssigned(41)
            repository.cancelInstallOutcomes += InstallCleanupResult.OwnerMismatch
            repository.terminalStateOnCancelInstall = InstallState.Installed(PACKAGE)
            repository.terminalStateOnCancelInstallRequest = 2
            repository.yieldAfterTerminalStateOnCancelInstall = true
            repository.terminalOwnerImportToken = TOKEN
            repository.terminalOwnerSessionId = 41
            repository.terminalOwnerState = InstallState.Installed(PACKAGE)
            repository.terminalOwnerProofConfigured = true

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(
                listOf(TOKEN to null, TOKEN to null, TOKEN to 41),
                repository.cancelInstallArguments,
            )
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertEquals("COMMIT_DESTINATION", generatedCleanupPhase(savedState))
            viewModel.accept(ManagerAction.GeneratedCleanupDestinationPresented(TOKEN))
            advanceUntilIdle()
            assertNull(generatedCleanupPhase(savedState))
        }

    @Test
    fun `normal review promotion의 두 cancel 사이 exact terminal은 current owner로 회수한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                )
                cancelInstallOutcomes += InstallCleanupResult.OwnerSessionAssigned(41)
                cancelInstallOutcomes += InstallCleanupResult.OwnerMismatch
                terminalStateOnCancelInstall = InstallState.Cancelled
                terminalStateOnCancelInstallRequest = 1
                yieldAfterTerminalStateOnCancelInstall = true
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = InstallState.Cancelled
                terminalOwnerProofConfigured = true
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
            advanceUntilIdle()

            viewModel.accept(ManagerAction.CancelReview)
            advanceUntilIdle()

            assertEquals(
                listOf(TOKEN to null, TOKEN to 41),
                repository.cancelInstallArguments,
            )
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertNull(savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
        }

    @Test
    fun `normal terminal은 exact owner ack 뒤 receipt와 saved review를 정리한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                )
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = InstallState.Cancelled
                terminalOwnerProofConfigured = true
                terminalReceiptActive = true
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            advanceUntilIdle()

            repository.installFlow.value = InstallState.Cancelled
            advanceUntilIdle()

            assertEquals(
                listOf(
                    Triple<String, Int?, InstallState>(
                        TOKEN,
                        41,
                        InstallState.Cancelled,
                    ),
                ),
                repository.terminalAcknowledgeArguments,
            )
            assertFalse(repository.terminalReceiptActive)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
            assertNull(savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))
        }

    @Test
    fun `normal terminal ack 실패는 receipt와 review를 보존하고 복구 메시지를 표시한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                )
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = InstallState.Cancelled
                terminalOwnerProofConfigured = true
                terminalAcknowledgeResult = false
                installResult = InstallState.Cancelled
                emitInstallResultOnReturn = true
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()

            assertTrue(repository.terminalReceiptActive)
            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
            assertEquals(41, savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))
            assertEquals(UserMessageKind.INSTALL_FAILED, viewModel.state.value.message?.kind)
            assertEquals(
                RecoveryAction.SELECT_ANOTHER_FILE,
                viewModel.state.value.message?.recoveryAction,
            )
        }

    @Test
    fun `normal PREPARED cold start는 receipt finalize 뒤 review cleanup을 먼저 완료한다`() =
        runTest(dispatcher.scheduler) {
            val handoff = NormalInstallHandoff(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Cancelled,
                stage = NormalHandoffStage.PREPARED,
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    terminal = RestoredInstallTerminal(
                        importToken = TOKEN,
                        sessionId = 41,
                        packageName = PACKAGE,
                        state = InstallState.Cancelled,
                        stage = FinalReceiptStage.READY,
                    ),
                    normalHandoff = handoff,
                )
                normalHandoff = handoff
                installFlow.value = InstallState.Cancelled
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = InstallState.Cancelled
                terminalOwnerProofConfigured = true
                discardReviewResult = false
            }
            val savedState = SavedStateHandle()

            val first = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Review(TOKEN), first.state.value.route)
            assertEquals(handoff, repository.normalHandoff)
            assertEquals(
                listOf<Triple<String, Int?, InstallState>>(
                    Triple(TOKEN, 41, InstallState.Cancelled),
                    Triple(TOKEN, 41, InstallState.Cancelled),
                ),
                repository.terminalFinalizeArguments,
            )
            assertEquals(listOf(TOKEN), repository.discardAttempts)

            repository.discardReviewResult = true
            repository.restoreResult = ManagerRestoreResult(
                review = review(ContractCompatibilityStatus.COMPATIBLE),
                normalHandoff = handoff,
            )
            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, recreated.state.value.route)
            assertEquals(
                handoff.copy(stage = NormalHandoffStage.OUTCOME_READY),
                repository.normalHandoff,
            )
            assertEquals(2, repository.discardAttempts.size)

            repository.importResult = ManagerImportResult.Success(
                review(
                    status = ContractCompatibilityStatus.COMPATIBLE,
                    token = SECOND_TOKEN,
                ),
            )
            recreated.startImport(repository::nextImport)
            advanceUntilIdle()

            assertNull(repository.normalHandoff)
            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(
                    recreated.state.value.importState,
                ).review.token,
            )
        }

    @Test
    fun `normal PREPARED는 current raw ready state로 exact rebase한 뒤 복구한다`() =
        runTest(dispatcher.scheduler) {
            val old = NormalInstallHandoff(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Cancelled,
                stage = NormalHandoffStage.PREPARED,
            )
            val current = RestoredInstallTerminal(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Failed("LATE_FAILURE", "late failure"),
                stage = FinalReceiptStage.READY,
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    terminal = current,
                    normalHandoff = old,
                )
                normalHandoff = old
                rawReadyTerminal = current
                installFlow.value = current.state
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = current.state
                terminalOwnerProofConfigured = true
            }

            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            assertEquals(
                old.copy(
                    state = current.state,
                    stage = NormalHandoffStage.OUTCOME_READY,
                ),
                repository.normalHandoff,
            )
            assertEquals(
                listOf(
                    Triple<String, Int?, InstallState>(
                        TOKEN,
                        41,
                        current.state,
                    ),
                    Triple<String, Int?, InstallState>(
                        TOKEN,
                        41,
                        current.state,
                    ),
                ),
                repository.terminalFinalizeArguments,
            )
            assertEquals(listOf(TOKEN), repository.discardAttempts)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals(UserMessageKind.INSTALL_FAILED, viewModel.state.value.message?.kind)
            assertEquals(
                listOf(
                    Triple<NormalInstallHandoff, NormalInstallHandoff, Boolean>(
                        old,
                        old.copy(state = current.state),
                        true,
                    ),
                ),
                repository.normalRebaseAttempts,
            )
        }

    @Test
    fun `normal rebase commit fault는 fail closed하고 recreation에서 재증명해야 진행한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                InstallState.Installed(PACKAGE),
                InstallState.Failed("LATE_FAILURE", "late failure"),
            ).forEach { currentState ->
                val old = NormalInstallHandoff(
                    importToken = TOKEN,
                    sessionId = 41,
                    packageName = PACKAGE,
                    state = InstallState.Cancelled,
                    stage = NormalHandoffStage.PREPARED,
                )
                val current = RestoredInstallTerminal(
                    importToken = TOKEN,
                    sessionId = 41,
                    packageName = PACKAGE,
                    state = currentState,
                    stage = FinalReceiptStage.READY,
                )
                val repository = FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(
                        review = review(ContractCompatibilityStatus.COMPATIBLE),
                        terminal = current,
                        normalHandoff = old,
                    )
                    normalHandoff = old
                    rawReadyTerminal = current
                    installFlow.value = currentState
                    terminalOwnerImportToken = TOKEN
                    terminalOwnerSessionId = 41
                    terminalOwnerState = currentState
                    terminalOwnerProofConfigured = true
                    normalHandoffRebaseResult = false
                    importResult = ManagerImportResult.Success(
                        review(
                            status = ContractCompatibilityStatus.COMPATIBLE,
                            token = SECOND_TOKEN,
                        ),
                    )
                }
                val savedState = SavedStateHandle()
                val viewModel = ManagerViewModel(repository, savedState)
                advanceUntilIdle()

                assertEquals(old, repository.normalHandoff, "state=$currentState")
                assertTrue(repository.terminalReceiptActive, "state=$currentState")
                assertEquals(
                    StartupRecoveryUiState.RECOVERY_BLOCKED,
                    viewModel.state.value.startupRecoveryState,
                    "state=$currentState",
                )

                viewModel.startImport(repository::nextImport)
                advanceUntilIdle()

                assertEquals(old, repository.normalHandoff, "state=$currentState")
                assertTrue(repository.terminalReceiptActive, "state=$currentState")
                assertTrue(repository.discardedTokens.isEmpty(), "state=$currentState")
                assertEquals(
                    ManagerRoute.Review(TOKEN),
                    viewModel.state.value.route,
                    "state=$currentState",
                )
                assertEquals(
                    TOKEN,
                    assertIs<ImportUiState.ReviewReady>(
                        viewModel.state.value.importState,
                    ).review.token,
                    "state=$currentState",
                )

                repository.normalHandoffRebaseResult = true
                val recreated = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                assertEquals(
                    StartupRecoveryUiState.READY,
                    recreated.state.value.startupRecoveryState,
                    "state=$currentState",
                )
                recreated.startImport(repository::nextImport)
                advanceUntilIdle()

                assertNull(repository.normalHandoff, "state=$currentState")
                assertFalse(repository.terminalReceiptActive, "state=$currentState")
                assertEquals(listOf(TOKEN), repository.discardedTokens, "state=$currentState")
                assertEquals(
                    SECOND_TOKEN,
                    assertIs<ImportUiState.ReviewReady>(
                        recreated.state.value.importState,
                    ).review.token,
                    "state=$currentState",
                )
            }
        }

    @Test
    fun `normal PREPARED 새 작업은 READY ACK absent의 exact current receipt proof에서만 열린다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                FinalReceiptStage.READY,
                FinalReceiptStage.ACKNOWLEDGED,
                null,
            ).forEach { stage ->
                val handoff = NormalInstallHandoff(
                    importToken = TOKEN,
                    sessionId = 41,
                    packageName = PACKAGE,
                    state = InstallState.Cancelled,
                    stage = NormalHandoffStage.PREPARED,
                )
                val repository = FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(
                        review = review(ContractCompatibilityStatus.COMPATIBLE),
                        normalHandoff = handoff,
                    )
                    normalHandoff = handoff
                    terminalOwnerImportToken = TOKEN
                    terminalOwnerSessionId = 41
                    terminalOwnerPackageName = PACKAGE
                    terminalOwnerState = InstallState.Cancelled
                    terminalOwnerProofConfigured = true
                    installFlow.value = InstallState.Cancelled
                    terminalReceiptActive = stage != null
                    terminalReceiptAcknowledged =
                        stage == FinalReceiptStage.ACKNOWLEDGED
                    currentTerminalReceiptResult = stage?.let {
                        TerminalReceiptOwnerResult.Owned(
                            RestoredInstallTerminal(
                                importToken = TOKEN,
                                sessionId = 41,
                                packageName = PACKAGE,
                                state = InstallState.Cancelled,
                                stage = it,
                            ),
                        )
                    } ?: TerminalReceiptOwnerResult.Absent
                    importResult = ManagerImportResult.Success(
                        review(
                            status = ContractCompatibilityStatus.COMPATIBLE,
                            token = SECOND_TOKEN,
                        ),
                    )
                }
                val viewModel = ManagerViewModel(repository, SavedStateHandle())
                advanceUntilIdle()

                viewModel.startImport(repository::nextImport)
                advanceUntilIdle()

                assertNull(repository.normalHandoff, "stage=$stage")
                assertFalse(repository.terminalReceiptActive, "stage=$stage")
                assertEquals(listOf(TOKEN), repository.discardedTokens, "stage=$stage")
                assertEquals(
                    SECOND_TOKEN,
                    assertIs<ImportUiState.ReviewReady>(
                        viewModel.state.value.importState,
                    ).review.token,
                    "stage=$stage",
                )
            }
        }

    @Test
    fun `normal PREPARED 새 import와 conversion은 foreign proof에서 기존 소유권을 보존한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                41 to TerminalReceiptOwnerResult.OwnerMismatch,
                null to TerminalReceiptOwnerResult.OwnerSessionAssigned(41),
            ).forEach { (handoffSession, proof) ->
                listOf("import", "conversion").forEach { action ->
                    val handoff = NormalInstallHandoff(
                        importToken = TOKEN,
                        sessionId = handoffSession,
                        packageName = PACKAGE,
                        state = InstallState.Cancelled,
                        stage = NormalHandoffStage.PREPARED,
                    )
                    val repository = FakeManagerRepository().apply {
                        restoreResult = ManagerRestoreResult(
                            review = review(ContractCompatibilityStatus.COMPATIBLE),
                            normalHandoff = handoff,
                        )
                        normalHandoff = handoff
                        currentTerminalReceiptResult = proof
                        importResult = ManagerImportResult.Success(
                            review(
                                status = ContractCompatibilityStatus.COMPATIBLE,
                                token = SECOND_TOKEN,
                            ),
                        )
                    }
                    var importRequested = false
                    val viewModel = ManagerViewModel(repository, SavedStateHandle())
                    advanceUntilIdle()

                    when (action) {
                        "import" -> viewModel.startImport {
                            importRequested = true
                            repository.nextImport()
                        }
                        "conversion" -> viewModel.accept(ManagerAction.OpenConversionInput)
                    }
                    advanceUntilIdle()

                    assertFalse(importRequested, "proof=$proof action=$action")
                    assertEquals(
                        handoff,
                        repository.normalHandoff,
                        "proof=$proof action=$action",
                    )
                    assertTrue(
                        repository.discardedTokens.isEmpty(),
                        "proof=$proof action=$action",
                    )
                    assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
                    assertEquals(
                        TOKEN,
                        assertIs<ImportUiState.ReviewReady>(
                            viewModel.state.value.importState,
                        ).review.token,
                    )
                    assertTrue(
                        repository.currentTerminalReceiptArguments.all {
                            it == Triple(TOKEN, handoffSession, PACKAGE)
                        },
                        "proof=$proof action=$action",
                    )
                }
            }
        }

    @Test
    fun `normal cancelled와 failed OUTCOME은 DismissMessage exact CAS 뒤에만 소비한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                InstallState.Cancelled,
                InstallState.Failed("INSTALL_FAILED", "failed"),
            ).forEach { terminal ->
                val handoff = NormalInstallHandoff(
                    importToken = TOKEN,
                    sessionId = 41,
                    packageName = PACKAGE,
                    state = terminal,
                    stage = NormalHandoffStage.OUTCOME_READY,
                )
                val repository = FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(normalHandoff = handoff)
                    normalHandoff = handoff
                    failNextNormalHandoffCompare = true
                }
                val savedState = SavedStateHandle()
                val viewModel = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                val before = viewModel.state.value

                viewModel.accept(ManagerAction.DismissMessage)
                advanceUntilIdle()

                assertEquals(before.route, viewModel.state.value.route, "terminal=$terminal")
                assertEquals(before.message, viewModel.state.value.message, "terminal=$terminal")
                assertEquals(handoff, repository.normalHandoff, "terminal=$terminal")

                viewModel.accept(ManagerAction.DismissMessage)
                advanceUntilIdle()

                assertNull(viewModel.state.value.message, "terminal=$terminal")
                assertNull(repository.normalHandoff, "terminal=$terminal")
                viewModel.accept(ManagerAction.DismissMessage)
                assertNull(viewModel.state.value.message, "terminal=$terminal duplicate")

                repository.restoreResult = ManagerRestoreResult()
                val recreated = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                assertNull(recreated.state.value.message, "terminal=$terminal recreation")
            }
        }

    @Test
    fun `normal installed OUTCOME은 matching Report ReturnHome exact CAS 뒤에만 소비한다`() =
        runTest(dispatcher.scheduler) {
            val installed = InstallState.Installed(PACKAGE)
            val handoff = NormalInstallHandoff(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = installed,
                stage = NormalHandoffStage.OUTCOME_READY,
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(normalHandoff = handoff)
                normalHandoff = handoff
                failNextNormalHandoffCompare = true
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)

            viewModel.accept(ManagerAction.ReturnHome)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertEquals(handoff, repository.normalHandoff)

            viewModel.accept(ManagerAction.ReturnHome)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(repository.normalHandoff)
            viewModel.accept(ManagerAction.ReturnHome)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        }

    @Test
    fun `normal terminal과 무관한 표시 메시지 Dismiss는 OUTCOME을 소비하지 않는다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                InstallState.Cancelled,
                InstallState.Failed("INSTALL_FAILED", "failed"),
            ).forEach { terminal ->
                val handoff = NormalInstallHandoff(
                    importToken = TOKEN,
                    sessionId = 41,
                    packageName = PACKAGE,
                    state = terminal,
                    stage = NormalHandoffStage.OUTCOME_READY,
                )
                val repository = FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(normalHandoff = handoff)
                    normalHandoff = handoff
                    navigationResult =
                        RepositoryNavigationResult.SamsungBrowserMissing
                }
                val viewModel = ManagerViewModel(repository, SavedStateHandle())
                advanceUntilIdle()

                viewModel.accept(ManagerAction.OpenSamsungAddOns)
                advanceUntilIdle()
                assertEquals(
                    UserMessageKind.SAMSUNG_BROWSER_MISSING,
                    viewModel.state.value.message?.kind,
                    "terminal=$terminal",
                )

                viewModel.accept(ManagerAction.DismissMessage)
                advanceUntilIdle()

                assertNull(viewModel.state.value.message, "terminal=$terminal")
                assertEquals(handoff, repository.normalHandoff, "terminal=$terminal")
            }
        }

    @Test
    fun `program cancel terminal은 normal PREPARED를 거쳐 review를 한 번 정리한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                )
                cancelInstallResult = false
                terminalStateOnCancelInstall = InstallState.Cancelled
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = InstallState.Cancelled
                terminalOwnerProofConfigured = true
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.CancelReview)
            advanceUntilIdle()

            assertEquals(
                NormalInstallHandoff(
                    importToken = TOKEN,
                    sessionId = 41,
                    packageName = PACKAGE,
                    state = InstallState.Cancelled,
                    stage = NormalHandoffStage.OUTCOME_READY,
                ),
                repository.normalHandoff,
            )
            assertFalse(repository.terminalReceiptActive)
            assertEquals(listOf(TOKEN), repository.discardAttempts)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
        }

    @Test
    fun `pre-claim attempt failure는 durable terminal proof 없이 현재 review를 종료한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                )
                installResult = InstallState.Failed(
                    "SESSION_CREATE_FAILED",
                    "pre-claim failure",
                )
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertNull(savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
            assertTrue(repository.terminalAcknowledgeArguments.isEmpty())
            assertEquals(UserMessageKind.INSTALL_FAILED, viewModel.state.value.message?.kind)
        }

    @Test
    fun `promotion 사이 foreign terminal은 generated journal과 owner를 소비하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallOutcome = InstallCleanupResult.CleanupFailed
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
            advanceUntilIdle()
            viewModel.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()

            repository.cancelInstallOutcome = null
            repository.cancelInstallOutcomes += InstallCleanupResult.OwnerSessionAssigned(41)
            repository.cancelInstallOutcomes += InstallCleanupResult.OwnerMismatch
            repository.terminalStateOnCancelInstall = InstallState.Installed(PACKAGE)
            repository.terminalStateOnCancelInstallRequest = 2
            repository.yieldAfterTerminalStateOnCancelInstall = true
            repository.terminalOwnerImportToken = SECOND_TOKEN
            repository.terminalOwnerSessionId = 41
            repository.terminalOwnerState = InstallState.Installed(PACKAGE)
            repository.terminalOwnerProofConfigured = true

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertEquals("INSTALL", generatedCleanupPhase(savedState))
            assertEquals(41, generatedCleanupInstallSession(savedState))
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
        }

    @Test
    fun `startup foreign 또는 legacy terminal은 null-session generated owner를 소비하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallOutcome = InstallCleanupResult.CleanupFailed
            }
            val sourceState = SavedStateHandle()
            val source = ManagerViewModel(sourceRepository, sourceState)
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            sourceRepository.installFlow.value =
                InstallState.UnknownSourcesPermissionRequired
            advanceUntilIdle()
            source.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()
            assertEquals("INSTALL", generatedCleanupPhase(sourceState))
            assertNull(generatedCleanupInstallSession(sourceState))

            listOf(
                "foreign" to SECOND_TOKEN,
                "legacy" to null,
            ).forEach { (kind, terminalToken) ->
                val deathState = generatedCleanupDeathSnapshot(sourceState)
                val repository = FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(
                        review = review(ContractCompatibilityStatus.COMPATIBLE),
                    )
                    restoredConversion = RestoredConversion.BundleReady(
                        review(ContractCompatibilityStatus.COMPATIBLE),
                        generatedBundle(),
                    )
                    installFlow.value = InstallState.Installed(PACKAGE)
                    terminalOwnerImportToken = terminalToken
                    terminalOwnerSessionId = 41
                    terminalOwnerState = InstallState.Installed(PACKAGE)
                    terminalOwnerProofConfigured = true
                }

                val recreated = ManagerViewModel(repository, deathState)
                advanceUntilIdle()

                assertEquals(
                    ManagerRoute.ConversionResult(TOKEN),
                    recreated.state.value.route,
                    kind,
                )
                assertEquals("INSTALL", generatedCleanupPhase(deathState), kind)
                assertNull(generatedCleanupInstallSession(deathState), kind)
                assertNull(
                    deathState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY),
                    kind,
                )
                assertTrue(repository.discardedTokens.isEmpty(), kind)
                assertTrue(repository.discardedConversions.isEmpty(), kind)
            }
        }

    @Test
    fun `startup exact terminal은 null-session generated journal을 promote하고 완료한다`() =
        runTest(dispatcher.scheduler) {
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallOutcome = InstallCleanupResult.CleanupFailed
            }
            val sourceState = SavedStateHandle()
            val source = ManagerViewModel(sourceRepository, sourceState)
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            sourceRepository.installFlow.value =
                InstallState.UnknownSourcesPermissionRequired
            advanceUntilIdle()
            source.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()
            val deathState = generatedCleanupDeathSnapshot(sourceState)
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                installFlow.value = InstallState.Installed(PACKAGE)
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = InstallState.Installed(PACKAGE)
                terminalOwnerProofConfigured = true
            }

            val recreated = ManagerViewModel(repository, deathState)
            advanceUntilIdle()

            assertEquals(
                listOf(TOKEN to (null as Int?)),
                repository.terminalOwnerArguments,
            )
            assertEquals(ManagerRoute.Report(PACKAGE), recreated.state.value.route)
            assertEquals("COMMIT_DESTINATION", generatedCleanupPhase(deathState))
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
            recreated.accept(ManagerAction.GeneratedCleanupDestinationPresented(TOKEN))
            advanceUntilIdle()
            assertNull(generatedCleanupPhase(deathState))
            assertNull(deathState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))
        }

    @Test
    fun `terminal receipt는 journal persist 직후 process death 뒤 새 ViewModel이 exact ack하고 재개한다`() =
        runTest(dispatcher.scheduler) {
            val dyingRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val savedState = SavedStateHandle()
            var acknowledgeCountAtJournalPersist: Int? = null
            val dying = ManagerViewModel(
                repository = dyingRepository,
                savedStateHandle = savedState,
                afterGeneratedInstallCleanupTransitionPersisted = {
                    acknowledgeCountAtJournalPersist =
                        dyingRepository.terminalAcknowledgeArguments.size
                    throw CancellationException("simulated process death")
                },
            )
            advanceUntilIdle()
            dying.accept(ManagerAction.PrepareGeneratedInstall)
            dyingRepository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            dying.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()
            dyingRepository.terminalOwnerImportToken = TOKEN
            dyingRepository.terminalOwnerSessionId = 41
            dyingRepository.terminalOwnerState = InstallState.Installed(PACKAGE)
            dyingRepository.terminalOwnerProofConfigured = true
            dyingRepository.terminalReceiptActive = true

            dyingRepository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()

            assertEquals(0, acknowledgeCountAtJournalPersist)
            assertTrue(dyingRepository.terminalReceiptActive)
            assertEquals("REVIEW", generatedCleanupPhase(savedState))
            assertEquals(
                TOKEN,
                savedState.get<String>(
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                ),
            )
            assertEquals(41, savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))

            val deathState = generatedCleanupDeathSnapshot(savedState)
            val recreatedRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                installFlow.value = InstallState.Installed(PACKAGE)
                terminalOwnerImportToken = TOKEN
                terminalOwnerSessionId = 41
                terminalOwnerState = InstallState.Installed(PACKAGE)
                terminalOwnerProofConfigured = true
                terminalReceiptActive = true
            }

            val recreated = ManagerViewModel(recreatedRepository, deathState)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    Triple<String, Int?, InstallState>(
                        TOKEN,
                        41,
                        InstallState.Installed(PACKAGE),
                    ),
                ),
                recreatedRepository.terminalAcknowledgeArguments,
            )
            assertFalse(recreatedRepository.terminalReceiptActive)
            assertNull(
                deathState.get<String>(
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                ),
            )
            assertNull(deathState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))

            recreated.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), recreated.state.value.route)
            assertEquals("COMMIT_DESTINATION", generatedCleanupPhase(deathState))
        }

    @Test
    fun `null-session INSTALL promotion의 두 torn snapshot은 missing key를 보완해 exact cancel한다`() =
        runTest(dispatcher.scheduler) {
            listOf("journal-only", "saved-only").forEach { snapshotKind ->
                val sourceRepository = FakeManagerRepository().apply {
                    restoredConversion = RestoredConversion.BundleReady(
                        review(ContractCompatibilityStatus.COMPATIBLE),
                        generatedBundle(),
                    )
                    cancelInstallResult = false
                }
                val sourceState = SavedStateHandle()
                val source = ManagerViewModel(sourceRepository, sourceState)
                advanceUntilIdle()
                source.accept(ManagerAction.PrepareGeneratedInstall)
                source.accept(ManagerAction.CancelConversion)
                advanceUntilIdle()

                when (snapshotKind) {
                    "journal-only" -> rewriteGeneratedCleanupInstallSession(sourceState, 41)
                    "saved-only" ->
                        sourceState[ManagerViewModel.INSTALL_SESSION_ID_KEY] = 41
                }
                val deathState = generatedCleanupDeathSnapshot(sourceState)
                val repository = FakeManagerRepository().apply {
                    restoreResult = ManagerRestoreResult(
                        review = review(ContractCompatibilityStatus.COMPATIBLE),
                    )
                    restoredConversion = RestoredConversion.BundleReady(
                        review(ContractCompatibilityStatus.COMPATIBLE),
                        generatedBundle(),
                    )
                    installFlow.value = InstallState.AwaitingUserApproval(41)
                }
                val recreated = ManagerViewModel(repository, deathState)
                advanceUntilIdle()

                recreated.accept(ManagerAction.RetryGeneratedCleanup)
                advanceUntilIdle()

                assertEquals(
                    listOf(TOKEN to (41 as Int?)),
                    repository.cancelInstallArguments,
                    "snapshot=$snapshotKind",
                )
                assertEquals(
                    ManagerRoute.Home,
                    recreated.state.value.route,
                    "snapshot=$snapshotKind",
                )
            }
        }

    @Test
    fun `null-session INSTALL journal recreation은 Retry로 exact destination을 복원한다`() =
        runTest(dispatcher.scheduler) {
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val sourceState = SavedStateHandle()
            val source = ManagerViewModel(sourceRepository, sourceState)
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            source.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()
            val deathState = generatedCleanupDeathSnapshot(sourceState)
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                installFlow.value = InstallState.UnknownSourcesPermissionRequired
            }
            val recreated = ManagerViewModel(repository, deathState)
            advanceUntilIdle()

            recreated.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(
                listOf(TOKEN to (null as Int?)),
                repository.cancelInstallArguments,
            )
            assertEquals(ManagerRoute.Home, recreated.state.value.route)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }

    @Test
    fun `null-session INSTALL late Cancelled와 cleanup failure는 exact journal만 처리한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                InstallState.Cancelled,
                InstallState.Failed("CLEANUP_FAILED", "late cleanup"),
            ).forEach { terminal ->
                val repository = FakeManagerRepository().apply {
                    restoredConversion = RestoredConversion.BundleReady(
                        review(ContractCompatibilityStatus.COMPATIBLE),
                        generatedBundle(),
                    )
                    cancelInstallResult = false
                }
                val savedState = SavedStateHandle()
                val viewModel = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.PrepareGeneratedInstall)
                viewModel.accept(ManagerAction.CancelConversion)
                advanceUntilIdle()

                repository.installFlow.value = terminal
                advanceUntilIdle()

                if (terminal == InstallState.Cancelled) {
                    assertEquals(ManagerRoute.Home, viewModel.state.value.route)
                    assertEquals(listOf(TOKEN), repository.discardedTokens)
                    assertEquals(listOf(TOKEN), repository.discardedConversions)
                } else {
                    assertEquals("INSTALL", generatedCleanupPhase(savedState))
                    assertEquals(41, generatedCleanupInstallSession(savedState))
                    assertEquals(
                        TOKEN,
                        savedState.get<String>(
                            ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                        ),
                    )
                    assertTrue(repository.discardedTokens.isEmpty())
                    assertTrue(repository.discardedConversions.isEmpty())
                }
            }
        }

    @Test
    fun `INSTALL foreign session terminal은 journal과 owner를 변경하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()
            savedState[ManagerViewModel.INSTALL_SESSION_ID_KEY] = 42

            repository.installFlow.value = InstallState.Cancelled
            advanceUntilIdle()

            assertEquals("INSTALL", generatedCleanupPhase(savedState))
            assertEquals(41, generatedCleanupInstallSession(savedState))
            assertEquals(
                TOKEN,
                savedState.get<String>(ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY),
            )
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
        }

    @Test
    fun `null-session INSTALL은 journal transition을 owner 해제보다 먼저 기록한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val savedState = SavedStateHandle()
            var transitionPhase: String? = null
            var transitionOwner: String? = null
            var transitionSession: Int? = -1
            val viewModel = ManagerViewModel(
                repository = repository,
                savedStateHandle = savedState,
                afterGeneratedInstallCleanupTransitionPersisted = {
                    transitionPhase = generatedCleanupPhase(savedState)
                    transitionOwner = savedState[
                        ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY
                    ]
                    transitionSession = savedState[
                        ManagerViewModel.INSTALL_SESSION_ID_KEY
                    ]
                },
            )
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)

            viewModel.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()

            assertEquals("REVIEW", transitionPhase)
            assertEquals(TOKEN, transitionOwner)
            assertNull(transitionSession)
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                ),
            )
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        }

    @Test
    fun `pending INSTALL Retry는 session 불일치면 cancelInstall을 호출하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()
            savedState[ManagerViewModel.INSTALL_SESSION_ID_KEY] = 42

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(1, repository.cancelInstallRequests)
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
        }

    @Test
    fun `pending INSTALL Retry는 token owner 불일치면 cancelInstall을 호출하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle()
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val source = ManagerViewModel(sourceRepository, savedState)
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            sourceRepository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            source.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()
            savedState[ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY] = SECOND_TOKEN

            val recreatedRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    installSessionId = 41,
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                installFlow.value = InstallState.AwaitingUserApproval(41)
            }
            val recreated = ManagerViewModel(recreatedRepository, savedState)
            advanceUntilIdle()

            recreated.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(0, recreatedRepository.cancelInstallRequests)
            assertTrue(recreatedRepository.discardedTokens.isEmpty())
            assertTrue(recreatedRepository.discardedConversions.isEmpty())
        }

    @Test
    fun `pending INSTALL recreation은 cleanup Retry message를 복원한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle()
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallResult = false
            }
            val source = ManagerViewModel(sourceRepository, savedState)
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            sourceRepository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            source.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            val recreatedRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    installSessionId = 41,
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                installFlow.value = InstallState.AwaitingUserApproval(41)
            }
            val recreated = ManagerViewModel(recreatedRepository, savedState)
            advanceUntilIdle()

            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                recreated.state.value.message?.kind,
            )
            assertEquals(
                RecoveryAction.RETRY,
                recreated.state.value.message?.recoveryAction,
            )
        }

    @Test
    fun `pending REVIEW recreation은 cleanup Retry message를 복원한다`() =
        runTest(dispatcher.scheduler) {
            val savedState = SavedStateHandle()
            val sourceRepository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardReviewResult = false
            }
            val source = ManagerViewModel(sourceRepository, savedState)
            advanceUntilIdle()
            source.accept(ManagerAction.FinishConversion)
            advanceUntilIdle()

            val recreatedRepository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val recreated = ManagerViewModel(recreatedRepository, savedState)
            advanceUntilIdle()

            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                recreated.state.value.message?.kind,
            )
            assertEquals(
                RecoveryAction.RETRY,
                recreated.state.value.message?.recoveryAction,
            )
        }

    @Test
    fun `generated active install cancel 예외는 review와 conversion owner를 보존한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallException = IllegalStateException("cancel failed")
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            assertEquals(1, repository.cancelInstallRequests)
            assertTrue(repository.discardedConversions.isEmpty())
            assertTrue(repository.discardedTokens.isEmpty())
            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
        }

    @Test
    fun `pending INSTALL cancel 예외 뒤 Retry는 exact session cancel을 다시 호출한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                cancelInstallException = IllegalStateException("cancel failed")
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()
            repository.cancelInstallException = null

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(2, repository.cancelInstallRequests)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        }

    @Test
    fun `generated install 성공은 exact conversion cleanup 뒤 report로 이동한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)

            repository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "install:start",
                    "install:end",
                    "discardReview:$TOKEN",
                    "discardConversion:$TOKEN",
                ),
                repository.lifecycleEvents,
            )
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertIs<ConversionUiState.Idle>(viewModel.state.value.conversionState)
        }

    @Test
    fun `generated install 성공 cleanup 실패는 결과와 retry owner를 유지한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardConversionResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.PrepareGeneratedInstall)

            repository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()

            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertNull(
                assertIs<ConversionUiState.ResultReady>(
                    viewModel.state.value.conversionState,
                ).review,
            )
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
            assertEquals(
                TOKEN,
                savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY),
            )
            assertEquals(
                "CONVERSION",
                generatedCleanupPhase(savedState),
            )
            assertTrue(repository.generatedExists)
        }

    @Test
    fun `generated install 취소와 실패 terminal은 결과로 돌아가 retry proof를 유지한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                InstallState.Cancelled,
                InstallState.Failed("INSTALL_FAILED", "failed"),
            ).forEach { terminal ->
                val repository = FakeManagerRepository().apply {
                    restoredConversion = RestoredConversion.BundleReady(
                        review(ContractCompatibilityStatus.COMPATIBLE),
                        generatedBundle(),
                    )
                }
                val savedState = SavedStateHandle()
                val viewModel = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.PrepareGeneratedInstall)

                repository.installFlow.value = terminal
                advanceUntilIdle()

                val ready = assertIs<ConversionUiState.ResultReady>(
                    viewModel.state.value.conversionState,
                    "terminal=$terminal",
                )
                assertEquals(TOKEN, ready.generated.token)
                assertEquals(
                    ManagerRoute.ConversionResult(TOKEN),
                    viewModel.state.value.route,
                    "terminal=$terminal",
                )
                assertTrue(repository.discardedConversions.isEmpty(), "terminal=$terminal")
                assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
            }
        }

    @Test
    fun `generated terminal result recreation은 Cancelled와 Failed 뒤 Result owner를 우선한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                InstallState.Cancelled,
                InstallState.Failed("INSTALL_FAILED", "failed"),
            ).forEach { terminal ->
                val retainedReview = review(ContractCompatibilityStatus.COMPATIBLE)
                val generated = generatedBundle()
                val repository = FakeManagerRepository().apply {
                    restoredConversion = RestoredConversion.BundleReady(
                        retainedReview,
                        generated,
                    )
                }
                val savedState = SavedStateHandle()
                val original = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                original.accept(ManagerAction.PrepareGeneratedInstall)
                repository.installFlow.value = terminal
                advanceUntilIdle()

                repository.restoreResult = ManagerRestoreResult(review = retainedReview)
                repository.restoredConversion = RestoredConversion.GeneratedPending(generated)
                val recreated = ManagerViewModel(repository, savedState)
                advanceUntilIdle()

                val ready = assertIs<ConversionUiState.ResultReady>(
                    recreated.state.value.conversionState,
                    "terminal=$terminal",
                )
                assertEquals(TOKEN, ready.token, "terminal=$terminal")
                assertEquals(TOKEN, ready.review?.token, "terminal=$terminal")
                assertFalse(ready.installOwnerActive, "terminal=$terminal")
                assertEquals(
                    ManagerRoute.ConversionResult(TOKEN),
                    recreated.state.value.route,
                    "terminal=$terminal",
                )
                assertEquals(false, repository.restoreConversionImportReviewRequests.last())

                recreated.accept(ManagerAction.SaveGeneratedConversion)
                assertEquals(
                    TOKEN,
                    assertIs<ManagerEffect.CreateConversionDocument>(
                        recreated.effects.first(),
                    ).token,
                )
                recreated.accept(ManagerAction.ShareGeneratedConversion)
                val share = assertIs<ManagerEffect.ShareGeneratedBundle>(
                    recreated.effects.first(),
                )
                assertEquals(
                    TOKEN,
                    share.token,
                )
                recreated.accept(
                    ManagerAction.GeneratedShareLaunchSucceeded(
                        share.token,
                        share.handoffId,
                    ),
                )
                dispatcher.scheduler.runCurrent()
            }
        }

    @Test
    fun `generated direct terminal은 persist ack finalize 세 death gap에서 result owner를 복원한다`() =
        runTest(dispatcher.scheduler) {
            listOf(
                InstallState.Cancelled,
                InstallState.Failed("INSTALL_FAILED", "failed"),
            ).forEach { terminal ->
                listOf("persisted", "acknowledged", "finalized").forEach { gap ->
                    val repository = FakeManagerRepository().apply {
                        restoredConversion = RestoredConversion.BundleReady(
                            review(ContractCompatibilityStatus.COMPATIBLE),
                            generatedBundle(),
                        )
                        terminalFinalizeResult = gap != "acknowledged"
                    }
                    val savedState = SavedStateHandle()
                    val source = ManagerViewModel(
                        repository = repository,
                        savedStateHandle = savedState,
                        afterGeneratedTerminalHandoffPersisted = {
                            if (gap == "persisted") {
                                throw CancellationException("simulated process death")
                            }
                        },
                        afterTerminalReceiptFinalized = {
                            if (gap == "finalized") {
                                throw CancellationException("simulated process death")
                            }
                        },
                    )
                    advanceUntilIdle()
                    source.accept(ManagerAction.PrepareGeneratedInstall)
                    repository.installFlow.value = InstallState.AwaitingUserApproval(41)
                    dispatcher.scheduler.runCurrent()
                    repository.installFlow.value = terminal
                    advanceUntilIdle()

                    assertNotNull(
                        savedState.get<String>(
                            ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY,
                        ),
                        "terminal=$terminal gap=$gap",
                    )

                    val finalStage = when (gap) {
                        "persisted" -> FinalReceiptStage.READY
                        "acknowledged" -> FinalReceiptStage.ACKNOWLEDGED
                        else -> null
                    }
                    val recreatedRepository = FakeManagerRepository().apply {
                        restoreResult = ManagerRestoreResult(
                            review = review(ContractCompatibilityStatus.COMPATIBLE),
                            terminal = finalStage?.let {
                                RestoredInstallTerminal(
                                    importToken = TOKEN,
                                    sessionId = 41,
                                    packageName = PACKAGE,
                                    state = terminal,
                                    stage = it,
                                )
                            },
                        )
                        restoredConversion =
                            RestoredConversion.GeneratedPending(generatedBundle())
                        installFlow.value = terminal
                        terminalOwnerImportToken = TOKEN
                        terminalOwnerSessionId = 41
                        terminalOwnerState = terminal
                        terminalOwnerProofConfigured = true
                        terminalReceiptActive = finalStage != null
                        terminalReceiptAcknowledged =
                            finalStage == FinalReceiptStage.ACKNOWLEDGED
                        suspendInstall = true
                    }

                    val recreated = ManagerViewModel(recreatedRepository, savedState)
                    advanceUntilIdle()

                    val ready = assertIs<ConversionUiState.ResultReady>(
                        recreated.state.value.conversionState,
                        "terminal=$terminal gap=$gap",
                    )
                    assertEquals(TOKEN, ready.review?.token, "terminal=$terminal gap=$gap")
                    assertFalse(ready.installOwnerActive, "terminal=$terminal gap=$gap")
                    assertEquals(
                        ManagerRoute.ConversionResult(TOKEN),
                        recreated.state.value.route,
                        "terminal=$terminal gap=$gap",
                    )
                    assertNull(
                        savedState.get<String>(
                            ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY,
                        ),
                        "terminal=$terminal gap=$gap",
                    )
                    assertTrue(
                        recreatedRepository.discardedTokens.isEmpty(),
                        "terminal=$terminal gap=$gap",
                    )

                    recreated.accept(ManagerAction.PrepareGeneratedInstall)
                    recreated.accept(ManagerAction.InstallReviewedBundle)
                    dispatcher.scheduler.runCurrent()

                    assertEquals(
                        1,
                        recreatedRepository.installRequests,
                        "same review retry terminal=$terminal gap=$gap",
                    )
                    recreatedRepository.completeInstall()
                    advanceUntilIdle()
                }
            }
        }

    @Test
    fun `generated terminal handoff는 current raw ready state로 SavedState를 교체해 복구한다`() =
        runTest(dispatcher.scheduler) {
            val retainedReview = review(ContractCompatibilityStatus.COMPATIBLE)
            val generated = generatedBundle()
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    retainedReview,
                    generated,
                )
            }
            val savedState = SavedStateHandle()
            val source = ManagerViewModel(
                repository = repository,
                savedStateHandle = savedState,
                afterGeneratedTerminalHandoffPersisted = {
                    throw CancellationException("simulated process death")
                },
            )
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            repository.installFlow.value = InstallState.Cancelled
            advanceUntilIdle()

            assertNotNull(
                savedState.get<String>(ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY),
            )

            val current = RestoredInstallTerminal(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Failed("LATE_FAILURE", "late failure"),
                stage = FinalReceiptStage.READY,
            )
            repository.restoreResult = ManagerRestoreResult(
                review = retainedReview,
                terminal = current,
            )
            repository.restoredConversion = RestoredConversion.GeneratedPending(generated)
            repository.installFlow.value = current.state
            repository.rawReadyTerminal = current
            repository.terminalOwnerImportToken = TOKEN
            repository.terminalOwnerSessionId = 41
            repository.terminalOwnerState = current.state
            repository.terminalOwnerProofConfigured = true

            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertNull(
                savedState.get<String>(ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY),
            )
            assertEquals(
                ManagerRoute.ConversionResult(TOKEN),
                recreated.state.value.route,
            )
            assertEquals(
                UserMessageKind.INSTALL_FAILED,
                recreated.state.value.message?.kind,
            )
            assertFalse(repository.terminalReceiptActive)
            assertEquals(
                listOf(Triple<String, Int?, String>(TOKEN, 41, PACKAGE)),
                repository.rawReadyTerminalArguments,
            )
        }

    @Test
    fun `generated terminal handoff의 late success는 Report cleanup journal로 승계한다`() =
        runTest(dispatcher.scheduler) {
            val retainedReview = review(ContractCompatibilityStatus.COMPATIBLE)
            val generated = generatedBundle()
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    retainedReview,
                    generated,
                )
            }
            val savedState = SavedStateHandle()
            val source = ManagerViewModel(
                repository = repository,
                savedStateHandle = savedState,
                afterGeneratedTerminalHandoffPersisted = {
                    throw CancellationException("simulated process death")
                },
            )
            advanceUntilIdle()
            source.accept(ManagerAction.PrepareGeneratedInstall)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            dispatcher.scheduler.runCurrent()
            repository.installFlow.value = InstallState.Cancelled
            advanceUntilIdle()

            val current = RestoredInstallTerminal(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Installed(PACKAGE),
                stage = FinalReceiptStage.READY,
            )
            repository.restoreResult = ManagerRestoreResult(
                review = retainedReview,
                terminal = current,
            )
            repository.restoredConversion = RestoredConversion.GeneratedPending(generated)
            repository.installFlow.value = current.state
            repository.rawReadyTerminal = current
            repository.terminalOwnerImportToken = TOKEN
            repository.terminalOwnerSessionId = 41
            repository.terminalOwnerState = current.state
            repository.terminalOwnerProofConfigured = true

            val interrupted = ManagerViewModel(
                repository = repository,
                savedStateHandle = savedState,
                afterGeneratedTerminalHandoffCleared = {
                    throw CancellationException("simulated process death")
                },
            )
            advanceUntilIdle()

            assertNull(
                savedState.get<String>(ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY),
            )
            assertEquals(
                listOf("install:start", "install:end"),
                repository.lifecycleEvents,
            )
            assertFalse(repository.terminalReceiptActive)

            repository.restoreResult = ManagerRestoreResult(review = retainedReview)
            repository.rawReadyTerminal = null
            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "install:start",
                    "install:end",
                    "discardReview:$TOKEN",
                    "discardConversion:$TOKEN",
                ),
                repository.lifecycleEvents,
            )
            assertEquals(ManagerRoute.Report(PACKAGE), recreated.state.value.route)
            assertFalse(repository.terminalReceiptActive)
        }

    @Test
    fun `result owner review mismatch는 generated와 결합하지 않고 기존 Review로 fallback한다`() =
        runTest(dispatcher.scheduler) {
            val otherReview = review(
                status = ContractCompatibilityStatus.COMPATIBLE,
                token = SECOND_TOKEN,
            )
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                    ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
                    ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY to TOKEN,
                    ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.REVIEW_TOKEN_KEY to SECOND_TOKEN,
                ),
            )
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(review = otherReview)
                restoredConversion = RestoredConversion.GeneratedPending(generatedBundle())
            }

            val recreated = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            assertEquals(
                ManagerRoute.Review(SECOND_TOKEN),
                recreated.state.value.route,
            )
            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(
                    recreated.state.value.importState,
                ).review.token,
            )
            assertIs<ConversionUiState.Idle>(recreated.state.value.conversionState)
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY,
                ),
            )
            assertNull(
                savedState.get<String>(
                    ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY,
                ),
            )
        }

    @Test
    fun `process recreation은 generated install owner를 review route로 바꾸지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoreResult = ManagerRestoreResult(
                    review = review(ContractCompatibilityStatus.COMPATIBLE),
                    installSessionId = 41,
                )
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                installFlow.value = InstallState.AwaitingUserApproval(41)
            }
            val savedState = SavedStateHandle(
                mapOf(
                    ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN,
                    ManagerViewModel.INSTALL_SESSION_ID_KEY to 41,
                    ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                    ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
                    ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY to TOKEN,
                ),
            )

            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            val ready = assertIs<ConversionUiState.ResultReady>(
                viewModel.state.value.conversionState,
            )
            assertTrue(ready.installOwnerActive)
            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertEquals(
                TOKEN,
                assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState)
                    .review
                    .token,
            )
            assertEquals(1, repository.restoreConversionRequests)
        }

    @Test
    fun `분석 취소는 join 전에 홈을 열고 제거를 허용한다`() =
        runTest(dispatcher.scheduler) {
            val pending = CompletableDeferred<ConversionBeginResult>()
            val repository = FakeManagerRepository().apply {
                beginConversionDeferred = pending
                catalogFlow.tryEmit(listOf(catalogRecord()))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(
                ManagerAction.ConversionUrlChanged("https://example.test/extension"),
            )
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.Remove(PACKAGE))
            dispatcher.scheduler.runCurrent()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertTrue(viewModel.state.value.conversionCleanupPending)
            assertIs<ConversionUiState.Cancelling>(viewModel.state.value.conversionState)
            assertEquals(listOf(PACKAGE), repository.uninstallPackages)
        }

    @Test
    fun `협력적 분석 취소 cleanup 실패는 관리 action과 exact token retry를 보존한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                suspendFirstConversionBegin = true
                conversionCleanupCancellationToken = TOKEN
                discardConversionResult = false
                catalogFlow.tryEmit(listOf(catalogRecord()))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(
                ManagerAction.ConversionUrlChanged("https://example.test/extension"),
            )
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            viewModel.accept(ManagerAction.Remove(PACKAGE))
            viewModel.accept(ManagerAction.OpenReport(PACKAGE))
            dispatcher.scheduler.runCurrent()

            assertEquals(listOf(PACKAGE), repository.uninstallPackages)
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertEquals(
                TOKEN,
                assertIs<ConversionUiState.Cancelling>(
                    viewModel.state.value.conversionState,
                ).token,
            )
            assertTrue(viewModel.state.value.conversionCleanupPending)

            repository.discardConversionResult = true
            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(listOf(TOKEN, TOKEN), repository.discardedConversions)
            assertFalse(repository.generatedExists)
            assertFalse(viewModel.state.value.conversionCleanupPending)
            assertIs<ConversionUiState.Idle>(viewModel.state.value.conversionState)
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
        }

    @Test
    fun `분석 cleanup 중 보고서와 Add-ons 이동도 허용한다`() =
        runTest(dispatcher.scheduler) {
            val pending = CompletableDeferred<ConversionBeginResult>()
            val repository = FakeManagerRepository().apply {
                beginConversionDeferred = pending
                catalogFlow.tryEmit(listOf(catalogRecord()))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(
                ManagerAction.ConversionUrlChanged("https://example.test/extension"),
            )
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.OpenReport(PACKAGE))
            viewModel.accept(ManagerAction.OpenSamsungAddOns)

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertTrue(viewModel.state.value.conversionCleanupPending)
            assertEquals(
                LaunchDestination.SAMSUNG_ADD_ONS,
                assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first()).destination,
            )

            pending.complete(conversionAnalysis(TOKEN))
            advanceUntilIdle()

            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertFalse(
                viewModel.state.value.conversionCleanupPending,
                viewModel.state.value.toString(),
            )
        }

    @Test
    fun `분석 cleanup 중 새 변환은 차단되고 완료 뒤 허용된다`() =
        runTest(dispatcher.scheduler) {
            val pending = CompletableDeferred<ConversionBeginResult>()
            val repository = FakeManagerRepository().apply {
                beginConversionDeferred = pending
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(
                ManagerAction.ConversionUrlChanged("https://example.test/extension"),
            )
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.OpenConversionInput)

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertTrue(viewModel.state.value.conversionCleanupPending)

            pending.complete(conversionAnalysis(TOKEN))
            advanceUntilIdle()

            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertFalse(viewModel.state.value.conversionCleanupPending)
            assertIs<ConversionUiState.Idle>(viewModel.state.value.conversionState)

            viewModel.accept(ManagerAction.OpenConversionInput)

            assertEquals(ManagerRoute.ConversionInput, viewModel.state.value.route)
        }

    @Test
    fun `분석 cleanup 실패는 홈과 exact token을 유지하고 Retry로 완료한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis(TOKEN)
                discardConversionResult = false
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(
                ManagerAction.ConversionUrlChanged("https://example.test/extension"),
            )
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertTrue(viewModel.state.value.conversionCleanupPending)
            assertEquals(
                TOKEN,
                assertIs<ConversionUiState.Cancelling>(
                    viewModel.state.value.conversionState,
                ).token,
            )
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(RecoveryAction.RETRY, viewModel.state.value.message?.recoveryAction)

            repository.discardConversionResult = true
            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(listOf(TOKEN, TOKEN), repository.discardedConversions)
            assertFalse(viewModel.state.value.conversionCleanupPending)
            assertIs<ConversionUiState.Idle>(viewModel.state.value.conversionState)
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        }

    @Test
    fun `분석 cleanup discard 예외는 retry 상태와 exact token을 유지한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis(TOKEN)
                discardConversionException = IOException("discard failed")
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(
                ManagerAction.ConversionUrlChanged("https://example.test/extension"),
            )
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertTrue(viewModel.state.value.conversionCleanupPending)
            assertEquals(
                TOKEN,
                assertIs<ConversionUiState.Cancelling>(
                    viewModel.state.value.conversionState,
                ).token,
            )
            assertEquals(
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(RecoveryAction.RETRY, viewModel.state.value.message?.recoveryAction)

            repository.discardConversionException = null
            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(listOf(TOKEN, TOKEN), repository.discardedConversions)
            assertFalse(viewModel.state.value.conversionCleanupPending)
            assertIs<ConversionUiState.Idle>(viewModel.state.value.conversionState)
        }

    @Test
    fun `분석 cleanup 완료는 Add-ons 오류 메시지를 보존한다`() =
        runTest(dispatcher.scheduler) {
            val pending = CompletableDeferred<ConversionBeginResult>()
            val repository = FakeManagerRepository().apply {
                beginConversionDeferred = pending
                navigationResult = RepositoryNavigationResult.AddOnsIntentUnavailable
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(
                ManagerAction.ConversionUrlChanged("https://example.test/extension"),
            )
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            dispatcher.scheduler.runCurrent()
            viewModel.accept(ManagerAction.CancelConversion)
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.OpenSamsungAddOns)

            assertEquals(
                UserMessageKind.SAMSUNG_ADD_ONS_UNAVAILABLE,
                viewModel.state.value.message?.kind,
            )
            pending.complete(conversionAnalysis(TOKEN))
            advanceUntilIdle()

            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertFalse(viewModel.state.value.conversionCleanupPending)
            assertEquals(
                UserMessageKind.SAMSUNG_ADD_ONS_UNAVAILABLE,
                viewModel.state.value.message?.kind,
            )
            assertEquals(RecoveryAction.RETRY, viewModel.state.value.message?.recoveryAction)
        }

    @Test
    fun `새 begin은 cancellation 무시 old begin과 compensation cleanup 완료를 기다린다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResults += conversionAnalysis(TOKEN)
                conversionBeginResults += conversionAnalysis(SECOND_TOKEN)
                suspendFirstConversionBegin = true
                ignoreFirstConversionBeginCancellation = true
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test/old"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            dispatcher.scheduler.runCurrent()

            viewModel.accept(ManagerAction.CancelConversion)
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test/new"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            dispatcher.scheduler.runCurrent()

            assertEquals(1, repository.conversionRequests.size)

            repository.completeFirstConversionBegin()
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test/new"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()

            assertEquals(2, repository.conversionRequests.size)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertEquals(
                ManagerRoute.ConversionAnalysis(SECOND_TOKEN),
                viewModel.state.value.route,
            )
            assertEquals(
                SECOND_TOKEN,
                assertIs<ConversionUiState.AnalysisReady>(
                    viewModel.state.value.conversionState,
                ).token,
            )
        }

    @Test
    fun `새 mutation은 cancellation 무시 old build의 generated와 review compensation을 기다린다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis(TOKEN)
                conversionBuildResult = ConversionBuildResult.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                suspendFirstConversionBuild = true
                ignoreFirstConversionBuildCancellation = true
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test/old"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.BuildConversion)
            dispatcher.scheduler.runCurrent()

            repository.conversionBeginResult = conversionAnalysis(SECOND_TOKEN)
            viewModel.accept(ManagerAction.CancelConversion)
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test/new"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            dispatcher.scheduler.runCurrent()

            assertEquals(1, repository.conversionRequests.size)

            repository.completeFirstConversionBuild()
            advanceUntilIdle()

            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test/new"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()

            assertEquals(2, repository.conversionRequests.size)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(
                ManagerRoute.ConversionAnalysis(SECOND_TOKEN),
                viewModel.state.value.route,
            )
        }

    @Test
    fun `결과에서 새 변환 진입은 이전 generated와 review cleanup 성공 뒤에만 열린다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenConversionInput)
            advanceUntilIdle()

            assertEquals(listOf(TOKEN), repository.discardedConversions)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(ManagerRoute.ConversionInput, viewModel.state.value.route)
        }

    @Test
    fun `결과 cleanup 실패는 새 변환 진입 대신 기존 result owner를 유지한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                discardConversionResult = false
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenConversionInput)
            advanceUntilIdle()

            assertEquals(ManagerRoute.ConversionResult(TOKEN), viewModel.state.value.route)
            assertIs<ConversionUiState.ResultReady>(viewModel.state.value.conversionState)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
        }

    @Test
    fun `OpenDocument callback은 exact owner generation과 launch identity만 한 번 수락한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis()
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.OpenConversionFilePicker)
            val effect = assertIs<ManagerEffect.PickConversionDocument>(
                viewModel.effects.first(),
            )

            val uri = unitTestUri()
            viewModel.accept(
                ManagerAction.ConversionFileSelected(
                    uri = uri,
                    ownerGeneration = effect.ownerGeneration,
                    launchId = effect.launchId,
                ),
            )
            advanceUntilIdle()
            viewModel.accept(
                ManagerAction.ConversionFileSelected(
                    uri = uri,
                    ownerGeneration = effect.ownerGeneration,
                    launchId = effect.launchId,
                ),
            )
            advanceUntilIdle()

            assertEquals(1, repository.conversionRequests.size)
            assertTrue(
                uri ===
                    assertIs<OnDeviceInputRequest.Saf>(
                        repository.conversionRequests.single(),
                    ).uri,
            )
        }

    @Test
    fun `새 zip 변환은 소유 화면이 사라진 manual install을 먼저 정리한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis()
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            repository.installFlow.value = InstallState.AwaitingManualInstall(TOKEN)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.OpenConversionFilePicker)
            val effect = assertIs<ManagerEffect.PickConversionDocument>(
                viewModel.effects.first(),
            )

            viewModel.accept(
                ManagerAction.ConversionFileSelected(
                    uri = unitTestUri(),
                    ownerGeneration = effect.ownerGeneration,
                    launchId = effect.launchId,
                ),
            )
            advanceUntilIdle()

            assertEquals(
                listOf<Pair<String, Int?>>(TOKEN to null),
                repository.cancelInstallArguments,
            )
            assertEquals(1, repository.conversionRequests.size)
            assertEquals(
                ManagerRoute.ConversionAnalysis(TOKEN),
                viewModel.state.value.route,
            )
        }

    @Test
    fun `stale OpenDocument result는 review route와 retained owner를 덮지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.OpenConversionFilePicker)
            val stale = assertIs<ManagerEffect.PickConversionDocument>(
                viewModel.effects.first(),
            )
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            viewModel.accept(
                ManagerAction.ConversionFileSelected(
                    uri = unitTestUri(),
                    ownerGeneration = stale.ownerGeneration,
                    launchId = stale.launchId,
                ),
            )
            advanceUntilIdle()

            assertTrue(repository.conversionRequests.isEmpty())
            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
            assertEquals(
                TOKEN,
                assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState)
                    .review
                    .token,
            )
        }

    @Test
    fun `새 picker B 뒤 늦은 picker A result는 B ownership으로 오인하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = conversionAnalysis()
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.OpenConversionFilePicker)
            val first = assertIs<ManagerEffect.PickConversionDocument>(
                viewModel.effects.first(),
            )
            viewModel.accept(ManagerAction.OpenConversionFilePicker)
            val second = assertIs<ManagerEffect.PickConversionDocument>(
                viewModel.effects.first(),
            )

            viewModel.accept(
                ManagerAction.ConversionFileSelected(
                    unitTestUri(),
                    first.ownerGeneration,
                    first.launchId,
                ),
            )
            dispatcher.scheduler.runCurrent()
            assertTrue(repository.conversionRequests.isEmpty())

            viewModel.accept(
                ManagerAction.ConversionFileSelected(
                    unitTestUri(),
                    second.ownerGeneration,
                    second.launchId,
                ),
            )
            advanceUntilIdle()
            assertEquals(1, repository.conversionRequests.size)
        }

    @Test
    fun `stale CreateDocument result는 새 review에서 generated copy를 실행하지 않는다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                importResult = ManagerImportResult.Success(
                    review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
                )
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.SaveGeneratedConversion)
            val stale = assertIs<ManagerEffect.CreateConversionDocument>(
                viewModel.effects.first(),
            )
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            viewModel.accept(
                ManagerAction.SaveGeneratedConversionTo(
                    token = stale.token,
                    destination = "content://save/stale.six",
                    ownerGeneration = stale.ownerGeneration,
                    launchId = stale.launchId,
                ),
            )
            advanceUntilIdle()

            assertTrue(repository.copiedConversionTokens.isEmpty())
            assertEquals(ManagerRoute.Review(SECOND_TOKEN), viewModel.state.value.route)
        }

    @Test
    fun `CreateDocument pending ownership은 recreation 뒤 URI 없이 복구해 한 번 소비한다`() =
        runTest(dispatcher.scheduler) {
            val generated = generatedBundle()
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generated,
                )
                copiedConversionHash = generated.sha256
            }
            val savedState = SavedStateHandle()
            val first = ManagerViewModel(
                repository,
                savedState,
                saveGeneratedDocument = documentSaver(ByteArrayOutputStream()),
            )
            advanceUntilIdle()
            first.accept(ManagerAction.SaveGeneratedConversion)
            val effect = assertIs<ManagerEffect.CreateConversionDocument>(
                first.effects.first(),
            )

            val recreated = ManagerViewModel(
                repository,
                savedState,
                saveGeneratedDocument = documentSaver(ByteArrayOutputStream()),
            )
            advanceUntilIdle()
            recreated.accept(
                ManagerAction.SaveGeneratedConversionTo(
                    token = effect.token,
                    destination = "content://save/result.six",
                    ownerGeneration = effect.ownerGeneration,
                    launchId = effect.launchId,
                ),
            )
            advanceUntilIdle()
            recreated.accept(
                ManagerAction.SaveGeneratedConversionTo(
                    token = effect.token,
                    destination = "content://save/result.six",
                    ownerGeneration = effect.ownerGeneration,
                    launchId = effect.launchId,
                ),
            )
            advanceUntilIdle()

            assertEquals(listOf(TOKEN), repository.copiedConversionTokens)
            assertNull(savedState.get<String>("pending_conversion_destination"))
        }

    private fun generatedBundle(token: String = TOKEN) = GeneratedBundle(
        token = token,
        file = File("generated-bundles/$token.six"),
        sha256 = "d".repeat(64),
        suggestedFileName = "확장.six",
    )

    private fun restoredCancelledTerminal() = RestoredInstallTerminal(
        importToken = TOKEN,
        sessionId = 41,
        packageName = PACKAGE,
        state = InstallState.Cancelled,
        stage = FinalReceiptStage.READY,
    )

    private fun generatedInstallSavedState(
        cleanupValues: Map<String, Any?>,
    ) = SavedStateHandle(
        mapOf(
            ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN,
            ManagerViewModel.INSTALL_SESSION_ID_KEY to 41,
            ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
            ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
            ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY to TOKEN,
            ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY to TOKEN,
            ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY to TOKEN,
        ) + cleanupValues,
    )

    private fun currentGeneratedInstallSavedState(
        cleanupJournal: String,
    ) = generatedInstallSavedState(
        mapOf(CLEANUP_JOURNAL_KEY to cleanupJournal),
    )

    private fun pendingReviewReportSavedState() = currentGeneratedInstallSavedState(
        cleanupJournal = """
            {
              "version": 1,
              "token": "$TOKEN",
              "reviewToken": "$TOKEN",
              "phase": "REVIEW",
              "destination": {
                "kind": "REPORT",
                "packageName": "$PACKAGE"
              },
              "installSessionId": null
            }
        """.trimIndent(),
    )

    private fun assertGeneratedOwnersAndSessionPreserved(savedState: SavedStateHandle) {
        assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
        assertEquals(41, savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))
        assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
        assertEquals(
            "result",
            savedState.get<String>(ManagerViewModel.CONVERSION_ROUTE_KEY),
        )
        assertEquals(
            TOKEN,
            savedState.get<String>(ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY),
        )
        assertEquals(
            TOKEN,
            savedState.get<String>(ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY),
        )
        assertEquals(
            TOKEN,
            savedState.get<String>(ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY),
        )
    }

    private fun assertGeneratedInstallOwnerCleared(savedState: SavedStateHandle) {
        assertNull(
            savedState.get<String>(ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY),
        )
        assertNull(savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY))
        assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
        assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
        assertEquals(
            TOKEN,
            savedState.get<String>(ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY),
        )
        assertEquals(
            TOKEN,
            savedState.get<String>(ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY),
        )
    }

    private fun assertGeneratedCleanupRestoreBlocked(viewModel: ManagerViewModel) {
        assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        assertEquals(
            UserMessageKind.CONVERSION_CLEANUP_FAILED,
            viewModel.state.value.message?.kind,
        )
        assertEquals(RecoveryAction.NONE, viewModel.state.value.message?.recoveryAction)
    }

    private fun assertGeneratedCleanupCompletionSnapshot(
        step: GeneratedCleanupCompletionStep,
        savedState: SavedStateHandle,
    ) {
        val installOwner =
            savedState.get<String>(ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY)
        val resultOwner =
            savedState.get<String>(ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY)
        val resultReview =
            savedState.get<String>(ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY)
        val review = savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY)
        val session = savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY)
        val conversion = savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY)
        when (step) {
            GeneratedCleanupCompletionStep.DESTINATION_COMMITTED -> {
                assertNull(installOwner)
                assertEquals(TOKEN, resultOwner)
                assertEquals(TOKEN, resultReview)
                assertEquals(TOKEN, review)
                assertNull(session)
                assertEquals(TOKEN, conversion)
            }
            GeneratedCleanupCompletionStep.INSTALL_OWNER_CLEARED -> {
                assertNull(installOwner)
                assertEquals(TOKEN, resultOwner)
                assertEquals(TOKEN, resultReview)
                assertEquals(TOKEN, review)
                assertNull(session)
                assertEquals(TOKEN, conversion)
            }
            GeneratedCleanupCompletionStep.RESULT_OWNER_CLEARED -> {
                assertNull(installOwner)
                assertNull(resultOwner)
                assertNull(resultReview)
                assertEquals(TOKEN, review)
                assertNull(session)
                assertEquals(TOKEN, conversion)
            }
            GeneratedCleanupCompletionStep.REVIEW_SESSION_CLEARED -> {
                assertNull(installOwner)
                assertNull(resultOwner)
                assertNull(resultReview)
                assertNull(review)
                assertNull(session)
                assertEquals(TOKEN, conversion)
            }
            GeneratedCleanupCompletionStep.CONVERSION_OWNER_CLEARED -> {
                assertNull(installOwner)
                assertNull(resultOwner)
                assertNull(resultReview)
                assertNull(review)
                assertNull(session)
                assertNull(conversion)
            }
        }
    }

    private fun currentGeneratedInstallRepository() = FakeManagerRepository().apply {
        restoreResult = ManagerRestoreResult(
            review = review(ContractCompatibilityStatus.COMPATIBLE),
            installSessionId = 41,
        )
        restoredConversion = RestoredConversion.BundleReady(
            review(ContractCompatibilityStatus.COMPATIBLE),
            generatedBundle(),
        )
        installFlow.value = InstallState.AwaitingUserApproval(41)
    }

    private fun generatedCleanupPersistenceValues(
        savedState: SavedStateHandle,
    ): Map<String, Any?> = (generatedCleanupLegacyKeys() + CLEANUP_JOURNAL_KEY).associateWith {
        savedState.get<Any>(it)
    }

    private fun generatedCleanupPhase(savedState: SavedStateHandle): String? =
        generatedCleanupJournal(savedState)
            ?.get("phase")
            ?.jsonPrimitive
            ?.contentOrNull

    private fun hasDeferredInitialTerminal(viewModel: ManagerViewModel): Boolean {
        val field = ManagerViewModel::class.java.getDeclaredField("deferredInitialTerminals")
        field.isAccessible = true
        val deferred = field.get(viewModel) ?: return false
        return (deferred as? Collection<*>)?.isNotEmpty() ?: true
    }

    private fun generatedCleanupInstallSession(savedState: SavedStateHandle): Int? =
        generatedCleanupJournal(savedState)
            ?.get("installSessionId")
            ?.jsonPrimitive
            ?.contentOrNull
            ?.toIntOrNull()

    private fun generatedCleanupDestinationKind(savedState: SavedStateHandle): String? =
        generatedCleanupJournal(savedState)
            ?.get("destination")
            ?.jsonObject
            ?.get("kind")
            ?.jsonPrimitive
            ?.contentOrNull

    private fun generatedCleanupDestinationPackage(savedState: SavedStateHandle): String? =
        generatedCleanupJournal(savedState)
            ?.get("destination")
            ?.jsonObject
            ?.get("packageName")
            ?.jsonPrimitive
            ?.contentOrNull

    private fun generatedCleanupJournal(savedState: SavedStateHandle) =
        savedState.get<String>(CLEANUP_JOURNAL_KEY)
            ?.let(Json::parseToJsonElement)
            ?.jsonObject

    private fun rewriteGeneratedCleanupReviewToken(
        savedState: SavedStateHandle,
        reviewToken: String,
    ) {
        val journal = requireNotNull(generatedCleanupJournal(savedState))
        savedState[CLEANUP_JOURNAL_KEY] =
            JsonObject(journal + ("reviewToken" to JsonPrimitive(reviewToken))).toString()
    }

    private fun rewriteGeneratedCleanupInstallSession(
        savedState: SavedStateHandle,
        sessionId: Int,
    ) {
        val journal = requireNotNull(generatedCleanupJournal(savedState))
        savedState[CLEANUP_JOURNAL_KEY] =
            JsonObject(journal + ("installSessionId" to JsonPrimitive(sessionId))).toString()
    }

    private fun generatedCleanupDeathSnapshot(
        savedState: SavedStateHandle,
    ) = SavedStateHandle(
        (
            setOf(
                ManagerViewModel.REVIEW_TOKEN_KEY,
                ManagerViewModel.INSTALL_SESSION_ID_KEY,
                ManagerViewModel.CONVERSION_TOKEN_KEY,
                ManagerViewModel.CONVERSION_ROUTE_KEY,
                ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY,
                ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY,
                ManagerViewModel.INSTALL_OWNER_CONVERSION_TOKEN_KEY,
                CLEANUP_JOURNAL_KEY,
            ) + generatedCleanupLegacyKeys()
        ).associateWith { savedState.get<Any>(it) },
    )

    private fun generatedCleanupLegacyKeys() = setOf(
        ManagerViewModel.GENERATED_CLEANUP_TOKEN_KEY,
        ManagerViewModel.GENERATED_CLEANUP_REVIEW_TOKEN_KEY,
        ManagerViewModel.GENERATED_CLEANUP_PHASE_KEY,
        ManagerViewModel.GENERATED_CLEANUP_DESTINATION_KEY,
        ManagerViewModel.GENERATED_CLEANUP_PACKAGE_KEY,
        ManagerViewModel.GENERATED_CLEANUP_INSTALL_SESSION_ID_KEY,
    )

    private fun documentSaver(
        output: OutputStream,
    ): suspend (String, String, (OutputStream) -> String?) -> GeneratedDocumentSaveResult =
        { _, expectedHash, copy ->
            if (copy(output) == expectedHash) {
                GeneratedDocumentSaveResult.Success
            } else {
                GeneratedDocumentSaveResult.Failure(cleanupFailed = false)
            }
        }

    private fun conversionAnalysis(
        token: String = TOKEN,
    ) = ConversionBeginResult.AnalysisReady(
        token = token,
        profile = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = "30.0.2.61",
            versionCode = 1,
            majorVersion = 30,
            supportedApis = emptySet(),
            wrapperContract = WrapperContract.complete(),
        ),
        report = CompatibilityReport(
            status = CompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = "확장",
            extensionVersion = "1.0",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            conversionState = ConversionState.PASSTHROUGH,
        ),
        selectedAutomaticAdapters = emptySet(),
        requiredApprovals = emptySet(),
        identityRequirement = IdentityRequirement.Stable("0123456789ab"),
    )

    private fun forcedConversionAnalysis() = conversionAnalysis().copy(
        report = conversionAnalysis().report.copy(
            status = CompatibilityStatus.UNKNOWN,
            issues = listOf(
                CompatibilityIssue(
                    code = "api_usage_dynamic",
                    message = "dynamic API",
                    blocking = true,
                ),
            ),
            unverifiedApis = setOf("scripting"),
            sourceSha256 = "a".repeat(64),
            browserProfileSha256 = conversionAnalysis().profile.canonicalSha256(),
            conversionState = ConversionState.REQUIRES_APPROVAL,
        ),
        forceChallenge = ConversionForceChallenge(TOKEN, "challenge"),
    )

    private fun existingTarget() = ExistingConversionTarget(
        identitySha256 = "e".repeat(64),
        displayName = "기존 대상",
        extensionId = EXTENSION_ID,
        packageName = PACKAGE,
        wrapperSignerSha256 = "f".repeat(64),
    )

    private fun legacyIdentity() = LegacyConversionIdentity(
        identitySha256 = "9".repeat(64),
        displayName = "이전 변환 대상",
        mode = InstallationIdentityMode.CONVERTED,
        packageName = PACKAGE,
        extensionId = EXTENSION_ID,
        wrapperSignerSha256 = "f".repeat(64),
    )

    private fun apkTransactionJournal(
        token: String,
        phase: String,
    ): Map<String, Any> = mapOf(
        "install_apk_transaction_token" to token,
        "install_apk_transaction_owner" to 7L,
        "install_apk_transaction_launch" to 11L,
        "install_apk_transaction_destination" to "content://apk/restored",
        "install_apk_transaction_phase" to phase,
    )

    private fun review(
        status: ContractCompatibilityStatus,
        token: String = TOKEN,
    ) = ReviewBundle(
        token = token,
        packageName = PACKAGE,
        extensionId = EXTENSION_ID,
        originalExtensionId = null,
        wrapperSha256 =
            "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
        signerSha256 =
            "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee",
        name = "확장",
        version = "1.0",
        status = status,
        issues = emptyList(),
    )

    private fun forcedReview() = review(ContractCompatibilityStatus.UNKNOWN).copy(
        issues = listOf(ContractIssue("api_usage_dynamic", "dynamic API", true)),
        unverifiedApis = listOf("scripting"),
        forcedCompatibility = ContractForcedCompatibility(
            scope = "UNKNOWN_API_USAGE",
            originalStatus = ContractCompatibilityStatus.UNKNOWN,
            issueCodes = listOf("api_usage_dynamic"),
        ),
    )

    private fun catalogRecord(
        packageName: String = PACKAGE,
        compatibilityStatus: String = "COMPATIBLE",
        reportJson: String? = null,
    ) = CatalogRecord(
        packageName = packageName,
        extensionId = EXTENSION_ID,
        name = "확장",
        iconPng = ICON,
        installedVersionCode = 1,
        signerSha256 = "a".repeat(64),
        compatibilityStatus = compatibilityStatus,
        reportJson = reportJson,
        lastVerifiedAtEpochMillis = 1,
    )

    private fun compatibilityReport(status: ContractCompatibilityStatus) =
        ContractCompatibilityReport(
            schemaVersion = 3,
            status = status,
            manifestVersion = 3,
            extensionName = "확장",
            extensionVersion = "1.0",
            manifestKeyPresent = false,
            manifestKeyExtensionId = null,
            declaredPermissions = listOf("storage"),
            usedApis = listOf("tabs"),
            unsupportedApis = emptyList(),
            issues = listOf(ContractIssue("API_LIMIT", "일부 API 제한", false)),
            conversionState = "converted",
            adapterDecisions = listOf(
                ContractAdapterDecision(
                    id = "tabs",
                    version = 1,
                    risk = "behavior-change",
                    disposition = "requires-approval",
                    applied = true,
                    evidence = emptyList(),
                    changes = listOf("tabs API 변환"),
                    remainingLimitations = emptyList(),
                ),
            ),
            originalExtensionId = EXTENSION_ID,
            convertedExtensionId = EXTENSION_ID,
            sourceSha256 = "b".repeat(64),
            browserProfileSha256 = "c".repeat(64),
        )

    @Test
    fun `forced reanalysis 중 ReturnHome은 Building 소유권을 바꾸지 않는다`() =
        runTest(dispatcher.scheduler) {
            val deferred = CompletableDeferred<ConversionBeginResult>()
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = forcedConversionAnalysis()
                conversionBuildResult = ConversionBuildResult.Failure(ConversionFailureCode.PACKAGE)
                conversionAnalyzeDeferred = deferred
                ignoreConversionAnalyzeCancellation = true
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.ConfirmConversionForce)
            viewModel.accept(ManagerAction.BuildConversion)
            testScheduler.runCurrent()
            viewModel.accept(ManagerAction.ReturnHome)
            deferred.complete(forcedConversionAnalysis().copy(
                forceChallenge = ConversionForceChallenge(TOKEN, "late"),
            ))
            advanceUntilIdle()
            assertEquals(ManagerRoute.ConversionAnalysis(TOKEN), viewModel.state.value.route)
            val ready = assertIs<ConversionUiState.AnalysisReady>(
                viewModel.state.value.conversionState,
            )
            assertEquals("late", ready.forceChallenge?.nonce)
            assertFalse(repository.conversionForceRevocations.any { it.nonce == "late" })
        }

    @Test
    fun `Building 중 route replacement는 모두 no-op이고 정식 취소 뒤 새 변환이 가능하다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = forcedConversionAnalysis()
                suspendFirstConversionBuild = true
            }
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.ConfirmConversionForce)
            viewModel.accept(ManagerAction.BuildConversion)
            testScheduler.runCurrent()
            val ownedState = viewModel.state.value
            val revocations = repository.conversionForceRevocations.size

            listOf(
                ManagerAction.ReturnHome,
                ManagerAction.OpenReport(PACKAGE),
                ManagerAction.OpenConversionIdentityManagement,
                ManagerAction.OpenConversionInput,
                ManagerAction.OpenSixFilePicker,
                ManagerAction.OpenDraftFilePicker,
                ManagerAction.OpenConversionFilePicker,
                ManagerAction.CancelReview,
                ManagerAction.FinishConversion,
            ).forEach(viewModel::accept)
            var importCalled = false
            viewModel.startImport {
                importCalled = true
                ManagerImportResult.Failure(ImportFailure(ImportFailureCode.SOURCE_UNREADABLE))
            }
            testScheduler.runCurrent()

            assertEquals(ownedState, viewModel.state.value)
            assertFalse(importCalled)
            assertEquals(revocations, repository.conversionForceRevocations.size)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))

            viewModel.accept(ManagerAction.CancelConversion)
            advanceUntilIdle()
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
            assertEquals(ConversionUiState.Idle, viewModel.state.value.conversionState)
            viewModel.accept(ManagerAction.OpenConversionInput)
            assertEquals(ManagerRoute.ConversionInput, viewModel.state.value.route)
        }

    @Test
    fun `forced reanalysis exception never leaves Building`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                conversionBeginResult = forcedConversionAnalysis()
                conversionBuildResult = ConversionBuildResult.Failure(ConversionFailureCode.KEYSTORE)
                conversionAnalyzeFailure = IOException("reanalyze failed")
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.accept(ManagerAction.OpenConversionInput)
            viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
            viewModel.accept(ManagerAction.AnalyzeConversionUrl)
            advanceUntilIdle()
            viewModel.accept(ManagerAction.ConfirmConversionForce)
            viewModel.accept(ManagerAction.BuildConversion)
            advanceUntilIdle()
            assertIs<ConversionUiState.Failed>(viewModel.state.value.conversionState)
        }

    @Test
    fun `Cancel and OpenReport synchronously revoke forced approval`() =
        runTest(dispatcher.scheduler) {
            suspend fun ready(repository: FakeManagerRepository): ManagerViewModel {
                val viewModel = ManagerViewModel(repository, SavedStateHandle())
                advanceUntilIdle()
                viewModel.accept(ManagerAction.OpenConversionInput)
                viewModel.accept(ManagerAction.ConversionUrlChanged("https://example.test"))
                viewModel.accept(ManagerAction.AnalyzeConversionUrl)
                advanceUntilIdle()
                viewModel.accept(ManagerAction.ConfirmConversionForce)
                return viewModel
            }

            val cancelRepository = FakeManagerRepository().apply {
                conversionBeginResult = forcedConversionAnalysis()
            }
            ready(cancelRepository).accept(ManagerAction.CancelConversion)
            assertEquals(1, cancelRepository.conversionForceRevocations.size)

            val reportRepository = FakeManagerRepository().apply {
                conversionBeginResult = forcedConversionAnalysis()
                catalogFlow.tryEmit(listOf(catalogRecord()))
            }
            ready(reportRepository).accept(ManagerAction.OpenReport(PACKAGE))
            assertEquals(1, reportRepository.conversionForceRevocations.size)
        }

    private companion object {
        const val TOKEN = "11111111-1111-1111-1111-111111111111"
        const val SECOND_TOKEN = "22222222-2222-2222-2222-222222222222"
        const val CLEANUP_JOURNAL_KEY = "generated_cleanup_journal"
        val EXTENSION_ID = "a".repeat(32)
        val PACKAGE = "dev.sixbridge.ext.$EXTENSION_ID"
        val SECOND_PACKAGE = "$PACKAGE.second"
        val ICON = byteArrayOf(1, 2, 3)
    }
}

private class FakeManagerRepository : ManagerRepository {
    val catalogFlow = MutableSharedFlow<List<CatalogRecord>>(replay = 1)
    val installFlow = MutableStateFlow<InstallState>(InstallState.Idle)
    var restoreResult = ManagerRestoreResult()
    var importResult: ManagerImportResult =
        ManagerImportResult.Failure(ImportFailure(ImportFailureCode.SOURCE_UNREADABLE))
    var initialized = false
    var initializeException: RuntimeException? = null
    var initializeFailuresRemaining = 0
    var initializeRequests = 0
    var initializeDeferred: CompletableDeferred<ManagerRestoreResult>? = null
    var suspendInitialize = false
    var ignoreInitializeCancellation = false
    var restoreArguments: Pair<String?, Int?>? = null
    var uriImportRequests = 0
    var installRequests = 0
    var completedInstallRequests = 0
    var installResult: InstallState = InstallState.Idle
    var emitInstallResultOnReturn = false
    var cancelInstallRequests = 0
    val cancelInstallArguments = mutableListOf<Pair<String, Int?>>()
    var lastPartialConfirmed = false
    var lastForceCompatibilityConfirmed = false
    var resumeRequests = 0
    var reconcileRequests = 0
    var legacyIdentities = emptyList<LegacyConversionIdentity>()
    var legacyListDeferred: CompletableDeferred<List<LegacyConversionIdentity>>? = null
    var legacyListRequests = 0
    val legacyCleanupRequests = mutableListOf<String>()
    var legacyCleanupResult: ConversionIdentityCleanupResult =
        ConversionIdentityCleanupResult.NothingOwned
    var installApkExport: InstallApkExport? = null
    var suspendInstallApkExport = false
    var installApkExportRequests = 0
    val installApkExportedTokens = mutableListOf<String>()
    var reconcileManualCalls = 0
    var manualSystemInstallResult: InstallState? = null
    var uninstallOutcome: UninstallOutcome = UninstallOutcome.CancelledOrStillInstalled
    val uninstallPackages = mutableListOf<String>()
    val uninstallLaunches = mutableListOf<RepositoryLaunch>()
    val reconcileUninstallPackages = mutableListOf<String>()
    var suspendUninstallReconcile = false
    var ignoreUninstallReconcileCancellation = false
    var navigationResult: RepositoryNavigationResult = RepositoryNavigationResult.Launch(
        RepositoryLaunch(
            destination = LaunchDestination.SAMSUNG_ADD_ONS,
            intentFactory = { error("unit test에서 Android Intent를 만들지 않습니다.") },
        ),
    )
    var conversionBeginResult: ConversionBeginResult =
        ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)
    var beginConversionDeferred: CompletableDeferred<ConversionBeginResult>? = null
    var conversionBuildResult: ConversionBuildResult =
        ConversionBuildResult.Failure(ConversionFailureCode.INPUT_INVALID)
    var conversionForceApproval = OnDeviceConversionApproval(
        forceProof = ConversionForceProof("proof"),
    )
    val conversionForceRequests = mutableListOf<ConversionForceRequest>()
    val conversionForceRevocations = mutableListOf<ConversionForceChallenge>()
    val conversionForceRevocationApprovals = mutableListOf<OnDeviceConversionApproval?>()
    var conversionChallengeSequence = 0
    var conversionAnalyzeRequests = 0
    var conversionAnalyzeDeferred: CompletableDeferred<ConversionBeginResult>? = null
    var conversionAnalyzeFailure: IOException? = null
    var ignoreConversionAnalyzeCancellation = false
    val conversionBeginResults = mutableListOf<ConversionBeginResult>()
    var suspendFirstConversionBegin = false
    var ignoreFirstConversionBeginCancellation = false
    var conversionCleanupCancellationToken: String? = null
    var suspendFirstConversionBuild = false
    var ignoreFirstConversionBuildCancellation = false
    var restoredConversion: RestoredConversion? = null
    var restoreConversionDeferred: CompletableDeferred<RestoredConversion?>? = null
    var restoreConversionFailure: IOException? = null
    var restoreConversionRequests = 0
    val restoreConversionImportReviewRequests = mutableListOf<Boolean>()
    val conversionRequests = mutableListOf<OnDeviceInputRequest>()
    val conversionBuildRequests = mutableListOf<ConversionBuildRequest>()
    val discardedConversions = mutableListOf<String>()
    var discardConversionResult = true
    var discardConversionException: IOException? = null
    val discardConversionResults = mutableMapOf<String, Boolean>()
    var beforeDiscardConversion: ((String) -> Unit)? = null
    var suspendDiscardConversion = false
    var generatedExists = true
    var copiedConversionHash: String? = null
    val copiedConversionTokens = mutableListOf<String>()
    val generatedShareSnapshotRequests = mutableListOf<String>()
    var generatedShareSnapshot: File? = null
    var suspendGeneratedShareSnapshot = false
    var generatedShareSnapshotException: Throwable? = null
    val lifecycleEvents = mutableListOf<String>()
    var suspendImport = false
    var suspendInstall = false
    var suspendCancelInstall = false
    var emitCancelledOnCancelInstall = false
    var terminalStateOnCancelInstall: InstallState? = null
    var terminalStateOnCancelInstallRequest: Int? = null
    var yieldAfterTerminalStateOnCancelInstall = false
    var terminalOwnerImportToken: String? = null
    var terminalOwnerSessionId: Int? = null
    var terminalOwnerPackageName: String? = null
    var terminalOwnerState: InstallState? = null
    var terminalOwnerProofConfigured = false
    var suspendTerminalOwnerAfterRequests: Int? = null
    private val terminalOwnerGate = MutableSharedFlow<Unit>()
    val terminalOwnerArguments = mutableListOf<Pair<String, Int?>>()
    val currentTerminalReceiptArguments =
        mutableListOf<Triple<String, Int?, String>>()
    var currentTerminalReceiptResult: TerminalReceiptOwnerResult? = null
    var rawReadyTerminal: RestoredInstallTerminal? = null
    val rawReadyTerminalArguments =
        mutableListOf<Triple<String, Int?, String>>()
    var terminalReceiptActive = true
    var terminalReceiptAcknowledged = false
    var terminalAcknowledgeResult = true
    val terminalAcknowledgeArguments =
        mutableListOf<Triple<String, Int?, InstallState>>()
    val terminalFinalizeArguments =
        mutableListOf<Triple<String, Int?, InstallState>>()
    var terminalFinalizeResult = true
    var normalHandoff: NormalInstallHandoff? = null
    var failNextNormalHandoffCompare = false
    var normalHandoffRebaseResult = true
    val normalRebaseAttempts =
        mutableListOf<Triple<NormalInstallHandoff, NormalInstallHandoff, Boolean>>()
    var cancelInstallResult = true
    var cancelInstallOutcome: InstallCleanupResult? = null
    val cancelInstallOutcomes = mutableListOf<InstallCleanupResult>()
    var cancelInstallException: RuntimeException? = null
    var discardReviewResult = true
    var beforeDiscardReview: ((String) -> Unit)? = null
    val discardReviewResults = mutableMapOf<String, Boolean>()
    var ignoreImportCancellation = false
    private val importGate = MutableSharedFlow<Unit>()
    private val installGate = MutableSharedFlow<Unit>()
    private val installApkExportGate = MutableSharedFlow<Unit>()
    private val cancelInstallGate = MutableSharedFlow<Unit>()
    private val initializeGate = MutableSharedFlow<Unit>()
    private val firstConversionBeginGate = MutableSharedFlow<Unit>()
    private val firstConversionBuildGate = MutableSharedFlow<Unit>()
    private val discardConversionGate = MutableSharedFlow<Unit>()
    private val generatedShareSnapshotGate = MutableSharedFlow<Unit>()
    private val uninstallReconcileGate = CompletableDeferred<Unit>()
    private val launchChannel = Channel<RepositoryLaunch>(Channel.UNLIMITED)

    override val catalog = catalogFlow
    override val installState = installFlow
    override val launches = launchChannel.receiveAsFlow()

    override suspend fun initialize(reviewToken: String?, installSessionId: Int?): ManagerRestoreResult {
        initialized = true
        initializeRequests += 1
        restoreArguments = reviewToken to installSessionId
        if (initializeFailuresRemaining > 0) {
            initializeFailuresRemaining -= 1
            throw IllegalStateException("transient initialize failure")
        }
        initializeException?.let { throw it }
        initializeDeferred?.let { return it.await() }
        if (suspendInitialize) {
            if (ignoreInitializeCancellation) {
                withContext(NonCancellable) { initializeGate.first() }
            } else {
                initializeGate.first()
            }
        }
        return restoreResult
    }

    suspend fun completeInitialize() {
        initializeGate.emit(Unit)
    }

    override suspend fun import(uri: Uri): ManagerImportResult {
        uriImportRequests += 1
        return nextImport()
    }

    override suspend fun beginConversion(request: OnDeviceInputRequest): ConversionBeginResult {
        val index = conversionRequests.size
        conversionRequests += request
        beginConversionDeferred?.let { pending ->
            return withContext(NonCancellable) { pending.await() }
        }
        if (index == 0 && suspendFirstConversionBegin) {
            if (ignoreFirstConversionBeginCancellation) {
                withContext(NonCancellable) { firstConversionBeginGate.first() }
            } else {
                try {
                    firstConversionBeginGate.first()
                } catch (error: CancellationException) {
                    conversionCleanupCancellationToken?.let {
                        throw ConversionCleanupCancellationException(it, error)
                    }
                    throw error
                }
            }
        }
        return conversionBeginResults.getOrNull(index) ?: conversionBeginResult
    }

    suspend fun completeFirstConversionBegin() {
        firstConversionBeginGate.emit(Unit)
    }

    override suspend fun approveConversion(
        token: String,
        approvedAdapterIds: Set<String>,
        partialConfirmed: Boolean,
        identitySelection: InstallationIdentitySelection?,
    ): ConversionBuildResult {
        val index = conversionBuildRequests.size
        conversionBuildRequests += ConversionBuildRequest(
            token,
            approvedAdapterIds,
            partialConfirmed,
            identitySelection,
            ConversionAuthorization.NONE,
        )
        if (index == 0 && suspendFirstConversionBuild) {
            if (ignoreFirstConversionBuildCancellation) {
                withContext(NonCancellable) { firstConversionBuildGate.first() }
            } else {
                firstConversionBuildGate.first()
            }
        }
        return conversionBuildResult
    }

    override fun authorizeConversionForce(
        challenge: ConversionForceChallenge,
        approvedAdapterIds: Set<String>,
        identitySelection: InstallationIdentitySelection?,
    ): OnDeviceConversionApproval {
        conversionForceRequests += ConversionForceRequest(
            challenge,
            approvedAdapterIds,
            identitySelection,
        )
        return conversionForceApproval
    }

    override fun revokeConversionForce(
        challenge: ConversionForceChallenge,
        approval: OnDeviceConversionApproval?,
        reissue: Boolean,
    ): ConversionForceChallenge? {
        conversionForceRevocations += challenge
        conversionForceRevocationApprovals += approval
        return if (reissue) {
            ConversionForceChallenge(challenge.token, "reissued-${++conversionChallengeSequence}")
        } else {
            null
        }
    }

    override suspend fun analyzeConversion(token: String): ConversionBeginResult {
        conversionAnalyzeRequests += 1
        conversionAnalyzeFailure?.let { throw it }
        conversionAnalyzeDeferred?.let {
            return if (ignoreConversionAnalyzeCancellation) {
                withContext(NonCancellable) { it.await() }
            } else {
                it.await()
            }
        }
        return when (val result = conversionBeginResult) {
            is ConversionBeginResult.AnalysisReady -> result.copy(
                forceChallenge = result.forceChallenge?.copy(
                    nonce = "reanalyzed-${++conversionChallengeSequence}",
                ),
            )
            is ConversionBeginResult.Failure -> result
        }
    }

    override suspend fun approveConversion(
        token: String,
        approvedAdapterIds: Set<String>,
        partialConfirmed: Boolean,
        identitySelection: InstallationIdentitySelection?,
        approval: OnDeviceConversionApproval,
    ): ConversionBuildResult {
        val index = conversionBuildRequests.size
        conversionBuildRequests += ConversionBuildRequest(
            token = token,
            approvedAdapterIds = approvedAdapterIds,
            partialConfirmed = partialConfirmed,
            identitySelection = identitySelection,
            approval = approval,
        )
        if (index == 0 && suspendFirstConversionBuild) {
            if (ignoreFirstConversionBuildCancellation) {
                withContext(NonCancellable) { firstConversionBuildGate.first() }
            } else {
                firstConversionBuildGate.first()
            }
        }
        return conversionBuildResult
    }

    override suspend fun approveConversion(
        token: String,
        approvedAdapterIds: Set<String>,
        partialConfirmed: Boolean,
        identitySelection: InstallationIdentitySelection?,
        authorization: ConversionAuthorization,
    ): ConversionBuildResult {
        val index = conversionBuildRequests.size
        conversionBuildRequests += ConversionBuildRequest(
            token,
            approvedAdapterIds,
            partialConfirmed,
            identitySelection,
            authorization,
        )
        if (index == 0 && suspendFirstConversionBuild) {
            if (ignoreFirstConversionBuildCancellation) {
                withContext(NonCancellable) { firstConversionBuildGate.first() }
            } else {
                firstConversionBuildGate.first()
            }
        }
        return conversionBuildResult
    }

    suspend fun completeFirstConversionBuild() {
        firstConversionBuildGate.emit(Unit)
    }

    override suspend fun restoreConversion(importReview: Boolean): RestoredConversion? {
        restoreConversionRequests += 1
        restoreConversionImportReviewRequests += importReview
        restoreConversionFailure?.let { throw it }
        restoreConversionDeferred?.let { return it.await() }
        return restoredConversion
    }

    override suspend fun discardConversion(token: String): Boolean {
        lifecycleEvents += "discardConversion:$token"
        discardedConversions += token
        beforeDiscardConversion?.invoke(token)
        discardConversionException?.let { throw it }
        if (suspendDiscardConversion) discardConversionGate.first()
        val result = discardConversionResults[token] ?: discardConversionResult
        if (result) generatedExists = false
        return result
    }

    suspend fun completeDiscardConversion() {
        discardConversionGate.emit(Unit)
    }

    override fun copyGeneratedConversion(token: String, output: OutputStream): String? {
        copiedConversionTokens += token
        output.write(byteArrayOf(1, 2, 3))
        return copiedConversionHash
    }

    override suspend fun createGeneratedShareSnapshot(token: String): File? {
        generatedShareSnapshotRequests += token
        if (suspendGeneratedShareSnapshot) generatedShareSnapshotGate.first()
        generatedShareSnapshotException?.let { throw it }
        return generatedShareSnapshot ?: when (val restored = restoredConversion) {
            is RestoredConversion.BundleReady -> restored.generated.file
            is RestoredConversion.GeneratedPending -> restored.generated.file
            is RestoredConversion.Analysis,
            RestoredConversion.InterruptedDiscarded,
            is RestoredConversion.InterruptedCleanupPending,
            null,
            -> null
        }
    }

    suspend fun completeGeneratedShareSnapshot() {
        generatedShareSnapshotGate.emit(Unit)
    }

    suspend fun nextImport(): ManagerImportResult {
        if (suspendImport) {
            if (ignoreImportCancellation) {
                withContext(NonCancellable) { importGate.first() }
            } else {
                importGate.first()
            }
        }
        return importResult
    }

    suspend fun completeImport() {
        importGate.emit(Unit)
    }

    override suspend fun install(
        review: ReviewBundle,
        partialConfirmed: Boolean,
    ): InstallState {
        lifecycleEvents += "install:start"
        installRequests += 1
        lastPartialConfirmed = partialConfirmed
        try {
            if (suspendInstall) installGate.first()
            completedInstallRequests += 1
            if (emitInstallResultOnReturn) installFlow.value = installResult
            return installResult
        } finally {
            lifecycleEvents += "install:end"
        }
    }

    override suspend fun install(
        review: ReviewBundle,
        partialConfirmed: Boolean,
        forceCompatibilityConfirmed: Boolean,
    ): InstallState {
        lastForceCompatibilityConfirmed = forceCompatibilityConfirmed
        return install(review, partialConfirmed)
    }

    override suspend fun installApkExport(
        importToken: String,
    ): InstallApkExport? {
        installApkExportRequests += 1
        if (suspendInstallApkExport) installApkExportGate.first()
        return installApkExport?.takeIf { it.importToken == importToken }
    }

    suspend fun completeInstallApkExport() {
        installApkExportGate.emit(Unit)
    }

    override fun markInstallApkExported(importToken: String): Boolean {
        installApkExportedTokens += importToken
        installFlow.value = InstallState.AwaitingManualInstall(importToken)
        return true
    }

    override suspend fun reconcileManualInstall(importToken: String): InstallState? {
        reconcileManualCalls += 1
        return manualSystemInstallResult?.also { installFlow.value = it }
    }

    suspend fun completeInstall() {
        installGate.emit(Unit)
    }

    suspend fun completeTerminalOwnerProof() {
        terminalOwnerGate.emit(Unit)
    }

    override suspend fun cancelInstall(
        expectedReviewToken: String,
        expectedSessionId: Int?,
    ): InstallCleanupResult {
        lifecycleEvents += "cancelInstall"
        cancelInstallRequests += 1
        cancelInstallArguments += expectedReviewToken to expectedSessionId
        if (suspendCancelInstall) cancelInstallGate.first()
        cancelInstallException?.let { throw it }
        if (emitCancelledOnCancelInstall) installFlow.value = InstallState.Cancelled
        if (terminalStateOnCancelInstallRequest == null ||
            terminalStateOnCancelInstallRequest == cancelInstallRequests
        ) {
            terminalStateOnCancelInstall?.let {
                installFlow.value = it
                if (yieldAfterTerminalStateOnCancelInstall) yield()
            }
        }
        return cancelInstallOutcomes.removeFirstOrNull() ?: cancelInstallOutcome ?: if (
            cancelInstallResult
        ) {
            InstallCleanupResult.Cleaned
        } else {
            InstallCleanupResult.CleanupFailed
        }
    }

    override suspend fun terminalForOwner(
        expectedReviewToken: String,
        expectedSessionId: Int?,
    ): InstallTerminalOwnerResult {
        terminalOwnerArguments += expectedReviewToken to expectedSessionId
        suspendTerminalOwnerAfterRequests?.let { completedRequests ->
            if (terminalOwnerArguments.size == completedRequests + 1) {
                terminalOwnerGate.first()
            }
        }
        if (!terminalOwnerProofConfigured) {
            val state = installFlow.value
            return if (!(state is InstallState.Installed ||
                state == InstallState.Cancelled ||
                state is InstallState.Failed
            )) {
                InstallTerminalOwnerResult.OwnerMismatch
            } else if (expectedSessionId == null) {
                InstallTerminalOwnerResult.OwnerSessionAssigned(41, state)
            } else {
                InstallTerminalOwnerResult.Owned(state)
            }
        }
        val state = terminalOwnerState ?: return InstallTerminalOwnerResult.OwnerMismatch
        val importToken =
            terminalOwnerImportToken ?: return InstallTerminalOwnerResult.OwnerMismatch
        val sessionId = terminalOwnerSessionId
        if (importToken != expectedReviewToken || installFlow.value != state) {
            return InstallTerminalOwnerResult.OwnerMismatch
        }
        if (expectedSessionId == null) {
            return sessionId?.let {
                InstallTerminalOwnerResult.OwnerSessionAssigned(it, state)
            } ?: InstallTerminalOwnerResult.Owned(state)
        }
        return if (expectedSessionId == sessionId) {
            InstallTerminalOwnerResult.Owned(state)
        } else {
            InstallTerminalOwnerResult.OwnerMismatch
        }
    }

    override suspend fun rawReadyTerminalForOwner(
        expectedReviewToken: String,
        expectedSessionId: Int?,
        expectedPackageName: String,
    ): RestoredInstallTerminal? {
        rawReadyTerminalArguments += Triple(
            expectedReviewToken,
            expectedSessionId,
            expectedPackageName,
        )
        return rawReadyTerminal?.takeIf {
            it.importToken == expectedReviewToken &&
                it.sessionId == expectedSessionId &&
                it.packageName == expectedPackageName
        }
    }

    override suspend fun currentTerminalReceiptForOwner(
        expectedReviewToken: String,
        expectedSessionId: Int?,
        expectedPackageName: String,
    ): TerminalReceiptOwnerResult {
        currentTerminalReceiptArguments += Triple(
            expectedReviewToken,
            expectedSessionId,
            expectedPackageName,
        )
        currentTerminalReceiptResult?.let { return it }
        if (!terminalReceiptActive) return TerminalReceiptOwnerResult.Absent
        if (!terminalOwnerProofConfigured ||
            terminalOwnerImportToken != expectedReviewToken ||
            terminalOwnerState != installFlow.value ||
            expectedPackageName != (
                terminalOwnerPackageName
                    ?: rawReadyTerminal?.packageName
                    ?: normalHandoff?.packageName
                    ?: restoreResult.terminal?.packageName
                )
        ) {
            return TerminalReceiptOwnerResult.OwnerMismatch
        }
        val sessionId = terminalOwnerSessionId
        if (expectedSessionId == null && sessionId != null) {
            return TerminalReceiptOwnerResult.OwnerSessionAssigned(sessionId)
        }
        if (expectedSessionId != sessionId) {
            return TerminalReceiptOwnerResult.OwnerMismatch
        }
        return TerminalReceiptOwnerResult.Owned(
            RestoredInstallTerminal(
                importToken = expectedReviewToken,
                sessionId = sessionId,
                packageName = expectedPackageName,
                state = requireNotNull(terminalOwnerState),
                stage = if (terminalReceiptAcknowledged) {
                    FinalReceiptStage.ACKNOWLEDGED
                } else {
                    FinalReceiptStage.READY
                },
            ),
        )
    }

    override suspend fun acknowledgeTerminal(
        expectedReviewToken: String,
        expectedSessionId: Int?,
        expectedState: InstallState,
    ): Boolean {
        terminalAcknowledgeArguments += Triple(
            expectedReviewToken,
            expectedSessionId,
            expectedState,
        )
        val owned = terminalReceiptActive &&
            !terminalReceiptAcknowledged &&
            if (terminalOwnerProofConfigured) {
                terminalOwnerImportToken == expectedReviewToken &&
                    terminalOwnerSessionId == expectedSessionId &&
                    terminalOwnerState == expectedState &&
                    installFlow.value == expectedState
            } else {
                installFlow.value == expectedState
        }
        if (!owned || !terminalAcknowledgeResult) return false
        terminalReceiptAcknowledged = true
        return true
    }

    override suspend fun finalizeTerminal(
        expectedReviewToken: String,
        expectedSessionId: Int?,
        expectedState: InstallState,
    ): Boolean {
        terminalFinalizeArguments += Triple(
            expectedReviewToken,
            expectedSessionId,
            expectedState,
        )
        if (!terminalReceiptActive) return true
        if (!terminalReceiptAcknowledged || !terminalFinalizeResult) return false
        terminalReceiptActive = false
        terminalReceiptAcknowledged = false
        return true
    }

    override fun compareAndSetNormalHandoff(
        expected: NormalInstallHandoff?,
        replacement: NormalInstallHandoff?,
    ): Boolean {
        if (failNextNormalHandoffCompare) {
            failNextNormalHandoffCompare = false
            return false
        }
        if (normalHandoff != expected) return false
        normalHandoff = replacement
        return true
    }

    override fun rebaseNormalHandoff(
        expected: NormalInstallHandoff,
        replacement: NormalInstallHandoff,
    ): Boolean {
        val succeeded = normalHandoffRebaseResult && normalHandoff == expected
        normalRebaseAttempts += Triple(expected, replacement, succeeded)
        if (succeeded) normalHandoff = replacement
        return succeeded
    }

    suspend fun completeCancelInstall() {
        cancelInstallGate.emit(Unit)
    }

    val discardedTokens = mutableListOf<String>()
    val discardAttempts = mutableListOf<String>()

    override fun discardReview(token: String): Boolean {
        lifecycleEvents += "discardReview:$token"
        discardAttempts += token
        beforeDiscardReview?.invoke(token)
        val result = discardReviewResults[token] ?: discardReviewResult
        if (result) discardedTokens += token
        return result
    }

    override fun unknownSourcesSettings(): RepositoryLaunch = RepositoryLaunch(
        destination = LaunchDestination.UNKNOWN_SOURCES_SETTINGS,
        intentFactory = { error("unit test에서 Android Intent를 만들지 않습니다.") },
    )

    override suspend fun resumeAfterUnknownSourcesSettings() {
        resumeRequests += 1
    }

    override fun uninstall(packageName: String): RepositoryLaunch {
        uninstallPackages += packageName
        return RepositoryLaunch(
            destination = LaunchDestination.UNINSTALL,
            intentFactory = { error("unit test에서 Android Intent를 만들지 않습니다.") },
        ).also(uninstallLaunches::add)
    }

    override suspend fun reconcileUninstall(packageName: String): UninstallOutcome {
        reconcileUninstallPackages += packageName
        if (suspendUninstallReconcile) {
            if (ignoreUninstallReconcileCancellation) {
                withContext(NonCancellable) { uninstallReconcileGate.await() }
            } else {
                uninstallReconcileGate.await()
            }
        }
        return uninstallOutcome
    }

    fun completeUninstallReconcile() {
        uninstallReconcileGate.complete(Unit)
    }

    override fun samsungAddOns(): RepositoryNavigationResult = navigationResult

    override suspend fun reconcileCatalog() {
        reconcileRequests += 1
    }

    override suspend fun listLegacyUnknown(): List<LegacyConversionIdentity> {
        legacyListRequests += 1
        return legacyListDeferred?.await() ?: legacyIdentities
    }

    override suspend fun discardLegacyUnknown(
        identitySha256: String,
    ): ConversionIdentityCleanupResult {
        legacyCleanupRequests += identitySha256
        return legacyCleanupResult.also { result ->
            if (result == ConversionIdentityCleanupResult.Removed) {
                legacyIdentities = legacyIdentities.filterNot {
                    it.identitySha256 == identitySha256
                }
            }
        }
    }

    fun cleanupRequestCount(): Int =
        cancelInstallRequests + discardAttempts.size + discardedConversions.size

    fun emitLaunch(
        destination: LaunchDestination,
        installSessionId: Int? = null,
    ) {
        launchChannel.trySend(
            RepositoryLaunch(
                destination = destination,
                intentFactory = { error("unit test에서 Android Intent를 만들지 않습니다.") },
                installSessionId = installSessionId,
            ),
        )
    }
}

private data class ConversionBuildRequest(
    val token: String,
    val approvedAdapterIds: Set<String>,
    val partialConfirmed: Boolean,
    val identitySelection: InstallationIdentitySelection?,
    val authorization: ConversionAuthorization = ConversionAuthorization.NONE,
    val approval: OnDeviceConversionApproval = OnDeviceConversionApproval.NONE,
)

private data class ConversionForceRequest(
    val challenge: ConversionForceChallenge,
    val approvedAdapterIds: Set<String>,
    val identitySelection: InstallationIdentitySelection?,
)

private fun unitTestUri(): Uri {
    return android.net.TestUri()
}
