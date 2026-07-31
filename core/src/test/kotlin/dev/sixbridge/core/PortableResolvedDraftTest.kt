package dev.sixbridge.core

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.conversion.ConversionTempTreeFactory
import dev.sixbridge.core.conversion.SecureConversionRoot
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.draft.DraftReplayResult
import dev.sixbridge.core.draft.DraftSigningIdentityProvider
import dev.sixbridge.core.draft.DraftSourceKind
import dev.sixbridge.core.draft.SixDraft
import dev.sixbridge.core.draft.SixDraftReplay
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.ResolvedExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.security.KeyPairGenerator
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class PortableResolvedDraftTest {
    @Test
    fun `source draft는 schema2 source zip이며 CRX key를 만들지 않는다`() {
        SecureFixture().use { fixture ->
            val result = fixture.draftPortableResolved(
                resolved = fixture.sourceResolved,
                projectIdentity = fixture.projectIdentity,
            )
            val verified = SixDraft.read(result.draft)

            assertEquals(2, verified.payload.draft.schemaVersion)
            assertEquals(DraftSourceKind.ZIP, verified.payload.sourceKind)
            assertFalse(
                Files.exists(fixture.workspace.resolve(".local/keystore/crx")),
            )
            assertContentEquals(fixture.originalCanonicalZip, verified.payload.source)
        }
    }

    @Test
    fun `CRX draft는 검증된 원본 CRX를 담고 Android replay가 일치한다`() {
        SecureFixture().use { fixture ->
            fixture.draftPortableResolved(
                resolved = fixture.crxResolved,
            )
            val verified = SixDraft.read(fixture.output)

            assertEquals(DraftSourceKind.CRX, verified.payload.sourceKind)
            assertContentEquals(fixture.originalCrx, verified.payload.source)
            assertIs<DraftReplayResult.Ready>(
                SixDraftReplay().replay(
                    verified,
                    profile,
                    verified.payload.plan.selectedAdapters.map { it.id }.toSet(),
                    fixture.control,
                    fixture.tempTreeFactory,
                ),
            )
            assertFalse(Files.exists(fixture.workspace.resolve(".local/keystore/crx")))
        }
    }

    private class SecureFixture : AutoCloseable {
        private val fileSystem = Jimfs.newFileSystem(
            Configuration.unix().toBuilder()
                .setAttributeViews("basic", "owner", "posix", "unix")
                .build(),
        )
        private val secureRootPath = Files.createDirectory(
            fileSystem.getPath("/secure-root"),
        )
        private val secureRoot = SecureConversionRoot.verify(secureRootPath)
        val tempTreeFactory = ConversionTempTreeFactory(secureRoot)
        val workspace = Files.createDirectory(fileSystem.getPath("/workspace"))
        private val outputDirectory = Files.createDirectory(workspace.resolve("drafts"))
        val output = outputDirectory.resolve("portable.sixdraft")
        val projectIdentity = "b".repeat(32)
        private val source = Files.createTempDirectory("six-portable-source").also {
            it.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Portable source","version":"1"}""",
            )
        }
        val originalCanonicalZip = ExtensionZip.createBytes(
            source,
            includeProjectMarker = true,
        )
        val sourceResolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.SOURCE_DIRECTORY,
            originalInput = source.toString(),
            sourceRoot = source,
        )
        private val packed = Crx3.pack(
            source,
            KeyPairGenerator.getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair(),
        )
        private val crxFile = workspace.resolve("portable.crx").also {
            Files.write(it, packed.bytes)
        }
        val originalCrx = packed.bytes
        val crxResolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.CRX3,
            originalInput = crxFile.toString(),
            sourceRoot = source,
            crxBytes = originalCrx,
            expectedExtensionId = packed.extensionId,
            archivePath = crxFile,
        )
        val control = AnalysisControl()
        val service = SixBridgeService(
            workspaceRoot = workspace,
            draftSigningIdentityProvider = DraftSigningIdentityProvider {
                KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            },
            secureConversionRoot = secureRoot,
        )
        fun draftPortableResolved(
            resolved: ResolvedExtensionInput,
            projectIdentity: String? = null,
        ): SixDraftResult = service.draftPortableResolved(
            resolved = resolved,
            profile = profile,
            outputDraft = output,
            projectIdentity = projectIdentity,
            approvedAdapterIds = emptyList(),
            protectedInputs = emptyList(),
            control = control,
        )

        override fun close() {
            fileSystem.close()
        }
    }

    private companion object {
        val profile = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = BrowserProfile.BASELINE_VERSION,
            versionCode = 1_300_261_502,
            majorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION,
            supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
            wrapperContract = WrapperContract.complete(),
            sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
            sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
            supportedFeatures = setOf("extension.options-page.user-accessible"),
        )
    }
}
