package dev.sixbridge.core.conversion

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

internal object WorkspaceTreeDigest {
    fun sha256(root: Path): String = digest(root, includeIdentity = true)

    fun contentSha256(root: Path): String = digest(
        root,
        includeIdentity = false,
    )

    private fun digest(root: Path, includeIdentity: Boolean): String {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val first = entries(normalizedRoot)
        val second = entries(normalizedRoot)
        check(first == second) {
            "workspace tree가 digest 계산 중 변경되었습니다."
        }
        val digest = MessageDigest.getInstance("SHA-256")
        first.forEach { entry ->
            digest.field(entry.relativePath)
            digest.field(entry.type)
            digest.field(if (entry.type == "file") entry.size.toString() else "")
            if (includeIdentity) digest.field(entry.fileKey)
            digest.field(entry.contentSha256)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun entries(root: Path): List<TreeEntry> =
        Files.walk(root).use { paths ->
            paths.filter { it != root }
                .sorted()
                .map { path ->
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
                    val verified = Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        NOFOLLOW_LINKS,
                    )
                    check(
                        attributes.fileKey() == verified.fileKey() &&
                            attributes.size() == verified.size() &&
                            attributes.lastModifiedTime() ==
                            verified.lastModifiedTime(),
                    ) {
                        "workspace tree entry가 digest 계산 중 변경되었습니다."
                    }
                    TreeEntry(
                        relativePath = root.relativize(path)
                            .joinToString("/") { it.toString() },
                        type = if (attributes.isDirectory) "directory" else "file",
                        size = attributes.size(),
                        fileKey = requireNotNull(attributes.fileKey()) {
                            "workspace tree entry identity를 확인할 수 없습니다."
                        }.toString(),
                        contentSha256 = contentSha256,
                    )
                }
                .toList()
        }

    private fun MessageDigest.field(value: String) {
        val bytes = value.toByteArray(UTF_8)
        update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes.size).array())
        update(bytes)
    }

    private data class TreeEntry(
        val relativePath: String,
        val type: String,
        val size: Long,
        val fileKey: String,
        val contentSha256: String,
    )
}
