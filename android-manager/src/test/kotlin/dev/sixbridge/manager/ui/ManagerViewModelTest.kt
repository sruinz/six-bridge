package dev.sixbridge.manager.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractIssue
import dev.sixbridge.manager.app.ManagerImportResult
import dev.sixbridge.manager.app.ManagerRepository
import dev.sixbridge.manager.app.ManagerRestoreResult
import dev.sixbridge.manager.app.RepositoryLaunch
import dev.sixbridge.manager.app.RepositoryNavigationResult
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.conversion.ConversionBeginResult
import dev.sixbridge.manager.conversion.ConversionBuildResult
import dev.sixbridge.manager.conversion.ConversionFailureCode
import dev.sixbridge.manager.conversion.GeneratedBundle
import dev.sixbridge.manager.conversion.IdentityRequirement
import dev.sixbridge.manager.conversion.InstallationIdentitySelection
import dev.sixbridge.manager.conversion.OnDeviceInputRequest
import dev.sixbridge.manager.conversion.RestoredConversion
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.uninstall.UninstallOutcome
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
        assertContentEquals(
            ICON,
            viewModel.state.value.installed.single().iconPng,
        )
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
            assertEquals(RecoveryAction.SELECT_ANOTHER_FILE, viewModel.state.value.message?.recoveryAction)
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
    fun `launch 실패 RETRY는 저장한 factory와 session으로 현재 세대 effect를 새로 만든다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
            advanceUntilIdle()
            repository.installFlow.value = InstallState.AwaitingUserApproval(41)
            val effect = ManagerEffect.LaunchIntent(
                destination = LaunchDestination.SYSTEM_INSTALL_CONFIRMATION,
                intentFactory = { error("launch failure") },
                installSessionId = 41,
            )

            viewModel.accept(ManagerAction.LaunchFailed(effect))
            assertEquals(UserMessageKind.LAUNCH_FAILED, viewModel.state.value.message?.kind)
            viewModel.accept(ManagerAction.RetryRecovery)
            val retried = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            assertFalse(effect === retried)
            assertTrue(effect.intentFactory === retried.intentFactory)
            assertEquals(effect.installSessionId, retried.installSessionId)
            assertNull(viewModel.state.value.message)

            viewModel.accept(ManagerAction.LaunchFailed(retried))
            assertEquals(UserMessageKind.LAUNCH_FAILED, viewModel.state.value.message?.kind)
            viewModel.accept(ManagerAction.RetryRecovery)
            val retriedAgain = assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
            assertFalse(retried === retriedAgain)
            assertTrue(retried.intentFactory === retriedAgain.intentFactory)
            assertEquals(retried.installSessionId, retriedAgain.installSessionId)
        }

    @Test
    fun `unknown sources launch 실패는 writing 또는 approval 진행 시 message와 RETRY를 지운다`() =
        runTest(dispatcher.scheduler) {
            for (progressed in listOf(
                InstallState.WritingSession(40),
                InstallState.AwaitingUserApproval(41),
            )) {
                val repository = FakeManagerRepository()
                val viewModel = ManagerViewModel(repository, SavedStateHandle())
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
    fun `terminal install은 settings와 confirmation launch recovery만 제거한다`() =
        runTest(dispatcher.scheduler) {
            for (destination in listOf(
                LaunchDestination.UNKNOWN_SOURCES_SETTINGS,
                LaunchDestination.SYSTEM_INSTALL_CONFIRMATION,
            )) {
                val repository = FakeManagerRepository()
                val viewModel = ManagerViewModel(repository, SavedStateHandle())
                advanceUntilIdle()
                val effect = if (destination == LaunchDestination.UNKNOWN_SOURCES_SETTINGS) {
                    repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
                    advanceUntilIdle()
                    assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
                } else {
                    repository.installFlow.value = InstallState.AwaitingUserApproval(41)
                    repository.emitLaunch(destination, 41)
                    advanceUntilIdle()
                    assertIs<ManagerEffect.LaunchIntent>(viewModel.effects.first())
                }
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
        }

    @Test
    fun `다른 destination launch 실패는 install 진행 중에도 유지한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
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
            settingsViewModel.accept(ManagerAction.FilePickerOpened)
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
            val repository = FakeManagerRepository()
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
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
    fun `파일 선택기를 연 뒤 늦은 uninstall reconcile은 취소 카드와 RETRY를 만들지 않는다`() =
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

            viewModel.accept(ManagerAction.FilePickerOpened)
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
            viewModel.accept(ManagerAction.FilePickerOpened)
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
            val savedState = SavedStateHandle()
            val viewModel = ManagerViewModel(repository, savedState)
            advanceUntilIdle()
            viewModel.startImport(repository::nextImport)
            advanceUntilIdle()

            repository.cancelInstallResult = false
            viewModel.accept(ManagerAction.CancelReview)
            advanceUntilIdle()

            assertTrue(repository.discardedTokens.isEmpty())
            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
            assertEquals(TOKEN, savedState.get<String>("review_token"))

            repository.restoreResult = ManagerRestoreResult(
                review = review(ContractCompatibilityStatus.COMPATIBLE),
            )
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

            assertEquals(0, importCalls)
            assertEquals(ManagerRoute.Review(TOKEN), recreated.state.value.route)
            assertEquals(TOKEN, savedState.get<String>("review_token"))
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
            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
            assertEquals(TOKEN, savedState.get<String>("review_token"))

            repository.restoreResult = ManagerRestoreResult(
                review = review(ContractCompatibilityStatus.COMPATIBLE),
            )
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
            assertEquals(0, blockedImportCalls)
            assertEquals(ManagerRoute.Review(TOKEN), recreated.state.value.route)
            assertEquals(TOKEN, savedState.get<String>("review_token"))

            repository.discardReviewResult = true
            repository.importResult = ManagerImportResult.Success(
                review(ContractCompatibilityStatus.COMPATIBLE).copy(token = SECOND_TOKEN),
            )
            recreated.startImport(repository::nextImport)
            advanceUntilIdle()

            assertEquals(listOf(TOKEN, TOKEN, TOKEN), repository.discardAttempts)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
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
    fun `cleanup 실패 뒤 늦은 stale token은 현재 review 파일을 자동 또는 부분 삭제하지 않는다`() =
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

            viewModel.startImport {
                ManagerImportResult.Success(review(ContractCompatibilityStatus.COMPATIBLE))
            }
            dispatcher.scheduler.runCurrent()
            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)

            viewModel.accept(ManagerAction.CancelReview)
            dispatcher.scheduler.runCurrent()
            assertEquals(listOf(TOKEN), repository.discardAttempts)
            assertEquals(TOKEN, savedState.get<String>("review_token"))

            repository.discardReviewResults[TOKEN] = true
            repository.discardReviewResults[SECOND_TOKEN] = false
            repository.completeInitialize()
            dispatcher.scheduler.runCurrent()

            assertTrue(repository.discardedTokens.isEmpty())
            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
            assertEquals(TOKEN, savedState.get<String>("review_token"))

            viewModel.accept(ManagerAction.CancelReview)
            dispatcher.scheduler.runCurrent()

            assertTrue(repository.discardedTokens.isEmpty())
            assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
            assertEquals(TOKEN, savedState.get<String>("review_token"))
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
                openConversionOutput = { copied },
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
    fun `generated active install 취소는 install job과 coordinator를 먼저 정리한다`() =
        runTest(dispatcher.scheduler) {
            val repository = FakeManagerRepository().apply {
                restoredConversion = RestoredConversion.BundleReady(
                    review(ContractCompatibilityStatus.COMPATIBLE),
                    generatedBundle(),
                )
                suspendInstall = true
            }
            val viewModel = ManagerViewModel(repository, SavedStateHandle())
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
                    "discardConversion:$TOKEN",
                    "discardReview:$TOKEN",
                ),
                repository.lifecycleEvents,
            )
            assertEquals(ManagerRoute.Home, viewModel.state.value.route)
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
                listOf("discardConversion:$TOKEN", "discardReview:$TOKEN"),
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
            assertIs<ConversionUiState.ResultReady>(viewModel.state.value.conversionState)
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.CONVERSION_TOKEN_KEY))
            assertEquals(TOKEN, savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY))
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
                openConversionOutput = { ByteArrayOutputStream() },
            )
            advanceUntilIdle()
            first.accept(ManagerAction.SaveGeneratedConversion)
            val effect = assertIs<ManagerEffect.CreateConversionDocument>(
                first.effects.first(),
            )

            val recreated = ManagerViewModel(
                repository,
                savedState,
                openConversionOutput = { ByteArrayOutputStream() },
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

    private fun generatedBundle() = GeneratedBundle(
        token = TOKEN,
        file = File("generated-bundles/$TOKEN.six"),
        sha256 = "d".repeat(64),
        suggestedFileName = "확장.six",
    )

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

    private fun review(
        status: ContractCompatibilityStatus,
        token: String = TOKEN,
    ) = ReviewBundle(
        token = token,
        packageName = PACKAGE,
        extensionId = EXTENSION_ID,
        name = "확장",
        version = "1.0",
        status = status,
        issues = emptyList(),
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

    private companion object {
        const val TOKEN = "11111111-1111-1111-1111-111111111111"
        const val SECOND_TOKEN = "22222222-2222-2222-2222-222222222222"
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
    var suspendInitialize = false
    var ignoreInitializeCancellation = false
    var restoreArguments: Pair<String?, Int?>? = null
    var installRequests = 0
    var completedInstallRequests = 0
    var installResult: InstallState = InstallState.Idle
    var cancelInstallRequests = 0
    var lastPartialConfirmed = false
    var resumeRequests = 0
    var reconcileRequests = 0
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
    var conversionBuildResult: ConversionBuildResult =
        ConversionBuildResult.Failure(ConversionFailureCode.INPUT_INVALID)
    val conversionBeginResults = mutableListOf<ConversionBeginResult>()
    var suspendFirstConversionBegin = false
    var ignoreFirstConversionBeginCancellation = false
    var suspendFirstConversionBuild = false
    var ignoreFirstConversionBuildCancellation = false
    var restoredConversion: RestoredConversion? = null
    var restoreConversionRequests = 0
    val conversionRequests = mutableListOf<OnDeviceInputRequest>()
    val conversionBuildRequests = mutableListOf<ConversionBuildRequest>()
    val discardedConversions = mutableListOf<String>()
    var discardConversionResult = true
    var copiedConversionHash: String? = null
    val copiedConversionTokens = mutableListOf<String>()
    val lifecycleEvents = mutableListOf<String>()
    var suspendImport = false
    var suspendInstall = false
    var suspendCancelInstall = false
    var emitCancelledOnCancelInstall = false
    var terminalStateOnCancelInstall: InstallState? = null
    var cancelInstallResult = true
    var cancelInstallException: RuntimeException? = null
    var discardReviewResult = true
    val discardReviewResults = mutableMapOf<String, Boolean>()
    var ignoreImportCancellation = false
    private val importGate = MutableSharedFlow<Unit>()
    private val installGate = MutableSharedFlow<Unit>()
    private val cancelInstallGate = MutableSharedFlow<Unit>()
    private val initializeGate = MutableSharedFlow<Unit>()
    private val firstConversionBeginGate = MutableSharedFlow<Unit>()
    private val firstConversionBuildGate = MutableSharedFlow<Unit>()
    private val uninstallReconcileGate = CompletableDeferred<Unit>()
    private val launchChannel = Channel<RepositoryLaunch>(Channel.UNLIMITED)

    override val catalog = catalogFlow
    override val installState = installFlow
    override val launches = launchChannel.receiveAsFlow()

    override suspend fun initialize(reviewToken: String?, installSessionId: Int?): ManagerRestoreResult {
        initialized = true
        restoreArguments = reviewToken to installSessionId
        initializeException?.let { throw it }
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

    override suspend fun beginConversion(request: OnDeviceInputRequest): ConversionBeginResult {
        val index = conversionRequests.size
        conversionRequests += request
        if (index == 0 && suspendFirstConversionBegin) {
            if (ignoreFirstConversionBeginCancellation) {
                withContext(NonCancellable) { firstConversionBeginGate.first() }
            } else {
                firstConversionBeginGate.first()
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

    override suspend fun restoreConversion(): RestoredConversion? {
        restoreConversionRequests += 1
        return restoredConversion
    }

    override suspend fun discardConversion(token: String): Boolean {
        lifecycleEvents += "discardConversion:$token"
        discardedConversions += token
        return discardConversionResult
    }

    override fun copyGeneratedConversion(token: String, output: OutputStream): String? {
        copiedConversionTokens += token
        output.write(byteArrayOf(1, 2, 3))
        return copiedConversionHash
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
            return installResult
        } finally {
            lifecycleEvents += "install:end"
        }
    }

    suspend fun completeInstall() {
        installGate.emit(Unit)
    }

    override suspend fun cancelInstall(): Boolean {
        lifecycleEvents += "cancelInstall"
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
        lifecycleEvents += "discardReview:$token"
        discardAttempts += token
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

private data class ConversionBuildRequest(
    val token: String,
    val approvedAdapterIds: Set<String>,
    val partialConfirmed: Boolean,
    val identitySelection: InstallationIdentitySelection?,
)

private fun unitTestUri(): Uri {
    return android.net.TestUri()
}
