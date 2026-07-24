package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.util.Comparator
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object ConversionWorkspaceOwnership {
    private const val MARKER_SUFFIX = ".sixbridge-workspace-owner"

    private val records = ConcurrentHashMap<Path, OwnershipRecord>()

    fun register(root: Path) {
        val key = key(root)
        check(!records.containsKey(key)) { "이미 등록된 conversion workspace입니다." }
        val parent = requireNotNull(key.parent)
        requirePrivateDirectory(parent)
        val token = UUID.randomUUID().toString()
        writeMarker(key, token)
        records[key] = OwnershipRecord(
            token = token,
            parentFileKey = requireNotNull(fileKey(parent)) {
                "conversion workspace parent file identity를 확인할 수 없습니다."
            },
            poisoned = false,
        )
        requireUsable(key)
    }

    fun unregister(root: Path) {
        records.remove(key(root))
    }

    fun requireUsable(root: Path): OwnershipRecord {
        val key = key(root)
        val record = records[key]
            ?: throw IllegalStateException(
                "same-process ConversionWorkspace ownership을 확인할 수 없습니다.",
            )
        if (record.poisoned) {
            throw IllegalStateException("conversion workspace output이 poison 상태입니다.")
        }
        val parent = requireNotNull(key.parent)
        if (!Files.isDirectory(key, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(key) ||
            Files.isSymbolicLink(parent) ||
            fileKey(parent) != record.parentFileKey
        ) {
            throw IllegalStateException("conversion workspace 경계가 변경되었습니다.")
        }
        requirePrivateDirectory(parent)
        val marker = markerPath(key)
        if (!Files.isRegularFile(marker, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(marker) ||
            Files.readString(marker) != record.token
        ) {
            throw IllegalStateException("conversion workspace ownership marker가 다릅니다.")
        }
        requireOwnerOnly(marker)
        return record
    }

    fun writeMarker(root: Path, token: String) {
        val marker = markerPath(key(root))
        Files.writeString(marker, token, CREATE_NEW, WRITE, NOFOLLOW_LINKS)
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

    fun poison(root: Path) {
        val key = key(root)
        records.computeIfPresent(key) { _, record ->
            record.copy(poisoned = true)
        }
    }

    private fun requireOwnerOnly(path: Path) {
        val permissions = runCatching {
            Files.getPosixFilePermissions(path, NOFOLLOW_LINKS)
        }.getOrNull() ?: return
        require(
            permissions.none {
                it in setOf(
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_WRITE,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_WRITE,
                    PosixFilePermission.OTHERS_EXECUTE,
                )
            },
        ) {
            "conversion workspace marker 권한이 owner-only가 아닙니다."
        }
    }

    private fun requirePrivateDirectory(path: Path) {
        if (!Files.isDirectory(path, NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw IllegalStateException("conversion workspace parent가 안전한 디렉터리가 아닙니다.")
        }
        requireOwnerOnly(path)
    }

    private fun fileKey(path: Path): String? =
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        ).fileKey()?.toString()

    private fun key(root: Path): Path = root.toAbsolutePath().normalize()

    private fun markerPath(root: Path): Path =
        requireNotNull(root.parent).resolve(".${root.fileName}$MARKER_SUFFIX")

    internal data class OwnershipRecord(
        val token: String,
        val parentFileKey: String,
        val poisoned: Boolean,
    )
}

internal class ConversionWorkspaceTransaction(
    private val moveDirectory: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, ATOMIC_MOVE)
    },
) {
    fun <T> run(
        workingRoot: Path,
        transform: (Path) -> T,
    ): T {
        val root = workingRoot.toAbsolutePath().normalize()
        ConversionWorkspaceOwnership.requireUsable(root)
        val parent = requireNotNull(root.parent)
        val stageDestination = parent.resolve(
            ".sixbridge-stage-${UUID.randomUUID()}",
        )
        val backup = parent.resolve(
            ".sixbridge-backup-${UUID.randomUUID()}",
        )
        var stagedRoot: Path? = null
        try {
            val snapshot = ExtensionZip.createBytes(
                sourceRoot = root,
                includeProjectMarker = true,
            )
            stagedRoot = ExtensionZip.extract(snapshot, stageDestination)
            val result = transform(stagedRoot)
            validateCompleteTree(stagedRoot)
            ConversionWorkspaceOwnership.requireUsable(root)

            moveDirectory(root, backup)
            try {
                moveDirectory(stagedRoot, root)
                stagedRoot = null
            } catch (publishError: Throwable) {
                try {
                    moveDirectory(backup, root)
                } catch (restoreError: Throwable) {
                    publishError.addSuppressed(restoreError)
                    ConversionWorkspaceOwnership.poison(root)
                }
                throw publishError
            }
            try {
                ConversionWorkspaceOwnership.requireUsable(root)
            } catch (validationError: Throwable) {
                ConversionWorkspaceOwnership.poison(root)
                throw validationError
            }
            runCatching { deleteTree(backup) }
            return result
        } finally {
            stagedRoot?.let(::deleteTree)
        }
    }

    private fun validateCompleteTree(root: Path) {
        if (!Files.isDirectory(root, NOFOLLOW_LINKS) ||
            Files.isSymbolicLink(root) ||
            !Files.isRegularFile(root.resolve("manifest.json"), NOFOLLOW_LINKS)
        ) {
            throw IllegalStateException("완성된 staged conversion tree를 검증할 수 없습니다.")
        }
        ExtensionZip.createBytes(
            sourceRoot = root,
            includeProjectMarker = true,
        )
    }

    private fun deleteTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }
}
