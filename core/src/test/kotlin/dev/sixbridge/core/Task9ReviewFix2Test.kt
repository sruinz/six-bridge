package dev.sixbridge.core

import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.conversion.SecureBuildPublisher
import dev.sixbridge.core.draft.DraftSourceStreams
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.KeyPairGenerator
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Task9ReviewFix2Test {
    @Test
    fun `draft rejects hardlink aliases of source archive profile and key before mutation`() {
        listOf("source", "zip", "crx", "profile", "key").forEach { kind ->
            val workspace = Files.createTempDirectory("six-review-hardlink-$kind")
            val source = source(workspace.resolve("source"))
            val profilePath = workspace.resolve("profile.json")
                .also { it.writeText("""{"sentinel":"profile"}""") }
            val input = when (kind) {
                "zip" -> workspace.resolve("input.zip").also {
                    ExtensionZip.create(source, it)
                }
                "crx" -> workspace.resolve("input.crx").also {
                    it.writeBytes(Crx3.pack(source, rsa()).bytes)
                }
                else -> source
            }
            val protectedFile = when (kind) {
                "source" -> source.resolve("manifest.json")
                "zip", "crx" -> input
                "profile" -> profilePath
                "key" -> workspace.resolve(".local/keystore/existing/key.bin").also {
                    it.parent.createDirectories()
                    it.writeBytes("persistent-key".toByteArray())
                }
                else -> error("unexpected case")
            }
            val output = workspace.resolve("output-$kind.sixdraft")
            Files.createLink(output, protectedFile)
            val before = treeBytes(workspace)

            assertFailsWith<UnsafeOutputException>(kind) {
                SixBridgeService(workspace).draft(
                    input = input.toString(),
                    profile = profile(),
                    outputDraft = output,
                    protectedInputs = listOf(profilePath),
                )
            }

            assertEquals(before, treeBytes(workspace), kind)
        }
    }

    @Test
    fun `output parent replacement fails before key version or wrapper work`() {
        val workspace = Files.createTempDirectory("six-review-parent-swap")
        val source = source(workspace.resolve("source"))
        val outputParent = workspace.resolve("output").also(Files::createDirectory)
        outputParent.resolve("sentinel").writeText("original-parent")
        val movedParent = workspace.resolve("output-original")
        val sourceBefore = treeBytes(source)
        var toolchainRequests = 0
        val service = SixBridgeService(
            workspaceRoot = workspace,
            outputGuardHook = {
                Files.move(outputParent, movedParent, ATOMIC_MOVE)
                Files.createDirectory(outputParent)
            },
            toolchainProvider = {
                toolchainRequests += 1
                error("교체된 output parent에서 wrapper를 빌드하면 안 됩니다.")
            },
        )

        assertFailsWith<UnsafeOutputException> {
            service.build(
                input = source.toString(),
                profile = profile(),
                outputBundle = outputParent.resolve("result.six"),
            )
        }

        assertEquals(0, toolchainRequests)
        assertEquals(sourceBefore, treeBytes(source))
        assertEquals("original-parent", movedParent.resolve("sentinel").toFile().readText())
        assertFalse(workspace.resolve(".local").exists())
        assertTrue(Files.list(outputParent).use { it.findAny().isEmpty })
    }

    @Test
    fun `publisher binds publication to the initially captured parent identity`() {
        val parent = Files.createTempDirectory("six-review-publisher-parent")
        val moved = parent.resolveSibling("${parent.fileName}-original")
        val output = parent.resolve("result.six")
        val guard = SecureBuildPublisher.capturePublicationGuard(listOf(output))
        Files.move(parent, moved, ATOMIC_MOVE)
        Files.createDirectory(parent)

        assertFailsWith<IllegalStateException> {
            SecureBuildPublisher.publish(
                outputs = mapOf(output to "replacement".toByteArray()),
                expectedGuard = guard,
            )
        }

        assertTrue(Files.list(parent).use { it.findAny().isEmpty })
        assertTrue(Files.list(moved).use { it.findAny().isEmpty })
    }

    @Test
    fun `draft self verification uses a bounded source stream`() {
        val workspace = Files.createTempDirectory("six-review-stream-verify")
        var streamCalls = 0
        var maximumRequest = 0
        val observer: (InputStream) -> InputStream = { source ->
            streamCalls += 1
            object : InputStream() {
                override fun read(): Int = source.read()

                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    maximumRequest = maxOf(maximumRequest, length)
                    return source.read(bytes, offset, length)
                }

                override fun close() = source.close()
            }
        }

        SixBridgeService(
            workspaceRoot = workspace,
            draftSourceStreamObserver = observer,
        ).draft(
            input = source(workspace.resolve("source")).toString(),
            profile = profile(),
            outputDraft = workspace.resolve("result.sixdraft"),
            projectIdentity = "0123456789abcdef0123456789abcdef",
        )

        assertEquals(1, streamCalls)
        assertTrue(maximumRequest in 1..(64 * 1024))
    }

    @Test
    fun `source stream comparison handles the maximum logical size with bounded buffers`() {
        val maximum = 512L * 1024L * 1024L
        val first = GeneratedInputStream(maximum)
        val second = GeneratedInputStream(maximum)

        assertTrue(DraftSourceStreams.contentEquals(first, second))

        assertEquals(maximum, first.totalRead)
        assertEquals(maximum, second.totalRead)
        assertTrue(first.maximumRequest in 1..(64 * 1024))
        assertTrue(second.maximumRequest in 1..(64 * 1024))
    }

    @Test
    fun `build accepts a distinct output beside its input CRX`() {
        val workspace = Files.createTempDirectory("six-review-crx-sibling")
        val source = source(workspace.resolve("source"))
        val input = workspace.resolve("input.crx").also {
            it.writeBytes(Crx3.pack(source, rsa()).bytes)
        }
        var toolchainRequests = 0
        class ExpectedStop : RuntimeException()
        val service = SixBridgeService(
            workspaceRoot = workspace,
            toolchainProvider = {
                toolchainRequests += 1
                throw ExpectedStop()
            },
        )

        assertFailsWith<ExpectedStop> {
            service.build(
                input = input.toString(),
                profile = profile(),
                outputBundle = workspace.resolve("safe-output.six"),
            )
        }

        assertEquals(1, toolchainRequests)
        assertTrue(input.exists())
    }

    private class GeneratedInputStream(
        private val size: Long,
    ) : InputStream() {
        var totalRead = 0L
            private set
        var maximumRequest = 0
            private set

        override fun read(): Int {
            if (totalRead == size) return -1
            totalRead += 1
            return 0
        }

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (totalRead == size) return -1
            maximumRequest = maxOf(maximumRequest, length)
            val count = minOf(length.toLong(), size - totalRead).toInt()
            totalRead += count
            return count
        }
    }

    private fun source(root: Path): Path {
        root.createDirectories()
        root.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Review 2","version":"1"}""",
        )
        return root
    }

    private fun profile() = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = setOf("action", "runtime", "storage", "tabs"),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    )

    private fun rsa() = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()

    private fun treeBytes(root: Path): Map<String, String> =
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile)
                .sorted()
                .iterator()
                .asSequence()
                .associate {
                    root.relativize(it).toString() to
                        MessageDigest.getInstance("SHA-256")
                            .digest(it.readBytes())
                            .joinToString("") { byte -> "%02x".format(byte) }
                }
        }
}
