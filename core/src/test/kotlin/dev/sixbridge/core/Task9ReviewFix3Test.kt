package dev.sixbridge.core

import dev.sixbridge.core.conversion.SecureBuildPublisher
import dev.sixbridge.core.draft.DraftSourceStreams
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
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

class Task9ReviewFix3Test {
    @Test
    fun `build protects all basename sidecars before persistent prepare`() {
        listOf("source-crx", "profile-wrapper", "key-wrapper").forEach { kind ->
            val workspace = Files.createTempDirectory("six-review-derived-$kind")
            val source = source(workspace.resolve("source"))
            val profileFile = workspace.resolve("profile.json")
                .also { it.writeText("""{"profile":"sentinel"}""") }
            val local = workspace.resolve(".local")
            val keyFile = local.resolve("keystore/wrapper.p12").also {
                it.parent.createDirectories()
                it.writeBytes("wrapper-key-sentinel".toByteArray())
            }
            local.resolve("keystore/crx/sentinel").also {
                it.parent.createDirectories()
                it.writeText("crx-key-sentinel")
            }
            local.resolve("source-identities/sentinel").also {
                it.parent.createDirectories()
                it.writeText("identity-sentinel")
            }
            local.resolve("versions/sentinel").also {
                it.parent.createDirectories()
                it.writeText("version-sentinel")
            }
            val outputParent = workspace.resolve("output").also(Files::createDirectory)
            val protectedFile = when (kind) {
                "source-crx" -> source.resolve("manifest.json")
                "profile-wrapper" -> profileFile
                "key-wrapper" -> keyFile
                else -> error("unexpected case")
            }
            val derived = when (kind) {
                "source-crx" -> outputParent.resolve("converted.crx")
                else -> outputParent.resolve("converted-wrapper.apk")
            }
            Files.createLink(derived, protectedFile)
            val sourceBefore = treeDigest(source)
            val localBefore = treeDigest(local)
            var toolchainRequests = 0

            assertFailsWith<UnsafeOutputException>(kind) {
                SixBridgeService(
                    workspaceRoot = workspace,
                    toolchainProvider = {
                        toolchainRequests += 1
                        error("파생 target 검증 전에 toolchain을 조회했습니다.")
                    },
                ).build(
                    input = source.toString(),
                    profile = profile(),
                    outputBundle = outputParent.resolve("converted.six"),
                    protectedInputs = listOf(profileFile),
                )
            }

            assertEquals(0, toolchainRequests, kind)
            assertEquals(sourceBefore, treeDigest(source), kind)
            assertEquals(localBefore, treeDigest(local), kind)
        }
    }

    @Test
    fun `build rejects a missing output parent without creating or deleting anything`() {
        val workspace = Files.createTempDirectory("six-review-missing-parent")
        val source = source(workspace.resolve("source"))
        val anchor = workspace.resolve("anchor").also(Files::createDirectory)
        anchor.resolve("sentinel").writeText("original")
        val output = anchor.resolve("new-parent/converted.six")
        var toolchainRequests = 0

        val error = assertFailsWith<UnsafeOutputException> {
            SixBridgeService(
                workspaceRoot = workspace,
                toolchainProvider = {
                    toolchainRequests += 1
                    error("없는 output parent에서 wrapper를 빌드하면 안 됩니다.")
                },
            ).build(
                input = source.toString(),
                profile = profile(),
                outputBundle = output,
            )
        }

        assertEquals(0, toolchainRequests)
        assertTrue(error.message.orEmpty().contains("mkdir -p"))
        assertEquals("original", anchor.resolve("sentinel").toFile().readText())
        assertEquals(
            listOf("sentinel"),
            Files.list(anchor).use { paths ->
                paths.map { it.fileName.toString() }.sorted().toList()
            },
        )
        assertFalse(anchor.resolve("new-parent").exists())
        assertFalse(workspace.resolve(".local").exists())
    }

    @Test
    fun `draft rejects a missing output parent before adapter approval or prepare`() {
        val workspace = Files.createTempDirectory("six-review-draft-missing-parent")
        val source = source(workspace.resolve("source"))
        source.resolve("popup.html").writeText("<p>popup</p>")
        source.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Draft missing parent",
              "version": "1",
              "action": {"default_popup": "popup.html"}
            }
            """.trimIndent(),
        )
        val output = workspace.resolve("missing/converted.sixdraft")

        val error = assertFailsWith<UnsafeOutputException> {
            SixBridgeService(workspace).draft(
                input = source.toString(),
                profile = profile(),
                outputDraft = output,
            )
        }

        assertTrue(error.message.orEmpty().contains("mkdir -p"))
        assertFalse(workspace.resolve("missing").exists())
        assertFalse(workspace.resolve(".local").exists())
    }

    @Test
    fun `publication guard recapture rejects a different target set`() {
        val parent = Files.createTempDirectory("six-review-guard-target-set")
        val first = parent.resolve("first.six")
        val second = parent.resolve("second.six")
        val guard = SecureBuildPublisher.capturePublicationGuard(listOf(first))

        assertFailsWith<IllegalArgumentException> {
            SecureBuildPublisher.capturePublicationGuard(
                outputs = listOf(second),
                expectedParent = guard,
            )
        }
    }

    @Test
    fun `source stream comparison fills asymmetric chunks and rejects mismatches`() {
        val bytes = ByteArray(200_000) { index -> (index % 251).toByte() }

        assertTrue(
            DraftSourceStreams.contentEquals(
                ChunkedInputStream(bytes, 1_024),
                ChunkedInputStream(bytes, 64 * 1024),
            ),
        )
        assertFalse(
            DraftSourceStreams.contentEquals(
                ChunkedInputStream(bytes, 1_024),
                ChunkedInputStream(bytes.copyOf().also { it[100_000]++ }, 64 * 1024),
            ),
        )
        assertFalse(
            DraftSourceStreams.contentEquals(
                ChunkedInputStream(bytes, 1_024),
                ChunkedInputStream(bytes.copyOf(bytes.size - 1), 64 * 1024),
            ),
        )
    }

    private class ChunkedInputStream(
        bytes: ByteArray,
        private val chunkSize: Int,
    ) : ByteArrayInputStream(bytes) {
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, minOf(length, chunkSize))
    }

    private fun source(root: Path): Path {
        root.createDirectories()
        root.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Review 3","version":"1"}""",
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

    private fun treeDigest(root: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.walk(root).use { paths ->
            paths.filter(Files::isRegularFile)
                .sorted()
                .forEach { path ->
                    digest.update(root.relativize(path).toString().toByteArray())
                    digest.update(path.readBytes())
                }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
