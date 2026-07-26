package dev.sixbridge.manager.app

import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.CatalogReconciler
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.importer.ImportedBundle
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import dev.sixbridge.manager.install.PackageInstallResult
import dev.sixbridge.manager.install.PackageInstallCallback
import dev.sixbridge.manager.install.PackageInstallEvents
import dev.sixbridge.manager.ui.LaunchDestination
import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import dev.sixbridge.manager.wrapper.WrapperVerificationResult
import java.io.File
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
    override suspend fun replaceVerified(record: CatalogRecord) = Unit
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
