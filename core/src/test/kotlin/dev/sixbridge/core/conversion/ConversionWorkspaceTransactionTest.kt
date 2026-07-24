package dev.sixbridge.core.conversion

import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversionWorkspaceTransactionTest {
    @Test
    fun `publishes only a fully transformed staged tree`() {
        val source = Files.createTempDirectory("sixbridge-transaction-source-")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Transaction","version":"1"}""",
        )
        source.resolve("one.js").writeText("one")
        source.resolve("two.js").writeText("two")

        ConversionWorkspace().use { workspace ->
            val snapshot = workspace.snapshot(source)

            ConversionWorkspaceTransaction().run(snapshot.workingRoot) { staged ->
                staged.resolve("one.js").writeText("changed-one")
                staged.resolve("two.js").writeText("changed-two")
            }

            assertEquals("changed-one", snapshot.workingRoot.resolve("one.js").readText())
            assertEquals("changed-two", snapshot.workingRoot.resolve("two.js").readText())
            assertEquals("one", snapshot.originalRoot.resolve("one.js").readText())
            ConversionWorkspaceOwnership.requireUsable(snapshot.workingRoot)
        }
    }

    @Test
    fun `transform failure leaves the published tree byte unchanged`() {
        val source = Files.createTempDirectory("sixbridge-transaction-source-")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Transaction","version":"1"}""",
        )
        source.resolve("one.js").writeText("one")

        ConversionWorkspace().use { workspace ->
            val working = workspace.snapshot(source).workingRoot

            assertFailsWith<IllegalStateException> {
                ConversionWorkspaceTransaction().run(working) { staged ->
                    staged.resolve("one.js").writeText("partial")
                    throw IllegalStateException("transform failed")
                }
            }

            assertEquals("one", working.resolve("one.js").readText())
            ConversionWorkspaceOwnership.requireUsable(working)
        }
    }

    @Test
    fun `failed publish and failed restore poison the output path`() {
        val source = Files.createTempDirectory("sixbridge-transaction-source-")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Transaction","version":"1"}""",
        )
        source.resolve("one.js").writeText("one")

        ConversionWorkspace().use { workspace ->
            val working = workspace.snapshot(source).workingRoot
            var moves = 0
            val transaction = ConversionWorkspaceTransaction(
                moveDirectory = { from, to ->
                    moves++
                    when (moves) {
                        1 -> Files.move(from, to, ATOMIC_MOVE)
                        else -> throw IllegalStateException("injected move failure $moves")
                    }
                },
            )

            assertFailsWith<IllegalStateException> {
                transaction.run(working) { staged ->
                    staged.resolve("one.js").writeText("complete-stage")
                }
            }

            assertFalse(Files.exists(working))
            assertTrue(moves >= 3)
            assertFailsWith<IllegalStateException> {
                ConversionWorkspaceOwnership.requireUsable(working)
            }
        }
    }
}
