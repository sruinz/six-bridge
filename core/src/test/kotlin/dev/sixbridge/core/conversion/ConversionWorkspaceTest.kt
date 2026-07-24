package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.util.Comparator
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.readText
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
    fun `adapter proof rejects metadata replacement before final archive sealing`() {
        val source = Files.createTempDirectory("six-workspace-proof-source").also { root ->
            root.resolve("content.js").writeText(
                """
                document.addEventListener("mouseup", (event) => {
                  toolbar.button.disabled = false;
                  const value = getSelection()?.toString();
                  if (value) console.log(value);
                });
                """.trimIndent(),
            )
            root.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version":3,
                  "name":"Proof",
                  "version":"1",
                  "content_scripts":[{"matches":["https://example.com/*"],"js":["content.js"]}]
                }
                """.trimIndent(),
            )
        }
        val profile = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = BrowserProfile.BASELINE_VERSION,
            versionCode = 1_300_261_502,
            majorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION,
            supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
            wrapperContract = WrapperContract.complete(),
            sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
            sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        )
        val key = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source, requireOwnerOnlyMarker = false)
            val report = MobilePatternAnalyzer().analyzeArchive(
                snapshot.sourceZip,
                profile,
            )
            val plan = ConversionPlanner().plan(report, emptySet())
            val applied = workspace.transform(snapshot, profile, plan)
            val archive = ExtensionZip.createBytes(
                snapshot.workingRoot,
                includeProjectMarker = true,
            )
            val proof = workspace.verifyApplied(
                snapshot,
                profile,
                plan,
                applied,
                archive,
            )
            val manifest = snapshot.workingRoot.resolve("manifest.json")
            Files.setLastModifiedTime(
                manifest,
                FileTime.fromMillis(
                    Files.getLastModifiedTime(manifest).toMillis() + 2_000,
                ),
            )

            assertFailsWith<IllegalStateException> {
                workspace.sealArchive(
                    snapshot,
                    publicKey = key.public.encoded,
                    includeProjectMarker = false,
                    expectedArchive = ExtensionZip.createBytes(
                        snapshot.workingRoot,
                        key.public.encoded,
                    ),
                    applicationProof = proof,
                )
            }
        }
    }
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
    fun `close retains a replaced working root owner without deleting the replacement`() {
        val source = source("six-working-root-swap")
        source.resolve("0000-swap-trigger.txt").writeText("trigger")
        repeat(2_500) { index ->
            source.resolve("padding-${index.toString().padStart(4, '0')}.txt")
                .writeText("workspace-$index")
        }
        val outside = Files.createTempDirectory("six-cleanup-outside")
        val outsideProof = outside.resolve("padding-2499.txt")
        outsideProof.writeText("outside")
        val workspace = ConversionWorkspace()
        val snapshot = workspace.snapshot(source)
        val workingRoot = snapshot.workingRoot
        workingRoot.resolve("owned-proof.txt").writeText("owned")
        val movedRoot = workingRoot.resolveSibling("${workingRoot.fileName}-moved")
        val closeTask = FutureTask { workspace.close() }
        val closeThread = Thread(closeTask, "anchored-close-victim")
        val swapTask = FutureTask {
            val trigger = workingRoot.resolve("0000-swap-trigger.txt")
            while (Files.exists(trigger)) Thread.onSpinWait()
            Files.move(workingRoot, movedRoot, StandardCopyOption.ATOMIC_MOVE)
            Files.createSymbolicLink(workingRoot, outside)
        }
        val swapThread = Thread(swapTask, "working-root-swap-attacker")
        swapThread.start()
        closeThread.start()

        try {
            swapTask.get(10, TimeUnit.SECONDS)
            val error = assertFailsWith<ExecutionException> {
                closeTask.get(10, TimeUnit.SECONDS)
            }.cause ?: error("working root replacement cleanup 실패 원인이 없습니다.")

            assertEquals(
                "소유한 임시 경로를 정리하지 못했습니다.",
                error.message,
            )
            assertEquals(null, error.cause)
            assertEquals("outside", outsideProof.readText())
            assertTrue(Files.isDirectory(outside, LinkOption.NOFOLLOW_LINKS))
            assertTrue(Files.isSymbolicLink(workingRoot))
            assertTrue(Files.isDirectory(movedRoot, LinkOption.NOFOLLOW_LINKS))
            assertFalse(
                Files.exists(
                    movedRoot.resolve("owned-proof.txt"),
                    LinkOption.NOFOLLOW_LINKS,
                ),
            )

            Files.delete(workingRoot)
            Files.move(movedRoot, workingRoot, StandardCopyOption.ATOMIC_MOVE)
            workspace.close()

            assertFalse(Files.exists(workingRoot, LinkOption.NOFOLLOW_LINKS))
            assertFalse(Files.exists(movedRoot, LinkOption.NOFOLLOW_LINKS))
        } finally {
            closeThread.join(5_000)
            swapThread.join(5_000)
            runCatching { Files.deleteIfExists(workingRoot) }
            runCatching {
                if (Files.exists(movedRoot, LinkOption.NOFOLLOW_LINKS)) {
                    Files.walk(movedRoot).use { paths ->
                        paths.sorted(Comparator.reverseOrder())
                            .forEach(Files::deleteIfExists)
                    }
                }
            }
            runCatching { workspace.close() }
        }
    }

    @Test
    fun `retained child owner handle survives its parent path replacement`() {
        val source = source("six-parent-handle-retained")
        source.resolve("content.js").writeText("content")
        val outside = Files.createTempDirectory("six-parent-handle-outside")
        val outsideProof = outside.resolve("outside-proof.txt")
        outsideProof.writeText("outside")
        val workspace = ConversionWorkspace()
        val snapshot = workspace.snapshot(source)
        val snapshotRoot = requireNotNull(snapshot.workingRoot.parent)
        val movedSnapshotRoot = snapshotRoot.resolveSibling(
            "${snapshotRoot.fileName}-moved",
        )
        Files.move(
            snapshotRoot,
            movedSnapshotRoot,
            StandardCopyOption.ATOMIC_MOVE,
        )
        Files.createSymbolicLink(snapshotRoot, outside)

        try {
            val error = assertFailsWith<OwnedTempCleanupException> {
                workspace.close()
            }

            assertEquals(null, error.cause)
            assertEquals("outside", outsideProof.readText())
            assertTrue(Files.isSymbolicLink(snapshotRoot))
            assertTrue(Files.isDirectory(movedSnapshotRoot, LinkOption.NOFOLLOW_LINKS))
            assertFalse(
                Files.exists(
                    movedSnapshotRoot.resolve("working"),
                    LinkOption.NOFOLLOW_LINKS,
                ),
            )

            Files.delete(snapshotRoot)
            Files.move(
                movedSnapshotRoot,
                snapshotRoot,
                StandardCopyOption.ATOMIC_MOVE,
            )
            workspace.close()
        } finally {
            runCatching { Files.deleteIfExists(snapshotRoot) }
            runCatching {
                if (Files.exists(movedSnapshotRoot, LinkOption.NOFOLLOW_LINKS)) {
                    Files.walk(movedSnapshotRoot).use { paths ->
                        paths.sorted(Comparator.reverseOrder())
                            .forEach(Files::deleteIfExists)
                    }
                }
            }
            runCatching { workspace.close() }
        }
    }

    @Test
    fun `identityless path cannot enter process pending cleanup`() {
        val workspace = ConversionWorkspace()
        val snapshot = workspace.snapshot(source("six-identityless-cleanup"))
        val workspaceRoot = requireNotNull(requireNotNull(snapshot.originalRoot.parent).parent)
        val identityless = Files.createDirectories(
            workspaceRoot.resolve("identityless"),
        )
        identityless.resolve("proof.txt").writeText("preserve")

        try {
            assertFailsWith<OwnedTempCleanupException> {
                OwnedTempTree.delete(identityless)
            }
            ConversionWorkspace().use { retryTrigger ->
                assertTrue(Files.exists(identityless, LinkOption.NOFOLLOW_LINKS))
                retryTrigger.snapshot(source("six-identityless-trigger"))
            }
            assertTrue(Files.exists(identityless, LinkOption.NOFOLLOW_LINKS))
        } finally {
            workspace.close()
        }
    }

    @Test
    fun `second persistent close failure abandons handles without deleting replacements`() {
        val workspace = ConversionWorkspace()
        val snapshot = workspace.snapshot(source("six-explicit-abandon"))
        val working = snapshot.workingRoot
        val moved = working.resolveSibling("${working.fileName}-abandoned")
        val outside = Files.createTempDirectory("six-explicit-abandon-outside")
        outside.resolve("proof.txt").writeText("outside")
        Files.move(working, moved, StandardCopyOption.ATOMIC_MOVE)
        Files.createSymbolicLink(working, outside)
        try {
            assertFailsWith<OwnedTempCleanupException> {
                workspace.close()
            }
            val abandoned = assertFailsWith<OwnedTempAbandonedException> {
                workspace.close()
            }

            assertEquals(
                "소유 handle을 닫고 임시 경로는 삭제하지 않았습니다.",
                abandoned.message,
            )
            assertTrue(Files.isSymbolicLink(working))
            assertTrue(Files.isDirectory(moved, LinkOption.NOFOLLOW_LINKS))
            assertEquals("outside", outside.resolve("proof.txt").readText())
            workspace.close()
        } finally {
            runCatching { Files.deleteIfExists(working) }
            runCatching {
                Files.walk(moved).use { paths ->
                    paths.sorted(Comparator.reverseOrder())
                        .forEach(Files::deleteIfExists)
                }
            }
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
    fun `close reenumerates and removes one concurrent late child`() {
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
            workspace.close()
            assertEquals(late, attacker.get(5, TimeUnit.SECONDS))
            assertFalse(Files.exists(workspaceRoot))
            workspace.close()
        } finally {
            attackerThread.join(5_000)
            runCatching { workspace.close() }
        }
    }

    @Test
    fun `snapshot failure preserves the original error while cleanup absorbs late children`() {
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

            assertTrue(error.suppressedExceptions.isEmpty())
            assertFalse(Files.exists(late))

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
