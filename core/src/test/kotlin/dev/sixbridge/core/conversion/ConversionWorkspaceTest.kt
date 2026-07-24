package dev.sixbridge.core.conversion

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.lang.reflect.InvocationTargetException
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConversionWorkspaceTest {
    @Test
    fun `creates deterministic isolated snapshots without changing the source`() {
        val source = source("six-snapshot")
        source.resolve("scripts").also(Files::createDirectories)
            .resolve("background.js").writeText("chrome.runtime.onInstalled.addListener(() => {})")
        val before = hashTree(source)
        lateinit var originalCopy: Path
        lateinit var workingCopy: Path

        ConversionWorkspace().use { workspace ->
            val first = workspace.snapshot(source)
            val second = workspace.snapshot(source)
            originalCopy = first.originalRoot
            workingCopy = first.workingRoot

            assertEquals(before, hashTree(source))
            assertContentEquals(first.sourceZip, second.sourceZip)
            assertEquals(first.sourceSha256, second.sourceSha256)
            assertFalse(source.resolve(".sixbridge-project-id").exists())
            assertNotEquals(first.originalRoot, first.workingRoot)
            assertEquals(hashTree(first.originalRoot), hashTree(first.workingRoot))

            first.workingRoot.resolve("manifest.json").writeText("""{"name":"changed"}""")
            assertNotEquals(hashTree(first.originalRoot), hashTree(first.workingRoot))
            assertEquals(before, hashTree(source))
        }

        assertFalse(originalCopy.exists())
        assertFalse(workingCopy.exists())
    }

    @Test
    fun `retains an existing identity marker in both isolated copies`() {
        val source = source("six-marker-snapshot")
        source.resolve(".sixbridge-project-id")
            .writeText("sixbridge-project-v1:${"a".repeat(32)}")

        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)

            assertTrue(snapshot.originalRoot.resolve(".sixbridge-project-id").exists())
            assertTrue(snapshot.workingRoot.resolve(".sixbridge-project-id").exists())
        }
    }

    @Test
    fun `rejects cross-copy A to B to A source provenance`() {
        val source = source("six-cross-copy-aba")
        val script = source.resolve("content.js")
        val fixedTime = FileTime.fromMillis(1_700_000_000_000)
        script.writeText("A")
        Files.setLastModifiedTime(script, fixedTime)
        val expected = WorkspaceTreeDigest.capture(source)
        val original = NativeWorkspaceTree.copy(
            source,
            source.resolveSibling("${source.fileName}-original"),
        )
        script.writeText("B")
        Files.setLastModifiedTime(script, fixedTime)
        val working = NativeWorkspaceTree.copy(
            source,
            source.resolveSibling("${source.fileName}-working"),
        )
        script.writeText("A")
        Files.setLastModifiedTime(script, fixedTime)
        assertEquals(expected.provenanceSha256, WorkspaceTreeDigest
            .capture(source).provenanceSha256)
        assertEquals(expected.provenanceSha256, WorkspaceTreeDigest
            .capture(original).provenanceSha256)
        assertNotEquals(expected.provenanceSha256, WorkspaceTreeDigest
            .capture(working).provenanceSha256)

        ConversionWorkspace().use { workspace ->
            val method = ConversionWorkspace::class.java.getDeclaredMethod(
                "requireSnapshotCopy",
                WorkspaceTreeDigest.State::class.java,
                Path::class.java,
                String::class.java,
            ).also { it.isAccessible = true }

            val error = assertFailsWith<InvocationTargetException> {
                method.invoke(workspace, expected, working, "working")
            }

            assertTrue(error.cause is IllegalStateException)
        }
    }

    @Test
    fun `rejects symlinks fifos and sockets in source trees`() {
        val symlinkSource = source("six-symlink-source")
        Files.createSymbolicLink(
            symlinkSource.resolve("linked.js"),
            symlinkSource.resolve("manifest.json"),
        )
        assertFailsWith<IllegalArgumentException> {
            ConversionWorkspace().use { it.snapshot(symlinkSource) }
        }

        val fifoSource = source("six-fifo-source")
        val fifo = fifoSource.resolve("events.pipe")
        assertEquals(0, ProcessBuilder("mkfifo", fifo.toString()).start().waitFor())
        assertFailsWith<IllegalArgumentException> {
            ConversionWorkspace().use { it.snapshot(fifoSource) }
        }

        val socketSource = source("six-socket-source")
        val socket = socketSource.resolve("events.sock")
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.bind(UnixDomainSocketAddress.of(socket))
            assertFailsWith<IllegalArgumentException> {
                ConversionWorkspace().use { it.snapshot(socketSource) }
            }
        }
    }

    private fun source(prefix: String): Path =
        Files.createTempDirectory(prefix).also {
            it.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Snapshot","version":"1"}""",
            )
        }

    private fun hashTree(root: Path): Map<String, String> =
        Files.walk(root).use { paths ->
            paths.filter { it != root }
                .iterator()
                .asSequence()
                .associate { path ->
                    val relative = root.relativize(path).toString()
                    val value = when {
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> "directory"
                        Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ->
                            MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(path))
                                .joinToString("") { "%02x".format(it) }
                        Files.isSymbolicLink(path) -> "symlink:${Files.readSymbolicLink(path)}"
                        else -> "other"
                    }
                    relative to value
                }
        }
}
