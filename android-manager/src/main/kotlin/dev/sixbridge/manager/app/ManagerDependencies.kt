package dev.sixbridge.manager.app

import android.app.Application
import androidx.room.Room
import dev.sixbridge.manager.catalog.CatalogReconciler
import dev.sixbridge.manager.catalog.ManagerDatabase
import dev.sixbridge.manager.catalog.RoomManagerCatalog
import dev.sixbridge.manager.cleanup.StartupCleanup
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

class ManagerDependencies private constructor(
    val repository: ManagerRepository,
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
            val installCoordinator = InstallCoordinator(
                policy = InstallPolicy(),
                gateway = installGateway,
                pendingStore = pendingStore,
                installedWrappers = installedWrappers,
                catalog = catalog,
            )
            val importer = BundleImportService(
                source = ContentResolverBundleSource(application.contentResolver),
                verifier = AndroidWrapperVerifier(AndroidArchiveInspector(application.packageManager)),
                cacheDirectory = application.cacheDir,
            )
            val uninstall = DefaultUninstallCoordinator(installedWrappers, catalog)
            return ManagerDependencies(
                DefaultManagerRepository(
                    importer = importer,
                    catalogStore = catalog,
                    installedWrappers = installedWrappers,
                    reconciler = CatalogReconciler(),
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
                ),
            )
        }
    }
}
