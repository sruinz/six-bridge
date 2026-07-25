package dev.sixbridge.manager.install

import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.manager.catalog.CatalogRecord
import dev.sixbridge.manager.catalog.ManagerCatalog
import dev.sixbridge.manager.importer.ImportedBundle
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

class InstallCoordinator(
    private val policy: InstallPolicy,
    private val gateway: PackageInstallGateway,
    private val pendingStore: PendingInstallStore,
    private val installedWrappers: InstalledWrapperRepository,
    private val catalog: ManagerCatalog,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var activeBundle: ImportedBundle? = null
    private val mutableState = MutableStateFlow<InstallState>(restoreState())

    val state: StateFlow<InstallState> = mutableState.asStateFlow()

    suspend fun request(bundle: ImportedBundle, partialConfirmed: Boolean) = mutex.withLock {
        val existing = pendingStore.load()
        if (activeBundle != null || existing != null) {
            val activeToken = existing?.importToken ?: activeBundle?.token
            if (bundle.token != activeToken) {
                runCatching { pendingStore.discardImport(bundle.token) }
            }
            return@withLock
        }
        val installed = runCatching {
            installedWrappers.find(bundle.verifiedApk.packageName)
        }.getOrElse {
            finishWithoutSession(
                bundle,
                InstallState.Failed("INSTALLED_QUERY_FAILED", "설치 상태를 확인하지 못했습니다."),
            )
            return@withLock
        }
        when (val decision = policy.decide(bundle.report, bundle.verifiedApk, installed)) {
            is InstallDecision.Denied -> {
                finishWithoutSession(
                    bundle,
                    InstallState.Failed(
                        code = decision.reason.name,
                        message = "설치 정책이 래퍼를 거부했습니다.",
                    ),
                )
            }

            is InstallDecision.Allowed -> {
                if (decision.requiresPartialConfirmation && !partialConfirmed) {
                    finishWithoutSession(
                        bundle,
                        InstallState.Failed(
                            code = "PARTIAL_CONFIRMATION_REQUIRED",
                            message = "부분 호환 설치 확인이 필요합니다.",
                        ),
                    )
                    return@withLock
                }
                activeBundle = bundle
                if (!gateway.canRequestPackageInstalls()) {
                    mutableState.value = InstallState.UnknownSourcesPermissionRequired
                    return@withLock
                }
                createAndCommit(bundle)
            }
        }
    }

    suspend fun resumeAfterUnknownSourcesSettings() = mutex.withLock {
        if (mutableState.value != InstallState.UnknownSourcesPermissionRequired) return@withLock
        val bundle = activeBundle ?: run {
            mutableState.value = InstallState.Failed(
                "IMPORT_NOT_AVAILABLE",
                "설치할 임시 래퍼를 다시 가져와야 합니다.",
            )
            return@withLock
        }
        if (!gateway.canRequestPackageInstalls()) return@withLock
        createAndCommit(bundle)
    }

    suspend fun onPackageInstallerResult(result: PackageInstallResult) = mutex.withLock {
        val pending = pendingStore.load() ?: return@withLock
        if (pending.sessionId != result.sessionId) return@withLock
        when (result) {
            is PackageInstallResult.PendingUserAction -> {
                mutableState.value = InstallState.AwaitingUserApproval(result.sessionId)
            }

            is PackageInstallResult.Cancelled -> {
                finishSession(
                    pending,
                    InstallState.Cancelled,
                    abandon = true,
                )
            }

            is PackageInstallResult.Failure -> {
                finishSession(
                    pending,
                    InstallState.Failed(result.code, result.message),
                    abandon = true,
                )
            }

            is PackageInstallResult.Success -> verifyAndRecord(pending)
        }
    }

    suspend fun cancel() = mutex.withLock {
        val pending = pendingStore.load()
        if (pending != null) {
            finishSession(pending, InstallState.Cancelled, abandon = true)
        } else {
            val bundle = activeBundle
            activeBundle = null
            mutableState.value = if (bundle == null || clearPending(bundle.token)) {
                InstallState.Cancelled
            } else {
                cleanupFailed()
            }
        }
    }

    fun unknownSourcesSettingsIntent() = gateway.unknownSourcesSettingsIntent()

    private suspend fun createAndCommit(bundle: ImportedBundle) {
        if (!matchesRetainedWrapper(bundle)) {
            finishWithoutSession(
                bundle,
                InstallState.Failed(
                    "WRAPPER_HASH_MISMATCH",
                    "검증 후 보관된 래퍼가 변경되었습니다.",
                ),
            )
            return
        }
        val sessionId = try {
            gateway.createAndWrite(bundle.verifiedApk.packageName, bundle.wrapperFile)
        } catch (error: PackageInstallSessionException) {
            error.sessionId?.let(::abandonQuietly)
            finishWithoutSession(
                bundle,
                InstallState.Failed(error.code, error.message),
            )
            return
        } catch (error: CancellationException) {
            finishWithoutSession(bundle, InstallState.Cancelled)
            throw error
        } catch (_: Exception) {
            finishWithoutSession(
                bundle,
                InstallState.Failed("SESSION_CREATE_FAILED", "설치 session을 만들지 못했습니다."),
            )
            return
        }
        mutableState.value = InstallState.WritingSession(sessionId)
        val pending = PendingInstall(
            sessionId = sessionId,
            packageName = bundle.verifiedApk.packageName,
            importToken = bundle.token,
            startedAtEpochMillis = clock(),
        )
        try {
            pendingStore.save(pending)
            gateway.commit(sessionId)
            mutableState.value = InstallState.AwaitingUserApproval(sessionId)
        } catch (error: CancellationException) {
            finishSession(pending, InstallState.Cancelled, abandon = true)
            throw error
        } catch (_: Exception) {
            finishSession(
                pending,
                InstallState.Failed("SESSION_COMMIT_FAILED", "설치 session을 제출하지 못했습니다."),
                abandon = true,
            )
        }
    }

    private suspend fun verifyAndRecord(pending: PendingInstall) {
        val installed = runCatching {
            installedWrappers.find(pending.packageName)
        }.getOrNull()
        val bundle = activeBundle
        if (!isExpectedInstall(pending, installed, bundle)) {
            finishSession(
                pending,
                InstallState.Failed(
                    "POST_INSTALL_VERIFICATION_FAILED",
                    "설치된 래퍼를 검증할 수 없습니다.",
                ),
                abandon = true,
            )
            return
        }
        val verified = requireNotNull(installed)
        val record = CatalogRecord(
            packageName = verified.packageName,
            extensionId = verified.extensionId,
            name = verified.name,
            iconPng = null,
            installedVersionCode = verified.versionCode,
            signerSha256 = verified.signerSha256,
            compatibilityStatus = bundle?.report?.status?.name ?: "UNKNOWN",
            reportJson = bundle?.report?.let(BundleJson::encodeCompatibility),
            lastVerifiedAtEpochMillis = clock(),
        )
        try {
            catalog.replaceVerified(record)
        } catch (_: Exception) {
            finishSession(
                pending,
                InstallState.Failed("CATALOG_UPDATE_FAILED", "설치 catalog를 갱신하지 못했습니다."),
                abandon = true,
            )
            return
        }
        finishSession(
            pending,
            InstallState.Installed(verified.packageName),
            abandon = false,
        )
    }

    private fun isExpectedInstall(
        pending: PendingInstall,
        installed: InstalledWrapper?,
        bundle: ImportedBundle?,
    ): Boolean {
        if (installed == null ||
            installed.packageName != pending.packageName ||
            installed.versionCode <= 0 ||
            installed.signerSha256.isBlank()
        ) {
            return false
        }
        if (bundle == null) return false
        return installed.extensionId == bundle.verifiedApk.extensionId &&
            installed.versionCode == bundle.verifiedApk.versionCode &&
            installed.signerSha256 == bundle.verifiedApk.signerSha256
    }

    private suspend fun matchesRetainedWrapper(bundle: ImportedBundle): Boolean =
        bundle.wrapperSha256 == bundle.verifiedApk.wrapperSha256 &&
            bundle.wrapperSha256 == bundle.descriptor.wrapperSha256 &&
            runCatching {
                withContext(Dispatchers.IO) { sha256(bundle.wrapperFile) }
            }.getOrNull() == bundle.wrapperSha256

    private fun sha256(file: File): String {
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

    private fun restoreState(): InstallState {
        val pending = pendingStore.load() ?: return InstallState.Idle
        val owned = runCatching(gateway::ownedSessions).getOrNull()
            ?: return InstallState.AwaitingUserApproval(pending.sessionId)
        if (pending.sessionId in owned) {
            return InstallState.AwaitingUserApproval(pending.sessionId)
        }
        val now = clock()
        if (now < pending.startedAtEpochMillis ||
            now - pending.startedAtEpochMillis <= COMPLETED_SESSION_GRACE_MILLIS
        ) {
            return InstallState.AwaitingUserApproval(pending.sessionId)
        }
        return if (clearPending(pending.importToken)) {
            InstallState.Idle
        } else {
            cleanupFailed()
        }
    }

    private fun finishWithoutSession(bundle: ImportedBundle, next: InstallState) {
        activeBundle = null
        mutableState.value = if (clearPending(bundle.token)) next else cleanupFailed()
    }

    private fun finishSession(
        pending: PendingInstall,
        next: InstallState,
        abandon: Boolean,
    ) {
        if (abandon) abandonQuietly(pending.sessionId)
        activeBundle = null
        mutableState.value = if (clearPending(pending.importToken)) next else cleanupFailed()
    }

    private fun clearPending(importToken: String): Boolean {
        val recordCleared = runCatching { pendingStore.clear() }.isSuccess
        val importDiscarded = runCatching { pendingStore.discardImport(importToken) }.isSuccess
        return recordCleared && importDiscarded
    }

    private fun abandonQuietly(sessionId: Int) {
        runCatching { gateway.abandon(sessionId) }
    }

    private fun cleanupFailed() = InstallState.Failed(
        "CLEANUP_FAILED",
        "설치 임시 데이터를 정리하지 못했습니다.",
    )

    private companion object {
        const val COMPLETED_SESSION_GRACE_MILLIS = 24 * 60 * 60 * 1_000L
    }
}
