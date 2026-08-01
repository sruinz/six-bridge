package dev.sixbridge.core.conversion

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.Path
import java.nio.ByteBuffer
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.Base64
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureBuildPublisherTest {
    @Test
    fun `stream writer IO failure preserves existing output and leaves no partial final`() {
        val parent = Files.createTempDirectory("six-publish-stream-failure")
        val output = parent.resolve("draft.sixdraft")
        Files.writeString(output, "old")

        assertFails {
            SecureBuildPublisher.publishSingleStream(
                output = output,
                expectedSize = 7,
                expectedSha256 = "0".repeat(64),
            ) { channel ->
                channel.write(ByteBuffer.wrap("partial".encodeToByteArray()))
                throw java.io.IOException("실제 stream writer 실패")
            }
        }

        assertEquals("old", Files.readString(output))
        assertNoTransactionDebris(parent)
    }

    @Test
    fun `reserved internal output names are rejected before creating the parent`() {
        val root = Files.createTempDirectory("six-reserved-output-root")
        val reservedNames = listOf(
            ".SIXBRIDGE-build-publisher.lock",
            ".SixBridge-publish-journal.v1",
            ".SIXbridge-publish-journal.tmp-${java.util.UUID.randomUUID()}",
            ".sixBRIDGE-publish-${java.util.UUID.randomUUID()}",
            ".SIXBRIDGE-publish-intent-${java.util.UUID.randomUUID()}",
            ".SixBridge-local-stage-${java.util.UUID.randomUUID()}",
            ".SIXbridge-local-backup-${java.util.UUID.randomUUID()}",
            ".sixBRIDGE-stage-owner-${java.util.UUID.randomUUID()}",
            ".SIXBRIDGE-future-internal",
        )

        reservedNames.forEach { name ->
            val parent = root.resolve(name.removePrefix("."))
            assertFails {
                SecureBuildPublisher.publish(
                    mapOf(parent.resolve(name) to "reserved".toByteArray()),
                )
            }
            assertFalse(Files.exists(parent), name)
        }
    }

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
    fun `Jimfs directory fsync unsupported outcome만 publication에 허용한다`() {
        Jimfs.newFileSystem(jimfsConfiguration()).use { fileSystem ->
            val parent = fileSystem.getPath("/publisher")
            Files.createDirectory(parent)
            val output = parent.resolve("extension.crx")

            BuildPublicationFaultInjection.directoryFsyncForTesting(
                operation = { DirectoryFsyncResult.UNSUPPORTED },
                block = { SecureBuildPublisher.publish(mapOf(output to "new".toByteArray())) },
            )

            assertEquals("new", Files.readString(output))
        }
    }

    @Test
    fun `Jimfs directory fsync의 임의 FileSystemException은 재전파한다`() {
        Jimfs.newFileSystem(jimfsConfiguration()).use { fileSystem ->
            val parent = fileSystem.getPath("/publisher")
            Files.createDirectory(parent)
            val output = parent.resolve("extension.crx")

            assertFailsWith<FileSystemException> {
                BuildPublicationFaultInjection.directoryFsyncForTesting(
                    operation = { path -> throw FileSystemException(path.toString(), null, "injected failure") },
                    block = { SecureBuildPublisher.publish(mapOf(output to "new".toByteArray())) },
                )
            }

            assertFalse(Files.exists(output))
        }
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
    fun `a competitor that wins the exact intent marker name is never deleted`() {
        val parent = Files.createTempDirectory("six-intent-marker-competitor")
        val output = parent.resolve("extension.crx")
        Files.writeString(output, "old")
        var competitor: Path? = null
        var competitorIdentity: Any? = null
        var competitorBytes: ByteArray? = null

        assertFails {
            BuildPublicationFaultInjection.beforeIntentMarkerCreate(
                action = { markerParent, token ->
                    val marker = markerParent.resolve(".sixbridge-publish-intent-$token")
                    val bytes = token.toString().toByteArray()
                    Files.write(marker, bytes)
                    Files.setPosixFilePermissions(
                        marker,
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                        ),
                    )
                    competitor = marker
                    competitorBytes = bytes
                    competitorIdentity = Files.readAttributes(
                        marker,
                        java.nio.file.attribute.BasicFileAttributes::class.java,
                    ).fileKey()
                },
                block = {
                    SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
                },
            )
        }

        val marker = requireNotNull(competitor)
        assertContentEquals(requireNotNull(competitorBytes), Files.readAllBytes(marker))
        assertEquals(
            competitorIdentity,
            Files.readAttributes(
                marker,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey(),
        )
        assertTrue(Files.exists(parent.resolve(".sixbridge-publish-journal.v1")))
        assertEquals("old", Files.readString(output))

        assertFails { SecureBuildPublisher.recoverPending(listOf(output), null) }
        assertContentEquals(requireNotNull(competitorBytes), Files.readAllBytes(marker))
        assertEquals(
            competitorIdentity,
            Files.readAttributes(
                marker,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey(),
        )
        assertTrue(Files.exists(parent.resolve(".sixbridge-publish-journal.v1")))
    }

    @Test
    fun `recovery preserves intent markers with wrong token or permissions`() {
        listOf("token", "permissions").forEach { mode ->
            val parent = Files.createTempDirectory("six-intent-marker-invalid-$mode")
            val output = parent.resolve("extension.crx")
            Files.writeString(output, "old")
            assertFails {
                BuildPublicationFaultInjection.forceAfterIntentBeforeStage {
                    SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
                }
            }
            val journal = parent.resolve(".sixbridge-publish-journal.v1")
            val token = journalOutputIntentToken(journal)
            val marker = parent.resolve(".sixbridge-publish-intent-$token")
            val bytes = if (mode == "token") {
                "00000000-0000-4000-8000-000000000000".toByteArray()
            } else {
                token.toByteArray()
            }
            Files.write(marker, bytes)
            Files.setPosixFilePermissions(
                marker,
                buildSet {
                    add(PosixFilePermission.OWNER_READ)
                    add(PosixFilePermission.OWNER_WRITE)
                    if (mode == "permissions") add(PosixFilePermission.GROUP_READ)
                },
            )
            val identity = Files.readAttributes(
                marker,
                java.nio.file.attribute.BasicFileAttributes::class.java,
            ).fileKey()

            assertFails { SecureBuildPublisher.recoverPending(listOf(output), null) }
            assertContentEquals(bytes, Files.readAllBytes(marker))
            assertEquals(
                identity,
                Files.readAttributes(
                    marker,
                    java.nio.file.attribute.BasicFileAttributes::class.java,
                ).fileKey(),
            )
            assertTrue(Files.exists(journal))
            assertEquals("old", Files.readString(output))
        }
    }

    @Test
    fun `crash between marker fsync and identity journal preserves the orphan`() {
        val parent = Files.createTempDirectory("six-marker-before-identity-journal")
        val output = parent.resolve("extension.crx")
        Files.writeString(output, "old")

        assertFails {
            BuildPublicationFaultInjection.forceOutputStageCrash(
                OutputStageCrashPoint.AFTER_INTENT_MARKER_BEFORE_JOURNAL,
            ) {
                SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
            }
        }
        val journal = parent.resolve(".sixbridge-publish-journal.v1")
        val journalLines = journal.readText().lineSequence().toList()
        assertEquals("SIXBRIDGE-PUBLISH-JOURNAL-V3", journalLines[0])
        assertEquals("3", journalLines[1].split('|')[1])
        val marker = parent.resolve(
            ".sixbridge-publish-intent-${journalOutputIntentToken(journal)}",
        )
        val markerBytes = Files.readAllBytes(marker)
        val markerIdentity = fileIdentity(marker)

        assertFails { SecureBuildPublisher.recoverPending(listOf(output), null) }
        assertContentEquals(markerBytes, Files.readAllBytes(marker))
        assertEquals(markerIdentity, fileIdentity(marker))
        assertTrue(Files.exists(journal))
        assertEquals("old", Files.readString(output))
    }

    @Test
    fun `local crash between marker fsync and identity journal preserves the orphan`() {
        val outputParent = Files.createTempDirectory("six-local-marker-output")
        val output = outputParent.resolve("extension.crx")
        Files.writeString(output, "old-output")
        val localParent = Files.createTempDirectory("six-local-marker-parent")
        val destination = localParent.resolve("extension-id")
        val source = Files.createTempDirectory("six-local-marker-source")
        source.resolve("manifest.json").writeText("new-source")
        val local = LocalSourcePublication(source, destination)

        assertFails {
            BuildPublicationFaultInjection.forceLocalStageCrash(
                LocalStageCrashPoint.AFTER_INTENT_MARKER_BEFORE_JOURNAL,
            ) {
                SecureBuildPublisher.publish(
                    mapOf(output to "new-output".toByteArray()),
                    local,
                )
            }
        }
        val journal = outputParent.resolve(".sixbridge-publish-journal.v1")
        val intentLine = journal.readText().lineSequence()
            .first { it.startsWith("T|") }
            .split('|')
        val token = String(Base64.getUrlDecoder().decode(intentLine[4]))
        val marker = localParent.resolve(".sixbridge-publish-intent-$token")
        val markerBytes = Files.readAllBytes(marker)
        val markerIdentity = fileIdentity(marker)

        assertFails { SecureBuildPublisher.recoverPending(listOf(output), local) }
        assertContentEquals(markerBytes, Files.readAllBytes(marker))
        assertEquals(markerIdentity, fileIdentity(marker))
        assertTrue(Files.exists(journal))
        assertEquals("old-output", Files.readString(output))
        assertFalse(Files.exists(destination))
    }

    @Test
    fun `journaled marker identity rejects a same-token replacement inode`() {
        val parent = Files.createTempDirectory("six-marker-replaced-inode")
        val output = parent.resolve("extension.crx")
        Files.writeString(output, "old")
        assertFails {
            BuildPublicationFaultInjection.forceOutputStageCrash(
                OutputStageCrashPoint.AFTER_OWNER_MARKER,
            ) {
                SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
            }
        }
        val journal = parent.resolve(".sixbridge-publish-journal.v1")
        val token = journalOutputIntentToken(journal)
        val marker = parent.resolve(".sixbridge-publish-intent-$token")
        val originalIdentity = fileIdentity(marker)
        val replacement = parent.resolve(".replacement-${java.util.UUID.randomUUID()}")
        Files.writeString(replacement, token)
        Files.setPosixFilePermissions(
            replacement,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
        )
        Files.move(
            replacement,
            marker,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
        )
        val replacementIdentity = fileIdentity(marker)
        assertTrue(replacementIdentity != originalIdentity)

        assertFails { SecureBuildPublisher.recoverPending(listOf(output), null) }
        assertEquals(replacementIdentity, fileIdentity(marker))
        assertEquals(token, Files.readString(marker))
        assertTrue(Files.exists(journal))
    }

    @Test
    fun `markerRemoved journal never touches a later same-name competitor`() {
        val parent = Files.createTempDirectory("six-marker-removed-competitor")
        val output = parent.resolve("extension.crx")
        Files.writeString(output, "old")
        assertFails {
            BuildPublicationFaultInjection.forceTermination(
                BuildCrashPoint.AFTER_OUTPUT_BACKUP,
            ) {
                SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
            }
        }
        val journal = parent.resolve(".sixbridge-publish-journal.v1")
        val token = journalOutputIntentToken(journal)
        val marker = parent.resolve(".sixbridge-publish-intent-$token")
        assertFalse(Files.exists(marker))
        Files.writeString(marker, token)
        Files.setPosixFilePermissions(
            marker,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
        )
        val competitorIdentity = fileIdentity(marker)

        SecureBuildPublisher.recoverPending(listOf(output), null)

        assertEquals(competitorIdentity, fileIdentity(marker))
        assertEquals(token, Files.readString(marker))
        assertFalse(Files.exists(journal))
        assertEquals("old", Files.readString(output))
    }

    @Test
    fun `intent marker evidence allows verified cleanup after its parent fsync fails`() {
        val parent = Files.createTempDirectory("six-intent-marker-fsync")
        val output = parent.resolve("extension.crx")
        Files.writeString(output, "old")
        var parentFsyncs = 0

        assertFails {
            BuildPublicationFaultInjection.failDirectoryFsyncWhen(
                predicate = { path ->
                    if (path == parent) parentFsyncs += 1
                    path == parent && parentFsyncs == 3
                },
                block = {
                    SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
                },
            )
        }

        assertEquals(3, parentFsyncs)
        assertEquals("old", Files.readString(output))
        assertFalse(Files.exists(parent.resolve(".sixbridge-publish-journal.v1")))
        assertTrue(
            parent.listDirectoryEntries().none {
                it.fileName.toString().startsWith(".sixbridge-publish-intent-")
            },
        )
    }

    @Test
    fun `output stage recovery requires an internal token before identity is journaled`() {
        OutputStageCrashPoint.entries
            .filterNot {
                it == OutputStageCrashPoint.AFTER_INTENT_MARKER_BEFORE_JOURNAL
            }
            .forEach { point ->
            val parent = Files.createTempDirectory("six-output-stage-owner")
            val output = parent.resolve("extension.crx")
            Files.writeString(output, "old")

            assertFails {
                BuildPublicationFaultInjection.forceOutputStageCrash(point) {
                    SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
                }
            }
            val stage = parent.listDirectoryEntries()
                .single {
                    Files.isDirectory(it) &&
                        it.fileName.toString().startsWith(".sixbridge-publish-")
                }
            if (point == OutputStageCrashPoint.AFTER_DIRECTORY_CREATE) {
                assertFails {
                    SecureBuildPublisher.recoverPending(listOf(output), null)
                }
                assertTrue(Files.isDirectory(stage))
                assertTrue(Files.exists(parent.resolve(".sixbridge-publish-journal.v1")))
            } else {
                SecureBuildPublisher.recoverPending(listOf(output), null)
                assertEquals("old", Files.readString(output))
                assertNoTransactionDebris(parent)
            }
        }
    }

    @Test
    fun `local stage creation crashes recover only proven owned directories`() {
        LocalStageCrashPoint.entries
            .filterNot {
                it == LocalStageCrashPoint.AFTER_INTENT_MARKER_BEFORE_JOURNAL
            }
            .forEach { point ->
            val outputParent = Files.createTempDirectory("six-local-stage-output")
            val output = outputParent.resolve("extension.crx")
            Files.writeString(output, "old-output")
            val localParent = Files.createTempDirectory("six-local-stage-parent")
            val destination = localParent.resolve("extension-id")
            Files.createDirectory(destination)
            destination.resolve("manifest.json").writeText("old-source")
            val source = Files.createTempDirectory("six-local-stage-source")
            source.resolve("manifest.json").writeText("new-source")
            val local = LocalSourcePublication(source, destination)

            assertFails {
                BuildPublicationFaultInjection.forceLocalStageCrash(point) {
                    SecureBuildPublisher.publish(
                        mapOf(output to "new-output".toByteArray()),
                        local,
                    )
                }
            }
            val stage = localParent.listDirectoryEntries()
                .single { it.fileName.toString().startsWith(".sixbridge-local-stage-") }
            if (point == LocalStageCrashPoint.AFTER_DIRECTORY_CREATE) {
                assertFails {
                    SecureBuildPublisher.recoverPending(listOf(output), local)
                }
                assertTrue(Files.isDirectory(stage))
                assertTrue(
                    Files.exists(outputParent.resolve(".sixbridge-publish-journal.v1")),
                )
            } else {
                SecureBuildPublisher.recoverPending(listOf(output), local)
                assertEquals("old-output", Files.readString(output))
                assertEquals("old-source", destination.resolve("manifest.json").readText())
                assertNoTransactionDebris(outputParent)
                assertNoTransactionDebris(localParent)
            }
        }
    }

    @Test
    fun `wrong internal owner token and replaced recorded inode are preserved`() {
        val outputParent = Files.createTempDirectory("six-stage-wrong-token")
        val output = outputParent.resolve("extension.crx")
        Files.writeString(output, "old")
        assertFails {
            BuildPublicationFaultInjection.forceOutputStageCrash(
                OutputStageCrashPoint.AFTER_OWNER_MARKER,
            ) {
                SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
            }
        }
        val outputStage = outputParent.listDirectoryEntries()
            .single {
                Files.isDirectory(it) &&
                    it.fileName.toString().startsWith(".sixbridge-publish-")
            }
        val ownerMarker = outputStage.listDirectoryEntries()
            .single { it.fileName.toString().startsWith(".sixbridge-stage-owner-") }
        ownerMarker.writeText("00000000-0000-4000-8000-000000000000")
        assertFails { SecureBuildPublisher.recoverPending(listOf(output), null) }
        assertTrue(Files.exists(outputStage))

        val secondOutputParent = Files.createTempDirectory("six-stage-replaced-inode")
        val secondOutput = secondOutputParent.resolve("extension.crx")
        Files.writeString(secondOutput, "old-output")
        val localParent = Files.createTempDirectory("six-stage-replaced-local")
        val destination = localParent.resolve("extension-id")
        val source = Files.createTempDirectory("six-stage-replaced-source")
        source.resolve("manifest.json").writeText("new-source")
        val local = LocalSourcePublication(source, destination)
        assertFails {
            BuildPublicationFaultInjection.forceLocalStageCrash(
                LocalStageCrashPoint.AFTER_CREATED_JOURNAL,
            ) {
                SecureBuildPublisher.publish(
                    mapOf(secondOutput to "new-output".toByteArray()),
                    local,
                )
            }
        }
        val localStage = localParent.listDirectoryEntries()
            .single { it.fileName.toString().startsWith(".sixbridge-local-stage-") }
        deleteTestTree(localStage)
        Files.createDirectory(localStage)
        localStage.resolve("keep.txt").writeText("competitor")

        assertFails {
            SecureBuildPublisher.recoverPending(listOf(secondOutput), local)
        }
        assertEquals("competitor", localStage.resolve("keep.txt").readText())
        assertTrue(
            Files.exists(secondOutputParent.resolve(".sixbridge-publish-journal.v1")),
        )
    }

    @Test
    fun `journal structural corruption fails before recovery mutations`() {
        val mutations = listOf<(MutableList<String>) -> Unit>(
            { lines ->
                val output = lines.first { it.startsWith("O|") }
                lines.add(lines.indexOfLast { it.startsWith("O|") } + 1, output)
                val metadata = lines[1].split('|').toMutableList()
                metadata[2] = (metadata[2].toInt() + 1).toString()
                lines[1] = metadata.joinToString("|")
            },
            { lines ->
                val header = lines[2].split('|').toMutableList()
                header[3] = "%"
                lines[2] = header.joinToString("|")
            },
            { lines ->
                val header = lines[2].split('|').toMutableList()
                header[2] = "1"
                lines[2] = header.joinToString("|")
            },
            { lines ->
                lines.removeAt(lines.indexOfFirst { it.startsWith("L|") })
                val metadata = lines[1].split('|').toMutableList()
                metadata[4] = "false"
                lines[1] = metadata.joinToString("|")
            },
            { lines ->
                val outputIndex = lines.indexOfFirst { it.startsWith("O|") }
                val fields = lines[outputIndex].split('|').toMutableList()
                fields[4] = "z".repeat(64)
                lines[outputIndex] = fields.joinToString("|")
            },
            { lines ->
                lines.removeAt(lines.indexOfFirst { it.startsWith("L|") })
                lines.removeAt(lines.indexOfFirst { it.startsWith("T|") })
                val metadata = lines[1].split('|').toMutableList()
                metadata[3] = "false"
                metadata[4] = "false"
                lines[1] = metadata.joinToString("|")
                val header = lines[2].split('|').toMutableList()
                header[1] = "LOCAL_INTENT_READY"
                lines[2] = header.joinToString("|")
            },
        )
        mutations.forEachIndexed { index, mutation ->
            val outputParent = Files.createTempDirectory("six-journal-invalid-$index")
            val output = outputParent.resolve("extension.crx")
            Files.writeString(output, "old-output")
            val localParent = Files.createTempDirectory("six-journal-invalid-local-$index")
            val destination = localParent.resolve("extension-id")
            val source = Files.createTempDirectory("six-journal-invalid-source-$index")
            source.resolve("manifest.json").writeText("new-source")
            val local = LocalSourcePublication(source, destination)
            assertFails {
                BuildPublicationFaultInjection.forceLocalStageCrash(
                    LocalStageCrashPoint.AFTER_CREATED_JOURNAL,
                ) {
                    SecureBuildPublisher.publish(
                        mapOf(output to "new-output".toByteArray()),
                        local,
                    )
                }
            }
            val journal = outputParent.resolve(".sixbridge-publish-journal.v1")
            rewriteJournal(journal, mutation)
            val journalBytes = Files.readAllBytes(journal)
            val outputStage = outputParent.listDirectoryEntries()
                .single {
                    Files.isDirectory(it) &&
                        it.fileName.toString().startsWith(".sixbridge-publish-")
                }
            val localStage = localParent.listDirectoryEntries()
                .single { it.fileName.toString().startsWith(".sixbridge-local-stage-") }

            assertFails {
                SecureBuildPublisher.recoverPending(listOf(output), local)
            }
            assertContentEquals(journalBytes, Files.readAllBytes(journal))
            assertTrue(Files.isDirectory(outputStage))
            assertTrue(Files.isDirectory(localStage))
            assertEquals("old-output", Files.readString(output))
        }
    }

    @Test
    fun `journal checksum mismatch and truncated lines preserve all recovery evidence`() {
        listOf("checksum", "truncated").forEach { mode ->
            val parent = Files.createTempDirectory("six-journal-integrity-$mode")
            val output = parent.resolve("extension.crx")
            Files.writeString(output, "old")
            assertFails {
                BuildPublicationFaultInjection.forceOutputStageCrash(
                    OutputStageCrashPoint.AFTER_OWNER_MARKER,
                ) {
                    SecureBuildPublisher.publish(mapOf(output to "new".toByteArray()))
                }
            }
            val journal = parent.resolve(".sixbridge-publish-journal.v1")
            val lines = journal.readText().split('\n').toMutableList()
            if (mode == "checksum") {
                val metadata = lines[1].split('|').toMutableList()
                metadata[5] = "0".repeat(64)
                lines[1] = metadata.joinToString("|")
            } else {
                lines.removeAt(lines.indexOfFirst { it.startsWith("O|") })
            }
            journal.writeText(lines.joinToString("\n"))
            val corrupted = Files.readAllBytes(journal)
            val stage = parent.listDirectoryEntries().single {
                Files.isDirectory(it) &&
                    it.fileName.toString().startsWith(".sixbridge-publish-")
            }

            assertFails { SecureBuildPublisher.recoverPending(listOf(output), null) }
            assertContentEquals(corrupted, Files.readAllBytes(journal))
            assertTrue(Files.isDirectory(stage))
            assertEquals("old", Files.readString(output))
        }
    }

    @Test
    fun `local tree durability flushes files then nested directories bottom up`() {
        val outputParent = Files.createTempDirectory("six-tree-order-output")
        val output = outputParent.resolve("extension.crx")
        val localParent = Files.createTempDirectory("six-tree-order-local")
        val destination = localParent.resolve("extension-id")
        val source = Files.createTempDirectory("six-tree-order-source")
        Files.createDirectories(source.resolve("a/b"))
            .resolve("data.txt").writeText("data")
        val files = mutableListOf<Path>()
        val directories = mutableListOf<Path>()

        BuildPublicationFaultInjection.observeLocalFileFsync(files::add) {
            BuildPublicationFaultInjection.observeDirectoryFsync(directories::add) {
                SecureBuildPublisher.publish(
                    mapOf(output to "new".toByteArray()),
                    LocalSourcePublication(source, destination),
                )
            }
        }

        assertTrue(files.any { it.fileName.toString() == "data.txt" })
        val localStageDirectories = directories.filter {
            it.startsWith(localParent) &&
                it.toString().contains(".sixbridge-local-stage-")
        }
        val bIndex = localStageDirectories.indexOfLast { it.fileName.toString() == "b" }
        val aIndex = localStageDirectories.indexOfLast { it.fileName.toString() == "a" }
        val rootIndex = localStageDirectories.indexOfLast {
            it.fileName.toString().startsWith(".sixbridge-local-stage-")
        }
        assertTrue(bIndex >= 0 && aIndex > bIndex && rootIndex > aIndex)
    }

    @Test
    fun `local tree fsync covers nested files and directories and failures stay recoverable`() {
        listOf("file", "directory").forEach { failureKind ->
            val outputParent = Files.createTempDirectory("six-tree-fsync-output")
            val output = outputParent.resolve("extension.crx")
            Files.writeString(output, "old-output")
            val localParent = Files.createTempDirectory("six-tree-fsync-local")
            val destination = localParent.resolve("extension-id")
            Files.createDirectory(destination)
            destination.resolve("manifest.json").writeText("old-source")
            val source = Files.createTempDirectory("six-tree-fsync-source")
            val nested = Files.createDirectories(source.resolve("a/b"))
            nested.resolve("data.txt").writeText("new-source")
            val local = LocalSourcePublication(source, destination)
            val operation = {
                SecureBuildPublisher.publish(
                    mapOf(output to "new-output".toByteArray()),
                    local,
                )
            }

            assertFails {
                if (failureKind == "file") {
                    BuildPublicationFaultInjection.failLocalFileFsyncWhen(
                        { it.fileName.toString() == "data.txt" },
                        operation,
                    )
                } else {
                    BuildPublicationFaultInjection.failDirectoryFsyncWhen(
                        { it.fileName.toString() == "b" },
                        operation,
                    )
                }
            }
            assertTrue(Files.exists(outputParent.resolve(".sixbridge-publish-journal.v1")))
            assertEquals("old-output", Files.readString(output))
            assertEquals("old-source", destination.resolve("manifest.json").readText())

            SecureBuildPublisher.recoverPending(listOf(output), local)
            assertNoTransactionDebris(outputParent)
            assertNoTransactionDebris(localParent)
        }
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

    private fun rewriteJournal(
        journal: Path,
        mutation: (MutableList<String>) -> Unit,
    ) {
        val lines = journal.readText().trimEnd('\n').split('\n').toMutableList()
        mutation(lines)
        val metadata = lines[1].split('|').toMutableList()
        val checksumInput = (
            listOf(lines.first(), metadata.take(5).joinToString("|")) +
                lines.drop(2)
            ).joinToString("\n", postfix = "\n")
        metadata[5] = MessageDigest.getInstance("SHA-256")
            .digest(checksumInput.toByteArray())
            .joinToString("") { "%02x".format(it) }
        lines[1] = metadata.joinToString("|")
        journal.writeText(lines.joinToString("\n", postfix = "\n"))
    }

    private fun deleteTestTree(root: Path) {
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private fun journalOutputIntentToken(journal: Path): String {
        val header = journal.readText().lineSequence().drop(2).first().split('|')
        return String(Base64.getUrlDecoder().decode(header.last()))
    }

    private fun fileIdentity(path: Path): Pair<Long, Long> {
        val attributes = Files.readAttributes(path, "unix:dev,ino")
        return (attributes.getValue("dev") as Number).toLong() to
            (attributes.getValue("ino") as Number).toLong()
    }

    private fun jimfsConfiguration() = Configuration.unix().toBuilder()
        .setAttributeViews("basic", "owner", "posix", "unix")
        .build()
}
