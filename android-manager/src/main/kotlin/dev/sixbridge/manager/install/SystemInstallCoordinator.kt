package dev.sixbridge.manager.install

import dev.sixbridge.manager.importer.ImportedBundle
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import dev.sixbridge.manager.wrapper.InstalledWrapper
import dev.sixbridge.manager.wrapper.InstalledWrapperRepository
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface SystemInstallRequestResult {
    data class Launch(
        val state: InstallState.AwaitingSystemInstaller,
        val launch: SystemInstallerLaunch,
    ) : SystemInstallRequestResult

    data object UnknownSourcesPermissionRequired : SystemInstallRequestResult
    data object MyFilesFallbackRequired : SystemInstallRequestResult
    data class Failed(val state: InstallState.Failed) : SystemInstallRequestResult
}

sealed interface InstalledIdentityDecision {
    data object Installed : InstalledIdentityDecision
    data object Cancelled : InstalledIdentityDecision
    data class Conflict(val code: String) : InstalledIdentityDecision
}

class SystemInstallCoordinator(
    private val policy: InstallPolicy,
    private val gateway: SystemInstallerGateway,
    private val handoffStore: SystemInstallHandoffStore,
    private val installedWrappers: InstalledWrapperRepository,
    private val pendingStore: PendingInstallStore,
    private val retainedWrapperFile: (String) -> File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retainedWrapperSha256: suspend (File) -> String = { file ->
        withContext(Dispatchers.IO) { sha256(file) }
    },
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(restoreState())

    val state: StateFlow<InstallState> = mutableState.asStateFlow()

    suspend fun request(
        bundle: ImportedBundle,
        partialConfirmed: Boolean,
    ): SystemInstallRequestResult = mutex.withLock {
        val legacyRecoveryEmpty = when (val snapshot = pendingStore.recoveryStoreSnapshot()) {
            is RecoveryStoreSnapshot.Valid ->
                snapshot.pending == null &&
                    snapshot.terminal == null &&
                    snapshot.finalReceipt == null &&
                    snapshot.normalHandoff == null
            RecoveryStoreSnapshot.Invalid -> false
        }
        if (handoffStore.snapshot() != RecoveryRecordSnapshot.Absent || !legacyRecoveryEmpty) {
            return@withLock failed(recoveryWriteFailed())
        }
        mutableState.value = InstallState.PreparingSystemInstaller
        val installed = try {
            installedWrappers.find(bundle.verifiedApk.packageName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withLock failed(
                InstallState.Failed(
                    "INSTALLED_QUERY_FAILED",
                    "설치 상태를 확인하지 못했습니다.",
                ),
            )
        }
        when (val decision = policy.decide(bundle.report, bundle.verifiedApk, installed)) {
            is InstallDecision.Denied -> {
                val failure = if (decision.reason == InstallDenial.SIGNER_MISMATCH) {
                    InstallState.Failed(
                        "SIGNATURE_MISMATCH",
                        "설치된 래퍼와 새 래퍼의 서명이 일치하지 않습니다.",
                    )
                } else {
                    InstallState.Failed(
                        decision.reason.name,
                        "설치 정책이 래퍼를 거부했습니다.",
                    )
                }
                return@withLock failed(failure)
            }
            is InstallDecision.Allowed -> {
                if (decision.requiresPartialConfirmation && !partialConfirmed) {
                    return@withLock failed(
                        InstallState.Failed(
                            "PARTIAL_CONFIRMATION_REQUIRED",
                            "부분 호환 설치 확인이 필요합니다.",
                        ),
                    )
                }
            }
        }
        val currentSha256 = try {
            retainedWrapperSha256(bundle.wrapperFile)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        if (currentSha256 != bundle.wrapperSha256) {
            return@withLock failed(
                InstallState.Failed(
                    "WRAPPER_HASH_MISMATCH",
                    "검증 후 보관된 래퍼가 변경되었습니다.",
                ),
            )
        }
        if (!gateway.canRequestPackageInstalls()) {
            mutableState.value = InstallState.UnknownSourcesPermissionRequired
            return@withLock SystemInstallRequestResult.UnknownSourcesPermissionRequired
        }
        val launch = try {
            gateway.createLaunch(bundle.wrapperFile)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return@withLock failed(
                InstallState.Failed(
                    "SYSTEM_INSTALLER_LAUNCH_FAILED",
                    "시스템 설치 화면을 준비하지 못했습니다.",
                ),
            )
        }
        val prepared = PendingSystemInstall(
            importToken = bundle.token,
            expected = bundle.verifiedApk.toExpected(),
            wrapperSha256 = bundle.wrapperSha256,
            previous = installed?.toExpected(),
            startedAtEpochMillis = clock(),
            stage = SystemInstallStage.PREPARED,
        )
        if (!handoffStore.tryPrepare(prepared)) {
            if (launch is SystemInstallerLaunchResult.Ready) {
                runCatching { gateway.revoke(bundle.wrapperFile) }
            }
            return@withLock failed(recoveryWriteFailed())
        }
        if (launch == SystemInstallerLaunchResult.MyFilesFallbackRequired) {
            mutableState.value = InstallState.PreparingSystemInstaller
            return@withLock SystemInstallRequestResult.MyFilesFallbackRequired
        }
        val awaiting = InstallState.AwaitingSystemInstaller(bundle.token)
        mutableState.value = awaiting
        SystemInstallRequestResult.Launch(
            state = awaiting,
            launch = (launch as SystemInstallerLaunchResult.Ready).launch,
        )
    }

    fun markLaunchSucceeded(importToken: String): Boolean {
        val prepared = when (val snapshot = handoffStore.snapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent,
            RecoveryRecordSnapshot.Invalid,
            -> return false
        }
        if (prepared.importToken != importToken ||
            prepared.stage != SystemInstallStage.PREPARED
        ) {
            return false
        }
        val launched = handoffStore.markLaunched(prepared) ?: return false
        mutableState.value = InstallState.AwaitingSystemInstaller(launched.importToken)
        return true
    }

    fun markLaunchFailed(importToken: String): Boolean {
        val prepared = when (val snapshot = handoffStore.snapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent,
            RecoveryRecordSnapshot.Invalid,
            -> return false
        }
        if (prepared.importToken != importToken ||
            prepared.stage != SystemInstallStage.PREPARED
        ) {
            return false
        }
        return true
    }

    fun markManualExportSucceeded(importToken: String): Boolean {
        val prepared = when (val snapshot = handoffStore.snapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent,
            RecoveryRecordSnapshot.Invalid,
            -> return false
        }
        if (prepared.importToken != importToken ||
            prepared.stage != SystemInstallStage.PREPARED
        ) {
            return false
        }
        val exported = handoffStore.markExported(prepared) ?: return false
        mutableState.value = InstallState.AwaitingManualInstall(exported.importToken)
        return true
    }

    suspend fun reconcileAfterReturn(): InstallState? = mutex.withLock {
        val record = when (val snapshot = handoffStore.snapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Invalid -> {
                mutableState.value = recoveryWriteFailed()
                return@withLock mutableState.value
            }
            RecoveryRecordSnapshot.Absent -> {
                val receipt = pendingStore.loadFinalReceipt()
                    ?.takeIf { it.origin == FinalReceiptOrigin.SYSTEM_INSTALLER }
                    ?: return@withLock null
                if (!revokeRetainedWrapper(receipt.importToken)) {
                    mutableState.value = recoveryWriteFailed()
                    return@withLock mutableState.value
                }
                mutableState.value = receipt.state
                return@withLock receipt.state
            }
        }
        if (record.stage != SystemInstallStage.LAUNCHED) return@withLock null
        reconcileRecord(record)
    }

    suspend fun reconcileManualAfterUserCheck(importToken: String): InstallState? =
        mutex.withLock {
            val record = when (val snapshot = handoffStore.snapshot()) {
                is RecoveryRecordSnapshot.Present -> snapshot.value
                RecoveryRecordSnapshot.Absent,
                RecoveryRecordSnapshot.Invalid,
                -> return@withLock null
            }
            if (record.importToken != importToken ||
                record.stage != SystemInstallStage.EXPORTED
            ) {
                return@withLock null
            }
            reconcileRecord(record)
        }

    private suspend fun reconcileRecord(record: PendingSystemInstall): InstallState {
        val actual = try {
            installedWrappers.find(record.expected.packageName)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.value = InstallState.Failed(
                "INSTALLED_QUERY_FAILED",
                "설치 상태를 확인하지 못했습니다.",
            )
            return mutableState.value
        }
        val terminal = when (
            val decision = decideInstalledIdentity(record.expected, record.previous, actual)
        ) {
            InstalledIdentityDecision.Installed ->
                InstallState.Installed(record.expected.packageName)
            InstalledIdentityDecision.Cancelled -> InstallState.Cancelled
            is InstalledIdentityDecision.Conflict ->
                InstallState.Failed(
                    decision.code,
                    "설치된 래퍼 신원이 일치하지 않습니다.",
                )
        }
        val receipt = FinalInstallReceipt(
            importToken = record.importToken,
            sessionId = null,
            packageName = record.expected.packageName,
            state = terminal,
            stage = FinalReceiptStage.READY,
            origin = FinalReceiptOrigin.SYSTEM_INSTALLER,
            rawTerminalAtEpochMillis = null,
            terminalAtEpochMillis = clock(),
        )
        if (!ensureDurableReceipt(receipt)) {
            mutableState.value = recoveryWriteFailed()
            return mutableState.value
        }
        if (!handoffStore.clear(record)) {
            mutableState.value = recoveryWriteFailed()
            return mutableState.value
        }
        if (!revokeRetainedWrapper(record.importToken)) {
            mutableState.value = recoveryWriteFailed()
            return mutableState.value
        }
        mutableState.value = terminal
        return terminal
    }

    suspend fun cancel(expectedImportToken: String): InstallCleanupResult = mutex.withLock {
        val record = when (val snapshot = handoffStore.snapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Invalid -> return@withLock InstallCleanupResult.CleanupFailed
            RecoveryRecordSnapshot.Absent -> return@withLock InstallCleanupResult.Cleaned
        }
        if (record.importToken != expectedImportToken) {
            return@withLock InstallCleanupResult.OwnerMismatch
        }
        val wrapper = retainedWrapperFile(expectedImportToken)
        if (!runCatching { gateway.revoke(wrapper) }.isSuccess ||
            !handoffStore.clear(record)
        ) {
            return@withLock InstallCleanupResult.CleanupFailed
        }
        val discarded = runCatching {
            pendingStore.discardImport(expectedImportToken)
        }.isSuccess
        if (!discarded) return@withLock InstallCleanupResult.CleanupFailed
        mutableState.value = InstallState.Idle
        InstallCleanupResult.Cleaned
    }

    private fun ensureDurableReceipt(receipt: FinalInstallReceipt): Boolean =
        when (val snapshot = pendingStore.finalReceiptSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value.matchesSystemOutcome(receipt)
            RecoveryRecordSnapshot.Invalid -> false
            RecoveryRecordSnapshot.Absent ->
                pendingStore.createFinalReceipt(
                    expectedPending = null,
                    expectedRaw = null,
                    receipt = receipt,
                ) && pendingStore.loadFinalReceipt() == receipt
        }

    private fun revokeRetainedWrapper(importToken: String): Boolean =
        runCatching {
            gateway.revoke(retainedWrapperFile(importToken))
        }.isSuccess

    private fun FinalInstallReceipt.matchesSystemOutcome(expected: FinalInstallReceipt): Boolean =
        origin == FinalReceiptOrigin.SYSTEM_INSTALLER &&
            stage != FinalReceiptStage.CANCELLING &&
            importToken == expected.importToken &&
            sessionId == null &&
            packageName == expected.packageName &&
            state == expected.state &&
            rawTerminalAtEpochMillis == null

    private fun restoreState(): InstallState {
        when (val receipt = pendingStore.finalReceiptSnapshot()) {
            is RecoveryRecordSnapshot.Present -> return receipt.value.state
            RecoveryRecordSnapshot.Invalid -> return recoveryWriteFailed()
            RecoveryRecordSnapshot.Absent -> Unit
        }
        return when (val handoff = handoffStore.snapshot()) {
            is RecoveryRecordSnapshot.Present ->
                when (handoff.value.stage) {
                    SystemInstallStage.LAUNCHED ->
                        InstallState.AwaitingSystemInstaller(handoff.value.importToken)
                    SystemInstallStage.EXPORTED ->
                        InstallState.AwaitingManualInstall(handoff.value.importToken)
                    SystemInstallStage.PREPARED ->
                        InstallState.PreparingSystemInstaller
                }
            RecoveryRecordSnapshot.Invalid -> recoveryWriteFailed()
            RecoveryRecordSnapshot.Absent -> InstallState.Idle
        }
    }

    private fun failed(state: InstallState.Failed): SystemInstallRequestResult.Failed {
        mutableState.value = state
        return SystemInstallRequestResult.Failed(state)
    }

    private fun recoveryWriteFailed() = InstallState.Failed(
        "RECOVERY_WRITE_FAILED",
        "시스템 설치 결과를 저장하지 못했습니다.",
    )

    private companion object {
        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun decideInstalledIdentity(
    expected: ExpectedInstalledWrapper,
    previous: ExpectedInstalledWrapper?,
    actual: InstalledWrapper?,
): InstalledIdentityDecision = when {
    actual.matches(expected) -> InstalledIdentityDecision.Installed
    previous != null && actual.matches(previous) -> InstalledIdentityDecision.Cancelled
    actual == null -> InstalledIdentityDecision.Cancelled
    else -> InstalledIdentityDecision.Conflict("INSTALLED_IDENTITY_MISMATCH")
}

private fun InstalledWrapper?.matches(expected: ExpectedInstalledWrapper): Boolean =
    this != null &&
        packageName == expected.packageName &&
        extensionId == expected.extensionId &&
        versionCode == expected.versionCode &&
        signerSha256 == expected.signerSha256

private fun VerifiedWrapperApk.toExpected() = ExpectedInstalledWrapper(
    packageName = packageName,
    extensionId = extensionId,
    versionCode = versionCode,
    signerSha256 = signerSha256,
)

private fun InstalledWrapper.toExpected() = ExpectedInstalledWrapper(
    packageName = packageName,
    extensionId = extensionId,
    versionCode = versionCode,
    signerSha256 = signerSha256,
)
