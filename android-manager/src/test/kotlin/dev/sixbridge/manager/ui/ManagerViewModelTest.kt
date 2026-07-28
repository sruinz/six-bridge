package dev.sixbridge.manager.ui

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractIssue
import dev.sixbridge.manager.GeneratedDocumentSaveResult
import dev.sixbridge.manager.app.ManagerImportResult
import dev.sixbridge.manager.app.ManagerRepository
import dev.sixbridge.manager.app.ManagerRestoreResult
import dev.sixbridge.manager.app.RestoredInstallTerminal
import dev.sixbridge.manager.app.RepositoryLaunch
import dev.sixbridge.manager.app.RepositoryNavigationResult
import dev.sixbridge.manager.app.ReviewBundle
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.conversion.ConversionBeginResult
import dev.sixbridge.manager.conversion.ConversionBuildResult
import dev.sixbridge.manager.conversion.ConversionFailureCode
import dev.sixbridge.manager.conversion.ExistingConversionTarget
import dev.sixbridge.manager.conversion.GeneratedBundle
import dev.sixbridge.manager.conversion.IdentityRequirement
import dev.sixbridge.manager.conversion.InstallationIdentitySelection
import dev.sixbridge.manager.conversion.OnDeviceInputRequest
import dev.sixbridge.manager.conversion.RestoredConversion
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.install.InstallCleanupResult
import dev.sixbridge.manager.install.FinalReceiptStage
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.install.InstallTerminalOwnerResult
import dev.sixbridge.manager.install.NormalHandoffStage
import dev.sixbridge.manager.install.NormalInstallHandoff
import dev.sixbridge.manager.uninstall.UninstallOutcome
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
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
    fun `terminal install은 settings와 confirmation launch recovery만 제거한다`() =
        runTest(dispatcher.scheduler) {
            for (destination in listOf(
                LaunchDestination.UNKNOWN_SOURCES_SETTINGS,
                LaunchDestination.SYSTEM_INSTALL_CONFIRMATION,
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

            assertEquals(listOf("discardReview:$TOKEN"), repository.lifecycleEvents)
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
                listOf("discardReview:$TOKEN", "discardConversion:$TOKEN"),
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
                listOf("discardReview:$TOKEN", "discardConversion:$TOKEN"),
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

            repository.installFlow.value = InstallState.Installed(PACKAGE)
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

            val recreated = ManagerViewModel(currentGeneratedInstallRepository(), deathState)
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
                    LaunchDestination.SYSTEM_INSTALL_CONFIRMATION,
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
    fun `pending REVIEW initialize exception은 Home Retry로 exact Report를 완료한다`() =
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
                UserMessageKind.CONVERSION_CLEANUP_FAILED,
                viewModel.state.value.message?.kind,
            )
            assertEquals(
                RecoveryAction.RETRY,
                viewModel.state.value.message?.recoveryAction,
            )
            assertGeneratedOwnersAndSessionPreserved(savedState)
            assertEquals(journalBefore, savedState.get<String>(CLEANUP_JOURNAL_KEY))
            assertEquals(0, repository.cleanupRequestCount())

            viewModel.accept(ManagerAction.RetryGeneratedCleanup)
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), viewModel.state.value.route)
            assertEquals(TOKEN, viewModel.state.value.destinationCommitToken)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)
        }

    @Test
    fun `pending REVIEW restore null과 exception은 Home Retry로 exact Report를 완료한다`() =
        runTest(dispatcher.scheduler) {
            listOf("null", "exception").forEach { caseName ->
                val savedState = pendingReviewReportSavedState()
                val journalBefore = savedState.get<String>(CLEANUP_JOURNAL_KEY)
                val repository = currentGeneratedInstallRepository().apply {
                    restoredConversion = null
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
                assertGeneratedOwnersAndSessionPreserved(savedState)
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
    fun `pending REVIEW stale initialize exception은 현재 상태를 오염시키지 않는다`() =
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
            assertNull(viewModel.state.value.message)
            assertGeneratedOwnersAndSessionPreserved(savedState)
            assertEquals(journalBefore, savedState.get<String>(CLEANUP_JOURNAL_KEY))
            assertEquals(1, repository.cancelInstallRequests)
            assertTrue(repository.discardedTokens.isEmpty())
            assertTrue(repository.discardedConversions.isEmpty())
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
            advanceUntilIdle()

            assertEquals("CONVERSION", generatedCleanupPhase(savedState))
            assertGeneratedOwnersAndSessionPreserved(savedState)
            assertEquals(listOf(TOKEN), repository.discardedTokens)
            assertEquals(listOf(TOKEN), repository.discardedConversions)

            repository.beforeDiscardConversion = null
            initializeResult.complete(repository.restoreResult)
            advanceUntilIdle()

            assertEquals(listOf(false), repository.restoreConversionImportReviewRequests)
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

            repository.installFlow.value = InstallState.Installed(PACKAGE)
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
                installFlow.value = InstallState.Installed(PACKAGE)
                restoredConversion = null
            }
            val conversionRecreated = ManagerViewModel(
                conversionRecreatedRepository,
                conversionSnapshot,
            )
            advanceUntilIdle()

            assertEquals(ManagerRoute.Report(PACKAGE), conversionRecreated.state.value.route)
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
                listOf("discardReview:$TOKEN", "discardConversion:$TOKEN"),
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

    private fun existingTarget() = ExistingConversionTarget(
        identitySha256 = "e".repeat(64),
        displayName = "기존 대상",
        extensionId = EXTENSION_ID,
        packageName = PACKAGE,
        wrapperSignerSha256 = "f".repeat(64),
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
    var initializeDeferred: CompletableDeferred<ManagerRestoreResult>? = null
    var suspendInitialize = false
    var ignoreInitializeCancellation = false
    var restoreArguments: Pair<String?, Int?>? = null
    var installRequests = 0
    var completedInstallRequests = 0
    var installResult: InstallState = InstallState.Idle
    var emitInstallResultOnReturn = false
    var cancelInstallRequests = 0
    val cancelInstallArguments = mutableListOf<Pair<String, Int?>>()
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
    var restoreConversionDeferred: CompletableDeferred<RestoredConversion?>? = null
    var restoreConversionFailure: IOException? = null
    var restoreConversionRequests = 0
    val restoreConversionImportReviewRequests = mutableListOf<Boolean>()
    val conversionRequests = mutableListOf<OnDeviceInputRequest>()
    val conversionBuildRequests = mutableListOf<ConversionBuildRequest>()
    val discardedConversions = mutableListOf<String>()
    var discardConversionResult = true
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
    var terminalOwnerState: InstallState? = null
    var terminalOwnerProofConfigured = false
    val terminalOwnerArguments = mutableListOf<Pair<String, Int?>>()
    var terminalReceiptActive = true
    var terminalReceiptAcknowledged = false
    var terminalAcknowledgeResult = true
    val terminalAcknowledgeArguments =
        mutableListOf<Triple<String, Int?, InstallState>>()
    val terminalFinalizeArguments =
        mutableListOf<Triple<String, Int?, InstallState>>()
    var terminalFinalizeResult = true
    var normalHandoff: NormalInstallHandoff? = null
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
        restoreArguments = reviewToken to installSessionId
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

    suspend fun completeInstall() {
        installGate.emit(Unit)
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
        val sessionId =
            terminalOwnerSessionId ?: return InstallTerminalOwnerResult.OwnerMismatch
        if (importToken != expectedReviewToken || installFlow.value != state) {
            return InstallTerminalOwnerResult.OwnerMismatch
        }
        if (expectedSessionId == null) {
            return InstallTerminalOwnerResult.OwnerSessionAssigned(sessionId, state)
        }
        return if (expectedSessionId == sessionId) {
            InstallTerminalOwnerResult.Owned(state)
        } else {
            InstallTerminalOwnerResult.OwnerMismatch
        }
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
        if (normalHandoff != expected) return false
        normalHandoff = replacement
        return true
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

    fun cleanupRequestCount(): Int =
        cancelInstallRequests + discardAttempts.size + discardedConversions.size

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
