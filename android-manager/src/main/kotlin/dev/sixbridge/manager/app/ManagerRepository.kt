package dev.sixbridge.manager.app

import android.content.Intent
import android.net.Uri
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractIssue
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.CatalogReconciler
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.cleanup.StartupCleanup
import dev.sixbridge.manager.importer.BundleImportService
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportResult
import dev.sixbridge.manager.importer.ImportedBundle
import dev.sixbridge.manager.install.InstallCoordinator
import dev.sixbridge.manager.install.InstallState
import dev.sixbridge.manager.install.PendingInstallStore
import dev.sixbridge.manager.navigation.SamsungBrowserAvailability
import dev.sixbridge.manager.navigation.SamsungBrowserNavigator
import dev.sixbridge.manager.ui.LaunchDestination
import dev.sixbridge.manager.uninstall.UninstallCoordinator
import dev.sixbridge.manager.uninstall.UninstallOutcome
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class ReviewBundle(
    val token: String,
    val packageName: String,
    val extensionId: String,
    val name: String,
    val version: String,
    val status: ContractCompatibilityStatus,
    val issues: List<ContractIssue>,
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
)

sealed interface RepositoryNavigationResult {
    data class Launch(val effect: RepositoryLaunch) : RepositoryNavigationResult
    data object SamsungBrowserMissing : RepositoryNavigationResult
    data object AddOnsIntentUnavailable : RepositoryNavigationResult
}

interface ManagerRepository {
    val catalog: Flow<List<CatalogRecord>>
    val installState: StateFlow<InstallState>

    suspend fun initialize(reviewToken: String?, installSessionId: Int?): ManagerRestoreResult
    suspend fun import(uri: Uri): ManagerImportResult
    suspend fun importRequest(request: Any): ManagerImportResult = import(request as Uri)
    suspend fun install(review: ReviewBundle, partialConfirmed: Boolean)
    fun discardReview(token: String)
    fun unknownSourcesSettings(): RepositoryLaunch
    suspend fun resumeAfterUnknownSourcesSettings()
    fun uninstall(packageName: String): RepositoryLaunch
    suspend fun reconcileUninstall(packageName: String): UninstallOutcome
    fun samsungAddOns(): RepositoryNavigationResult
}

class DefaultManagerRepository(
    private val importer: BundleImportService,
    private val catalogStore: ManagerCatalog,
    private val installedWrappers: InstalledWrapperRepository,
    private val reconciler: CatalogReconciler,
    private val startupCleanup: StartupCleanup,
    private val installCoordinator: InstallCoordinator,
    private val pendingStore: PendingInstallStore,
    private val uninstallCoordinator: UninstallCoordinator,
    private val samsungBrowserNavigator: SamsungBrowserNavigator,
) : ManagerRepository {
    private val imported = ConcurrentHashMap<String, ImportedBundle>()

    override val catalog: Flow<List<CatalogRecord>> = catalogStore.observe()
    override val installState: StateFlow<InstallState> = installCoordinator.state

    override suspend fun initialize(
        reviewToken: String?,
        installSessionId: Int?,
    ): ManagerRestoreResult {
        startupCleanup.run()
        installCoordinator.restore()
        reconciler.reconcile(catalogStore, installedWrappers)
        val pending = pendingStore.load()
        val restoredSession = installSessionId?.takeIf { pending?.sessionId == it }
        val bundle = reviewToken
            ?.let(imported::get)
            ?.takeIf { it.wrapperFile.isFile && it.token == reviewToken && pending == null }
        return ManagerRestoreResult(bundle?.toReview(), restoredSession)
    }

    override suspend fun import(uri: Uri): ManagerImportResult = importRequest(uri)

    override suspend fun importRequest(request: Any): ManagerImportResult {
        return when (val result = importer.importRequest(request)) {
            is ImportResult.Failure -> ManagerImportResult.Failure(result.reason)
            is ImportResult.Success -> {
                imported[result.value.token] = result.value
                ManagerImportResult.Success(result.value.toReview())
            }
        }
    }

    override suspend fun install(review: ReviewBundle, partialConfirmed: Boolean) {
        val bundle = imported[review.token] ?: return
        installCoordinator.request(bundle, partialConfirmed)
    }

    override fun discardReview(token: String) {
        imported.remove(token)
        importer.discard(token)
    }

    override fun unknownSourcesSettings(): RepositoryLaunch = RepositoryLaunch(
        LaunchDestination.UNKNOWN_SOURCES_SETTINGS,
        installCoordinator::unknownSourcesSettingsIntent,
    )

    override suspend fun resumeAfterUnknownSourcesSettings() {
        installCoordinator.resumeAfterUnknownSourcesSettings()
    }

    override fun uninstall(packageName: String): RepositoryLaunch = RepositoryLaunch(
        LaunchDestination.UNINSTALL,
    ) {
        uninstallCoordinator.intent(packageName)
    }

    override suspend fun reconcileUninstall(packageName: String): UninstallOutcome =
        uninstallCoordinator.reconcileAfterReturn(packageName)

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

    private fun ImportedBundle.toReview() = ReviewBundle(
        token = token,
        packageName = verifiedApk.packageName,
        extensionId = descriptor.extensionId,
        name = descriptor.name,
        version = descriptor.version,
        status = report.status,
        issues = report.issues,
    )
}
