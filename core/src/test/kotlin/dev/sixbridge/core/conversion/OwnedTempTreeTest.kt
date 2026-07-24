package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnedTempTreeTest {
    @Test
    fun `owner is sealed before an existing root is populated`() {
        val parent = OwnedTempTree.create("six-owned-populate-")
        try {
            val canonical = OwnedTempTree.createNamed(parent, "canonical")
            val source = Files.createTempDirectory("six-owned-populate-source-")
            source.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Owned","version":"1"}""",
            )
            val archive = ExtensionZip.createBytes(source)

            val extracted = ExtensionZip.extractInto(
                archive,
                canonical.rootPath,
            )

            assertEquals(canonical.rootPath, extracted)
            assertTrue(Files.isRegularFile(extracted.resolve("manifest.json")))
        } finally {
            OwnedTempTree.delete(parent)
        }
    }

    @Test
    fun `one workspace cannot retain more than its owner quota`() {
        val root = OwnedTempTree.create("six-owned-quota-")
        try {
            repeat(OwnedTempTree.MAX_OWNERS_PER_TREE - 1) { index ->
                OwnedTempTree.createNamed(root, "child-$index")
            }

            assertFailsWith<OwnedTempCleanupException> {
                OwnedTempTree.createNamed(root, "over-quota")
            }
            assertFalse(Files.exists(root.rootPath.resolve("over-quota")))
        } finally {
            OwnedTempTree.delete(root)
        }
    }

    @Test
    fun `global owner quota fails before another temp root is created`() {
        val owners = mutableListOf<OwnedTempDirectory>()
        try {
            repeat(OwnedTempTree.MAX_ACTIVE_OWNERS) {
                owners += OwnedTempTree.create("six-owned-global-quota-")
            }

            assertFailsWith<OwnedTempCleanupException> {
                OwnedTempTree.create("six-owned-global-over-")
            }
        } finally {
            owners.asReversed().forEach(OwnedTempTree::delete)
        }
    }

    @Test
    fun `explicit abandon closes ownership without deleting any filesystem entry`() {
        val owner = OwnedTempTree.create("six-owned-abandon-")
        val proof = owner.rootPath.resolve("proof.txt")
        proof.writeText("preserve")

        OwnedTempTree.abandon(owner)

        assertEquals("preserve", Files.readString(proof))
        assertTrue(Files.isDirectory(owner.rootPath, NOFOLLOW_LINKS))
    }

    @Test
    fun `registration failure without file identity never deletes the created path`() {
        val archive = Files.createTempFile("six-owned-provider-", ".zip")
        Files.delete(archive)
        val uri = URI.create("jar:${archive.toUri()}")
        FileSystems.newFileSystem(uri, mapOf("create" to "true")).use { fileSystem ->
            val parent = fileSystem.getPath("/parent")
            Files.createDirectory(parent)
            val target = parent.resolve("owned")

            assertFailsWith<OwnedTempCleanupException> {
                OwnedTempTree.createAt(target)
            }

            assertTrue(Files.exists(target, NOFOLLOW_LINKS))
        }
    }

    @Test
    fun `a replaced parent path cannot redirect nested owner creation`() {
        val parent = OwnedTempTree.create("six-owned-parent-swap-")
        val moved = parent.rootPath.resolveSibling(
            "${parent.rootPath.fileName}-moved",
        )
        val outside = Files.createTempDirectory("six-owned-parent-outside-")
        Files.move(parent.rootPath, moved)
        Files.createSymbolicLink(parent.rootPath, outside)
        try {
            assertFailsWith<OwnedTempCleanupException> {
                OwnedTempTree.createNamed(parent, "child")
            }
            assertFalse(Files.exists(outside.resolve("child"), NOFOLLOW_LINKS))

            Files.delete(parent.rootPath)
            Files.move(moved, parent.rootPath)
            OwnedTempTree.delete(parent)
        } finally {
            runCatching { Files.deleteIfExists(parent.rootPath) }
            runCatching { Files.move(moved, parent.rootPath) }
            runCatching { OwnedTempTree.abandon(parent) }
        }
    }
}
