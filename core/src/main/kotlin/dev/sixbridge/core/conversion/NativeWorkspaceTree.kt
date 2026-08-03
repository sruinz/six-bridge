package dev.sixbridge.core.conversion

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission
import kotlin.jvm.JvmSynthetic

internal object NativeWorkspaceTree {
    @JvmSynthetic
    fun copyInto(source: Path, destination: Path): Path {
        val sourceRoot = source.toAbsolutePath().normalize()
        val targetRoot = destination.toAbsolutePath().normalize()
        check(
            Files.isDirectory(sourceRoot, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(sourceRoot) &&
                Files.isDirectory(targetRoot, NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(targetRoot) &&
                Files.list(targetRoot).use { it.findAny().isEmpty },
        ) {
            "native workspace copy 경계를 확인할 수 없습니다."
        }
        PendingTempCleanup.requireOwned(targetRoot)
        val preservePrincipals = sourceRoot.fileSystem == targetRoot.fileSystem
        val before = WorkspaceTreeDigest.capture(sourceRoot)
        copyTree(sourceRoot, targetRoot, preservePrincipals)
        val sourceAfter = WorkspaceTreeDigest.capture(sourceRoot)
        val target = WorkspaceTreeDigest.capture(targetRoot)
        check(before == sourceAfter) {
            "native workspace source가 copy 중 변경되었습니다."
        }
        val sourceProvenance = if (preservePrincipals) {
            before.provenanceSha256
        } else {
            before.portableProvenanceSha256
        }
        val targetProvenance = if (preservePrincipals) {
            target.provenanceSha256
        } else {
            target.portableProvenanceSha256
        }
        check(sourceProvenance == targetProvenance) {
            "native workspace metadata 또는 content를 보존하지 못했습니다."
        }
        return targetRoot
    }

    private fun copyTree(
        sourceRoot: Path,
        targetRoot: Path,
        preservePrincipals: Boolean,
    ) {
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
                if (preservePrincipals) {
                    Files.copy(
                        source,
                        target,
                        COPY_ATTRIBUTES,
                        NOFOLLOW_LINKS,
                    )
                } else {
                    Files.copy(source, target, NOFOLLOW_LINKS)
                }
                copyMetadata(source, target, preservePrincipals)
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
                copyMetadata(source, target, preservePrincipals)
            }
    }

    private fun copyMetadata(
        source: Path,
        target: Path,
        preservePrincipals: Boolean,
    ) {
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
            if (preservePrincipals) {
                targetPosix.setOwner(attributes.owner())
                targetPosix.setGroup(attributes.group())
                targetPosix.setPermissions(attributes.permissions())
            } else {
                targetPosix.setPermissions(
                    if (basic.isDirectory) OWNER_DIRECTORY_PERMISSIONS else OWNER_FILE_PERMISSIONS,
                )
            }
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
            if (preservePrincipals) {
                targetAcl.owner = sourceAcl.owner
                targetAcl.acl = sourceAcl.acl
            }
        }
        if (sourcePosix == null && sourceAcl == null) {
            WorkspacePermissionPolicy.requireMetadataSupport(source)
        }
        if (targetPosix == null && targetAcl == null) {
            WorkspacePermissionPolicy.requireMetadataSupport(target)
        }
    }

    private val OWNER_FILE_PERMISSIONS = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )

    private val OWNER_DIRECTORY_PERMISSIONS = OWNER_FILE_PERMISSIONS +
        PosixFilePermission.OWNER_EXECUTE
}
