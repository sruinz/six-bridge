package dev.sixbridge.manager.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.manager.app.ManagerImportResult
import dev.sixbridge.manager.app.ManagerRepository
import dev.sixbridge.manager.app.ManagerRestoreResult
import dev.sixbridge.manager.app.RepositoryLaunch
import dev.sixbridge.manager.app.RepositoryNavigationResult
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.uninstall.UninstallOutcome
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
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
    fun `설치 성공 취소 실패를 각각 상태와 한국어 메시지로 반영한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            repository.installFlow.value = InstallState.Installed(PACKAGE)
            advanceUntilIdle()
            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)

            repository.installFlow.value = InstallState.Cancelled
            advanceUntilIdle()
            assertEquals(UserMessageKind.INSTALL_CANCELLED, viewModel.state.value.message?.kind)

            repository.installFlow.value = InstallState.Failed("SIGNER_MISMATCH", "raw")
            advanceUntilIdle()
            assertEquals(UserMessageKind.UPDATE_IDENTITY, viewModel.state.value.message?.kind)

            repository.installFlow.value = InstallState.Failed("VERSION_NOT_NEWER", "raw")
            advanceUntilIdle()
            assertEquals(UserMessageKind.UPDATE_IDENTITY, viewModel.state.value.message?.kind)

            repository.installFlow.value = InstallState.Failed("WRITE_FAILED", "raw")
            advanceUntilIdle()
            assertEquals(UserMessageKind.INSTALL_FAILED, viewModel.state.value.message?.kind)
        }

    @Test
    fun `제거 화면 복귀 뒤 실제 재조회 결과만 반영한다`() = runTest(dispatcher.scheduler) {
        val repository = FakeManagerRepository()
        val viewModel = ManagerViewModel(repository, SavedStateHandle())
        advanceUntilIdle()
        viewModel.accept(ManagerAction.Remove(PACKAGE))
        advanceUntilIdle()
        assertEquals(
            LaunchDestination.UNINSTALL,
            assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first()).destination,
        )

        repository.uninstallOutcome = UninstallOutcome.CancelledOrStillInstalled
        viewModel.accept(ManagerAction.ReturnedToForeground)
        advanceUntilIdle()
        assertEquals(UserMessageKind.UNINSTALL_CANCELLED, viewModel.state.value.message?.kind)

        viewModel.accept(ManagerAction.Remove(PACKAGE))
        repository.uninstallOutcome = UninstallOutcome.Removed
        viewModel.accept(ManagerAction.ReturnedToForeground)
        advanceUntilIdle()
        assertEquals(UserMessageKind.UNINSTALLED, viewModel.state.value.message?.kind)
        assertEquals(2, repository.reconcileRequests)
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
    fun `오래 걸린 이전 import 결과가 새 route를 덮지 않는다`() = runTest(dispatcher.scheduler) {
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

        assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        assertEquals(ImportUiState.Idle, viewModel.state.value.importState)
        assertEquals(listOf(TOKEN), repository.discardedTokens)
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
            assertEquals(listOf(TOKEN, SECOND_TOKEN), repository.discardedTokens)
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
    fun `느린 초기 restore는 새 import를 덮지 않고 old token을 폐기한다`() =
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

            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()
            repository.completeInitialize()
            advanceUntilIdle()

            assertEquals(
                SECOND_TOKEN,
                assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState).review.token,
            )
            assertEquals(listOf(TOKEN), repository.discardedTokens)
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
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.cancelInstallResult = false
            viewModel.accept(ManagerAction.CancelReview)
            advanceUntilIdle()

            assertTrue(repository.discardedTokens.isEmpty())
        }

    @Test
    fun `review 파일 삭제 실패 token은 다음 cleanup 성공까지 소유한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                importResult = ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.discardReviewResult = false
            viewModel.accept(ManagerAction.CancelReview)
            advanceUntilIdle()
            assertEquals(listOf(TOKEN), repository.discardAttempts)

            repository.discardReviewResult = true
            repository.importResult = ManagerImportResult.Success(
                review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
            )
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            assertEquals(listOf(TOKEN, TOKEN), repository.discardAttempts)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
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
        }

    @Test
    fun `구독자보다 먼저 발생한 Add-ons와 confirmation effect를 각각 한 번 전달한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            viewModel.accept(ManagerAction.OpenSamsungAddOns)
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            repository.emitLaunch(LaunchDestination.SYSTEM_INSTALL_CONFIRMATION, 41)
            advanceUntilIdle()

            assertEquals(
                LaunchDestination.SAMSUNG_ADD_ONS,
                assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first()).destination,
            )
            assertEquals(
                LaunchDestination.SYSTEM_INSTALL_CONFIRMATION,
                assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first()).destination,
            )
        }

    @Test
    fun `terminal 상태가 된 뒤 구독하면 stale install confirmation을 건너뛴다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()

            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            repository.emitLaunch(LaunchDestination.SYSTEM_INSTALL_CONFIRMATION, 41)
            advanceUntilIdle()
            repository.installFlow.value = InstallState.Cancelled
            viewModel.accept(ManagerAction.OpenSamsungAddOns)
            advanceUntilIdle()

            assertEquals(
                LaunchDestination.SAMSUNG_ADD_ONS,
                assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first()).destination,
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

    private fun review(status: ContractCompatibilityStatus) = ReviewBundle(
        token = TOKEN,
        packageName = PACKAGE,
        extensionId = EXTENSION_ID,
        name = "확장",
        version = "1.0",
        status = status,
        issues = emptyList(),
    )

    private fun catalogRecord() = CatalogRecord(
        packageName = PACKAGE,
        extensionId = EXTENSION_ID,
        name = "확장",
        iconPng = null,
        installedVersionCode = 1,
        signerSha256 = "a".repeat(64),
        compatibilityStatus = "COMPATIBLE",
        reportJson = null,
        lastVerifiedAtEpochMillis = 1,
    )

    private companion object {
        const val TOKEN = "11111111-1111-1111-1111-111111111111"
        const val SECOND_TOKEN = "22222222-2222-2222-2222-222222222222"
        val EXTENSION_ID = "a".repeat(32)
        val PACKAGE = "dev.sixbridge.ext.$EXTENSION_ID"
    }
}

private class FakeManagerRepository : ManagerRepository {
    val catalogFlow = MutableSharedFlow<List<CatalogRecord>>(replay = 1)
    val installFlow = MutableStateFlow<InstallState>(InstallState.Idle)
    var restoreResult = ManagerRestoreResult()
    var importResult: ManagerImportResult =
        ManagerImportResult.Failure(ImportFailure(ImportFailureCode.SOURCE_UNREADABLE))
    var initialized = false
    var suspendInitialize = false
    var ignoreInitializeCancellation = false
    var restoreArguments: Pair<String?, Int?>? = null
    var installRequests = 0
    var completedInstallRequests = 0
    var cancelInstallRequests = 0
    var lastPartialConfirmed = false
    var resumeRequests = 0
    var reconcileRequests = 0
    var uninstallOutcome: UninstallOutcome = UninstallOutcome.CancelledOrStillInstalled
    var navigationResult: RepositoryNavigationResult = RepositoryNavigationResult.Launch(
        RepositoryLaunch(
            destination = LaunchDestination.SAMSUNG_ADD_ONS,
            intentFactory = { error("unit test에서 Android Intent를 만들지 않습니다.") },
        ),
    )
    var suspendImport = false
    var suspendInstall = false
    var suspendCancelInstall = false
    var emitCancelledOnCancelInstall = false
    var terminalStateOnCancelInstall: InstallState? = null
    var cancelInstallResult = true
    var cancelInstallException: RuntimeException? = null
    var discardReviewResult = true
    var ignoreImportCancellation = false
    private val importGate = MutableSharedFlow<Unit>()
    private val installGate = MutableSharedFlow<Unit>()
    private val cancelInstallGate = MutableSharedFlow<Unit>()
    private val initializeGate = MutableSharedFlow<Unit>()
    private val launchChannel = Channel<RepositoryLaunch>(Channel.UNLIMITED)

    override val catalog = catalogFlow
    override val installState = installFlow
    override val launches = launchChannel.receiveAsFlow()

    override suspend fun initialize(reviewToken: String?, installSessionId: Int?): ManagerRestoreResult {
        initialized = true
        restoreArguments = reviewToken to installSessionId
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

    override suspend fun import(uri: Uri): ManagerImportResult =
        nextImport()

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

    override suspend fun install(review: ReviewBundle, partialConfirmed: Boolean) {
        installRequests += 1
        lastPartialConfirmed = partialConfirmed
        if (suspendInstall) installGate.first()
        completedInstallRequests += 1
    }

    suspend fun completeInstall() {
        installGate.emit(Unit)
    }

    override suspend fun cancelInstall(): Boolean {
        cancelInstallRequests += 1
        if (suspendCancelInstall) cancelInstallGate.first()
        cancelInstallException?.let { throw it }
        if (emitCancelledOnCancelInstall) installFlow.value = InstallState.Cancelled
        terminalStateOnCancelInstall?.let { installFlow.value = it }
        return cancelInstallResult
    }

    suspend fun completeCancelInstall() {
        cancelInstallGate.emit(Unit)
    }

    val discardedTokens = mutableListOf<String>()
    val discardAttempts = mutableListOf<String>()

    override fun discardReview(token: String): Boolean {
        discardAttempts += token
        if (discardReviewResult) discardedTokens += token
        return discardReviewResult
    }

    override fun unknownSourcesSettings(): RepositoryLaunch = RepositoryLaunch(
        destination = LaunchDestination.UNKNOWN_SOURCES_SETTINGS,
        intentFactory = { error("unit test에서 Android Intent를 만들지 않습니다.") },
    )

    override suspend fun resumeAfterUnknownSourcesSettings() {
        resumeRequests += 1
    }

    override fun uninstall(packageName: String): RepositoryLaunch = RepositoryLaunch(
        destination = LaunchDestination.UNINSTALL,
        intentFactory = { error("unit test에서 Android Intent를 만들지 않습니다.") },
    )

    override suspend fun reconcileUninstall(packageName: String): UninstallOutcome = uninstallOutcome

    override fun samsungAddOns(): RepositoryNavigationResult = navigationResult

    override suspend fun reconcileCatalog() {
        reconcileRequests += 1
    }

    fun emitLaunch(destination: LaunchDestination, installSessionId: Int? = null) {
        launchChannel.trySend(
            RepositoryLaunch(
                destination = destination,
                intentFactory = { error("unit test에서 Android Intent를 만들지 않습니다.") },
                installSessionId = installSessionId,
            ),
        )
    }
}
