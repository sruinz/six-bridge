package dev.sixbridge.core.conversion

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView

internal object NativeWorkspaceTree {
    fun copy(source: Path, destination: Path): Path {
        val sourceRoot = source.toAbsolutePath().normalize()
        val targetRoot = destination.toAbsolutePath().normalize()
        check(
            Files.isDirectory(sourceRoot, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(sourceRoot) &&
                !Files.exists(targetRoot, NOFOLLOW_LINKS),
        ) {
            "native workspace copy 경계를 확인할 수 없습니다."
        }
        val before = WorkspaceTreeDigest.capture(sourceRoot)
        PendingTempCleanup.createDirectory(targetRoot)
        try {
            copyTree(sourceRoot, targetRoot)
            val sourceAfter = WorkspaceTreeDigest.capture(sourceRoot)
            val target = WorkspaceTreeDigest.capture(targetRoot)
            check(before == sourceAfter) {
                "native workspace source가 copy 중 변경되었습니다."
            }
            check(before.provenanceSha256 == target.provenanceSha256) {
                "native workspace metadata 또는 content를 보존하지 못했습니다."
            }
            return targetRoot
        } catch (error: Throwable) {
            deleteTree(targetRoot)
            throw error
        }
    }

    private fun copyTree(sourceRoot: Path, targetRoot: Path) {
        val entries = Files.walk(sourceRoot).use { paths ->
            paths.sorted().toList()
        }
        entries.filter { it != sourceRoot }.forEach { source ->
            val attributes = Files.readAttributes(
                source,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            check(
                !attributes.isSymbolicLink &&
                    (attributes.isDirectory || attributes.isRegularFile),
            ) {
                "native workspace에는 일반 파일과 디렉터리만 허용됩니다."
            }
            val target = targetRoot.resolve(sourceRoot.relativize(source).toString())
            if (attributes.isDirectory) {
                Files.createDirectory(target)
            } else {
                Files.copy(
                    source,
                    target,
                    COPY_ATTRIBUTES,
                    NOFOLLOW_LINKS,
                )
                copyMetadata(source, target)
            }
        }
        entries.asReversed()
            .filter { Files.isDirectory(it, NOFOLLOW_LINKS) }
            .forEach { source ->
                val target = if (source == sourceRoot) {
                    targetRoot
                } else {
                    targetRoot.resolve(sourceRoot.relativize(source).toString())
                }
                copyMetadata(source, target)
            }
    }

    private fun copyMetadata(source: Path, target: Path) {
        val basic = Files.readAttributes(
            source,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        Files.getFileAttributeView(
            target,
            BasicFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        ).setTimes(basic.lastModifiedTime(), null, null)
        val sourcePosix = Files.getFileAttributeView(
            source,
            PosixFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        )
        val targetPosix = Files.getFileAttributeView(
            target,
            PosixFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        )
        if (sourcePosix != null || targetPosix != null) {
            check(sourcePosix != null && targetPosix != null) {
                "POSIX metadata view를 동일하게 보존할 수 없습니다."
            }
            val attributes = sourcePosix.readAttributes()
            targetPosix.setOwner(attributes.owner())
            targetPosix.setGroup(attributes.group())
            targetPosix.setPermissions(attributes.permissions())
        }
        val sourceAcl = Files.getFileAttributeView(
            source,
            AclFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        )
        val targetAcl = Files.getFileAttributeView(
            target,
            AclFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        )
        if (sourceAcl != null || targetAcl != null) {
            check(sourceAcl != null && targetAcl != null) {
                "ACL metadata view를 동일하게 보존할 수 없습니다."
            }
            targetAcl.owner = sourceAcl.owner
            targetAcl.acl = sourceAcl.acl
        }
        if (sourcePosix == null && sourceAcl == null) {
            WorkspacePermissionPolicy.requireMetadataSupport(source)
        }
        if (targetPosix == null && targetAcl == null) {
            WorkspacePermissionPolicy.requireMetadataSupport(target)
        }
    }

    private fun deleteTree(path: Path) {
        OwnedTempTree.delete(path)
    }
}
