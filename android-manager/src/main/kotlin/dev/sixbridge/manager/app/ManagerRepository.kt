package dev.sixbridge.manager.app

import android.content.Intent
import android.net.Uri
import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.bundle.ContractIssue
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.CatalogReconciler
import dev.sixbridge.manager.catalog.CatalogIdentity
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.catalog.identity
import dev.sixbridge.manager.cleanup.StartupCleanup
import dev.sixbridge.manager.icon.CatalogIconEnricher
import dev.sixbridge.manager.icon.IconPersistenceResult
import dev.sixbridge.manager.importer.BundleImportService
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.importer.ImportResult
import dev.sixbridge.manager.importer.ImportedBundle
import dev.sixbridge.manager.install.InstallCoordinator
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.install.PackageInstallCallback
import dev.sixbridge.manager.install.PackageInstallEvents
import dev.sixbridge.manager.install.PackageInstallResult
import dev.sixbridge.manager.install.PendingInstall
import dev.sixbridge.manager.install.PendingInstallStore
import dev.sixbridge.manager.navigation.SamsungBrowserAvailability
import dev.sixbridge.manager.navigation.SamsungBrowserNavigator
import dev.sixbridge.manager.ui.LaunchDestination
import dev.sixbridge.manager.uninstall.UninstallCoordinator
import dev.sixbridge.manager.uninstall.UninstallOutcome
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import dev.sixbridge.manager.wrapper.WrapperVerificationResult
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

data class ReviewBundle(
    val token: String,
    val packageName: String,
    val extensionId: String,
    val name: String,
    val version: String,
    val status: ContractCompatibilityStatus,
    val issues: List<ContractIssue>,
    val declaredPermissions: List<String> = emptyList(),
    val adapterDecisions: List<ContractAdapterDecision> = emptyList(),
    val signerSha256: String = "",
    val wrapperVersionCode: Int = 0,
)

sealed interface ManagerImportResult {
    data class Success(val review: ReviewBundle) : ManagerImportResult
    data class Failure(val reason: ImportFailure) : ManagerImportResult
}

data class ManagerRestoreResult(
    val review: ReviewBundle? = null,
    val installSessionId: Int? = null,
)

class RepositoryLaunch(
    val destination: LaunchDestination,
    val intentFactory: () -> Intent,
    val installSessionId: Int? = null,
)

sealed interface RepositoryNavigationResult {
    data class Launch(val effect: RepositoryLaunch) : RepositoryNavigationResult
    data object SamsungBrowserMissing : RepositoryNavigationResult
    data object AddOnsIntentUnavailable : RepositoryNavigationResult
}

interface ManagerRepository {
    val catalog: Flow<List<CatalogRecord>>
    val installState: StateFlow<InstallState>
    val launches: Flow<RepositoryLaunch>

    suspend fun initialize(reviewToken: String?, installSessionId: Int?): ManagerRestoreResult
    suspend fun import(uri: Uri): ManagerImportResult
    suspend fun install(review: ReviewBundle, partialConfirmed: Boolean): InstallState
    suspend fun cancelInstall(): Boolean
    fun discardReview(token: String): Boolean
    fun unknownSourcesSettings(): RepositoryLaunch
    suspend fun resumeAfterUnknownSourcesSettings()
    fun uninstall(packageName: String): RepositoryLaunch
    suspend fun reconcileUninstall(packageName: String): UninstallOutcome
    fun samsungAddOns(): RepositoryNavigationResult
    suspend fun reconcileCatalog()
}

internal class DefaultManagerRepository(
    private val importer: BundleImportService,
    private val retainedImports: RetainedImportStore,
    private val startupCleanup: StartupCleanup,
    private val installCoordinator: InstallCoordinator,
    private val pendingStore: PendingInstallStore,
    private val uninstallCoordinator: UninstallCoordinator,
    private val samsungBrowserNavigator: SamsungBrowserNavigator,
    private val catalogPublisher: CatalogPublisher,
    private val installEvents: Flow<PackageInstallCallback> = PackageInstallEvents.events,
    private val afterInstallRequest: suspend (InstallState) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ManagerRepository {
    private val started = AtomicBoolean()
    private val launchChannel = Channel<RepositoryLaunch>(Channel.UNLIMITED)
    private val callbackRouter = InstallCallbackRouter(
        onResult = installCoordinator::onPackageInstallerResult,
        onRefresh = catalogPublisher::reconcile,
    )
    private val startup = RepositoryStartup(
        retainedImports = retainedImports,
        startupCleanup = startupCleanup,
        installCoordinator = installCoordinator,
        pendingStore = pendingStore,
        catalogPublisher = catalogPublisher,
        ioDispatcher = ioDispatcher,
    )

    override val catalog: Flow<List<CatalogRecord>> = catalogPublisher.state
    override val installState: StateFlow<InstallState> = installCoordinator.state
    override val launches: Flow<RepositoryLaunch> = launchChannel.receiveAsFlow()

    fun start(applicationScope: CoroutineScope, catalogStore: ManagerCatalog) {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            catalogStore.observe().collect { records ->
                try {
                    catalogPublisher.publish(records)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // A later Room emission or foreground reconciliation retries the refresh.
                }
            }
        }
        startup.start(applicationScope, installEvents, callbackRouter)
    }

    override suspend fun initialize(
        reviewToken: String?,
        installSessionId: Int?,
    ): ManagerRestoreResult {
        val startupSnapshot = startup.awaitReady()
        val currentSnapshot = installCoordinator.currentRecoverySnapshot()
        val pending = currentSnapshot.pending
        val retained = reviewToken?.let { token ->
            when {
                pending?.importToken == token && pending == startupSnapshot.pending ->
                    currentSnapshot.activeBundle
                        ?.takeIf {
                            it.token == token &&
                                it.verifiedApk.packageName == pending.packageName
                        }
                        ?: startupSnapshot.restoredBundle
                            ?.takeIf {
                                it.token == token &&
                                    it.verifiedApk.packageName == pending.packageName
                            }
                pending?.importToken == token ->
                    withContext(ioDispatcher) { retainedImports.load(token) }
                        ?.takeIf { it.verifiedApk.packageName == pending.packageName }
                pending == null -> currentSnapshot.activeBundle
                    ?.takeIf { it.token == token }
                    ?: if (startupSnapshot.pending?.importToken != token) {
                        withContext(ioDispatcher) { retainedImports.load(token) }
                    } else {
                        null
                    }
                else -> null
            }
        }
        val restoredSession = installSessionId?.takeIf { pending?.sessionId == it }
        val review = retained
            ?.takeIf {
                it.token == reviewToken &&
                    (pending == null ||
                        pending.importToken == reviewToken &&
                        pending.packageName == it.verifiedApk.packageName)
            }
            ?.toReview()
        return ManagerRestoreResult(review, restoredSession)
    }

    override suspend fun import(uri: Uri): ManagerImportResult {
        return when (val result = importer.import(uri)) {
            is ImportResult.Failure -> ManagerImportResult.Failure(result.reason)
            is ImportResult.Success -> {
                val saved = try {
                    withContext(Dispatchers.IO) { retainedImports.save(result.value) }
                } catch (error: CancellationException) {
                    importer.discard(result.value.token)
                    throw error
                }
                if (!saved) {
                    importer.discard(result.value.token)
                    ManagerImportResult.Failure(ImportFailure(ImportFailureCode.CACHE_IO))
                } else {
                    ManagerImportResult.Success(result.value.toReview())
                }
            }
        }
    }

    override suspend fun install(
        review: ReviewBundle,
        partialConfirmed: Boolean,
    ): InstallState {
        val bundle = withContext(ioDispatcher) { retainedImports.load(review.token) }
            ?: return InstallState.Idle
        val requestState = installCoordinator.request(bundle, partialConfirmed)
        afterInstallRequest(requestState)
        return requestState
    }

    override suspend fun cancelInstall(): Boolean {
        return installCoordinator.cancel()
    }

    override fun discardReview(token: String): Boolean =
        runCatching { pendingStore.discardImport(token) }.isSuccess

    override fun unknownSourcesSettings(): RepositoryLaunch = RepositoryLaunch(
        LaunchDestination.UNKNOWN_SOURCES_SETTINGS,
        installCoordinator::unknownSourcesSettingsIntent,
    )

    override suspend fun resumeAfterUnknownSourcesSettings() {
        installCoordinator.resumeAfterUnknownSourcesSettings()
    }

    override fun uninstall(packageName: String): RepositoryLaunch = RepositoryLaunch(
        destination = LaunchDestination.UNINSTALL,
        intentFactory = { uninstallCoordinator.intent(packageName) },
    )

    override suspend fun reconcileUninstall(packageName: String): UninstallOutcome {
        val outcome = uninstallCoordinator.reconcileAfterReturn(packageName)
        if (outcome != UninstallOutcome.Removed) return outcome
        return try {
            catalogPublisher.remove(packageName)
            UninstallOutcome.Removed
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            UninstallOutcome.CancelledOrStillInstalled
        }
    }

    override fun samsungAddOns(): RepositoryNavigationResult =
        when (samsungBrowserNavigator.availability()) {
            SamsungBrowserAvailability.Available -> RepositoryNavigationResult.Launch(
                RepositoryLaunch(
                    LaunchDestination.SAMSUNG_ADD_ONS,
                    samsungBrowserNavigator::addOnsIntent,
                ),
            )
            SamsungBrowserAvailability.SamsungBrowserMissing ->
                RepositoryNavigationResult.SamsungBrowserMissing
            SamsungBrowserAvailability.AddOnsIntentUnavailable ->
                RepositoryNavigationResult.AddOnsIntentUnavailable
        }

    override suspend fun reconcileCatalog() {
        catalogPublisher.reconcile()
    }
}

internal class RepositoryStartup(
    private val retainedImports: RetainedImportStore,
    private val startupCleanup: StartupCleanup,
    private val installCoordinator: InstallCoordinator,
    private val pendingStore: PendingInstallStore,
    private val catalogPublisher: CatalogPublisher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelayMillis: Long = 250,
) {
    private val mutex = Mutex()
    private val ready = CompletableDeferred<StartupSnapshot>()
    private val started = AtomicBoolean()

    fun start(
        applicationScope: CoroutineScope,
        installEvents: Flow<PackageInstallCallback>,
        callbackRouter: InstallCallbackRouter,
    ) {
        if (!started.compareAndSet(false, true)) return
        applicationScope.launch {
            awaitReady()
            collectPackageInstallEvents(installEvents, callbackRouter)
        }
    }

    suspend fun awaitReady(): StartupSnapshot {
        while (!ready.isCompleted) {
            try {
                ensureReady()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                delay(retryDelayMillis)
            }
        }
        return ready.await()
    }

    suspend fun ensureReady(): StartupSnapshot {
        if (!ready.isCompleted) {
            mutex.withLock {
                if (!ready.isCompleted) {
                    startupCleanup.run()
                    val pending = pendingStore.load()
                    val retained = pending?.importToken?.let { token ->
                        withContext(ioDispatcher) { retainedImports.load(token) }
                    }
                    installCoordinator.restore(retained)
                    val restoredPending = pendingStore.load()
                    val restoredBundle = retained?.takeIf { bundle ->
                        restoredPending?.importToken == bundle.token
                    }
                    runBestEffortStartupStep { catalogPublisher.reconcile() }
                    ready.complete(StartupSnapshot(restoredPending, restoredBundle))
                }
            }
        }
        return ready.await()
    }

    private suspend fun runBestEffortStartupStep(step: suspend () -> Unit) {
        try {
            step()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // A foreground initialization or later callback can retry durable work.
        }
    }
}

internal data class StartupSnapshot(
    val pending: PendingInstall?,
    val restoredBundle: ImportedBundle?,
)

internal suspend fun collectPackageInstallEvents(
    events: Flow<PackageInstallCallback>,
    router: InstallCallbackRouter,
) {
    events.collect { callback ->
        router.handle(callback.toInstallEvent())
    }
}

private fun PackageInstallCallback.toInstallEvent() = InstallEvent(
    result = result,
)

internal data class InstallEvent(
    val result: PackageInstallResult,
)

internal class InstallCallbackRouter(
    private val onResult: suspend (PackageInstallResult) -> Boolean,
    private val onRefresh: suspend () -> Unit,
) {
    suspend fun handle(event: InstallEvent) {
        try {
            onResult(event.result)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Keep the application callback collector alive for later events.
        }
        try {
            onRefresh()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Callback processing is durable; foreground/Room changes retry catalog refresh.
        }
    }
}

internal class CatalogPublisher(
    private val catalog: ManagerCatalog,
    private val installedWrappers: InstalledWrapperRepository,
    private val reconciler: CatalogReconciler,
    private val iconEnricher: CatalogIconEnricher,
    private val scope: CoroutineScope,
    private val iconLoadTimeoutMillis: Long = 5_000,
) {
    private val mutex = Mutex()
    private val iconJobs = mutableMapOf<CatalogIdentity, Job>()
    private val mutableState = MutableStateFlow<List<CatalogRecord>>(emptyList())
    val state: StateFlow<List<CatalogRecord>> = mutableState.asStateFlow()

    suspend fun reconcile() {
        val publication = mutex.withLock {
            publishFallbackLocked(reconcileLocked(catalog.observe().first()))
        }
        publication.cancelledJobs.forEach(Job::cancel)
        scheduleIcons(publication.iconCandidates)
    }

    suspend fun publish(records: List<CatalogRecord>) {
        val publication = mutex.withLock {
            publishFallbackLocked(reconcileLocked(records))
        }
        publication.cancelledJobs.forEach(Job::cancel)
        scheduleIcons(publication.iconCandidates)
    }

    suspend fun remove(packageName: String) {
        var cancelledJobs = emptyList<Job>()
        try {
            mutex.withLock {
                cancelledJobs = iconJobs
                    .filterKeys { it.packageName == packageName }
                    .values
                    .toList()
                cancelledJobs.forEach(Job::cancel)
                iconJobs.keys.removeAll { it.packageName == packageName }
                iconEnricher.retainOnly(
                    mutableState.value
                        .filterNot { it.packageName == packageName }
                        .mapTo(mutableSetOf(), CatalogRecord::identity),
                )
                catalog.remove(packageName)
                mutableState.value = mutableState.value.filterNot {
                    it.packageName == packageName
                }
            }
        } finally {
            withContext(NonCancellable) {
                cancelledJobs.forEach { job -> job.join() }
            }
        }
    }

    private suspend fun reconcileLocked(
        catalogRecords: List<CatalogRecord>,
    ): CatalogDisplayRecords {
        val installed = installedWrappers.findAll()
        val reconciliation = reconciler.reconcile(catalogRecords, installed)
        catalog.removeAll(reconciliation.stalePackageNames.toSet())
        val ensured = reconciliation.records.map { discovered ->
            catalog.ensureDiscoveredIdentity(discovered)
        }
        val identities = ensured.mapTo(mutableSetOf(), CatalogRecord::identity)
        iconEnricher.retainOnly(identities)
        val currentByPackage = mutableState.value.associateBy(CatalogRecord::packageName)
        val displayRecords = ensured.map { candidate ->
            val cached = if (candidate.iconPng == null) {
                iconEnricher.mergeCached(candidate)
            } else {
                candidate
            }
            if (cached.iconPng != null) {
                cached
            } else {
                currentByPackage[candidate.packageName]
                    ?.takeIf { current -> current.identity() == candidate.identity() }
                    ?.iconPng
                    ?.let { currentIcon -> candidate.copy(iconPng = currentIcon) }
                    ?: candidate
            }
        }
        return CatalogDisplayRecords(
            iconCandidates = ensured,
            displayRecords = displayRecords,
        )
    }

    private fun publishFallbackLocked(records: CatalogDisplayRecords): IconPublication {
        mutableState.value = records.displayRecords
        val requiredIdentities = records.iconCandidates
            .asSequence()
            .filter { it.iconPng == null }
            .map(CatalogRecord::identity)
            .toSet()
        val cancelled = iconJobs
            .filterKeys { it !in requiredIdentities }
            .toList()
        cancelled.forEach { (identity, _) -> iconJobs.remove(identity) }
        return IconPublication(records.iconCandidates, cancelled.map { it.second })
    }

    private suspend fun scheduleIcons(records: List<CatalogRecord>) {
        records.asSequence()
            .filter { it.iconPng == null }
            .forEach { record ->
                val identity = record.identity()
                var job: Job? = null
                mutex.withLock {
                    val current = mutableState.value
                        .singleOrNull { it.packageName == identity.packageName }
                    if (current?.identity() == identity && iconJobs[identity] == null) {
                        job = scope.launch(start = CoroutineStart.LAZY) {
                            loadAndPublishIcon(record)
                        }
                        iconJobs[identity] = requireNotNull(job)
                    }
                }
                val scheduled = job ?: return@forEach
                if (!scheduled.start()) {
                    mutex.withLock {
                        if (iconJobs[identity] === scheduled) {
                            iconJobs.remove(identity)
                        }
                    }
                }
            }
    }

    private suspend fun loadAndPublishIcon(discovered: CatalogRecord) {
        val identity = discovered.identity()
        val job = currentCoroutineContext().job
        try {
            val iconPng = withTimeout(iconLoadTimeoutMillis) {
                iconEnricher.load(discovered)
            } ?: return
            val installed = try {
                installedWrappers.find(discovered.packageName)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                return
            }
            if (installed == null ||
                installed.extensionId != identity.extensionId ||
                installed.versionCode != identity.installedVersionCode ||
                installed.signerSha256 != identity.signerSha256
            ) {
                return
            }
            mutex.withLock {
                if (iconJobs[identity] !== job) return@withLock
                val index = mutableState.value.indexOfFirst {
                    it.packageName == identity.packageName
                }
                if (index < 0) return@withLock
                val current = mutableState.value[index]
                if (current.identity() != identity) {
                    return@withLock
                }
                val enriched = when (val persisted = iconEnricher.persist(current, iconPng)) {
                    is IconPersistenceResult.Stored -> persisted.record
                    IconPersistenceResult.WriteFailed -> current.copy(iconPng = iconPng)
                    IconPersistenceResult.IdentityMismatch -> return@withLock
                }
                if (enriched.identity() == identity) {
                    mutableState.value = mutableState.value.toMutableList().also { records ->
                        records[index] = enriched
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Icon discovery is best-effort and never blocks catalog publication.
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (iconJobs[identity] === job) {
                        iconJobs.remove(identity)
                    }
                }
            }
        }
    }

    private data class IconPublication(
        val iconCandidates: List<CatalogRecord>,
        val cancelledJobs: List<Job>,
    )

    private data class CatalogDisplayRecords(
        val iconCandidates: List<CatalogRecord>,
        val displayRecords: List<CatalogRecord>,
    )
}

internal data class RetainedImportIo(
    val openSidecar: (File) -> InputStream = File::inputStream,
    val wrapperSha256: (File) -> String = ::retainedFileSha256,
    val readAttributes: (Path) -> BasicFileAttributes = { path ->
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
    },
    val toRealPath: (Path) -> Path = { path ->
        path.toRealPath(LinkOption.NOFOLLOW_LINKS)
    },
    val notExists: (Path) -> Boolean = { path ->
        Files.notExists(path, LinkOption.NOFOLLOW_LINKS)
    },
)

internal class RetainedImportStore(
    private val importRoot: File,
    private val io: RetainedImportIo = RetainedImportIo(),
    private val verify: (
        File,
        ContractExtensionDescriptor,
        ContractCompatibilityReport,
    ) -> WrapperVerificationResult,
) {
    constructor(
        importRoot: File,
        verify: (
            File,
            ContractExtensionDescriptor,
            ContractCompatibilityReport,
        ) -> WrapperVerificationResult,
    ) : this(importRoot, RetainedImportIo(), verify)

    constructor(importRoot: File, verifier: AndroidWrapperVerifier) : this(
        importRoot = importRoot,
        verify = verifier::verify,
    )

    fun save(bundle: ImportedBundle): Boolean {
        val directory = tokenDirectory(bundle.token) ?: return false
        val wrapper = wrapperFile(directory) ?: return false
        val importedWrapper = runCatching { bundle.wrapperFile.canonicalFile }.getOrNull()
            ?: return false
        val currentSha256 = runCatching { io.wrapperSha256(wrapper) }.getOrNull() ?: return false
        if (wrapper != importedWrapper ||
            currentSha256 != bundle.wrapperSha256 ||
            bundle.wrapperSha256 != bundle.descriptor.wrapperSha256
        ) {
            return false
        }
        val extension = BundleJson.encodeExtension(bundle.descriptor).toByteArray(Charsets.UTF_8)
        val compatibility = BundleJson.encodeCompatibility(bundle.report).toByteArray(Charsets.UTF_8)
        if (extension.isEmpty() || extension.size > MAX_METADATA_BYTES ||
            compatibility.isEmpty() || compatibility.size > MAX_METADATA_BYTES
        ) {
            return false
        }
        val temporary = File(directory, "$SIDECAR_FILE.tmp")
        val sidecar = File(directory, SIDECAR_FILE)
        return try {
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(FORMAT_VERSION)
                output.writeInt(extension.size)
                output.write(extension)
                output.writeInt(compatibility.size)
                output.write(compatibility)
            }
            try {
                Files.move(
                    temporary.toPath(),
                    sidecar.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: Exception) {
                Files.move(
                    temporary.toPath(),
                    sidecar.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
            true
        } catch (error: CancellationException) {
            temporary.delete()
            throw error
        } catch (_: Exception) {
            temporary.delete()
            false
        }
    }

    fun load(token: String): ImportedBundle? {
        val directory = loadTokenDirectory(token) ?: return null
        val wrapper = loadDirectFile(directory, WRAPPER_FILE)?.file ?: return null
        val sidecar = loadDirectFile(directory, SIDECAR_FILE) ?: return null
        if (sidecar.attributes.size() !in MIN_SIDECAR_BYTES..MAX_SIDECAR_BYTES) {
            return null
        }
        val metadata = try {
            DataInputStream(io.openSidecar(sidecar.file).buffered()).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) return null
                val extension = input.readBoundedBytes()
                val compatibility = input.readBoundedBytes()
                if (input.read() != -1) return null
                extension.toString(Charsets.UTF_8) to compatibility.toString(Charsets.UTF_8)
            }
        } catch (_: EOFException) {
            return null
        } catch (_: NoSuchFileException) {
            return null
        } catch (error: FileNotFoundException) {
            if (confirmMissing(sidecar.file.toPath())) return null
            throw RetainedImportTransientException(error)
        } catch (_: IllegalArgumentException) {
            return null
        } catch (error: CancellationException) {
            throw error
        } catch (error: IOException) {
            throw RetainedImportTransientException(error)
        } catch (error: Exception) {
            throw RetainedImportTransientException(error)
        }
        val descriptor = runCatching { BundleJson.decodeExtension(metadata.first) }.getOrNull()
            ?: return null
        val report = runCatching { BundleJson.decodeCompatibility(metadata.second) }.getOrNull()
            ?: return null
        val currentSha256 = try {
            io.wrapperSha256(wrapper)
        } catch (_: NoSuchFileException) {
            return null
        } catch (error: FileNotFoundException) {
            if (confirmMissing(wrapper.toPath())) return null
            throw RetainedImportTransientException(error)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw RetainedImportTransientException(error)
        }
        if (report.schemaVersion != 3 ||
            report.convertedExtensionId != descriptor.extensionId ||
            report.extensionName != descriptor.name ||
            report.extensionVersion != descriptor.version ||
            currentSha256 != descriptor.wrapperSha256
        ) {
            return null
        }
        val verification = try {
            verify(wrapper, descriptor, report)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw RetainedImportTransientException(error)
        }
        val verified = when (verification) {
            is WrapperVerificationResult.Success -> verification.value
            is WrapperVerificationResult.Failure -> return null
        }
        if (verified.wrapperSha256 != descriptor.wrapperSha256 ||
            verified.packageName != descriptor.wrapperPackage ||
            verified.extensionId != descriptor.extensionId ||
            verified.signerSha256 != descriptor.signerSha256
        ) {
            return null
        }
        return ImportedBundle(
            token = token,
            descriptor = descriptor,
            report = report,
            verifiedApk = verified,
            wrapperFile = wrapper,
            wrapperSha256 = verified.wrapperSha256,
        )
    }

    private fun loadTokenDirectory(token: String): File? {
        if (!isCanonicalToken(token)) return null
        val rootPath = importRoot.toPath()
        val rootAttributes = readAttributes(rootPath) ?: return null
        if (!rootAttributes.isDirectory || rootAttributes.isSymbolicLink) return null
        val root = realPath(rootPath) ?: return null
        val directoryPath = root.resolve(token)
        val directoryAttributes = readAttributes(directoryPath) ?: return null
        if (!directoryAttributes.isDirectory || directoryAttributes.isSymbolicLink) return null
        val directory = realPath(directoryPath) ?: return null
        return directory.takeIf { it.parent == root }?.toFile()
    }

    private fun loadDirectFile(directory: File, name: String): ResolvedRetainedFile? {
        val directoryPath = directory.toPath()
        val path = directoryPath.resolve(name)
        val attributes = readAttributes(path) ?: return null
        if (!attributes.isRegularFile || attributes.isSymbolicLink) return null
        val realPath = realPath(path) ?: return null
        if (realPath.parent != directoryPath) return null
        return ResolvedRetainedFile(realPath.toFile(), attributes)
    }

    private fun readAttributes(path: Path): BasicFileAttributes? = try {
        io.readAttributes(path)
    } catch (_: NoSuchFileException) {
        null
    } catch (error: FileNotFoundException) {
        if (confirmMissing(path)) null else throw RetainedImportTransientException(error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw RetainedImportTransientException(error)
    }

    private fun realPath(path: Path): Path? = try {
        io.toRealPath(path)
    } catch (_: NoSuchFileException) {
        null
    } catch (error: FileNotFoundException) {
        if (confirmMissing(path)) null else throw RetainedImportTransientException(error)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw RetainedImportTransientException(error)
    }

    private fun confirmMissing(path: Path): Boolean = try {
        io.notExists(path)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw RetainedImportTransientException(error)
    }

    private fun tokenDirectory(token: String): File? {
        if (!isCanonicalToken(token) ||
            Files.isSymbolicLink(importRoot.toPath()) ||
            !importRoot.isDirectory
        ) {
            return null
        }
        val root = runCatching { importRoot.canonicalFile }.getOrNull() ?: return null
        val directory = File(root, token)
        if (Files.isSymbolicLink(directory.toPath()) ||
            !Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)
        ) {
            return null
        }
        return directory.canonicalFile.takeIf { it.parentFile == root }
    }

    private fun wrapperFile(directory: File): File? =
        File(directory, WRAPPER_FILE).takeIf { isDirectFile(directory, it) }?.canonicalFile

    private fun isDirectFile(directory: File, file: File): Boolean =
        !Files.isSymbolicLink(file.toPath()) &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) &&
            runCatching { file.canonicalFile.parentFile == directory }.getOrDefault(false)

    private fun DataInputStream.readBoundedBytes(): ByteArray {
        val size = readInt()
        require(size in 1..MAX_METADATA_BYTES)
        return ByteArray(size).also(::readFully)
    }

    private fun isCanonicalToken(token: String): Boolean =
        runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)

    private data class ResolvedRetainedFile(
        val file: File,
        val attributes: BasicFileAttributes,
    )

    private companion object {
        const val MAGIC = 0x53495852
        const val FORMAT_VERSION = 1
        const val MAX_METADATA_BYTES = 1024 * 1024
        const val MIN_SIDECAR_BYTES = 18L
        const val MAX_SIDECAR_BYTES = (MAX_METADATA_BYTES * 2L) + 16L
        const val SIDECAR_FILE = "review.bundle"
        const val WRAPPER_FILE = "wrapper.apk"
    }
}

internal class RetainedImportTransientException(cause: Throwable) : RuntimeException(cause)

private fun retainedFileSha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun ImportedBundle.toReview() = ReviewBundle(
    token = token,
    packageName = verifiedApk.packageName,
    extensionId = descriptor.extensionId,
    name = descriptor.name,
    version = descriptor.version,
    status = report.status,
    issues = report.issues,
    declaredPermissions = report.declaredPermissions,
    adapterDecisions = report.adapterDecisions,
    signerSha256 = descriptor.signerSha256,
    wrapperVersionCode = descriptor.wrapperVersionCode,
)
