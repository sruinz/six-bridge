package dev.sixbridge.manager.install

import android.content.Intent
import android.net.Uri
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.manager.importer.ImportedBundle
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SystemInstallCoordinatorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `기대 신원이 설치되면 session 없는 SYSTEM_INSTALLER success receipt를 만든다`() =
        runTest {
            val fixture = fixture(actual = expectedInstalled())
            fixture.launch()

            assertEquals(
                InstallState.Installed(PACKAGE),
                fixture.coordinator.reconcileAfterReturn(),
            )
            assertEquals(
                FinalReceiptOrigin.SYSTEM_INSTALLER,
                fixture.pendingStore.loadFinalReceipt()?.origin,
            )
            assertNull(fixture.pendingStore.loadFinalReceipt()?.sessionId)
            assertNull(fixture.pendingStore.loadFinalReceipt()?.rawTerminalAtEpochMillis)
            assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
            assertEquals(listOf(fixture.bundle.wrapperFile), fixture.gateway.revoked)
        }

    @Test
    fun `업데이트 취소로 기존 신원이 남으면 Cancelled로 조정한다`() = runTest {
        val previous = oldInstalled()
        val fixture = fixture(previous = previous, actual = previous)
        fixture.launch()

        assertEquals(InstallState.Cancelled, fixture.coordinator.reconcileAfterReturn())
    }

    @Test
    fun `실제 설치본이 없으면 Cancelled로 조정한다`() = runTest {
        val fixture = fixture(actual = null)
        fixture.launch()

        assertEquals(InstallState.Cancelled, fixture.coordinator.reconcileAfterReturn())
    }

    @Test
    fun `signer version extension ID 중 하나라도 다르면 identity conflict다`() = runTest {
        listOf(
            expectedInstalled().copy(signerSha256 = OTHER_SHA),
            expectedInstalled().copy(versionCode = VERSION + 1),
            expectedInstalled().copy(extensionId = OTHER_EXTENSION_ID),
        ).forEach { actual ->
            val fixture = fixture(actual = actual)
            fixture.launch()

            assertEquals(
                InstallState.Failed(
                    "INSTALLED_IDENTITY_MISMATCH",
                    "설치된 래퍼 신원이 일치하지 않습니다.",
                ),
                fixture.coordinator.reconcileAfterReturn(),
            )
        }
    }

    @Test
    fun `기존 래퍼와 새 래퍼 signer가 다르면 설치 화면을 열기 전에 거부한다`() =
        runTest {
            val fixture = fixture(previous = oldInstalled().copy(signerSha256 = OTHER_SHA))

            assertEquals(
                SystemInstallRequestResult.Failed(
                    InstallState.Failed(
                        "SIGNATURE_MISMATCH",
                        "설치된 래퍼와 새 래퍼의 서명이 일치하지 않습니다.",
                    ),
                ),
                fixture.coordinator.request(fixture.bundle, partialConfirmed = true),
            )
            assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
            assertEquals(0, fixture.gateway.createLaunchCalls)
        }

    @Test
    fun `요청 직전 retained wrapper digest가 달라지면 설치 화면을 만들지 않는다`() =
        runTest {
            val fixture = fixture()
            fixture.bundle.wrapperFile.writeText("tampered")

            assertEquals(
                SystemInstallRequestResult.Failed(
                    InstallState.Failed(
                        "WRAPPER_HASH_MISMATCH",
                        "검증 후 보관된 래퍼가 변경되었습니다.",
                    ),
                ),
                fixture.coordinator.request(fixture.bundle, partialConfirmed = true),
            )
            assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
            assertEquals(0, fixture.gateway.createLaunchCalls)
        }

    @Test
    fun `unknown sources 권한이 없으면 handoff를 준비하지 않는다`() = runTest {
        val fixture = fixture(canRequestPackageInstalls = false)

        assertEquals(
            SystemInstallRequestResult.UnknownSourcesPermissionRequired,
            fixture.coordinator.request(fixture.bundle, partialConfirmed = true),
        )
        assertEquals(
            InstallState.UnknownSourcesPermissionRequired,
            fixture.coordinator.state.value,
        )
        assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
    }

    @Test
    fun `허용된 system installer가 없으면 prepared handoff를 남기지 않는다`() = runTest {
        val fixture = fixture(
            launchResult = SystemInstallerLaunchResult.MyFilesFallbackRequired,
        )

        assertEquals(
            SystemInstallRequestResult.MyFilesFallbackRequired,
            fixture.coordinator.request(fixture.bundle, partialConfirmed = true),
        )
        assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
    }

    @Test
    fun `launch 성공 표시는 정확한 owner의 PREPARED record만 LAUNCHED로 바꾼다`() =
        runTest {
            val fixture = fixture()
            val result = assertIs<SystemInstallRequestResult.Launch>(
                fixture.coordinator.request(fixture.bundle, partialConfirmed = true),
            )

            assertEquals(
                InstallState.AwaitingSystemInstaller(TOKEN),
                result.state,
            )
            assertFalse(fixture.coordinator.markLaunchSucceeded(OTHER_TOKEN))
            assertTrue(fixture.coordinator.markLaunchSucceeded(TOKEN))
            assertFalse(fixture.coordinator.markLaunchSucceeded(TOKEN))
            val record = assertIs<RecoveryRecordSnapshot.Present<PendingSystemInstall>>(
                fixture.handoffStore.snapshot(),
            ).value
            assertEquals(SystemInstallStage.LAUNCHED, record.stage)
        }

    @Test
    fun `launch 실패 표시는 PREPARED record와 URI 권한을 정리하고 실패 상태를 남긴다`() =
        runTest {
            val fixture = fixture()
            assertIs<SystemInstallRequestResult.Launch>(
                fixture.coordinator.request(fixture.bundle, partialConfirmed = true),
            )

            assertFalse(fixture.coordinator.markLaunchFailed(OTHER_TOKEN))
            assertTrue(fixture.coordinator.markLaunchFailed(TOKEN))
            assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
            assertEquals(listOf(fixture.bundle.wrapperFile), fixture.gateway.revoked)
            assertEquals(
                InstallState.Failed(
                    "SYSTEM_INSTALLER_LAUNCH_FAILED",
                    "시스템 설치 화면을 열지 못했습니다.",
                ),
                fixture.coordinator.state.value,
            )
        }

    @Test
    fun `legacy pending recovery가 있으면 신규 시스템 설치 화면을 만들지 않는다`() =
        runTest {
            val fixture = fixture()
            fixture.pendingStore.pending = PendingInstall(
                sessionId = 41,
                packageName = PACKAGE,
                importToken = OTHER_TOKEN,
                startedAtEpochMillis = NOW,
            )

            assertIs<SystemInstallRequestResult.Failed>(
                fixture.coordinator.request(fixture.bundle, partialConfirmed = true),
            )
            assertEquals(0, fixture.gateway.createLaunchCalls)
            assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
        }

    @Test
    fun `신규 coordinator는 legacy PackageInstaller session gateway와 분리된다`() = runTest {
        val legacyGateway = FakeLegacyPackageInstallGateway()
        val fixture = fixture()

        fixture.launch()

        assertTrue(
            SystemInstallCoordinator::class.java.declaredConstructors
                .flatMap { it.parameterTypes.asIterable() }
                .none(PackageInstallGateway::class.java::isAssignableFrom),
        )
        assertEquals(0, legacyGateway.createSessionCalls)
        assertEquals(0, legacyGateway.writeSessionCalls)
        assertEquals(0, legacyGateway.commitCalls)
    }

    @Test
    fun `receipt commit 실패는 launched record를 보존하고 fail closed한다`() = runTest {
        val fixture = fixture(actual = expectedInstalled())
        fixture.pendingStore.createFinalFailure = true
        fixture.launch()

        assertEquals(
            InstallState.Failed(
                "RECOVERY_WRITE_FAILED",
                "시스템 설치 결과를 저장하지 못했습니다.",
            ),
            fixture.coordinator.reconcileAfterReturn(),
        )
        assertIs<RecoveryRecordSnapshot.Present<PendingSystemInstall>>(
            fixture.handoffStore.snapshot(),
        )
        assertTrue(fixture.gateway.revoked.isEmpty())
    }

    @Test
    fun `durable receipt 다음 handoff clear 실패는 재생성 coordinator가 마무리한다`() =
        runTest {
            val events = mutableListOf<String>()
            val fixture = fixture(actual = expectedInstalled(), events = events)
            fixture.handoffStore.clearFailure = true
            fixture.launch()

            assertEquals(
                InstallState.Failed(
                    "RECOVERY_WRITE_FAILED",
                    "시스템 설치 결과를 저장하지 못했습니다.",
                ),
                fixture.coordinator.reconcileAfterReturn(),
            )
            assertEquals(FinalReceiptOrigin.SYSTEM_INSTALLER, fixture.pendingStore.receipt?.origin)
            assertIs<RecoveryRecordSnapshot.Present<PendingSystemInstall>>(
                fixture.handoffStore.snapshot(),
            )
            assertEquals("receipt", events.first())

            fixture.handoffStore.clearFailure = false
            val recreated = fixture.newCoordinator()
            assertEquals(InstallState.Installed(PACKAGE), recreated.reconcileAfterReturn())
            assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
            assertEquals(listOf("receipt", "clear", "clear", "revoke"), events)
        }

    @Test
    fun `cancel은 owner가 일치하는 handoff만 지우고 URI 권한과 import를 정리한다`() =
        runTest {
            val fixture = fixture()
            fixture.launch()

            assertEquals(
                InstallCleanupResult.OwnerMismatch,
                fixture.coordinator.cancel(OTHER_TOKEN),
            )
            assertIs<RecoveryRecordSnapshot.Present<PendingSystemInstall>>(
                fixture.handoffStore.snapshot(),
            )
            assertEquals(
                InstallCleanupResult.Cleaned,
                fixture.coordinator.cancel(TOKEN),
            )
            assertEquals(listOf(fixture.bundle.wrapperFile), fixture.gateway.revoked)
            assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
        }

    private fun fixture(
        previous: InstalledWrapper? = null,
        actual: InstalledWrapper? = previous,
        canRequestPackageInstalls: Boolean = true,
        launchResult: SystemInstallerLaunchResult = readyLaunch(),
        events: MutableList<String> = mutableListOf(),
    ): Fixture {
        val bundle = importedBundle()
        val gateway = FakeSystemInstallerGateway(
            canRequest = canRequestPackageInstalls,
            launchResult = launchResult,
            events = events,
        )
        val handoffStore = FakeSystemInstallHandoffStore(events)
        val pendingStore = FakePendingInstallStore(events)
        val installedWrappers = FakeInstalledWrapperRepository(
            requestValue = previous,
            reconcileValue = actual,
        )
        var now = NOW
        fun coordinator() = SystemInstallCoordinator(
            policy = InstallPolicy(),
            gateway = gateway,
            handoffStore = handoffStore,
            installedWrappers = installedWrappers,
            pendingStore = pendingStore,
            clock = { now++ },
            retainedWrapperFile = { bundle.wrapperFile },
        )
        return Fixture(
            bundle = bundle,
            gateway = gateway,
            handoffStore = handoffStore,
            pendingStore = pendingStore,
            coordinator = coordinator(),
            newCoordinator = ::coordinator,
        )
    }

    private fun importedBundle(): ImportedBundle {
        val wrapper = temporaryFolder.newFile().apply { writeText("apk") }
        val verified = VerifiedWrapperApk(
            packageName = PACKAGE,
            versionCode = VERSION,
            signerSha256 = SIGNER_SHA,
            wrapperSha256 = WRAPPER_SHA,
            extensionId = EXTENSION_ID,
            providerAuthority = "$PACKAGE.contentprovider",
            crx = VerifiedCrx3(
                extensionId = EXTENSION_ID,
                publicKeySha256 = "b".repeat(64),
                crxSha256 = "c".repeat(64),
                zipOffset = 0,
                zipLength = 0,
            ),
        )
        return ImportedBundle(
            token = TOKEN,
            descriptor = ContractExtensionDescriptor(
                schemaVersion = 1,
                extensionId = EXTENSION_ID,
                name = "Example",
                version = "2.0",
                sourceKind = "CRX3",
                crxSha256 = "c".repeat(64),
                wrapperSha256 = WRAPPER_SHA,
                wrapperPackage = PACKAGE,
                wrapperVersionCode = VERSION.toInt(),
                signerSha256 = SIGNER_SHA,
            ),
            report = compatibilityReport(),
            verifiedApk = verified,
            wrapperFile = wrapper,
            wrapperSha256 = WRAPPER_SHA,
        )
    }

    private fun compatibilityReport() = ContractCompatibilityReport(
        schemaVersion = 3,
        status = ContractCompatibilityStatus.COMPATIBLE,
        manifestVersion = 3,
        extensionName = "Example",
        extensionVersion = "2.0",
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

    private fun expectedInstalled() = InstalledWrapper(
        packageName = PACKAGE,
        extensionId = EXTENSION_ID,
        name = "Example",
        versionCode = VERSION,
        signerSha256 = SIGNER_SHA,
    )

    private fun oldInstalled() = expectedInstalled().copy(versionCode = VERSION - 1)

    private data class Fixture(
        val bundle: ImportedBundle,
        val gateway: FakeSystemInstallerGateway,
        val handoffStore: FakeSystemInstallHandoffStore,
        val pendingStore: FakePendingInstallStore,
        val coordinator: SystemInstallCoordinator,
        val newCoordinator: () -> SystemInstallCoordinator,
    ) {
        suspend fun launch() {
            assertIs<SystemInstallRequestResult.Launch>(
                coordinator.request(bundle, partialConfirmed = true),
            )
            assertTrue(coordinator.markLaunchSucceeded(bundle.token))
        }
    }

    private class FakeSystemInstallerGateway(
        private val canRequest: Boolean,
        private val launchResult: SystemInstallerLaunchResult,
        private val events: MutableList<String>,
    ) : SystemInstallerGateway {
        var createLaunchCalls = 0
        val revoked = mutableListOf<File>()

        override fun canRequestPackageInstalls(): Boolean = canRequest

        override fun unknownSourcesSettingsIntent(): Intent = Intent("settings")

        override fun createLaunch(apk: File): SystemInstallerLaunchResult {
            createLaunchCalls += 1
            return launchResult
        }

        override fun revoke(apk: File) {
            events += "revoke"
            revoked += apk
        }
    }

    private class FakeLegacyPackageInstallGateway : PackageInstallGateway {
        var createSessionCalls = 0
        var writeSessionCalls = 0
        var commitCalls = 0

        override fun canRequestPackageInstalls(): Boolean = true

        override fun unknownSourcesSettingsIntent(): Intent = Intent("settings")

        override suspend fun createSession(packageName: String): Int {
            createSessionCalls += 1
            return 41
        }

        override suspend fun writeSession(sessionId: Int, apk: File) {
            writeSessionCalls += 1
        }

        override fun commit(sessionId: Int) {
            commitCalls += 1
        }

        override fun abandon(sessionId: Int) = Unit

        override fun ownedSessions(): List<Int> = emptyList()

        override fun sessionSnapshot(sessionId: Int): InstallSessionSnapshot? = null
    }

    private class FakeSystemInstallHandoffStore(
        private val events: MutableList<String>,
    ) : SystemInstallHandoffStore {
        var current: PendingSystemInstall? = null
        var clearFailure = false

        override fun snapshot(): RecoveryRecordSnapshot<PendingSystemInstall> =
            current?.let { RecoveryRecordSnapshot.Present(it) }
                ?: RecoveryRecordSnapshot.Absent

        override fun tryPrepare(record: PendingSystemInstall): Boolean {
            if (current != null || record.stage != SystemInstallStage.PREPARED) return false
            current = record
            return true
        }

        override fun markLaunched(expected: PendingSystemInstall): PendingSystemInstall? {
            if (current != expected || expected.stage != SystemInstallStage.PREPARED) return null
            return expected.copy(stage = SystemInstallStage.LAUNCHED).also { current = it }
        }

        override fun clear(expected: PendingSystemInstall): Boolean {
            events += "clear"
            if (clearFailure || current != expected) return false
            current = null
            return true
        }
    }

    private class FakeInstalledWrapperRepository(
        private val requestValue: InstalledWrapper?,
        private val reconcileValue: InstalledWrapper?,
    ) : InstalledWrapperRepository {
        private var calls = 0

        override suspend fun findAll(): List<InstalledWrapper> =
            listOfNotNull(reconcileValue)

        override suspend fun find(packageName: String): InstalledWrapper? =
            if (calls++ == 0) requestValue else reconcileValue
    }

    private class FakePendingInstallStore(
        private val events: MutableList<String>,
    ) : PendingInstallStore {
        var pending: PendingInstall? = null
        var receipt: FinalInstallReceipt? = null
        var createFinalFailure = false
        val discardedTokens = mutableListOf<String>()

        override fun load(): PendingInstall? = pending

        override fun tryClaim(
            packageName: String,
            importToken: String,
            startedAtEpochMillis: Long,
        ): PendingInstall? = null

        override fun assignSession(claim: PendingInstall, sessionId: Int): PendingInstall? = null

        override fun recoverSession(claim: PendingInstall, sessionId: Int): PendingInstall? = null

        override fun clear(expected: PendingInstall): Boolean = false

        override fun clearWithoutTerminal(expected: PendingInstall): PendingCleanupResult =
            PendingCleanupResult.Failed

        override fun discardImport(importToken: String) {
            discardedTokens += importToken
        }

        override fun loadTerminalResult(): PendingInstallResult? = null

        override fun recordTerminalResult(
            result: PackageInstallResult,
            terminalAtEpochMillis: Long,
        ): Boolean = false

        override fun markTerminalProcessed(expected: PendingInstallResult): PendingInstallResult? =
            null

        override fun clearTerminalResult(expected: PendingInstallResult): Boolean = false

        override fun loadFinalReceipt(): FinalInstallReceipt? = receipt

        override fun createFinalReceipt(
            expectedPending: PendingInstall?,
            expectedRaw: PendingInstallResult?,
            receipt: FinalInstallReceipt,
        ): Boolean {
            if (createFinalFailure ||
                expectedPending != null ||
                expectedRaw != null ||
                this.receipt != null
            ) {
                return false
            }
            events += "receipt"
            this.receipt = receipt
            return true
        }

        override fun firstSessionMissingAt(
            pending: PendingInstall,
            observedAtEpochMillis: Long,
        ): Long = observedAtEpochMillis

        override fun clearSessionMissingObservation(pending: PendingInstall): Boolean = false
    }

    private companion object {
        const val TOKEN = "00000000-0000-0000-0000-000000000001"
        const val OTHER_TOKEN = "00000000-0000-0000-0000-000000000002"
        const val PACKAGE = "dev.sixbridge.ext.aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val EXTENSION_ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_EXTENSION_ID = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val VERSION = 12L
        const val SIGNER_SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_SHA = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val WRAPPER_SHA = "dd37c2d7274f7ea982cb83390c36918fee9ce8889073c44b68cdc00bdb8c3e04"
        const val NOW = 123L

        fun readyLaunch() = SystemInstallerLaunchResult.Ready(
            SystemInstallerLaunch(
                uri = Uri.parse("content://dev.sixbridge.manager.install/import/wrapper.apk"),
                intent = Intent(Intent.ACTION_VIEW),
            ),
        )
    }
}
