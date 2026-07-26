package dev.sixbridge.manager.app

import android.app.Application
import androidx.room.Room
import dev.sixbridge.manager.catalog.CatalogReconciler
import dev.sixbridge.manager.catalog.ManagerDatabase
import dev.sixbridge.manager.catalog.RoomManagerCatalog
import dev.sixbridge.manager.cleanup.StartupCleanup
import dev.sixbridge.manager.icon.CatalogIconEnricher
import dev.sixbridge.manager.icon.ContentProviderInstalledExtensionIconSource
import dev.sixbridge.manager.importer.BundleImportService
import dev.sixbridge.manager.importer.ContentResolverBundleSource
import dev.sixbridge.manager.install.AndroidPackageInstallGateway
import dev.sixbridge.manager.install.InstallCoordinator
import dev.sixbridge.manager.install.InstallPolicy
import dev.sixbridge.manager.install.SharedPreferencesPendingInstallStore
import dev.sixbridge.manager.navigation.AndroidSamsungBrowserNavigator
import dev.sixbridge.manager.uninstall.DefaultUninstallCoordinator
import dev.sixbridge.manager.wrapper.AndroidArchiveInspector
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.PackageManagerInstalledWrapperRepository
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class ManagerDependencies private constructor(
    val repository: ManagerRepository,
    private val applicationScope: CoroutineScope,
) {
    companion object {
        @Volatile
        private var instance: ManagerDependencies? = null

        fun create(application: Application): ManagerDependencies =
            instance ?: synchronized(this) {
                instance ?: build(application).also { instance = it }
            }

        private fun build(application: Application): ManagerDependencies {
            val database = Room.databaseBuilder(
                application,
                ManagerDatabase::class.java,
                "six-bridge-manager.db",
            ).build()
            val catalog = RoomManagerCatalog(database.catalogEntryDao())
            val installedWrappers = PackageManagerInstalledWrapperRepository(application.packageManager)
            val pendingStore = SharedPreferencesPendingInstallStore(application)
            val installGateway = AndroidPackageInstallGateway(application)
            val verifier = AndroidWrapperVerifier(
                AndroidArchiveInspector(application.packageManager),
            )
            val installCoordinator = InstallCoordinator(
                policy = InstallPolicy(),
                gateway = installGateway,
                pendingStore = pendingStore,
                installedWrappers = installedWrappers,
                catalog = catalog,
            )
            val importer = BundleImportService(
                source = ContentResolverBundleSource(application.contentResolver),
                verifier = verifier,
                cacheDirectory = application.cacheDir,
            )
            val uninstall = DefaultUninstallCoordinator(installedWrappers, catalog)
            val iconSource = ContentProviderInstalledExtensionIconSource(
                application.contentResolver,
                File(application.cacheDir, "extension-icons"),
            )
            val iconEnricher = CatalogIconEnricher(catalog, iconSource)
            val catalogPublisher = CatalogPublisher(
                catalog = catalog,
                installedWrappers = installedWrappers,
                reconciler = CatalogReconciler(),
                iconEnricher = iconEnricher,
            )
            val repository = DefaultManagerRepository(
                importer = importer,
                retainedImports = RetainedImportStore(
                    File(application.cacheDir, "imports"),
                    verifier,
                ),
                startupCleanup = StartupCleanup(
                    importRoot = File(application.cacheDir, "imports"),
                    installGateway = installGateway,
                    pendingStore = pendingStore,
                    clock = Clock.systemUTC(),
                ),
                installCoordinator = installCoordinator,
                pendingStore = pendingStore,
                uninstallCoordinator = uninstall,
                samsungBrowserNavigator =
                    AndroidSamsungBrowserNavigator(application.packageManager),
                catalogPublisher = catalogPublisher,
            )
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            repository.start(applicationScope, catalog)
            return ManagerDependencies(repository, applicationScope)
        }
    }
}

class SixBridgeManagerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ManagerDependencies.create(this)
    }
}
