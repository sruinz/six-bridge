package dev.sixbridge.manager.app

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.CatalogReconciler
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.cleanup.StartupCleanup
import dev.sixbridge.manager.importer.ImportedBundle
import dev.sixbridge.manager.importer.BundleImportService
import dev.sixbridge.manager.importer.BundleSource
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import dev.sixbridge.manager.install.InstallCoordinator
import dev.sixbridge.manager.install.InstallPolicy
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.install.PackageInstallGateway
import dev.sixbridge.manager.install.PackageInstallResult
import dev.sixbridge.manager.install.PackageInstallCallback
import dev.sixbridge.manager.install.PackageInstallEvents
import dev.sixbridge.manager.install.PendingCleanupResult
import dev.sixbridge.manager.install.PendingInstall
import dev.sixbridge.manager.install.PendingInstallResult
import dev.sixbridge.manager.install.PendingInstallStore
import dev.sixbridge.manager.ui.LaunchDestination
import dev.sixbridge.manager.ui.ImportUiState
import dev.sixbridge.manager.ui.ManagerAction
import dev.sixbridge.manager.ui.ManagerViewModel
import dev.sixbridge.manager.ui.ManagerRoute
import dev.sixbridge.manager.uninstall.UninstallCoordinator
import dev.sixbridge.manager.uninstall.UninstallOutcome
import dev.sixbridge.manager.navigation.SamsungBrowserAvailability
import dev.sixbridge.manager.navigation.SamsungBrowserNavigator
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.ArchiveInspector
import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import dev.sixbridge.manager.wrapper.WrapperVerificationResult
import java.io.File
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ManagerRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `새 repository 인스턴스가 bounded sidecar와 wrapper identity를 검증해 bundle을 복원한다`() {
        val root = temporaryFolder.newFolder("imports")
        val bundle = importedBundle(root)
        val verifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
            WrapperVerificationResult.Success(bundle.verifiedApk)
        }
        RetainedImportStore(root, verifier).save(bundle)

        val restored = RetainedImportStore(root, verifier).load(TOKEN)

        assertNotNull(restored)
        assertEquals(bundle.descriptor, restored.descriptor)
        assertEquals(bundle.report, restored.report)
        assertEquals(bundle.wrapperSha256, restored.wrapperSha256)

        bundle.wrapperFile.writeText("changed")
        assertNull(RetainedImportStore(root, verifier).load(TOKEN))
    }

    @Test
    fun `callback router는 confirmation effect와 terminal result를 각각 한 번 전달한다`() =
        runBlocking {
            val results = mutableListOf<PackageInstallResult>()
            val launches = mutableListOf<RepositoryLaunch>()
            var refreshes = 0
            val router = InstallCallbackRouter(
                onResult = results::add,
                onLaunch = launches::add,
                onRefresh = { refreshes += 1 },
            )

            router.handle(
                InstallEvent(
                    result = PackageInstallResult.PendingUserAction(41),
                    confirmationIntentFactory = {
                        error("unit test에서 Android Intent를 만들지 않습니다.")
                    },
                ),
            )
            router.handle(InstallEvent(PackageInstallResult.Success(41)))

            assertEquals(
                listOf(
                    PackageInstallResult.PendingUserAction(41),
                    PackageInstallResult.Success(41),
                ),
                results,
            )
            assertEquals(
                listOf(LaunchDestination.SYSTEM_INSTALL_CONFIRMATION),
                launches.map(RepositoryLaunch::destination),
            )
            assertEquals(2, refreshes)
        }

    @Test
    fun `callback router는 coordinator가 거부한 stale pending confirmation을 전달하지 않는다`() =
        runBlocking {
            val launches = mutableListOf<RepositoryLaunch>()
            val router = InstallCallbackRouter(
                onResult = { result ->
                    result !is PackageInstallResult.PendingUserAction || result.sessionId == 41
                },
                onLaunch = launches::add,
                onRefresh = {},
            )

            router.handle(
                InstallEvent(
                    result = PackageInstallResult.PendingUserAction(42),
                    confirmationIntentFactory = {
                        error("unit test에서 Android Intent를 만들지 않습니다.")
                    },
                ),
            )

            assertEquals(emptyList(), launches)
        }

    @Test
    fun `confirmation enqueue 실패는 marker를 확정하지 않고 다음 callback에서 재시도한다`() =
        runBlocking {
            var delivered = false
            var launchAttempts = 0
            var markerWrites = 0
            val router = InstallCallbackRouter(
                onResult = { result ->
                    result is PackageInstallResult.PendingUserAction && !delivered
                },
                onLaunch = {
                    launchAttempts += 1
                    if (launchAttempts == 1) error("transient enqueue failure")
                },
                onConfirmationDelivered = {
                    markerWrites += 1
                    delivered = true
                },
                onRefresh = {},
            )
            val event = InstallEvent(
                result = PackageInstallResult.PendingUserAction(41),
                confirmationIntentFactory = {
                    error("unit test에서 Android Intent를 만들지 않습니다.")
                },
            )

            router.handle(event)
            router.handle(event)

            assertEquals(2, launchAttempts)
            assertEquals(1, markerWrites)
            assertTrue(delivered)
        }

    @Test
    fun `callback router는 일시적인 catalog refresh 실패 뒤 다음 event도 처리한다`() =
        runBlocking {
            val results = mutableListOf<PackageInstallResult>()
            var refreshes = 0
            val router = InstallCallbackRouter(
                onResult = results::add,
                onLaunch = {},
                onRefresh = {
                    refreshes += 1
                    if (refreshes == 1) error("transient")
                },
            )

            router.handle(InstallEvent(PackageInstallResult.Success(41)))
            router.handle(InstallEvent(PackageInstallResult.Cancelled(42)))

            assertEquals(
                listOf(
                    PackageInstallResult.Success(41),
                    PackageInstallResult.Cancelled(42),
                ),
                results,
            )
            assertEquals(2, refreshes)
        }

    @Test
    fun `global package install event는 application collector 경로에서 terminal로 전달된다`() =
        runBlocking {
            val results = mutableListOf<PackageInstallResult>()
            val router = InstallCallbackRouter(
                onResult = results::add,
                onLaunch = {},
                onRefresh = {},
            )
            val collector = launch {
                collectPackageInstallEvents(PackageInstallEvents.events, router)
            }
            yield()

            PackageInstallEvents.dispatch(
                PackageInstallCallback(PackageInstallResult.Success(41)),
            )
            yield()

            assertEquals(
                listOf<PackageInstallResult>(PackageInstallResult.Success(41)),
                results,
            )
            collector.cancelAndJoin()
        }

    @Test
    fun `ViewModel 없는 새 process도 retained bundle 복원 뒤 terminal success를 처리한다`() =
        runBlocking {
            val root = temporaryFolder.newFolder("startup-imports")
            val bundle = importedBundle(root)
            val verifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
                WrapperVerificationResult.Success(bundle.verifiedApk)
            }
            val retainedImports = RetainedImportStore(root, verifier)
            retainedImports.save(bundle)
            val pendingStore = StartupPendingStore(
                PendingInstall(
                    sessionId = 41,
                    packageName = PACKAGE,
                    importToken = TOKEN,
                    startedAtEpochMillis = 1,
                ),
            )
            val gateway = StartupGateway(listOf(41))
            val installed = FakeInstalledWrappers(
                listOf(
                    InstalledWrapper(
                        packageName = PACKAGE,
                        extensionId = EXTENSION_ID,
                        name = "Example",
                        versionCode = 2,
                        signerSha256 = "a".repeat(64),
                    ),
                ),
            )
            val catalog = FakeCatalog(emptyList())
            val publisher = CatalogPublisher(catalog, installed, CatalogReconciler())
            val coordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
                clock = { 2 },
            )
            val startup = RepositoryStartup(
                retainedImports,
                StartupCleanup(
                    root,
                    gateway,
                    pendingStore,
                    Clock.fixed(Instant.ofEpochMilli(2), ZoneOffset.UTC),
                ),
                coordinator,
                pendingStore,
                publisher,
                Dispatchers.Unconfined,
            )
            val callbacks = Channel<PackageInstallCallback>(Channel.UNLIMITED)
            val router = InstallCallbackRouter(
                onResult = coordinator::onPackageInstallerResult,
                onLaunch = {},
                onRefresh = publisher::reconcile,
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

            startup.start(scope, callbacks.receiveAsFlow(), router)
            startup.awaitReady()
            callbacks.send(PackageInstallCallback(PackageInstallResult.Success(41)))
            withTimeout(1_000) {
                coordinator.state.first { it is InstallState.Installed }
            }

            assertEquals(InstallState.Installed(PACKAGE), coordinator.state.value)
            assertEquals(PACKAGE, catalog.records.single().packageName)
            assertNull(pendingStore.record)
            assertNull(pendingStore.terminal)
            scope.cancel()
        }

    @Test
    fun `누락 sidecar startup은 terminal success와 pending ledger를 보존한다`() =
        runBlocking {
            val root = temporaryFolder.newFolder("missing-startup-import")
            val pending = PendingInstall(41, PACKAGE, TOKEN, 1)
            val pendingStore = StartupPendingStore(pending).apply {
                terminal = PendingInstallResult(
                    PackageInstallResult.Success(41),
                    terminalAtEpochMillis = 2,
                    packageName = PACKAGE,
                )
            }
            val gateway = StartupGateway(listOf(41))
            val installed = FakeInstalledWrappers(emptyList())
            val catalog = FakeCatalog(emptyList())
            val publisher = CatalogPublisher(catalog, installed, CatalogReconciler())
            val coordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
            )
            val startup = RepositoryStartup(
                RetainedImportStore(root) { _, _, _ ->
                    error("누락 sidecar는 verifier를 호출하지 않습니다.")
                },
                StartupCleanup(
                    root,
                    gateway,
                    pendingStore,
                    Clock.fixed(Instant.ofEpochMilli(2), ZoneOffset.UTC),
                ),
                coordinator,
                pendingStore,
                publisher,
                Dispatchers.Unconfined,
            )

            startup.awaitReady()

            assertEquals(
                "IMPORT_NOT_AVAILABLE",
                assertIs<InstallState.Failed>(coordinator.state.value).code,
            )
            assertEquals(pending, pendingStore.record)
            assertNotNull(pendingStore.terminal)
            Unit
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `cleanup 실패는 coordinator부터 ViewModel까지 review token 소유권을 유지한다`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher.scheduler) {
                val root = temporaryFolder.newFolder("cleanup-ownership")
                val bundle = importedBundle(root)
                val verifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
                    WrapperVerificationResult.Success(bundle.verifiedApk)
                }
                val retainedImports = RetainedImportStore(root, verifier)
                retainedImports.save(bundle)
                val pendingStore = StartupPendingStore(null, root)
                val gateway = StartupGateway(emptyList()).apply {
                    canInstallPackages = false
                }
                val installed = FakeInstalledWrappers(emptyList())
                val catalog = FakeCatalog(emptyList())
                val publisher = CatalogPublisher(catalog, installed, CatalogReconciler())
                val coordinator = InstallCoordinator(
                    InstallPolicy(),
                    gateway,
                    pendingStore,
                    installed,
                    catalog,
                )
                val repository = DefaultManagerRepository(
                    importer = BundleImportService(
                        BundleSource { error("직접 import하지 않습니다.") },
                        AndroidWrapperVerifier(ArchiveInspector { error("검증하지 않습니다.") }),
                        root,
                        ioDispatcher = dispatcher,
                    ),
                    retainedImports = retainedImports,
                    startupCleanup = StartupCleanup(
                        root,
                        gateway,
                        pendingStore,
                        Clock.fixed(Instant.ofEpochMilli(2), ZoneOffset.UTC),
                    ),
                    installCoordinator = coordinator,
                    pendingStore = pendingStore,
                    uninstallCoordinator = object : UninstallCoordinator {
                        override fun intent(packageName: String) =
                            error("uninstall하지 않습니다.")
                        override suspend fun reconcileAfterReturn(packageName: String) =
                            UninstallOutcome.CancelledOrStillInstalled
                    },
                    samsungBrowserNavigator = object : SamsungBrowserNavigator {
                        override fun addOnsIntent() = error("Add-ons를 열지 않습니다.")
                        override fun availability() =
                            SamsungBrowserAvailability.SamsungBrowserMissing
                    },
                    catalogPublisher = publisher,
                    installEvents = emptyFlow(),
                    ioDispatcher = dispatcher,
                )
                val viewModel = ManagerViewModel(
                    repository,
                    SavedStateHandle(mapOf("review_token" to TOKEN)),
                )
                advanceUntilIdle()
                assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState)

                viewModel.accept(ManagerAction.InstallReviewedBundle)
                advanceUntilIdle()
                assertEquals(
                    InstallState.UnknownSourcesPermissionRequired,
                    coordinator.state.value,
                )

                pendingStore.discardFailure = true
                viewModel.accept(ManagerAction.CancelReview)
                advanceUntilIdle()

                assertEquals(ManagerRoute.Home, viewModel.state.value.route)
                assertEquals(1, pendingStore.discardAttempts)
                assertTrue(File(root, TOKEN).exists())

                pendingStore.discardFailure = false
                viewModel.startImport {
                    ManagerImportResult.Success(
                        ReviewBundle(
                            token = SECOND_TOKEN,
                            packageName = bundle.verifiedApk.packageName,
                            extensionId = bundle.descriptor.extensionId,
                            name = bundle.descriptor.name,
                            version = bundle.descriptor.version,
                            status = bundle.report.status,
                            issues = bundle.report.issues,
                        ),
                    )
                }
                advanceUntilIdle()

                assertEquals(3, pendingStore.discardAttempts)
                assertTrue(!File(root, TOKEN).exists())
                assertEquals(
                    SECOND_TOKEN,
                    assertIs<ImportUiState.ReviewReady>(viewModel.state.value.importState)
                        .review.token,
                )
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `catalog publisher는 실제 설치 wrapper만 publish하고 외부 version signer 변경을 반영한다`() =
        runBlocking {
            val catalog = FakeCatalog(
                listOf(record(version = 1, signer = "old")),
            )
            val installed = FakeInstalledWrappers(
                listOf(installed(version = 2, signer = "new")),
            )
            val publisher = CatalogPublisher(catalog, installed, CatalogReconciler())

            publisher.reconcile()

            assertEquals(2, publisher.state.value.single().installedVersionCode)
            assertEquals("new", publisher.state.value.single().signerSha256)
            assertEquals("UNKNOWN", publisher.state.value.single().compatibilityStatus)
            assertNull(publisher.state.value.single().reportJson)
            assertEquals(0, publisher.state.value.single().lastVerifiedAtEpochMillis)

            installed.values = listOf(
                installed(
                    packageName = SECOND_PACKAGE,
                    version = 7,
                    signer = "actual-only",
                ),
            )
            publisher.reconcile()

            val actualOnly = publisher.state.value.single()
            assertEquals(SECOND_PACKAGE, actualOnly.packageName)
            assertEquals("UNKNOWN", actualOnly.compatibilityStatus)
        }

    private fun importedBundle(root: File): ImportedBundle {
        val directory = File(root, TOKEN).apply { mkdir() }
        val wrapper = File(directory, "wrapper.apk").apply { writeText("verified apk") }
        val hash = sha256(wrapper)
        val descriptor = ContractExtensionDescriptor(
            schemaVersion = 1,
            extensionId = EXTENSION_ID,
            name = "Example",
            version = "1.0",
            sourceKind = "CRX3",
            crxSha256 = "c".repeat(64),
            wrapperSha256 = hash,
            wrapperPackage = PACKAGE,
            wrapperVersionCode = 2,
            signerSha256 = "a".repeat(64),
        )
        val report = ContractCompatibilityReport(
            schemaVersion = 3,
            status = ContractCompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = "Example",
            extensionVersion = "1.0",
            manifestKeyPresent = true,
            manifestKeyExtensionId = EXTENSION_ID,
            declaredPermissions = emptyList(),
            usedApis = emptyList(),
            unsupportedApis = emptyList(),
            issues = emptyList(),
            conversionState = "converted",
            adapterDecisions = emptyList<ContractAdapterDecision>(),
            originalExtensionId = EXTENSION_ID,
            convertedExtensionId = EXTENSION_ID,
            sourceSha256 = "d".repeat(64),
            browserProfileSha256 = "e".repeat(64),
        )
        val verified = VerifiedWrapperApk(
            packageName = PACKAGE,
            versionCode = 2,
            signerSha256 = descriptor.signerSha256,
            wrapperSha256 = hash,
            extensionId = EXTENSION_ID,
            providerAuthority = "$PACKAGE.contentprovider",
            crx = VerifiedCrx3(
                extensionId = EXTENSION_ID,
                publicKeySha256 = "b".repeat(64),
                crxSha256 = descriptor.crxSha256,
                zipOffset = 0,
                zipLength = 0,
            ),
        )
        return ImportedBundle(TOKEN, descriptor, report, verified, wrapper, hash)
    }

    private fun record(version: Long, signer: String) = CatalogRecord(
        packageName = PACKAGE,
        extensionId = EXTENSION_ID,
        name = "Example",
        iconPng = null,
        installedVersionCode = version,
        signerSha256 = signer,
        compatibilityStatus = "COMPATIBLE",
        reportJson = null,
        lastVerifiedAtEpochMillis = 1,
    )

    private fun installed(
        packageName: String = PACKAGE,
        version: Long,
        signer: String,
    ) = InstalledWrapper(
        packageName = packageName,
        extensionId = packageName.removePrefix("dev.sixbridge.ext."),
        name = "Actual",
        versionCode = version,
        signerSha256 = signer,
    )

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TOKEN = "11111111-1111-1111-1111-111111111111"
        const val SECOND_TOKEN = "22222222-2222-2222-2222-222222222222"
        val EXTENSION_ID = "a".repeat(32)
        val SECOND_EXTENSION_ID = "b".repeat(32)
        val PACKAGE = "dev.sixbridge.ext.$EXTENSION_ID"
        val SECOND_PACKAGE = "dev.sixbridge.ext.$SECOND_EXTENSION_ID"
    }
}

private class FakeCatalog(
    initial: List<CatalogRecord>,
) : ManagerCatalog {
    var records = initial

    override fun observe(): Flow<List<CatalogRecord>> = flowOf(records)
    override suspend fun replaceVerified(record: CatalogRecord) {
        records = records.filterNot { it.packageName == record.packageName } + record
    }
    override suspend fun remove(packageName: String) = Unit
    override suspend fun removeAll(packageNames: Set<String>) {
        records = records.filterNot { it.packageName in packageNames }
    }
    override suspend fun get(packageName: String): CatalogRecord? =
        records.singleOrNull { it.packageName == packageName }
}

private class FakeInstalledWrappers(
    var values: List<InstalledWrapper>,
) : InstalledWrapperRepository {
    override suspend fun findAll(): List<InstalledWrapper> = values
    override suspend fun find(packageName: String): InstalledWrapper? =
        values.singleOrNull { it.packageName == packageName }
}

private class StartupGateway(
    owned: List<Int>,
) : PackageInstallGateway {
    private val sessions = owned.toMutableList()
    var canInstallPackages = true

    override fun canRequestPackageInstalls(): Boolean = canInstallPackages
    override fun unknownSourcesSettingsIntent() =
        error("startup test에서는 설정 Intent를 만들지 않습니다.")
    override suspend fun createSession(packageName: String): Int =
        error("startup test에서는 session을 만들지 않습니다.")
    override suspend fun writeSession(sessionId: Int, apk: File) =
        error("startup test에서는 session을 쓰지 않습니다.")
    override fun commit(sessionId: Int) =
        error("startup test에서는 session을 제출하지 않습니다.")
    override fun abandon(sessionId: Int) {
        sessions -= sessionId
    }
    override fun ownedSessions(): List<Int> = sessions.toList()
}

private class StartupPendingStore(
    initial: PendingInstall?,
    private val importRoot: File? = null,
) : PendingInstallStore {
    var record = initial
    var terminal: PendingInstallResult? = null
    var discardFailure = false
    var discardAttempts = 0

    override fun load(): PendingInstall? = record
    override fun tryClaim(
        packageName: String,
        importToken: String,
        startedAtEpochMillis: Long,
    ): PendingInstall? = null
    override fun assignSession(claim: PendingInstall, sessionId: Int): PendingInstall? = null
    override fun recoverSession(claim: PendingInstall, sessionId: Int): PendingInstall? = null
    override fun clear(expected: PendingInstall): Boolean {
        if (record != expected) return false
        record = null
        return true
    }
    override fun clearWithoutTerminal(expected: PendingInstall): PendingCleanupResult =
        PendingCleanupResult.Failed
    override fun discardImport(importToken: String) {
        discardAttempts += 1
        if (discardFailure) error("discard failed")
        importRoot?.let { root ->
            check(File(root, importToken).deleteRecursively())
        }
    }
    override fun loadTerminalResult(): PendingInstallResult? = terminal
    override fun recordTerminalResult(
        result: PackageInstallResult,
        terminalAtEpochMillis: Long,
    ): Boolean {
        val pending = record ?: return false
        if (pending.sessionId != result.sessionId || result is PackageInstallResult.PendingUserAction) {
            return false
        }
        if (terminal != null) return false
        terminal = PendingInstallResult(result, terminalAtEpochMillis, pending.packageName)
        return true
    }
    override fun markTerminalProcessed(expected: PendingInstallResult): PendingInstallResult? {
        if (terminal != expected) return null
        return expected.copy(processed = true).also { terminal = it }
    }
    override fun clearTerminalResult(expected: PendingInstallResult): Boolean {
        if (terminal != expected) return false
        terminal = null
        return true
    }
    override fun firstSessionMissingAt(
        pending: PendingInstall,
        observedAtEpochMillis: Long,
    ): Long = observedAtEpochMillis
    override fun clearSessionMissingObservation(pending: PendingInstall): Boolean = true
}
