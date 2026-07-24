package dev.sixbridge.core.conversion

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.UserPrincipal
import java.time.Instant
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class NativeWorkspaceTreeTest {
    @Test
    fun `native copy preserves excluded files empty directories and metadata`() {
        val source = Files.createTempDirectory("sixbridge-native-source-")
        val destination = source.resolveSibling("${source.fileName}-copy")
        val excluded = Files.createDirectories(source.resolve("node_modules/cache"))
            .resolve("entry.js")
        excluded.writeText("preserved")
        Files.createDirectories(source.resolve("empty"))
        val modified = FileTime.fromMillis(1_700_000_000_000)
        Files.setLastModifiedTime(excluded, modified)
        runCatching {
            Files.setPosixFilePermissions(
                excluded,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        }

        NativeWorkspaceTree.copy(source, destination)

        assertEquals(
            "preserved",
            destination.resolve("node_modules/cache/entry.js").readText(),
        )
        assertTrue(Files.isDirectory(destination.resolve("empty")))
        assertEquals(modified, Files.getLastModifiedTime(destination.resolve(
            "node_modules/cache/entry.js",
        )))
        assertEquals(
            WorkspaceTreeDigest.provenanceSha256(source),
            WorkspaceTreeDigest.provenanceSha256(destination),
        )
    }

    @Test
    fun `native copy rejects symlinks`() {
        val source = Files.createTempDirectory("sixbridge-native-link-")
        val target = Files.createTempFile("sixbridge-native-target-", ".js")
        val linked = runCatching {
            Files.createSymbolicLink(source.resolve("linked.js"), target)
        }.isSuccess
        if (!linked) return

        assertFailsWith<IllegalStateException> {
            NativeWorkspaceTree.copy(
                source,
                source.resolveSibling("${source.fileName}-copy"),
            )
        }
    }

    @Test
    fun `metadata changes alter provenance digest`() {
        val source = Files.createTempDirectory("sixbridge-native-digest-")
        val file = source.resolve("entry.js")
        file.writeText("same")
        val before = WorkspaceTreeDigest.provenanceSha256(source)

        Files.setLastModifiedTime(
            file,
            FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 2_000),
        )

        assertTrue(before != WorkspaceTreeDigest.provenanceSha256(source))
    }

    @Test
    fun `sub-millisecond mtime changes alter provenance digest`() {
        val source = Files.createTempDirectory("sixbridge-native-nanos-")
        val file = source.resolve("entry.js")
        file.writeText("same")
        Files.setLastModifiedTime(
            file,
            FileTime.from(Instant.ofEpochSecond(1_700_000_000, 123_456_000)),
        )
        val before = WorkspaceTreeDigest.provenanceSha256(source)

        Files.setLastModifiedTime(
            file,
            FileTime.from(Instant.ofEpochSecond(1_700_000_000, 123_457_000)),
        )

        assertTrue(before != WorkspaceTreeDigest.provenanceSha256(source))
    }

    @Test
    fun `ACL entry order remains part of provenance metadata`() {
        val owner = principal("owner")
        val system = principal("SYSTEM")
        val first = acl(owner)
        val second = acl(system)

        assertTrue(
            WorkspaceTreeDigest.aclEntries(listOf(first, second)) !=
                WorkspaceTreeDigest.aclEntries(listOf(second, first)),
        )
    }

    private fun principal(name: String) = object : UserPrincipal {
        override fun getName(): String = name
    }

    private fun acl(principal: UserPrincipal): AclEntry =
        AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(principal)
            .setPermissions(AclEntryPermission.READ_DATA)
            .build()
}
