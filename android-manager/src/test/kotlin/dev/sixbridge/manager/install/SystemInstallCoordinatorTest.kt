package dev.sixbridge.manager.install

import android.content.Intent
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.manager.importer.ImportedBundle
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import dev.sixbridge.manager.wrapper.InstallSourceEvidence
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
    fun `정상 요청은 installer launch 없이 export required를 반환한다`() = runTest {
        val fixture = fixture()

        val result = fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        val export = assertIs<SystemInstallRequestResult.ExportRequired>(result)
        assertEquals(TOKEN, export.state.importToken)
        assertEquals(
            SystemInstallStage.PREPARED,
            assertIs<RecoveryRecordSnapshot.Present<PendingSystemInstall>>(
                fixture.handoffStore.snapshot(),
            ).value.stage,
        )
    }

    @Test
    fun `같은 버전 설치본의 APK hash를 확보하지 못하면 내보내기 전에 거부한다`() = runTest {
        val fixture = fixture(previous = expectedInstalled())

        assertEquals(
            SystemInstallRequestResult.Failed(
                InstallState.Failed(
                    "INSTALLED_APK_HASH_UNKNOWN",
                    "설치된 APK의 무결성을 확인하지 못했습니다.",
                ),
            ),
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false),
        )
        assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
    }

    @Test
    fun `같은 버전 설치본의 APK hash가 변환 결과와 다르면 거부한다`() = runTest {
        val fixture = fixture(
            previous = expectedInstalled().copy(apkSha256 = OTHER_SHA),
        )

        assertEquals(
            SystemInstallRequestResult.Failed(
                InstallState.Failed(
                    "SAME_VERSION_APK_MISMATCH",
                    "같은 버전의 설치 APK가 변환 결과와 일치하지 않습니다.",
                ),
            ),
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false),
        )
        assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
    }

    @Test
    fun `같은 버전 설치본의 APK hash가 변환 결과와 같으면 내보내기를 허용한다`() = runTest {
        val fixture = fixture(
            previous = expectedInstalled().copy(apkSha256 = WRAPPER_SHA),
        )

        assertIs<SystemInstallRequestResult.ExportRequired>(
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false),
        )
    }

    @Test
    fun `내 파일 출처와 정확 신원을 함께 확인해야 installed receipt를 만든다`() =
        runTest {
            val fixture = fixture(
                actual = expectedInstalled().copy(
                    apkSha256 = WRAPPER_SHA,
                    installSourceEvidence = InstallSourceEvidence.Known(
                        GOOGLE_PACKAGE_INSTALLER,
                        SAMSUNG_MY_FILES,
                    ),
                ),
            )
            assertIs<SystemInstallRequestResult.ExportRequired>(
                fixture.coordinator.request(fixture.bundle, partialConfirmed = false),
            )
            assertTrue(fixture.coordinator.markManualExportSucceeded(TOKEN))

            assertIs<InstallState.Installed>(
                fixture.coordinator.reconcileManualAfterUserCheck(TOKEN),
            )
            assertEquals(
                FinalReceiptOrigin.MY_FILES,
                fixture.pendingStore.loadFinalReceipt()?.origin,
            )
            assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
        }

    @Test
    fun `ADB 출처도 정확 신원 확인 뒤 installed로 조정한다`() = runTest {
        val fixture = fixture(
            actual = expectedInstalled().copy(
                apkSha256 = WRAPPER_SHA,
                installSourceEvidence = InstallSourceEvidence.Known(null, null),
            ),
        )
        assertIs<SystemInstallRequestResult.ExportRequired>(
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false),
        )
        assertTrue(fixture.coordinator.markManualExportSucceeded(TOKEN))

        assertIs<InstallState.Installed>(
            fixture.coordinator.reconcileManualAfterUserCheck(TOKEN),
        )
    }

    @Test
    fun `manager origin 설치는 unsupported source로 실패한다`() = runTest {
        val fixture = fixture(
            actual = expectedInstalled().copy(
                apkSha256 = WRAPPER_SHA,
                installSourceEvidence = InstallSourceEvidence.Known(
                    GOOGLE_PACKAGE_INSTALLER,
                    "dev.sixbridge.manager",
                ),
            ),
        )
        assertIs<SystemInstallRequestResult.ExportRequired>(
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false),
        )
        assertTrue(fixture.coordinator.markManualExportSucceeded(TOKEN))

        assertEquals(
            "UNSUPPORTED_INSTALL_SOURCE",
            assertIs<InstallState.Failed>(
                fixture.coordinator.reconcileManualAfterUserCheck(TOKEN),
            ).code,
        )
    }

    @Test
    fun `다른 파일 앱 origin은 설치를 유지하고 삼성 인터넷 지원만 미확인으로 둔다`() =
        runTest {
            val fixture = fixture(
                actual = expectedInstalled().copy(
                    apkSha256 = WRAPPER_SHA,
                    installSourceEvidence = InstallSourceEvidence.Known(
                        GOOGLE_PACKAGE_INSTALLER,
                        "com.example.files",
                    ),
                ),
            )
            assertIs<SystemInstallRequestResult.ExportRequired>(
                fixture.coordinator.request(fixture.bundle, partialConfirmed = false),
            )
            assertTrue(fixture.coordinator.markManualExportSucceeded(TOKEN))

            assertEquals(
                InstallState.Installed(PACKAGE),
                fixture.coordinator.reconcileManualAfterUserCheck(TOKEN),
            )
        }

    @Test
    fun `설치 출처를 확인하지 못하면 source unknown으로 실패한다`() = runTest {
        val fixture = fixture(
            actual = expectedInstalled().copy(
                apkSha256 = WRAPPER_SHA,
                installSourceEvidence = InstallSourceEvidence.Unavailable,
            ),
        )
        assertIs<SystemInstallRequestResult.ExportRequired>(
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false),
        )
        assertTrue(fixture.coordinator.markManualExportSucceeded(TOKEN))

        assertEquals(
            "INSTALL_SOURCE_UNKNOWN",
            assertIs<InstallState.Failed>(
                fixture.coordinator.reconcileManualAfterUserCheck(TOKEN),
            ).code,
        )
    }

    @Test
    fun `기존 래퍼와 새 래퍼 signer가 다르면 내보내기 전에 거부한다`() =
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
        }

    @Test
    fun `요청 직전 retained wrapper digest가 달라지면 내보내기 record를 만들지 않는다`() =
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
        }

    @Test
    fun `unknown sources 권한과 무관하게 직접 installer 없이 내보내기를 준비한다`() = runTest {
        val fixture = fixture()

        assertIs<SystemInstallRequestResult.ExportRequired>(
            fixture.coordinator.request(fixture.bundle, partialConfirmed = true),
        )
    }

    @Test
    fun `manual export는 foreground에서 조정하지 않고 정확한 token 확인에서만 조정한다`() =
        runTest {
            val fixture = fixture(
                actual = verifiedInstalled(),
            )
            assertIs<SystemInstallRequestResult.ExportRequired>(
                fixture.coordinator.request(fixture.bundle, partialConfirmed = true),
            )

            assertNull(fixture.coordinator.reconcileAfterReturn())
            assertNull(fixture.coordinator.reconcileManualAfterUserCheck(OTHER_TOKEN))
            assertFalse(fixture.coordinator.markManualExportSucceeded(OTHER_TOKEN))
            assertTrue(fixture.coordinator.markManualExportSucceeded(TOKEN))
            assertEquals(
                InstallState.AwaitingManualInstall(TOKEN),
                fixture.coordinator.state.value,
            )
            assertEquals(
                InstallState.Installed(PACKAGE),
                fixture.coordinator.reconcileManualAfterUserCheck(TOKEN),
            )
            assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
        }

    @Test
    fun `handoff prepare가 실패해도 installer URI를 만들거나 회수하지 않는다`() = runTest {
        val fixture = fixture()
        fixture.handoffStore.prepareFailure = true

        assertEquals(
            SystemInstallRequestResult.Failed(
                InstallState.Failed(
                    "RECOVERY_WRITE_FAILED",
                    "시스템 설치 결과를 저장하지 못했습니다.",
                ),
            ),
            fixture.coordinator.request(fixture.bundle, partialConfirmed = true),
        )
        assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
    }

    @Test
    fun `legacy pending recovery가 있으면 신규 APK 내보내기를 만들지 않는다`() =
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
            assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
        }

    @Test
    fun `신규 coordinator는 legacy PackageInstaller session gateway와 분리된다`() = runTest {
        val legacyGateway = FakeLegacyPackageInstallGateway()
        val fixture = fixture()

        fixture.export()

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
    fun `receipt commit 실패는 exported record를 보존하고 fail closed한다`() = runTest {
        val fixture = fixture(actual = verifiedInstalled())
        fixture.pendingStore.createFinalFailure = true
        fixture.export()

        assertEquals(
            InstallState.Failed(
                "RECOVERY_WRITE_FAILED",
                "시스템 설치 결과를 저장하지 못했습니다.",
            ),
            fixture.coordinator.reconcileManualAfterUserCheck(TOKEN),
        )
        assertIs<RecoveryRecordSnapshot.Present<PendingSystemInstall>>(
            fixture.handoffStore.snapshot(),
        )
    }

    @Test
    fun `durable receipt 다음 handoff clear 실패는 재생성 coordinator가 마무리한다`() =
        runTest {
            val events = mutableListOf<String>()
            val fixture = fixture(actual = verifiedInstalled(), events = events)
            fixture.handoffStore.clearFailure = true
            fixture.export()

            assertEquals(
                InstallState.Failed(
                    "RECOVERY_WRITE_FAILED",
                    "시스템 설치 결과를 저장하지 못했습니다.",
                ),
                fixture.coordinator.reconcileManualAfterUserCheck(TOKEN),
            )
            assertEquals(FinalReceiptOrigin.MY_FILES, fixture.pendingStore.receipt?.origin)
            assertIs<RecoveryRecordSnapshot.Present<PendingSystemInstall>>(
                fixture.handoffStore.snapshot(),
            )
            assertEquals("receipt", events.first())

            fixture.handoffStore.clearFailure = false
            val recreated = fixture.newCoordinator()
            assertEquals(
                InstallState.Installed(PACKAGE),
                recreated.reconcileManualAfterUserCheck(TOKEN),
            )
            assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
            assertEquals(listOf("receipt", "clear", "clear"), events)
            assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
        }

    @Test
    fun `업데이트 취소로 기존 신원이 남으면 MY_FILES Cancelled receipt를 만든다`() = runTest {
        val previous = oldInstalled()
        val fixture = fixture(previous = previous, actual = previous)
        fixture.export()

        assertEquals(
            InstallState.Cancelled,
            fixture.coordinator.reconcileManualAfterUserCheck(TOKEN),
        )
        assertEquals(FinalReceiptOrigin.MY_FILES, fixture.pendingStore.receipt?.origin)
    }

    @Test
    fun `signer version extension ID hash 중 하나라도 다르면 identity conflict다`() =
        runTest {
            listOf(
                verifiedInstalled().copy(signerSha256 = OTHER_SHA),
                verifiedInstalled().copy(versionCode = VERSION + 1),
                verifiedInstalled().copy(extensionId = OTHER_EXTENSION_ID),
                verifiedInstalled().copy(apkSha256 = OTHER_SHA),
            ).forEach { actual ->
                val fixture = fixture(actual = actual)
                fixture.export()

                assertEquals(
                    InstallState.Failed(
                        "INSTALLED_IDENTITY_MISMATCH",
                        "설치된 래퍼 신원이 일치하지 않습니다.",
                    ),
                    fixture.coordinator.reconcileManualAfterUserCheck(TOKEN),
                )
            }
        }

    @Test
    fun `cancel은 owner가 일치하는 handoff와 private import만 정리한다`() = runTest {
        val fixture = fixture()
        fixture.export()

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
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `PREPARED cancel은 import 정리 실패 시 handoff를 보존하고 재시도한다`() = runTest {
        val fixture = fixture()
        assertIs<SystemInstallRequestResult.ExportRequired>(
            fixture.coordinator.request(fixture.bundle, partialConfirmed = true),
        )
        fixture.pendingStore.discardFailure = true

        assertEquals(
            InstallCleanupResult.CleanupFailed,
            fixture.coordinator.cancel(TOKEN),
        )
        assertEquals(
            SystemInstallStage.PREPARED,
            assertIs<RecoveryRecordSnapshot.Present<PendingSystemInstall>>(
                fixture.handoffStore.snapshot(),
            ).value.stage,
        )

        fixture.pendingStore.discardFailure = false
        assertEquals(InstallCleanupResult.Cleaned, fixture.coordinator.cancel(TOKEN))
        assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `EXPORTED cancel은 import 정리 실패 시 handoff를 보존하고 재시도한다`() = runTest {
        val fixture = fixture()
        fixture.export()
        fixture.pendingStore.discardFailure = true

        assertEquals(
            InstallCleanupResult.CleanupFailed,
            fixture.coordinator.cancel(TOKEN),
        )
        assertEquals(
            SystemInstallStage.EXPORTED,
            assertIs<RecoveryRecordSnapshot.Present<PendingSystemInstall>>(
                fixture.handoffStore.snapshot(),
            ).value.stage,
        )

        fixture.pendingStore.discardFailure = false
        assertEquals(InstallCleanupResult.Cleaned, fixture.coordinator.cancel(TOKEN))
        assertEquals(RecoveryRecordSnapshot.Absent, fixture.handoffStore.snapshot())
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    private fun fixture(
        previous: InstalledWrapper? = null,
        actual: InstalledWrapper? = previous,
        events: MutableList<String> = mutableListOf(),
    ): Fixture {
        val bundle = importedBundle()
        val handoffStore = FakeSystemInstallHandoffStore(events)
        val pendingStore = FakePendingInstallStore(events)
        val installedWrappers = FakeInstalledWrapperRepository(
            requestValue = previous,
            reconcileValue = actual,
        )
        var now = NOW
        fun coordinator() = SystemInstallCoordinator(
            policy = InstallPolicy(),
            handoffStore = handoffStore,
            installedWrappers = installedWrappers,
            pendingStore = pendingStore,
            clock = { now++ },
        )
        return Fixture(
            bundle = bundle,
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

    private fun verifiedInstalled() = expectedInstalled().copy(
        apkSha256 = WRAPPER_SHA,
        installSourceEvidence = InstallSourceEvidence.Known(
            GOOGLE_PACKAGE_INSTALLER,
            SAMSUNG_MY_FILES,
        ),
    )

    private fun oldInstalled() = expectedInstalled().copy(versionCode = VERSION - 1)

    private data class Fixture(
        val bundle: ImportedBundle,
        val handoffStore: FakeSystemInstallHandoffStore,
        val pendingStore: FakePendingInstallStore,
        val coordinator: SystemInstallCoordinator,
        val newCoordinator: () -> SystemInstallCoordinator,
    ) {
        suspend fun export() {
            assertIs<SystemInstallRequestResult.ExportRequired>(
                coordinator.request(bundle, partialConfirmed = true),
            )
            assertTrue(coordinator.markManualExportSucceeded(bundle.token))
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
        var prepareFailure = false
        var clearFailure = false

        override fun snapshot(): RecoveryRecordSnapshot<PendingSystemInstall> =
            current?.let { RecoveryRecordSnapshot.Present(it) }
                ?: RecoveryRecordSnapshot.Absent

        override fun tryPrepare(record: PendingSystemInstall): Boolean {
            if (prepareFailure ||
                current != null ||
                record.stage != SystemInstallStage.PREPARED
            ) {
                return false
            }
            current = record
            return true
        }

        override fun markLaunched(expected: PendingSystemInstall): PendingSystemInstall? {
            if (current != expected || expected.stage != SystemInstallStage.PREPARED) return null
            return expected.copy(stage = SystemInstallStage.LAUNCHED).also { current = it }
        }

        override fun markExported(expected: PendingSystemInstall): PendingSystemInstall? {
            if (current != expected || expected.stage != SystemInstallStage.PREPARED) return null
            return expected.copy(stage = SystemInstallStage.EXPORTED).also { current = it }
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
        var discardFailure = false
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
            if (discardFailure) error("discard failed")
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

    }
}
