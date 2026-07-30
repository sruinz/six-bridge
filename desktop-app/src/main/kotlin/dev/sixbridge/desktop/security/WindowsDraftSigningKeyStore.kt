package dev.sixbridge.desktop.security

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipal
import java.util.EnumSet

internal interface WindowsAclAccess {
    fun applyOwnerOnly(path: Path)

    fun requireOwnerOnly(path: Path)
}

internal fun interface WindowsAtomicMove {
    fun move(source: Path, target: Path)
}

internal object WindowsOwnerAcl {
    fun ownerOnly(owner: UserPrincipal): List<AclEntry> = listOf(
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission::class.java))
            .build(),
    )
}

internal object NioWindowsAclAccess : WindowsAclAccess {
    override fun applyOwnerOnly(path: Path) {
        val view = view(path)
        view.acl = WindowsOwnerAcl.ownerOnly(view.owner)
        requireOwnerOnly(path)
    }

    override fun requireOwnerOnly(path: Path) {
        val view = view(path)
        val expected = WindowsOwnerAcl.ownerOnly(view.owner)
        require(view.acl == expected) {
            "Windows 전달 signer 경로 ACL이 현재 owner 전용이 아닙니다: $path"
        }
    }

    private fun view(path: Path): AclFileAttributeView = requireNotNull(
        Files.getFileAttributeView(
            path,
            AclFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        ),
    ) {
        "Windows 전달 signer 경로의 ACL을 확인할 수 없습니다: $path"
    }
}

internal val NIO_WINDOWS_ATOMIC_MOVE = WindowsAtomicMove { source, target ->
    Files.move(source, target, ATOMIC_MOVE)
}
