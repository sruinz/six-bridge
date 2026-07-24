package dev.sixbridge.core.compat

import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StableCleanupRetryTest {
    private val profile = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = setOf("storage"),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    )

    @Test
    fun `stable close abandons a replaced root without deleting the replacement`() {
        val ownersBefore = activeOwnerCount()
        val source = Files.createTempDirectory("six-stable-cleanup-source")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Cleanup","version":"1"}""",
        )
        source.resolve("0000-swap-trigger.txt").writeText("trigger")
        repeat(2_500) { index ->
            source.resolve("padding-${index.toString().padStart(4, '0')}.txt")
                .writeText("padding-$index")
        }
        val rootsBefore = analysisRoots()
        val analysisTask = FutureTask {
            CompatibilityAnalyzer().analyze(source, profile)
        }
        val analysisThread = Thread(analysisTask, "stable-cleanup-victim")
        analysisThread.start()
        val abandonedRoot = waitForNewAnalysisRoot(rootsBefore)
        val movedRoot = abandonedRoot.resolveSibling(
            "${abandonedRoot.fileName}-moved",
        )
        val outside = Files.createTempDirectory("six-stable-cleanup-outside")
        val outsideProof = outside.resolve("outside-proof.txt")
        outsideProof.writeText("outside")
        val attacker = FutureTask {
            val trigger = abandonedRoot.resolve(
                "canonical/0000-swap-trigger.txt",
            )
            while (!Files.exists(trigger)) {
                check(!analysisTask.isDone) {
                    "stable cleanup trigger를 관찰하기 전에 분석이 종료되었습니다."
                }
                Thread.onSpinWait()
            }
            while (Files.exists(trigger)) Thread.onSpinWait()
            Files.move(
                abandonedRoot,
                movedRoot,
                StandardCopyOption.ATOMIC_MOVE,
            )
            Files.createSymbolicLink(abandonedRoot, outside)
        }
        val attackerThread = Thread(attacker, "stable-root-swap-attacker")
        attackerThread.start()

        try {
            val error = assertFailsWith<ExecutionException> {
                analysisTask.get(15, TimeUnit.SECONDS)
            }.cause ?: error("stable cleanup 실패 원인이 없습니다.")
            attacker.get(5, TimeUnit.SECONDS)
            attackerThread.join(5_000)

            assertEquals(
                "소유한 임시 경로를 정리하지 못했습니다.",
                error.message,
            )
            assertEquals(null, error.cause)
            assertTrue(Files.exists(abandonedRoot, NOFOLLOW_LINKS))
            assertEquals("outside", Files.readString(outsideProof))
            assertEquals(ownersBefore, activeOwnerCount())

            val retrySource = Files.createTempDirectory("six-stable-cleanup-retry")
            retrySource.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Retry","version":"1"}""",
            )
            CompatibilityAnalyzer().analyze(retrySource, profile)

            assertTrue(Files.isSymbolicLink(abandonedRoot))
            assertTrue(Files.exists(movedRoot, NOFOLLOW_LINKS))
            assertEquals("outside", Files.readString(outsideProof))
        } finally {
            analysisThread.join(5_000)
            attackerThread.join(5_000)
            runCatching { Files.deleteIfExists(abandonedRoot) }
            deleteTestTree(movedRoot)
            deleteTestTree(abandonedRoot)
        }
    }

    @Test
    fun `stable close abandons a missing root without deleting the moved inode`() {
        val ownersBefore = activeOwnerCount()
        val source = Files.createTempDirectory("six-stable-missing-source")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Missing","version":"1"}""",
        )
        source.resolve("0000-missing-trigger.txt").writeText("trigger")
        repeat(2_500) { index ->
            source.resolve("padding-${index.toString().padStart(4, '0')}.txt")
                .writeText("padding-$index")
        }
        val rootsBefore = analysisRoots()
        val analysisTask = FutureTask {
            CompatibilityAnalyzer().analyze(source, profile)
        }
        val analysisThread = Thread(analysisTask, "stable-missing-victim")
        analysisThread.start()
        val missingRoot = waitForNewAnalysisRoot(rootsBefore)
        val movedRoot = missingRoot.resolveSibling("${missingRoot.fileName}-missing")
        val attacker = FutureTask {
            val trigger = missingRoot.resolve(
                "canonical/0000-missing-trigger.txt",
            )
            while (!Files.exists(trigger)) {
                check(!analysisTask.isDone) {
                    "stable missing trigger를 관찰하기 전에 분석이 종료되었습니다."
                }
                Thread.onSpinWait()
            }
            while (Files.exists(trigger)) Thread.onSpinWait()
            Files.move(missingRoot, movedRoot, StandardCopyOption.ATOMIC_MOVE)
        }
        val attackerThread = Thread(attacker, "stable-missing-attacker")
        attackerThread.start()

        try {
            val error = assertFailsWith<ExecutionException> {
                analysisTask.get(15, TimeUnit.SECONDS)
            }.cause ?: error("stable missing cleanup 실패 원인이 없습니다.")
            attacker.get(5, TimeUnit.SECONDS)

            assertEquals(
                "소유한 임시 경로를 정리하지 못했습니다.",
                error.message,
            )
            assertFalse(Files.exists(missingRoot, NOFOLLOW_LINKS))
            assertTrue(Files.exists(movedRoot, NOFOLLOW_LINKS))
            assertFalse(
                Files.exists(
                    movedRoot.resolve("canonical"),
                    NOFOLLOW_LINKS,
                ),
            )
            assertEquals(ownersBefore, activeOwnerCount())

            val retrySource = Files.createTempDirectory("six-stable-missing-retry")
            retrySource.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Retry","version":"1"}""",
            )
            CompatibilityAnalyzer().analyze(retrySource, profile)

            assertFalse(Files.exists(missingRoot, NOFOLLOW_LINKS))
            assertTrue(Files.exists(movedRoot, NOFOLLOW_LINKS))
        } finally {
            analysisThread.join(5_000)
            attackerThread.join(5_000)
            deleteTestTree(movedRoot)
            deleteTestTree(missingRoot)
        }
    }

    @Test
    fun `sixty four stable cleanup failures release owner and fd quota`() {
        val source = Files.createTempDirectory("six-stable-quota-source")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Quota","version":"1"}""",
        )
        val archive = ExtensionZip.createBytes(source)
        val ownersBefore = activeOwnerCount()
        val descriptorsBefore = openFileDescriptorCount()

        repeat(64) { index ->
            val stable = StableExtensionAnalysis.fromArchive(archive)
            val root = stable.root.parent
            val moved = root.resolveSibling("${root.fileName}-quota-$index")
            Files.move(root, moved, StandardCopyOption.ATOMIC_MOVE)
            try {
                val error = assertFailsWith<IllegalStateException> {
                    stable.close()
                }
                assertEquals(
                    "소유한 임시 경로를 정리하지 못했습니다.",
                    error.message,
                )
                assertEquals(null, error.cause)
                assertEquals(ownersBefore, activeOwnerCount())
                assertFalse(Files.exists(root, NOFOLLOW_LINKS))
                assertTrue(Files.exists(moved, NOFOLLOW_LINKS))
            } finally {
                deleteTestTree(moved)
            }
        }

        StableExtensionAnalysis.fromArchive(archive).use { stable ->
            assertTrue(Files.isRegularFile(stable.root.resolve("manifest.json")))
        }

        assertEquals(ownersBefore, activeOwnerCount())
        descriptorsBefore?.let { before ->
            val after = requireNotNull(openFileDescriptorCount())
            assertTrue(
                after <= before + 2,
                "stable cleanup이 file descriptor를 누수했습니다: $before -> $after",
            )
        }
    }

    private fun analysisRoots(): Set<Path> {
        val temporary = Path.of(System.getProperty("java.io.tmpdir"))
        return Files.list(temporary).use { paths ->
            paths.filter {
                it.fileName.toString().startsWith("sixbridge-analysis-")
            }.map { it.toAbsolutePath().normalize() }.toList().toSet()
        }
    }

    private fun waitForNewAnalysisRoot(existing: Set<Path>): Path {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            (analysisRoots() - existing).singleOrNull()?.let { return it }
            Thread.onSpinWait()
        }
        error("새 stable analysis root를 관찰하지 못했습니다.")
    }

    private fun deleteTestTree(path: Path) {
        if (!Files.exists(path, NOFOLLOW_LINKS)) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
    }

    private fun activeOwnerCount(): Int {
        val field = Class.forName(
            "dev.sixbridge.core.conversion.PendingTempCleanup",
        ).getDeclaredField("ownersByToken")
        field.isAccessible = true
        return (field.get(null) as Map<*, *>).size
    }

    private fun openFileDescriptorCount(): Long? =
        (
            ManagementFactory.getOperatingSystemMXBean() as?
                com.sun.management.UnixOperatingSystemMXBean
        )?.openFileDescriptorCount
}
