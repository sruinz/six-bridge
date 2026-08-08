package dev.sixbridge.core

import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.crx.ExtensionKeyManager
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class Task9ReviewFixTest {
    @Test
    fun `draft rejects source input archive and key store output overlap before mutation`() {
        val workspace = Files.createTempDirectory("six-review-output-workspace")
        val source = source(workspace.resolve("source"), popup = false)
        val service = SixBridgeService(workspace)
        val manifest = source.resolve("manifest.json")
        val manifestBefore = manifest.readBytes()

        assertFailsWith<IllegalArgumentException> {
            service.draft(
                source.toString(),
                profile(),
                manifest,
            )
        }
        assertContentEquals(manifestBefore, manifest.readBytes())
        val sourceAlias = workspace.resolve("source-alias")
        Files.createSymbolicLink(sourceAlias, source)
        assertFailsWith<IllegalArgumentException> {
            service.draft(
                source.toString(),
                profile(),
                sourceAlias.resolve("aliased.sixdraft"),
            )
        }
        assertContentEquals(manifestBefore, manifest.readBytes())

        val crxSource = source(workspace.resolve("crx-source"), popup = false)
        val packed = Crx3.pack(crxSource, rsa())
        val crx = workspace.resolve("input.crx").also { it.writeBytes(packed.bytes) }
        val crxBefore = crx.readBytes()
        assertFailsWith<IllegalArgumentException> {
            service.draft(crx.toString(), profile(), crx)
        }
        assertContentEquals(crxBefore, crx.readBytes())

        service.draft(
            source.toString(),
            profile(),
            workspace.resolve("seed.sixdraft"),
            projectIdentity = "0123456789abcdef0123456789abcdef",
        )
        val privateKey = workspace.resolve(
            ".local/keystore/draft-transfer/draft-transfer.pk8",
        )
        val keyBefore = privateKey.readBytes()
        assertFailsWith<IllegalArgumentException> {
            service.draft(source.toString(), profile(), privateKey)
        }
        assertContentEquals(keyBefore, privateKey.readBytes())
    }

    @Test
    fun `build rejects a source tree output before toolchain or tree mutation`() {
        val workspace = Files.createTempDirectory("six-review-build-workspace")
        val source = source(workspace.resolve("source"), popup = false)
        val before = treeDigest(source)
        val service = SixBridgeService(workspace) {
            error("unsafe output 검증 전에 toolchain을 조회했습니다.")
        }

        assertFailsWith<IllegalArgumentException> {
            service.build(
                source.toString(),
                profile(),
                source.resolve("inside.six"),
            )
        }

        assertEquals(before, treeDigest(source))
    }

    @Test
    fun `source manifest key ID binds passthrough and converted drafts`() {
        verifyManifestKeyDrafts(zipInput = false)
    }

    @Test
    fun `ZIP manifest key ID binds passthrough and converted drafts`() {
        verifyManifestKeyDrafts(zipInput = true)
    }

    @Test
    fun `draft rejects a valid replacement signed after publication`() {
        val workspace = Files.createTempDirectory("six-review-self-verify")
        val replacingReader: (Path) -> dev.sixbridge.core.draft.VerifiedSixDraft = { path ->
            val original = SixDraft.read(path)
            path.writeBytes(SixDraft.createBytes(original.payload, ed25519()))
            SixDraft.read(path)
        }
        val service = SixBridgeService(
            workspaceRoot = workspace,
            draftReader = replacingReader,
        )

        assertFailsWith<IllegalStateException> {
            service.draft(
                source(workspace.resolve("source"), popup = false).toString(),
                profile(),
                workspace.resolve("replaced.sixdraft"),
                projectIdentity = "0123456789abcdef0123456789abcdef",
            )
        }
    }

    private fun verifyManifestKeyDrafts(zipInput: Boolean) {
        listOf(false, true).forEach { converted ->
            val workspace = Files.createTempDirectory("six-review-manifest-key")
            val key = rsa()
            val extensionId = Crx3.extensionId(key.public.encoded)
            ExtensionKeyManager(
                workspace,
                workspace.resolve(".local/keystore/crx"),
            ).persist(extensionId, key)
            val source = source(
                workspace.resolve("source"),
                popup = converted,
                manifestKey = Base64.getEncoder().encodeToString(key.public.encoded),
            )
            val input = if (zipInput) {
                workspace.resolve("input.zip").also {
                    ExtensionZip.create(source, it)
                }
            } else {
                source
            }
            val output = workspace.resolve("result.sixdraft")
            val result = SixBridgeService(workspace).draft(
                input.toString(),
                profile(),
                output,
                approvedAdapterIds = if (converted) {
                    setOf("action-popup-entry")
                } else {
                    emptySet()
                },
            )

            val verified = SixDraft.read(output)
            assertEquals(
                extensionId,
                verified.payload.compatibilityBefore.originalExtensionId,
            )
            assertEquals(extensionId, verified.payload.input.originalExtensionId)
            assertEquals(
                extensionId,
                verified.payload.compatibilityAfter.originalExtensionId,
            )
            assertEquals(
                if (converted) "CONVERTED" else "PASSTHROUGH",
                result.conversionState.name,
            )
        }
    }

    private fun source(
        root: Path,
        popup: Boolean,
        manifestKey: String? = null,
    ): Path {
        Files.createDirectories(root)
        if (popup) {
            root.resolve("content.js").writeText("console.log('content');")
            root.resolve("popup.html").writeText(
                "<html><head></head><body><p>popup</p></body></html>",
            )
        }
        val key = manifestKey?.let { ""","key":"$it"""" }.orEmpty()
        val action = if (popup) {
            ""","action":{"default_popup":"popup.html"},"content_scripts":[{"matches":["https://example.test/*"],"js":["content.js"]}]"""
        } else {
            ""
        }
        root.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Review","version":"1"$key$action}""",
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
        supportedFeatures = setOf("extension.options-page.user-accessible"),
    )

    private fun rsa() = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()

    private fun ed25519() =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

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
