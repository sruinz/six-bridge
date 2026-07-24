package dev.sixbridge.core.conversion

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
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
    fun `same output parent publications are serialized for the whole transaction`() {
        val parent = Files.createTempDirectory("six-publish-concurrent")
        val output = parent.resolve("extension.crx")
        Files.writeString(output, "old")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        val first = thread {
            runCatching {
                BuildPublicationFaultInjection.pauseAfterStagedJournal({
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }) {
                    SecureBuildPublisher.publish(mapOf(output to "first".toByteArray()))
                }
            }.exceptionOrNull()?.let(failures::add)
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        val second = thread {
            runCatching {
                SecureBuildPublisher.publish(mapOf(output to "second".toByteArray()))
            }.exceptionOrNull()?.let(failures::add)
        }
        Thread.sleep(100)
        release.countDown()
        first.join(10_000)
        second.join(10_000)

        assertTrue(failures.isEmpty(), failures.joinToString())
        assertEquals("second", Files.readString(output))
    }

    @Test
    fun `publisher waits for an operating system lock held by another process`() {
        val parent = Files.createTempDirectory("six-publish-process-lock")
        val output = parent.resolve("extension.crx")
        SecureBuildPublisher.recoverPending(listOf(output), null)
        val probeRoot = Files.createTempDirectory("six-publish-lock-probe")
        val source = probeRoot.resolve("LockProbe.java")
        source.writeText(
            """
            import java.nio.channels.FileChannel;
            import java.nio.file.*;
            import static java.nio.file.StandardOpenOption.WRITE;
            public class LockProbe {
              public static void main(String[] args) throws Exception {
                Path lock = Path.of(args[0]);
                Path ready = Path.of(args[1]);
                Path release = Path.of(args[2]);
                try (FileChannel channel = FileChannel.open(lock, WRITE);
                     var ignored = channel.lock()) {
                  Files.writeString(ready, "ready");
                  while (!Files.exists(release)) Thread.sleep(10);
                }
              }
            }
            """.trimIndent(),
        )
        val javaHome = Path.of(System.getProperty("java.home"))
        assertEquals(
            0,
            ProcessBuilder(javaHome.resolve("bin/javac").toString(), source.toString())
                .inheritIO()
                .start()
                .waitFor(),
        )
        val ready = probeRoot.resolve("ready")
        val release = probeRoot.resolve("release")
        val process = ProcessBuilder(
            javaHome.resolve("bin/java").toString(),
            "-cp",
            probeRoot.toString(),
            "LockProbe",
            parent.resolve(".sixbridge-build-publisher.lock").toString(),
            ready.toString(),
            release.toString(),
        ).inheritIO().start()
        repeat(200) {
            if (Files.exists(ready)) return@repeat
            Thread.sleep(10)
        }
        assertTrue(Files.exists(ready))
        val publisher = thread {
            SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
        }
        Thread.sleep(100)
        assertTrue(publisher.isAlive)
        Files.writeString(release, "release")
        assertEquals(0, process.waitFor())
        publisher.join(10_000)
        assertEquals("new", Files.readString(output))
    }

    @Test
    fun `publisher lock permissions and hard links fail closed`() {
        listOf("permissions", "hardlink").forEach { mode ->
            val parent = Files.createTempDirectory("six-publish-lock-$mode")
            val lock = parent.resolve(".sixbridge-build-publisher.lock")
            Files.writeString(lock, "lock")
            Files.setPosixFilePermissions(
                lock,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
            if (mode == "permissions") {
                Files.setPosixFilePermissions(
                    lock,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ,
                    ),
                )
            } else {
                Files.createLink(parent.resolve("lock-hardlink"), lock)
            }
            val output = parent.resolve("extension.crx")

            assertFails {
                SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
            }
            assertFalse(Files.exists(output))
        }
    }

    @Test
    fun `publication lock rejects output parent replacement`() {
        val parent = Files.createTempDirectory("six-publish-lock-root")
        val moved = parent.resolveSibling("${parent.fileName}-moved")
        val output = parent.resolve("extension.crx")

        assertFails {
            BuildPublicationFaultInjection.replaceParentAfterLock({
                Files.move(parent, moved)
                Files.createDirectory(parent)
            }) {
                SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
            }
        }

        assertTrue(parent.listDirectoryEntries().isEmpty())
        assertTrue(moved.listDirectoryEntries().all {
            it.fileName.toString() == ".sixbridge-build-publisher.lock"
        })
    }

    @Test
    fun `rollback failure preserves journal and backup for next old-all recovery`() {
        val outputParent = Files.createTempDirectory("six-publish-rollback-preserve")
        val output = outputParent.resolve("extension.crx")
        Files.writeString(output, "old-output")
        val localParent = Files.createTempDirectory("six-publish-rollback-preserve-local")
        val destination = localParent.resolve("extension-id")
        Files.createDirectory(destination)
        destination.resolve("manifest.json").writeText("old-source")
        val source = Files.createTempDirectory("six-publish-rollback-preserve-source")
        source.resolve("manifest.json").writeText("new-source")
        val local = LocalSourcePublication(source, destination)

        assertFails {
            BuildPublicationFaultInjection.failOutputRollback {
                BuildPublicationFaultInjection.failAfterLocalPublish {
                    SecureBuildPublisher.publish(
                        mapOf(output to "new-output".toByteArray()),
                        local,
                    )
                }
            }
        }
        assertTrue(Files.exists(outputParent.resolve(".sixbridge-publish-journal.v1")))

        SecureBuildPublisher.recoverPending(listOf(output), local)
        assertEquals("old-output", Files.readString(output))
        assertEquals("old-source", destination.resolve("manifest.json").readText())
        assertNoTransactionDebris(outputParent)
        assertNoTransactionDebris(localParent)
    }

    @Test
    fun `rename phases fsync every affected output stage and local parent`() {
        val outputParent = Files.createTempDirectory("six-publish-fsync-output")
        val output = outputParent.resolve("extension.crx")
        Files.writeString(output, "old")
        val localParent = Files.createTempDirectory("six-publish-fsync-local")
        val destination = localParent.resolve("extension-id")
        Files.createDirectory(destination)
        destination.resolve("manifest.json").writeText("old")
        val source = Files.createTempDirectory("six-publish-fsync-source")
        source.resolve("manifest.json").writeText("new")
        val observed = Collections.synchronizedList(mutableListOf<Path>())

        BuildPublicationFaultInjection.observeDirectoryFsync(observed::add) {
            SecureBuildPublisher.publish(
                mapOf(output to "new".toByteArray()),
                LocalSourcePublication(source, destination),
            )
        }

        assertTrue(outputParent in observed)
        assertTrue(localParent in observed)
        assertTrue(observed.any {
            it.parent == outputParent &&
                it.fileName.toString().startsWith(".sixbridge-publish-")
        })
    }

    @Test
    fun `directory fsync failure propagates and restores old-all`() {
        val parent = Files.createTempDirectory("six-publish-fsync-failure")
        val output = parent.resolve("extension.crx")
        Files.writeString(output, "old")

        var stageFsyncs = 0
        assertFails {
            BuildPublicationFaultInjection.failDirectoryFsyncWhen(
                predicate = { path ->
                    if (path.parent == parent &&
                        path.fileName.toString().startsWith(".sixbridge-publish-")
                    ) {
                        stageFsyncs += 1
                    }
                    stageFsyncs == 2
                },
                block = {
                    SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
                },
            )
        }

        assertEquals(2, stageFsyncs)
        assertEquals("old", Files.readString(output))
        SecureBuildPublisher.recoverPending(listOf(output), null)
        assertEquals("old", Files.readString(output))
    }

    @Test
    fun `recovery removes an owner-only orphan journal temp without a journal`() {
        val outputParent = Files.createTempDirectory("six-publish-journal-orphan")
        val orphan = outputParent.resolve(
            ".sixbridge-publish-journal.tmp-interrupted",
        )
        Files.writeString(orphan, "incomplete")
        Files.setPosixFilePermissions(
            orphan,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
        )

        SecureBuildPublisher.recoverPending(
            listOf(outputParent.resolve("extension.crx")),
            null,
        )

        assertFalse(Files.exists(orphan))
    }

    @Test
    fun `forced termination phases recover the complete old transaction on next execution`() {
        BuildCrashPoint.entries.forEach { point ->
            val outputParent = Files.createTempDirectory("six-publish-crash-output")
            val outputs = linkedMapOf<Path, ByteArray>(
                outputParent.resolve("extension.crx") to "new-crx".toByteArray(),
                outputParent.resolve("wrapper.apk") to "new-wrapper".toByteArray(),
                outputParent.resolve("bundle.six") to "new-bundle".toByteArray(),
            )
            outputs.keys.forEachIndexed { index, path ->
                Files.writeString(path, "old-$index")
            }
            val localParent = Files.createTempDirectory("six-publish-crash-local")
            val destination = localParent.resolve("extension-id")
            Files.createDirectory(destination)
            destination.resolve("manifest.json").writeText("old-source")
            val source = Files.createTempDirectory("six-publish-crash-source")
            source.resolve("manifest.json").writeText("new-source")
            val local = LocalSourcePublication(source, destination)

            assertFails {
                BuildPublicationFaultInjection.forceTermination(point) {
                    SecureBuildPublisher.publish(outputs, local)
                }
            }
            SecureBuildPublisher.recoverPending(outputs.keys, local)

            outputs.keys.forEachIndexed { index, path ->
                assertEquals("old-$index", Files.readString(path), point.name)
            }
            assertEquals(
                "old-source",
                destination.resolve("manifest.json").readText(),
                point.name,
            )
            assertNoTransactionDebris(outputParent)
            assertNoTransactionDebris(localParent)
        }
    }

    @Test
    fun `commit cleanup failure leaves recoverable journal and keeps published success`() {
        val outputParent = Files.createTempDirectory("six-publish-cleanup-output")
        val output = outputParent.resolve("extension.crx")
        Files.writeString(output, "old-output")
        val localParent = Files.createTempDirectory("six-publish-cleanup-local")
        val destination = localParent.resolve("extension-id")
        Files.createDirectory(destination)
        destination.resolve("manifest.json").writeText("old-source")
        val source = Files.createTempDirectory("six-publish-cleanup-source")
        source.resolve("manifest.json").writeText("new-source")
        val outputs = mapOf(output to "new-output".toByteArray())
        val local = LocalSourcePublication(source, destination)

        BuildPublicationFaultInjection.failCommitCleanup {
            SecureBuildPublisher.publish(outputs, local)
        }

        assertEquals("new-output", Files.readString(output))
        assertEquals("new-source", destination.resolve("manifest.json").readText())
        SecureBuildPublisher.recoverPending(outputs.keys, local)
        assertEquals("new-output", Files.readString(output))
        assertEquals("new-source", destination.resolve("manifest.json").readText())
        assertNoTransactionDebris(outputParent)
        assertNoTransactionDebris(localParent)
    }

    @Test
    fun `local destination symlink ancestor outside trusted workspace is rejected`() {
        val workspace = Files.createTempDirectory("six-publish-trusted-workspace")
        val outside = Files.createTempDirectory("six-publish-local-outside")
        Files.createSymbolicLink(workspace.resolve(".local"), outside)
        val source = Files.createTempDirectory("six-publish-local-source")
        source.resolve("manifest.json").writeText("source")
        val outputParent = Files.createTempDirectory("six-publish-local-output")
        val output = outputParent.resolve("extension.crx")

        val failure = assertFails {
            SecureBuildPublisher.publish(
                mapOf(output to byteArrayOf(1)),
                LocalSourcePublication(
                    source,
                    workspace.resolve(".local/extensions/id"),
                    workspace,
                ),
            )
        }
        assertTrue(
            failure.suppressed.isEmpty(),
            failure.suppressed.joinToString { it.stackTraceToString() },
        )

        assertTrue(outside.listDirectoryEntries().isEmpty())
        assertFalse(Files.exists(output))
        SecureBuildPublisher.recoverPending(listOf(output), null)
        assertNoTransactionDebris(outputParent)
    }

    @Test
    fun `journal recovery refuses a replaced target without following it`() {
        val outputParent = Files.createTempDirectory("six-publish-journal-attack")
        val output = outputParent.resolve("extension.crx")
        Files.writeString(output, "old")
        val outside = Files.createTempFile("six-publish-journal-outside", ".bin")
        Files.writeString(outside, "outside")
        val outputs = mapOf(output to "new".toByteArray())

        assertFails {
            BuildPublicationFaultInjection.forceTermination(
                BuildCrashPoint.AFTER_PARTIAL_OUTPUT,
            ) {
                SecureBuildPublisher.publish(outputs)
            }
        }
        Files.delete(output)
        Files.createSymbolicLink(output, outside)

        assertFails {
            SecureBuildPublisher.recoverPending(outputs.keys, null)
        }
        assertEquals("outside", Files.readString(outside))
        assertTrue(Files.isSymbolicLink(output))
    }

    @Test
    fun `journal recovery rejects a same-bytes replacement inode`() {
        val parent = Files.createTempDirectory("six-publish-journal-inode")
        val output = parent.resolve("extension.crx")
        Files.writeString(output, "old")
        val outputs = mapOf(output to "new".toByteArray())

        assertFails {
            BuildPublicationFaultInjection.forceTermination(
                BuildCrashPoint.AFTER_PARTIAL_OUTPUT,
            ) {
                SecureBuildPublisher.publish(outputs)
            }
        }
        val bytes = Files.readAllBytes(output)
        Files.delete(output)
        Files.write(output, bytes)

        assertFails {
            SecureBuildPublisher.recoverPending(outputs.keys, null)
        }
        assertEquals("new", Files.readString(output))
    }

    @Test
    fun `next build recovers a pending transaction before accepting different names`() {
        val parent = Files.createTempDirectory("six-publish-different-request")
        val oldOutput = parent.resolve("extension-a.crx")
        Files.writeString(oldOutput, "old-a")

        assertFails {
            BuildPublicationFaultInjection.forceTermination(
                BuildCrashPoint.AFTER_PARTIAL_OUTPUT,
            ) {
                SecureBuildPublisher.publish(mapOf(oldOutput to "new-a".toByteArray()))
            }
        }
        val nextOutput = parent.resolve("extension-b.crx")
        SecureBuildPublisher.publish(mapOf(nextOutput to "new-b".toByteArray()))

        assertEquals("old-a", Files.readString(oldOutput))
        assertEquals("new-b", Files.readString(nextOutput))
        assertNoTransactionDebris(parent)
    }

    @Test
    fun `intent journal recovers a crash after stage creation before staged journal`() {
        val parent = Files.createTempDirectory("six-publish-intent-stage")
        val output = parent.resolve("extension.crx")
        Files.writeString(output, "old")

        assertFails {
            BuildPublicationFaultInjection.forceAfterStageBeforeStagedJournal {
                SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
            }
        }
        assertTrue(Files.exists(parent.resolve(".sixbridge-publish-journal.v1")))

        SecureBuildPublisher.recoverPending(listOf(output), null)
        assertEquals("old", Files.readString(output))
        assertNoTransactionDebris(parent)
    }

    @Test
    fun `intent journal recovers a crash before any stage is created`() {
        val parent = Files.createTempDirectory("six-publish-intent-only")
        val output = parent.resolve("extension.crx")
        Files.writeString(output, "old")

        assertFails {
            BuildPublicationFaultInjection.forceAfterIntentBeforeStage {
                SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
            }
        }
        SecureBuildPublisher.recoverPending(listOf(output), null)

        assertEquals("old", Files.readString(output))
        assertNoTransactionDebris(parent)
    }

    @Test
    fun `orphan recovery never deletes an arbitrary same-prefix user directory`() {
        val parent = Files.createTempDirectory("six-publish-user-prefix")
        val userDirectory = parent.resolve(".sixbridge-publish-user-owned")
        Files.createDirectory(userDirectory)
        userDirectory.resolve("keep.txt").writeText("keep")

        SecureBuildPublisher.publish(
            mapOf(parent.resolve("extension.crx") to "new".toByteArray()),
        )

        assertEquals("keep", userDirectory.resolve("keep.txt").readText())
    }

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
    fun `successful publication completes one recoverable outputs and local source transaction`() {
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
        val entries = parent.listDirectoryEntries()
        assertTrue(
            entries.none {
                val name = it.fileName.toString()
                name.startsWith(".sixbridge-publish-") ||
                name.startsWith(".sixbridge-local-stage-") ||
                    name.startsWith(".sixbridge-local-backup-") ||
                    name.startsWith(".sixbridge-publish-journal")
            },
            entries.joinToString { it.fileName.toString() },
        )
    }
}
