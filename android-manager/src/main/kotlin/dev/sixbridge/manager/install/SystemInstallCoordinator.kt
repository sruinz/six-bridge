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
    data class ExportRequired(
        val state: InstallState.PreparingApkExport,
    ) : SystemInstallRequestResult

    data class Failed(val state: InstallState.Failed) : SystemInstallRequestResult
}

sealed interface InstalledIdentityDecision {
    data object Installed : InstalledIdentityDecision
    data object Cancelled : InstalledIdentityDecision
    data class Conflict(val code: String) : InstalledIdentityDecision
}

class SystemInstallCoordinator(
    private val policy: InstallPolicy,
    private val handoffStore: SystemInstallHandoffStore,
    private val installedWrappers: InstalledWrapperRepository,
    private val pendingStore: PendingInstallStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retainedWrapperSha256: suspend (File) -> String = { file ->
        withContext(Dispatchers.IO) { sha256(file) }
    },
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(restoreState())

    val state: StateFlow<InstallState> = mutableState.asStateFlow()

    suspend fun reconcileAfterStartupCleanup() = mutex.withLock {
        mutableState.value = restoreState()
    }

    fun ownsHandoff(importToken: String): Boolean =
        when (val snapshot = handoffStore.snapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value.importToken == importToken
            RecoveryRecordSnapshot.Absent,
            RecoveryRecordSnapshot.Invalid,
            -> false
        }

    fun ownsPreparedFallback(importToken: String): Boolean {
        val prepared = when (val snapshot = handoffStore.snapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value
            RecoveryRecordSnapshot.Absent,
            RecoveryRecordSnapshot.Invalid,
            -> return false
        }
        return prepared.importToken == importToken &&
            prepared.stage == SystemInstallStage.PREPARED
    }

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
        mutableState.value = InstallState.PreparingApkExport(bundle.token)
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
        if (installed != null &&
            installed.versionCode == bundle.verifiedApk.versionCode
        ) {
            when (installed.apkSha256) {
                null -> return@withLock failed(
                    InstallState.Failed(
                        "INSTALLED_APK_HASH_UNKNOWN",
                        "설치된 APK의 무결성을 확인하지 못했습니다.",
                    ),
                )
                bundle.wrapperSha256 -> Unit
                else -> return@withLock failed(
                    InstallState.Failed(
                        "SAME_VERSION_APK_MISMATCH",
                        "같은 버전의 설치 APK가 변환 결과와 일치하지 않습니다.",
                    ),
                )
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
        val prepared = PendingSystemInstall(
            importToken = bundle.token,
            expected = bundle.verifiedApk.toExpected(),
            wrapperSha256 = bundle.wrapperSha256,
            previous = installed?.toExpected(),
            startedAtEpochMillis = clock(),
            stage = SystemInstallStage.PREPARED,
        )
        if (!handoffStore.tryPrepare(prepared)) {
            return@withLock failed(recoveryWriteFailed())
        }
        val preparing = InstallState.PreparingApkExport(bundle.token)
        mutableState.value = preparing
        SystemInstallRequestResult.ExportRequired(preparing)
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
        when (handoffStore.snapshot()) {
            is RecoveryRecordSnapshot.Present -> return@withLock null
            RecoveryRecordSnapshot.Invalid -> {
                mutableState.value = recoveryWriteFailed()
                return@withLock mutableState.value
            }
            RecoveryRecordSnapshot.Absent -> Unit
        }
        val receipt = pendingStore.loadFinalReceipt()
            ?.takeIf { it.origin == FinalReceiptOrigin.MY_FILES }
            ?: return@withLock null
        if (!discardPrivateImport(receipt.importToken)) {
            mutableState.value = recoveryWriteFailed()
            return@withLock mutableState.value
        }
        mutableState.value = receipt.state
        receipt.state
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
        val identity = when (
            val decision = decideInstalledIdentity(record.expected, record.previous, actual)
        ) {
            InstalledIdentityDecision.Installed -> {
                if (actual?.apkSha256 != record.wrapperSha256) {
                    InstallState.Failed(
                        "INSTALLED_IDENTITY_MISMATCH",
                        "설치된 래퍼 신원이 일치하지 않습니다.",
                    )
                } else {
                    when (classifySamsungInstallSource(actual.installSourceEvidence)) {
                        SamsungInstallSourceVerdict.ALLOWED_ADB,
                        SamsungInstallSourceVerdict.ALLOWED_SYSTEM_DOCUMENTS,
                        SamsungInstallSourceVerdict.ALLOWED_MY_FILES,
                        -> InstallState.Installed(record.expected.packageName)
                        SamsungInstallSourceVerdict.UNVERIFIED_FILE_APP ->
                            InstallState.Installed(
                                record.expected.packageName,
                                SamsungInternetSupportStatus.UNVERIFIED_FILE_APP,
                            )
                        SamsungInstallSourceVerdict.UNSUPPORTED ->
                            InstallState.Failed(
                                "UNSUPPORTED_INSTALL_SOURCE",
                                "지원되는 파일 앱에서 APK를 다시 설치해 주세요.",
                            )
                        SamsungInstallSourceVerdict.UNKNOWN ->
                            InstallState.Failed(
                                "INSTALL_SOURCE_UNKNOWN",
                                "설치 출처를 확인하지 못했습니다.",
                            )
                    }
                }
            }
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
            state = identity,
            stage = FinalReceiptStage.READY,
            origin = FinalReceiptOrigin.MY_FILES,
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
        if (!discardPrivateImport(record.importToken)) {
            mutableState.value = recoveryWriteFailed()
            return mutableState.value
        }
        mutableState.value = identity
        return identity
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
        if (!discardPrivateImport(expectedImportToken)) {
            return@withLock InstallCleanupResult.CleanupFailed
        }
        if (!handoffStore.clear(record)) {
            return@withLock InstallCleanupResult.CleanupFailed
        }
        mutableState.value = InstallState.Idle
        InstallCleanupResult.Cleaned
    }

    private fun ensureDurableReceipt(receipt: FinalInstallReceipt): Boolean =
        when (val snapshot = pendingStore.finalReceiptSnapshot()) {
            is RecoveryRecordSnapshot.Present -> snapshot.value.matchesManualOutcome(receipt)
            RecoveryRecordSnapshot.Invalid -> false
            RecoveryRecordSnapshot.Absent ->
                pendingStore.createFinalReceipt(
                    expectedPending = null,
                    expectedRaw = null,
                    receipt = receipt,
                ) && pendingStore.loadFinalReceipt() == receipt
        }

    private fun discardPrivateImport(importToken: String): Boolean =
        runCatching {
            pendingStore.discardImport(importToken)
        }.isSuccess

    private fun FinalInstallReceipt.matchesManualOutcome(expected: FinalInstallReceipt): Boolean =
        origin == FinalReceiptOrigin.MY_FILES &&
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
                    SystemInstallStage.LAUNCHED -> InstallState.Idle
                    SystemInstallStage.EXPORTED ->
                        InstallState.AwaitingManualInstall(handoff.value.importToken)
                    SystemInstallStage.PREPARED ->
                        InstallState.PreparingApkExport(handoff.value.importToken)
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
