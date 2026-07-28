package dev.sixbridge.manager.app

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.manager.catalog.CatalogIdentity
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.CatalogReconciler
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.cleanup.StartupCleanup
import dev.sixbridge.manager.conversion.ConversionBeginResult
import dev.sixbridge.manager.conversion.ConversionBuildResult
import dev.sixbridge.manager.conversion.ConversionCoordinatorPort
import dev.sixbridge.manager.conversion.ConversionFailureCode
import dev.sixbridge.manager.conversion.GeneratedBundle
import dev.sixbridge.manager.conversion.InstallationIdentitySelection
import dev.sixbridge.manager.conversion.MemoryPreferenceControl
import dev.sixbridge.manager.conversion.OnDeviceInputRequest
import dev.sixbridge.manager.conversion.RestoredConversion
import dev.sixbridge.manager.conversion.memorySharedPreferences
import dev.sixbridge.manager.icon.CatalogIconEnricher
import dev.sixbridge.manager.icon.InstalledExtensionIconSource
import dev.sixbridge.manager.importer.BundleImportService
import dev.sixbridge.manager.importer.BundleSource
import dev.sixbridge.manager.importer.ImportedBundle
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import dev.sixbridge.manager.install.FinalReceiptOrigin
import dev.sixbridge.manager.install.FinalReceiptStage
import dev.sixbridge.manager.install.InstallCoordinator
import dev.sixbridge.manager.install.InstallPolicy
import dev.sixbridge.manager.install.InstallSessionSnapshot
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.install.NormalHandoffStage
import dev.sixbridge.manager.install.PackageInstallGateway
import dev.sixbridge.manager.install.PackageInstallResult
import dev.sixbridge.manager.install.SharedPreferencesPendingInstallStore
import dev.sixbridge.manager.navigation.SamsungBrowserAvailability
import dev.sixbridge.manager.navigation.SamsungBrowserNavigator
import dev.sixbridge.manager.ui.ConversionUiState
import dev.sixbridge.manager.ui.ManagerAction
import dev.sixbridge.manager.ui.ManagerRoute
import dev.sixbridge.manager.ui.ManagerViewModel
import dev.sixbridge.manager.ui.UserMessageKind
import dev.sixbridge.manager.uninstall.UninstallCoordinator
import dev.sixbridge.manager.uninstall.UninstallOutcome
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.ArchiveInspector
import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import dev.sixbridge.manager.wrapper.WrapperVerificationResult
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalHandoffRaceInstrumentedTest {
    @Test
    fun normalSourceViewModelPreparedHandoffRebasesAllLateRawOutcomesAndAcksExactly() =
        runBlocking {
            terminalCases().forEach { case ->
                val fixture = ActualFixture()
                try {
                    val savedState = SavedStateHandle(
                        mapOf(ManagerViewModel.REVIEW_TOKEN_KEY to REVIEW_TOKEN),
                    )
                    val source = ManagerViewModel(
                        repository = fixture.repository,
                        savedStateHandle = savedState,
                        afterNormalHandoffPersisted = {
                            throw CancellationException("PREPARED 직후 process death")
                        },
                    )
                    val sourceStore = ViewModelStore().apply {
                        put("source", source)
                    }
                    fixture.awaitReview(source, savedState)
                    source.accept(ManagerAction.InstallReviewedBundle)
                    fixture.awaitSession(savedState, source)
                    source.accept(ManagerAction.CancelReview)
                    fixture.await("normal PREPARED", source, savedState) {
                        fixture.store.loadNormalHandoff()?.stage ==
                            NormalHandoffStage.PREPARED
                    }
                    sourceStore.clear()
                    if (case.raw is PackageInstallResult.Success) {
                        fixture.installed.installed = fixture.installedWrapper()
                    }
                    assertTrue(fixture.coordinator.onPackageInstallerResult(case.raw))
                    assertEquals(
                        case.name,
                        FinalReceiptOrigin.RAW_TERMINAL,
                        fixture.store.loadFinalReceipt()?.origin,
                    )

                    val recreated = ManagerViewModel(fixture.repository, savedState)
                    fixture.await("normal OUTCOME_READY", recreated, savedState) {
                        fixture.store.loadNormalHandoff()?.stage ==
                            NormalHandoffStage.OUTCOME_READY
                    }
                    assertEquals(
                        case.name,
                        case.expected,
                        fixture.store.loadNormalHandoff()?.state,
                    )
                    assertNull(case.name, fixture.store.loadFinalReceipt())
                    assertEquals(case.name, case.route, recreated.state.value.route)

                    fixture.recoveryControl.failNextCommit = true
                    recreated.accept(case.acknowledgement)
                    assertNotNull(case.name, fixture.store.loadNormalHandoff())
                    assertEquals(case.name, case.route, recreated.state.value.route)
                    recreated.accept(case.acknowledgement)
                    assertNull(case.name, fixture.store.loadNormalHandoff())

                    val cold = ManagerViewModel(fixture.repository, SavedStateHandle())
                    delay(50)
                    assertNull(case.name, cold.state.value.message)
                    assertTrue(
                        case.name,
                        fixture.store.tryClaim(
                            packageName = PACKAGE,
                            importToken = SECOND_TOKEN,
                            startedAtEpochMillis = 20,
                        ) != null,
                    )
                } finally {
                    fixture.close()
                }
            }
        }

    @Test
    fun generatedSourceViewModelSavedHandoffRebasesAllLateRawOutcomesAndInstalledGap() =
        runBlocking {
            terminalCases().forEach { case ->
                val fixture = ActualFixture()
                try {
                    val savedState = SavedStateHandle()
                    val source = ManagerViewModel(
                        repository = fixture.repository,
                        savedStateHandle = savedState,
                        afterGeneratedTerminalHandoffPersisted = {
                            throw CancellationException("generated handoff 직후 process death")
                        },
                    )
                    val sourceStore = ViewModelStore().apply {
                        put("source", source)
                    }
                    fixture.awaitGeneratedResult(source, savedState)
                    source.accept(ManagerAction.PrepareGeneratedInstall)
                    source.accept(ManagerAction.InstallReviewedBundle)
                    fixture.awaitSession(savedState, source)
                    source.accept(ManagerAction.CancelReview)
                    fixture.await("generated terminal handoff", source, savedState) {
                        savedState.get<String>(
                            ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY,
                        ) != null
                    }
                    sourceStore.clear()
                    if (case.raw is PackageInstallResult.Success) {
                        fixture.installed.installed = fixture.installedWrapper()
                    }
                    assertTrue(fixture.coordinator.onPackageInstallerResult(case.raw))

                    val firstRecreated = ManagerViewModel(
                        repository = fixture.repository,
                        savedStateHandle = savedState,
                        afterGeneratedTerminalHandoffCleared = {
                            if (case.raw is PackageInstallResult.Success) {
                                throw CancellationException(
                                    "handoff clear와 cleanup enqueue 사이 process death",
                                )
                            }
                        },
                    )
                    val firstRecreatedStore = ViewModelStore().apply {
                        put("firstRecreated", firstRecreated)
                    }
                    if (case.raw is PackageInstallResult.Success) {
                        fixture.await(
                            "generated Success handoff clear",
                            firstRecreated,
                            savedState,
                        ) {
                            savedState.get<String>(
                                ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY,
                            ) == null && fixture.conversion.discardedTokens.isEmpty()
                        }
                        firstRecreatedStore.clear()
                        val finalRecreated = ManagerViewModel(
                            fixture.repository,
                            savedState,
                        )
                        fixture.await(
                            "generated Success cleanup resume",
                            finalRecreated,
                            savedState,
                        ) {
                            fixture.conversion.discardedTokens == listOf(CONVERSION_TOKEN)
                        }
                        assertEquals(
                            ManagerRoute.Report(PACKAGE),
                            finalRecreated.state.value.route,
                        )
                        assertFalse(fixture.retainedReviewExists())
                    } else {
                        fixture.await(
                            "generated ${case.name} handoff clear",
                            firstRecreated,
                            savedState,
                        ) {
                            savedState.get<String>(
                                ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY,
                            ) == null
                        }
                        assertEquals(
                            case.name,
                            ManagerRoute.ConversionResult(CONVERSION_TOKEN),
                            firstRecreated.state.value.route,
                        )
                        assertFalse(
                            case.name,
                            firstRecreated.state.value
                                .let { it.conversionState as ConversionUiState.ResultReady }
                                .installOwnerActive,
                        )
                        assertEquals(
                            case.name,
                            case.messageKind,
                            firstRecreated.state.value.message?.kind,
                        )
                        firstRecreatedStore.clear()
                    }
                    assertNull(case.name, fixture.store.loadFinalReceipt())
                } finally {
                    fixture.close()
                }
            }
        }

    private fun terminalCases(): List<TerminalCase> = listOf(
        TerminalCase(
            name = "Success",
            raw = PackageInstallResult.Success(SESSION_ID),
            expected = InstallState.Installed(PACKAGE),
            route = ManagerRoute.Report(PACKAGE),
            acknowledgement = ManagerAction.ReturnHome,
            messageKind = null,
        ),
        TerminalCase(
            name = "Failure",
            raw = PackageInstallResult.Failure(
                SESSION_ID,
                "LATE_FAILURE",
                "late failure",
            ),
            expected = InstallState.Failed("LATE_FAILURE", "late failure"),
            route = ManagerRoute.Home,
            acknowledgement = ManagerAction.DismissMessage,
            messageKind = UserMessageKind.INSTALL_FAILED,
        ),
        TerminalCase(
            name = "Cancelled",
            raw = PackageInstallResult.Cancelled(SESSION_ID),
            expected = InstallState.Cancelled,
            route = ManagerRoute.Home,
            acknowledgement = ManagerAction.DismissMessage,
            messageKind = UserMessageKind.INSTALL_CANCELLED,
        ),
    )

    private data class TerminalCase(
        val name: String,
        val raw: PackageInstallResult,
        val expected: InstallState,
        val route: ManagerRoute,
        val acknowledgement: ManagerAction,
        val messageKind: UserMessageKind?,
    )

    private class ActualFixture {
        val root: File = Files.createTempDirectory("sixbridge-race-actual").toFile()
        private val pendingPreferences = memorySharedPreferences()
        val recoveryControl = MemoryPreferenceControl()
        private val recoveryPreferences = memorySharedPreferences(recoveryControl)
        val store = SharedPreferencesPendingInstallStore(
            pendingPreferences = pendingPreferences,
            recoveryPreferences = recoveryPreferences,
            importDirectory = root,
        )
        val gateway = TestPackageInstallGateway()
        val installed = MutableInstalledWrappers()
        val catalog = TestCatalog()
        val conversion = TestConversionCoordinator()
        private val bundle = importedBundle(root)
        private val generated = GeneratedBundle(
            token = CONVERSION_TOKEN,
            file = File(root, "generated.six").apply {
                writeBytes(byteArrayOf(4, 5, 6))
            },
            sha256 = sha256(byteArrayOf(4, 5, 6)),
            suggestedFileName = "generated.six",
        )
        val coordinator = InstallCoordinator(
            policy = InstallPolicy(),
            gateway = gateway,
            pendingStore = store,
            installedWrappers = installed,
            catalog = catalog,
            clock = { 10 },
        )
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val retained = RetainedImportStore(root) { _, _, _ ->
            WrapperVerificationResult.Success(bundle.verifiedApk)
        }
        val repository: DefaultManagerRepository

        init {
            conversion.configure(bundle.toReview(), generated)
            assertTrue(retained.save(bundle))
            val publisher = CatalogPublisher(
                catalog = catalog,
                installedWrappers = installed,
                reconciler = CatalogReconciler(),
                iconEnricher = CatalogIconEnricher(
                    catalog,
                    InstalledExtensionIconSource { null },
                ),
                scope = scope,
            )
            repository = DefaultManagerRepository(
                importer = BundleImportService(
                    BundleSource { error("직접 import하지 않습니다.") },
                    AndroidWrapperVerifier(
                        ArchiveInspector { error("wrapper를 다시 검증하지 않습니다.") },
                    ),
                    root,
                    ioDispatcher = Dispatchers.Unconfined,
                ),
                retainedImports = retained,
                startupCleanup = StartupCleanup(
                    root,
                    gateway,
                    store,
                    Clock.fixed(Instant.ofEpochMilli(11), ZoneOffset.UTC),
                ),
                installCoordinator = coordinator,
                pendingStore = store,
                uninstallCoordinator = object : UninstallCoordinator {
                    override fun intent(packageName: String): Intent =
                        error("제거하지 않습니다.")

                    override suspend fun reconcileAfterReturn(
                        packageName: String,
                    ): UninstallOutcome = UninstallOutcome.CancelledOrStillInstalled
                },
                samsungBrowserNavigator = object : SamsungBrowserNavigator {
                    override fun addOnsIntent(): Intent =
                        error("추가 기능 화면을 열지 않습니다.")

                    override fun availability(): SamsungBrowserAvailability =
                        SamsungBrowserAvailability.SamsungBrowserMissing
                },
                catalogPublisher = publisher,
                installEvents = emptyFlow(),
                ioDispatcher = Dispatchers.Unconfined,
                conversionCoordinator = conversion,
            )
            repository.start(scope, catalog)
        }

        suspend fun awaitReview(
            viewModel: ManagerViewModel,
            savedState: SavedStateHandle,
        ) {
            await("normal review", viewModel, savedState) {
                viewModel.state.value.route == ManagerRoute.Review(REVIEW_TOKEN)
            }
        }

        suspend fun awaitGeneratedResult(
            viewModel: ManagerViewModel,
            savedState: SavedStateHandle,
        ) {
            await("generated result", viewModel, savedState) {
                viewModel.state.value.route ==
                    ManagerRoute.ConversionResult(CONVERSION_TOKEN) &&
                    (viewModel.state.value.conversionState as?
                        ConversionUiState.ResultReady)
                        ?.review
                        ?.token == REVIEW_TOKEN
            }
        }

        suspend fun awaitSession(
            savedState: SavedStateHandle,
            viewModel: ManagerViewModel,
        ) {
            await("install session", viewModel, savedState) {
                savedState.get<Int>(ManagerViewModel.INSTALL_SESSION_ID_KEY) == SESSION_ID
            }
        }

        suspend fun await(
            label: String,
            viewModel: ManagerViewModel,
            savedState: SavedStateHandle,
            predicate: () -> Boolean,
        ) {
            val completed = withTimeoutOrNull(5_000) {
                while (!predicate()) delay(10)
                true
            }
            assertTrue(
                "$label timeout; route=${viewModel.state.value.route}; " +
                    "message=${viewModel.state.value.message}; " +
                    "review=${savedState.get<String>(ManagerViewModel.REVIEW_TOKEN_KEY)}; " +
                    "session=${savedState.get<Int>(
                        ManagerViewModel.INSTALL_SESSION_ID_KEY,
                    )}; normal=${store.loadNormalHandoff()}; " +
                    "final=${store.loadFinalReceipt()}; " +
                    "generatedHandoff=${savedState.get<String>(
                        ManagerViewModel.GENERATED_TERMINAL_HANDOFF_KEY,
                    )}",
                completed == true,
            )
        }

        fun installedWrapper() = InstalledWrapper(
            packageName = PACKAGE,
            extensionId = EXTENSION_ID,
            name = "Example",
            versionCode = 2,
            signerSha256 = "a".repeat(64),
        )

        fun retainedReviewExists(): Boolean = File(root, REVIEW_TOKEN).exists()

        fun close() {
            scope.cancel()
            root.deleteRecursively()
        }
    }

    private class TestPackageInstallGateway : PackageInstallGateway {
        private val owned = linkedSetOf<Int>()
        override fun canRequestPackageInstalls(): Boolean = true
        override fun unknownSourcesSettingsIntent(): Intent =
            error("설정 화면을 열지 않습니다.")

        override suspend fun createSession(packageName: String): Int {
            owned += SESSION_ID
            return SESSION_ID
        }

        override suspend fun writeSession(sessionId: Int, apk: File) = Unit
        override fun commit(sessionId: Int) = Unit
        override fun abandon(sessionId: Int) {
            owned -= sessionId
        }

        override fun ownedSessions(): List<Int> = owned.toList()
        override fun sessionSnapshot(sessionId: Int): InstallSessionSnapshot? =
            if (sessionId in owned) {
                InstallSessionSnapshot(
                    sessionId = sessionId,
                    packageName = PACKAGE,
                    ownedByCaller = true,
                )
            } else {
                null
            }
    }

    private class MutableInstalledWrappers : InstalledWrapperRepository {
        var installed: InstalledWrapper? = null
        override suspend fun findAll(): List<InstalledWrapper> = listOfNotNull(installed)
        override suspend fun find(packageName: String): InstalledWrapper? =
            installed?.takeIf { it.packageName == packageName }
    }

    private class TestCatalog : ManagerCatalog {
        val removedPackages = mutableListOf<String>()
        private var record: CatalogRecord? = null
        override fun observe(): Flow<List<CatalogRecord>> =
            flowOf(listOfNotNull(record))

        override suspend fun replaceVerified(record: CatalogRecord) {
            this.record = record
        }

        override suspend fun ensureDiscoveredIdentity(
            discovered: CatalogRecord,
        ): CatalogRecord = discovered

        override suspend fun persistIconIfIdentity(
            identity: CatalogIdentity,
            iconPng: ByteArray,
        ): CatalogRecord? = null

        override suspend fun remove(packageName: String) {
            removedPackages += packageName
            record = null
        }

        override suspend fun removeAll(packageNames: Set<String>) = Unit
        override suspend fun get(packageName: String): CatalogRecord? = record
    }

    private class TestConversionCoordinator : ConversionCoordinatorPort {
        val discardedTokens = mutableListOf<String>()
        private lateinit var review: ReviewBundle
        private lateinit var generated: GeneratedBundle

        fun configure(review: ReviewBundle, generated: GeneratedBundle) {
            this.review = review
            this.generated = generated
        }

        override suspend fun begin(
            request: OnDeviceInputRequest,
        ): ConversionBeginResult =
            ConversionBeginResult.Failure(ConversionFailureCode.INPUT_INVALID)

        override suspend fun build(
            token: String,
            approvedAdapterIds: Set<String>,
            partialConfirmed: Boolean,
            identitySelection: InstallationIdentitySelection?,
        ): ConversionBuildResult =
            ConversionBuildResult.Failure(ConversionFailureCode.INPUT_INVALID)

        override suspend fun restore(importReview: Boolean): RestoredConversion =
            if (importReview) {
                RestoredConversion.BundleReady(review, generated)
            } else {
                RestoredConversion.GeneratedPending(generated)
            }

        override suspend fun discard(token: String): Boolean {
            discardedTokens += token
            return true
        }

        override fun copyGenerated(token: String, output: OutputStream): String? = null
    }

    private companion object {
        const val REVIEW_TOKEN = "11111111-1111-1111-1111-111111111111"
        const val SECOND_TOKEN = "22222222-2222-2222-2222-222222222222"
        const val CONVERSION_TOKEN = "33333333-3333-3333-3333-333333333333"
        const val SESSION_ID = 41
        val EXTENSION_ID = "a".repeat(32)
        val PACKAGE = "dev.sixbridge.ext.$EXTENSION_ID"

        fun importedBundle(root: File): ImportedBundle {
            val directory = File(root, REVIEW_TOKEN).apply { mkdirs() }
            val wrapper = File(directory, "wrapper.apk").apply {
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val wrapperHash = sha256(wrapper.readBytes())
            val descriptor = ContractExtensionDescriptor(
                schemaVersion = 1,
                extensionId = EXTENSION_ID,
                name = "Example",
                version = "1.0",
                sourceKind = "CRX3",
                crxSha256 = "c".repeat(64),
                wrapperSha256 = wrapperHash,
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
                adapterDecisions = emptyList(),
                originalExtensionId = EXTENSION_ID,
                convertedExtensionId = EXTENSION_ID,
                sourceSha256 = "d".repeat(64),
                browserProfileSha256 = "e".repeat(64),
            )
            val verified = VerifiedWrapperApk(
                packageName = PACKAGE,
                versionCode = 2,
                signerSha256 = descriptor.signerSha256,
                wrapperSha256 = wrapperHash,
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
            return ImportedBundle(
                token = REVIEW_TOKEN,
                descriptor = descriptor,
                report = report,
                verifiedApk = verified,
                wrapperFile = wrapper,
                wrapperSha256 = wrapperHash,
            )
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
