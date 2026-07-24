package dev.sixbridge.core.conversion

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

internal enum class WorkspacePermissionMode {
    POSIX_OWNER_ONLY,
    ACL_OWNER_ONLY,
    UNSUPPORTED,
}

internal enum class WorkspaceMetadataMode {
    POSIX,
    ACL,
    UNSUPPORTED,
}

internal object WorkspacePermissionPolicy {
    fun requirePrivate(path: Path, freshWorkspaceRoot: Path) {
        val normalizedRoot = freshWorkspaceRoot.toAbsolutePath().normalize()
        val normalizedPath = path.toAbsolutePath().normalize()
        check(normalizedPath.startsWith(normalizedRoot)) {
            "workspace private path가 발급된 root 밖에 있습니다."
        }
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
        val mode = selectPrivateMode(
            posixAvailable = posixView != null,
            posixOwnerOnly = posixView
                ?.readAttributes()
                ?.permissions()
                ?.let(::isOwnerOnly)
                ?: false,
            aclAvailable = aclView != null,
            aclOwnerOnly = aclView?.let(::ownerOnlyAcl) ?: false,
        )
        check(mode != WorkspacePermissionMode.UNSUPPORTED) {
            "workspace filesystem의 owner-only 경계를 확인할 수 없습니다."
        }
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
        posixAvailable && posixOwnerOnly ->
            WorkspacePermissionMode.POSIX_OWNER_ONLY
        !posixAvailable && aclAvailable && aclOwnerOnly ->
            WorkspacePermissionMode.ACL_OWNER_ONLY
        else -> WorkspacePermissionMode.UNSUPPORTED
    }

    internal fun selectMetadataMode(
        posixAvailable: Boolean,
        aclAvailable: Boolean,
    ): WorkspaceMetadataMode = when {
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

    private fun ownerOnlyAcl(view: AclFileAttributeView): Boolean {
        val owner = runCatching { view.owner }.getOrNull() ?: return false
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
        )
        return runCatching {
            view.acl.none { entry ->
                entry.type() == AclEntryType.ALLOW &&
                    entry.principal() != owner &&
                    entry.permissions().any(sensitive::contains)
            }
        }.getOrDefault(false)
    }
}
