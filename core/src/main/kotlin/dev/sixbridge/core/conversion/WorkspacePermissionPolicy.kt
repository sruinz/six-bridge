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
    ANDROID_APP_PRIVATE,
    UNSUPPORTED,
}

internal object WorkspacePermissionPolicy {
    fun requirePrivate(path: Path, freshWorkspaceRoot: Path) {
        val posixView = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        )
        val posixAvailable = posixView != null
        if (posixView != null) {
            requireOwnerOnly(posixView.readAttributes().permissions())
        }
        val aclOwnerOnly = if (posixAvailable) {
            false
        } else {
            ownerOnlyAcl(path)
        }
        val normalizedRoot = freshWorkspaceRoot.toAbsolutePath().normalize()
        val normalizedPath = path.toAbsolutePath().normalize()
        val mode = selectMode(
            posixAvailable = posixAvailable,
            aclOwnerOnly = aclOwnerOnly,
            runtimeName = System.getProperty("java.runtime.name").orEmpty(),
            insideFreshWorkspaceRoot = normalizedPath.startsWith(normalizedRoot),
        )
        check(mode != WorkspacePermissionMode.UNSUPPORTED) {
            "workspace filesystem의 owner-only 경계를 확인할 수 없습니다."
        }
    }

    internal fun selectMode(
        posixAvailable: Boolean,
        aclOwnerOnly: Boolean,
        runtimeName: String,
        insideFreshWorkspaceRoot: Boolean,
    ): WorkspacePermissionMode = when {
        posixAvailable -> WorkspacePermissionMode.POSIX_OWNER_ONLY
        aclOwnerOnly -> WorkspacePermissionMode.ACL_OWNER_ONLY
        runtimeName.contains("Android", ignoreCase = true) &&
            insideFreshWorkspaceRoot -> WorkspacePermissionMode.ANDROID_APP_PRIVATE
        else -> WorkspacePermissionMode.UNSUPPORTED
    }

    private fun requireOwnerOnly(permissions: Set<PosixFilePermission>) {
        val shared = setOf(
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE,
        )
        require(permissions.none(shared::contains)) {
            "workspace 권한이 owner-only가 아닙니다."
        }
    }

    private fun ownerOnlyAcl(path: Path): Boolean {
        val view = Files.getFileAttributeView(
            path,
            AclFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        ) ?: return false
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
