package dev.sixbridge.core.conversion

import dev.sixbridge.core.profile.BrowserProfile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal object ConversionWorkspaceOwnership {
    private const val MARKER_SUFFIX = ".sixbridge-workspace-owner"
    private const val ACTIVE = "ACTIVE"
    private const val POISONED = "POISONED"

    private val records = ConcurrentHashMap<Path, OwnershipRecord>()

    fun register(
        originalRoot: Path,
        workingRoot: Path,
        freshWorkspaceRoot: Path,
    ) {
        val key = key(workingRoot)
        val original = key(originalRoot)
        val parent = requireNotNull(key.parent)
        WorkspacePermissionPolicy.requirePrivate(parent, freshWorkspaceRoot)
        val token = UUID.randomUUID().toString()
        writeNewMarker(key, token)
        val record = OwnershipRecord(
            originalRoot = original,
            freshWorkspaceRoot = key(freshWorkspaceRoot),
            token = token,
            parentFileKey = requireFileKey(parent, "workspace parent"),
            originalFileKey = requireFileKey(original, "original root"),
            workingFileKey = requireFileKey(key, "working root"),
            originalTreeSha256 = treeSha256(original),
            workingTreeSha256 = treeSha256(key),
        )
        val previous = records.putIfAbsent(key, record)
        if (previous != null) {
            Files.deleteIfExists(markerPath(key))
            throw IllegalStateException("이미 등록된 conversion workspace입니다.")
        }
        try {
            requireUsable(key)
        } catch (error: Throwable) {
            records.remove(key, record)
            Files.deleteIfExists(markerPath(key))
            throw error
        }
    }

    fun unregister(root: Path) {
        val key = key(root)
        val record = records[key] ?: return
        record.lock.withLock {
            records.remove(key, record)
        }
    }

    fun record(root: Path): OwnershipRecord =
        records[key(root)]
            ?: throw IllegalStateException(
                "same-process ConversionWorkspace ownership을 확인할 수 없습니다.",
            )

    fun requireUsable(root: Path): OwnershipRecord {
        val key = key(root)
        val record = record(key)
        record.lock.withLock {
            verifyLocked(key, record, verifyTree = true)
        }
        return record
    }

    fun captureWorkingTreeLocked(
        root: Path,
        record: OwnershipRecord,
    ): CapturedTree {
        val key = key(root)
        verifyLocked(key, record, verifyTree = false)
        val state = WorkspaceTreeDigest.capture(key)
        check(state.identitySha256 == record.workingTreeSha256) {
            "conversion workspace tree가 등록된 상태와 다릅니다."
        }
        check(
            WorkspaceTreeDigest.capture(key) == state
        ) {
            "conversion workspace tree가 snapshot 중 변경되었습니다."
        }
        verifyLocked(key, record, verifyTree = false)
        return CapturedTree(state)
    }

    fun verifyOriginalLocked(record: OwnershipRecord) {
        val original = record.originalRoot
        check(
            Files.isDirectory(original, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(original) &&
                requireFileKey(original, "original root") == record.originalFileKey &&
                treeSha256(original) == record.originalTreeSha256
        ) {
            "immutable original workspace 경계가 변경되었습니다."
        }
    }

    fun updatePublishedLocked(
        root: Path,
        record: OwnershipRecord,
        publishedTreeSha256: String,
    ) {
        val key = key(root)
        verifyParentAndMarker(key, record)
        check(Files.isDirectory(key, NOFOLLOW_LINKS) && !Files.isSymbolicLink(key)) {
            "published workspace root를 확인할 수 없습니다."
        }
        val previousFileKey = record.workingFileKey
        val previousTreeSha256 = record.workingTreeSha256
        try {
            record.workingFileKey = requireFileKey(key, "published working root")
            record.workingTreeSha256 = publishedTreeSha256
            verifyLocked(key, record, verifyTree = true)
        } catch (error: Throwable) {
            record.workingFileKey = previousFileKey
            record.workingTreeSha256 = previousTreeSha256
            throw error
        }
    }

    fun poison(
        root: Path,
        record: OwnershipRecord,
        persistMarker: (Path, String) -> Unit = ::persistPoisonMarker,
    ) {
        record.poisoned = true
        try {
            val key = key(root)
            verifyParent(key, record)
            val marker = markerPath(key)
            check(
                Files.isRegularFile(marker, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(marker),
            )
            persistMarker(
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

    internal fun poisonForTesting(
        root: Path,
        persistMarker: (Path, String) -> Unit,
    ) {
        val record = record(root)
        record.lock.withLock {
            poison(root, record, persistMarker)
        }
    }

    internal fun markerStateForTesting(root: Path): String? =
        runCatching {
            Files.readString(markerPath(key(root)))
                .lineSequence()
                .firstOrNull()
        }.getOrNull()

    private fun verifyLocked(
        key: Path,
        record: OwnershipRecord,
        verifyTree: Boolean,
    ) {
        check(!record.poisoned) {
            "conversion workspace output이 poison 상태입니다."
        }
        verifyParentAndMarker(key, record)
        check(
            Files.isDirectory(key, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(key) &&
                requireFileKey(key, "working root") == record.workingFileKey
        ) {
            "conversion workspace root identity가 변경되었습니다."
        }
        if (verifyTree) {
            check(treeSha256(key) == record.workingTreeSha256) {
                "conversion workspace tree가 등록된 상태와 다릅니다."
            }
        }
    }

    private fun verifyParentAndMarker(key: Path, record: OwnershipRecord) {
        verifyParent(key, record)
        val marker = markerPath(key)
        check(
                Files.isRegularFile(marker, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(marker) &&
                Files.readString(marker) ==
                markerContent(ACTIVE, record.token)
        ) {
            "conversion workspace ownership marker가 다릅니다."
        }
        WorkspacePermissionPolicy.requirePrivate(
            marker,
            record.freshWorkspaceRoot,
        )
    }

    private fun verifyParent(key: Path, record: OwnershipRecord) {
        val parent = requireNotNull(key.parent)
        check(
            Files.isDirectory(parent, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(parent) &&
                requireFileKey(parent, "workspace parent") == record.parentFileKey
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
        runCatching {
            Files.setPosixFilePermissions(
                marker,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
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
            if (Files.getFileAttributeView(
                    temporary,
                    java.nio.file.attribute.PosixFileAttributeView::class.java,
                    NOFOLLOW_LINKS,
                ) != null
            ) {
                Files.setPosixFilePermissions(
                    temporary,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                    ),
                )
            }
            FileChannel.open(
                temporary,
                WRITE,
                TRUNCATE_EXISTING,
            ).use { channel ->
                val bytes = ByteBuffer.wrap(content.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.move(
                temporary,
                marker,
                ATOMIC_MOVE,
                REPLACE_EXISTING,
            )
            FileChannel.open(parent, READ).use { channel ->
                channel.force(true)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun markerContent(state: String, token: String): String =
        "$state\n$token\n"

    private fun treeSha256(root: Path): String = WorkspaceTreeDigest.sha256(root)

    private fun requireFileKey(path: Path, label: String): String =
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        ).fileKey()?.toString()
            ?: throw IllegalStateException("$label identity를 확인할 수 없습니다.")

    private fun key(root: Path): Path = root.toAbsolutePath().normalize()

    private fun markerPath(root: Path): Path =
        requireNotNull(root.parent).resolve(".${root.fileName}$MARKER_SUFFIX")

    internal class OwnershipRecord(
        val originalRoot: Path,
        val freshWorkspaceRoot: Path,
        val token: String,
        val parentFileKey: String,
        val originalFileKey: String,
        var workingFileKey: String,
        val originalTreeSha256: String,
        var workingTreeSha256: String,
        var poisoned: Boolean = false,
        var active: Boolean = false,
        val lock: ReentrantLock = ReentrantLock(),
    )

    internal data class CapturedTree(
        val state: WorkspaceTreeDigest.State,
    )
}

internal class WorkspacePoisonPersistenceException :
    IllegalStateException(
        "conversion workspace poison 상태를 디스크에 기록하지 못했습니다.",
    )

internal class ConversionWorkspaceTransaction(
    private val moveDirectory: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, ATOMIC_MOVE)
    },
    private val beforeExecution: (Path) -> Unit = {},
    private val beforePublish: () -> Unit = {},
    private val afterBackupMove: (Path) -> Unit = {},
    private val afterStagePublish: (Path) -> Unit = {},
    private val beforeOwnershipUpdate: (Path) -> Unit = {},
) {
    fun run(
        originalRoot: Path,
        workingRoot: Path,
        profile: BrowserProfile,
        selectedAdapters: List<SelectedAdapter>,
        registry: MobileAdapterRegistry,
    ): List<AdapterDecision> {
        val root = workingRoot.toAbsolutePath().normalize()
        val record = ConversionWorkspaceOwnership.record(root)
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
                    registry,
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
        record: ConversionWorkspaceOwnership.OwnershipRecord,
        selectedAdapters: List<SelectedAdapter>,
        registry: MobileAdapterRegistry,
    ): List<AdapterDecision> {
        check(originalRoot == record.originalRoot) {
            "등록된 immutable original root와 다릅니다."
        }
        ConversionWorkspaceOwnership.verifyOriginalLocked(record)
        val sourceTree = ConversionWorkspaceOwnership.captureWorkingTreeLocked(
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
            stagedRoot = NativeWorkspaceTree.copy(root, stageDestination)
            beforeExecution(stagedRoot)
            TransactionContext.activate(
                originalRoot = originalRoot,
                stagedRoot = stagedRoot,
                profile = profile,
            )
            val result = try {
                registry.apply(TransactionContext, selectedAdapters)
            } finally {
                TransactionContext.deactivate()
            }
            val stagedTree = validateCompleteTree(stagedRoot)
            beforePublish()
            val latestWorkingTree =
                ConversionWorkspaceOwnership.captureWorkingTreeLocked(
                    root,
                    record,
                )
            check(
                sourceTree.state == latestWorkingTree.state
            ) {
                "conversion workspace가 transaction 실행 중 변경되었습니다."
            }
            if (
                sourceTree.state.provenanceSha256 ==
                    stagedTree.state.provenanceSha256
            ) {
                return result
            }

            check(validateCompleteTree(stagedRoot).state == stagedTree.state) {
                "staged conversion tree가 publish 직전에 변경되었습니다."
            }
            moveDirectory(root, backup)
            try {
                afterBackupMove(backup)
                check(
                    WorkspaceTreeDigest.capture(backup) == sourceTree.state
                ) {
                    "backup workspace가 root move 직후 변경되었습니다."
                }
                check(validateCompleteTree(stagedRoot).state == stagedTree.state) {
                    "staged conversion tree가 root move 후 변경되었습니다."
                }
                moveDirectory(stagedRoot, root)
                stagedRoot = null
            } catch (prePublishError: Throwable) {
                try {
                    if (!Files.exists(root, NOFOLLOW_LINKS) &&
                        Files.exists(backup, NOFOLLOW_LINKS)
                    ) {
                        moveDirectory(backup, root)
                    }
                    check(
                        WorkspaceTreeDigest.capture(root) == sourceTree.state
                    ) {
                        "backup restore 후 원래 workspace와 다릅니다."
                    }
                } catch (restoreError: Throwable) {
                    prePublishError.addSuppressed(restoreError)
                    try {
                        ConversionWorkspaceOwnership.poison(root, record)
                    } catch (poisonError: WorkspacePoisonPersistenceException) {
                        poisonError.addSuppressed(prePublishError)
                        throw poisonError
                    }
                }
                throw prePublishError
            }
            try {
                afterStagePublish(root)
                check(
                    validateCompleteTree(root).state == stagedTree.state
                ) {
                    "published workspace가 검증된 stage와 다릅니다."
                }
                check(
                    WorkspaceTreeDigest.capture(backup) == sourceTree.state
                ) {
                    "backup workspace가 publish 중 변경되었습니다."
                }
                beforeOwnershipUpdate(root)
                ConversionWorkspaceOwnership.updatePublishedLocked(
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
            runCatching { deleteTree(backup) }
            return result
        } finally {
            stagedRoot?.let(::deleteTree)
        }
    }

    private fun validateCompleteTree(root: Path): ValidatedTree {
        check(
            Files.isDirectory(root, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(root) &&
                Files.isRegularFile(root.resolve("manifest.json"), NOFOLLOW_LINKS),
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
        record: ConversionWorkspaceOwnership.OwnershipRecord,
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
            deleteTree(quarantine)
        } catch (restoreError: Throwable) {
            failure.addSuppressed(restoreError)
            try {
                ConversionWorkspaceOwnership.poison(root, record)
            } catch (poisonError: WorkspacePoisonPersistenceException) {
                poisonError.addSuppressed(failure)
                throw poisonError
            }
        }
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private data class ValidatedTree(
        val state: WorkspaceTreeDigest.State,
    )

    private object TransactionContext : AdapterExecutionContext {
        private val current = ThreadLocal<State?>()

        override val originalRoot: Path
            get() = checked(State::originalRoot)

        override val stagedRoot: Path
            get() = checked(State::stagedRoot)

        override val profile: BrowserProfile
            get() = checked(State::profile)

        override fun requireExactOriginalTree() {
            checked { state ->
                check(
                    WorkspaceTreeDigest.provenanceSha256(state.originalRoot) ==
                        WorkspaceTreeDigest.provenanceSha256(state.stagedRoot),
                ) {
                    "storage adapter는 immutable original과 같은 stage에서만 실행됩니다."
                }
            }
        }

        @JvmSynthetic
        fun activate(
            originalRoot: Path,
            stagedRoot: Path,
            profile: BrowserProfile,
        ) {
            check(current.get() == null) {
                "adapter execution context가 이미 활성 상태입니다."
            }
            current.set(
                State(
                    active = AtomicBoolean(true),
                    originalRoot = originalRoot,
                    stagedRoot = stagedRoot,
                    profile = profile,
                ),
            )
        }

        @JvmSynthetic
        fun deactivate() {
            val state = requireNotNull(current.get()) {
                "adapter execution context가 활성 상태가 아닙니다."
            }
            check(state.active.compareAndSet(true, false)) {
                "adapter execution context가 이미 비활성 상태입니다."
            }
            current.remove()
        }

        private inline fun <T> checked(block: (State) -> T): T {
            val state = current.get()
            check(state != null && state.active.get()) {
                "adapter execution context가 활성 상태가 아닙니다."
            }
            return block(state)
        }

        private data class State(
            val active: AtomicBoolean,
            val originalRoot: Path,
            val stagedRoot: Path,
            val profile: BrowserProfile,
        )
    }

}
