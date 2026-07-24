package dev.sixbridge.core.conversion

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureBuildPublisherTest {
    @Test
    fun `normalized output aliases are rejected before publication`() {
        val parent = Files.createTempDirectory("six-publish-alias")
        val first = parent.resolve("artifact.six")
        val alias = parent.resolve("nested").resolve("..").resolve("artifact.six")

        assertFails {
            SecureBuildPublisher.publish(
                linkedMapOf(
                    first to byteArrayOf(1),
                    alias to byteArrayOf(2),
                ),
            )
        }

        assertFalse(Files.exists(first))
        assertNoTransactionDebris(parent)
    }

    @Test
    fun `symlink and hard link output targets are rejected without modifying outside files`() {
        val parent = Files.createTempDirectory("six-publish-links")
        val outside = Files.createTempFile("six-publish-outside", ".bin")
        Files.write(outside, byteArrayOf(7, 8, 9))
        val symlink = parent.resolve("symlink.crx")
        Files.createSymbolicLink(symlink, outside)

        assertFails {
            SecureBuildPublisher.publish(mapOf(symlink to byteArrayOf(1)))
        }
        assertContentEquals(byteArrayOf(7, 8, 9), Files.readAllBytes(outside))

        Files.delete(symlink)
        val hardLink = parent.resolve("hardlink.crx")
        Files.createLink(hardLink, outside)
        assertFails {
            SecureBuildPublisher.publish(mapOf(hardLink to byteArrayOf(2)))
        }
        assertContentEquals(byteArrayOf(7, 8, 9), Files.readAllBytes(outside))
        assertNoTransactionDebris(parent)
    }

    @Test
    fun `target replacement race is detected before anchored move`() {
        val parent = Files.createTempDirectory("six-publish-race")
        val target = parent.resolve("wrapper.apk")
        val outside = Files.createTempFile("six-publish-race-outside", ".bin")
        Files.writeString(target, "old")
        Files.writeString(outside, "outside")

        assertFails {
            BuildPublicationFaultInjection.beforeOutputPublish(
                action = { path ->
                    if (path == target) {
                        Files.delete(path)
                        Files.createSymbolicLink(path, outside)
                    }
                },
                block = {
                    SecureBuildPublisher.publish(mapOf(target to "new".toByteArray()))
                },
            )
        }

        assertEquals("outside", Files.readString(outside))
        assertTrue(Files.isSymbolicLink(target))
        assertNoTransactionDebris(parent)
    }

    @Test
    fun `failure after local source publication rolls back outputs and local source`() {
        val outputParent = Files.createTempDirectory("six-publish-rollback-output")
        val output = outputParent.resolve("extension.crx")
        Files.writeString(output, "old-output")
        val localParent = Files.createTempDirectory("six-publish-rollback-local")
        val destination = localParent.resolve("extension-id")
        Files.createDirectory(destination)
        destination.resolve("manifest.json").writeText("old-source")
        val source = Files.createTempDirectory("six-publish-new-source")
        source.resolve("manifest.json").writeText("new-source")

        assertFails {
            BuildPublicationFaultInjection.failAfterLocalPublish {
                SecureBuildPublisher.publish(
                    outputs = mapOf(output to "new-output".toByteArray()),
                    localSource = LocalSourcePublication(source, destination),
                )
            }
        }

        assertEquals("old-output", Files.readString(output))
        assertEquals("old-source", destination.resolve("manifest.json").readText())
        assertNoTransactionDebris(outputParent)
        assertNoTransactionDebris(localParent)
    }

    @Test
    fun `successful publication atomically replaces all outputs and local source`() {
        val outputParent = Files.createTempDirectory("six-publish-success-output")
        val outputs = linkedMapOf<Path, ByteArray>(
            outputParent.resolve("extension.crx") to byteArrayOf(1),
            outputParent.resolve("wrapper.apk") to byteArrayOf(2),
            outputParent.resolve("bundle.six") to byteArrayOf(3),
        )
        val localParent = Files.createTempDirectory("six-publish-success-local")
        val destination = localParent.resolve("extension-id")
        val source = Files.createTempDirectory("six-publish-success-source")
        source.resolve("manifest.json").writeText("new-source")

        SecureBuildPublisher.publish(
            outputs,
            LocalSourcePublication(source, destination),
        )

        outputs.forEach { (path, bytes) ->
            assertContentEquals(bytes, Files.readAllBytes(path))
        }
        assertEquals("new-source", destination.resolve("manifest.json").readText())
        assertNoTransactionDebris(outputParent)
        assertNoTransactionDebris(localParent)
    }

    private fun assertNoTransactionDebris(parent: Path) {
        assertTrue(
            parent.listDirectoryEntries().none {
                val name = it.fileName.toString()
                name.startsWith(".sixbridge-publish-") ||
                    name.startsWith(".sixbridge-local-stage-") ||
                    name.startsWith(".sixbridge-local-backup-")
            },
        )
    }
}
