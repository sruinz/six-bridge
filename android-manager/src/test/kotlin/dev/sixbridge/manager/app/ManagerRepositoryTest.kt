package dev.sixbridge.manager.app

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.manager.catalog.CatalogIdentity
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.CatalogReconciler
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.catalog.identity
import dev.sixbridge.manager.cleanup.StartupCleanup
import dev.sixbridge.manager.conversion.ConversionBeginResult
import dev.sixbridge.manager.conversion.ConversionBuildResult
import dev.sixbridge.manager.conversion.ConversionCoordinatorPort
import dev.sixbridge.manager.conversion.ConversionFailureCode
import dev.sixbridge.manager.conversion.GeneratedBundle
import dev.sixbridge.manager.conversion.InstallationIdentitySelection
import dev.sixbridge.manager.conversion.OnDeviceInputRequest
import dev.sixbridge.manager.conversion.RestoredConversion
import dev.sixbridge.manager.icon.CatalogIconEnricher
import dev.sixbridge.manager.icon.InstalledExtensionIconSource
import dev.sixbridge.manager.importer.ImportedBundle
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.importer.ImportResult
import dev.sixbridge.manager.importer.BundleImportService
import dev.sixbridge.manager.importer.BundleSource
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import dev.sixbridge.manager.install.InstallCoordinator
import dev.sixbridge.manager.install.InstallCleanupResult
import dev.sixbridge.manager.install.FinalInstallReceipt
import dev.sixbridge.manager.install.FinalReceiptOrigin
import dev.sixbridge.manager.install.FinalReceiptStage
import dev.sixbridge.manager.install.InstallPolicy
import dev.sixbridge.manager.install.InstallSessionSnapshot
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.install.InstallTerminalOwnerResult
import dev.sixbridge.manager.install.NormalHandoffStage
import dev.sixbridge.manager.install.NormalInstallHandoff
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
import dev.sixbridge.manager.ui.UserMessageKind
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
import java.io.FileNotFoundException
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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
    fun `retained sidecar IO는 transient로 전파되고 다음 load에서 복원한다`() {
        val root = temporaryFolder.newFolder("sidecar-io-retry")
        val bundle = importedBundle(root)
        val verifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
            WrapperVerificationResult.Success(bundle.verifiedApk)
        }
        RetainedImportStore(root, verifier).save(bundle)
        var attempts = 0
        val store = RetainedImportStore(
            importRoot = root,
            io = RetainedImportIo(
                openSidecar = { sidecar ->
                    attempts += 1
                    if (attempts == 1) throw IOException("transient sidecar read")
                    sidecar.inputStream()
                },
            ),
            verify = verifier,
        )

        assertFailsWith<RetainedImportTransientException> { store.load(TOKEN) }
        assertNotNull(store.load(TOKEN))
    }

    @Test
    fun `retained verifier 예외는 transient로 전파되고 명시적 Failure만 invalid다`() {
        val root = temporaryFolder.newFolder("verifier-retry")
        val bundle = importedBundle(root)
        val stableVerifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
            WrapperVerificationResult.Success(bundle.verifiedApk)
        }
        RetainedImportStore(root, stableVerifier).save(bundle)
        var attempts = 0
        val retrying = RetainedImportStore(root) { _, _, _ ->
            attempts += 1
            if (attempts == 1) error("transient verifier")
            WrapperVerificationResult.Success(bundle.verifiedApk)
        }

        assertFailsWith<RetainedImportTransientException> { retrying.load(TOKEN) }
        assertNotNull(retrying.load(TOKEN))
        assertNull(
            RetainedImportStore(root) { _, _, _ ->
                WrapperVerificationResult.Failure(
                    ImportFailure(ImportFailureCode.ARCHIVE_INVALID),
                )
            }.load(TOKEN),
        )
    }

    @Test
    fun `retained wrapper hash IO는 transient로 전파되고 다음 load에서 복원한다`() {
        val root = temporaryFolder.newFolder("hash-retry")
        val bundle = importedBundle(root)
        val verifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
            WrapperVerificationResult.Success(bundle.verifiedApk)
        }
        RetainedImportStore(root, verifier).save(bundle)
        var attempts = 0
        val store = RetainedImportStore(
            importRoot = root,
            io = RetainedImportIo(
                wrapperSha256 = {
                    attempts += 1
                    if (attempts == 1) throw IOException("transient wrapper read")
                    bundle.wrapperSha256
                },
            ),
            verify = verifier,
        )

        assertFailsWith<RetainedImportTransientException> { store.load(TOKEN) }
        assertNotNull(store.load(TOKEN))
    }

    @Test
    fun `존재하는 sidecar의 FileNotFoundException은 transient이고 다음 load가 성공한다`() {
        val root = temporaryFolder.newFolder("sidecar-resource-retry")
        val bundle = importedBundle(root)
        val verifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
            WrapperVerificationResult.Success(bundle.verifiedApk)
        }
        assertTrue(RetainedImportStore(root, verifier).save(bundle))
        var attempts = 0
        val store = RetainedImportStore(
            importRoot = root,
            io = RetainedImportIo(
                openSidecar = { sidecar ->
                    attempts += 1
                    if (attempts == 1) throw FileNotFoundException("resource exhausted")
                    sidecar.inputStream()
                },
                notExists = { false },
            ),
            verify = verifier,
        )

        assertFailsWith<RetainedImportTransientException> { store.load(TOKEN) }
        assertNotNull(store.load(TOKEN))
    }

    @Test
    fun `metadata IO 실패는 transient이고 다음 load에서 복원한다`() {
        val root = temporaryFolder.newFolder("metadata-retry")
        val bundle = importedBundle(root)
        val verifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
            WrapperVerificationResult.Success(bundle.verifiedApk)
        }
        assertTrue(RetainedImportStore(root, verifier).save(bundle))
        var attempts = 0
        val store = RetainedImportStore(
            importRoot = root,
            io = RetainedImportIo(
                readAttributes = { path ->
                    attempts += 1
                    if (attempts == 1) throw IOException("transient metadata")
                    Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                },
            ),
            verify = verifier,
        )

        assertFailsWith<RetainedImportTransientException> { store.load(TOKEN) }
        assertNotNull(store.load(TOKEN))
    }

    @Test
    fun `real path IO 실패는 transient이고 다음 load에서 복원한다`() {
        val root = temporaryFolder.newFolder("real-path-retry")
        val bundle = importedBundle(root)
        val verifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
            WrapperVerificationResult.Success(bundle.verifiedApk)
        }
        assertTrue(RetainedImportStore(root, verifier).save(bundle))
        var attempts = 0
        val store = RetainedImportStore(
            importRoot = root,
            io = RetainedImportIo(
                toRealPath = { path ->
                    attempts += 1
                    if (attempts == 1) throw IOException("transient real path")
                    path.toRealPath(LinkOption.NOFOLLOW_LINKS)
                },
            ),
            verify = verifier,
        )

        assertFailsWith<RetainedImportTransientException> { store.load(TOKEN) }
        assertNotNull(store.load(TOKEN))
    }

    @Test
    fun `open 중 실제 sidecar 누락은 definitive null이다`() {
        val root = temporaryFolder.newFolder("sidecar-open-missing")
        val bundle = importedBundle(root)
        val verifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
            WrapperVerificationResult.Success(bundle.verifiedApk)
        }
        assertTrue(RetainedImportStore(root, verifier).save(bundle))
        val store = RetainedImportStore(
            importRoot = root,
            io = RetainedImportIo(
                openSidecar = { throw NoSuchFileException(it.path) },
            ),
            verify = verifier,
        )

        assertNull(store.load(TOKEN))
    }

    @Test
    fun `hash 중 실제 wrapper 누락이 확인되면 definitive null이다`() {
        val root = temporaryFolder.newFolder("wrapper-hash-missing")
        val bundle = importedBundle(root)
        val verifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
            WrapperVerificationResult.Success(bundle.verifiedApk)
        }
        assertTrue(RetainedImportStore(root, verifier).save(bundle))
        val store = RetainedImportStore(
            importRoot = root,
            io = RetainedImportIo(
                wrapperSha256 = { throw FileNotFoundException("missing wrapper") },
                notExists = { path -> path.fileName.toString() == "wrapper.apk" },
            ),
            verify = verifier,
        )

        assertNull(store.load(TOKEN))
    }

    @Test
    fun `repository는 generated import를 보존하고 conversion 복원 취소 저장을 위임한다`() =
        runBlocking {
            val retainedRoot = temporaryFolder.newFolder("generated-repository-retained")
            val imported = importedBundle(retainedRoot)
            val retainedImports = RetainedImportStore(retainedRoot) { _, _, _ ->
                WrapperVerificationResult.Success(imported.verifiedApk)
            }
            val generatedFile = temporaryFolder.newFile("repository-generated.six")
                .apply { writeText("bundle") }
            val generated = GeneratedBundle(
                token = TOKEN,
                file = generatedFile,
                sha256 = "f".repeat(64),
                suggestedFileName = "$TOKEN.six",
            )
            val beginFailure =
                ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)
            val buildFailure =
                ConversionBuildResult.Failure(ConversionFailureCode.PACKAGE)
            val restored = RestoredConversion.GeneratedPending(generated)
            val calls = mutableListOf<String>()
            val conversion = object : ConversionCoordinatorPort {
                override suspend fun begin(request: OnDeviceInputRequest): ConversionBeginResult {
                    calls += "begin"
                    return beginFailure
                }

                override suspend fun build(
                    token: String,
                    approvedAdapterIds: Set<String>,
                    partialConfirmed: Boolean,
                    identitySelection: InstallationIdentitySelection?,
                ): ConversionBuildResult {
                    calls += "build"
                    return buildFailure
                }

                override suspend fun restore(importReview: Boolean): RestoredConversion {
                    calls += "restore"
                    return restored
                }

                override suspend fun discard(token: String): Boolean {
                    calls += "discard"
                    return true
                }

                override fun copyGenerated(token: String, output: OutputStream): String {
                    calls += "copy"
                    output.write("bundle".encodeToByteArray())
                    return generated.sha256
                }
            }
            val pendingStore = StartupPendingStore(null, retainedRoot)
            val gateway = StartupGateway(emptyList())
            val installed = FakeInstalledWrappers(emptyList())
            val catalog = FakeCatalog(emptyList())
            val install = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
            )
            val repository = managerRepository(
                root = retainedRoot,
                retainedImports = retainedImports,
                gateway = gateway,
                pendingStore = pendingStore,
                installed = installed,
                catalog = catalog,
                coordinator = install,
                conversionCoordinator = conversion,
                generatedImporter = {
                    calls += "import-generated"
                    assertEquals(generated, it)
                    ImportResult.Success(imported)
                },
            )

            val importedResult =
                assertIs<ManagerImportResult.Success>(repository.importGenerated(generated))
            assertEquals(TOKEN, importedResult.review.token)
            assertNotNull(retainedImports.load(TOKEN))
            assertSame(
                beginFailure,
                repository.beginConversion(
                    OnDeviceInputRequest.ChromeStore("https://example.invalid"),
                ),
            )
            assertSame(buildFailure, repository.approveConversion(TOKEN, emptySet(), true, null))
            assertSame(restored, repository.restoreConversion())
            val copied = ByteArrayOutputStream()
            assertEquals(generated.sha256, repository.copyGeneratedConversion(TOKEN, copied))
            assertEquals("bundle", copied.toString(Charsets.UTF_8.name()))
            assertTrue(repository.discardConversion(TOKEN))
            assertEquals(
                listOf("import-generated", "begin", "build", "restore", "copy", "discard"),
                calls,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `generated terminal finalize death 뒤 실제 retained review로 같은 설치를 다시 시작한다`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher.scheduler) {
                listOf(
                    PackageInstallResult.Cancelled(41),
                    PackageInstallResult.Failure(
                        41,
                        "SYSTEM_FAILURE",
                        "installer rejected package",
                    ),
                ).forEachIndexed { index, terminal ->
                    val root = temporaryFolder.newFolder("generated-terminal-retry-$index")
                    val bundle = importedBundle(root)
                    val verifier = {
                            _: File,
                            _: ContractExtensionDescriptor,
                            _: ContractCompatibilityReport,
                        ->
                        WrapperVerificationResult.Success(bundle.verifiedApk)
                    }
                    assertTrue(RetainedImportStore(root, verifier).save(bundle))
                    var retainedLoadCalls = 0
                    val retainedImports = RetainedImportStore(
                        root,
                        RetainedImportIo(
                            openSidecar = { sidecar ->
                                retainedLoadCalls += 1
                                sidecar.inputStream()
                            },
                        ),
                        verifier,
                    )
                    val generated = GeneratedBundle(
                        token = TOKEN,
                        file = temporaryFolder.newFile("generated-terminal-$index.six")
                            .apply { writeText("bundle") },
                        sha256 = "f".repeat(64),
                        suggestedFileName = "$TOKEN.six",
                    )
                    var restoredConversion: RestoredConversion =
                        RestoredConversion.BundleReady(bundle.toReview(), generated)
                    val conversion = object : ConversionCoordinatorPort {
                        override suspend fun begin(
                            request: OnDeviceInputRequest,
                        ) = ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)

                        override suspend fun build(
                            token: String,
                            approvedAdapterIds: Set<String>,
                            partialConfirmed: Boolean,
                            identitySelection: InstallationIdentitySelection?,
                        ) = ConversionBuildResult.Failure(ConversionFailureCode.PACKAGE)

                        override suspend fun restore(
                            importReview: Boolean,
                        ): RestoredConversion = restoredConversion

                        override suspend fun discard(token: String): Boolean = true

                        override fun copyGenerated(
                            token: String,
                            output: OutputStream,
                        ): String? = null
                    }
                    val pendingStore = StartupPendingStore(null, root)
                    val sourceGateway = StartupGateway(emptyList())
                    val installed = FakeInstalledWrappers(emptyList())
                    val catalog = FakeCatalog(emptyList())
                    val sourceCoordinator = InstallCoordinator(
                        InstallPolicy(),
                        sourceGateway,
                        pendingStore,
                        installed,
                        catalog,
                        clock = { 2 },
                        retainedWrapperSha256 = { sha256(it) },
                    )
                    val sourceRepository = managerRepository(
                        root = root,
                        retainedImports = retainedImports,
                        gateway = sourceGateway,
                        pendingStore = pendingStore,
                        installed = installed,
                        catalog = catalog,
                        coordinator = sourceCoordinator,
                        conversionCoordinator = conversion,
                    )
                    val savedState = SavedStateHandle(
                        mapOf(
                            ManagerViewModel.REVIEW_TOKEN_KEY to TOKEN,
                            ManagerViewModel.CONVERSION_TOKEN_KEY to TOKEN,
                            ManagerViewModel.CONVERSION_ROUTE_KEY to "result",
                            ManagerViewModel.RESULT_OWNER_CONVERSION_TOKEN_KEY to TOKEN,
                            ManagerViewModel.RESULT_OWNER_REVIEW_TOKEN_KEY to TOKEN,
                        ),
                    )
                    val source = ManagerViewModel(
                        sourceRepository,
                        savedState,
                        afterTerminalReceiptFinalized = {
                            throw CancellationException("simulated process death")
                        },
                    )
                    advanceUntilIdle()
                    source.accept(ManagerAction.PrepareGeneratedInstall)
                    source.accept(ManagerAction.InstallReviewedBundle)
                    advanceUntilIdle()
                    assertEquals(
                        InstallState.AwaitingUserApproval(41),
                        sourceCoordinator.state.value,
                    )

                    sourceCoordinator.onPackageInstallerResult(terminal)
                    advanceUntilIdle()

                    assertNotNull(
                        savedState.get<String>(
                            ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY,
                        ),
                    )
                    assertNull(pendingStore.finalReceipt)
                    assertNull(pendingStore.terminal)
                    assertTrue(File(root, TOKEN).exists())
                    sourceGateway.replaceOwnedSessions()

                    restoredConversion = RestoredConversion.GeneratedPending(generated)
                    val retryGateway = StartupGateway(emptyList())
                    val retryCoordinator = InstallCoordinator(
                        InstallPolicy(),
                        retryGateway,
                        pendingStore,
                        installed,
                        catalog,
                        clock = { 3 },
                        retainedWrapperSha256 = { sha256(it) },
                    )
                    val retryRepository = managerRepository(
                        root = root,
                        retainedImports = retainedImports,
                        gateway = retryGateway,
                        pendingStore = pendingStore,
                        installed = installed,
                        catalog = catalog,
                        coordinator = retryCoordinator,
                        conversionCoordinator = conversion,
                    )
                    val recreated = ManagerViewModel(retryRepository, savedState)
                    advanceUntilIdle()

                    assertNull(
                        savedState.get<String>(
                            ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY,
                        ),
                    )
                    assertTrue(retainedLoadCalls >= 2)
                    assertEquals(
                        TOKEN,
                        assertIs<ImportUiState.ReviewReady>(
                            recreated.state.value.importState,
                        ).review.token,
                    )

                    recreated.accept(ManagerAction.PrepareGeneratedInstall)
                    recreated.accept(ManagerAction.InstallReviewedBundle)
                    advanceUntilIdle()

                    assertEquals(
                        InstallState.AwaitingUserApproval(41),
                        retryCoordinator.state.value,
                    )
                    assertEquals(TOKEN, pendingStore.record?.importToken)
                    assertTrue(File(root, TOKEN).exists())
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `callback router는 confirmation을 launch하지 않고 terminal result만 전달한다`() =
        runBlocking {
            val results = mutableListOf<PackageInstallResult>()
            val launches = mutableListOf<RepositoryLaunch>()
            var refreshes = 0
            val router = InstallCallbackRouter(
                onResult = results::add,
                onRefresh = { refreshes += 1 },
            )

            router.handle(
                InstallEvent(
                    result = PackageInstallResult.PendingUserAction(41),
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
            assertTrue(launches.isEmpty())
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
                onRefresh = {},
            )

            router.handle(
                InstallEvent(
                    result = PackageInstallResult.PendingUserAction(42),
                ),
            )

            assertEquals(emptyList(), launches)
        }

    @Test
    fun `callback router는 일시적인 catalog refresh 실패 뒤 다음 event도 처리한다`() =
        runBlocking {
            val results = mutableListOf<PackageInstallResult>()
            var refreshes = 0
            val router = InstallCallbackRouter(
                onResult = results::add,
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
                root,
            )
            val gateway = StartupGateway(listOf(41), PACKAGE)
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
            val publisher = catalogPublisher(catalog, installed)
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
                onRefresh = publisher::reconcile,
            )
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

            startup.start(scope, callbacks.receiveAsFlow(), router)
            startup.ensureReady()
            callbacks.send(PackageInstallCallback(PackageInstallResult.Success(41)))
            withTimeout(1_000) {
                coordinator.state.first { it is InstallState.Installed }
            }

            assertEquals(InstallState.Installed(PACKAGE), coordinator.state.value)
            assertEquals(PACKAGE, catalog.records.single().packageName)
            assertNull(pendingStore.record)
            assertNotNull(pendingStore.terminal)
            assertTrue(
                coordinator.acknowledgeTerminal(
                    TOKEN,
                    41,
                    InstallState.Installed(PACKAGE),
                ),
            )
            assertEquals(
                FinalReceiptStage.ACKNOWLEDGED,
                pendingStore.finalReceipt?.stage,
            )
            assertNotNull(pendingStore.terminal)
            assertTrue(File(root, TOKEN).exists())
            assertTrue(
                coordinator.finalizeTerminal(
                    TOKEN,
                    41,
                    InstallState.Installed(PACKAGE),
                ),
            )
            assertNull(pendingStore.terminal)
            assertNull(pendingStore.finalReceipt)
            assertTrue(File(root, TOKEN).exists())
            pendingStore.discardImport(TOKEN)
            assertFalse(File(root, TOKEN).exists())
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
                    importToken = TOKEN,
                )
            }
            val gateway = StartupGateway(listOf(41), PACKAGE)
            val installed = FakeInstalledWrappers(emptyList())
            val catalog = FakeCatalog(emptyList())
            val publisher = catalogPublisher(catalog, installed)
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

            startup.ensureReady()

            assertEquals(
                "IMPORT_NOT_AVAILABLE",
                assertIs<InstallState.Failed>(coordinator.state.value).code,
            )
            assertEquals(pending, pendingStore.record)
            assertNotNull(pendingStore.terminal)
            Unit
        }

    @Test
    fun `startup terminal cleanup 뒤 snapshot은 완료된 review와 session을 복원하지 않는다`() =
        runBlocking {
            val root = temporaryFolder.newFolder("completed-startup-snapshot")
            val bundle = importedBundle(root)
            val retainedImports = RetainedImportStore(root) { _, _, _ ->
                WrapperVerificationResult.Success(bundle.verifiedApk)
            }
            assertTrue(retainedImports.save(bundle))
            val pending = PendingInstall(41, PACKAGE, TOKEN, 1)
            val pendingStore = StartupPendingStore(pending, root).apply {
                terminal = PendingInstallResult(
                    PackageInstallResult.Success(41),
                    terminalAtEpochMillis = 2,
                    packageName = PACKAGE,
                    importToken = TOKEN,
                )
            }
            val gateway = StartupGateway(listOf(41), PACKAGE)
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
            val coordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
                clock = { 2 },
                retainedWrapperSha256 = { sha256(it) },
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
                catalogPublisher(catalog, installed),
                Dispatchers.Unconfined,
            )

            val snapshot = startup.ensureReady()

            assertEquals(InstallState.Installed(PACKAGE), coordinator.state.value)
            assertNull(snapshot.pending)
            assertNull(snapshot.restoredBundle)
            assertNull(pendingStore.record)
            assertTrue(File(root, TOKEN).exists())
            assertTrue(
                coordinator.acknowledgeTerminal(
                    TOKEN,
                    41,
                    InstallState.Installed(PACKAGE),
                ),
            )
            assertEquals(
                FinalReceiptStage.ACKNOWLEDGED,
                pendingStore.finalReceipt?.stage,
            )
            assertNotNull(pendingStore.terminal)
            assertTrue(File(root, TOKEN).exists())
            assertTrue(
                coordinator.finalizeTerminal(
                    TOKEN,
                    41,
                    InstallState.Installed(PACKAGE),
                ),
            )
            assertNull(pendingStore.terminal)
            assertNull(pendingStore.finalReceipt)
            assertTrue(File(root, TOKEN).exists())
            pendingStore.discardImport(TOKEN)
            assertFalse(File(root, TOKEN).exists())
        }

    @Test
    fun `readiness 뒤 final owner는 review와 terminal을 함께 복원한다`() =
        runBlocking {
            val root = temporaryFolder.newFolder("current-recovery-after-terminal")
            val bundle = importedBundle(root)
            val retainedImports = RetainedImportStore(root) { _, _, _ ->
                WrapperVerificationResult.Success(bundle.verifiedApk)
            }
            assertTrue(retainedImports.save(bundle))
            val pendingStore = StartupPendingStore(
                PendingInstall(41, PACKAGE, TOKEN, 1),
                root,
            )
            val gateway = StartupGateway(listOf(41), PACKAGE)
            val installed = FakeInstalledWrappers(emptyList())
            val catalog = FakeCatalog(emptyList())
            val coordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
                clock = { 2 },
                retainedWrapperSha256 = { sha256(it) },
            )
            val repository = managerRepository(
                root,
                retainedImports,
                gateway,
                pendingStore,
                installed,
                catalog,
                coordinator,
            )

            assertEquals(TOKEN, repository.initialize(TOKEN, 41).review?.token)
            installed.values = listOf(installed(version = 2, signer = "a".repeat(64)))
            coordinator.onPackageInstallerResult(PackageInstallResult.Success(41))

            val restored = repository.initialize(TOKEN, 41)

            assertEquals(TOKEN, restored.review?.token)
            assertNull(restored.installSessionId)
            assertEquals(
                RestoredInstallTerminal(
                    importToken = TOKEN,
                    sessionId = 41,
                    packageName = PACKAGE,
                    state = InstallState.Installed(PACKAGE),
                    stage = FinalReceiptStage.READY,
                ),
                restored.terminal,
            )
            assertNull(pendingStore.record)
        }

    @Test
    fun `빈 SavedState cold start는 durable final owner와 review를 함께 복원한다`() =
        runBlocking {
            val outcomes = listOf(
                InstallState.Installed(PACKAGE) to PackageInstallResult.Success(41),
                InstallState.Cancelled to PackageInstallResult.Cancelled(41),
                InstallState.Failed("SYSTEM_FAILURE", "installer rejected package") to
                    PackageInstallResult.Failure(
                        41,
                        "SYSTEM_FAILURE",
                        "installer rejected package",
                    ),
            )
            outcomes.forEachIndexed { index, (state, rawResult) ->
                val root = temporaryFolder.newFolder("cold-start-final-$index")
                val bundle = importedBundle(root)
                val retainedImports = RetainedImportStore(root) { _, _, _ ->
                    WrapperVerificationResult.Success(bundle.verifiedApk)
                }
                assertTrue(retainedImports.save(bundle))
                val raw = PendingInstallResult(
                    result = rawResult,
                    terminalAtEpochMillis = 2,
                    packageName = PACKAGE,
                    importToken = TOKEN,
                )
                val receipt = FinalInstallReceipt(
                    importToken = TOKEN,
                    sessionId = 41,
                    packageName = PACKAGE,
                    state = state,
                    stage = FinalReceiptStage.READY,
                    origin = FinalReceiptOrigin.RAW_TERMINAL,
                    rawTerminalAtEpochMillis = 2,
                    terminalAtEpochMillis = 2,
                )
                val pendingStore = StartupPendingStore(null, root).apply {
                    terminal = raw
                    finalReceipt = receipt
                }
                val gateway = StartupGateway(emptyList())
                val installed = FakeInstalledWrappers(emptyList())
                val catalog = FakeCatalog(emptyList())
                val coordinator = InstallCoordinator(
                    InstallPolicy(),
                    gateway,
                    pendingStore,
                    installed,
                    catalog,
                    clock = { 2 },
                    retainedWrapperSha256 = { sha256(it) },
                )
                val repository = managerRepository(
                    root,
                    retainedImports,
                    gateway,
                    pendingStore,
                    installed,
                    catalog,
                    coordinator,
                )

                val restored = repository.initialize(null, null)

                assertEquals(TOKEN, restored.review?.token)
                assertEquals(
                    RestoredInstallTerminal(
                        importToken = TOKEN,
                        sessionId = 41,
                        packageName = PACKAGE,
                        state = state,
                        stage = FinalReceiptStage.READY,
                    ),
                    restored.terminal,
                )
            }
        }

    @Test
    fun `빈 SavedState cold start는 durable normal handoff와 review를 함께 복원한다`() =
        runBlocking {
            val root = temporaryFolder.newFolder("cold-start-normal-handoff")
            val bundle = importedBundle(root)
            val retainedImports = RetainedImportStore(root) { _, _, _ ->
                WrapperVerificationResult.Success(bundle.verifiedApk)
            }
            assertTrue(retainedImports.save(bundle))
            val handoff = NormalInstallHandoff(
                importToken = TOKEN,
                sessionId = 41,
                packageName = PACKAGE,
                state = InstallState.Cancelled,
                stage = NormalHandoffStage.OUTCOME_READY,
            )
            val pendingStore = StartupPendingStore(null, root).apply {
                normalHandoff = handoff
            }
            val gateway = StartupGateway(emptyList())
            val installed = FakeInstalledWrappers(emptyList())
            val catalog = FakeCatalog(emptyList())
            val coordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
            )
            val repository = managerRepository(
                root,
                retainedImports,
                gateway,
                pendingStore,
                installed,
                catalog,
                coordinator,
            )

            val restored = repository.initialize(null, null)

            assertEquals(TOKEN, restored.review?.token)
            assertEquals(handoff, restored.normalHandoff)
        }

    @Test
    fun `readiness가 비어 있던 뒤 시작한 install은 현재 B review와 session을 복원한다`() =
        runBlocking {
            val root = temporaryFolder.newFolder("current-recovery-late-b")
            val bundleB = importedBundle(root, SECOND_TOKEN)
            val retainedImports = RetainedImportStore(root) { _, _, _ ->
                WrapperVerificationResult.Success(bundleB.verifiedApk)
            }
            assertTrue(retainedImports.save(bundleB))
            val pendingStore = StartupPendingStore(null, root)
            val gateway = StartupGateway(emptyList())
            val installed = FakeInstalledWrappers(emptyList())
            val catalog = FakeCatalog(emptyList())
            val coordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
                clock = { 2 },
                retainedWrapperSha256 = { sha256(it) },
            )
            val repository = managerRepository(
                root,
                retainedImports,
                gateway,
                pendingStore,
                installed,
                catalog,
                coordinator,
            )
            repository.initialize(null, null)
            val pendingB = PendingInstall(42, PACKAGE, SECOND_TOKEN, 2)
            pendingStore.record = pendingB
            gateway.replaceOwnedSessions(42)
            coordinator.restore(bundleB)

            val restored = repository.initialize(SECOND_TOKEN, 42)

            assertEquals(SECOND_TOKEN, restored.review?.token)
            assertEquals(42, restored.installSessionId)
        }

    @Test
    fun `readiness A 뒤 ownership이 B로 바뀌면 B sidecar와 session을 복원한다`() =
        runBlocking {
            val root = temporaryFolder.newFolder("current-recovery-a-to-b")
            val bundleA = importedBundle(root)
            val bundleB = importedBundle(root, SECOND_TOKEN)
            val retainedImports = RetainedImportStore(root) { _, descriptor, _ ->
                WrapperVerificationResult.Success(
                    if (descriptor.wrapperSha256 == bundleA.wrapperSha256) {
                        bundleA.verifiedApk
                    } else {
                        bundleB.verifiedApk
                    },
                )
            }
            assertTrue(retainedImports.save(bundleA))
            assertTrue(retainedImports.save(bundleB))
            val pendingStore = StartupPendingStore(
                PendingInstall(41, PACKAGE, TOKEN, 1),
                root,
            )
            val gateway = StartupGateway(listOf(41), PACKAGE)
            val installed = FakeInstalledWrappers(emptyList())
            val catalog = FakeCatalog(emptyList())
            val coordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
                clock = { 2 },
                retainedWrapperSha256 = { sha256(it) },
            )
            val repository = managerRepository(
                root,
                retainedImports,
                gateway,
                pendingStore,
                installed,
                catalog,
                coordinator,
            )
            assertEquals(TOKEN, repository.initialize(TOKEN, 41).review?.token)
            val pendingB = PendingInstall(42, PACKAGE, SECOND_TOKEN, 2)
            pendingStore.record = pendingB
            gateway.replaceOwnedSessions(42)
            coordinator.restore(bundleB)

            val restored = repository.initialize(SECOND_TOKEN, 42)

            assertEquals(SECOND_TOKEN, restored.review?.token)
            assertEquals(42, restored.installSessionId)
        }

    @Test
    fun `readiness A cleanup 뒤 새 review-only B는 typed sidecar로 복원한다`() =
        runBlocking {
            val root = temporaryFolder.newFolder("current-recovery-review-only-b")
            val bundleA = importedBundle(root)
            val retainedImports = RetainedImportStore(root) { _, _, _ ->
                WrapperVerificationResult.Success(bundleA.verifiedApk)
            }
            assertTrue(retainedImports.save(bundleA))
            val pendingStore = StartupPendingStore(
                PendingInstall(41, PACKAGE, TOKEN, 1),
                root,
            )
            val gateway = StartupGateway(listOf(41), PACKAGE)
            val installed = FakeInstalledWrappers(emptyList())
            val catalog = FakeCatalog(emptyList())
            val coordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
                clock = { 2 },
                retainedWrapperSha256 = { sha256(it) },
            )
            val repository = managerRepository(
                root,
                retainedImports,
                gateway,
                pendingStore,
                installed,
                catalog,
                coordinator,
            )
            assertEquals(TOKEN, repository.initialize(TOKEN, 41).review?.token)
            assertEquals(
                InstallCleanupResult.CleanupFailed,
                coordinator.cancel(TOKEN, 41),
            )
            assertNull(pendingStore.record)
            assertEquals(
                FinalInstallReceipt(
                    importToken = TOKEN,
                    sessionId = 41,
                    packageName = PACKAGE,
                    state = InstallState.Cancelled,
                    stage = FinalReceiptStage.READY,
                    origin = FinalReceiptOrigin.PROGRAM_CANCEL,
                    rawTerminalAtEpochMillis = null,
                    terminalAtEpochMillis = 2,
                ),
                pendingStore.finalReceipt,
            )
            assertTrue(
                coordinator.acknowledgeTerminal(
                    TOKEN,
                    41,
                    InstallState.Cancelled,
                ),
            )
            assertEquals(
                FinalReceiptStage.ACKNOWLEDGED,
                pendingStore.finalReceipt?.stage,
            )
            assertTrue(
                coordinator.finalizeTerminal(
                    TOKEN,
                    41,
                    InstallState.Cancelled,
                ),
            )
            assertNull(pendingStore.finalReceipt)
            pendingStore.discardImport(TOKEN)
            val bundleB = importedBundle(root, SECOND_TOKEN)
            assertTrue(retainedImports.save(bundleB))

            val restored = repository.initialize(SECOND_TOKEN, null)

            assertEquals(SECOND_TOKEN, restored.review?.token)
            assertNull(restored.installSessionId)
        }

    @Test
    fun `repository owner-aware cancel은 review token과 nullable session을 coordinator에 전달한다`() =
        runBlocking {
            val root = temporaryFolder.newFolder("owner-aware-cancel")
            val bundle = importedBundle(root)
            val retainedImports = RetainedImportStore(root) { _, _, _ ->
                WrapperVerificationResult.Success(bundle.verifiedApk)
            }
            assertTrue(retainedImports.save(bundle))
            val pendingStore = StartupPendingStore(null, root)
            val gateway = StartupGateway(emptyList()).apply {
                canInstallPackages = false
            }
            val installed = FakeInstalledWrappers(emptyList())
            val catalog = FakeCatalog(emptyList())
            val coordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
            )
            val repository = managerRepository(
                root,
                retainedImports,
                gateway,
                pendingStore,
                installed,
                catalog,
                coordinator,
            )
            coordinator.request(bundle, partialConfirmed = false)

            val foreign = repository.cancelInstall(SECOND_TOKEN, null)
            val cleaned = repository.cancelInstall(TOKEN, null)

            assertEquals(InstallCleanupResult.OwnerMismatch, foreign)
            assertEquals(InstallCleanupResult.CleanupFailed, cleaned)
            assertNull(pendingStore.record)
            assertEquals(FinalReceiptStage.READY, pendingStore.finalReceipt?.stage)
            assertEquals(FinalReceiptOrigin.PROGRAM_CANCEL, pendingStore.finalReceipt?.origin)
            assertNull(pendingStore.finalReceipt?.sessionId)
            assertEquals(
                InstallTerminalOwnerResult.Owned(InstallState.Cancelled),
                repository.terminalForOwner(TOKEN, null),
            )
            assertTrue(repository.acknowledgeTerminal(TOKEN, null, InstallState.Cancelled))
            assertEquals(
                FinalReceiptStage.ACKNOWLEDGED,
                pendingStore.finalReceipt?.stage,
            )
            assertTrue(repository.finalizeTerminal(TOKEN, null, InstallState.Cancelled))
            assertNull(pendingStore.finalReceipt)
            pendingStore.discardImport(TOKEN)
        }

    @Test
    fun `repository terminal owner proof는 coordinator의 immutable token과 session을 전달한다`() =
        runBlocking {
            val root = temporaryFolder.newFolder("terminal-owner-proof")
            val bundle = importedBundle(root)
            val retainedImports = RetainedImportStore(root) { _, _, _ ->
                WrapperVerificationResult.Success(bundle.verifiedApk)
            }
            val pendingStore = StartupPendingStore(null, root).apply {
                terminal = PendingInstallResult(
                    result = PackageInstallResult.Cancelled(41),
                    terminalAtEpochMillis = 2,
                    packageName = PACKAGE,
                    importToken = TOKEN,
                )
            }
            val gateway = StartupGateway(emptyList())
            val installed = FakeInstalledWrappers(emptyList())
            val catalog = FakeCatalog(emptyList())
            val coordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
            )
            val repository = managerRepository(
                root,
                retainedImports,
                gateway,
                pendingStore,
                installed,
                catalog,
                coordinator,
            )
            coordinator.restore()

            assertEquals(
                InstallTerminalOwnerResult.OwnerSessionAssigned(
                    sessionId = 41,
                    state = InstallState.Cancelled,
                ),
                repository.terminalForOwner(TOKEN, null),
            )
            assertEquals(
                InstallTerminalOwnerResult.Owned(InstallState.Cancelled),
                repository.terminalForOwner(TOKEN, 41),
            )
            assertTrue(
                repository.acknowledgeTerminal(
                    expectedReviewToken = TOKEN,
                    expectedSessionId = 41,
                    expectedState = InstallState.Cancelled,
                ),
            )
            assertEquals(
                FinalReceiptStage.ACKNOWLEDGED,
                pendingStore.finalReceipt?.stage,
            )
            assertNotNull(pendingStore.terminal)
            assertTrue(
                repository.finalizeTerminal(
                    expectedReviewToken = TOKEN,
                    expectedSessionId = 41,
                    expectedState = InstallState.Cancelled,
                ),
            )
            assertNull(pendingStore.terminal)
            assertNull(pendingStore.finalReceipt)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `동일한 pre-claim 실패도 이번 B attempt 결과로 review와 token을 정리한다`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher.scheduler) {
                val root = temporaryFolder.newFolder("identical-attempt-failure")
                val bundleA = importedBundle(root)
                val retainedImports = RetainedImportStore(root) { _, _, _ ->
                    WrapperVerificationResult.Success(bundleA.verifiedApk)
                }
                assertTrue(retainedImports.save(bundleA))
                val pendingStore = StartupPendingStore(null, root)
                val gateway = StartupGateway(emptyList())
                val installed = FakeInstalledWrappers(emptyList()).apply {
                    findFailure = IllegalStateException("installed query failed")
                }
                val catalog = FakeCatalog(emptyList())
                val coordinator = InstallCoordinator(
                    InstallPolicy(),
                    gateway,
                    pendingStore,
                    installed,
                    catalog,
                    clock = { 2 },
                    retainedWrapperSha256 = { sha256(it) },
                )
                coordinator.request(bundleA, partialConfirmed = false)
                val failure = assertIs<InstallState.Failed>(coordinator.state.value)
                assertEquals("INSTALLED_QUERY_FAILED", failure.code)

                val bundleB = importedBundle(root, SECOND_TOKEN)
                assertTrue(retainedImports.save(bundleB))
                val repository = managerRepository(
                    root,
                    retainedImports,
                    gateway,
                    pendingStore,
                    installed,
                    catalog,
                    coordinator,
                )
                val savedState = SavedStateHandle(mapOf("review_token" to SECOND_TOKEN))
                val viewModel = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                assertEquals(ManagerRoute.Review(SECOND_TOKEN), viewModel.state.value.route)
                val messages = mutableListOf<UserMessageKind?>()
                val collector = launch {
                    viewModel.state.collect { state -> messages += state.message?.kind }
                }
                runCurrent()

                viewModel.accept(ManagerAction.InstallReviewedBundle)
                advanceUntilIdle()

                assertFalse(File(root, SECOND_TOKEN).exists())
                assertEquals(ManagerRoute.Home, viewModel.state.value.route)
                assertNull(savedState.get<String>("review_token"))
                assertEquals(UserMessageKind.INSTALL_FAILED, viewModel.state.value.message?.kind)
                assertEquals(1, messages.count { it == UserMessageKind.INSTALL_FAILED })
                collector.cancel()
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `repository install은 mutex 뒤 callback보다 request 내부 결과를 반환한다`() =
        runBlocking {
            val root = temporaryFolder.newFolder("atomic-attempt-result")
            val bundle = importedBundle(root)
            val retainedImports = RetainedImportStore(root) { _, _, _ ->
                WrapperVerificationResult.Success(bundle.verifiedApk)
            }
            assertTrue(retainedImports.save(bundle))
            val pendingStore = StartupPendingStore(null, root)
            val gateway = StartupGateway(emptyList())
            val installed = FakeInstalledWrappers(emptyList())
            val catalog = FakeCatalog(emptyList())
            val coordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
                clock = { 2 },
                retainedWrapperSha256 = { sha256(it) },
            )
            var stateBeforeCallback: InstallState? = null
            val repository = managerRepository(
                root,
                retainedImports,
                gateway,
                pendingStore,
                installed,
                catalog,
                coordinator,
                afterInstallRequest = { requestState ->
                    stateBeforeCallback = requestState
                    coordinator.onPackageInstallerResult(
                        PackageInstallResult.Failure(
                            sessionId = 41,
                            code = "CALLBACK_FAILURE",
                            message = "callback terminal",
                        ),
                    )
                },
            )
            val review = assertNotNull(repository.initialize(TOKEN, null).review)

            val attemptResult = repository.install(review, partialConfirmed = false)

            assertEquals(InstallState.AwaitingUserApproval(41), stateBeforeCallback)
            assertEquals(InstallState.AwaitingUserApproval(41), attemptResult)
            assertEquals(
                "CALLBACK_FAILURE",
                assertIs<InstallState.Failed>(coordinator.state.value).code,
            )
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `startup 첫 복원 예외는 readiness 전 terminal 소비를 막고 다음 시도에서 복원한다`() =
        runTest {
            val root = temporaryFolder.newFolder("retry-startup-import")
            val bundle = importedBundle(root)
            var verifierCalls = 0
            val verifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
                verifierCalls += 1
                WrapperVerificationResult.Success(bundle.verifiedApk)
            }
            val retainedImports = RetainedImportStore(root, verifier)
            retainedImports.save(bundle)
            val pendingStore = StartupPendingStore(
                PendingInstall(41, PACKAGE, TOKEN, 1),
            )
            val gateway = StartupGateway(listOf(41), PACKAGE)
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
            val publisher = catalogPublisher(catalog, installed)
            val coordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
                clock = { 2 },
            )
            pendingStore.loadFailures = 1
            val dispatcher = StandardTestDispatcher(testScheduler)
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
                ioDispatcher = dispatcher,
                retryDelayMillis = 100,
            )
            val callbacks = Channel<PackageInstallCallback>(Channel.UNLIMITED)
            val router = InstallCallbackRouter(
                onResult = coordinator::onPackageInstallerResult,
                onRefresh = publisher::reconcile,
            )

            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            startup.start(scope, callbacks.receiveAsFlow(), router)
            val foregroundInitialization = async { startup.awaitReady() }
            callbacks.send(PackageInstallCallback(PackageInstallResult.Success(41)))
            runCurrent()

            assertFalse(foregroundInitialization.isCancelled)
            assertEquals(InstallState.AwaitingUserApproval(41), coordinator.state.value)
            assertNull(pendingStore.terminal)
            assertTrue(catalog.records.isEmpty())

            advanceTimeBy(101)
            withTimeout(1_000) {
                coordinator.state.first { it is InstallState.Installed }
            }

            assertEquals(0, pendingStore.loadFailures)
            assertEquals(InstallState.Installed(PACKAGE), coordinator.state.value)
            assertNotNull(pendingStore.terminal)
            assertTrue(
                coordinator.acknowledgeTerminal(
                    TOKEN,
                    41,
                    InstallState.Installed(PACKAGE),
                ),
            )
            assertEquals(
                FinalReceiptStage.ACKNOWLEDGED,
                pendingStore.finalReceipt?.stage,
            )
            assertNotNull(pendingStore.terminal)
            assertTrue(
                coordinator.finalizeTerminal(
                    TOKEN,
                    41,
                    InstallState.Installed(PACKAGE),
                ),
            )
            assertNull(pendingStore.terminal)
            assertNull(pendingStore.finalReceipt)
            assertEquals(PACKAGE, catalog.records.single().packageName)
            val snapshot = foregroundInitialization.await()
            assertEquals(TOKEN, snapshot.pending?.importToken)
            assertEquals(TOKEN, snapshot.restoredBundle?.token)
            assertEquals(1, verifierCalls)
            scope.cancel()
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
                val publisher = catalogPublisher(catalog, installed)
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
                val savedState = SavedStateHandle(mapOf("review_token" to TOKEN))
                val viewModel = ManagerViewModel(repository, savedState)
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

                assertEquals(ManagerRoute.Review(TOKEN), viewModel.state.value.route)
                assertEquals(TOKEN, savedState.get<String>("review_token"))
                assertEquals(1, pendingStore.discardAttempts)
                assertTrue(File(root, TOKEN).exists())

                var blockedImportCalls = 0
                val recreated = ManagerViewModel(repository, savedState)
                advanceUntilIdle()
                assertEquals(ManagerRoute.Review(TOKEN), recreated.state.value.route)
                recreated.startImport {
                    blockedImportCalls += 1
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
                assertEquals(0, blockedImportCalls)
                assertEquals(ManagerRoute.Review(TOKEN), recreated.state.value.route)
                assertEquals(TOKEN, savedState.get<String>("review_token"))

                pendingStore.discardFailure = false
                recreated.startImport {
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

                assertEquals(4, pendingStore.discardAttempts)
                assertTrue(!File(root, TOKEN).exists())
                assertEquals(
                    SECOND_TOKEN,
                    assertIs<ImportUiState.ReviewReady>(recreated.state.value.importState)
                        .review.token,
                )
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `committed cleanup 실패 뒤 새 repository와 ViewModel이 review를 복원해 재시도한다`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            runTest(dispatcher.scheduler) {
                val root = temporaryFolder.newFolder("committed-cleanup-recreation")
                val bundle = importedBundle(root)
                var retainedLoadCalls = 0
                var verifierCalls = 0
                val verifier = { _: File, _: ContractExtensionDescriptor, _: ContractCompatibilityReport ->
                    verifierCalls += 1
                    WrapperVerificationResult.Success(bundle.verifiedApk)
                }
                val retainedImports = RetainedImportStore(
                    root,
                    RetainedImportIo(
                        openSidecar = { sidecar ->
                            retainedLoadCalls += 1
                            sidecar.inputStream()
                        },
                    ),
                    verifier,
                )
                retainedImports.save(bundle)
                val pending = PendingInstall(41, PACKAGE, TOKEN, 1)
                val pendingStore = StartupPendingStore(pending, root).apply {
                    discardFailure = true
                }
                val gateway = StartupGateway(listOf(41), PACKAGE)
                val installed = FakeInstalledWrappers(emptyList())
                val catalog = FakeCatalog(emptyList())
                val firstCoordinator = InstallCoordinator(
                    InstallPolicy(),
                    gateway,
                    pendingStore,
                    installed,
                    catalog,
                    clock = { 2 },
                    retainedWrapperSha256 = { sha256(it) },
                )
                firstCoordinator.restore(bundle)

                assertEquals(
                    InstallCleanupResult.CleanupFailed,
                    firstCoordinator.cancel(TOKEN, 41),
                )
                assertEquals(InstallState.Cancelled, firstCoordinator.state.value)
                assertEquals(
                    FinalInstallReceipt(
                        importToken = TOKEN,
                        sessionId = 41,
                        packageName = PACKAGE,
                        state = InstallState.Cancelled,
                        stage = FinalReceiptStage.READY,
                        origin = FinalReceiptOrigin.PROGRAM_CANCEL,
                        rawTerminalAtEpochMillis = null,
                        terminalAtEpochMillis = 2,
                    ),
                    pendingStore.finalReceipt,
                )
                assertNull(pendingStore.record)
                assertTrue(File(root, TOKEN).exists())

                val restoredCoordinator = InstallCoordinator(
                    InstallPolicy(),
                    gateway,
                    pendingStore,
                    installed,
                    catalog,
                    clock = { 2 },
                    retainedWrapperSha256 = { sha256(it) },
                )
                val publisher = catalogPublisher(catalog, installed)
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
                    installCoordinator = restoredCoordinator,
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
                val savedState = SavedStateHandle(mapOf("review_token" to TOKEN))
                val recreated = ManagerViewModel(repository, savedState)
                advanceUntilIdle()

                assertEquals(1, retainedLoadCalls)
                assertEquals(1, verifierCalls)
                assertEquals(InstallState.Cancelled, restoredCoordinator.state.value)
                assertNull(pendingStore.record)
                assertNull(pendingStore.finalReceipt)
                assertEquals(
                    NormalInstallHandoff(
                        importToken = TOKEN,
                        sessionId = 41,
                        packageName = PACKAGE,
                        state = InstallState.Cancelled,
                        stage = NormalHandoffStage.PREPARED,
                    ),
                    pendingStore.normalHandoff,
                )
                assertTrue(File(root, TOKEN).exists())
                assertEquals(TOKEN, savedState.get<String>("review_token"))
                assertEquals(ManagerRoute.Review(TOKEN), recreated.state.value.route)
                assertIs<ImportUiState.ReviewReady>(recreated.state.value.importState)
                assertEquals(TOKEN, savedState.get<String>("review_token"))

                pendingStore.discardFailure = false
                recreated.accept(ManagerAction.CancelReview)
                advanceUntilIdle()

                assertEquals(ManagerRoute.Home, recreated.state.value.route)
                assertNull(savedState.get<String>("review_token"))
                assertNull(pendingStore.record)
                assertFalse(File(root, TOKEN).exists())
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
            val publisher = catalogPublisher(catalog, installed)

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
            assertEquals(
                listOf(SECOND_PACKAGE),
                catalog.records.map(CatalogRecord::packageName),
            )
        }

    @Test
    fun `catalog publisher reconcile 경로는 설치 record icon을 보강한다`() = runBlocking {
        val catalog = FakeCatalog(listOf(record(version = 1, signer = "same")))
        val installed = FakeInstalledWrappers(listOf(installed(version = 1, signer = "same")))
        val publisher = catalogPublisher(
            catalog,
            installed,
            InstalledExtensionIconSource { PUBLISHER_PNG },
        )

        publisher.reconcile()

        assertContentEquals(PUBLISHER_PNG, publisher.state.value.single().iconPng)
        assertContentEquals(PUBLISHER_PNG, catalog.records.single().iconPng)
    }

    @Test
    fun `publish는 기준 행을 보장한 뒤 미저장 record의 icon을 반영한다`() = runBlocking {
        val catalog = FakeCatalog(emptyList())
        val installed = FakeInstalledWrappers(listOf(installed(version = 1, signer = "same")))
        val publisher = catalogPublisher(
            catalog,
            installed,
            InstalledExtensionIconSource { PUBLISHER_PNG },
        )

        publisher.publish(listOf(record(version = 1, signer = "same")))

        assertContentEquals(PUBLISHER_PNG, publisher.state.value.single().iconPng)
        assertContentEquals(PUBLISHER_PNG, catalog.records.single().iconPng)
    }

    @Test
    fun `fake catalog ensure는 변경된 identity를 unknown baseline으로 교체한다`() = runBlocking {
        val current = record(version = 1, signer = "same")
        val discovered = current.copy(
            extensionId = "different",
            name = "Discovered",
            iconPng = null,
            compatibilityStatus = "UNKNOWN",
            reportJson = null,
            lastVerifiedAtEpochMillis = 0,
        )
        val catalog = FakeCatalog(listOf(current))

        val ensured = catalog.ensureDiscoveredIdentity(discovered)

        assertEquals(discovered, ensured)
        assertEquals(discovered, catalog.records.single())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `지연된 icon 저장은 같은 identity의 최신 report와 timestamp를 되돌리지 않는다`() =
        runTest {
            val sourceStarted = CompletableDeferred<Unit>()
            val releaseSource = CompletableDeferred<Unit>()
            val catalog = FakeCatalog(listOf(record(version = 1, signer = "same")))
            val installed =
                FakeInstalledWrappers(listOf(installed(version = 1, signer = "same")))
            val publisher = catalogPublisher(
                catalog,
                installed,
                InstalledExtensionIconSource {
                    sourceStarted.complete(Unit)
                    releaseSource.await()
                    PUBLISHER_PNG
                },
            )
            val refresh = async { publisher.reconcile() }
            sourceStarted.await()
            val latest = catalog.records.single().copy(
                name = "Latest",
                compatibilityStatus = "BLOCKED",
                reportJson = "{\"status\":\"BLOCKED\"}",
                lastVerifiedAtEpochMillis = 999,
            )
            catalog.replaceVerified(latest)

            releaseSource.complete(Unit)
            refresh.await()
            advanceUntilIdle()

            val stored = catalog.records.single()
            assertEquals("Latest", stored.name)
            assertEquals("BLOCKED", stored.compatibilityStatus)
            assertEquals("{\"status\":\"BLOCKED\"}", stored.reportJson)
            assertEquals(999, stored.lastVerifiedAtEpochMillis)
            assertContentEquals(PUBLISHER_PNG, stored.iconPng)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `지연된 icon 결과는 변경된 설치 identity의 state와 catalog에 병합하지 않는다`() =
        runTest {
            val sourceStarted = CompletableDeferred<Unit>()
            val releaseSource = CompletableDeferred<Unit>()
            val catalog = FakeCatalog(listOf(record(version = 1, signer = "same")))
            val installed =
                FakeInstalledWrappers(listOf(installed(version = 1, signer = "same")))
            val publisher = catalogPublisher(
                catalog,
                installed,
                InstalledExtensionIconSource {
                    sourceStarted.complete(Unit)
                    releaseSource.await()
                    PUBLISHER_PNG
                },
            )
            val refresh = async { publisher.reconcile() }
            sourceStarted.await()
            val changed = record(version = 2, signer = "new")
            catalog.replaceVerified(changed)
            installed.values = listOf(installed(version = 2, signer = "new"))

            releaseSource.complete(Unit)
            refresh.await()
            advanceUntilIdle()

            assertEquals(changed, catalog.records.single())
            assertNull(publisher.state.value.single().iconPng)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `설치 확인 뒤 제거가 완료되면 늦은 icon 결과가 행과 UI를 재생성하지 않는다`() =
        runTest {
            val iconLoaded = CompletableDeferred<Unit>()
            val wrapper = installed(version = 1, signer = "same")
            val installed = LateReturningInstalledWrappers(wrapper)
            val catalog = FakeCatalog(listOf(record(version = 1, signer = "same")))
            val publisher = catalogPublisher(
                catalog = catalog,
                installed = installed,
                source = InstalledExtensionIconSource {
                    iconLoaded.complete(Unit)
                    PUBLISHER_PNG
                },
                scope = backgroundScope,
            )

            publisher.reconcile()
            runCurrent()
            iconLoaded.await()
            installed.findEntered.await()

            val removal = async { publisher.remove(PACKAGE) }
            runCurrent()

            assertNull(catalog.get(PACKAGE))
            assertTrue(publisher.state.value.none { it.packageName == PACKAGE })
            assertFalse(removal.isCompleted)
            assertFalse(installed.findReturned.isCompleted)

            installed.allowFindReturn.complete(Unit)
            removal.await()
            advanceUntilIdle()

            assertTrue(installed.findReturned.isCompleted)
            assertTrue(installed.findCompleted.isCompleted)
            assertEquals(0, catalog.iconInsertCount)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Room icon 저장 실패 뒤 반복 reconcile은 저장을 반복하거나 icon을 지우지 않는다`() =
        runTest {
            var providerLoadCount = 0
            val catalog = FakeCatalog(
                listOf(record(version = 1, signer = "same")),
            ).apply {
                persistFailuresRemaining = 1
            }
            val installed =
                FakeInstalledWrappers(listOf(installed(version = 1, signer = "same")))
            val publisher = catalogPublisher(
                catalog = catalog,
                installed = installed,
                source = InstalledExtensionIconSource {
                    providerLoadCount += 1
                    PUBLISHER_PNG
                },
                scope = backgroundScope,
            )
            val emissions = mutableListOf<ByteArray?>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                publisher.state.collect { records ->
                    emissions += records.singleOrNull()?.iconPng
                }
            }

            publisher.reconcile()
            runCurrent()
            assertContentEquals(PUBLISHER_PNG, publisher.state.value.single().iconPng)
            publisher.publish(listOf(record(version = 1, signer = "same")))
            assertContentEquals(PUBLISHER_PNG, publisher.state.value.single().iconPng)
            runCurrent()
            publisher.reconcile()
            runCurrent()
            collector.cancelAndJoin()

            val firstPositive = emissions.indexOfFirst { it != null }
            assertTrue(firstPositive >= 0)
            assertTrue(
                emissions.drop(firstPositive + 1).none { it == null },
                "최초 양성 아이콘 표시 뒤 null emission이 없어야 합니다.",
            )
            assertEquals(1, providerLoadCount)
            assertEquals(1, catalog.iconInsertCount)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `source load 뒤 null Room publish는 진행 중 persist job을 취소하지 않는다`() =
        runTest {
            val sourceLoaded = CompletableDeferred<Unit>()
            val findEntered = CompletableDeferred<Unit>()
            val allowFindReturn = CompletableDeferred<Unit>()
            val findCancelled = CompletableDeferred<Unit>()
            var providerLoadCount = 0
            val wrapper = installed(version = 1, signer = "same")
            val installed = object : InstalledWrapperRepository {
                override suspend fun findAll(): List<InstalledWrapper> = listOf(wrapper)

                override suspend fun find(packageName: String): InstalledWrapper? {
                    findEntered.complete(Unit)
                    return try {
                        allowFindReturn.await()
                        wrapper.takeIf { it.packageName == packageName }
                    } catch (error: CancellationException) {
                        findCancelled.complete(Unit)
                        throw error
                    }
                }
            }
            val nullIcon = record(version = 1, signer = "same")
            val catalog = FakeCatalog(listOf(nullIcon))
            val publisher = catalogPublisher(
                catalog = catalog,
                installed = installed,
                source = InstalledExtensionIconSource {
                    providerLoadCount += 1
                    sourceLoaded.complete(Unit)
                    PUBLISHER_PNG
                },
                scope = backgroundScope,
            )

            publisher.reconcile()
            runCurrent()
            sourceLoaded.await()
            findEntered.await()

            publisher.publish(listOf(nullIcon))
            runCurrent()

            assertFalse(findCancelled.isCompleted)
            assertContentEquals(PUBLISHER_PNG, publisher.state.value.single().iconPng)

            allowFindReturn.complete(Unit)
            runCurrent()

            assertEquals(1, providerLoadCount)
            assertEquals(1, catalog.iconInsertCount)
            assertContentEquals(PUBLISHER_PNG, catalog.records.single().iconPng)
            assertContentEquals(PUBLISHER_PNG, publisher.state.value.single().iconPng)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `새 publisher의 동일 identity null Room emission은 현재 UI icon을 유지한다`() =
        runTest {
            var providerLoadCount = 0
            val withIcon = record(version = 1, signer = "same").copy(
                iconPng = PUBLISHER_PNG,
            )
            val withoutIcon = withIcon.copy(iconPng = null)
            val catalog = FakeCatalog(listOf(withIcon))
            val installed =
                FakeInstalledWrappers(listOf(installed(version = 1, signer = "same")))
            val publisher = catalogPublisher(
                catalog = catalog,
                installed = installed,
                source = InstalledExtensionIconSource {
                    providerLoadCount += 1
                    null
                },
                scope = backgroundScope,
            )
            val emissions = mutableListOf<ByteArray?>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                publisher.state.collect { records ->
                    emissions += records.singleOrNull()?.iconPng
                }
            }

            publisher.publish(listOf(withIcon))
            runCurrent()
            catalog.records = listOf(withoutIcon)
            publisher.publish(listOf(withoutIcon))
            runCurrent()
            collector.cancelAndJoin()

            val firstPositive = emissions.indexOfFirst { it != null }
            assertTrue(firstPositive >= 0)
            assertTrue(
                emissions.drop(firstPositive + 1).none { it == null },
                "같은 full identity의 현재 UI icon 뒤 null state를 방출하지 않아야 합니다.",
            )
            assertContentEquals(PUBLISHER_PNG, publisher.state.value.single().iconPng)
            assertEquals(1, providerLoadCount)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `durable Room icon publish는 취소를 무시한 이전 find 결과로 덮어쓰지 않는다`() =
        runTest {
            val loadedPng = byteArrayOf(1, 2, 3)
            val durablePng = byteArrayOf(4, 5, 6)
            val wrapper = installed(version = 1, signer = "same")
            val installed = LateReturningInstalledWrappers(wrapper)
            val withoutIcon = record(version = 1, signer = "same")
            val withDurableIcon = withoutIcon.copy(iconPng = durablePng)
            val catalog = FakeCatalog(listOf(withoutIcon))
            val publisher = catalogPublisher(
                catalog = catalog,
                installed = installed,
                source = InstalledExtensionIconSource { loadedPng },
                scope = backgroundScope,
            )

            publisher.reconcile()
            runCurrent()
            installed.findEntered.await()

            catalog.records = listOf(withDurableIcon)
            publisher.publish(listOf(withDurableIcon))
            runCurrent()
            assertContentEquals(durablePng, publisher.state.value.single().iconPng)

            installed.allowFindReturn.complete(Unit)
            runCurrent()

            assertTrue(installed.findReturned.isCompleted)
            assertTrue(installed.findCompleted.isCompleted)
            assertEquals(0, catalog.iconInsertCount)
            assertContentEquals(durablePng, catalog.records.single().iconPng)
            assertContentEquals(durablePng, publisher.state.value.single().iconPng)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `이전 null publication의 늦은 schedule은 최신 durable Room icon을 덮어쓰지 않는다`() =
        runTest {
            val loadedPng = byteArrayOf(1, 2, 3)
            val durablePng = byteArrayOf(4, 5, 6)
            val firstCandidateCaptured = CompletableDeferred<Unit>()
            val allowFirstPublication = CompletableDeferred<Unit>()
            val withoutIcon = record(version = 1, signer = "same")
            val withDurableIcon = withoutIcon.copy(iconPng = durablePng)
            val backingCatalog = FakeCatalog(listOf(withoutIcon))
            var ensureCount = 0
            val catalog = object : ManagerCatalog by backingCatalog {
                override suspend fun ensureDiscoveredIdentity(
                    discovered: CatalogRecord,
                ): CatalogRecord {
                    val ensured = backingCatalog.ensureDiscoveredIdentity(discovered)
                    ensureCount += 1
                    if (ensureCount == 1) {
                        firstCandidateCaptured.complete(Unit)
                        allowFirstPublication.await()
                    }
                    return ensured
                }
            }
            var providerLoadCount = 0
            val installed =
                FakeInstalledWrappers(listOf(installed(version = 1, signer = "same")))
            val publisher = catalogPublisher(
                catalog = catalog,
                installed = installed,
                source = InstalledExtensionIconSource {
                    providerLoadCount += 1
                    loadedPng
                },
                scope = backgroundScope,
            )

            val firstPublish = async { publisher.publish(listOf(withoutIcon)) }
            runCurrent()
            firstCandidateCaptured.await()

            backingCatalog.records = listOf(withDurableIcon)
            val secondPublish = async { publisher.publish(listOf(withDurableIcon)) }
            runCurrent()
            assertFalse(secondPublish.isCompleted)

            allowFirstPublication.complete(Unit)
            runCurrent()
            firstPublish.await()
            secondPublish.await()
            runCurrent()

            assertEquals(0, providerLoadCount)
            assertEquals(0, backingCatalog.iconInsertCount)
            assertContentEquals(durablePng, backingCatalog.records.single().iconPng)
            assertContentEquals(durablePng, publisher.state.value.single().iconPng)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `삭제 실패는 이전 null publication의 늦은 icon schedule 권한을 폐기한다`() =
        runTest {
            val firstCandidateCaptured = CompletableDeferred<Unit>()
            val allowFirstPublication = CompletableDeferred<Unit>()
            val withoutIcon = record(version = 1, signer = "same")
            val backingCatalog = FakeCatalog(listOf(withoutIcon))
            var ensureCount = 0
            val catalog = object : ManagerCatalog by backingCatalog {
                override suspend fun ensureDiscoveredIdentity(
                    discovered: CatalogRecord,
                ): CatalogRecord {
                    val ensured = backingCatalog.ensureDiscoveredIdentity(discovered)
                    ensureCount += 1
                    if (ensureCount == 1) {
                        firstCandidateCaptured.complete(Unit)
                        allowFirstPublication.await()
                    }
                    return ensured
                }
            }
            var providerLoadCount = 0
            val installed =
                FakeInstalledWrappers(listOf(installed(version = 1, signer = "same")))
            val publisher = catalogPublisher(
                catalog = catalog,
                installed = installed,
                source = InstalledExtensionIconSource {
                    providerLoadCount += 1
                    PUBLISHER_PNG
                },
                scope = backgroundScope,
            )
            val removeFailure = IllegalStateException("Room unavailable")
            backingCatalog.removeFailure = removeFailure

            val firstPublish = async { publisher.publish(listOf(withoutIcon)) }
            runCurrent()
            firstCandidateCaptured.await()

            val thrownResult = CompletableDeferred<Throwable>()
            val removal = launch {
                try {
                    publisher.remove(PACKAGE)
                } catch (error: Throwable) {
                    thrownResult.complete(error)
                }
            }
            runCurrent()
            assertFalse(removal.isCompleted)

            allowFirstPublication.complete(Unit)
            runCurrent()
            firstPublish.await()
            removal.join()
            runCurrent()

            assertSame(removeFailure, thrownResult.await())
            assertEquals(0, providerLoadCount)
            assertEquals(0, backingCatalog.iconInsertCount)
            assertNull(backingCatalog.records.single().iconPng)
            assertNull(publisher.state.value.single().iconPng)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `한 package 삭제 실패는 같은 publication의 다른 package icon schedule을 유지한다`() =
        runTest {
            val secondPng = byteArrayOf(4, 5, 6)
            val candidatesCaptured = CompletableDeferred<Unit>()
            val allowPublication = CompletableDeferred<Unit>()
            val first = record(version = 1, signer = "same")
            val second = first.copy(
                packageName = SECOND_PACKAGE,
                extensionId = SECOND_EXTENSION_ID,
                name = "Second",
            )
            val backingCatalog = FakeCatalog(listOf(first, second))
            val persistedPackages = mutableListOf<String>()
            var ensureCount = 0
            val catalog = object : ManagerCatalog by backingCatalog {
                override suspend fun ensureDiscoveredIdentity(
                    discovered: CatalogRecord,
                ): CatalogRecord {
                    val ensured = backingCatalog.ensureDiscoveredIdentity(discovered)
                    ensureCount += 1
                    if (ensureCount == 2) {
                        candidatesCaptured.complete(Unit)
                        allowPublication.await()
                    }
                    return ensured
                }

                override suspend fun persistIconIfIdentity(
                    identity: CatalogIdentity,
                    iconPng: ByteArray,
                ): CatalogRecord? {
                    persistedPackages += identity.packageName
                    return backingCatalog.persistIconIfIdentity(identity, iconPng)
                }
            }
            val providerLoads = mutableListOf<String>()
            val installed = FakeInstalledWrappers(
                listOf(
                    installed(PACKAGE, version = 1, signer = "same"),
                    installed(SECOND_PACKAGE, version = 1, signer = "same"),
                ),
            )
            val publisher = catalogPublisher(
                catalog = catalog,
                installed = installed,
                source = InstalledExtensionIconSource { packageName ->
                    providerLoads += packageName
                    when (packageName) {
                        PACKAGE -> PUBLISHER_PNG
                        SECOND_PACKAGE -> secondPng
                        else -> error("unexpected package: $packageName")
                    }
                },
                scope = backgroundScope,
            )
            val removeFailure = IllegalStateException("Room unavailable")
            backingCatalog.removeFailure = removeFailure

            val publication = async { publisher.publish(listOf(first, second)) }
            runCurrent()
            candidatesCaptured.await()

            val thrownResult = CompletableDeferred<Throwable>()
            val removal = launch {
                try {
                    publisher.remove(PACKAGE)
                } catch (error: Throwable) {
                    thrownResult.complete(error)
                }
            }
            runCurrent()
            assertFalse(removal.isCompleted)

            allowPublication.complete(Unit)
            runCurrent()
            publication.await()
            removal.join()
            runCurrent()

            assertSame(removeFailure, thrownResult.await())
            assertEquals(listOf(SECOND_PACKAGE), providerLoads)
            assertEquals(listOf(SECOND_PACKAGE), persistedPackages)
            assertEquals(1, backingCatalog.iconInsertCount)
            val catalogByPackage = backingCatalog.records.associateBy(CatalogRecord::packageName)
            val stateByPackage = publisher.state.value.associateBy(CatalogRecord::packageName)
            assertNull(catalogByPackage.getValue(PACKAGE).iconPng)
            assertNull(stateByPackage.getValue(PACKAGE).iconPng)
            assertContentEquals(secondPng, catalogByPackage.getValue(SECOND_PACKAGE).iconPng)
            assertContentEquals(secondPng, stateByPackage.getValue(SECOND_PACKAGE).iconPng)
        }

    @Test
    fun `identity 변경은 이전 bytes와 저장 실패 상태를 재사용하지 않는다`() = runBlocking {
        var providerLoadCount = 0
        val firstPng = byteArrayOf(1, 2, 3)
        val secondPng = byteArrayOf(4, 5, 6)
        val catalog = FakeCatalog(
            listOf(record(version = 1, signer = "same")),
        ).apply {
            persistFailuresRemaining = 1
        }
        val installed =
            FakeInstalledWrappers(listOf(installed(version = 1, signer = "same")))
        val publisher = catalogPublisher(
            catalog = catalog,
            installed = installed,
            source = InstalledExtensionIconSource {
                providerLoadCount += 1
                if (providerLoadCount == 1) firstPng else secondPng
            },
        )

        publisher.reconcile()
        assertContentEquals(firstPng, publisher.state.value.single().iconPng)

        installed.values = listOf(installed(version = 2, signer = "changed"))
        publisher.reconcile()

        val current = publisher.state.value.single()
        assertEquals(2, current.installedVersionCode)
        assertEquals("changed", current.signerSha256)
        assertContentEquals(secondPng, current.iconPng)
        assertContentEquals(secondPng, catalog.records.single().iconPng)
        assertEquals(2, providerLoadCount)
        assertEquals(2, catalog.iconInsertCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Room 삭제 실패는 active icon job 정리 뒤 취소 결과와 기존 UI를 유지한다`() =
        runTest {
            val root = temporaryFolder.newFolder("uninstall-publisher-failure")
            val bundle = importedBundle(root)
            val retainedImports = RetainedImportStore(root) { _, _, _ ->
                WrapperVerificationResult.Success(bundle.verifiedApk)
            }
            val pendingStore = StartupPendingStore(null, root)
            val gateway = StartupGateway(emptyList())
            val installed = LateReturningInstalledWrappers(
                installed(version = 1, signer = "same"),
            )
            val catalog = FakeCatalog(listOf(record(version = 1, signer = "same")))
            val installCoordinator = InstallCoordinator(
                InstallPolicy(),
                gateway,
                pendingStore,
                installed,
                catalog,
            )
            val publisher = catalogPublisher(
                catalog = catalog,
                installed = installed,
                source = InstalledExtensionIconSource { PUBLISHER_PNG },
                scope = backgroundScope,
            )
            publisher.reconcile()
            runCurrent()
            installed.findEntered.await()
            val repository = managerRepository(
                root = root,
                retainedImports = retainedImports,
                gateway = gateway,
                pendingStore = pendingStore,
                installed = installed,
                catalog = catalog,
                coordinator = installCoordinator,
                uninstallCoordinator = object : UninstallCoordinator {
                    override fun intent(packageName: String) =
                        error("이 테스트에서는 Intent를 만들지 않습니다.")

                    override suspend fun reconcileAfterReturn(packageName: String) =
                        UninstallOutcome.Removed
                },
                catalogPublisher = publisher,
            )

            catalog.removeFailure = IllegalStateException("Room unavailable")
            val reconciliation = async {
                repository.reconcileUninstall(PACKAGE)
            }
            runCurrent()

            assertFalse(reconciliation.isCompleted)
            assertFalse(installed.findReturned.isCompleted)
            assertNotNull(catalog.get(PACKAGE))
            assertTrue(publisher.state.value.any { it.packageName == PACKAGE })

            installed.allowFindReturn.complete(Unit)
            assertEquals(
                UninstallOutcome.CancelledOrStillInstalled,
                reconciliation.await(),
            )
            assertTrue(installed.findReturned.isCompleted)
            assertTrue(installed.findCompleted.isCompleted)
            assertNotNull(catalog.get(PACKAGE))
            assertTrue(publisher.state.value.any { it.packageName == PACKAGE })

            catalog.removeFailure = null
            assertEquals(UninstallOutcome.Removed, repository.reconcileUninstall(PACKAGE))
            assertNull(catalog.get(PACKAGE))
            assertTrue(publisher.state.value.none { it.packageName == PACKAGE })
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `Room 삭제 cancellation은 active icon job 정리 뒤 원 cancellation을 재전파한다`() =
        runTest {
            val wrapper = installed(version = 1, signer = "same")
            val installed = LateReturningInstalledWrappers(wrapper)
            val catalog = FakeCatalog(listOf(record(version = 1, signer = "same")))
            val publisher = catalogPublisher(
                catalog = catalog,
                installed = installed,
                source = InstalledExtensionIconSource { PUBLISHER_PNG },
                scope = backgroundScope,
            )
            publisher.reconcile()
            runCurrent()
            installed.findEntered.await()
            val cancellation = CancellationException("cancelled")
            catalog.removeFailure = cancellation

            val thrownResult = CompletableDeferred<Throwable>()
            val removal = launch {
                try {
                    publisher.remove(PACKAGE)
                } catch (error: Throwable) {
                    thrownResult.complete(error)
                }
            }
            runCurrent()

            assertFalse(removal.isCompleted)
            assertFalse(installed.findReturned.isCompleted)
            assertNotNull(catalog.get(PACKAGE))
            assertTrue(publisher.state.value.any { it.packageName == PACKAGE })

            installed.allowFindReturn.complete(Unit)
            removal.join()

            assertSame(cancellation, thrownResult.await())
            assertTrue(installed.findReturned.isCompleted)
            assertTrue(installed.findCompleted.isCompleted)
            assertNotNull(catalog.get(PACKAGE))
            assertTrue(publisher.state.value.any { it.packageName == PACKAGE })
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `영구 suspend icon source는 제거 reconciliation을 막지 않고 stale 작업을 정리한다`() =
        runTest {
            val sourceStarted = CompletableDeferred<Unit>()
            val sourceCancelled = CompletableDeferred<Unit>()
            val catalog = FakeCatalog(listOf(record(version = 1, signer = "same")))
            val installed =
                FakeInstalledWrappers(listOf(installed(version = 1, signer = "same")))
            val publisher = catalogPublisher(
                catalog,
                installed,
                InstalledExtensionIconSource {
                    sourceStarted.complete(Unit)
                    try {
                        kotlinx.coroutines.awaitCancellation()
                    } finally {
                        sourceCancelled.complete(Unit)
                    }
                },
            )
            val initial = async { publisher.reconcile() }
            sourceStarted.await()
            installed.values = emptyList()
            catalog.remove(PACKAGE)
            val removal = async { publisher.reconcile() }

            try {
                runCurrent()
                assertTrue(removal.isCompleted)
                removal.await()
                sourceCancelled.await()
                assertTrue(publisher.state.value.isEmpty())
                assertTrue(catalog.records.isEmpty())
            } finally {
                initial.cancelAndJoin()
                removal.cancelAndJoin()
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `같은 identity의 background icon 작업은 하나만 실행한다`() = runTest {
        val sourceStarted = CompletableDeferred<Unit>()
        val sourceCancelled = CompletableDeferred<Unit>()
        var calls = 0
        val catalog = FakeCatalog(listOf(record(version = 1, signer = "same")))
        val installed =
            FakeInstalledWrappers(listOf(installed(version = 1, signer = "same")))
        val publisher = catalogPublisher(
            catalog = catalog,
            installed = installed,
            source = InstalledExtensionIconSource {
                calls += 1
                sourceStarted.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    sourceCancelled.complete(Unit)
                }
            },
            scope = backgroundScope,
        )

        publisher.reconcile()
        sourceStarted.await()
        publisher.reconcile()
        runCurrent()

        assertEquals(1, calls)
        installed.values = emptyList()
        publisher.reconcile()
        sourceCancelled.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `icon timeout은 source를 취소 정리하고 다음 publish에서 재시도한다`() = runTest {
        var calls = 0
        var active = 0
        val firstCancelled = CompletableDeferred<Unit>()
        val catalog = FakeCatalog(listOf(record(version = 1, signer = "same")))
        val installed =
            FakeInstalledWrappers(listOf(installed(version = 1, signer = "same")))
        val publisher = catalogPublisher(
            catalog = catalog,
            installed = installed,
            source = InstalledExtensionIconSource {
                calls += 1
                active += 1
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    active -= 1
                    firstCancelled.complete(Unit)
                }
            },
            scope = backgroundScope,
            iconLoadTimeoutMillis = 1_000,
        )

        publisher.reconcile()
        runCurrent()
        assertEquals(1, calls)
        assertEquals(1, active)

        advanceTimeBy(1_001)
        runCurrent()
        firstCancelled.await()
        assertEquals(0, active)

        publisher.reconcile()
        runCurrent()
        assertEquals(2, calls)
        assertEquals(1, active)

        installed.values = emptyList()
        publisher.reconcile()
        runCurrent()
        assertEquals(0, active)
    }

    private fun managerRepository(
        root: File,
        retainedImports: RetainedImportStore,
        gateway: StartupGateway,
        pendingStore: StartupPendingStore,
        installed: InstalledWrapperRepository,
        catalog: FakeCatalog,
        coordinator: InstallCoordinator,
        afterInstallRequest: suspend (InstallState) -> Unit = {},
        uninstallCoordinator: UninstallCoordinator = object : UninstallCoordinator {
            override fun intent(packageName: String) = error("uninstall하지 않습니다.")
            override suspend fun reconcileAfterReturn(packageName: String) =
                UninstallOutcome.CancelledOrStillInstalled
        },
        catalogPublisher: CatalogPublisher = catalogPublisher(catalog, installed),
        conversionCoordinator: ConversionCoordinatorPort? = null,
        generatedImporter: suspend (GeneratedBundle) -> ImportResult = {
            ImportResult.Failure(ImportFailure(ImportFailureCode.CACHE_IO))
        },
    ) = DefaultManagerRepository(
        importer = BundleImportService(
            BundleSource { error("직접 import하지 않습니다.") },
            AndroidWrapperVerifier(ArchiveInspector { error("검증하지 않습니다.") }),
            root,
            ioDispatcher = Dispatchers.Unconfined,
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
        uninstallCoordinator = uninstallCoordinator,
        samsungBrowserNavigator = object : SamsungBrowserNavigator {
            override fun addOnsIntent() = error("Add-ons를 열지 않습니다.")
            override fun availability() = SamsungBrowserAvailability.SamsungBrowserMissing
        },
        catalogPublisher = catalogPublisher,
        installEvents = emptyFlow(),
        ioDispatcher = Dispatchers.Unconfined,
        afterInstallRequest = afterInstallRequest,
        conversionCoordinator = conversionCoordinator,
        generatedImporter = generatedImporter,
    )

    private fun catalogPublisher(
        catalog: ManagerCatalog,
        installed: InstalledWrapperRepository,
        source: InstalledExtensionIconSource = InstalledExtensionIconSource { null },
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        iconLoadTimeoutMillis: Long = 5_000,
    ) = CatalogPublisher(
        catalog = catalog,
        installedWrappers = installed,
        reconciler = CatalogReconciler(),
        iconEnricher = CatalogIconEnricher(catalog, source),
        scope = scope,
        iconLoadTimeoutMillis = iconLoadTimeoutMillis,
    )

    private fun importedBundle(root: File, token: String = TOKEN): ImportedBundle {
        val directory = File(root, token).apply { mkdir() }
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
        return ImportedBundle(token, descriptor, report, verified, wrapper, hash)
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
        val PUBLISHER_PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    }
}

private class FakeCatalog(
    initial: List<CatalogRecord>,
) : ManagerCatalog {
    var records = initial
    var iconInsertCount = 0
    var persistFailuresRemaining = 0
    var removeFailure: Throwable? = null

    override fun observe(): Flow<List<CatalogRecord>> = flowOf(records)
    override suspend fun replaceVerified(record: CatalogRecord) {
        records = records.filterNot { it.packageName == record.packageName } + record
    }
    override suspend fun ensureDiscoveredIdentity(discovered: CatalogRecord): CatalogRecord {
        val current = records.singleOrNull { it.packageName == discovered.packageName }
        if (current != null && current.identity() == discovered.identity()) return current
        records = records.filterNot { it.packageName == discovered.packageName } + discovered
        return discovered
    }
    override suspend fun persistIconIfIdentity(
        identity: CatalogIdentity,
        iconPng: ByteArray,
    ): CatalogRecord? {
        iconInsertCount += 1
        if (persistFailuresRemaining > 0) {
            persistFailuresRemaining -= 1
            error("transient icon write failure")
        }
        val index = records.indexOfFirst { it.packageName == identity.packageName }
        val current = records.getOrNull(index) ?: return null
        if (current.extensionId != identity.extensionId ||
            current.installedVersionCode != identity.installedVersionCode ||
            current.signerSha256 != identity.signerSha256
        ) {
            return null
        }
        return current.copy(iconPng = iconPng).also { stored ->
            records = records.toMutableList().also { it[index] = stored }
        }
    }
    override suspend fun remove(packageName: String) {
        removeFailure?.let { throw it }
        records = records.filterNot { it.packageName == packageName }
    }
    override suspend fun removeAll(packageNames: Set<String>) {
        records = records.filterNot { it.packageName in packageNames }
    }
    override suspend fun get(packageName: String): CatalogRecord? =
        records.singleOrNull { it.packageName == packageName }
}

private class FakeInstalledWrappers(
    var values: List<InstalledWrapper>,
) : InstalledWrapperRepository {
    var findFailure: Throwable? = null

    override suspend fun findAll(): List<InstalledWrapper> = values
    override suspend fun find(packageName: String): InstalledWrapper? {
        findFailure?.let { throw it }
        return values.singleOrNull { it.packageName == packageName }
    }
}

private class LateReturningInstalledWrappers(
    private val wrapper: InstalledWrapper,
) : InstalledWrapperRepository {
    val findEntered = CompletableDeferred<Unit>()
    val allowFindReturn = CompletableDeferred<Unit>()
    val findReturned = CompletableDeferred<Unit>()
    val findCompleted = CompletableDeferred<Unit>()

    override suspend fun findAll(): List<InstalledWrapper> = listOf(wrapper)

    override suspend fun find(packageName: String): InstalledWrapper? {
        findEntered.complete(Unit)
        return try {
            withContext(NonCancellable) {
                allowFindReturn.await()
                findReturned.complete(Unit)
                wrapper.takeIf { it.packageName == packageName }
            }
        } finally {
            findCompleted.complete(Unit)
        }
    }
}

private class StartupGateway(
    owned: List<Int>,
    ownedPackageName: String? = null,
) : PackageInstallGateway {
    private val sessions = owned.associateWith { ownedPackageName }.toMutableMap()
    private var defaultPackageName = ownedPackageName
    var canInstallPackages = true

    override fun canRequestPackageInstalls(): Boolean = canInstallPackages
    override fun unknownSourcesSettingsIntent() =
        error("startup test에서는 설정 Intent를 만들지 않습니다.")
    override suspend fun createSession(packageName: String): Int = 41.also { sessionId ->
        defaultPackageName = packageName
        sessions[sessionId] = packageName
    }
    override suspend fun writeSession(sessionId: Int, apk: File) = Unit
    override fun commit(sessionId: Int) = Unit
    override fun abandon(sessionId: Int) {
        sessions.remove(sessionId)
    }
    override fun ownedSessions(): List<Int> = sessions.keys.toList()
    override fun sessionSnapshot(sessionId: Int): InstallSessionSnapshot? =
        if (sessions.containsKey(sessionId)) {
            InstallSessionSnapshot(
                sessionId,
                sessions[sessionId],
                ownedByCaller = true,
            )
        } else {
            null
        }
    fun replaceOwnedSessions(vararg sessionIds: Int) {
        sessions.clear()
        sessionIds.forEach { sessions[it] = defaultPackageName }
    }
}

private class StartupPendingStore(
    initial: PendingInstall?,
    private val importRoot: File? = null,
) : PendingInstallStore {
    var record = initial
    var terminal: PendingInstallResult? = null
    var finalReceipt: FinalInstallReceipt? = null
    var normalHandoff: NormalInstallHandoff? = null
    var discardFailure = false
    var discardAttempts = 0
    var loadFailures = 0

    override fun load(): PendingInstall? {
        if (loadFailures > 0) {
            loadFailures -= 1
            error("transient pending load failure")
        }
        return record
    }
    override fun tryClaim(
        packageName: String,
        importToken: String,
        startedAtEpochMillis: Long,
    ): PendingInstall? {
        if (record != null || terminal != null || finalReceipt != null || normalHandoff != null) {
            return null
        }
        return PendingInstall(-1, packageName, importToken, startedAtEpochMillis)
            .also { record = it }
    }
    override fun assignSession(claim: PendingInstall, sessionId: Int): PendingInstall? {
        if (record != claim) return null
        return claim.copy(sessionId = sessionId).also { record = it }
    }
    override fun recoverSession(claim: PendingInstall, sessionId: Int): PendingInstall? {
        if (record != claim) return null
        return claim.copy(sessionId = sessionId).also { record = it }
    }
    override fun clear(expected: PendingInstall): Boolean {
        if (record != expected) return false
        record = null
        return true
    }
    override fun clearWithoutTerminal(expected: PendingInstall): PendingCleanupResult {
        if (record != expected || discardFailure) return PendingCleanupResult.Failed
        return try {
            discardImport(expected.importToken)
            record = null
            PendingCleanupResult.Cleared
        } catch (_: Exception) {
            PendingCleanupResult.Failed
        }
    }
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
        val pending = record
        val lateCancel = finalReceipt?.takeIf {
            pending == null &&
                it.origin == FinalReceiptOrigin.PROGRAM_CANCEL &&
                it.stage != FinalReceiptStage.ACKNOWLEDGED &&
                it.sessionId == result.sessionId
        }
        if (result is PackageInstallResult.PendingUserAction ||
            pending?.sessionId != result.sessionId && lateCancel == null
        ) {
            return false
        }
        if (terminal != null) return false
        terminal = PendingInstallResult(
            result = result,
            terminalAtEpochMillis = terminalAtEpochMillis,
            packageName = pending?.packageName ?: requireNotNull(lateCancel).packageName,
            importToken = pending?.importToken ?: requireNotNull(lateCancel).importToken,
        )
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
    override fun loadFinalReceipt(): FinalInstallReceipt? = finalReceipt
    override fun createFinalReceipt(
        expectedPending: PendingInstall?,
        expectedRaw: PendingInstallResult?,
        receipt: FinalInstallReceipt,
    ): Boolean {
        if (record != expectedPending || terminal != expectedRaw || finalReceipt != null) {
            return false
        }
        finalReceipt = receipt
        return true
    }
    override fun replaceFinalReceipt(
        expected: FinalInstallReceipt,
        expectedRaw: PendingInstallResult?,
        replacement: FinalInstallReceipt,
    ): Boolean {
        if (finalReceipt != expected || terminal != expectedRaw) return false
        finalReceipt = replacement
        return true
    }
    override fun acknowledgeFinalReceipt(
        expected: FinalInstallReceipt,
        expectedRaw: PendingInstallResult?,
    ): FinalInstallReceipt? {
        if (finalReceipt != expected ||
            terminal != expectedRaw ||
            expected.stage != FinalReceiptStage.READY
        ) {
            return null
        }
        return expected.copy(stage = FinalReceiptStage.ACKNOWLEDGED)
            .also { finalReceipt = it }
    }
    override fun finalizeFinalReceipt(expected: FinalInstallReceipt): Boolean {
        val current = finalReceipt ?: return true
        if (current != expected || current.stage != FinalReceiptStage.ACKNOWLEDGED) {
            return false
        }
        finalReceipt = null
        terminal = null
        return true
    }
    override fun clearPendingPreservingImport(expected: PendingInstall): Boolean {
        if (record != expected) return false
        record = null
        return true
    }
    override fun loadNormalHandoff(): NormalInstallHandoff? = normalHandoff
    override fun compareAndSetNormalHandoff(
        expected: NormalInstallHandoff?,
        replacement: NormalInstallHandoff?,
    ): Boolean {
        if (normalHandoff != expected) return false
        normalHandoff = replacement
        return true
    }
    override fun firstSessionMissingAt(
        pending: PendingInstall,
        observedAtEpochMillis: Long,
    ): Long = observedAtEpochMillis
    override fun clearSessionMissingObservation(pending: PendingInstall): Boolean = true
}
