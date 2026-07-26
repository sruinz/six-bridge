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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
        }
        val viewModel = ManagerViewModel(repository, SavedStateHandle())
        advanceUntilIdle()

        viewModel.importSelected("content://bundle")
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

        viewModel.importSelected("partial")
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
                viewModel.importSelected(status.name)
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
            val effects = mutableListOf<ManagerEffect>()
            val collector = launch { viewModel.effects.collect(effects::add) }

            viewModel.importSelected("compatible")
            advanceUntilIdle()
            viewModel.accept(ManagerAction.InstallReviewedBundle)
            advanceUntilIdle()
            repository.installFlow.value = InstallState.UnknownSourcesPermissionRequired
            advanceUntilIdle()

            assertIs<ManagerEffect.LaunchIntent>(effects.last())
            assertEquals(UserMessageKind.UNKNOWN_SOURCES_PERMISSION, viewModel.state.value.message?.kind)

            viewModel.accept(ManagerAction.ReturnedToForeground)
            advanceUntilIdle()
            assertEquals(1, repository.resumeRequests)
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
        val effects = mutableListOf<ManagerEffect>()
        val collector = launch { viewModel.effects.collect(effects::add) }
        dispatcher.scheduler.runCurrent()

        viewModel.accept(ManagerAction.Remove(PACKAGE))
        advanceUntilIdle()
        assertIs<ManagerEffect.LaunchIntent>(effects.last())

        repository.uninstallOutcome = UninstallOutcome.CancelledOrStillInstalled
        viewModel.accept(ManagerAction.ReturnedToForeground)
        advanceUntilIdle()
        assertEquals(UserMessageKind.UNINSTALL_CANCELLED, viewModel.state.value.message?.kind)

        viewModel.accept(ManagerAction.Remove(PACKAGE))
        repository.uninstallOutcome = UninstallOutcome.Removed
        viewModel.accept(ManagerAction.ReturnedToForeground)
        advanceUntilIdle()
        assertEquals(UserMessageKind.UNINSTALLED, viewModel.state.value.message?.kind)
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
        }
        val viewModel = ManagerViewModel(repository, SavedStateHandle())
        advanceUntilIdle()

        viewModel.importSelected("slow")
        dispatcher.scheduler.runCurrent()
        viewModel.accept(ManagerAction.CancelReview)
        repository.completeImport()
        advanceUntilIdle()

        assertEquals(ManagerRoute.Home, viewModel.state.value.route)
        assertEquals(ImportUiState.Idle, viewModel.state.value.importState)
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
                viewModel.importSelected(code.name)
                advanceUntilIdle()
                val message = requireNotNull(viewModel.state.value.message)
                assertEquals(kind, message.kind)
                assertTrue(message.text.any { it.code in 0xAC00..0xD7A3 })
                assertEquals(RecoveryAction.SELECT_ANOTHER_FILE, message.recoveryAction)
            }
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
    var restoreArguments: Pair<String?, Int?>? = null
    var installRequests = 0
    var lastPartialConfirmed = false
    var resumeRequests = 0
    var uninstallOutcome: UninstallOutcome = UninstallOutcome.CancelledOrStillInstalled
    var navigationResult: RepositoryNavigationResult = RepositoryNavigationResult.Launch(
        RepositoryLaunch(
            destination = LaunchDestination.SAMSUNG_ADD_ONS,
            intentFactory = { error("unit test에서 Android Intent를 만들지 않습니다.") },
        ),
    )
    var suspendImport = false
    private val importGate = MutableSharedFlow<Unit>()

    override val catalog = catalogFlow
    override val installState = installFlow

    override suspend fun initialize(reviewToken: String?, installSessionId: Int?): ManagerRestoreResult {
        initialized = true
        restoreArguments = reviewToken to installSessionId
        return restoreResult
    }

    override suspend fun import(uri: Uri): ManagerImportResult =
        error("local JVM 테스트는 opaque request seam을 사용합니다.")

    override suspend fun importRequest(request: Any): ManagerImportResult {
        if (suspendImport) importGate.first()
        return importResult
    }

    suspend fun completeImport() {
        importGate.emit(Unit)
    }

    override suspend fun install(review: ReviewBundle, partialConfirmed: Boolean) {
        installRequests += 1
        lastPartialConfirmed = partialConfirmed
    }

    override fun discardReview(token: String) = Unit

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
}
