package dev.sixbridge.core.conversion

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileOwnerAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.security.MessageDigest

internal object WorkspaceTreeDigest {
    fun sha256(root: Path): String = capture(root).identitySha256

    fun contentSha256(root: Path): String = capture(root).contentSha256

    fun provenanceSha256(root: Path): String =
        capture(root).provenanceSha256

    fun capture(root: Path): State {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val first = entries(normalizedRoot)
        val second = entries(normalizedRoot)
        check(first == second) {
            "workspace tree가 digest 계산 중 변경되었습니다."
        }
        return State(
            identitySha256 = digest(first, includeIdentity = true, includeMetadata = true),
            provenanceSha256 = digest(
                first,
                includeIdentity = false,
                includeMetadata = true,
            ),
            contentSha256 = digest(
                first,
                includeIdentity = false,
                includeMetadata = false,
            ),
        )
    }

    private fun digest(
        entries: List<TreeEntry>,
        includeIdentity: Boolean,
        includeMetadata: Boolean,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        entries.forEach { entry ->
            digest.field(entry.relativePath)
            digest.field(entry.type)
            digest.field(if (entry.type == "file") entry.size.toString() else "")
            digest.field(entry.contentSha256)
            if (includeMetadata) {
                digest.field(entry.lastModified)
                digest.field(entry.owner)
                digest.field(entry.group)
                digest.field(entry.permissions)
                digest.field(entry.acl)
            }
            if (includeIdentity) digest.field(entry.fileKey)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun entries(root: Path): List<TreeEntry> =
        Files.walk(root).use { paths ->
            paths.sorted()
                .map { path -> entry(root, path) }
                .toList()
        }

    private fun entry(root: Path, path: Path): TreeEntry {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        check(
            !attributes.isSymbolicLink &&
                (attributes.isDirectory || attributes.isRegularFile),
        ) {
            "workspace tree에는 일반 파일과 디렉터리만 허용됩니다."
        }
        val contentSha256 = if (attributes.isRegularFile) {
            MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(path))
                .joinToString("") { "%02x".format(it) }
        } else {
            ""
        }
        val posix = Files.getFileAttributeView(
            path,
            PosixFileAttributeView::class.java,
            NOFOLLOW_LINKS,
        )?.readAttributes()
        val aclView = if (posix == null) {
            Files.getFileAttributeView(
                path,
                AclFileAttributeView::class.java,
                NOFOLLOW_LINKS,
            )
        } else {
            null
        }
        WorkspacePermissionPolicy.requireMetadataSupport(path)
        val owner = posix?.owner()?.name
            ?: aclView?.owner?.name
            ?: Files.getFileAttributeView(
                path,
                FileOwnerAttributeView::class.java,
                NOFOLLOW_LINKS,
            )?.owner?.name
            .orEmpty()
        val verified = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        check(
            attributes.fileKey() == verified.fileKey() &&
                attributes.size() == verified.size() &&
                attributes.lastModifiedTime() == verified.lastModifiedTime(),
        ) {
            "workspace tree entry가 digest 계산 중 변경되었습니다."
        }
        return TreeEntry(
            relativePath = if (path == root) {
                "."
            } else {
                root.relativize(path).joinToString("/") { it.toString() }
            },
            type = if (attributes.isDirectory) "directory" else "file",
            size = attributes.size(),
            fileKey = requireNotNull(attributes.fileKey()) {
                "workspace tree entry identity를 확인할 수 없습니다."
            }.toString(),
            contentSha256 = contentSha256,
            lastModified = attributes.lastModifiedTime()
                .toInstant()
                .let { "${it.epochSecond}:${it.nano}" },
            owner = owner,
            group = posix?.group()?.name.orEmpty(),
            permissions = posix?.permissions()
                ?.map(Enum<*>::name)
                ?.sorted()
                ?.joinToString(",")
                .orEmpty(),
            acl = aclEntries(aclView?.acl.orEmpty()),
        )
    }

    internal fun aclEntries(entries: List<AclEntry>): String =
        entries.joinToString("|") { entry ->
            listOf(
                entry.type().name,
                entry.principal().name,
                entry.flags().map(Enum<*>::name).sorted().joinToString(","),
                entry.permissions().map(Enum<*>::name).sorted().joinToString(","),
            ).joinToString(":")
        }

    private fun MessageDigest.field(value: String) {
        val bytes = value.toByteArray(UTF_8)
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        update(bytes)
    }

    data class State(
        val identitySha256: String,
        val provenanceSha256: String,
        val contentSha256: String,
    )

    private data class TreeEntry(
        val relativePath: String,
        val type: String,
        val size: Long,
        val fileKey: String,
        val contentSha256: String,
        val lastModified: String,
        val owner: String,
        val group: String,
        val permissions: String,
        val acl: String,
    )
}
