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
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(extension())

            workspace.transformForTesting(
                snapshot = snapshot,
                profile = profile,
                plan = emptyPlan,
                mutateStage = { stage ->
                    stage.resolve("one.js").writeText("changed-one")
                    stage.resolve("two.js").writeText("changed-two")
                },
            )

            assertEquals("changed-one", snapshot.workingRoot.resolve("one.js").readText())
            assertEquals("changed-two", snapshot.workingRoot.resolve("two.js").readText())
            assertEquals("one", snapshot.originalRoot.resolve("one.js").readText())
            ConversionWorkspaceOwnership.requireUsable(snapshot.workingRoot)
        }
    }

    @Test
    fun `transform failure leaves the published tree unchanged`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(extension())

            assertFailsWith<ConversionExecutionException> {
                workspace.transformForTesting(
                    snapshot = snapshot,
                    profile = profile,
                    plan = emptyPlan,
                    mutateStage = { stage ->
                        stage.resolve("one.js").writeText("partial")
                        throw IllegalStateException("transform failed")
                    },
                )
            }

            assertEquals("one", snapshot.workingRoot.resolve("one.js").readText())
            ConversionWorkspaceOwnership.requireUsable(snapshot.workingRoot)
        }
    }

    @Test
    fun `no-op transform preserves the working root identity`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(extension())
            val before = fileKey(snapshot.workingRoot)

            workspace.transform(snapshot, profile, emptyPlan)

            assertEquals(before, fileKey(snapshot.workingRoot))
        }
    }

    @Test
    fun `concurrent transforms for one root are serialized`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(extension())
            val firstEntered = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val secondAttempting = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<Unit> {
                    workspace.transformForTesting(
                        snapshot,
                        profile,
                        emptyPlan,
                        beforePublish = {
                            firstEntered.countDown()
                            releaseFirst.await()
                        },
                    )
                }
                assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
                val second = pool.submit<Unit> {
                    secondAttempting.countDown()
                    workspace.transformForTesting(
                        snapshot,
                        profile,
                        emptyPlan,
                        beforePublish = { secondEntered.countDown() },
                    )
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
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(extension())

            assertFailsWith<ConversionExecutionException> {
                workspace.transformForTesting(
                    snapshot = snapshot,
                    profile = profile,
                    plan = emptyPlan,
                    mutateStage = {
                        it.resolve("one.js").writeText("staged")
                    },
                    beforePublish = {
                        snapshot.workingRoot.resolve("one.js").writeText("raced")
                    },
                )
            }

            assertEquals("raced", snapshot.workingRoot.resolve("one.js").readText())
        }
    }

    @Test
    fun `root mutation in an extension zip excluded directory is detected`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(extension())

            assertFailsWith<ConversionExecutionException> {
                workspace.transformForTesting(
                    snapshot = snapshot,
                    profile = profile,
                    plan = emptyPlan,
                    mutateStage = {
                        it.resolve("one.js").writeText("staged")
                    },
                    beforePublish = {
                        val raced = snapshot.workingRoot.resolve("node_modules")
                        Files.createDirectory(raced)
                        raced.resolve("race.js").writeText("external")
                    },
                )
            }

            assertEquals(
                "external",
                snapshot.workingRoot.resolve("node_modules/race.js").readText(),
            )
            assertEquals("one", snapshot.workingRoot.resolve("one.js").readText())
        }
    }

    @Test
    fun `backup mutation after root move is restored and not published`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(extension())

            assertFailsWith<ConversionExecutionException> {
                workspace.transformForTesting(
                    snapshot = snapshot,
                    profile = profile,
                    plan = emptyPlan,
                    mutateStage = {
                        it.resolve("one.js").writeText("staged")
                    },
                    afterBackupMove = { backup ->
                        backup.resolve("one.js").writeText("raced-backup")
                    },
                )
            }

            assertEquals("raced-backup", snapshot.workingRoot.resolve("one.js").readText())
            assertFalse(snapshot.workingRoot.resolve("one.js").readText() == "staged")
        }
    }

    @Test
    fun `published validation failure restores the exact previous tree`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(extension())
            val before = WorkspaceTreeDigest.contentSha256(snapshot.workingRoot)

            assertFailsWith<ConversionExecutionException> {
                workspace.transformForTesting(
                    snapshot = snapshot,
                    profile = profile,
                    plan = emptyPlan,
                    mutateStage = {
                        it.resolve("one.js").writeText("staged")
                    },
                    afterStagePublish = { published ->
                        published.resolve("one.js").writeText("raced-published")
                    },
                )
            }

            assertEquals(before, WorkspaceTreeDigest.contentSha256(snapshot.workingRoot))
            assertEquals("one", snapshot.workingRoot.resolve("one.js").readText())
            ConversionWorkspaceOwnership.requireUsable(snapshot.workingRoot)
        }
    }

    @Test
    fun `ownership update race restores the previous usable tree`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(extension())
            val before = WorkspaceTreeDigest.provenanceSha256(
                snapshot.workingRoot,
            )

            assertFailsWith<ConversionExecutionException> {
                workspace.transformForTesting(
                    snapshot = snapshot,
                    profile = profile,
                    plan = emptyPlan,
                    mutateStage = {
                        it.resolve("one.js").writeText("staged")
                    },
                    beforeOwnershipUpdate = { published ->
                        published.resolve("one.js").writeText("late-race")
                    },
                )
            }

            assertEquals(
                before,
                WorkspaceTreeDigest.provenanceSha256(snapshot.workingRoot),
            )
            assertEquals("one", snapshot.workingRoot.resolve("one.js").readText())
            ConversionWorkspaceOwnership.requireUsable(snapshot.workingRoot)
        }
    }

    @Test
    fun `failed publish and failed restore persist poison state`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(extension())
            var moves = 0

            assertFailsWith<ConversionExecutionException> {
                workspace.transformForTesting(
                    snapshot = snapshot,
                    profile = profile,
                    plan = emptyPlan,
                    moveDirectory = { from, to ->
                        moves++
                        when (moves) {
                            1 -> Files.move(from, to, ATOMIC_MOVE)
                            else -> throw IllegalStateException("injected move failure")
                        }
                    },
                    mutateStage = {
                        it.resolve("one.js").writeText("complete-stage")
                    },
                )
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
    fun `poison persistence failure remains in memory and exposes a fixed error`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(extension())

            val error = assertFailsWith<WorkspacePoisonPersistenceException> {
                ConversionWorkspaceOwnership.poisonForTesting(
                    snapshot.workingRoot,
                ) { _, _ ->
                    throw IllegalStateException(
                        "${snapshot.workingRoot}/secret-source",
                    )
                }
            }

            assertEquals(
                "conversion workspace poison 상태를 디스크에 기록하지 못했습니다.",
                error.message,
            )
            assertEquals(null, error.cause)
            assertFailsWith<IllegalStateException> {
                ConversionWorkspaceOwnership.requireUsable(snapshot.workingRoot)
            }
        }
    }

    @Test
    fun `public conversion exception exposes neither staged path nor source text`() {
        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(extension())
            val secretSource = "chrome.storage.sync.get('secret-source')"
            var stagedPath = ""

            val error = assertFailsWith<ConversionExecutionException> {
                workspace.transformForTesting(
                    snapshot = snapshot,
                    profile = profile,
                    plan = emptyPlan,
                    mutateStage = { stage ->
                        stagedPath = stage.toString()
                        throw IllegalStateException("$stagedPath $secretSource")
                    },
                )
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
        val emptyPlan = conversionPlanOf()
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
