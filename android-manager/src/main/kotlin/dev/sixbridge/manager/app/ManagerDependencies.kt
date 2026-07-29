package dev.sixbridge.manager.app

import android.app.Application
import androidx.room.Room
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.manager.catalog.CatalogReconciler
import dev.sixbridge.manager.catalog.ManagerDatabase
import dev.sixbridge.manager.catalog.RoomManagerCatalog
import dev.sixbridge.manager.cleanup.StartupCleanup
import dev.sixbridge.manager.conversion.ConversionBundleVerifier
import dev.sixbridge.manager.conversion.ConversionGeneratedImportPort
import dev.sixbridge.manager.conversion.ConversionIdentityLedger
import dev.sixbridge.manager.conversion.ConversionInstalledPort
import dev.sixbridge.manager.conversion.ConversionProfilePort
import dev.sixbridge.manager.conversion.ConversionWorkStore
import dev.sixbridge.manager.conversion.DefaultConversionBundlePort
import dev.sixbridge.manager.conversion.DefaultConversionIdentityPort
import dev.sixbridge.manager.conversion.DefaultConversionPackagePort
import dev.sixbridge.manager.conversion.DefaultConversionRuntime
import dev.sixbridge.manager.conversion.DefaultConversionWorkPort
import dev.sixbridge.manager.conversion.GeneratedBundleStore
import dev.sixbridge.manager.conversion.OnDeviceConversionCoordinator
import dev.sixbridge.manager.conversion.OnDeviceInputService
import dev.sixbridge.manager.conversion.SamsungProfileSnapshot
import dev.sixbridge.manager.icon.CatalogIconEnricher
import dev.sixbridge.manager.icon.ContentProviderInstalledExtensionIconSource
import dev.sixbridge.manager.importer.BundleImportService
import dev.sixbridge.manager.importer.ContentResolverBundleSource
import dev.sixbridge.manager.importer.GeneratedBundleStoreSource
import dev.sixbridge.manager.importer.ImportResult
import dev.sixbridge.manager.importer.RoutingBundleSource
import dev.sixbridge.manager.install.AndroidPackageInstallGateway
import dev.sixbridge.manager.install.InstallCoordinator
import dev.sixbridge.manager.install.InstallPolicy
import dev.sixbridge.manager.install.SharedPreferencesPendingInstallStore
import dev.sixbridge.manager.install.SharedPreferencesSystemInstallHandoffStore
import dev.sixbridge.manager.install.SystemInstallCoordinator
import dev.sixbridge.manager.navigation.AndroidSamsungBrowserNavigator
import dev.sixbridge.manager.packager.OnDeviceWrapperPackager
import dev.sixbridge.manager.profile.AndroidSamsungBrowserProfileProvider
import dev.sixbridge.manager.profile.SamsungBrowserProfileResult
import dev.sixbridge.manager.uninstall.DefaultUninstallCoordinator
import dev.sixbridge.manager.wrapper.AndroidArchiveInspector
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.PackageManagerInstalledWrapperRepository
import java.io.File
import java.time.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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
            val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val database = Room.databaseBuilder(
                application,
                ManagerDatabase::class.java,
                "six-bridge-manager.db",
            ).addMigrations(
                ManagerDatabase.MIGRATION_1_2,
                ManagerDatabase.MIGRATION_2_3,
                ManagerDatabase.MIGRATION_3_4,
            )
                .build()
            val catalog = RoomManagerCatalog(database.catalogEntryDao())
            val installedWrappers = PackageManagerInstalledWrapperRepository(application.packageManager)
            val pendingStore = SharedPreferencesPendingInstallStore(application)
            val systemInstallStore = SharedPreferencesSystemInstallHandoffStore(application)
            val installGateway = AndroidPackageInstallGateway(application)
            val verifier = AndroidWrapperVerifier(
                AndroidArchiveInspector(application.packageManager),
                File(application.cacheDir, "wrapper-verifier"),
            )
            val installCoordinator = InstallCoordinator(
                policy = InstallPolicy(),
                gateway = installGateway,
                pendingStore = pendingStore,
                installedWrappers = installedWrappers,
                catalog = catalog,
            )
            val generatedBundles = GeneratedBundleStore(application)
            val importer = BundleImportService(
                source = RoutingBundleSource(
                    ContentResolverBundleSource(application.contentResolver),
                    GeneratedBundleStoreSource(generatedBundles),
                ),
                verifier = verifier,
                cacheDirectory = application.cacheDir,
            )
            val importRoot = File(application.cacheDir, "imports")
            val retainedImports = RetainedImportStore(importRoot, verifier)
            val systemInstallCoordinator = SystemInstallCoordinator(
                policy = InstallPolicy(),
                handoffStore = systemInstallStore,
                installedWrappers = installedWrappers,
                pendingStore = pendingStore,
            )
            val identityLedger = ConversionIdentityLedger(database.conversionIdentityDao())
            val packager = OnDeviceWrapperPackager(application)
            val workStore = ConversionWorkStore(
                privateRoot = File(application.filesDir, "conversion-work"),
                dao = database.conversionJobDao(),
                bundleVerifier = ConversionBundleVerifier {
                    runCatching { SixBundleContract.read(it); true }.getOrDefault(false)
                },
                identityOwnerLifecycle = identityLedger.ownerLifecycle(packager),
            )
            val profileProvider = AndroidSamsungBrowserProfileProvider(application)
            val inputService = OnDeviceInputService(
                File(application.filesDir, "conversion-input"),
                application.contentResolver,
            )
            applicationScope.launch {
                inputService.reclaimOrphans()
            }
            val conversionCoordinator = OnDeviceConversionCoordinator(
                input = inputService,
                work = DefaultConversionWorkPort(workStore),
                profiles = ConversionProfilePort {
                    when (val profile = profileProvider.load()) {
                        is SamsungBrowserProfileResult.Supported ->
                            SamsungProfileSnapshot.Supported(profile.profile)
                        SamsungBrowserProfileResult.Missing -> SamsungProfileSnapshot.Missing
                        is SamsungBrowserProfileResult.Unknown -> SamsungProfileSnapshot.Unknown
                    }
                },
                runtime = DefaultConversionRuntime(),
                identities = DefaultConversionIdentityPort(identityLedger, packager),
                installed = ConversionInstalledPort(installedWrappers::find),
                packages = DefaultConversionPackagePort(
                    identityLedger,
                    packager,
                ),
                bundles = DefaultConversionBundlePort(generatedBundles),
                imports = ConversionGeneratedImportPort { generated ->
                    when (val imported = importer.importGenerated(generated)) {
                        is ImportResult.Failure -> null
                        is ImportResult.Success -> {
                            if (retainedImports.save(imported.value)) {
                                imported.value.toReview()
                            } else {
                                importer.discard(imported.value.token)
                                null
                            }
                        }
                    }
                },
            )
            val uninstall = DefaultUninstallCoordinator(installedWrappers)
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
                scope = applicationScope,
            )
            val repository = DefaultManagerRepository(
                importer = importer,
                retainedImports = retainedImports,
                startupCleanup = StartupCleanup(
                    importRoot = importRoot,
                    installGateway = installGateway,
                    pendingStore = pendingStore,
                    clock = Clock.systemUTC(),
                    systemInstallHandoffStore = systemInstallStore,
                ),
                installCoordinator = installCoordinator,
                systemInstallCoordinator = systemInstallCoordinator,
                pendingStore = pendingStore,
                uninstallCoordinator = uninstall,
                samsungBrowserNavigator =
                    AndroidSamsungBrowserNavigator(application.packageManager),
                catalogPublisher = catalogPublisher,
                conversionCoordinator = conversionCoordinator,
            )
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
