package dev.sixbridge.core

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.conversion.BuildPublicationFaultInjection
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.conversion.ConversionTempTreeFactory
import dev.sixbridge.core.conversion.MobilePatternAnalyzer
import dev.sixbridge.core.conversion.SecureBuildPublisher
import dev.sixbridge.core.conversion.SecureConversionRoot
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.draft.DraftSelectedAdapter
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
import java.nio.file.Path
import java.security.KeyPairGenerator
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PortableResolvedDraftTest {
    @Test
    fun `public portable draft는 Android production runtime용 canonical fixture와 같다`() {
        SecureFixture().use { fixture ->
            fixture.service.draftPortableResolved(
                resolved = fixture.platformResolved,
                profile = platformProfile,
                outputDraft = fixture.output,
                projectIdentity = fixture.projectIdentity,
                approvedAdapterIds = setOf(
                    MobilePatternAnalyzer.POPUP_NATIVE_AUTOFILL_GUARD,
                ),
                protectedInputs = emptyList(),
                control = fixture.control,
            )
            val desktop = SixDraft.read(fixture.output)
            val androidAsset = SixDraft.read(platformDraftAsset)

            assertContentEquals(androidAsset.payload.source, desktop.payload.source)
            assertContentEquals(androidAsset.payload.draftJson, desktop.payload.draftJson)
            assertContentEquals(androidAsset.payload.inputJson, desktop.payload.inputJson)
            assertContentEquals(
                androidAsset.payload.conversionPlanJson,
                desktop.payload.conversionPlanJson,
            )
            assertContentEquals(
                androidAsset.payload.compatibilityBeforeJson,
                desktop.payload.compatibilityBeforeJson,
            )
            assertContentEquals(
                androidAsset.payload.compatibilityAfterJson,
                desktop.payload.compatibilityAfterJson,
            )
            assertContentEquals(
                androidAsset.payload.expectedJson,
                desktop.payload.expectedJson,
            )
            assertEquals(
                listOf(
                    DraftSelectedAdapter(
                        MobilePatternAnalyzer.BROWSER_INTERNAL_URL_MAPPER,
                        1,
                    ),
                    DraftSelectedAdapter(
                        MobilePatternAnalyzer.POPUP_NATIVE_AUTOFILL_GUARD,
                        1,
                    ),
                ),
                desktop.payload.plan.selectedAdapters,
            )
            assertEquals(
                desktop.payload.input.originalSourceSha256,
                desktop.payload.compatibilityBefore.sourceSha256,
            )
            assertEquals(
                desktop.payload.expected.transformedSourceSha256,
                desktop.payload.compatibilityAfter.sourceSha256,
            )
            assertEquals(
                desktop.payload.input.browserProfileSha256,
                desktop.payload.compatibilityBefore.browserProfileSha256,
            )
            assertEquals(
                desktop.payload.input.browserProfileSha256,
                desktop.payload.compatibilityAfter.browserProfileSha256,
            )
            assertEquals(
                desktop.payload.compatibilityBefore.sourceSha256,
                desktop.payload.plan.reportSourceSha256,
            )
            assertEquals(
                desktop.payload.compatibilityBefore.browserProfileSha256,
                desktop.payload.plan.browserProfileSha256,
            )
            assertEquals(
                desktop.payload.plan.planSha256,
                desktop.payload.expected.planSha256,
            )
            assertEquals(
                desktop.payload.input.originalSourceSha256,
                desktop.payload.expected.originalSourceSha256,
            )
            assertFalse(
                listOf(
                    desktop.payload.conversionPlanJson,
                    desktop.payload.compatibilityBeforeJson,
                    desktop.payload.compatibilityAfterJson,
                    desktop.payload.expectedJson,
                ).joinToString(separator = "") { it.decodeToString() }
                    .contains(PRIVATE_SENTINEL),
            )
        }
    }

    @Test
    fun `popup 원본 유지 portable draft는 passthrough와 빈 signed plan을 보존한다`() {
        SecureFixture().use { fixture ->
            fixture.service.draftPortableResolved(
                resolved = fixture.popupGuardResolved,
                profile = profile,
                outputDraft = fixture.output,
                projectIdentity = fixture.projectIdentity,
                approvedAdapterIds = emptySet(),
                protectedInputs = emptyList(),
                control = fixture.control,
            )
            val verified = SixDraft.read(fixture.output)
            val androidAsset = SixDraft.read(popupOriginalDraftAsset)

            assertEquals(ConversionState.PASSTHROUGH, verified.payload.compatibilityAfter.conversionState)
            assertTrue(verified.payload.plan.selectedAdapters.isEmpty())
            assertEquals(
                verified.payload.input.originalSourceSha256,
                verified.payload.expected.transformedSourceSha256,
            )
            assertEquals(
                verified.payload.compatibilityBefore.status,
                verified.payload.compatibilityAfter.status,
            )
            assertEquals(
                verified.payload.compatibilityBefore.adapterDecisions,
                verified.payload.compatibilityAfter.adapterDecisions,
            )
            assertContentEquals(androidAsset.payload.source, verified.payload.source)
            assertContentEquals(androidAsset.payload.draftJson, verified.payload.draftJson)
            assertContentEquals(androidAsset.payload.inputJson, verified.payload.inputJson)
            assertContentEquals(
                androidAsset.payload.conversionPlanJson,
                verified.payload.conversionPlanJson,
            )
            assertContentEquals(
                androidAsset.payload.compatibilityBeforeJson,
                verified.payload.compatibilityBeforeJson,
            )
            assertContentEquals(
                androidAsset.payload.compatibilityAfterJson,
                verified.payload.compatibilityAfterJson,
            )
            assertContentEquals(
                androidAsset.payload.expectedJson,
                verified.payload.expectedJson,
            )
        }
    }

    @Test
    fun `public portable draft는 secure root와 output이 같은 provider여도 guarded publisher를 사용한다`() {
        SecureFixture().use { fixture ->
            assertFails {
                BuildPublicationFaultInjection.forceAfterIntentBeforeStage {
                    fixture.service.draftPortableResolved(
                        resolved = fixture.sourceResolved,
                        profile = profile,
                        outputDraft = fixture.output,
                        projectIdentity = fixture.projectIdentity,
                    )
                }
            }
        }
    }

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

    @Test
    fun `실제 mobile adapter가 적용된 ZIP draft를 secure factory로 replay하면 proof가 일치한다`() {
        SecureFixture().use { fixture ->
            fixture.draftPortableResolved(
                resolved = fixture.popupResolved,
                projectIdentity = fixture.projectIdentity,
                profile = profile,
                approvedAdapterIds = setOf(MobilePatternAnalyzer.ACTION_POPUP_ENTRY),
            )
            val verified = SixDraft.read(fixture.output)
            val selected = listOf(
                DraftSelectedAdapter(MobilePatternAnalyzer.ACTION_POPUP_ENTRY, 2),
            )

            assertEquals(selected, verified.payload.plan.selectedAdapters)
            assertTrue(
                verified.payload.compatibilityAfter.adapterDecisions.single {
                    it.id == MobilePatternAnalyzer.ACTION_POPUP_ENTRY
                }.applied,
            )
            assertNotEquals(
                verified.payload.expected.originalSourceSha256,
                verified.payload.expected.transformedSourceSha256,
            )

            val replayed = assertIs<DraftReplayResult.Ready>(
                SixDraftReplay().replay(
                    verified,
                    profile,
                    selected.map { it.id }.toSet(),
                    fixture.control,
                    fixture.tempTreeFactory,
                ),
            ).prepared

            assertEquals(
                verified.payload.expected.transformedSourceSha256,
                replayed.transformedSourceSha256,
            )
            assertEquals(verified.payload.expected.planSha256, replayed.planSha256)
            assertEquals(verified.payload.expected.reportSha256, replayed.reportSha256)
            assertEquals(
                verified.payload.compatibilityAfter,
                replayed.compatibilityAfter,
            )
        }
    }

    @Test
    fun `기존 draftResolved는 schema3 PREPARED_CRX를 계속 생성한다`() {
        SecureFixture().use { fixture ->
            fixture.service.draftResolved(
                resolved = fixture.crxResolved,
                profile = profile,
                outputDraft = fixture.legacyOutput,
                projectIdentity = fixture.projectIdentity,
                approvedAdapterIds = emptyList(),
                protectedInputs = emptyList(),
                control = fixture.control,
                draftPublisher = fixture.testDraftPublisher,
            )
            val verified = SixDraft.read(fixture.legacyOutput)

            assertEquals(3, verified.payload.draft.schemaVersion)
            assertEquals(DraftSourceKind.PREPARED_CRX, verified.payload.sourceKind)
            assertTrue(verified.payload.source.isNotEmpty())
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
        val legacyOutput = outputDirectory.resolve("legacy.sixdraft")
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
        val platformResolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.SOURCE_DIRECTORY,
            originalInput = platformSource.toString(),
            sourceRoot = platformSource,
        )
        val popupGuardResolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.SOURCE_DIRECTORY,
            originalInput = popupGuardSource.toString(),
            sourceRoot = popupGuardSource,
        )
        private val popupSource = Files.createTempDirectory(
            "six-portable-popup-source",
        ).also {
            it.resolve("popup.html").writeText("<p>Portable popup</p>")
            it.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "Portable popup",
                  "version": "1",
                  "action": {"default_popup": "popup.html"}
                }
                """.trimIndent(),
            )
        }
        val popupResolved = ResolvedExtensionInput(
            kind = ExtensionInputKind.SOURCE_DIRECTORY,
            originalInput = popupSource.toString(),
            sourceRoot = popupSource,
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
        val testDraftPublisher: GuardedDraftPublisher = {
                target: java.nio.file.Path,
                payload: dev.sixbridge.core.draft.SixDraftPayload,
                key: java.security.KeyPair,
                guard: dev.sixbridge.core.conversion.PublicationGuard,
            ->
            SecureBuildPublisher.requirePublicationGuard(guard)
            check(Files.write(target, SixDraft.createBytes(payload, key)) == target)
        }
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
            profile: BrowserProfile = PortableResolvedDraftTest.profile,
            approvedAdapterIds: Iterable<String> = emptyList(),
        ): SixDraftResult = service.draftPortableResolved(
            resolved = resolved,
            profile = profile,
            outputDraft = output,
            projectIdentity = projectIdentity,
            approvedAdapterIds = approvedAdapterIds,
            protectedInputs = emptyList(),
            control = control,
            draftPublisher = testDraftPublisher,
        )

        override fun close() {
            fileSystem.close()
        }
    }

    private companion object {
        private const val PRIVATE_SENTINEL = "SIX_PRIVATE_SENTINEL_DO_NOT_LOG"
        private val platformSource =
            Path.of("../fixtures/popup-autofill-internal-url-platform")
        private val popupGuardSource =
            Path.of("../fixtures/popup-native-autofill-guard")
        private val platformDraftAsset = Path.of(
            "../android-manager/src/androidTest/assets/" +
                "popup-autofill-internal-url-platform.sixdraft",
        )
        private val popupOriginalDraftAsset = Path.of(
            "../android-manager/src/androidTest/assets/" +
                "popup-autofill-original.sixdraft",
        )
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
        val platformProfile = profile
    }
}
