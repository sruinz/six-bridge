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
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
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
    fun `session 생성 전 cleanup 실패는 bundle 소유권을 유지해 cancel 재시도를 허용한다`() =
        runBlocking {
            val fixture = fixture(canInstallPackages = false)
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
            fixture.pendingStore.discardFailure = true

            assertTrue(!fixture.coordinator.cancel())
            assertEquals("CLEANUP_FAILED", assertIs<InstallState.Failed>(fixture.coordinator.state.value).code)

            fixture.pendingStore.discardFailure = false

            assertTrue(fixture.coordinator.cancel())
            assertEquals(InstallState.Cancelled, fixture.coordinator.state.value)
            assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
        }

    @Test
    fun `pending prefs만 지워진 partial cleanup 실패도 cancel 성공으로 보고하지 않는다`() =
        runBlocking {
            val fixture = fixture()
            fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
            fixture.pendingStore.clearPendingAfterFailure = true

            assertTrue(!fixture.coordinator.cancel())
            assertNull(fixture.pendingStore.record)
            assertEquals(
                "CLEANUP_FAILED",
                assertIs<InstallState.Failed>(fixture.coordinator.state.value).code,
            )

            fixture.pendingStore.clearPendingAfterFailure = false

            assertTrue(fixture.coordinator.cancel())
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

        fixture.coordinator.cancel()

        assertEquals(InstallState.Cancelled, fixture.coordinator.state.value)
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
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

        fixture.coordinator.cancel()

        assertEquals(InstallState.Cancelled, fixture.coordinator.state.value)
        assertEquals(listOf(SESSION_ID), fixture.gateway.abandonedSessions)
        assertNull(fixture.pendingStore.record)
        assertEquals(listOf(TOKEN), fixture.pendingStore.discardedTokens)
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
    fun `retries terminal cleanup without losing ledger when temp deletion fails`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        fixture.pendingStore.discardFailure = true
        val result = PackageInstallResult.Failure(
            sessionId = SESSION_ID,
            code = "SYSTEM_FAILURE",
            message = "installer rejected package",
        )

        fixture.coordinator.onPackageInstallerResult(result)

        assertEquals(
            InstallState.Failed("CLEANUP_FAILED", "설치 임시 데이터를 정리하지 못했습니다."),
            fixture.coordinator.state.value,
        )
        assertEquals(pendingInstall(), fixture.pendingStore.record)
        assertEquals(result, fixture.pendingStore.terminalResult?.result)

        fixture.pendingStore.discardFailure = false
        fixture.coordinator.restore()

        assertEquals(
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package"),
            fixture.coordinator.state.value,
        )
        assertNull(fixture.pendingStore.record)
        assertNull(fixture.pendingStore.terminalResult)
    }

    @Test
    fun `restores completed success when terminal inbox deletion must be retried`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        fixture.installedWrappers.installed = installedWrapper()
        fixture.pendingStore.clearTerminalFailure = true

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Success(SESSION_ID))

        assertEquals(
            InstallState.Failed("CLEANUP_FAILED", "설치 임시 데이터를 정리하지 못했습니다."),
            fixture.coordinator.state.value,
        )
        assertNull(fixture.pendingStore.record)
        assertEquals(PACKAGE_NAME, fixture.pendingStore.terminalResult?.packageName)
        assertEquals(PACKAGE_NAME, fixture.catalog.replaced?.packageName)
        assertTrue(!fixture.coordinator.cancel())
        assertNotNull(fixture.pendingStore.terminalResult)

        fixture.pendingStore.clearTerminalFailure = false
        assertTrue(fixture.coordinator.cancel())

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
            assertNull(fixture.pendingStore.terminalResult)
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
        assertNull(fixture.pendingStore.terminalResult)
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
        assertNull(fixture.pendingStore.terminalResult)
        assertEquals(listOf(terminal), fixture.pendingStore.clearedTerminalResults)
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
        assertNull(fixture.pendingStore.terminalResult)
    }

    @Test
    fun `keeps durable terminal inbox and ledger when success recovery is cancelled`() = runBlocking {
        val terminal = PendingInstallResult(
            result = PackageInstallResult.Success(SESSION_ID),
            terminalAtEpochMillis = FINISHED_AT,
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
    fun `ignores and clears a foreign durable terminal result without touching pending session`() = runBlocking {
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
        assertNull(fixture.pendingStore.terminalResult)
        assertNull(fixture.catalog.replaced)
    }

    @Test
    fun `cancel is a no-op after terminal states`() = runBlocking {
        val idleFixture = fixture()
        idleFixture.coordinator.cancel()
        assertEquals(InstallState.Idle, idleFixture.coordinator.state.value)

        val installedFixture = fixture()
        installedFixture.coordinator.request(installedFixture.bundle, partialConfirmed = false)
        installedFixture.installedWrappers.installed = installedWrapper()
        installedFixture.coordinator.onPackageInstallerResult(PackageInstallResult.Success(SESSION_ID))
        installedFixture.coordinator.cancel()
        assertEquals(InstallState.Installed(PACKAGE_NAME), installedFixture.coordinator.state.value)

        val cancelledFixture = fixture()
        cancelledFixture.coordinator.request(cancelledFixture.bundle, partialConfirmed = false)
        cancelledFixture.coordinator.onPackageInstallerResult(PackageInstallResult.Cancelled(SESSION_ID))
        cancelledFixture.coordinator.cancel()
        assertEquals(InstallState.Cancelled, cancelledFixture.coordinator.state.value)

        val failedFixture = fixture(status = ContractCompatibilityStatus.BLOCKED)
        failedFixture.coordinator.request(failedFixture.bundle, partialConfirmed = false)
        val failed = failedFixture.coordinator.state.value
        failedFixture.coordinator.cancel()
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

        fixture.coordinator.cancel()

        assertEquals(InstallState.Installed(PACKAGE_NAME), fixture.coordinator.state.value)
        assertTrue(fixture.gateway.abandonedSessions.isEmpty())
        assertNull(fixture.pendingStore.record)
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

        fixture.coordinator.cancel()

        assertEquals(InstallState.Installed(PACKAGE_NAME), fixture.coordinator.state.value)
        assertEquals(PACKAGE_NAME, fixture.catalog.replaced?.packageName)
        assertNull(fixture.pendingStore.record)
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

        fixture.coordinator.cancel()

        assertEquals(
            InstallState.Failed("SYSTEM_FAILURE", "installer rejected package"),
            fixture.coordinator.state.value,
        )
        assertNull(fixture.pendingStore.record)
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

        assertEquals(
            InstallState.Failed(
                "TERMINAL_RECOVERY_FAILED",
                "검증 완료 표지가 없는 설치 결과를 복구하지 못했습니다.",
            ),
            fixture.coordinator.state.value,
        )
        assertEquals(terminal, fixture.pendingStore.terminalResult)
        assertNull(fixture.catalog.replaced)
    }

    @Test
    fun `retains ledger temp and inbox when abandon fails and session is still owned`() = runBlocking {
        val fixture = fixture()
        fixture.coordinator.request(fixture.bundle, partialConfirmed = false)
        fixture.gateway.abandonFailure = IllegalStateException("abandon failed")

        fixture.coordinator.onPackageInstallerResult(PackageInstallResult.Cancelled(SESSION_ID))

        assertEquals(
            InstallState.Failed("ABANDON_FAILED", "설치 session을 정리하지 못했습니다."),
            fixture.coordinator.state.value,
        )
        assertEquals(pendingInstall(), fixture.pendingStore.record)
        assertIs<PackageInstallResult.Cancelled>(fixture.pendingStore.terminalResult?.result)
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

        override fun canRequestPackageInstalls(): Boolean = canInstallPackages

        override fun unknownSourcesSettingsIntent(): Intent =
            throw UnsupportedOperationException("not called by coordinator")

        override suspend fun createSession(packageName: String): Int {
            createFailure?.let { throw it }
            createdPackages += packageName
            owned += SESSION_ID
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
        }

        override fun ownedSessions(): List<Int> = owned.toList()

        fun replaceOwnedSessions(sessionIds: List<Int>) {
            owned.clear()
            owned += sessionIds
        }
    }

    private class FakePendingInstallStore(
        initial: PendingInstall?,
    ) : PendingInstallStore {
        var record: PendingInstall? = initial
        var terminalResult: PendingInstallResult? = null
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
        var clearMissingFailure = false
        var terminalBeforeStaleClear: PackageInstallResult? = null
        var loadBarrier: CyclicBarrier? = null
        var confirmationDeliveredSessionId: Int? = null
        var confirmationMarkFailure = false
        private var barrierLoadsRemaining = 2

        override fun load(): PendingInstall? {
            val barrier = synchronized(this) {
                loadBarrier?.takeIf { barrierLoadsRemaining-- > 0 }
            }
            barrier?.await()
            return synchronized(this) { record }
        }

        override fun tryClaim(
            packageName: String,
            importToken: String,
            startedAtEpochMillis: Long,
        ): PendingInstall? = synchronized(this) {
            if (record != null) return@synchronized null
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
            if (result is PackageInstallResult.PendingUserAction ||
                record?.sessionId != result.sessionId
            ) {
                return@synchronized false
            }
            val pendingResult = PendingInstallResult(
                result = result,
                terminalAtEpochMillis = terminalAtEpochMillis,
                packageName = record?.packageName.orEmpty(),
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
