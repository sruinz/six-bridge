package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
        val treeSha256 = WorkspaceTreeDigest.sha256(key)
        check(treeSha256 == record.workingTreeSha256) {
            "conversion workspace tree가 등록된 상태와 다릅니다."
        }
        val contentSha256 = WorkspaceTreeDigest.contentSha256(key)
        val bytes = ExtensionZip.createBytes(
            sourceRoot = key,
            includeProjectMarker = true,
        )
        check(
            WorkspaceTreeDigest.sha256(key) == treeSha256 &&
                WorkspaceTreeDigest.contentSha256(key) == contentSha256
        ) {
            "conversion workspace tree가 snapshot 중 변경되었습니다."
        }
        verifyLocked(key, record, verifyTree = false)
        return CapturedTree(
            zipBytes = bytes,
            treeSha256 = treeSha256,
            contentSha256 = contentSha256,
        )
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
        record.workingFileKey = requireFileKey(key, "published working root")
        record.workingTreeSha256 = publishedTreeSha256
        verifyLocked(key, record, verifyTree = true)
    }

    fun poison(root: Path, record: OwnershipRecord) {
        record.poisoned = true
        runCatching {
            val key = key(root)
            verifyParent(key, record)
            val marker = markerPath(key)
            check(
                Files.isRegularFile(marker, NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(marker),
            )
            Files.writeString(
                marker,
                markerContent(POISONED, record.token),
                TRUNCATE_EXISTING,
                WRITE,
                NOFOLLOW_LINKS,
            )
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
        val zipBytes: ByteArray,
        val treeSha256: String,
        val contentSha256: String,
    )
}

internal class ConversionWorkspaceTransaction(
    private val moveDirectory: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, ATOMIC_MOVE)
    },
    private val beforePublish: () -> Unit = {},
) {
    fun <T> run(
        originalRoot: Path,
        workingRoot: Path,
        profile: BrowserProfile,
        transform: (AdapterExecutionContext) -> T,
    ): T {
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
                    transform,
                )
            } finally {
                record.active = false
            }
        }
    }

    private fun <T> runLocked(
        originalRoot: Path,
        root: Path,
        profile: BrowserProfile,
        record: ConversionWorkspaceOwnership.OwnershipRecord,
        transform: (AdapterExecutionContext) -> T,
    ): T {
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
            stagedRoot = ExtensionZip.extract(sourceTree.zipBytes, stageDestination)
            val context = ActiveAdapterExecutionContext.create(
                originalRoot = originalRoot,
                stagedRoot = stagedRoot,
                profile = profile,
            )
            val result = try {
                transform(context)
            } finally {
                context.deactivate()
            }
            val stagedTree = validateCompleteTree(stagedRoot)
            beforePublish()
            val latestWorkingTree =
                ConversionWorkspaceOwnership.captureWorkingTreeLocked(
                    root,
                    record,
                )
            check(
                sourceTree.treeSha256 == latestWorkingTree.treeSha256 &&
                    sourceTree.contentSha256 == latestWorkingTree.contentSha256 &&
                    sourceTree.zipBytes.contentEquals(latestWorkingTree.zipBytes)
            ) {
                "conversion workspace가 transaction 실행 중 변경되었습니다."
            }
            if (
                sourceTree.contentSha256 == stagedTree.contentSha256 &&
                sourceTree.zipBytes.contentEquals(stagedTree.zipBytes)
            ) {
                return result
            }

            moveDirectory(root, backup)
            try {
                moveDirectory(stagedRoot, root)
                stagedRoot = null
            } catch (publishError: Throwable) {
                try {
                    moveDirectory(backup, root)
                } catch (restoreError: Throwable) {
                    publishError.addSuppressed(restoreError)
                    ConversionWorkspaceOwnership.poison(root, record)
                }
                throw publishError
            }
            try {
                ConversionWorkspaceOwnership.updatePublishedLocked(
                    root,
                    record,
                    stagedTree.treeSha256,
                )
            } catch (validationError: Throwable) {
                ConversionWorkspaceOwnership.poison(root, record)
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
        val treeSha256 = WorkspaceTreeDigest.sha256(root)
        val contentSha256 = WorkspaceTreeDigest.contentSha256(root)
        val zipBytes = ExtensionZip.createBytes(
            sourceRoot = root,
            includeProjectMarker = true,
        )
        check(
            WorkspaceTreeDigest.sha256(root) == treeSha256 &&
                WorkspaceTreeDigest.contentSha256(root) == contentSha256
        ) {
            "staged conversion tree가 snapshot 중 변경되었습니다."
        }
        val validationDestination = requireNotNull(root.parent).resolve(
            ".sixbridge-validate-${UUID.randomUUID()}",
        )
        try {
            val validationRoot = ExtensionZip.extract(
                zipBytes,
                validationDestination,
            )
            check(
                WorkspaceTreeDigest.contentSha256(validationRoot) == contentSha256
            ) {
                "staged conversion tree에 bundle에 포함되지 않는 항목이 있습니다."
            }
        } finally {
            deleteTree(validationDestination)
        }
        return ValidatedTree(
            zipBytes = zipBytes,
            treeSha256 = treeSha256,
            contentSha256 = contentSha256,
        )
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private data class ValidatedTree(
        val zipBytes: ByteArray,
        val treeSha256: String,
        val contentSha256: String,
    )
}
