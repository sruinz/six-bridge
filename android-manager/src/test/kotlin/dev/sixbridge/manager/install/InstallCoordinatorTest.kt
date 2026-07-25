package dev.sixbridge.manager.install

import android.content.Intent
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.importer.ImportedBundle
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstallCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `waits for unknown sources permission and creates the session after returning`() = runBlocking {
        val fixture = fixture(canInstallPackages = false)

        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        assertEquals(InstallState.UnknownSourcesPermissionRequired, fixture.coordinator.state.value)
        assertTrue(fixture.gateway.createdPackages.isEmpty())

        fixture.gateway.canInstallPackages = true
        fixture.coordinator.resumeAfterUnknownSourcesSettings()

        assertEquals(InstallState.AwaitingUserApproval(SESSION_ID), fixture.coordinator.state.value)
        assertEquals(listOf(fixture.bundle.verifiedApk.packageName), fixture.gateway.createdPackages)
        assertEquals(listOf(SESSION_ID), fixture.gateway.committedSessions)
        assertEquals(
            PendingInstall(
                sessionId = SESSION_ID,
                packageName = fixture.bundle.verifiedApk.packageName,
                importToken = TOKEN,
                startedAtEpochMillis = STARTED_AT,
            ),
            fixture.pendingStore.record,
        )
    }

    @Test
    fun `moves from a committed session through pending user action to verified installation`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.PendingUserAction(SESSION_ID))
        assertEquals(InstallState.AwaitingUserApproval(SESSION_ID), fixture.coordinator.state.value)

        fixture.installedWrappers.installed = installedWrapper()
        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Success(SESSION_ID))

        assertEquals(InstallState.Installed(PACKAGE_NAME), fixture.coordinator.state.value)
        assertEquals(
            CatalogRecord(
                packageName = PACKAGE_NAME,
                extensionId = EXTENSION_ID,
                name = "Example",
                iconPng = null,
                installedVersionCode = VERSION_CODE,
                signerSha256 = SIGNER,
                compatibilityStatus = "COMPATIBLE",
                reportJson = EXPECTED_REPORT_JSON,
                lastVerifiedAtEpochMillis = FINISHED_AT,
            ),
            fixture.catalog.replaced,
        )
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `cancelling from package installer abandons the session and clears pending import`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Cancelled(SESSION_ID))

        assertEquals(InstallState.Cancelled, fixture.coordinator.state.value)
        assertEquals(listOf(SESSION_ID), fixture.gateway.abandonedSessions)
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `write failure abandons its allocated session and clears pending import`() = runBlocking {
        val fixture = fixture()
        fixture.gateway.createFailure = PackageInstallSessionException(
            sessionId = SESSION_ID,
            code = "WRITE_FAILED",
            message = "write failed",
        )

        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        assertEquals(InstallState.Failed("WRITE_FAILED", "write failed"), fixture.coordinator.state.value)
        assertEquals(listOf(SESSION_ID), fixture.gateway.abandonedSessions)
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `commit failure abandons the session and clears pending import`() = runBlocking {
        val fixture = fixture()
        fixture.gateway.commitFailure = IllegalStateException("commit failed")

        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        assertIs<InstallState.Failed>(fixture.coordinator.state.value)
        assertEquals(listOf(SESSION_ID), fixture.gateway.abandonedSessions)
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `system failure abandons the session and clears pending import`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        fixture.coordinator.onPackageInstallerResult(
            PackageInstallResult.Failure(
                sessionId = SESSION_ID,
                code = "SYSTEM_FAILURE",
                message = "installer rejected package",
            ),
        )

        assertEquals(
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package"),
            fixture.coordinator.state.value,
        )
        assertEquals(listOf(SESSION_ID), fixture.gateway.abandonedSessions)
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `cleanup failure is reported after both pending and temp cleanup are attempted`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        fixture.pendingStore.clearFailure = true

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Cancelled(SESSION_ID))

        assertEquals(
            InstallState.Failed("CLEANUP_FAILED", "설치 임시 데이터를 정리하지 못했습니다."),
            fixture.coordinator.state.value,
        )
        assertEquals(listOf(SESSION_ID), fixture.gateway.abandonedSessions)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `does not trust a success callback when the installed package cannot be found`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Success(SESSION_ID))

        assertEquals(
            InstallState.Failed(
                "POST_INSTALL_VERIFICATION_FAILED",
                "설치된 래퍼를 검증할 수 없습니다.",
            ),
            fixture.coordinator.state.value,
        )
        assertEquals(listOf(SESSION_ID), fixture.gateway.abandonedSessions)
        assertNull(fixture.catalog.replaced)
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `does not trust a success callback when signer or version differs from the imported wrapper`() = runBlocking {
        val mismatches = listOf(
            installedWrapper().copy(signerSha256 = "2".repeat(64)),
            installedWrapper().copy(versionCode = VERSION_CODE + 1),
        )

        mismatches.forEach { installed ->
            val fixture = fixture()
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
            fixture.installedWrappers.installed = installed

            fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Success(SESSION_ID))

            assertIs<InstallState.Failed>(fixture.coordinator.state.value)
            assertNull(fixture.catalog.replaced)
            assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
        }
    }

    @Test
    fun `does not create a session when partial compatibility was not confirmed`() = runBlocking {
        val fixture = fixture(status = ContractCompatibilityStatus.PARTIAL)

        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        assertEquals(
            InstallState.Failed(
                "PARTIAL_CONFIRMATION_REQUIRED",
                "부분 호환 설치 확인이 필요합니다.",
            ),
            fixture.coordinator.state.value,
        )
        assertTrue(fixture.gateway.createdPackages.isEmpty())
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `does not create a session when install policy denies the bundle`() = runBlocking {
        val fixture = fixture(status = ContractCompatibilityStatus.BLOCKED)

        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        assertEquals(
            InstallState.Failed("BLOCKED_COMPATIBILITY", "설치 정책이 래퍼를 거부했습니다."),
            fixture.coordinator.state.value,
        )
        assertTrue(fixture.gateway.createdPackages.isEmpty())
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `recovers an owned pending session without retaining an apk path or bytes`() {
        val pending = PendingInstall(
            sessionId = SESSION_ID,
            packageName = PACKAGE_NAME,
            importToken = TOKEN,
            startedAtEpochMillis = STARTED_AT,
        )
        val fixture = fixture(pending = pending, ownedSessions = listOf(SESSION_ID))

        assertEquals(InstallState.AwaitingUserApproval(SESSION_ID), fixture.coordinator.state.value)
        assertEquals(pending, fixture.pendingStore.record)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `does not accept a success callback after process recreation without expected signer and version`() = runBlocking {
        val pending = PendingInstall(
            sessionId = SESSION_ID,
            packageName = PACKAGE_NAME,
            importToken = TOKEN,
            startedAtEpochMillis = STARTED_AT,
        )
        val fixture = fixture(pending = pending, ownedSessions = listOf(SESSION_ID))
        fixture.installedWrappers.installed = installedWrapper()

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Success(SESSION_ID))

        assertIs<InstallState.Failed>(fixture.coordinator.state.value)
        assertNull(fixture.catalog.replaced)
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `keeps a recently completed session pending until its result can be delivered`() {
        val pending = PendingInstall(
            sessionId = SESSION_ID,
            packageName = PACKAGE_NAME,
            importToken = TOKEN,
            startedAtEpochMillis = STARTED_AT,
        )
        val fixture = fixture(pending = pending, ownedSessions = emptyList())

        assertEquals(InstallState.AwaitingUserApproval(SESSION_ID), fixture.coordinator.state.value)
        assertEquals(pending, fixture.pendingStore.record)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `cleans expired pending data when its session is no longer owned`() {
        val pending = PendingInstall(
            sessionId = SESSION_ID,
            packageName = PACKAGE_NAME,
            importToken = TOKEN,
            startedAtEpochMillis = STARTED_AT,
        )
        val fixture = fixture(
            pending = pending,
            ownedSessions = emptyList(),
            now = STALE_NOW,
        )

        assertEquals(InstallState.Idle, fixture.coordinator.state.value)
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `rejects a second request without replacing the active session`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        val secondToken = "123e4567-e89b-12d3-a456-426614174001"
        val secondBundle = importedBundle(
            status = ContractCompatibilityStatus.COMPATIBLE,
            token = secondToken,
        )

        fixture.coordinator.request(secondBundle, partialConfirmed = false)

        assertEquals(InstallState.AwaitingUserApproval(SESSION_ID), fixture.coordinator.state.value)
        assertEquals(listOf(PACKAGE_NAME), fixture.gateway.createdPackages)
        assertEquals(TOKEN, fixture.pendingStore.record?.importToken)
        assertEquals(listOf(secondToken), fixture.pendingStore.discardedTokens)
    }

    private fun fixture(
        canInstallPackages: Boolean = true,
        status: ContractCompatibilityStatus = ContractCompatibilityStatus.COMPATIBLE,
        pending: PendingInstall? = null,
        ownedSessions: List<Int> = emptyList(),
        now: Long = FINISHED_AT,
    ): Fixture {
        val gateway = FakePackageInstallGateway(canInstallPackages, ownedSessions)
        val pendingStore = FakePendingInstallStore(pending)
        val installedWrappers = FakeInstalledWrapperRepository()
        val catalog = FakeManagerCatalog()
        val bundle = importedBundle(status)
        return Fixture(
            coordinator = InstallCoordinator(
                policy = InstallPolicy(),
                gateway = gateway,
                pendingStore = pendingStore,
                installedWrappers = installedWrappers,
                catalog = catalog,
                clock = { if (pendingStore.record == null) STARTED_AT else now },
            ),
            gateway = gateway,
            pendingStore = pendingStore,
            installedWrappers = installedWrappers,
            catalog = catalog,
            bundle = bundle,
        )
    }

    private fun importedBundle(
        status: ContractCompatibilityStatus,
        token: String = TOKEN,
    ): ImportedBundle {
        val wrapper = temporaryFolder.newFile()
        wrapper.writeText("apk")
        val report = compatibilityReport(status)
        val verified = VerifiedWrapperApk(
            packageName = PACKAGE_NAME,
            versionCode = VERSION_CODE,
            signerSha256 = SIGNER,
            wrapperSha256 = WRAPPER_SHA256,
            extensionId = EXTENSION_ID,
            providerAuthority = "$PACKAGE_NAME.contentprovider",
            crx = VerifiedCrx3(
                extensionId = EXTENSION_ID,
                publicKeySha256 = "b".repeat(64),
                crxSha256 = "c".repeat(64),
                zipOffset = 0,
                zipLength = 0,
            ),
        )
        return ImportedBundle(
            token = token,
            descriptor = ContractExtensionDescriptor(
                schemaVersion = 1,
                extensionId = EXTENSION_ID,
                name = "Example",
                version = "1.0",
                sourceKind = "CRX3",
                crxSha256 = "c".repeat(64),
                wrapperSha256 = WRAPPER_SHA256,
                wrapperPackage = PACKAGE_NAME,
                wrapperVersionCode = VERSION_CODE.toInt(),
                signerSha256 = SIGNER,
            ),
            report = report,
            verifiedApk = verified,
            wrapperFile = wrapper,
            wrapperSha256 = WRAPPER_SHA256,
        )
    }

    private fun compatibilityReport(status: ContractCompatibilityStatus) =
        ContractCompatibilityReport(
            schemaVersion = 3,
            status = status,
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

    private fun installedWrapper() = InstalledWrapper(
        packageName = PACKAGE_NAME,
        extensionId = EXTENSION_ID,
        name = "Example",
        versionCode = VERSION_CODE,
        signerSha256 = SIGNER,
    )

    private data class Fixture(
        val coordinator: InstallCoordinator,
        val gateway: FakePackageInstallGateway,
        val pendingStore: FakePendingInstallStore,
        val installedWrappers: FakeInstalledWrapperRepository,
        val catalog: FakeManagerCatalog,
        val bundle: ImportedBundle,
    )

    private class FakePackageInstallGateway(
        var canInstallPackages: Boolean,
        ownedSessions: List<Int>,
    ) : PackageInstallGateway {
        val createdPackages = mutableListOf<String>()
        val committedSessions = mutableListOf<Int>()
        val abandonedSessions = mutableListOf<Int>()
        var createFailure: PackageInstallSessionException? = null
        var commitFailure: RuntimeException? = null
        private val owned = ownedSessions.toMutableList()

        override fun canRequestPackageInstalls(): Boolean = canInstallPackages

        override fun unknownSourcesSettingsIntent(): Intent =
            throw UnsupportedOperationException("not called by coordinator")

        override suspend fun createAndWrite(packageName: String, apk: File): Int {
            createFailure?.let { throw it }
            createdPackages += packageName
            owned += SESSION_ID
            return SESSION_ID
        }

        override fun commit(sessionId: Int) {
            commitFailure?.let { throw it }
            committedSessions += sessionId
        }

        override fun abandon(sessionId: Int) {
            abandonedSessions += sessionId
            owned -= sessionId
        }

        override fun ownedSessions(): List<Int> = owned.toList()
    }

    private class FakePendingInstallStore(
        initial: PendingInstall?,
    ) : PendingInstallStore {
        var record: PendingInstall? = initial
        val discardedTokens = mutableListOf<String>()
        var clearFailure = false

        override fun load(): PendingInstall? = record

        override fun save(pending: PendingInstall) {
            record = pending
        }

        override fun clear() {
            if (clearFailure) throw IllegalStateException("clear failed")
            record = null
        }

        override fun discardImport(importToken: String) {
            discardedTokens += importToken
        }
    }

    private class FakeInstalledWrapperRepository : InstalledWrapperRepository {
        var installed: InstalledWrapper? = null

        override suspend fun findAll(): List<InstalledWrapper> = listOfNotNull(installed)

        override suspend fun find(packageName: String): InstalledWrapper? =
            installed?.takeIf { it.packageName == packageName }
    }

    private class FakeManagerCatalog : ManagerCatalog {
        var replaced: CatalogRecord? = null

        override fun observe(): Flow<List<CatalogRecord>> = flowOf(listOfNotNull(replaced))

        override suspend fun replaceVerified(record: CatalogRecord) {
            replaced = record
        }

        override suspend fun remove(packageName: String) = Unit

        override suspend fun removeAll(packageNames: Set<String>) = Unit

        override suspend fun get(packageName: String): CatalogRecord? =
            replaced?.takeIf { it.packageName == packageName }
    }

    private companion object {
        const val EXTENSION_ID = "aabbccddeeffgghhiijjkkllmmnnoopp"
        const val PACKAGE_NAME = "dev.sixbridge.ext.$EXTENSION_ID"
        const val TOKEN = "123e4567-e89b-12d3-a456-426614174000"
        const val SESSION_ID = 41
        const val VERSION_CODE = 20L
        const val STARTED_AT = 1_000L
        const val FINISHED_AT = 2_000L
        const val STALE_NOW = 86_402_001L
        const val WRAPPER_SHA256 = "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04"
        val SIGNER = "1".repeat(64)
        const val EXPECTED_REPORT_JSON =
            """{"schemaVersion":3,"status":"compatible","manifestKeyPresent":true,"declaredPermissions":[],"usedApis":[],"unsupportedApis":[],"issues":[],"manifestVersion":3,"extensionName":"Example","extensionVersion":"1.0","manifestKeyExtensionId":"aabbccddeeffgghhiijjkkllmmnnoopp","conversionState":"converted","adapterDecisions":[],"originalExtensionId":"aabbccddeeffgghhiijjkkllmmnnoopp","convertedExtensionId":"aabbccddeeffgghhiijjkkllmmnnoopp","sourceSha256":"dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd","browserProfileSha256":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}"""
    }
}
