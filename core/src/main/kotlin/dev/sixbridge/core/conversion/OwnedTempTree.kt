package dev.sixbridge.core.conversion

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import kotlin.jvm.JvmSynthetic

internal object OwnedTempTree {
    @JvmSynthetic
    fun delete(path: Path) {
        try {
            deleteEntry(path.toAbsolutePath().normalize())
        } catch (_: Exception) {
            throw OwnedTempCleanupException()
        }
    }

    private fun deleteEntry(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        if (!attributes.isDirectory || attributes.isSymbolicLink) {
            normalizeOwnerAccess(path, directory = false)
            Files.deleteIfExists(path)
            return
        }

        normalizeOwnerAccess(path, directory = true)
        val children = Files.newDirectoryStream(path).use { stream ->
            stream.toList()
        }
        children.forEach(::deleteEntry)
        normalizeOwnerAccess(path, directory = true)
        Files.deleteIfExists(path)
    }

    private fun normalizeOwnerAccess(path: Path, directory: Boolean) {
        if (Files.isSymbolicLink(path)) return
        runCatching {
            val view = Files.getFileAttributeView(
                path,
                PosixFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ) ?: return@runCatching
            val permissions = EnumSet.noneOf(PosixFilePermission::class.java)
            permissions += view.readAttributes().permissions()
            permissions += PosixFilePermission.OWNER_READ
            permissions += PosixFilePermission.OWNER_WRITE
            if (directory) permissions += PosixFilePermission.OWNER_EXECUTE
            view.setPermissions(permissions)
        }
        runCatching {
            val view = Files.getFileAttributeView(
                path,
                AclFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            ) ?: return@runCatching
            val owner = view.owner
            val ownerAllow = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(
                    EnumSet.allOf(AclEntryPermission::class.java),
                )
                .build()
            view.acl = listOf(ownerAllow) + view.acl.filterNot {
                it.type() == AclEntryType.DENY && it.principal() == owner
            }
        }
        runCatching {
            Files.getFileAttributeView(
                path,
                DosFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            )?.setReadOnly(false)
        }
    }
}

internal class OwnedTempCleanupException :
    IllegalStateException("소유한 임시 경로를 정리하지 못했습니다.")
