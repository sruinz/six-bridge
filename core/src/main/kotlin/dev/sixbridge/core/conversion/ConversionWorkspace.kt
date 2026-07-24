package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.SourceProjectIdentityStore
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.profile.BrowserProfile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.jvm.JvmSynthetic

internal sealed interface VerifiedCrxPayload {
    @get:JvmSynthetic
    val bytes: ByteArray
}

private class WorkspaceVerifiedCrxPayload(
    bytes: ByteArray,
) : VerifiedCrxPayload {
    private val stableBytes = bytes.copyOf()

    override val bytes: ByteArray
        get() = stableBytes.copyOf()
}

internal sealed interface VerifiedAdapterApplication {
    @get:JvmSynthetic
    val decisions: List<AdapterDecision>
}

private class WorkspaceVerifiedAdapterApplication(
    decisions: List<AdapterDecision>,
) : VerifiedAdapterApplication {
    override val decisions: List<AdapterDecision> =
        decisions.map { it.copy() }
}

class ConversionWorkspace : AutoCloseable {
    private val rootOwner = OwnedTempTree.create("sixbridge-conversion-")
    private val root = rootOwner.rootPath
    private val ownedWorkingRoots = mutableListOf<Path>()
    private val ownedSnapshots = IdentityHashMap<ExtensionSnapshot, Unit>()
    private val pendingCleanup = ConcurrentHashMap.newKeySet<Path>()
    private var lifecycle = Lifecycle.OPEN
    private var closeFailures = 0

    @Synchronized
    fun snapshot(
        source: Path,
        requireOwnerOnlyMarker: Boolean = true,
    ): ExtensionSnapshot {
        requireOpen()
        val snapshotOwner = OwnedTempTree.create(
            rootOwner,
            "snapshot-",
        )
        val snapshotRoot = snapshotOwner.rootPath
        return try {
            val sourceProjectIdentity = SourceProjectIdentityStore.readSourceMarker(
                source,
                requireOwnerOnlyMarker,
            )
            val sourceZip = ExtensionZip.createBytes(
                sourceRoot = source,
                includeProjectMarker = true,
            )
            val originalOwner = OwnedTempTree.createNamed(
                snapshotOwner,
                "original",
            )
            val original = ExtensionZip.extractInto(
                sourceZip,
                originalOwner.rootPath,
            )
            val workingOwner = OwnedTempTree.createNamed(
                snapshotOwner,
                "working",
            )
            val working = NativeWorkspaceTree.copyInto(
                original,
                workingOwner.rootPath,
            )
            require(
                sourceProjectIdentity == SourceProjectIdentityStore.readSourceMarker(
                    original,
                    requireOwnerOnly = false,
                ),
            ) {
                "snapshot archive의 source project identity marker가 다릅니다."
            }
            check(
                sourceZip.contentEquals(
                    ExtensionZip.createBytes(
                        sourceRoot = original,
                        includeProjectMarker = true,
                    ),
                ) &&
                    sourceZip.contentEquals(
                        ExtensionZip.createBytes(
                            sourceRoot = working,
                            includeProjectMarker = true,
                        ),
                    ),
            ) {
                "snapshot archive 추출 결과가 canonical source와 다릅니다."
            }
            check(
                sourceZip.contentEquals(
                    ExtensionZip.createBytes(
                        sourceRoot = source,
                        includeProjectMarker = true,
                    ),
                ),
            ) {
                "snapshot 완료 전 canonical source archive가 변경되었습니다."
            }
            require(
                sourceProjectIdentity == SourceProjectIdentityStore.readSourceMarker(
                    source,
                    requireOwnerOnlyMarker,
                ),
            ) {
                "snapshot 완료 전 source project identity marker가 변경되었습니다."
            }
            val sourceSha256 = sha256(sourceZip)
            Ownership.register(
                originalRoot = original,
                workingRoot = working,
                freshWorkspaceRoot = root,
            )
            ownedWorkingRoots.add(working)
            val snapshot = ExtensionSnapshot.create(
                sourceZip = sourceZip,
                sourceSha256 = sourceSha256,
                originalRoot = original,
                workingRoot = working,
                sourceProjectIdentity = sourceProjectIdentity,
            )
            ownedSnapshots[snapshot] = Unit
            snapshot
        } catch (error: Throwable) {
            try {
                deleteTree(snapshotRoot)
            } catch (_: Throwable) {
                queueCleanup(snapshotRoot)
                error.addSuppressed(SnapshotCleanupException())
            }
            throw error
        }
    }

    @Synchronized
    fun transform(
        snapshot: ExtensionSnapshot,
        profile: BrowserProfile,
        plan: ConversionPlan,
    ): List<AdapterDecision> {
        requireOpen()
        check(ownedSnapshots.containsKey(snapshot)) {
            "이 ConversionWorkspace가 발급한 snapshot이 아닙니다."
        }
        profile.validate()
        return try {
            verifyPlan(snapshot, profile, plan)
            Transaction().run(
                originalRoot = snapshot.originalRoot,
                workingRoot = snapshot.workingRoot,
                profile = profile,
                selectedAdapters = plan.selectedAdapters,
            )
        } catch (error: Error) {
            throw error
        } catch (_: Throwable) {
            throw ConversionExecutionException()
        }
    }

    @Synchronized
    @JvmSynthetic
    internal fun verifyApplied(
        snapshot: ExtensionSnapshot,
        profile: BrowserProfile,
        plan: ConversionPlan,
        applied: List<AdapterDecision>,
        expectedArchive: ByteArray,
    ): VerifiedAdapterApplication {
        requireOpen()
        check(ownedSnapshots.containsKey(snapshot)) {
            "이 ConversionWorkspace가 발급한 snapshot이 아닙니다."
        }
        verifyPlan(snapshot, profile, plan)
        check(
            applied.size == plan.selectedAdapters.size &&
                applied.map { it.id to it.version } ==
                plan.selectedAdapters.map { it.id to it.version } &&
                applied.all(AdapterDecision::applied),
        ) {
            "adapter 실행 proof가 완전하지 않습니다."
        }
        val first = ExtensionZip.createBytes(
            snapshot.workingRoot,
            includeProjectMarker = true,
        )
        val second = ExtensionZip.createBytes(
            snapshot.workingRoot,
            includeProjectMarker = true,
        )
        check(
            expectedArchive.contentEquals(first) &&
                first.contentEquals(second),
        ) {
            "adapter 실행 결과를 canonical source와 함께 봉인하지 못했습니다."
        }
        return WorkspaceVerifiedAdapterApplication(applied)
    }

    @Synchronized
    @JvmSynthetic
    internal fun sealArchive(
        snapshot: ExtensionSnapshot,
        publicKey: ByteArray?,
        includeProjectMarker: Boolean,
        expectedArchive: ByteArray,
    ): VerifiedCrxPayload {
        requireOpen()
        check(ownedSnapshots.containsKey(snapshot)) {
            "이 ConversionWorkspace가 발급한 snapshot이 아닙니다."
        }
        val record = Ownership.record(snapshot.workingRoot)
        return record.lock.withLock {
            Ownership.verifyOriginalLocked(record)
            val before = Ownership.captureWorkingTreeLocked(
                snapshot.workingRoot,
                record,
            )
            val first = ExtensionZip.createBytes(
                snapshot.workingRoot,
                publicKey,
                includeProjectMarker,
            )
            check(expectedArchive.contentEquals(first)) {
                "검증된 canonical archive와 현재 workspace가 다릅니다."
            }
            val second = ExtensionZip.createBytes(
                snapshot.workingRoot,
                publicKey,
                includeProjectMarker,
            )
            val after = Ownership.captureWorkingTreeLocked(
                snapshot.workingRoot,
                record,
            )
            check(
                first.contentEquals(second) &&
                    before.state == after.state,
            ) {
                "canonical archive sealing 중 workspace가 변경되었습니다."
            }
            WorkspaceVerifiedCrxPayload(first)
        }
    }

    @Synchronized
    override fun close() {
        if (lifecycle == Lifecycle.CLOSED) return
        lifecycle = Lifecycle.CLOSING
        var failure: Throwable? = null
        pendingCleanup.toList().forEach { path ->
            try {
                deleteTree(path)
                pendingCleanup.remove(path)
            } catch (cleanupError: Throwable) {
                if (failure == null) {
                    failure = cleanupError
                } else {
                    requireNotNull(failure).addSuppressed(cleanupError)
                }
            }
        }
        ownedWorkingRoots.forEach(Ownership::unregister)
        ownedWorkingRoots.clear()
        ownedSnapshots.clear()
        try {
            deleteTree(root)
        } catch (cleanupError: Throwable) {
            if (failure == null) {
                failure = cleanupError
            } else {
                requireNotNull(failure).addSuppressed(cleanupError)
            }
        }
        if (failure == null) {
            lifecycle = Lifecycle.CLOSED
        } else {
            closeFailures += 1
            if (closeFailures >= MAX_CLOSE_FAILURES_BEFORE_ABANDON) {
                OwnedTempTree.abandon(rootOwner)
                pendingCleanup.clear()
                lifecycle = Lifecycle.CLOSED
                throw OwnedTempAbandonedException()
            }
            throw requireNotNull(failure)
        }
    }

    private fun requireOpen() {
        check(lifecycle == Lifecycle.OPEN) {
            "이미 정리 중이거나 정리된 conversion workspace입니다."
        }
    }

    private fun verifyPlan(
        snapshot: ExtensionSnapshot,
        profile: BrowserProfile,
        plan: ConversionPlan,
    ) {
        val report = plan.report
        report.validate()
        check(
            report.status == CompatibilityStatus.COMPATIBLE ||
                report.status == CompatibilityStatus.PARTIAL,
        ) {
            "변환 가능한 compatibility status가 아닙니다."
        }
        check(report.sourceSha256 == snapshot.sourceSha256) {
            "conversion plan source provenance가 snapshot과 다릅니다."
        }
        check(report.browserProfileSha256 == profile.canonicalSha256()) {
            "conversion plan browser profile provenance가 다릅니다."
        }
        check(
            ConversionPlanProof.digest(report, plan.selectedAdapters) ==
                plan.proofSha256,
        ) {
            "conversion plan proof가 다릅니다."
        }
        val originalArchive = ExtensionZip.createBytes(
            snapshot.originalRoot,
            includeProjectMarker = true,
        )
        check(snapshot.sourceZip.contentEquals(originalArchive)) {
            "immutable original source archive가 snapshot과 다릅니다."
        }
        val current = MobilePatternAnalyzer().analyzeArchive(
            originalArchive,
            profile,
        )
        check(current == report) {
            "conversion plan report가 immutable original 분석 결과와 다릅니다."
        }
        val selected = plan.selectedAdapters
        check(selected.map(SelectedAdapter::id).distinct().size == selected.size) {
            "conversion plan adapter selection이 중복되었습니다."
        }
        check(
            selected == selected.sortedWith(
                compareBy(SelectedAdapter::id, SelectedAdapter::version),
            ),
        ) {
            "conversion plan adapter 순서가 canonical하지 않습니다."
        }
        val decisions = report.adapterDecisions.associateBy { it.id to it.version }
        selected.forEach { capability ->
            val decision = decisions[capability.id to capability.version]
            check(
                decision != null &&
                    decision == capability.toDecision() &&
                    decision.disposition != AdapterDisposition.UNAVAILABLE,
            ) {
                "conversion plan adapter capability가 report와 다릅니다."
            }
        }
        val automatic = report.adapterDecisions
            .filter { it.disposition == AdapterDisposition.AUTOMATIC }
            .map { it.id to it.version }
            .toSet()
        check(
            automatic == selected
                .filter { it.disposition == AdapterDisposition.AUTOMATIC }
                .map { it.id to it.version }
                .toSet(),
        ) {
            "conversion plan automatic adapter selection이 완전하지 않습니다."
        }
        check(report.adapterDecisions.isEmpty() || selected.isNotEmpty()) {
            "adapter 결정이 있는 conversion plan은 no-op일 수 없습니다."
        }
    }

    private fun queueCleanup(path: Path) {
        pendingCleanup.add(path.toAbsolutePath().normalize())
    }

    private object Ownership {
        private const val MARKER_SUFFIX = ".sixbridge-workspace-owner"
        private const val ACTIVE = "ACTIVE"
        private const val POISONED = "POISONED"
        private val records = ConcurrentHashMap<Path, Record>()

        fun register(
            originalRoot: Path,
            workingRoot: Path,
            freshWorkspaceRoot: Path,
        ) {
            val working = key(workingRoot)
            val original = key(originalRoot)
            val parent = requireNotNull(working.parent)
            WorkspacePermissionPolicy.requirePrivate(parent, freshWorkspaceRoot)
            val token = UUID.randomUUID().toString()
            writeNewMarker(working, token)
            val record = Record(
                originalRoot = original,
                freshWorkspaceRoot = key(freshWorkspaceRoot),
                token = token,
                parentFileKey = fileKey(parent, "workspace parent"),
                workingFileKey = fileKey(working, "working root"),
                originalTreeState = WorkspaceTreeDigest.capture(original),
                workingTreeSha256 = WorkspaceTreeDigest.sha256(working),
            )
            check(records.putIfAbsent(working, record) == null) {
                "이미 등록된 conversion workspace입니다."
            }
            try {
                verifyLocked(working, record, verifyTree = true)
            } catch (error: Throwable) {
                records.remove(working, record)
                Files.deleteIfExists(markerPath(working))
                throw error
            }
        }

        fun unregister(root: Path) {
            val working = key(root)
            val record = records[working] ?: return
            record.lock.withLock {
                records.remove(working, record)
            }
        }

        fun record(root: Path): Record =
            records[key(root)]
                ?: throw IllegalStateException(
                    "same-process ConversionWorkspace ownership을 확인할 수 없습니다.",
                )

        fun captureWorkingTreeLocked(
            root: Path,
            record: Record,
        ): CapturedTree {
            val working = key(root)
            verifyLocked(working, record, verifyTree = false)
            val state = WorkspaceTreeDigest.capture(working)
            check(state.identitySha256 == record.workingTreeSha256) {
                "conversion workspace tree가 등록된 상태와 다릅니다."
            }
            check(WorkspaceTreeDigest.capture(working) == state) {
                "conversion workspace tree가 snapshot 중 변경되었습니다."
            }
            verifyLocked(working, record, verifyTree = false)
            return CapturedTree(state)
        }

        fun verifyOriginalLocked(record: Record) {
            val original = record.originalRoot
            check(
                Files.isDirectory(original, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(original) &&
                    WorkspaceTreeDigest.capture(original) ==
                    record.originalTreeState,
            ) {
                "immutable original workspace 경계가 변경되었습니다."
            }
        }

        fun updatePublishedLocked(
            root: Path,
            record: Record,
            publishedTreeSha256: String,
        ) {
            val working = key(root)
            verifyParentAndMarker(working, record)
            check(
                Files.isDirectory(working, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(working),
            ) {
                "published workspace root를 확인할 수 없습니다."
            }
            val previousFileKey = record.workingFileKey
            val previousTreeSha256 = record.workingTreeSha256
            try {
                record.workingFileKey = fileKey(
                    working,
                    "published working root",
                )
                record.workingTreeSha256 = publishedTreeSha256
                verifyLocked(working, record, verifyTree = true)
            } catch (error: Throwable) {
                record.workingFileKey = previousFileKey
                record.workingTreeSha256 = previousTreeSha256
                throw error
            }
        }

        fun poison(root: Path, record: Record) {
            record.poisoned = true
            try {
                val working = key(root)
                verifyParent(working, record)
                val marker = markerPath(working)
                check(
                    Files.isRegularFile(marker, NOFOLLOW_LINKS) &&
                        !Files.isSymbolicLink(marker),
                )
                persistPoisonMarker(
                    marker,
                    markerContent(POISONED, record.token),
                )
                WorkspacePermissionPolicy.requirePrivate(
                    marker,
                    record.freshWorkspaceRoot,
                )
            } catch (_: Throwable) {
                throw WorkspacePoisonPersistenceException()
            }
        }

        private fun verifyLocked(
            working: Path,
            record: Record,
            verifyTree: Boolean,
        ) {
            check(!record.poisoned) {
                "conversion workspace output이 poison 상태입니다."
            }
            verifyParentAndMarker(working, record)
            check(
                Files.isDirectory(working, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(working) &&
                    fileKey(working, "working root") ==
                    record.workingFileKey,
            ) {
                "conversion workspace root identity가 변경되었습니다."
            }
            if (verifyTree) {
                check(
                    WorkspaceTreeDigest.sha256(working) ==
                        record.workingTreeSha256,
                ) {
                    "conversion workspace tree가 등록된 상태와 다릅니다."
                }
            }
        }

        private fun verifyParentAndMarker(working: Path, record: Record) {
            verifyParent(working, record)
            val marker = markerPath(working)
            check(
                Files.isRegularFile(marker, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(marker) &&
                    Files.readString(marker) ==
                    markerContent(ACTIVE, record.token),
            ) {
                "conversion workspace ownership marker가 다릅니다."
            }
            WorkspacePermissionPolicy.requirePrivate(
                marker,
                record.freshWorkspaceRoot,
            )
        }

        private fun verifyParent(working: Path, record: Record) {
            val parent = requireNotNull(working.parent)
            check(
                Files.isDirectory(parent, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(parent) &&
                    fileKey(parent, "workspace parent") ==
                    record.parentFileKey,
            ) {
                "conversion workspace parent identity가 변경되었습니다."
            }
            WorkspacePermissionPolicy.requirePrivate(
                parent,
                record.freshWorkspaceRoot,
            )
        }

        private fun writeNewMarker(root: Path, token: String) {
            val marker = markerPath(root)
            Files.writeString(
                marker,
                markerContent(ACTIVE, token),
                CREATE_NEW,
                WRITE,
                NOFOLLOW_LINKS,
            )
            if (
                Files.getFileAttributeView(
                    marker,
                    PosixFileAttributeView::class.java,
                    NOFOLLOW_LINKS,
                ) != null
            ) {
                Files.setPosixFilePermissions(marker, OWNER_FILE_PERMISSIONS)
            }
        }

        private fun persistPoisonMarker(marker: Path, content: String) {
            val parent = requireNotNull(marker.parent)
            val temporary = Files.createTempFile(
                parent,
                ".${marker.fileName}.",
                ".tmp",
            )
            try {
                if (
                    Files.getFileAttributeView(
                        temporary,
                        PosixFileAttributeView::class.java,
                        NOFOLLOW_LINKS,
                    ) != null
                ) {
                    Files.setPosixFilePermissions(
                        temporary,
                        OWNER_FILE_PERMISSIONS,
                    )
                }
                FileChannel.open(
                    temporary,
                    WRITE,
                    TRUNCATE_EXISTING,
                ).use { channel ->
                    val bytes = ByteBuffer.wrap(
                        content.toByteArray(Charsets.UTF_8),
                    )
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                Files.move(
                    temporary,
                    marker,
                    ATOMIC_MOVE,
                    REPLACE_EXISTING,
                )
                FileChannel.open(parent, READ).use { it.force(true) }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

        private fun markerContent(state: String, token: String): String =
            "$state\n$token\n"

        private fun fileKey(path: Path, label: String): String =
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            ).fileKey()?.toString()
                ?: throw IllegalStateException(
                    "$label identity를 확인할 수 없습니다.",
                )

        private fun key(root: Path): Path =
            root.toAbsolutePath().normalize()

        private fun markerPath(root: Path): Path =
            requireNotNull(root.parent)
                .resolve(".${root.fileName}$MARKER_SUFFIX")

        class Record(
            val originalRoot: Path,
            val freshWorkspaceRoot: Path,
            val token: String,
            val parentFileKey: String,
            var workingFileKey: String,
            val originalTreeState: WorkspaceTreeDigest.State,
            var workingTreeSha256: String,
            var poisoned: Boolean = false,
            var active: Boolean = false,
            val lock: ReentrantLock = ReentrantLock(),
        )

        data class CapturedTree(
            val state: WorkspaceTreeDigest.State,
        )

        private val OWNER_FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }

    private class WorkspacePoisonPersistenceException :
        IllegalStateException(
            "conversion workspace poison 상태를 디스크에 기록하지 못했습니다.",
        )

    private object AdapterRegistry {
        private val adapters = listOf(
            ActionPopupEntryAdapter(),
            DesktopSelectionEventBridge(),
            StorageFallbackAdapter(),
        ).associateBy(MobileAdapter::id)

        fun apply(
            context: AdapterExecutionContext,
            selectedAdapters: List<SelectedAdapter>,
        ): List<AdapterDecision> {
            check(
                selectedAdapters.map(SelectedAdapter::id).distinct().size ==
                    selectedAdapters.size,
            ) {
                "중복 adapter selection은 실행할 수 없습니다."
            }
            val results = selectedAdapters
                .sortedWith(EXECUTION_ORDER)
                .associate { selected ->
                    val adapter = adapters[selected.id]
                        ?: throw IllegalStateException(
                            "등록되지 않은 adapter를 실행할 수 없습니다.",
                        )
                    val result = adapter.apply(context, selected)
                    check(
                        result.applied &&
                            result.id == selected.id &&
                            result.version == selected.version,
                    ) {
                        "adapter가 적용 완료 결과를 반환하지 않았습니다."
                    }
                    selected.id to result
                }
            return selectedAdapters.map { selected ->
                requireNotNull(results[selected.id])
            }
        }

        private val EXECUTION_ORDER =
            compareBy<SelectedAdapter>(
                { it.id != MobilePatternAnalyzer.STORAGE_FALLBACK },
                SelectedAdapter::id,
                SelectedAdapter::version,
            )
    }

    private inner class Transaction {
        fun run(
            originalRoot: Path,
            workingRoot: Path,
            profile: BrowserProfile,
            selectedAdapters: List<SelectedAdapter>,
        ): List<AdapterDecision> {
            val root = workingRoot.toAbsolutePath().normalize()
            val record = Ownership.record(root)
            return record.lock.withLock {
                check(!record.active) {
                    "같은 conversion workspace에서 nested transaction을 열 수 없습니다."
                }
                record.active = true
                try {
                    runLocked(
                        originalRoot.toAbsolutePath().normalize(),
                        root,
                        profile,
                        record,
                        selectedAdapters,
                    )
                } finally {
                    record.active = false
                }
            }
        }

        private fun runLocked(
            originalRoot: Path,
            root: Path,
            profile: BrowserProfile,
            record: Ownership.Record,
            selectedAdapters: List<SelectedAdapter>,
        ): List<AdapterDecision> {
            check(originalRoot == record.originalRoot) {
                "등록된 immutable original root와 다릅니다."
            }
            Ownership.verifyOriginalLocked(record)
            val sourceTree =
                Ownership.captureWorkingTreeLocked(
                    root,
                    record,
                )
            val parent = requireNotNull(root.parent)
            val stageDestination = parent.resolve(
                ".sixbridge-stage-${UUID.randomUUID()}",
            )
            val backup = parent.resolve(
                ".sixbridge-backup-${UUID.randomUUID()}",
            )
            var stagedRoot: Path? = null
            try {
                val stageOwner = OwnedTempTree.createNamed(
                    parent,
                    stageDestination.fileName.toString(),
                )
                stagedRoot = NativeWorkspaceTree.copyInto(
                    root,
                    stageOwner.rootPath,
                )
                TransactionContext.activate(
                    originalRoot = originalRoot,
                    stagedRoot = stagedRoot,
                    profile = profile,
                )
                val result = try {
                    AdapterRegistry.apply(
                        TransactionContext,
                        selectedAdapters,
                    )
                } finally {
                    TransactionContext.deactivate()
                }
                val stagedTree = validateCompleteTree(stagedRoot)
                Ownership.verifyOriginalLocked(record)
                val latestWorkingTree =
                    Ownership.captureWorkingTreeLocked(
                        root,
                        record,
                    )
                check(sourceTree.state == latestWorkingTree.state) {
                    "conversion workspace가 transaction 실행 중 변경되었습니다."
                }
                if (
                    sourceTree.state.provenanceSha256 ==
                        stagedTree.state.provenanceSha256
                ) {
                    Ownership.verifyOriginalLocked(record)
                    return result
                }

                check(validateCompleteTree(stagedRoot).state == stagedTree.state) {
                    "staged conversion tree가 publish 직전에 변경되었습니다."
                }
                Ownership.verifyOriginalLocked(record)
                try {
                    moveDirectory(root, backup)
                    check(
                        WorkspaceTreeDigest.capture(backup) == sourceTree.state,
                    ) {
                        "backup workspace가 root move 직후 변경되었습니다."
                    }
                    Ownership.verifyOriginalLocked(record)
                    check(
                        validateCompleteTree(stagedRoot).state ==
                            stagedTree.state,
                    ) {
                        "staged conversion tree가 root move 후 변경되었습니다."
                    }
                    moveDirectory(stagedRoot, root)
                    stagedRoot = null
                } catch (prePublishError: Throwable) {
                    restoreBeforePublish(
                        root = root,
                        backup = backup,
                        expected = sourceTree.state,
                        record = record,
                        failure = prePublishError,
                    )
                    throw prePublishError
                }
                try {
                    check(
                        validateCompleteTree(root).state == stagedTree.state,
                    ) {
                        "published workspace가 검증된 stage와 다릅니다."
                    }
                    check(
                        WorkspaceTreeDigest.capture(backup) ==
                            sourceTree.state,
                    ) {
                        "backup workspace가 publish 중 변경되었습니다."
                    }
                    Ownership.verifyOriginalLocked(record)
                    Ownership.updatePublishedLocked(
                        root,
                        record,
                        stagedTree.state.identitySha256,
                    )
                } catch (validationError: Throwable) {
                    restorePublishedFailure(
                        root = root,
                        backup = backup,
                        expected = sourceTree.state,
                        record = record,
                        failure = validationError,
                    )
                    throw validationError
                }
                if (runCatching { deleteTree(backup) }.isFailure) {
                    queueCleanup(backup)
                }
                return result
            } finally {
                stagedRoot?.let { stage ->
                    if (runCatching { deleteTree(stage) }.isFailure) {
                        queueCleanup(stage)
                    }
                }
            }
        }

        private fun restoreBeforePublish(
            root: Path,
            backup: Path,
            expected: WorkspaceTreeDigest.State,
            record: Ownership.Record,
            failure: Throwable,
        ) {
            try {
                if (!Files.exists(root, NOFOLLOW_LINKS) &&
                    Files.exists(backup, NOFOLLOW_LINKS)
                ) {
                    moveDirectory(backup, root)
                }
                check(WorkspaceTreeDigest.capture(root) == expected) {
                    "backup restore 후 원래 workspace와 다릅니다."
                }
                Ownership.verifyOriginalLocked(record)
            } catch (restoreError: Throwable) {
                failure.addSuppressed(restoreError)
                poison(root, record, failure)
            }
        }

        private fun validateCompleteTree(root: Path): ValidatedTree {
            check(
                Files.isDirectory(root, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(root) &&
                    Files.isRegularFile(
                        root.resolve("manifest.json"),
                        NOFOLLOW_LINKS,
                    ),
            ) {
                "완성된 staged conversion tree를 검증할 수 없습니다."
            }
            val state = WorkspaceTreeDigest.capture(root)
            check(WorkspaceTreeDigest.capture(root) == state) {
                "staged conversion tree가 검증 중 변경되었습니다."
            }
            return ValidatedTree(state)
        }

        private fun restorePublishedFailure(
            root: Path,
            backup: Path,
            expected: WorkspaceTreeDigest.State,
            record: Ownership.Record,
            failure: Throwable,
        ) {
            val quarantine = requireNotNull(root.parent).resolve(
                ".sixbridge-quarantine-${UUID.randomUUID()}",
            )
            try {
                if (Files.exists(root, NOFOLLOW_LINKS)) {
                    moveDirectory(root, quarantine)
                }
                moveDirectory(backup, root)
                check(WorkspaceTreeDigest.capture(root) == expected) {
                    "publish 실패 후 복구된 workspace가 이전 tree와 다릅니다."
                }
                Ownership.verifyOriginalLocked(record)
            } catch (restoreError: Throwable) {
                failure.addSuppressed(restoreError)
                poison(root, record, failure)
                return
            }
            if (runCatching { deleteTree(quarantine) }.isFailure) {
                queueCleanup(quarantine)
            }
        }

        private fun poison(
            root: Path,
            record: Ownership.Record,
            failure: Throwable,
        ) {
            try {
                Ownership.poison(root, record)
            } catch (poisonError: WorkspacePoisonPersistenceException) {
                poisonError.addSuppressed(failure)
                throw poisonError
            }
        }

        private fun moveDirectory(from: Path, to: Path) {
            PendingTempCleanup.move(from, to)
        }

    }

    private data class ValidatedTree(
        val state: WorkspaceTreeDigest.State,
    )

    private object TransactionContext : AdapterExecutionContext {
        private val current = ThreadLocal<ContextState?>()

        override val originalRoot: Path
            get() = checked(ContextState::originalRoot)

        override val stagedRoot: Path
            get() = checked(ContextState::stagedRoot)

        override val profile: BrowserProfile
            get() = checked(ContextState::profile)

        override fun requireExactOriginalTree() {
            checked { state ->
                check(
                    WorkspaceTreeDigest.provenanceSha256(
                        state.originalRoot,
                    ) == WorkspaceTreeDigest.provenanceSha256(
                        state.stagedRoot,
                    ),
                ) {
                    "storage adapter는 immutable original과 같은 stage에서만 실행됩니다."
                }
            }
        }

        fun activate(
            originalRoot: Path,
            stagedRoot: Path,
            profile: BrowserProfile,
        ) {
            check(current.get() == null) {
                "adapter execution context가 이미 활성 상태입니다."
            }
            current.set(
                ContextState(
                    active = AtomicBoolean(true),
                    originalRoot = originalRoot,
                    stagedRoot = stagedRoot,
                    profile = profile,
                ),
            )
        }

        fun deactivate() {
            val state = requireNotNull(current.get()) {
                "adapter execution context가 활성 상태가 아닙니다."
            }
            check(state.active.compareAndSet(true, false)) {
                "adapter execution context가 이미 비활성 상태입니다."
            }
            current.remove()
        }

        private inline fun <T> checked(block: (ContextState) -> T): T {
            val state = current.get()
            check(state != null && state.active.get()) {
                "adapter execution context가 활성 상태가 아닙니다."
            }
            return block(state)
        }
    }

    private data class ContextState(
        val active: AtomicBoolean,
        val originalRoot: Path,
        val stagedRoot: Path,
        val profile: BrowserProfile,
    )

    private enum class Lifecycle {
        OPEN,
        CLOSING,
        CLOSED,
    }

    private class SnapshotCleanupException :
        IllegalStateException(
            "snapshot 임시 경로를 정리하지 못했습니다.",
        )

    private fun deleteTree(path: Path) {
        OwnedTempTree.delete(path)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MAX_CLOSE_FAILURES_BEFORE_ABANDON = 2
    }
}
