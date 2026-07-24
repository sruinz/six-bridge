package dev.sixbridge.core.conversion

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

internal enum class WorkspacePermissionMode {
    POSIX_OWNER_ONLY,
    ACL_OWNER_ONLY,
    POSIX_AND_ACL_OWNER_ONLY,
    UNSUPPORTED,
}

internal enum class WorkspaceMetadataMode {
    POSIX,
    ACL,
    POSIX_AND_ACL,
    UNSUPPORTED,
}

internal data class WorkspaceAclState(
    val type: String,
    val principal: String,
    val permissions: List<String>,
    val flags: List<String>,
)

internal data class WorkspaceAccessState(
    val owner: String,
    val posixPermissions: List<String>?,
    val aclEntries: List<WorkspaceAclState>?,
)

internal data class WorkspacePathIdentity(
    val path: Path,
    val fileKey: Any,
    val device: Long,
    val inode: Long,
    val access: WorkspaceAccessState,
)

internal object WorkspacePermissionPolicy {
    fun requirePrivate(path: Path, freshWorkspaceRoot: Path) {
        capturePrivateState(path, freshWorkspaceRoot)
    }

    fun capturePrivateState(
        path: Path,
        freshWorkspaceRoot: Path,
    ): WorkspaceAccessState {
        val normalizedRoot = freshWorkspaceRoot.toAbsolutePath().normalize()
        val normalizedPath = path.toAbsolutePath().normalize()
        check(normalizedPath.startsWith(normalizedRoot)) {
            "workspace private path가 발급된 root 밖에 있습니다."
        }
        val capture = capture(path)
        val mode = selectPrivateMode(
            posixAvailable = capture.state.posixPermissions != null,
            posixOwnerOnly = capture.posixOwnerOnly,
            aclAvailable = capture.state.aclEntries != null,
            aclOwnerOnly = capture.aclOwnerOnly,
        )
        check(mode != WorkspacePermissionMode.UNSUPPORTED) {
            "workspace filesystem의 owner-only 경계를 확인할 수 없습니다."
        }
        return capture.state
    }

    fun captureState(path: Path): WorkspaceAccessState {
        val capture = capture(path)
        check(
            selectMetadataMode(
                posixAvailable = capture.state.posixPermissions != null,
                aclAvailable = capture.state.aclEntries != null,
            ) != WorkspaceMetadataMode.UNSUPPORTED,
        ) {
            "workspace metadata를 검증할 수 없는 filesystem입니다."
        }
        return capture.state
    }

    fun captureIdentity(path: Path): WorkspacePathIdentity {
        val normalized = path.toAbsolutePath().normalize()
        val before = Files.readAttributes(
            normalized,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        check(
            (before.isDirectory || before.isRegularFile) &&
                !before.isSymbolicLink &&
                before.fileKey() != null,
        ) {
            "path identity를 확인할 수 없습니다: $normalized"
        }
        val unix = Files.readAttributes(
            normalized,
            "unix:dev,ino",
            NOFOLLOW_LINKS,
        )
        val identity = WorkspacePathIdentity(
            path = normalized,
            fileKey = requireNotNull(before.fileKey()),
            device = (unix["dev"] as Number).toLong(),
            inode = (unix["ino"] as Number).toLong(),
            access = captureState(normalized),
        )
        val after = Files.readAttributes(
            normalized,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        check(after.fileKey() == identity.fileKey) {
            "path identity가 조회 중 변경되었습니다: $normalized"
        }
        return identity
    }

    fun requireSameIdentity(expected: WorkspacePathIdentity) {
        check(captureIdentity(expected.path) == expected) {
            "path identity 또는 접근 상태가 변경되었습니다: ${expected.path}"
        }
    }

    private fun capture(path: Path): AccessCapture {
        val posixView = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        )
        val aclView = Files.getFileAttributeView(
            path,
            AclFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        )
        val posixPermissions = posixView?.readAttributes()?.permissions()?.toSet()
        val aclOwner = aclView?.owner
        val acl = aclView?.acl?.toList()
        return AccessCapture(
            state = WorkspaceAccessState(
                owner = ownerToken(path),
                posixPermissions = posixPermissions
                    ?.map(PosixFilePermission::name)
                    ?.sorted(),
                aclEntries = acl?.map { entry ->
                    WorkspaceAclState(
                        type = entry.type().name,
                        principal = entry.principal().name,
                        permissions = entry.permissions()
                            .map(AclEntryPermission::name)
                            .sorted(),
                        flags = entry.flags().map { it.name }.sorted(),
                    )
                },
            ),
            posixOwnerOnly = posixPermissions?.let(::isOwnerOnly) ?: false,
            aclOwnerOnly = aclOwner != null &&
                acl != null &&
                isOwnerOnlyAcl(aclOwner, acl),
        )
    }

    fun requireMetadataSupport(path: Path) {
        val posixAvailable = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        ) != null
        val aclAvailable = Files.getFileAttributeView(
            path,
            AclFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        ) != null
        val mode = selectMetadataMode(
            posixAvailable = posixAvailable,
            aclAvailable = aclAvailable,
        )
        check(mode != WorkspaceMetadataMode.UNSUPPORTED) {
            "workspace metadata를 검증할 수 없는 filesystem입니다."
        }
    }

    internal fun selectPrivateMode(
        posixAvailable: Boolean,
        posixOwnerOnly: Boolean,
        aclAvailable: Boolean,
        aclOwnerOnly: Boolean,
    ): WorkspacePermissionMode = when {
        posixAvailable && aclAvailable && posixOwnerOnly && aclOwnerOnly ->
            WorkspacePermissionMode.POSIX_AND_ACL_OWNER_ONLY
        posixAvailable && !aclAvailable && posixOwnerOnly ->
            WorkspacePermissionMode.POSIX_OWNER_ONLY
        !posixAvailable && aclAvailable && aclOwnerOnly ->
            WorkspacePermissionMode.ACL_OWNER_ONLY
        else -> WorkspacePermissionMode.UNSUPPORTED
    }

    internal fun selectMetadataMode(
        posixAvailable: Boolean,
        aclAvailable: Boolean,
    ): WorkspaceMetadataMode = when {
        posixAvailable && aclAvailable -> WorkspaceMetadataMode.POSIX_AND_ACL
        posixAvailable -> WorkspaceMetadataMode.POSIX
        aclAvailable -> WorkspaceMetadataMode.ACL
        else -> WorkspaceMetadataMode.UNSUPPORTED
    }

    private fun isOwnerOnly(
        permissions: Set<PosixFilePermission>,
    ): Boolean {
        val shared = setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        return permissions.none(shared::contains)
    }

    private fun ownerToken(path: Path): String = runCatching {
        "uid:${(Files.getAttribute(path, "unix:uid", NOFOLLOW_LINKS) as Number).toLong()}"
    }.getOrElse {
        "principal:${Files.getOwner(path, NOFOLLOW_LINKS).name}"
    }

    internal fun isOwnerOnlyAcl(
        owner: java.nio.file.attribute.UserPrincipal,
        entries: List<java.nio.file.attribute.AclEntry>,
    ): Boolean {
        val sensitive = setOf(
            AclEntryPermission.READ_DATA,
            AclEntryPermission.WRITE_DATA,
            AclEntryPermission.APPEND_DATA,
            AclEntryPermission.DELETE,
            AclEntryPermission.DELETE_CHILD,
            AclEntryPermission.EXECUTE,
            AclEntryPermission.LIST_DIRECTORY,
            AclEntryPermission.ADD_FILE,
            AclEntryPermission.ADD_SUBDIRECTORY,
            AclEntryPermission.WRITE_ATTRIBUTES,
            AclEntryPermission.WRITE_NAMED_ATTRS,
            AclEntryPermission.WRITE_ACL,
            AclEntryPermission.WRITE_OWNER,
        )
        return entries.none { entry ->
            entry.type() == AclEntryType.ALLOW &&
                entry.principal() != owner &&
                entry.permissions().any(sensitive::contains)
        }
    }

    private data class AccessCapture(
        val state: WorkspaceAccessState,
        val posixOwnerOnly: Boolean,
        val aclOwnerOnly: Boolean,
    )
}
