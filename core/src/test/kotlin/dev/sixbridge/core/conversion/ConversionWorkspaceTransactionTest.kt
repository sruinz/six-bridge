package dev.sixbridge.core.conversion

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)

            workspace.transform(snapshot, profile) { context ->
                context.stagedRoot.resolve("one.js").writeText("changed-one")
                context.stagedRoot.resolve("two.js").writeText("changed-two")
            }

            assertEquals("changed-one", snapshot.workingRoot.resolve("one.js").readText())
            assertEquals("changed-two", snapshot.workingRoot.resolve("two.js").readText())
            assertEquals("one", snapshot.originalRoot.resolve("one.js").readText())
            ConversionWorkspaceOwnership.requireUsable(snapshot.workingRoot)
        }
    }

    @Test
    fun `transform failure leaves the published tree byte unchanged`() {
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val working = workspace.snapshot(source)

            assertFailsWith<ConversionExecutionException> {
                workspace.transform(working, profile) { context ->
                    context.stagedRoot.resolve("one.js").writeText("partial")
                    throw IllegalStateException("transform failed")
                }
            }

            assertEquals("one", working.workingRoot.resolve("one.js").readText())
            ConversionWorkspaceOwnership.requireUsable(working.workingRoot)
        }
    }

    @Test
    fun `no-op transform preserves the working root identity`() {
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            val before = fileKey(snapshot.workingRoot)

            workspace.transform(snapshot, profile) { "no-op" }

            assertEquals(before, fileKey(snapshot.workingRoot))
        }
    }

    @Test
    fun `concurrent transforms for one root are serialized`() {
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondAttempting = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<Unit> {
                    workspace.transform(snapshot, profile) {
                        firstEntered.countDown()
                        releaseFirst.await()
                    }
                }
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
                val second = pool.submit<Unit> {
                    secondAttempting.countDown()
                    workspace.transform(snapshot, profile) {
                        secondEntered.countDown()
                    }
                }
                assertTrue(secondAttempting.await(5, TimeUnit.SECONDS))
                assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS))

                releaseFirst.countDown()
                first.get(5, TimeUnit.SECONDS)
                second.get(5, TimeUnit.SECONDS)
                assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
            } finally {
                releaseFirst.countDown()
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun `root mutation before publish prevents staged output publication`() {
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)

            assertFailsWith<ConversionExecutionException> {
                workspace.transformForTesting(
                    snapshot = snapshot,
                    profile = profile,
                    beforePublish = {
                        snapshot.workingRoot.resolve("one.js").writeText("raced")
                    },
                ) { context ->
                    context.stagedRoot.resolve("one.js").writeText("staged")
                }
            }

            assertEquals("raced", snapshot.workingRoot.resolve("one.js").readText())
        }
    }

    @Test
    fun `root mutation in an extension zip excluded directory is still detected`() {
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)

            assertFailsWith<ConversionExecutionException> {
                workspace.transformForTesting(
                    snapshot = snapshot,
                    profile = profile,
                    beforePublish = {
                        val raced = snapshot.workingRoot.resolve("node_modules")
                        Files.createDirectory(raced)
                        raced.resolve("race.js").writeText("external")
                    },
                ) { context ->
                    context.stagedRoot.resolve("one.js").writeText("staged")
                }
            }

            assertEquals(
                "external",
                snapshot.workingRoot.resolve("node_modules/race.js").readText(),
            )
            assertEquals("one", snapshot.workingRoot.resolve("one.js").readText())
        }
    }

    @Test
    fun `failed publish and failed restore persist poison state`() {
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            var moves = 0

            assertFailsWith<ConversionExecutionException> {
                workspace.transformForTesting(
                    snapshot = snapshot,
                    profile = profile,
                    moveDirectory = { from, to ->
                        moves++
                        when (moves) {
                            1 -> Files.move(from, to, ATOMIC_MOVE)
                            else -> throw IllegalStateException("injected move failure")
                        }
                    },
                ) { context ->
                    context.stagedRoot.resolve("one.js").writeText("complete-stage")
                }
            }

            assertFalse(Files.exists(snapshot.workingRoot))
            assertTrue(moves >= 3)
            assertEquals(
                "POISONED",
                ConversionWorkspaceOwnership.markerStateForTesting(
                    snapshot.workingRoot,
                ),
            )
            assertFailsWith<IllegalStateException> {
                ConversionWorkspaceOwnership.requireUsable(snapshot.workingRoot)
            }
        }
    }

    @Test
    fun `execution context is active only inside its workspace transaction`() {
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            lateinit var captured: AdapterExecutionContext

            workspace.transform(snapshot, profile) { context ->
                captured = context
                context.requireActive()
            }

            assertFailsWith<IllegalStateException> {
                captured.requireActive()
            }
        }
    }

    @Test
    fun `public conversion exception does not expose staged path or source text`() {
        val source = extension()
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)
            var stagedPath = ""
            val secretSource = "chrome.storage.sync.get('secret-source')"

            val error = assertFailsWith<ConversionExecutionException> {
                workspace.transform(snapshot, profile) { context ->
                    stagedPath = context.stagedRoot.toString()
                    throw IllegalStateException("$stagedPath $secretSource")
                }
            }

            assertTrue(stagedPath.isNotBlank())
            assertNotEquals(stagedPath, error.message)
            assertFalse(requireNotNull(error.message).contains(stagedPath))
            assertFalse(requireNotNull(error.message).contains(secretSource))
            assertEquals(null, error.cause)
        }
    }

    private fun extension() =
        Files.createTempDirectory("sixbridge-transaction-source-").also { root ->
            root.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Transaction","version":"1"}""",
            )
            root.resolve("one.js").writeText("one")
            root.resolve("two.js").writeText("two")
        }

    private fun fileKey(path: java.nio.file.Path): String? =
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
            supportedApis = setOf("runtime"),
            wrapperContract = WrapperContract.complete(),
            sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
            sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        )
    }
}
