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
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.zip.UnixStat
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipFile as CommonsZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel

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

    @Test
    fun `Android identity preview fixtures는 signed semantics와 entry allowlist를 고정한다`() {
        identityPreviewFixtures.forEach { fixture ->
            val fixtureBytes = Files.readAllBytes(fixture.path)
            val outerEntries = readRegularZipEntries(fixtureBytes, fixture.name)
            val verified = SixDraft.read(fixtureBytes)
            val payload = verified.payload
            val crx = Crx3.parse(payload.source)

            assertEquals(fixture.sha256, sha256(fixtureBytes), fixture.name)
            assertEquals(fixture.schemaVersion, payload.draft.schemaVersion, fixture.name)
            assertEquals(fixture.inputKind, payload.input.inputKind, fixture.name)
            assertEquals(fixture.sourceKind, payload.sourceKind, fixture.name)
            assertEquals(profile.canonicalSha256(), payload.input.browserProfileSha256)
            assertEquals(payload.draft.sourceIdentity, payload.input.sourceIdentity)
            assertEquals(
                setOf(
                    "compatibility-after.json",
                    "compatibility-before.json",
                    "conversion-plan.json",
                    "draft-signature.json",
                    "draft.json",
                    "expected.json",
                    "input.json",
                    fixture.sourceKind.entryName,
                ),
                outerEntries.keys,
                fixture.name,
            )
            assertEquals(outerEntries.keys, verified.entryNames, fixture.name)
            assertEquals(64, verified.signerFingerprint.length, fixture.name)
            val outerJson = outerEntries
                .filterKeys { it.endsWith(".json") }
                .values
                .joinToString("\n") { it.decodeToString() }
            FORBIDDEN_FIXTURE_PATTERNS.forEach { forbidden ->
                assertFalse(forbidden.containsMatchIn(outerJson), fixture.name)
            }

            val embeddedEntries = readRegularZipEntries(crx.zipBytes, "${fixture.name} CRX")
            assertEquals(setOf("manifest.json"), embeddedEntries.keys, fixture.name)
            val manifest = Json.parseToJsonElement(
                embeddedEntries.getValue("manifest.json").decodeToString(),
            ).jsonObject
            assertEquals(
                setOf("manifest_version", "name", "version"),
                manifest.keys,
                fixture.name,
            )
            assertEquals("3", manifest.getValue("manifest_version").jsonPrimitive.content)
            assertEquals(fixture.extensionName, manifest.getValue("name").jsonPrimitive.content)
            assertEquals("1.0", manifest.getValue("version").jsonPrimitive.content)
            assertEquals(
                "RSA",
                KeyFactory.getInstance("RSA")
                    .generatePublic(X509EncodedKeySpec(crx.publicKey))
                    .algorithm,
                fixture.name,
            )
            assertFalse(payload.compatibilityBefore.manifestKeyPresent, fixture.name)
            assertEquals(null, payload.compatibilityBefore.manifestKeyExtensionId, fixture.name)
            assertFalse(payload.compatibilityAfter.manifestKeyPresent, fixture.name)
            assertEquals(null, payload.compatibilityAfter.manifestKeyExtensionId, fixture.name)
            assertEquals(crx.extensionId, payload.compatibilityAfter.convertedExtensionId)

            if (fixture.schemaVersion == 2) {
                assertTrue(payload.plan.selectedAdapters.isEmpty(), fixture.name)
                assertEquals(
                    ConversionState.PASSTHROUGH,
                    payload.compatibilityAfter.conversionState,
                    fixture.name,
                )
                assertEquals(crx.extensionId, payload.input.originalExtensionId, fixture.name)
                assertEquals(
                    "store:${crx.extensionId}",
                    payload.draft.sourceIdentity,
                    fixture.name,
                )
                assertEquals(
                    payload.expected.originalSourceSha256,
                    payload.expected.transformedSourceSha256,
                    fixture.name,
                )
            } else {
                assertEquals(
                    PREPARED_FIXTURE_SOURCE_IDENTITY,
                    payload.draft.sourceIdentity,
                    fixture.name,
                )
            }
        }
    }

    private fun readRegularZipEntries(
        bytes: ByteArray,
        label: String,
    ): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        SeekableInMemoryByteChannel(bytes).use { channel ->
            CommonsZipFile.builder()
                .setSeekableByteChannel(channel)
                .get()
                .use { zip ->
                    zip.entries.asSequence().forEach { entry ->
                        val segments = entry.name.split('/')
                        assertFalse(
                            entry.isDirectory,
                            "$label: directory entry ${entry.name}",
                        )
                        assertTrue(
                            zip.canReadEntryData(entry),
                            "$label: unreadable ${entry.name}",
                        )
                        assertFalse(entry.isUnixSymlink, "$label: symlink ${entry.name}")
                        if (entry.platform == ZipArchiveEntry.PLATFORM_UNIX) {
                            assertEquals(
                                UnixStat.FILE_FLAG,
                                entry.unixMode and UnixStat.FILE_TYPE_FLAG,
                                "$label: non-regular ${entry.name}",
                            )
                        }
                        assertFalse(
                            entry.name.startsWith('/') || '\\' in entry.name,
                            label,
                        )
                        assertTrue(
                            segments.all { it.isNotEmpty() && it != "." && it != ".." },
                            "$label: unsafe entry ${entry.name}",
                        )
                        assertFalse(
                            entries.containsKey(entry.name),
                            "$label: duplicate ${entry.name}",
                        )
                        entries[entry.name] = zip.getInputStream(entry).use { it.readBytes() }
                    }
                }
        }
        return entries
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

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
            it.resolve("popup.html").writeText(
                "<html><head></head><body><p>Portable popup</p></body></html>",
            )
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
        private const val PREPARED_FIXTURE_SOURCE_IDENTITY =
            "project:cccccccccccccccccccccccccccccccc"
        private val FORBIDDEN_FIXTURE_PATTERNS = listOf(
            Regex("BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY", RegexOption.IGNORE_CASE),
            Regex("/(?:Users|Volumes|private/var)/", RegexOption.IGNORE_CASE),
            Regex("\\b(?:[a-z0-9-]+\\.)+local\\b", RegexOption.IGNORE_CASE),
            Regex(
                "\\b(?:10(?:\\.\\d{1,3}){3}|192\\.168(?:\\.\\d{1,3}){2}|" +
                    "172\\.(?:1[6-9]|2\\d|3[01])(?:\\.\\d{1,3}){2})\\b",
            ),
            Regex("\\bSM-[A-Z0-9]+\\b", RegexOption.IGNORE_CASE),
            Regex("\\bemulator-\\d+\\b", RegexOption.IGNORE_CASE),
            Regex(
                "\\b(?:ANDROID_SERIAL|localhost|hostname|adb\\s+shell)\\b",
                RegexOption.IGNORE_CASE,
            ),
        )
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
        private val identityPreviewFixtures = listOf(
            IdentityPreviewFixture(
                name = "CRX3 schema 2",
                path = Path.of(
                    "../android-manager/src/androidTest/assets/" +
                        "draft-identity-preview-crx3-schema2.sixdraft",
                ),
                schemaVersion = 2,
                inputKind = ExtensionInputKind.CRX3,
                sourceKind = DraftSourceKind.CRX,
                extensionName = "Identity Preview",
                sha256 =
                    "9658077d6236ebf13ac98b25ebcc0bd4b0b98673fcb04d77ece14a813d41b14b",
            ),
            IdentityPreviewFixture(
                name = "Chrome Web Store schema 2",
                path = Path.of(
                    "../android-manager/src/androidTest/assets/" +
                        "draft-identity-preview-chrome-web-store-schema2.sixdraft",
                ),
                schemaVersion = 2,
                inputKind = ExtensionInputKind.CHROME_WEB_STORE,
                sourceKind = DraftSourceKind.CRX,
                extensionName = "Identity Preview",
                sha256 =
                    "652613abf2096fc4c4c1c39c365ab96fb5c2e954114a0996605231d2f442d5ff",
            ),
            IdentityPreviewFixture(
                name = "prepared schema 3",
                path = Path.of(
                    "../android-manager/src/androidTest/assets/" +
                        "draft-identity-preview-prepared-schema3.sixdraft",
                ),
                schemaVersion = 3,
                inputKind = ExtensionInputKind.ZIP,
                sourceKind = DraftSourceKind.PREPARED_CRX,
                extensionName = "Preview",
                sha256 =
                    "34a4390db6664560194724bd0920fc4109109f5e07bb130d206fb1cf635078a2",
            ),
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

    private data class IdentityPreviewFixture(
        val name: String,
        val path: Path,
        val schemaVersion: Int,
        val inputKind: ExtensionInputKind,
        val sourceKind: DraftSourceKind,
        val extensionName: String,
        val sha256: String,
    )
}
