package dev.sixbridge.core.conversion

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConversionWorkspaceTransactionTest {
    @Test
    fun `publishes only a fully transformed staged tree`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(storageExtension())

            val result = workspace.transform(
                snapshot,
                profile,
                plan(snapshot),
            )

            assertTrue(result.all(AdapterDecision::applied))
            assertTrue(
                "chrome.storage.local" in
                    snapshot.workingRoot.resolve("content.js").readText(),
            )
            assertTrue(
                "chrome.storage.sync" in
                    snapshot.originalRoot.resolve("content.js").readText(),
            )
        }
    }

    @Test
    fun `no-op transform preserves the working root identity`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(plainExtension())
            val before = fileKey(snapshot.workingRoot)

            workspace.transform(snapshot, profile, plan(snapshot))

            assertEquals(before, fileKey(snapshot.workingRoot))
        }
    }

    @Test
    fun `concurrent production transforms never expose two stages`() {
        val source = storageExtension(padding = 1_500)
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            val plan = plan(snapshot)
            val running = AtomicBoolean(true)
            val maximumStages = AtomicInteger()
            val pool = Executors.newFixedThreadPool(3)
            try {
                val monitor = pool.submit {
                    val parent = requireNotNull(snapshot.workingRoot.parent)
                    while (running.get()) {
                        Files.list(parent).use { paths ->
                            val count = paths.filter {
                                it.fileName.toString()
                                    .startsWith(".sixbridge-stage-")
                            }.count().toInt()
                            maximumStages.accumulateAndGet(count, ::maxOf)
                        }
                    }
                }
                val transforms = listOf(
                    pool.submit {
                        runCatching {
                            workspace.transform(snapshot, profile, plan)
                        }
                    },
                    pool.submit {
                        runCatching {
                            workspace.transform(snapshot, profile, plan)
                        }
                    },
                )
                transforms.forEach { it.get(15, TimeUnit.SECONDS) }
                running.set(false)
                monitor.get(5, TimeUnit.SECONDS)
            } finally {
                running.set(false)
                pool.shutdownNow()
            }

            assertTrue(maximumStages.get() <= 1)
        }
    }

    @Test
    fun `working mutation observed through a real stage prevents publication`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(storageExtension(padding = 1_000))
            val before = snapshot.workingRoot.resolve("content.js").readText()
            val mutation = mutateWhenSiblingAppears(
                snapshot,
                ".sixbridge-stage-",
            ) {
                snapshot.workingRoot.resolve("content.js")
                    .writeText("external-working-race")
            }

            val error = assertFailsWith<ConversionExecutionException> {
                workspace.transform(snapshot, profile, plan(snapshot))
            }
            mutation.get(5, TimeUnit.SECONDS)

            assertEquals("external-working-race", snapshot.workingRoot
                .resolve("content.js").readText())
            assertNotEquals(before, snapshot.workingRoot
                .resolve("content.js").readText())
            assertEquals(
                "변환 작업을 안전하게 완료하지 못했습니다.",
                error.message,
            )
            assertEquals(null, error.cause)
        }
    }

    @Test
    fun `backup mutation is never accepted as published output and poisons replay`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(storageExtension(padding = 1_500))
            val mutation = mutateWhenSiblingAppears(
                snapshot,
                ".sixbridge-backup-",
            ) { backup ->
                backup.resolve("content.js").writeText("raced-backup")
            }

            assertFailsWith<ConversionExecutionException> {
                workspace.transform(snapshot, profile, plan(snapshot))
            }
            mutation.get(5, TimeUnit.SECONDS)

            assertFalse(
                snapshot.workingRoot.resolve("content.js")
                    .readText()
                    .contains("storage.local"),
            )
            val marker = requireNotNull(snapshot.workingRoot.parent)
                .resolve(
                    ".${snapshot.workingRoot.fileName}" +
                        ".sixbridge-workspace-owner",
                )
            assertEquals("POISONED", Files.readAllLines(marker).first())
            assertFailsWith<ConversionExecutionException> {
                workspace.transform(snapshot, profile, plan(snapshot))
            }
        }
    }

    @Test
    fun `published mutation restores the exact previous usable tree`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(storageExtension(padding = 1_500))
            val before = WorkspaceTreeDigest.capture(snapshot.workingRoot)
            val mutation = mutatePublishedRoot(snapshot) {
                it.resolve("content.js").writeText("raced-published")
            }

            assertFailsWith<ConversionExecutionException> {
                workspace.transform(snapshot, profile, plan(snapshot))
            }
            mutation.get(5, TimeUnit.SECONDS)

            assertEquals(before, WorkspaceTreeDigest.capture(snapshot.workingRoot))
            workspace.transform(snapshot, profile, plan(snapshot))
            assertTrue(
                "chrome.storage.local" in
                    snapshot.workingRoot.resolve("content.js").readText(),
            )
        }
    }

    @Test
    fun `original mutation after stage creation blocks publication`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(storageExtension(padding = 1_000))
            val before = WorkspaceTreeDigest.capture(snapshot.workingRoot)
            val mutation = mutateWhenSiblingAppears(
                snapshot,
                ".sixbridge-stage-",
            ) {
                snapshot.originalRoot.resolve("content.js")
                    .writeText("mutated-original")
            }

            assertFailsWith<ConversionExecutionException> {
                workspace.transform(snapshot, profile, plan(snapshot))
            }
            mutation.get(5, TimeUnit.SECONDS)

            assertEquals(before, WorkspaceTreeDigest.capture(snapshot.workingRoot))
            assertEquals(
                "mutated-original",
                snapshot.originalRoot.resolve("content.js").readText(),
            )
        }
    }

    @Test
    fun `original mutation after root moves to backup restores working exactly`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(storageExtension(padding = 1_000))
            val before = WorkspaceTreeDigest.capture(snapshot.workingRoot)
            val mutation = mutateWhenSiblingAppears(
                snapshot,
                ".sixbridge-backup-",
            ) {
                snapshot.originalRoot.resolve("content.js")
                    .writeText("mutated-after-backup")
            }

            assertFailsWith<ConversionExecutionException> {
                workspace.transform(snapshot, profile, plan(snapshot))
            }
            mutation.get(5, TimeUnit.SECONDS)

            assertEquals(before, WorkspaceTreeDigest.capture(snapshot.workingRoot))
            assertEquals(
                "mutated-after-backup",
                snapshot.originalRoot.resolve("content.js").readText(),
            )
        }
    }

    @Test
    fun `successful backup cleanup absorbs one concurrent late child`() {
        val workspace = ConversionWorkspace()
        val snapshot = workspace.snapshot(storageExtension(padding = 1_500))
        val pool = Executors.newSingleThreadExecutor()
        val cleanupRace = pool.submit<Path> {
            val backup = waitForSibling(
                requireNotNull(snapshot.workingRoot.parent),
                ".sixbridge-backup-",
            )
            val sentinel = backup.resolve("padding-1499.txt")
            while (Files.exists(sentinel)) Thread.onSpinWait()
            backup.resolve("late-cleanup.txt").writeText("retry on close")
            backup
        }
        try {
            workspace.transform(snapshot, profile, plan(snapshot))
            val backup = cleanupRace.get(5, TimeUnit.SECONDS)
            assertFalse(Files.exists(backup))
            workspace.close()
        } finally {
            pool.shutdownNow()
            workspace.close()
        }
    }

    private fun mutateWhenSiblingAppears(
        snapshot: ExtensionSnapshot,
        prefix: String,
        mutation: (Path) -> Unit,
    ) = Executors.newSingleThreadExecutor().let { pool ->
        pool.submit {
            val sibling = waitForSibling(
                requireNotNull(snapshot.workingRoot.parent),
                prefix,
            )
            mutation(sibling)
            pool.shutdown()
        }
    }

    private fun mutatePublishedRoot(
        snapshot: ExtensionSnapshot,
        mutation: (Path) -> Unit,
    ) = Executors.newSingleThreadExecutor().let { pool ->
        pool.submit {
            waitForSibling(
                requireNotNull(snapshot.workingRoot.parent),
                ".sixbridge-backup-",
            )
            while (!Files.exists(snapshot.workingRoot)) Thread.onSpinWait()
            mutation(snapshot.workingRoot)
            pool.shutdown()
        }
    }

    private fun waitForSibling(parent: Path, prefix: String): Path {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (System.nanoTime() < deadline) {
            Files.list(parent).use { paths ->
                paths.filter { it.fileName.toString().startsWith(prefix) }
                    .findFirst()
                    .orElse(null)
                    ?.let { return it }
            }
            Thread.onSpinWait()
        }
        error("$prefix sibling을 관찰하지 못했습니다.")
    }

    private fun plan(snapshot: ExtensionSnapshot): ConversionPlan =
        conversionPlanFor(snapshot.originalRoot, profile)

    private fun storageExtension(padding: Int = 0) =
        Files.createTempDirectory("sixbridge-transaction-storage-").also { root ->
            root.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version":3,
                  "name":"Transaction",
                  "version":"1",
                  "permissions":["storage"],
                  "content_scripts":[{
                    "matches":["<all_urls>"],
                    "js":["content.js"]
                  }]
                }
                """.trimIndent(),
            )
            root.resolve("content.js")
                .writeText("chrome.storage.sync.get('value');")
            repeat(padding) { index ->
                root.resolve("padding-$index.txt").writeText("padding-$index")
            }
        }

    private fun plainExtension() =
        Files.createTempDirectory("sixbridge-transaction-plain-").also { root ->
            root.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Plain","version":"1"}""",
            )
        }

    private fun fileKey(path: Path): String? =
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
        ).fileKey()?.toString()

    private companion object {
        val profile = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = "30.0.2.61",
            versionCode = 1_300_261_502,
            majorVersion = 30,
            supportedApis = setOf("runtime", "storage"),
            supportedFeatures = setOf("storage.local"),
            unavailableFeatures = setOf("storage.sync"),
            wrapperContract = WrapperContract.complete(),
            sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
            sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        )
    }
}
