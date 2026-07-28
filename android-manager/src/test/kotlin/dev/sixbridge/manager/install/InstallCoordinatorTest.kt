package dev.sixbridge.manager.install

import android.content.Intent
import dev.sixbridge.contract.bundle.ContractAdapterDecision
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.manager.catalog.CatalogIdentity
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.catalog.identity
import dev.sixbridge.manager.importer.ImportedBundle
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CyclicBarrier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
        assertNotNull(fixture.pendingStore.terminalResult)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        fixture.acknowledgeTerminal(InstallState.Installed(PACKAGE_NAME))
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `같은 session의 pending confirmation은 process 재생성 뒤에도 한 번만 승인한다`() =
        runBlocking {
            val fixture = fixture()
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

            assertTrue(
                fixture.coordinator.onPackageInstallerResult(
                    PackageInstallResult.PendingUserAction(SESSION_ID),
                ),
            )
            assertTrue(fixture.coordinator.markConfirmationDelivered(SESSION_ID))
            assertTrue(
                !fixture.coordinator.onPackageInstallerResult(
                    PackageInstallResult.PendingUserAction(SESSION_ID),
                ),
            )

            val recreated = coordinator(
                fixture.gateway,
                fixture.pendingStore,
                fixture.installedWrappers,
                fixture.catalog,
            )
            assertTrue(
                !recreated.onPackageInstallerResult(
                    PackageInstallResult.PendingUserAction(SESSION_ID),
                ),
            )
        }

    @Test
    fun `terminal cleanup 뒤 재사용된 session id의 confirmation은 다시 승인한다`() =
        runBlocking {
            val fixture = fixture()
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
            assertTrue(
                fixture.coordinator.onPackageInstallerResult(
                    PackageInstallResult.PendingUserAction(SESSION_ID),
                ),
            )
            assertTrue(fixture.coordinator.markConfirmationDelivered(SESSION_ID))
            fixture.coordinator.onPackageInstallerResult(
                PackageInstallResult.Cancelled(SESSION_ID),
            )
            fixture.acknowledgeTerminal(InstallState.Cancelled)

            val nextToken = "123e4567-e89b-12d3-a456-426614174001"
            fixture.coordinator.request(
                importedBundle(ContractCompatibilityStatus.COMPATIBLE, nextToken),
                partialConfirmed = false,
            )

            assertTrue(
                fixture.coordinator.onPackageInstallerResult(
                    PackageInstallResult.PendingUserAction(SESSION_ID),
                ),
            )
        }

    @Test
    fun `confirmation marker 저장 실패도 process 수명 중 duplicate를 차단한다`() =
        runBlocking {
            val fixture = fixture()
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
            fixture.pendingStore.confirmationMarkFailure = true

            assertTrue(
                fixture.coordinator.onPackageInstallerResult(
                    PackageInstallResult.PendingUserAction(SESSION_ID),
                ),
            )
            assertTrue(!fixture.coordinator.markConfirmationDelivered(SESSION_ID))

            assertTrue(
                !fixture.coordinator.onPackageInstallerResult(
                    PackageInstallResult.PendingUserAction(SESSION_ID),
                ),
            )
        }

    @Test
    fun `cancelling from package installer preserves retained import until result handoff`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Cancelled(SESSION_ID))

        assertEquals(InstallState.Cancelled, fixture.coordinator.state.value)
        assertTrue(fixture.gateway.abandonedSessions.isEmpty())
        assertNull(fixture.pendingStore.record)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        fixture.acknowledgeTerminal(InstallState.Cancelled)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `session 생성 전 cleanup 실패는 bundle 소유권을 유지해 cancel 재시도를 허용한다`() =
        runBlocking {
            val fixture = fixture(canInstallPackages = false)
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
            fixture.pendingStore.createFinalFailure = true

            assertEquals(
                InstallCleanupResult.CleanupFailed,
                fixture.coordinator.cancel(TOKEN, null),
            )
            assertEquals("CLEANUP_FAILED", assertIs<InstallState.Failed>(fixture.coordinator.state.value).code)

            fixture.pendingStore.createFinalFailure = false

            assertEquals(
                InstallCleanupResult.CleanupFailed,
                fixture.coordinator.cancel(TOKEN, null),
            )
            assertEquals(InstallState.Cancelled, fixture.coordinator.state.value)
            assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        }

    @Test
    fun `null-session INSTALL active owner만 token으로 정리한다`() = runBlocking {
        val fixture = fixture(canInstallPackages = false)
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        val result = fixture.coordinator.cancel(
            expectedImportToken = TOKEN,
            expectedSessionId = null,
        )

        assertEquals(InstallCleanupResult.CleanupFailed, result)
        assertEquals(InstallState.Cancelled, fixture.coordinator.state.value)
        assertEquals(FinalReceiptStage.READY, fixture.pendingStore.finalReceipt?.stage)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `null-session INSTALL assigned same owner는 session만 알려주고 취소하지 않는다`() =
        runBlocking {
            val pending = pendingInstall()
            val fixture = fixture(
                pending = pending,
                ownedSessions = listOf(SESSION_ID),
            )

            val result = fixture.coordinator.cancel(
                expectedImportToken = TOKEN,
                expectedSessionId = null,
            )

            assertEquals(InstallCleanupResult.OwnerSessionAssigned(SESSION_ID), result)
            assertEquals(pending, fixture.pendingStore.record)
            assertTrue(fixture.gateway.abandonedSessions.isEmpty())
            assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        }

    @Test
    fun `null-session INSTALL foreign owner는 상태와 파일을 건드리지 않는다`() = runBlocking {
        val foreignToken = "22222222-2222-2222-2222-222222222222"
        val pending = pendingInstall()
        val fixture = fixture(
            pending = pending,
            ownedSessions = listOf(SESSION_ID),
        )

        val result = fixture.coordinator.cancel(
            expectedImportToken = foreignToken,
            expectedSessionId = null,
        )

        assertEquals(InstallCleanupResult.OwnerMismatch, result)
        assertEquals(pending, fixture.pendingStore.record)
        assertTrue(fixture.gateway.abandonedSessions.isEmpty())
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `session-bound INSTALL은 exact token과 session만 정리한다`() = runBlocking {
        val fixture = fixture(
            pending = pendingInstall(),
            ownedSessions = listOf(SESSION_ID),
        )

        val mismatch = fixture.coordinator.cancel(
            expectedImportToken = TOKEN,
            expectedSessionId = SESSION_ID + 1,
        )
        val cleaned = fixture.coordinator.cancel(
            expectedImportToken = TOKEN,
            expectedSessionId = SESSION_ID,
        )

        assertEquals(InstallCleanupResult.OwnerMismatch, mismatch)
        assertEquals(InstallCleanupResult.CleanupFailed, cleaned)
        assertEquals(listOf(SESSION_ID), fixture.gateway.abandonedSessions)
        assertEquals(FinalReceiptStage.READY, fixture.pendingStore.finalReceipt?.stage)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `pending prefs만 지워진 partial cleanup 실패도 cancel 성공으로 보고하지 않는다`() =
        runBlocking {
            val fixture = fixture()
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
            fixture.pendingStore.clearPreservingImportFailure = true

            assertEquals(
                InstallCleanupResult.CleanupFailed,
                fixture.coordinator.cancel(TOKEN, SESSION_ID),
            )
            assertEquals(pendingInstall(), fixture.pendingStore.record)
            assertEquals(
                "CLEANUP_FAILED",
                assertIs<InstallState.Failed>(fixture.coordinator.state.value).code,
            )

            fixture.pendingStore.clearPreservingImportFailure = false

            assertEquals(
                InstallCleanupResult.CleanupFailed,
                fixture.coordinator.cancel(TOKEN, SESSION_ID),
            )
            assertEquals(InstallState.Cancelled, fixture.coordinator.state.value)
        }

    @Test
    fun `write failure abandons its allocated session and clears pending import`() = runBlocking {
        val fixture = fixture()
        fixture.gateway.writeFailure = PackageInstallSessionException(
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
    fun `write cancellation keeps recovery ledger when allocated session cannot be abandoned`() = runBlocking {
        val fixture = fixture()
        fixture.gateway.writeFailure = CancellationException("write cancelled")
        fixture.gateway.abandonFailure = IllegalStateException("abandon failed")

        assertFailsWith<CancellationException> {
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        }

        assertEquals(
            InstallState.Failed("ABANDON_FAILED", "설치 session을 정리하지 못했습니다."),
            fixture.coordinator.state.value,
        )
        assertEquals(pendingInstall(), fixture.pendingStore.record)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `assignment failure retains recovered session id when abandon cannot be confirmed`() = runBlocking {
        val fixture = fixture()
        fixture.pendingStore.assignFailure = true
        fixture.pendingStore.recoverSessionSuccess = true
        fixture.gateway.abandonFailure = IllegalStateException("abandon failed")

        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        assertEquals(
            InstallState.Failed("ABANDON_FAILED", "설치 session을 정리하지 못했습니다."),
            fixture.coordinator.state.value,
        )
        assertEquals(pendingInstall(), fixture.pendingStore.record)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        assertTrue(fixture.gateway.writtenSessions.isEmpty())
    }

    @Test
    fun `never expires an unassigned recovery claim without proving its session is gone`() =
        runBlocking {
        val claim = pendingInstall().copy(
            sessionId = -1,
            startedAtEpochMillis = STARTED_AT,
        )
        val fixture = fixture(
            pending = claim,
            ownedSessions = listOf(SESSION_ID),
            now = STALE_NOW,
            missingObservedAt = STARTED_AT,
        )

        fixture.coordinator.restore()

        assertEquals(InstallState.WritingSession(-1), fixture.coordinator.state.value)
        assertEquals(claim, fixture.pendingStore.record)
        assertNull(fixture.pendingStore.missingObservedAt)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        assertTrue(fixture.gateway.abandonedSessions.isEmpty())
        }

    @Test
    fun `expires an unassigned claim only after empty owned sessions grace`() = runBlocking {
        val claim = pendingInstall().copy(
            sessionId = -1,
            startedAtEpochMillis = STARTED_AT,
        )
        val recent = fixture(
            pending = claim,
            ownedSessions = emptyList(),
            now = STALE_NOW,
            missingObservedAt = STALE_NOW - 1_000,
        )
        val expired = fixture(
            pending = claim,
            ownedSessions = emptyList(),
            now = STALE_NOW,
            missingObservedAt = STARTED_AT,
        )

        recent.coordinator.restore()
        expired.coordinator.restore()

        assertEquals(InstallState.WritingSession(-1), recent.coordinator.state.value)
        assertEquals(claim, recent.pendingStore.record)
        assertEquals(InstallState.Idle, expired.coordinator.state.value)
        assertNull(expired.pendingStore.record)
        assertEquals(listOf(TOKEN), expired.pendingStore.discardedTokens)
    }

    @Test
    fun `cleans a stale update claim despite an existing installed package`() = runBlocking {
        val fixture = fixture(
            pending = pendingInstall(),
            ownedSessions = listOf(SESSION_ID),
            now = STALE_NOW,
            missingObservedAt = STARTED_AT,
            clearMissingFailure = true,
        )

        fixture.coordinator.restore()

        assertEquals(
            InstallState.Failed(
                "MISSING_OBSERVATION_RESET_FAILED",
                "session 복구 시각을 초기화하지 못했습니다.",
            ),
            fixture.coordinator.state.value,
        )
        fixture.gateway.replaceOwnedSessions(emptyList())
        fixture.installedWrappers.installed = installedWrapper()
        val restored = coordinator(
            gateway = fixture.gateway,
            pendingStore = fixture.pendingStore,
            installedWrappers = fixture.installedWrappers,
            catalog = fixture.catalog,
            now = STALE_NOW,
        )
        restored.restore()

        assertEquals(InstallState.Idle, restored.state.value)
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `cancel clears a recreated unassigned claim after proving no session is owned`() = runBlocking {
        val claim = pendingInstall().copy(sessionId = -1)
        val fixture = fixture(
            pending = claim,
            ownedSessions = emptyList(),
        )
        fixture.installedWrappers.installed = installedWrapper()

        fixture.coordinator.cancel(TOKEN, null)

        assertEquals(InstallState.Cancelled, fixture.coordinator.state.value)
        assertNull(fixture.pendingStore.record)
        assertEquals(FinalReceiptStage.READY, fixture.pendingStore.finalReceipt?.stage)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        assertTrue(fixture.gateway.abandonedSessions.isEmpty())
    }

    @Test
    fun `write cancellation releases recovery ledger after abandon was confirmed`() = runBlocking {
        val fixture = fixture()
        fixture.gateway.writeFailure = CancellationException("write cancelled")

        assertFailsWith<CancellationException> {
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        }

        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `assignment failure abandons allocated session and clears claim before write`() = runBlocking {
        val fixture = fixture()
        fixture.pendingStore.assignFailure = true

        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        assertEquals(listOf(SESSION_ID), fixture.gateway.abandonedSessions)
        assertTrue(fixture.gateway.writtenSessions.isEmpty())
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `assignment disk failure clears the memory-updated session after abandon`() = runBlocking {
        val fixture = fixture()
        fixture.pendingStore.assignFailureAfterWrite = true

        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        assertEquals(listOf(SESSION_ID), fixture.gateway.abandonedSessions)
        assertTrue(fixture.gateway.writtenSessions.isEmpty())
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `restore binds an allocation interrupted before durable assignment`() = runBlocking {
        val claim = pendingInstall().copy(sessionId = -1)
        val fixture = fixture(
            pending = claim,
            ownedSessions = listOf(SESSION_ID),
        )
        fixture.pendingStore.recoverSessionSuccess = true

        fixture.coordinator.restore()

        assertEquals(pendingInstall(), fixture.pendingStore.record)
        assertEquals(
            InstallState.AwaitingUserApproval(SESSION_ID),
            fixture.coordinator.state.value,
        )
    }

    @Test
    fun `cancel binds and abandons a sole session allocated before assignment`() = runBlocking {
        val claim = pendingInstall().copy(sessionId = -1)
        val fixture = fixture(
            pending = claim,
            ownedSessions = listOf(SESSION_ID),
        )
        fixture.pendingStore.recoverSessionSuccess = true

        assertEquals(
            InstallCleanupResult.OwnerSessionAssigned(SESSION_ID),
            fixture.coordinator.cancel(TOKEN, null),
        )
        fixture.coordinator.cancel(TOKEN, SESSION_ID)

        assertEquals(InstallState.Cancelled, fixture.coordinator.state.value)
        assertEquals(listOf(SESSION_ID), fixture.gateway.abandonedSessions)
        assertNull(fixture.pendingStore.record)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `assignment recovery failure is explicit and retains the unassigned claim`() = runBlocking {
        val fixture = fixture()
        fixture.pendingStore.assignFailure = true
        fixture.gateway.abandonFailure = IllegalStateException("abandon failed")

        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        assertEquals(
            InstallState.Failed(
                "SESSION_RECOVERY_FAILED",
                "설치 session ID를 복구 저장하지 못했습니다.",
            ),
            fixture.coordinator.state.value,
        )
        assertEquals(pendingInstall().copy(sessionId = -1), fixture.pendingStore.record)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        assertTrue(fixture.gateway.writtenSessions.isEmpty())
    }

    @Test
    fun `create cancellation after allocation recovers and abandons before rethrow`() = runBlocking {
        val fixture = fixture()
        val cancellation = CancellationException("create cancelled")
        fixture.gateway.createFailureAfterAllocation = cancellation
        fixture.pendingStore.recoverSessionSuccess = true

        val thrown = assertFailsWith<CancellationException> {
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        }

        assertSame(cancellation, thrown)
        assertEquals(listOf(SESSION_ID), fixture.gateway.abandonedSessions)
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `persists allocated session id before writing apk bytes`() = runBlocking {
        val fixture = fixture()
        fixture.gateway.onWrite = { sessionId ->
            assertEquals(SESSION_ID, sessionId)
            assertEquals(pendingInstall(), fixture.pendingStore.record)
        }

        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        assertEquals(listOf(SESSION_ID), fixture.gateway.writtenSessions)
        assertEquals(listOf(SESSION_ID), fixture.gateway.committedSessions)
    }

    @Test
    fun `process death after durable assignment restores known id without starting write`() {
        val fixture = fixture()
        val processDeath = IllegalStateException("simulated process death")
        fixture.pendingStore.afterAssign = { throw processDeath }

        val thrown = assertFailsWith<IllegalStateException> {
            runBlocking {
                fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
            }
        }

        assertSame(processDeath, thrown)
        assertEquals(pendingInstall(), fixture.pendingStore.record)
        assertTrue(fixture.gateway.writtenSessions.isEmpty())
        val restored = coordinator(
            gateway = fixture.gateway,
            pendingStore = fixture.pendingStore,
        )
        assertEquals(InstallState.AwaitingUserApproval(SESSION_ID), restored.state.value)
    }

    @Test
    fun `write cancellation preserves original exception when atomic cleanup fails`() = runBlocking {
        val fixture = fixture()
        val cancellation = CancellationException("write cancelled")
        fixture.gateway.writeFailure = cancellation
        fixture.pendingStore.discardFailure = true

        val thrown = assertFailsWith<CancellationException> {
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        }

        assertSame(cancellation, thrown)
        assertEquals(
            InstallState.Failed("CLEANUP_FAILED", "설치 임시 데이터를 정리하지 못했습니다."),
            fixture.coordinator.state.value,
        )
        assertEquals(pendingInstall(), fixture.pendingStore.record)
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
    fun `system failure preserves raw proof and retained import for handoff`() = runBlocking {
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
        assertTrue(fixture.gateway.abandonedSessions.isEmpty())
        assertNull(fixture.pendingStore.record)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        fixture.acknowledgeTerminal(
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package"),
        )
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `pending clear failure preserves final raw pending and retained import`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        fixture.pendingStore.clearPreservingImportFailure = true

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Cancelled(SESSION_ID))

        assertEquals(
            InstallState.Failed("CLEANUP_FAILED", "설치 임시 데이터를 정리하지 못했습니다."),
            fixture.coordinator.state.value,
        )
        assertTrue(fixture.gateway.abandonedSessions.isEmpty())
        assertEquals(pendingInstall(), fixture.pendingStore.record)
        assertNotNull(fixture.pendingStore.finalReceipt)
        assertNotNull(fixture.pendingStore.terminalResult)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `acknowledge and finalize preserve retained import`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        val result = PackageInstallResult.Failure(
            sessionId = SESSION_ID,
            code = "SYSTEM_FAILURE",
            message = "installer rejected package",
        )

        fixture.coordinator.onPackageInstallerResult(result)

        val terminalState =
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package")
        assertEquals(terminalState, fixture.coordinator.state.value)
        assertNull(fixture.pendingStore.record)
        assertEquals(result, fixture.pendingStore.terminalResult?.result)
        assertTrue(fixture.coordinator.acknowledgeTerminal(TOKEN, SESSION_ID, terminalState))
        assertNotNull(fixture.pendingStore.terminalResult)

        assertTrue(fixture.coordinator.finalizeTerminal(TOKEN, SESSION_ID, terminalState))
        assertNull(fixture.pendingStore.terminalResult)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `malformed final marker는 finalize와 cancel에서 absent로 처리하지 않는다`() =
        runBlocking {
            val fixture = fixture()
            fixture.pendingStore.finalSnapshot = RecoveryRecordSnapshot.Invalid

            assertFalse(
                fixture.coordinator.finalizeTerminal(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedState = InstallState.Cancelled,
                ),
            )
            assertEquals(
                InstallCleanupResult.CleanupFailed,
                fixture.coordinator.cancel(TOKEN, SESSION_ID),
            )
            assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        }

    @Test
    fun `normal handoff marker가 남으면 cancel cleanup은 completed가 아니다`() =
        runBlocking {
            val handoff = NormalInstallHandoff(
                importToken = TOKEN,
                sessionId = SESSION_ID,
                packageName = PACKAGE_NAME,
                state = InstallState.Cancelled,
                stage = NormalHandoffStage.PREPARED,
            )
            listOf(
                RecoveryRecordSnapshot.Present(handoff),
                RecoveryRecordSnapshot.Invalid,
            ).forEach { snapshot ->
                val raw = PendingInstallResult(
                    result = PackageInstallResult.Cancelled(SESSION_ID),
                    terminalAtEpochMillis = FINISHED_AT,
                    packageName = PACKAGE_NAME,
                    importToken = TOKEN,
                )
                val fixture = fixture(
                    pending = pendingInstall(),
                    terminalResult = raw,
                ).apply {
                    pendingStore.normalSnapshot = snapshot
                    pendingStore.createFinalSuccessWithoutWrite = true
                    pendingStore.clearRawWhenPendingPreserved = true
                }

                assertEquals(
                    InstallCleanupResult.CleanupFailed,
                    fixture.coordinator.cancel(TOKEN, SESSION_ID),
                    "snapshot=$snapshot",
                )
                assertTrue(
                    fixture.pendingStore.discardedTokens.isEmpty(),
                    "snapshot=$snapshot",
                )
            }
        }

    @Test
    fun `restores completed success when terminal inbox deletion must be retried`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        fixture.installedWrappers.installed = installedWrapper()
        fixture.pendingStore.finalizeFinalFailure = true

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Success(SESSION_ID))

        val terminalState = InstallState.Installed(PACKAGE_NAME)
        assertEquals(terminalState, fixture.coordinator.state.value)
        assertNull(fixture.pendingStore.record)
        assertEquals(PACKAGE_NAME, fixture.pendingStore.terminalResult?.packageName)
        assertEquals(PACKAGE_NAME, fixture.catalog.replaced?.packageName)
        assertTrue(fixture.coordinator.acknowledgeTerminal(TOKEN, SESSION_ID, terminalState))
        assertNotNull(fixture.pendingStore.terminalResult)
        assertFalse(fixture.coordinator.finalizeTerminal(TOKEN, SESSION_ID, terminalState))
        assertNotNull(fixture.pendingStore.finalReceipt)

        fixture.pendingStore.finalizeFinalFailure = false
        assertTrue(fixture.coordinator.finalizeTerminal(TOKEN, SESSION_ID, terminalState))

        assertEquals(InstallState.Installed(PACKAGE_NAME), fixture.coordinator.state.value)
        assertNull(fixture.pendingStore.terminalResult)
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
        assertTrue(fixture.gateway.abandonedSessions.isEmpty())
        assertNull(fixture.catalog.replaced)
        assertNull(fixture.pendingStore.record)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `derived failures persist exact raw receipt before pending clear and survive recreation`() =
        runBlocking {
            val cases = listOf(
                "POST_INSTALL_VERIFICATION_FAILED" to
                    "설치된 래퍼를 검증할 수 없습니다.",
                "CATALOG_UPDATE_FAILED" to
                    "설치 catalog를 갱신하지 못했습니다.",
            )
            cases.forEach { (code, message) ->
                val fixture = fixture()
                fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
                if (code == "CATALOG_UPDATE_FAILED") {
                    fixture.installedWrappers.installed = installedWrapper()
                    fixture.catalog.replaceFailure = IllegalStateException("catalog failed")
                }

                fixture.coordinator.onPackageInstallerResult(
                    PackageInstallResult.Success(SESSION_ID),
                )

                val expected = InstallState.Failed(code, message)
                val receipt = requireNotNull(fixture.pendingStore.finalReceipt)
                assertEquals(TOKEN, receipt.importToken)
                assertEquals(SESSION_ID, receipt.sessionId)
                assertEquals(PACKAGE_NAME, receipt.packageName)
                assertEquals(expected, receipt.state)
                assertEquals(FinalReceiptStage.READY, receipt.stage)
                assertEquals(FinalReceiptOrigin.RAW_TERMINAL, receipt.origin)
                assertEquals(FINISHED_AT, receipt.rawTerminalAtEpochMillis)
                assertNull(fixture.pendingStore.record)
                assertTrue(fixture.pendingStore.discardedTokens.isEmpty())

                val recreated = coordinator(
                    gateway = fixture.gateway,
                    pendingStore = fixture.pendingStore,
                    installedWrappers = fixture.installedWrappers,
                    catalog = fixture.catalog,
                )
                recreated.restore(fixture.bundle)
                assertEquals(expected, recreated.state.value)
                assertEquals(receipt, fixture.pendingStore.finalReceipt)
                assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
            }
        }

    @Test
    fun `derived receipt commit failure preserves raw pending and retained import`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        fixture.pendingStore.createFinalFailure = true

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Success(SESSION_ID))

        assertNull(fixture.pendingStore.finalReceipt)
        assertEquals(pendingInstall(), fixture.pendingStore.record)
        assertIs<PackageInstallResult.Success>(fixture.pendingStore.terminalResult?.result)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `late raw after program cancel ready wins for success failure and cancelled`() = runBlocking {
        val results = listOf(
            PackageInstallResult.Success(SESSION_ID),
            PackageInstallResult.Failure(SESSION_ID, "LATE_FAILURE", "late failure"),
            PackageInstallResult.Cancelled(SESSION_ID),
        )
        results.forEach { result ->
            val fixture = fixture()
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
            if (result is PackageInstallResult.Success) {
                fixture.installedWrappers.installed = installedWrapper()
            }
            fixture.pendingStore.afterFinalReplace = { replacement ->
                if (replacement.origin == FinalReceiptOrigin.PROGRAM_CANCEL &&
                    replacement.stage == FinalReceiptStage.READY
                ) {
                    fixture.pendingStore.afterFinalReplace = null
                    assertTrue(
                        fixture.pendingStore.recordTerminalResult(
                            result,
                            FINISHED_AT + 1,
                        ),
                    )
                }
            }

            fixture.coordinator.cancel(TOKEN, SESSION_ID)

            val receipt = requireNotNull(fixture.pendingStore.finalReceipt)
            val expected = when (result) {
                is PackageInstallResult.Success -> InstallState.Installed(PACKAGE_NAME)
                is PackageInstallResult.Failure ->
                    InstallState.Failed(result.code, result.message)
                is PackageInstallResult.Cancelled -> InstallState.Cancelled
                is PackageInstallResult.PendingUserAction -> error("terminal만 사용")
            }
            assertEquals(expected, receipt.state)
            assertEquals(FinalReceiptOrigin.RAW_TERMINAL, receipt.origin)
            assertEquals(FINISHED_AT + 1, receipt.rawTerminalAtEpochMillis)
            assertEquals(FinalReceiptStage.READY, receipt.stage)
            assertEquals(expected, fixture.coordinator.state.value)
            assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        }
    }

    @Test
    fun `current raw ready proof rejects stale program cancel acknowledged and foreign owners`() =
        runBlocking {
            val results = listOf(
                PackageInstallResult.Success(SESSION_ID),
                PackageInstallResult.Failure(
                    SESSION_ID,
                    "LATE_FAILURE",
                    "late failure",
                ),
                PackageInstallResult.Cancelled(SESSION_ID),
            )
            results.forEach { result ->
                val fixture = fixture()
                fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
                if (result is PackageInstallResult.Success) {
                    fixture.installedWrappers.installed = installedWrapper()
                }
                fixture.coordinator.cancel(TOKEN, SESSION_ID)
                assertTrue(fixture.coordinator.onPackageInstallerResult(result))

                val expected = when (result) {
                    is PackageInstallResult.Success -> InstallState.Installed(PACKAGE_NAME)
                    is PackageInstallResult.Failure ->
                        InstallState.Failed(result.code, result.message)
                    is PackageInstallResult.Cancelled -> InstallState.Cancelled
                    is PackageInstallResult.PendingUserAction -> error("terminal만 사용")
                }
                val proof = fixture.coordinator.rawReadyTerminalForOwner(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedPackageName = PACKAGE_NAME,
                )
                assertEquals(expected, proof?.state)
                assertEquals(FinalReceiptOrigin.RAW_TERMINAL, proof?.origin)
                assertEquals(FinalReceiptStage.READY, proof?.stage)
                assertNull(
                    fixture.coordinator.rawReadyTerminalForOwner(
                        expectedImportToken =
                            "22222222-2222-2222-2222-222222222222",
                        expectedSessionId = SESSION_ID,
                        expectedPackageName = PACKAGE_NAME,
                    ),
                )
                assertNull(
                    fixture.coordinator.rawReadyTerminalForOwner(
                        expectedImportToken = TOKEN,
                        expectedSessionId = SESSION_ID + 1,
                        expectedPackageName = PACKAGE_NAME,
                    ),
                )
                assertNull(
                    fixture.coordinator.rawReadyTerminalForOwner(
                        expectedImportToken = TOKEN,
                        expectedSessionId = SESSION_ID,
                        expectedPackageName =
                            "dev.sixbridge.ext.${"b".repeat(32)}",
                    ),
                )

                assertFalse(
                    fixture.coordinator.onPackageInstallerResult(
                        PackageInstallResult.Failure(
                            SESSION_ID,
                            "DUPLICATE",
                            "must not replace",
                        ),
                    ),
                )
                assertEquals(
                    proof,
                    fixture.coordinator.rawReadyTerminalForOwner(
                        expectedImportToken = TOKEN,
                        expectedSessionId = SESSION_ID,
                        expectedPackageName = PACKAGE_NAME,
                    ),
                )

                assertTrue(
                    fixture.coordinator.acknowledgeTerminal(
                        expectedImportToken = TOKEN,
                        expectedSessionId = SESSION_ID,
                        expectedState = expected,
                    ),
                )
                assertNull(
                    fixture.coordinator.rawReadyTerminalForOwner(
                        expectedImportToken = TOKEN,
                        expectedSessionId = SESSION_ID,
                        expectedPackageName = PACKAGE_NAME,
                    ),
                )
            }

            val programCancel = fixture()
            programCancel.coordinator.request(programCancel.bundle, partialConfirmed = false)
            programCancel.coordinator.cancel(TOKEN, SESSION_ID)
            assertNull(
                programCancel.coordinator.rawReadyTerminalForOwner(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedPackageName = PACKAGE_NAME,
                ),
            )

            val tokenless = fixture(
                terminalResult = PendingInstallResult(
                    result = PackageInstallResult.Cancelled(SESSION_ID),
                    terminalAtEpochMillis = FINISHED_AT,
                    packageName = PACKAGE_NAME,
                    importToken = null,
                ),
            )
            assertNull(
                tokenless.coordinator.rawReadyTerminalForOwner(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedPackageName = PACKAGE_NAME,
                ),
            )
        }

    @Test
    fun `current final receipt proof distinguishes owned assigned mismatch and absent`() =
        runBlocking {
            val raw = fixture()
            raw.coordinator.request(raw.bundle, partialConfirmed = false)
            val failure = InstallState.Failed("LATE_FAILURE", "late failure")
            assertTrue(
                raw.coordinator.onPackageInstallerResult(
                    PackageInstallResult.Failure(
                        SESSION_ID,
                        failure.code,
                        failure.message,
                    ),
                ),
            )

            val ready = assertIs<FinalReceiptOwnerResult.Owned>(
                raw.coordinator.currentFinalReceiptForOwner(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedPackageName = PACKAGE_NAME,
                ),
            ).receipt
            assertEquals(failure, ready.state)
            assertEquals(FinalReceiptOrigin.RAW_TERMINAL, ready.origin)
            assertEquals(FinalReceiptStage.READY, ready.stage)
            assertEquals(
                FinalReceiptOwnerResult.OwnerSessionAssigned(SESSION_ID),
                raw.coordinator.currentFinalReceiptForOwner(
                    expectedImportToken = TOKEN,
                    expectedSessionId = null,
                    expectedPackageName = PACKAGE_NAME,
                ),
            )
            assertEquals(
                FinalReceiptOwnerResult.OwnerMismatch,
                raw.coordinator.currentFinalReceiptForOwner(
                    expectedImportToken =
                        "22222222-2222-2222-2222-222222222222",
                    expectedSessionId = SESSION_ID,
                    expectedPackageName = PACKAGE_NAME,
                ),
            )
            assertEquals(
                FinalReceiptOwnerResult.OwnerMismatch,
                raw.coordinator.currentFinalReceiptForOwner(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedPackageName = "dev.sixbridge.ext.${"b".repeat(32)}",
                ),
            )

            assertTrue(
                raw.coordinator.acknowledgeTerminal(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedState = failure,
                ),
            )
            val acknowledged = assertIs<FinalReceiptOwnerResult.Owned>(
                raw.coordinator.currentFinalReceiptForOwner(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedPackageName = PACKAGE_NAME,
                ),
            ).receipt
            assertEquals(FinalReceiptStage.ACKNOWLEDGED, acknowledged.stage)
            assertTrue(
                raw.coordinator.finalizeTerminal(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedState = failure,
                ),
            )
            assertEquals(
                FinalReceiptOwnerResult.Absent,
                raw.coordinator.currentFinalReceiptForOwner(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedPackageName = PACKAGE_NAME,
                ),
            )

            val program = fixture()
            program.coordinator.request(program.bundle, partialConfirmed = false)
            program.coordinator.cancel(TOKEN, SESSION_ID)
            val programReady = assertIs<FinalReceiptOwnerResult.Owned>(
                program.coordinator.currentFinalReceiptForOwner(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedPackageName = PACKAGE_NAME,
                ),
            ).receipt
            assertEquals(FinalReceiptOrigin.PROGRAM_CANCEL, programReady.origin)
            assertEquals(FinalReceiptStage.READY, programReady.stage)
            assertEquals(InstallState.Cancelled, programReady.state)
        }

    @Test
    fun `final absent proof는 raw와 pending도 실제 absent일 때만 성립한다`() =
        runBlocking {
            val tokenlessRaw = PendingInstallResult(
                result = PackageInstallResult.Cancelled(SESSION_ID),
                terminalAtEpochMillis = FINISHED_AT,
                packageName = PACKAGE_NAME,
                importToken = null,
            )
            val tokenedRaw = tokenlessRaw.copy(importToken = TOKEN)
            val foreignRaw = tokenlessRaw.copy(
                result = PackageInstallResult.Cancelled(77),
                importToken = "22222222-2222-2222-2222-222222222222",
            )
            val cases = listOf(
                "tokenless raw quarantine commit fault" to fixture(
                    terminalResult = tokenlessRaw,
                ).apply {
                    pendingStore.quarantineFailure = true
                },
                "tokened raw persist commit fault" to fixture(
                    pending = pendingInstall(),
                    terminalResult = tokenedRaw,
                ).apply {
                    pendingStore.createFinalFailure = true
                },
                "foreign raw" to fixture(
                    pending = pendingInstall(),
                    terminalResult = foreignRaw,
                ),
                "pending owner" to fixture(
                    pending = pendingInstall(),
                ),
                "malformed raw marker" to fixture().apply {
                    pendingStore.terminalSnapshot = RecoveryRecordSnapshot.Invalid
                },
                "malformed pending marker" to fixture().apply {
                    pendingStore.pendingSnapshot = RecoveryRecordSnapshot.Invalid
                },
            )

            cases.forEach { (name, fixture) ->
                assertEquals(
                    FinalReceiptOwnerResult.OwnerMismatch,
                    fixture.coordinator.currentFinalReceiptForOwner(
                        expectedImportToken = TOKEN,
                        expectedSessionId = SESSION_ID,
                        expectedPackageName = PACKAGE_NAME,
                    ),
                    name,
                )
                assertNull(fixture.pendingStore.finalReceipt, name)
                assertTrue(fixture.pendingStore.discardedTokens.isEmpty(), name)
            }
        }

    @Test
    fun `cancelling torn states require exact owned session and package before convergence`() =
        runBlocking {
            data class Case(
                val name: String,
                val pending: PendingInstall?,
                val configure: (FakePackageInstallGateway) -> Unit,
                val ready: Boolean,
                val abandoned: Boolean,
            )
            val cases = listOf(
                Case(
                    name = "abandon 직후 pending 유지",
                    pending = pendingInstall(),
                    configure = { it.replaceOwnedSessions(emptyList()) },
                    ready = true,
                    abandoned = false,
                ),
                Case(
                    name = "pending clear 직후 session absent",
                    pending = null,
                    configure = { it.replaceOwnedSessions(emptyList()) },
                    ready = true,
                    abandoned = false,
                ),
                Case(
                    name = "exact owned session",
                    pending = null,
                    configure = { it.replaceOwnedSessions(listOf(SESSION_ID)) },
                    ready = true,
                    abandoned = true,
                ),
                Case(
                    name = "same package foreign session",
                    pending = null,
                    configure = {
                        it.foreignSession = InstallSessionSnapshot(
                            SESSION_ID,
                            PACKAGE_NAME,
                            ownedByCaller = false,
                        )
                    },
                    ready = false,
                    abandoned = false,
                ),
                Case(
                    name = "different package reused session",
                    pending = null,
                    configure = {
                        it.foreignSession = InstallSessionSnapshot(
                            SESSION_ID,
                            "dev.sixbridge.ext.${"b".repeat(32)}",
                            ownedByCaller = true,
                        )
                    },
                    ready = false,
                    abandoned = false,
                ),
                Case(
                    name = "query failure",
                    pending = null,
                    configure = {
                        it.sessionSnapshotFailure = IllegalStateException("query failed")
                    },
                    ready = false,
                    abandoned = false,
                ),
            )
            cases.forEach { case ->
                val fixture = fixture(
                    pending = case.pending,
                    ownedSessions = emptyList(),
                )
                case.configure(fixture.gateway)
                fixture.pendingStore.finalReceipt = FinalInstallReceipt(
                    importToken = TOKEN,
                    sessionId = SESSION_ID,
                    packageName = PACKAGE_NAME,
                    state = InstallState.Cancelled,
                    stage = FinalReceiptStage.CANCELLING,
                    origin = FinalReceiptOrigin.PROGRAM_CANCEL,
                    rawTerminalAtEpochMillis = null,
                    terminalAtEpochMillis = FINISHED_AT,
                )

                fixture.coordinator.restore(fixture.bundle)

                assertEquals(
                    case.ready,
                    fixture.pendingStore.finalReceipt?.stage == FinalReceiptStage.READY,
                    case.name,
                )
                assertEquals(
                    case.abandoned,
                    SESSION_ID in fixture.gateway.abandonedSessions,
                    case.name,
                )
                if (!case.ready) {
                    assertEquals(
                        FinalReceiptStage.CANCELLING,
                        fixture.pendingStore.finalReceipt?.stage,
                        case.name,
                    )
                }
                assertTrue(fixture.pendingStore.discardedTokens.isEmpty(), case.name)
            }
        }

    @Test
    fun `nullable cancelling receipt converges without destructive session action`() = runBlocking {
        val fixture = fixture()
        fixture.pendingStore.finalReceipt = FinalInstallReceipt(
            importToken = TOKEN,
            sessionId = null,
            packageName = PACKAGE_NAME,
            state = InstallState.Cancelled,
            stage = FinalReceiptStage.CANCELLING,
            origin = FinalReceiptOrigin.PROGRAM_CANCEL,
            rawTerminalAtEpochMillis = null,
            terminalAtEpochMillis = FINISHED_AT,
        )

        fixture.coordinator.restore(fixture.bundle)

        assertEquals(FinalReceiptStage.READY, fixture.pendingStore.finalReceipt?.stage)
        assertEquals(InstallState.Cancelled, fixture.coordinator.state.value)
        assertTrue(fixture.gateway.abandonedSessions.isEmpty())
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `raw inbox commit wins acknowledge race against program cancel ready`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        fixture.coordinator.cancel(TOKEN, SESSION_ID)
        val programReady = requireNotNull(fixture.pendingStore.finalReceipt)
        assertEquals(FinalReceiptOrigin.PROGRAM_CANCEL, programReady.origin)
        fixture.pendingStore.beforeFinalAcknowledge = {
            assertTrue(
                fixture.pendingStore.recordTerminalResult(
                    PackageInstallResult.Failure(
                        SESSION_ID,
                        "RAW_WON",
                        "raw committed first",
                    ),
                    FINISHED_AT + 1,
                ),
            )
        }

        assertFalse(
            fixture.coordinator.acknowledgeTerminal(
                expectedImportToken = TOKEN,
                expectedSessionId = SESSION_ID,
                expectedState = InstallState.Cancelled,
            ),
        )
        fixture.coordinator.restore(fixture.bundle)

        val receipt = requireNotNull(fixture.pendingStore.finalReceipt)
        assertEquals(FinalReceiptOrigin.RAW_TERMINAL, receipt.origin)
        assertEquals(
            InstallState.Failed("RAW_WON", "raw committed first"),
            receipt.state,
        )
        assertEquals(FinalReceiptStage.READY, receipt.stage)
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
            assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        }
    }

    @Test
    fun `does not trust a success callback when extension id differs from the imported wrapper`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        fixture.installedWrappers.installed = installedWrapper().copy(extensionId = "b".repeat(32))

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Success(SESSION_ID))

        assertIs<InstallState.Failed>(fixture.coordinator.state.value)
        assertNull(fixture.catalog.replaced)
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
    fun `검증 bundle 없는 terminal success는 ledger와 inbox를 보존한다`() = runBlocking {
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
        assertEquals(pending, fixture.pendingStore.record)
        assertNotNull(fixture.pendingStore.terminalResult)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `process recreation rebinds the verified retained bundle before consuming terminal success`() =
        runBlocking {
            val terminal = PendingInstallResult(
                result = PackageInstallResult.Success(SESSION_ID),
                terminalAtEpochMillis = FINISHED_AT,
                packageName = PACKAGE_NAME,
                importToken = TOKEN,
            )
            val fixture = fixture(
                pending = pendingInstall(),
                terminalResult = terminal,
            )
            fixture.installedWrappers.installed = installedWrapper()

            fixture.coordinator.restore(fixture.bundle)

            assertEquals(InstallState.Installed(PACKAGE_NAME), fixture.coordinator.state.value)
            assertEquals(PACKAGE_NAME, fixture.catalog.replaced?.packageName)
            assertNull(fixture.pendingStore.record)
            fixture.acknowledgeTerminal(InstallState.Installed(PACKAGE_NAME))
            assertNull(fixture.pendingStore.terminalResult)
        }

    @Test
    fun `retained hash IO 실패는 restore를 중단하고 다음 시도에서 terminal을 처리한다`() =
        runBlocking {
            val terminal = PendingInstallResult(
                result = PackageInstallResult.Success(SESSION_ID),
                terminalAtEpochMillis = FINISHED_AT,
                packageName = PACKAGE_NAME,
                importToken = TOKEN,
            )
            val fixture = fixture(
                pending = pendingInstall(),
                terminalResult = terminal,
            )
            fixture.installedWrappers.installed = installedWrapper()
            var hashAttempts = 0
            val coordinator = InstallCoordinator(
                policy = InstallPolicy(),
                gateway = fixture.gateway,
                pendingStore = fixture.pendingStore,
                installedWrappers = fixture.installedWrappers,
                catalog = fixture.catalog,
                clock = { FINISHED_AT },
                retainedWrapperSha256 = {
                    hashAttempts += 1
                    if (hashAttempts == 1) throw IOException("transient hash read")
                    fixture.bundle.wrapperSha256
                },
            )

            assertFailsWith<IOException> {
                coordinator.restore(fixture.bundle)
            }
            assertEquals(pendingInstall(), fixture.pendingStore.record)
            assertEquals(terminal, fixture.pendingStore.terminalResult)

            coordinator.restore(fixture.bundle)

            assertEquals(InstallState.Installed(PACKAGE_NAME), coordinator.state.value)
            assertNull(fixture.pendingStore.record)
            assertTrue(
                coordinator.acknowledgeTerminal(
                    TOKEN,
                    SESSION_ID,
                    InstallState.Installed(PACKAGE_NAME),
                ),
            )
            assertTrue(
                coordinator.finalizeTerminal(
                    TOKEN,
                    SESSION_ID,
                    InstallState.Installed(PACKAGE_NAME),
                ),
            )
            assertNull(fixture.pendingStore.terminalResult)
        }

    @Test
    fun `첫 request hash IO 실패는 소유권을 풀어 다음 request가 성공한다`() =
        runBlocking {
            val fixture = fixture()
            var hashAttempts = 0
            val coordinator = InstallCoordinator(
                policy = InstallPolicy(),
                gateway = fixture.gateway,
                pendingStore = fixture.pendingStore,
                installedWrappers = fixture.installedWrappers,
                catalog = fixture.catalog,
                clock = { STARTED_AT },
                retainedWrapperSha256 = {
                    hashAttempts += 1
                    if (hashAttempts == 1) throw IOException("transient hash read")
                    fixture.bundle.wrapperSha256
                },
            )

            assertFailsWith<IOException> {
                coordinator.request(fixture.bundle, partialConfirmed = false)
            }
            assertNull(fixture.pendingStore.record)
            assertTrue(fixture.gateway.createdPackages.isEmpty())

            coordinator.request(fixture.bundle, partialConfirmed = false)

            assertEquals(2, hashAttempts)
            assertEquals(InstallState.AwaitingUserApproval(SESSION_ID), coordinator.state.value)
            assertEquals(listOf(SESSION_ID), fixture.gateway.committedSessions)
        }

    @Test
    fun `current recovery snapshot은 pending load 오류를 전파하고 다음 조회에서 복원한다`() =
        runBlocking {
            val pending = pendingInstall()
            val fixture = fixture(pending = pending, ownedSessions = listOf(SESSION_ID))
            fixture.pendingStore.loadFailure = IOException("transient pending read")

            assertFailsWith<IOException> {
                fixture.coordinator.currentRecoverySnapshot()
            }

            val snapshot = fixture.coordinator.currentRecoverySnapshot()
            assertEquals(pending, snapshot.pending)
            assertNull(snapshot.activeBundle)
        }

    @Test
    fun `keeps a recently completed session pending until its result can be delivered`() {
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
            missingObservedAt = STALE_NOW - 1_000,
        )

        assertEquals(InstallState.AwaitingUserApproval(SESSION_ID), fixture.coordinator.state.value)
        assertEquals(pending, fixture.pendingStore.record)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `cleans expired pending data when its session is no longer owned`() = runBlocking {
        val pending = PendingInstall(
            sessionId = SESSION_ID,
            packageName = PACKAGE_NAME,
            importToken = TOKEN,
            startedAtEpochMillis = STALE_NOW - 1_000,
        )
        val fixture = fixture(
            pending = pending,
            ownedSessions = emptyList(),
            now = STALE_NOW,
            missingObservedAt = STARTED_AT,
        )

        fixture.coordinator.restore()

        assertEquals(InstallState.Idle, fixture.coordinator.state.value)
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
    }

    @Test
    fun `terminal inbox wins when it arrives as stale cleanup begins`() = runBlocking {
        val fixture = fixture(
            pending = pendingInstall(),
            ownedSessions = emptyList(),
            now = STALE_NOW,
            missingObservedAt = STARTED_AT,
        )
        fixture.pendingStore.terminalBeforeStaleClear = PackageInstallResult.Failure(
            sessionId = SESSION_ID,
            code = "SYSTEM_FAILURE",
            message = "installer rejected package",
        )

        fixture.coordinator.restore()

        assertEquals(
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package"),
            fixture.coordinator.state.value,
        )
        assertNull(fixture.pendingStore.record)
        fixture.acknowledgeTerminal(
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package"),
        )
        assertNull(fixture.pendingStore.terminalResult)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
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

    @Test
    fun `restores a durable terminal result and deletes inbox only after processing`() = runBlocking {
        val pending = pendingInstall()
        val terminal = PendingInstallResult(
            result = PackageInstallResult.Failure(
                sessionId = SESSION_ID,
                code = "SYSTEM_FAILURE",
                message = "installer rejected package",
            ),
            terminalAtEpochMillis = FINISHED_AT,
            packageName = PACKAGE_NAME,
            importToken = TOKEN,
        )
        val fixture = fixture(
            pending = pending,
            ownedSessions = emptyList(),
            terminalResult = terminal,
        )

        fixture.coordinator.restore()

        assertEquals(
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package"),
            fixture.coordinator.state.value,
        )
        assertNull(fixture.pendingStore.record)
        fixture.acknowledgeTerminal(
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package"),
        )
        assertNull(fixture.pendingStore.terminalResult)
        assertNull(fixture.pendingStore.finalReceipt)
    }

    @Test
    fun `consumes terminal result already persisted by receiver in the current process`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        val result = PackageInstallResult.Failure(
            sessionId = SESSION_ID,
            code = "SYSTEM_FAILURE",
            message = "installer rejected package",
        )
        assertTrue(fixture.pendingStore.recordTerminalResult(result, FINISHED_AT))

        fixture.coordinator.onPackageInstallerResult(result)

        assertEquals(
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package"),
            fixture.coordinator.state.value,
        )
        assertNull(fixture.pendingStore.record)
        fixture.acknowledgeTerminal(
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package"),
        )
        assertNull(fixture.pendingStore.terminalResult)
    }

    @Test
    fun `keeps durable terminal inbox and ledger when success recovery is cancelled`() = runBlocking {
        val terminal = PendingInstallResult(
            result = PackageInstallResult.Success(SESSION_ID),
            terminalAtEpochMillis = FINISHED_AT,
            packageName = PACKAGE_NAME,
            importToken = TOKEN,
        )
        val fixture = fixture(
            pending = pendingInstall(),
            ownedSessions = emptyList(),
            terminalResult = terminal,
        )
        fixture.installedWrappers.findFailure = CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            fixture.coordinator.restore(fixture.bundle)
        }

        assertEquals(pendingInstall(), fixture.pendingStore.record)
        assertEquals(terminal, fixture.pendingStore.terminalResult)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `quarantines a foreign tokenless terminal without touching pending session`() = runBlocking {
        val foreign = PendingInstallResult(
            result = PackageInstallResult.Cancelled(SESSION_ID + 1),
            terminalAtEpochMillis = FINISHED_AT,
        )
        val fixture = fixture(
            pending = pendingInstall(),
            ownedSessions = listOf(SESSION_ID),
            terminalResult = foreign,
        )

        fixture.coordinator.restore()

        assertEquals(pendingInstall(), fixture.pendingStore.record)
        assertNull(fixture.pendingStore.terminalResult)
        assertEquals(listOf(foreign), fixture.pendingStore.clearedTerminalResults)
    }

    @Test
    fun `keeps the first durable terminal result and ignores duplicate callbacks`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        val first = PackageInstallResult.Failure(
            sessionId = SESSION_ID,
            code = "FIRST_FAILURE",
            message = "first",
        )

        assertTrue(fixture.pendingStore.recordTerminalResult(first, FINISHED_AT))
        assertEquals(
            false,
            fixture.pendingStore.recordTerminalResult(
                PackageInstallResult.Success(SESSION_ID),
                FINISHED_AT + 1,
            ),
        )
        fixture.coordinator.restore()

        assertEquals(InstallState.Failed("FIRST_FAILURE", "first"), fixture.coordinator.state.value)
        fixture.acknowledgeTerminal(InstallState.Failed("FIRST_FAILURE", "first"))
        assertNull(fixture.pendingStore.terminalResult)
        assertNull(fixture.catalog.replaced)
    }

    @Test
    fun `pending 없는 terminal은 immutable token과 nullable session으로 owner를 증명한다`() =
        runBlocking {
            val terminal = PendingInstallResult(
                result = PackageInstallResult.Cancelled(SESSION_ID),
                terminalAtEpochMillis = FINISHED_AT,
                packageName = PACKAGE_NAME,
                importToken = TOKEN,
            )
            val fixture = fixture(terminalResult = terminal)

            fixture.coordinator.restore()

            assertEquals(
                InstallTerminalOwnerResult.OwnerSessionAssigned(
                    sessionId = SESSION_ID,
                    state = InstallState.Cancelled,
                ),
                fixture.coordinator.terminalForOwner(TOKEN, null),
            )
            assertEquals(
                InstallTerminalOwnerResult.Owned(InstallState.Cancelled),
                fixture.coordinator.terminalForOwner(TOKEN, SESSION_ID),
            )
            assertEquals(
                InstallTerminalOwnerResult.OwnerMismatch,
                fixture.coordinator.terminalForOwner(TOKEN, SESSION_ID + 1),
            )
            assertEquals(
                InstallTerminalOwnerResult.OwnerMismatch,
                fixture.coordinator.terminalForOwner(
                    "22222222-2222-2222-2222-222222222222",
                    SESSION_ID,
                ),
            )
        }

    @Test
    fun `terminal receipt는 coordinator 재생성 뒤에도 exact owner 증명을 유지한다`() =
        runBlocking {
            val fixture = fixture()
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

            fixture.coordinator.onPackageInstallerResult(
                PackageInstallResult.Cancelled(SESSION_ID),
            )

            assertNull(fixture.pendingStore.record)
            assertNotNull(fixture.pendingStore.terminalResult)
            assertTrue(fixture.pendingStore.discardedTokens.isEmpty())

            val recreated = coordinator(
                gateway = fixture.gateway,
                pendingStore = fixture.pendingStore,
                installedWrappers = fixture.installedWrappers,
                catalog = fixture.catalog,
            )
            recreated.restore()

            assertEquals(InstallState.Cancelled, recreated.state.value)
            assertEquals(
                InstallTerminalOwnerResult.Owned(InstallState.Cancelled),
                recreated.terminalForOwner(TOKEN, SESSION_ID),
            )
            assertFalse(
                recreated.acknowledgeTerminal(
                    expectedImportToken =
                        "123e4567-e89b-12d3-a456-426614174099",
                    expectedSessionId = SESSION_ID,
                    expectedState = InstallState.Cancelled,
                ),
            )
            assertNotNull(fixture.pendingStore.terminalResult)
            assertTrue(fixture.pendingStore.discardedTokens.isEmpty())

            assertTrue(
                recreated.acknowledgeTerminal(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedState = InstallState.Cancelled,
                ),
            )
            assertNotNull(fixture.pendingStore.terminalResult)
            assertTrue(
                recreated.finalizeTerminal(
                    expectedImportToken = TOKEN,
                    expectedSessionId = SESSION_ID,
                    expectedState = InstallState.Cancelled,
                ),
            )
            assertNull(fixture.pendingStore.terminalResult)
            assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
        }

    @Test
    fun `legacy token 없는 terminal은 generated owner 증명을 fail closed한다`() = runBlocking {
        val terminal = PendingInstallResult(
            result = PackageInstallResult.Cancelled(SESSION_ID),
            terminalAtEpochMillis = FINISHED_AT,
            packageName = PACKAGE_NAME,
        )
        val fixture = fixture(terminalResult = terminal)

        fixture.coordinator.restore()

        assertEquals(
            InstallTerminalOwnerResult.OwnerMismatch,
            fixture.coordinator.terminalForOwner(TOKEN, null),
        )
        assertFalse(
            fixture.coordinator.acknowledgeTerminal(
                expectedImportToken = TOKEN,
                expectedSessionId = SESSION_ID,
                expectedState = InstallState.Cancelled,
            ),
        )
        assertNull(fixture.pendingStore.terminalResult)
        assertEquals(listOf(terminal), fixture.pendingStore.clearedTerminalResults)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `cancel is a no-op after terminal states`() = runBlocking {
        val idleFixture = fixture()
        assertEquals(
            InstallCleanupResult.OwnerMismatch,
            idleFixture.coordinator.cancel(TOKEN, null),
        )
        assertEquals(InstallState.Idle, idleFixture.coordinator.state.value)

        val installedFixture = fixture()
        installedFixture.coordinator.request(installedFixture.bundle, partialConfirmed = false)
        installedFixture.installedWrappers.installed = installedWrapper()
        installedFixture.coordinator.onPackageInstallerResult(PackageInstallResult.Success(SESSION_ID))
        assertEquals(
            InstallCleanupResult.CleanupFailed,
            installedFixture.coordinator.cancel(TOKEN, SESSION_ID),
        )
        assertEquals(InstallState.Installed(PACKAGE_NAME), installedFixture.coordinator.state.value)

        val cancelledFixture = fixture()
        cancelledFixture.coordinator.request(cancelledFixture.bundle, partialConfirmed = false)
        cancelledFixture.coordinator.onPackageInstallerResult(PackageInstallResult.Cancelled(SESSION_ID))
        assertEquals(
            InstallCleanupResult.CleanupFailed,
            cancelledFixture.coordinator.cancel(TOKEN, SESSION_ID),
        )
        assertEquals(InstallState.Cancelled, cancelledFixture.coordinator.state.value)

        val failedFixture = fixture(status = ContractCompatibilityStatus.BLOCKED)
        failedFixture.coordinator.request(failedFixture.bundle, partialConfirmed = false)
        val failed = failedFixture.coordinator.state.value
        assertEquals(
            InstallCleanupResult.OwnerMismatch,
            failedFixture.coordinator.cancel(TOKEN, null),
        )
        assertEquals(failed, failedFixture.coordinator.state.value)
    }

    @Test
    fun `cancel consumes durable success before abandoning the pending session`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        fixture.installedWrappers.installed = installedWrapper()
        assertTrue(
            fixture.pendingStore.recordTerminalResult(
                PackageInstallResult.Success(SESSION_ID),
                FINISHED_AT,
            ),
        )

        fixture.coordinator.cancel(TOKEN, SESSION_ID)

        assertEquals(InstallState.Installed(PACKAGE_NAME), fixture.coordinator.state.value)
        assertTrue(fixture.gateway.abandonedSessions.isEmpty())
        assertNull(fixture.pendingStore.record)
        fixture.acknowledgeTerminal(InstallState.Installed(PACKAGE_NAME))
        assertNull(fixture.pendingStore.terminalResult)
        assertEquals(PACKAGE_NAME, fixture.catalog.replaced?.packageName)
    }

    @Test
    fun `terminal success recorded during cancel abandon wins before cleanup`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        fixture.installedWrappers.installed = installedWrapper()
        fixture.gateway.onAbandon = {
            fixture.gateway.onAbandon = null
            assertTrue(
                fixture.pendingStore.recordTerminalResult(
                    PackageInstallResult.Success(SESSION_ID),
                    FINISHED_AT,
                ),
            )
        }

        fixture.coordinator.cancel(TOKEN, SESSION_ID)

        assertEquals(InstallState.Installed(PACKAGE_NAME), fixture.coordinator.state.value)
        assertEquals(PACKAGE_NAME, fixture.catalog.replaced?.packageName)
        assertNull(fixture.pendingStore.record)
        fixture.acknowledgeTerminal(InstallState.Installed(PACKAGE_NAME))
        assertNull(fixture.pendingStore.terminalResult)
    }

    @Test
    fun `terminal failure recorded during cancel abandon wins before cleanup`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        val result = PackageInstallResult.Failure(
            sessionId = SESSION_ID,
            code = "SYSTEM_FAILURE",
            message = "installer rejected package",
        )
        fixture.gateway.onAbandon = {
            fixture.gateway.onAbandon = null
            assertTrue(fixture.pendingStore.recordTerminalResult(result, FINISHED_AT))
        }

        fixture.coordinator.cancel(TOKEN, SESSION_ID)

        assertEquals(
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package"),
            fixture.coordinator.state.value,
        )
        assertNull(fixture.pendingStore.record)
        fixture.acknowledgeTerminal(
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package"),
        )
        assertNull(fixture.pendingStore.terminalResult)
    }

    @Test
    fun `does not trust an inbox-only success without a processed marker`() = runBlocking {
        val terminal = PendingInstallResult(
            result = PackageInstallResult.Success(SESSION_ID),
            terminalAtEpochMillis = FINISHED_AT,
            packageName = PACKAGE_NAME,
        )
        val fixture = fixture(terminalResult = terminal)

        fixture.coordinator.restore()

        assertEquals(InstallState.Idle, fixture.coordinator.state.value)
        assertNull(fixture.pendingStore.terminalResult)
        assertEquals(listOf(terminal), fixture.pendingStore.clearedTerminalResults)
        assertNull(fixture.catalog.replaced)

        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)

        assertEquals(
            InstallState.AwaitingUserApproval(SESSION_ID),
            fixture.coordinator.state.value,
        )
        assertEquals(TOKEN, fixture.pendingStore.record?.importToken)
    }

    @Test
    fun `raw terminal does not depend on abandon and preserves retained import`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        fixture.gateway.abandonFailure = IllegalStateException("abandon failed")

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Cancelled(SESSION_ID))

        assertEquals(InstallState.Cancelled, fixture.coordinator.state.value)
        assertNull(fixture.pendingStore.record)
        assertIs<PackageInstallResult.Cancelled>(fixture.pendingStore.terminalResult?.result)
        assertNotNull(fixture.pendingStore.finalReceipt)
        assertTrue(fixture.pendingStore.discardedTokens.isEmpty())
    }

    @Test
    fun `two coordinators sharing a store create only one package installer session`() = runBlocking {
        val backend = SharedFakePendingBackend()
        val firstStore = SharedAtomicFakePendingInstallStore(backend)
        val secondStore = SharedAtomicFakePendingInstallStore(backend)
        val sharedGateway = FakePackageInstallGateway(true, emptyList())
        val first = coordinator(sharedGateway, firstStore)
        val second = coordinator(sharedGateway, secondStore)
        backend.loadBarrier = CyclicBarrier(2)
        backend.barrierLoadsRemaining = 2
        val firstBundle = importedBundle(ContractCompatibilityStatus.COMPATIBLE, TOKEN)
        val secondToken = "123e4567-e89b-12d3-a456-426614174001"
        val secondBundle = importedBundle(ContractCompatibilityStatus.COMPATIBLE, secondToken)

        listOf(
            async(Dispatchers.Default) { first.request(firstBundle, partialConfirmed = false) },
            async(Dispatchers.Default) { second.request(secondBundle, partialConfirmed = false) },
        ).awaitAll()

        assertEquals(1, sharedGateway.createdPackages.size)
        assertEquals(1, sharedGateway.committedSessions.size)
        assertTrue(backend.record?.importToken in setOf(TOKEN, secondToken))
        assertEquals(1, backend.discardedTokens.size)
    }

    private fun fixture(
        canInstallPackages: Boolean = true,
        status: ContractCompatibilityStatus = ContractCompatibilityStatus.COMPATIBLE,
        pending: PendingInstall? = null,
        ownedSessions: List<Int> = emptyList(),
        now: Long = FINISHED_AT,
        terminalResult: PendingInstallResult? = null,
        missingObservedAt: Long? = null,
        clearMissingFailure: Boolean = false,
    ): Fixture {
        val gateway = FakePackageInstallGateway(canInstallPackages, ownedSessions)
        val pendingStore = FakePendingInstallStore(pending).apply {
            this.terminalResult = terminalResult
            this.missingObservedAt = missingObservedAt
            this.clearMissingFailure = clearMissingFailure
        }
        val installedWrappers = FakeInstalledWrapperRepository()
        val catalog = FakeManagerCatalog()
        val bundle = importedBundle(status)
        return Fixture(
            coordinator = coordinator(gateway, pendingStore, installedWrappers, catalog, now),
            gateway = gateway,
            pendingStore = pendingStore,
            installedWrappers = installedWrappers,
            catalog = catalog,
            bundle = bundle,
        )
    }

    private fun coordinator(
        gateway: FakePackageInstallGateway,
        pendingStore: PendingInstallStore,
        installedWrappers: FakeInstalledWrapperRepository = FakeInstalledWrapperRepository(),
        catalog: FakeManagerCatalog = FakeManagerCatalog(),
        now: Long = FINISHED_AT,
    ) = InstallCoordinator(
        policy = InstallPolicy(),
        gateway = gateway,
        pendingStore = pendingStore,
        installedWrappers = installedWrappers,
        catalog = catalog,
        clock = {
            if (pendingStore is FakePendingInstallStore && pendingStore.record != null) now
            else STARTED_AT
        },
    )

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

    private fun pendingInstall() = PendingInstall(
        sessionId = SESSION_ID,
        packageName = PACKAGE_NAME,
        importToken = TOKEN,
        startedAtEpochMillis = STARTED_AT,
    )

    private suspend fun Fixture.acknowledgeTerminal(state: InstallState) {
        assertTrue(
            coordinator.acknowledgeTerminal(
                expectedImportToken = TOKEN,
                expectedSessionId = SESSION_ID,
                expectedState = state,
            ),
        )
        assertTrue(
            coordinator.finalizeTerminal(
                expectedImportToken = TOKEN,
                expectedSessionId = SESSION_ID,
                expectedState = state,
            ),
        )
    }

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
        val writtenSessions = mutableListOf<Int>()
        val committedSessions = mutableListOf<Int>()
        val abandonedSessions = mutableListOf<Int>()
        var createFailure: Throwable? = null
        var createFailureAfterAllocation: Throwable? = null
        var writeFailure: Throwable? = null
        var commitFailure: RuntimeException? = null
        var abandonFailure: RuntimeException? = null
        var onAbandon: (() -> Unit)? = null
        var onWrite: ((Int) -> Unit)? = null
        private val owned = ownedSessions.toMutableList()
        private val ownedPackages = ownedSessions.associateWith { PACKAGE_NAME }.toMutableMap()
        var sessionSnapshotFailure: Throwable? = null
        var foreignSession: InstallSessionSnapshot? = null

        override fun canRequestPackageInstalls(): Boolean = canInstallPackages

        override fun unknownSourcesSettingsIntent(): Intent =
            throw UnsupportedOperationException("not called by coordinator")

        override suspend fun createSession(packageName: String): Int {
            createFailure?.let { throw it }
            createdPackages += packageName
            owned += SESSION_ID
            ownedPackages[SESSION_ID] = packageName
            createFailureAfterAllocation?.let { throw it }
            return SESSION_ID
        }

        override suspend fun writeSession(sessionId: Int, apk: File) {
            onWrite?.invoke(sessionId)
            writeFailure?.let { throw it }
            writtenSessions += sessionId
        }

        override fun commit(sessionId: Int) {
            commitFailure?.let { throw it }
            committedSessions += sessionId
        }

        override fun abandon(sessionId: Int) {
            abandonFailure?.let { throw it }
            onAbandon?.invoke()
            abandonedSessions += sessionId
            owned -= sessionId
            ownedPackages.remove(sessionId)
        }

        override fun ownedSessions(): List<Int> = owned.toList()

        override fun sessionSnapshot(sessionId: Int): InstallSessionSnapshot? {
            sessionSnapshotFailure?.let { throw it }
            foreignSession?.takeIf { it.sessionId == sessionId }?.let { return it }
            return sessionId.takeIf(owned::contains)?.let {
                InstallSessionSnapshot(
                    sessionId = it,
                    packageName = ownedPackages[it],
                    ownedByCaller = true,
                )
            }
        }

        fun replaceOwnedSessions(sessionIds: List<Int>) {
            owned.clear()
            owned += sessionIds
            ownedPackages.clear()
            sessionIds.forEach { ownedPackages[it] = PACKAGE_NAME }
        }
    }

    private class FakePendingInstallStore(
        initial: PendingInstall?,
    ) : PendingInstallStore {
        var record: PendingInstall? = initial
        var terminalResult: PendingInstallResult? = null
        var pendingSnapshot: RecoveryRecordSnapshot<PendingInstall>? = null
        var terminalSnapshot: RecoveryRecordSnapshot<PendingInstallResult>? = null
        var finalReceipt: FinalInstallReceipt? = null
        var finalSnapshot: RecoveryRecordSnapshot<FinalInstallReceipt>? = null
        var normalHandoff: NormalInstallHandoff? = null
        var normalSnapshot: RecoveryRecordSnapshot<NormalInstallHandoff>? = null
        var missingObservedAt: Long? = null
        val discardedTokens = mutableListOf<String>()
        val clearedTerminalResults = mutableListOf<PendingInstallResult>()
        var clearFailure = false
        var assignFailure = false
        var assignFailureAfterWrite = false
        var recoverSessionSuccess = false
        var afterAssign: (() -> Unit)? = null
        var discardFailure = false
        var clearPendingAfterFailure = false
        var clearTerminalFailure = false
        var quarantineFailure = false
        var clearMissingFailure = false
        var terminalBeforeStaleClear: PackageInstallResult? = null
        var loadBarrier: CyclicBarrier? = null
        var confirmationDeliveredSessionId: Int? = null
        var confirmationMarkFailure = false
        var loadFailure: Throwable? = null
        var createFinalFailure = false
        var createFinalSuccessWithoutWrite = false
        var replaceFinalFailure = false
        var acknowledgeFinalFailure = false
        var finalizeFinalFailure = false
        var clearPreservingImportFailure = false
        var clearRawWhenPendingPreserved = false
        var afterFinalReplace: ((FinalInstallReceipt) -> Unit)? = null
        var beforeFinalAcknowledge: (() -> Unit)? = null
        private var barrierLoadsRemaining = 2

        override fun load(): PendingInstall? {
            loadFailure?.let { error ->
                loadFailure = null
                throw error
            }
            val barrier = synchronized(this) {
                loadBarrier?.takeIf { barrierLoadsRemaining-- > 0 }
            }
            barrier?.await()
            return synchronized(this) { record }
        }

        override fun pendingInstallSnapshot(): RecoveryRecordSnapshot<PendingInstall> =
            synchronized(this) {
                pendingSnapshot ?: super.pendingInstallSnapshot()
            }

        override fun tryClaim(
            packageName: String,
            importToken: String,
            startedAtEpochMillis: Long,
        ): PendingInstall? = synchronized(this) {
            if (record != null || terminalResult != null || finalReceipt != null) {
                return@synchronized null
            }
            confirmationDeliveredSessionId = null
            PendingInstall(
                sessionId = -1,
                packageName = packageName,
                importToken = importToken,
                startedAtEpochMillis = startedAtEpochMillis,
            ).also { record = it }
        }

        override fun assignSession(claim: PendingInstall, sessionId: Int): PendingInstall? =
            synchronized(this) {
                if (assignFailure) return@synchronized null
                if (record != claim) return@synchronized null
                confirmationDeliveredSessionId = null
                val pending = claim.copy(sessionId = sessionId).also { record = it }
                if (assignFailureAfterWrite) return@synchronized null
                afterAssign?.invoke()
                pending
            }

        override fun recoverSession(
            claim: PendingInstall,
            sessionId: Int,
        ): PendingInstall? = synchronized(this) {
            val recovered = claim.copy(sessionId = sessionId)
            if (!recoverSessionSuccess || record !in setOf(claim, recovered)) {
                return@synchronized null
            }
            recovered.also { record = it }
        }

        override fun clear(expected: PendingInstall): Boolean = synchronized(this) {
            if (clearFailure) throw IllegalStateException("clear failed")
            if (record != expected) return@synchronized false
            record = null
            confirmationDeliveredSessionId = null
            missingObservedAt = null
            true
        }

        override fun clearWithoutTerminal(
            expected: PendingInstall,
        ): PendingCleanupResult = synchronized(this) {
            terminalBeforeStaleClear?.let { result ->
                terminalBeforeStaleClear = null
                terminalResult = PendingInstallResult(
                    result = result,
                    terminalAtEpochMillis = FINISHED_AT,
                    packageName = record?.packageName.orEmpty(),
                    importToken = record?.importToken,
                )
            }
            if (record != expected || discardFailure) {
                return@synchronized PendingCleanupResult.Failed
            }
            if (terminalResult != null) {
                return@synchronized PendingCleanupResult.TerminalAvailable
            }
            discardedTokens += expected.importToken
            record = null
            confirmationDeliveredSessionId = null
            missingObservedAt = null
            if (clearPendingAfterFailure) return@synchronized PendingCleanupResult.Failed
            PendingCleanupResult.Cleared
        }

        override fun discardImport(importToken: String) {
            synchronized(this) {
                if (discardFailure) throw IllegalStateException("discard failed")
                discardedTokens += importToken
            }
        }

        override fun loadTerminalResult(): PendingInstallResult? = synchronized(this) {
            terminalResult
        }

        override fun terminalResultSnapshot(): RecoveryRecordSnapshot<PendingInstallResult> =
            synchronized(this) {
                terminalSnapshot ?: super.terminalResultSnapshot()
            }

        override fun markConfirmationDelivered(sessionId: Int): Boolean = synchronized(this) {
            if (record?.sessionId != sessionId || confirmationDeliveredSessionId == sessionId) {
                return@synchronized false
            }
            if (confirmationMarkFailure) return@synchronized false
            confirmationDeliveredSessionId = sessionId
            true
        }

        override fun isConfirmationDelivered(sessionId: Int): Boolean = synchronized(this) {
            record?.sessionId == sessionId && confirmationDeliveredSessionId == sessionId
        }

        override fun recordTerminalResult(
            result: PackageInstallResult,
            terminalAtEpochMillis: Long,
        ): Boolean = synchronized(this) {
            val lateCancel = finalReceipt?.takeIf {
                record == null &&
                    it.origin == FinalReceiptOrigin.PROGRAM_CANCEL &&
                    it.stage != FinalReceiptStage.ACKNOWLEDGED &&
                    it.sessionId == result.sessionId
            }
            if (result is PackageInstallResult.PendingUserAction ||
                record?.sessionId != result.sessionId && lateCancel == null
            ) {
                return@synchronized false
            }
            val pendingResult = PendingInstallResult(
                result = result,
                terminalAtEpochMillis = terminalAtEpochMillis,
                packageName = record?.packageName ?: requireNotNull(lateCancel).packageName,
                importToken = record?.importToken ?: requireNotNull(lateCancel).importToken,
            )
            val existing = terminalResult
            if (existing != null) return@synchronized false
            confirmationDeliveredSessionId = null
            terminalResult = pendingResult
            true
        }

        override fun markTerminalProcessed(
            expected: PendingInstallResult,
        ): PendingInstallResult? = synchronized(this) {
            if (terminalResult != expected) return@synchronized null
            expected.copy(processed = true).also { terminalResult = it }
        }

        override fun clearTerminalResult(expected: PendingInstallResult): Boolean =
            synchronized(this) {
                if (clearTerminalFailure) return@synchronized false
                if (terminalResult != expected) return@synchronized false
                clearedTerminalResults += expected
                terminalResult = null
                confirmationDeliveredSessionId = null
                true
            }

        override fun quarantineTerminalResult(
            expectedRaw: PendingInstallResult,
            expectedPending: PendingInstall?,
            expectedFinal: FinalInstallReceipt?,
        ): Boolean = synchronized(this) {
            if (quarantineFailure ||
                expectedRaw.importToken != null ||
                terminalResult != expectedRaw ||
                record != expectedPending ||
                finalReceipt != expectedFinal
            ) {
                return@synchronized false
            }
            clearedTerminalResults += expectedRaw
            terminalResult = null
            confirmationDeliveredSessionId = null
            true
        }

        override fun loadFinalReceipt(): FinalInstallReceipt? = synchronized(this) {
            finalReceipt
        }

        override fun finalReceiptSnapshot(): RecoveryRecordSnapshot<FinalInstallReceipt> =
            synchronized(this) {
                finalSnapshot ?: super.finalReceiptSnapshot()
            }

        override fun createFinalReceipt(
            expectedPending: PendingInstall?,
            expectedRaw: PendingInstallResult?,
            receipt: FinalInstallReceipt,
        ): Boolean = synchronized(this) {
            if (createFinalFailure ||
                record != expectedPending ||
                terminalResult != expectedRaw ||
                finalReceipt != null
            ) {
                return@synchronized false
            }
            if (createFinalSuccessWithoutWrite) return@synchronized true
            finalReceipt = receipt
            true
        }

        override fun replaceFinalReceipt(
            expected: FinalInstallReceipt,
            expectedRaw: PendingInstallResult?,
            replacement: FinalInstallReceipt,
        ): Boolean = synchronized(this) {
            if (replaceFinalFailure ||
                finalReceipt != expected ||
                terminalResult != expectedRaw
            ) {
                return@synchronized false
            }
            finalReceipt = replacement
            afterFinalReplace?.invoke(replacement)
            true
        }

        override fun acknowledgeFinalReceipt(
            expected: FinalInstallReceipt,
            expectedRaw: PendingInstallResult?,
        ): FinalInstallReceipt? = synchronized(this) {
            beforeFinalAcknowledge?.invoke()
            beforeFinalAcknowledge = null
            if (acknowledgeFinalFailure ||
                finalReceipt != expected ||
                terminalResult != expectedRaw ||
                expected.stage != FinalReceiptStage.READY
            ) {
                return@synchronized null
            }
            expected.copy(stage = FinalReceiptStage.ACKNOWLEDGED)
                .also { finalReceipt = it }
        }

        override fun finalizeFinalReceipt(expected: FinalInstallReceipt): Boolean =
            synchronized(this) {
                if (finalizeFinalFailure) return@synchronized false
                val current = finalReceipt ?: return@synchronized true
                if (current != expected ||
                    current.stage != FinalReceiptStage.ACKNOWLEDGED
                ) {
                    return@synchronized false
                }
                finalReceipt = null
                terminalResult = null
                true
            }

        override fun clearPendingPreservingImport(expected: PendingInstall): Boolean =
            synchronized(this) {
                if (clearPreservingImportFailure || record != expected) {
                    return@synchronized false
                }
                record = null
                if (clearRawWhenPendingPreserved) terminalResult = null
                true
            }

        override fun loadNormalHandoff(): NormalInstallHandoff? = synchronized(this) {
            normalHandoff
        }

        override fun normalHandoffSnapshot(): RecoveryRecordSnapshot<NormalInstallHandoff> =
            synchronized(this) {
                normalSnapshot ?: super.normalHandoffSnapshot()
            }

        override fun compareAndSetNormalHandoff(
            expected: NormalInstallHandoff?,
            replacement: NormalInstallHandoff?,
        ): Boolean = synchronized(this) {
            if (normalHandoff != expected) return@synchronized false
            normalHandoff = replacement
            true
        }

        override fun firstSessionMissingAt(
            pending: PendingInstall,
            observedAtEpochMillis: Long,
        ): Long = synchronized(this) {
            missingObservedAt ?: observedAtEpochMillis.also { missingObservedAt = it }
        }

        override fun clearSessionMissingObservation(pending: PendingInstall): Boolean =
            synchronized(this) {
                if (clearMissingFailure) {
                    return@synchronized false
                }
                missingObservedAt = null
                true
            }
    }

    private class SharedFakePendingBackend {
        var record: PendingInstall? = null
        var loadBarrier: CyclicBarrier? = null
        var barrierLoadsRemaining = 2
        val discardedTokens = mutableListOf<String>()
    }

    private class SharedAtomicFakePendingInstallStore(
        private val backend: SharedFakePendingBackend,
    ) : PendingInstallStore {
        override fun load(): PendingInstall? {
            val barrier = synchronized(backend) {
                backend.loadBarrier?.takeIf { backend.barrierLoadsRemaining-- > 0 }
            }
            barrier?.await()
            return synchronized(backend) { backend.record }
        }

        override fun tryClaim(
            packageName: String,
            importToken: String,
            startedAtEpochMillis: Long,
        ): PendingInstall? = synchronized(backend) {
            if (backend.record != null) return@synchronized null
            PendingInstall(-1, packageName, importToken, startedAtEpochMillis)
                .also { backend.record = it }
        }

        override fun assignSession(claim: PendingInstall, sessionId: Int): PendingInstall? =
            synchronized(backend) {
                if (backend.record != claim) return@synchronized null
                claim.copy(sessionId = sessionId).also { backend.record = it }
            }

        override fun recoverSession(
            claim: PendingInstall,
            sessionId: Int,
        ): PendingInstall? = null

        override fun clear(expected: PendingInstall): Boolean = synchronized(backend) {
            if (backend.record != expected) return@synchronized false
            backend.record = null
            true
        }

        override fun clearWithoutTerminal(
            expected: PendingInstall,
        ): PendingCleanupResult = synchronized(backend) {
            if (backend.record != expected) return@synchronized PendingCleanupResult.Failed
            backend.discardedTokens += expected.importToken
            backend.record = null
            PendingCleanupResult.Cleared
        }

        override fun discardImport(importToken: String) {
            synchronized(backend) {
                backend.discardedTokens += importToken
            }
        }

        override fun loadTerminalResult(): PendingInstallResult? = null

        override fun recordTerminalResult(
            result: PackageInstallResult,
            terminalAtEpochMillis: Long,
        ): Boolean = false

        override fun markTerminalProcessed(
            expected: PendingInstallResult,
        ): PendingInstallResult? = null

        override fun clearTerminalResult(expected: PendingInstallResult): Boolean = false

        override fun firstSessionMissingAt(
            pending: PendingInstall,
            observedAtEpochMillis: Long,
        ): Long = observedAtEpochMillis

        override fun clearSessionMissingObservation(pending: PendingInstall): Boolean = true
    }

    private class FakeInstalledWrapperRepository : InstalledWrapperRepository {
        var installed: InstalledWrapper? = null
        var findFailure: Throwable? = null

        override suspend fun findAll(): List<InstalledWrapper> = listOfNotNull(installed)

        override suspend fun find(packageName: String): InstalledWrapper? {
            findFailure?.let { throw it }
            return installed?.takeIf { it.packageName == packageName }
        }
    }

    @Test
    fun `fake catalog ensure는 변경된 identity를 unknown baseline으로 교체한다`() = runBlocking {
        val current = CatalogRecord(
            packageName = PACKAGE_NAME,
            extensionId = EXTENSION_ID,
            name = "Verified",
            iconPng = byteArrayOf(1),
            installedVersionCode = VERSION_CODE,
            signerSha256 = SIGNER,
            compatibilityStatus = "COMPATIBLE",
            reportJson = "{\"status\":\"COMPATIBLE\"}",
            lastVerifiedAtEpochMillis = FINISHED_AT,
        )
        val discovered = current.copy(
            signerSha256 = "2".repeat(64),
            name = "Discovered",
            iconPng = null,
            compatibilityStatus = "UNKNOWN",
            reportJson = null,
            lastVerifiedAtEpochMillis = 0,
        )
        val catalog = FakeManagerCatalog().also { it.replaceVerified(current) }

        val ensured = catalog.ensureDiscoveredIdentity(discovered)

        assertEquals(discovered, ensured)
        assertEquals(discovered, catalog.replaced)
    }

    private class FakeManagerCatalog : ManagerCatalog {
        var replaced: CatalogRecord? = null
        var replaceFailure: Throwable? = null

        override fun observe(): Flow<List<CatalogRecord>> = flowOf(listOfNotNull(replaced))

        override suspend fun replaceVerified(record: CatalogRecord) {
            replaceFailure?.let { throw it }
            replaced = record
        }

        override suspend fun ensureDiscoveredIdentity(discovered: CatalogRecord): CatalogRecord {
            val current = replaced
            if (current != null && current.identity() == discovered.identity()) return current
            replaced = discovered
            return discovered
        }

        override suspend fun persistIconIfIdentity(
            identity: CatalogIdentity,
            iconPng: ByteArray,
        ): CatalogRecord? = null

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
