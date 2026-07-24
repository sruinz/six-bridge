package dev.sixbridge.core.conversion

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
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
    fun `public snapshot keeps archive A roots while live source changes A to B to A`() {
        val source = source("six-cross-copy-aba")
        repeat(2_500) { index ->
            source.resolve("padding-$index.txt").writeText("padding-$index")
        }
        val script = source.resolve("zzzz-content.js")
        val fixedTime = FileTime.fromMillis(1_700_000_000_000)
        script.writeText("A")
        Files.setLastModifiedTime(script, fixedTime)

        val rootsBefore = conversionRoots()
        val workspace = ConversionWorkspace()
        val workspaceRoot = waitForNewConversionRoot(rootsBefore)
        val snapshotTask = FutureTask {
            workspace.snapshot(source)
        }
        val snapshotThread = Thread(snapshotTask, "snapshot-aba-victim")
        snapshotThread.start()
        try {
            val snapshotRoot = waitForSnapshotRoot(workspaceRoot)
            waitForPath(snapshotRoot.resolve("original/manifest.json"))
            script.writeText("B")
            Files.setLastModifiedTime(script, fixedTime)

            waitForPathOrCompletion(
                snapshotRoot.resolve("working/manifest.json"),
                snapshotTask,
            )
            script.writeText("A")
            Files.setLastModifiedTime(script, fixedTime)

            val snapshot = snapshotTask.get(10, TimeUnit.SECONDS)
            assertEquals("A", snapshot.originalRoot.resolve("zzzz-content.js").readText())
            assertEquals("A", snapshot.workingRoot.resolve("zzzz-content.js").readText())
        } finally {
            script.writeText("A")
            Files.setLastModifiedTime(script, fixedTime)
            snapshotThread.join(5_000)
            runCatching { workspace.close() }
        }
    }

    @Test
    fun `snapshot ignores excluded native subtrees and extracts writable roots`() {
        val source = source("six-canonical-snapshot")
        val script = source.resolve("content.js")
        script.writeText("source")
        val excluded = Files.createDirectories(source.resolve(".git/objects"))
        val outside = Files.createTempFile("six-canonical-outside", ".bin")
        Files.createSymbolicLink(excluded.resolve("linked-object"), outside)
        Files.setPosixFilePermissions(
            script,
            setOf(PosixFilePermission.OWNER_READ),
        )

        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)

            assertFalse(Files.exists(snapshot.originalRoot.resolve(".git")))
            assertFalse(Files.exists(snapshot.workingRoot.resolve(".git")))
            snapshot.workingRoot.resolve("content.js").writeText("working")
            assertEquals("source", script.readText())
            assertEquals(
                "working",
                snapshot.workingRoot.resolve("content.js").readText(),
            )
        }
    }

    @Test
    fun `close normalizes read-only owned directories before deletion`() {
        val source = source("six-read-only-cleanup")
        Files.createDirectories(source.resolve("nested"))
            .resolve("content.js")
            .writeText("content")
        val workspace = ConversionWorkspace()
        val snapshot = workspace.snapshot(source)
        val workspaceRoot = requireNotNull(requireNotNull(snapshot.originalRoot.parent).parent)
        val readOnlyDirectories = listOf(
            snapshot.originalRoot.resolve("nested"),
            snapshot.workingRoot.resolve("nested"),
        )
        readOnlyDirectories.forEach { directory ->
            Files.setPosixFilePermissions(
                directory.resolve("content.js"),
                setOf(PosixFilePermission.OWNER_READ),
            )
            Files.setPosixFilePermissions(
                directory,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }

        try {
            workspace.close()
            assertFalse(Files.exists(workspaceRoot))
        } finally {
            readOnlyDirectories.forEach { directory ->
                runCatching {
                    Files.setPosixFilePermissions(
                        directory,
                        setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                        ),
                    )
                }
            }
            runCatching { workspace.close() }
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

    @Test
    fun `close remains closing and retries a real leftover on second close`() {
        val source = source("six-close-retry")
        repeat(2_500) { index ->
            source.resolve("padding-$index.txt").writeText("padding-$index")
        }
        source.resolve("zzzz-cleanup-sentinel.txt").writeText("sentinel")
        val workspace = ConversionWorkspace()
        val snapshot = workspace.snapshot(source)
        val snapshotRoot = requireNotNull(snapshot.originalRoot.parent)
        val workspaceRoot = requireNotNull(snapshotRoot.parent)
        val late = snapshotRoot.resolve("late-cleanup.txt")
        val attacker = FutureTask {
            val sentinel = snapshot.workingRoot
                .resolve("zzzz-cleanup-sentinel.txt")
            while (Files.exists(sentinel)) Thread.onSpinWait()
            late.writeText("retry")
            late
        }
        val attackerThread = Thread(attacker, "close-cleanup-attacker")
        attackerThread.start()
        try {
            assertFails { workspace.close() }
            assertEquals(late, attacker.get(5, TimeUnit.SECONDS))
            assertTrue(Files.exists(workspaceRoot))
            assertFailsWith<IllegalStateException> {
                workspace.snapshot(source)
            }

            workspace.close()

            assertFalse(Files.exists(workspaceRoot))
            workspace.close()
        } finally {
            attackerThread.join(5_000)
            runCatching { workspace.close() }
        }
    }

    @Test
    fun `snapshot failure preserves the original error and close retries cleanup`() {
        val source = source("six-snapshot-cleanup-retry")
        repeat(2_500) { index ->
            source.resolve("padding-$index.txt").writeText("padding-$index")
        }
        val sourceSentinel = source.resolve("zzzz-snapshot-sentinel.txt")
        sourceSentinel.writeText("stable")
        val rootsBefore = conversionRoots()
        val workspace = ConversionWorkspace()
        val workspaceRoot = waitForNewConversionRoot(rootsBefore)
        lateinit var snapshotTask: FutureTask<ExtensionSnapshot>
        snapshotTask = FutureTask {
            workspace.snapshot(source)
        }
        val snapshotThread = Thread(snapshotTask, "snapshot-cleanup-victim")
        val attacker = FutureTask<Path> {
            val snapshotRoot = waitForSnapshotRoot(workspaceRoot)
            val copiedSentinel = snapshotRoot.resolve(
                "working/zzzz-snapshot-sentinel.txt",
            )
            while (!Files.exists(copiedSentinel)) Thread.onSpinWait()
            sourceSentinel.writeText("mutated")
            while (Files.exists(copiedSentinel)) Thread.onSpinWait()
            val late = snapshotRoot.resolve("late-snapshot-cleanup.txt")
            while (!snapshotTask.isDone && Files.exists(snapshotRoot)) {
                runCatching { late.writeText("retry") }
                Thread.onSpinWait()
            }
            late
        }
        val attackerThread = Thread(attacker, "snapshot-cleanup-attacker")
        snapshotThread.start()
        attackerThread.start()
        try {
            val error = assertFailsWith<ExecutionException> {
                snapshotTask.get(10, TimeUnit.SECONDS)
            }.cause ?: error("snapshot failure cause가 없습니다.")
            val late = attacker.get(5, TimeUnit.SECONDS)

            assertTrue(error.suppressedExceptions.isNotEmpty())
            assertEquals(
                "snapshot 임시 경로를 정리하지 못했습니다.",
                error.suppressedExceptions.single().message,
            )
            assertEquals(null, error.suppressedExceptions.single().cause)
            assertTrue(Files.exists(late))

            workspace.close()

            assertFalse(Files.exists(workspaceRoot))
        } finally {
            snapshotThread.join(5_000)
            attackerThread.join(5_000)
            runCatching { workspace.close() }
        }
    }

    private fun source(prefix: String): Path =
        Files.createTempDirectory(prefix).also {
            it.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Snapshot","version":"1"}""",
            )
        }

    private fun waitForFrame(
        thread: Thread,
        className: String,
        methodName: String,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            check(thread.isAlive) {
                "snapshot thread가 공격 지점을 관찰하기 전에 종료되었습니다."
            }
            if (
                thread.stackTrace.any {
                    it.className == className && it.methodName == methodName
                }
            ) {
                return
            }
            Thread.onSpinWait()
        }
        error("$className.$methodName frame을 관찰하지 못했습니다.")
    }

    private fun conversionRoots(): Set<Path> {
        val temporary = Path.of(System.getProperty("java.io.tmpdir"))
        return Files.list(temporary).use { paths ->
            paths.filter {
                it.fileName.toString().startsWith("sixbridge-conversion-")
            }.map { it.toAbsolutePath().normalize() }.toList().toSet()
        }
    }

    private fun waitForNewConversionRoot(existing: Set<Path>): Path {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            (conversionRoots() - existing).singleOrNull()?.let { return it }
            Thread.onSpinWait()
        }
        error("새 conversion workspace root를 관찰하지 못했습니다.")
    }

    private fun waitForSnapshotRoot(workspaceRoot: Path): Path {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            Files.list(workspaceRoot).use { paths ->
                paths.filter {
                    it.fileName.toString().startsWith("snapshot-")
                }.findFirst().orElse(null)?.let { return it }
            }
            Thread.onSpinWait()
        }
        error("snapshot root를 관찰하지 못했습니다.")
    }

    private fun waitForPath(path: Path) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) return
            Thread.onSpinWait()
        }
        error("snapshot 경로를 관찰하지 못했습니다: ${path.fileName}")
    }

    private fun waitForPathOrCompletion(
        path: Path,
        task: FutureTask<*>,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (Files.exists(path) || task.isDone) return
            Thread.onSpinWait()
        }
        error("snapshot 경로 또는 작업 완료를 관찰하지 못했습니다: ${path.fileName}")
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
