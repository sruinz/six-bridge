package dev.sixbridge.core.draft

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.sixbridge.core.compat.CompatibilityAnalyzer
import dev.sixbridge.core.conversion.ConversionPreparationEngine
import dev.sixbridge.core.conversion.ConversionTempTreeFactory
import dev.sixbridge.core.conversion.LogicalIdentityEvidence
import dev.sixbridge.core.conversion.LogicalSourceIdentityResult
import dev.sixbridge.core.conversion.SecureConversionRoot
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.PortableExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SixDraftReplayTest {
    @Test
    fun `portable draft replay는 두 adapter의 직접 변환과 같은 canonical 결과를 만든다`() {
        val profile = internalUrlProfile()
        val source = popupAndInternalUrlFixtureZip()
        val approvals = setOf("popup-native-autofill-guard")
        val portable = portableV2Draft(source, profile, approvals)

        val replayed = assertIs<DraftReplayResult.Ready>(
            SixDraftReplay().replay(portable.draft, profile, approvals),
        ).prepared

        assertEquals(portable.direct.compatibilityAfter, replayed.compatibilityAfter)
        assertEquals(portable.direct.appliedAdapterIds, replayed.appliedAdapterIds)
        assertEquals(
            setOf("browser-internal-url-mapper", "popup-native-autofill-guard"),
            replayed.appliedAdapterIds,
        )
        assertEquals(
            portable.direct.transformedSourceSha256,
            replayed.transformedSourceSha256,
        )
    }

    @Test
    fun `portable draft의 popup 원본 유지는 빈 승인과 동일 report를 재생한다`() {
        val profile = profile()
        val source = popupOnlyFixtureZip()
        val portable = portableV2Draft(source, profile, emptySet())

        val replayed = assertIs<DraftReplayResult.Ready>(
            SixDraftReplay().replay(portable.draft, profile, emptySet()),
        ).prepared

        assertTrue(portable.direct.appliedAdapterIds.isEmpty())
        assertTrue(replayed.appliedAdapterIds.isEmpty())
        assertEquals(portable.direct.compatibilityAfter, replayed.compatibilityAfter)
        assertContentEquals(source, replayed.transformedSourceZip)
    }

    @Test
    fun `schema v2 converted draft reruns the signed original in the current core`() {
        val fixture = convertedV2Fixture(
            sourceIdentity = "project:signed-identity",
            embeddedMarker = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        )
        val verified = SixDraft.read(fixture.bytes)

        val result = SixDraftReplay().replay(
            draft = verified,
            profile = fixture.profile,
            approvedAdapterIds = emptySet(),
        )
        val prepared = assertIs<DraftReplayResult.Ready>(result).prepared
        val identity = assertIs<LogicalSourceIdentityResult.Stable>(
            prepared.logicalSourceIdentity,
        )

        assertEquals(
            verified.payload.expected.transformedSourceSha256,
            prepared.transformedSourceSha256,
        )
        assertEquals(verified.payload.expected.planSha256, prepared.planSha256)
        assertEquals(verified.payload.expected.reportSha256, prepared.reportSha256)
        assertEquals("project:signed-identity", identity.value)
        assertEquals(LogicalIdentityEvidence.SIGNED_DRAFT_METADATA, identity.evidence)
        assertNotEquals("project:${fixture.embeddedMarker}", identity.value)
    }

    @Test
    fun `schema v2 converted CRX stores and reruns the original signed CRX`() {
        val profile = profile()
        val sourceRoot = Files.createTempDirectory("six-draft-v2-crx-source")
        sourceRoot.resolve("content.js").writeText(
            """
            document.addEventListener("mouseup", (event) => {
              const value = getSelection()?.toString();
              if (value) console.log(value);
            });
            """.trimIndent(),
        )
        sourceRoot.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Draft CRX Replay",
              "version": "1",
              "content_scripts": [{
                "matches": ["https://example.com/*"],
                "js": ["content.js"]
              }]
            }
            """.trimIndent(),
        )
        val original = dev.sixbridge.core.crx.Crx3.pack(sourceRoot, rsa())
        val identity = "store:${original.extensionId}"
        val prepared = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromCrx(
                    ExtensionInputKind.CRX3,
                    original.bytes,
                    original.extensionId,
                    signedDraftSourceIdentity = identity,
                ),
                profile,
                emptySet(),
            )
        }
        val selected = prepared.compatibilityAfter.adapterDecisions
            .filter { it.applied }
            .map { DraftSelectedAdapter(it.id, it.version) }
        val before = prepared.compatibilityBefore.copy(
            originalExtensionId = original.extensionId,
        )
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.createV2(
                "test-v2",
                selected.associate { it.id to it.version },
                identity,
            ),
            input = DraftInputMetadata(
                ExtensionInputKind.CRX3,
                prepared.originalSourceSha256,
                prepared.browserProfileSha256,
                original.extensionId,
                "1",
                identity,
            ),
            sourceKind = DraftSourceKind.CRX,
            source = original.bytes,
            plan = DraftConversionPlan.create(before, selected),
            compatibilityBefore = before,
            compatibilityAfter = prepared.compatibilityAfter,
        )
        val verified = SixDraft.read(
            SixDraft.createBytes(payload, ed25519()),
        )

        val replayed = assertIs<DraftReplayResult.Ready>(
            SixDraftReplay().replay(verified, profile, emptySet()),
        ).prepared

        assertContentEquals(original.bytes, verified.payload.source)
        assertEquals(
            verified.payload.expected.originalSourceSha256,
            dev.sixbridge.core.crx.Crx3.verifiedPayloadSha256(
                verified.payload.source,
            ),
        )
        assertEquals(
            verified.payload.expected.transformedSourceSha256,
            replayed.transformedSourceSha256,
        )
        assertEquals(identity, assertIs<LogicalSourceIdentityResult.Stable>(
            replayed.logicalSourceIdentity,
        ).value)
    }

    @Test
    fun `schema v2 source is rejected when it differs from signed original hash`() {
        val fixture = portablePassthroughV2Fixture()
        val entries = readEntries(fixture.bytes).toMutableMap()
        entries["source.zip"] = entries.getValue("source.zip").copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        resign(entries, fixture.signingKey)

        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(storedArchive(entries))
        }
    }

    @Test
    fun `schema v2 adapter ID와 version 변경은 재서명해도 거부한다`() {
        val fixture = portablePassthroughV2Fixture()
        val entries = readEntries(fixture.bytes).toMutableMap()
        entries["draft.json"] = replaceRequired(
            entries.getValue("draft.json"),
            "\"adapterVersions\":{}",
            "\"adapterVersions\":{\"storage-fallback\":1}",
        )
        resign(entries, fixture.signingKey)

        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(storedArchive(entries))
        }
    }

    @Test
    fun `schema v2 plan SHA 변경은 재서명해도 거부한다`() {
        val fixture = portablePassthroughV2Fixture()
        val verified = SixDraft.read(fixture.bytes)
        val entries = readEntries(fixture.bytes).toMutableMap()
        entries["conversion-plan.json"] = replaceRequired(
            entries.getValue("conversion-plan.json"),
            "\"planSha256\":\"${verified.payload.plan.planSha256}\"",
            "\"planSha256\":\"${"0".repeat(64)}\"",
        )
        resign(entries, fixture.signingKey)

        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(storedArchive(entries))
        }
    }

    @Test
    fun `schema v2 compatibility after report 변경은 재서명해도 거부한다`() {
        val fixture = portablePassthroughV2Fixture()
        val entries = readEntries(fixture.bytes).toMutableMap()
        entries["compatibility-after.json"] = replaceRequired(
            entries.getValue("compatibility-after.json"),
            "\"extensionName\":\"Portable Replay\"",
            "\"extensionName\":\"Mutated Replay\"",
        )
        resign(entries, fixture.signingKey)

        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(storedArchive(entries))
        }
    }

    @Test
    fun `schema v2 browser profile SHA 변경은 재서명해도 거부한다`() {
        val fixture = portablePassthroughV2Fixture()
        val profileSha256 = fixture.profile.canonicalSha256()
        val entries = readEntries(fixture.bytes).toMutableMap()
        entries["input.json"] = replaceRequired(
            entries.getValue("input.json"),
            "\"browserProfileSha256\":\"$profileSha256\"",
            "\"browserProfileSha256\":\"${"0".repeat(64)}\"",
        )
        resign(entries, fixture.signingKey)

        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(storedArchive(entries))
        }
    }

    @Test
    fun `schema v2 requires matching signed metadata and input source identity`() {
        val fixture = convertedV2Fixture()
        val entries = readEntries(fixture.bytes).toMutableMap()
        entries["input.json"] = entries.getValue("input.json")
            .decodeToString()
            .replace(
                "\"sourceIdentity\":\"project:signed-identity\"",
                "\"sourceIdentity\":\"project:other-identity\"",
            )
            .encodeToByteArray()
        resign(entries, fixture.signingKey)

        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(storedArchive(entries))
        }
    }

    @Test
    fun `schema v2 strict documents reject unknown keys even after resigning`() {
        val fixture = convertedV2Fixture()
        listOf("draft.json", "expected.json").forEach { name ->
            val entries = readEntries(fixture.bytes).toMutableMap()
            entries[name] = entries.getValue(name)
                .decodeToString()
                .dropLast(1)
                .plus(",\"unknown\":true}")
                .encodeToByteArray()
            resign(entries, fixture.signingKey)

            assertFailsWith<InvalidSixDraftException>(name) {
                SixDraft.read(storedArchive(entries))
            }
        }
    }

    @Test
    fun `profile change returns fresh analysis without applying signed approvals`() {
        val fixture = convertedV2Fixture()
        val changed = fixture.profile.copy(
            versionCode = fixture.profile.versionCode + 1,
        )

        val result = SixDraftReplay().replay(
            draft = SixDraft.read(fixture.bytes),
            profile = changed,
            approvedAdapterIds = setOf("desktop-selection-event-bridge"),
        )
        val profileChanged = assertIs<DraftReplayResult.ProfileChanged>(result)

        assertEquals(fixture.profile.canonicalSha256(), profileChanged.signedProfileSha256)
        assertEquals(changed.canonicalSha256(), profileChanged.currentProfileSha256)
        assertTrue(profileChanged.currentAnalysis.adapterDecisions.none { it.applied })
    }

    @Test
    fun `approval that differs from the signed plan is never applied`() {
        val fixture = convertedV2Fixture()

        assertFailsWith<DraftReplayMismatchException> {
            SixDraftReplay().replay(
                draft = SixDraft.read(fixture.bytes),
                profile = fixture.profile,
                approvedAdapterIds = setOf("storage-fallback"),
            )
        }
    }

    @Test
    fun `v2 signed plan reports expected and signer tampering are rejected`() {
        val fixture = convertedV2Fixture()
        val signedEntries = listOf(
            "conversion-plan.json",
            "compatibility-before.json",
            "compatibility-after.json",
            "expected.json",
            "draft-signature.json",
        )

        signedEntries.forEach { name ->
            val entries = readEntries(fixture.bytes).toMutableMap()
            entries[name] = entries.getValue(name).copyOf().also {
                it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
            }

            assertFailsWith<InvalidSixDraftException>(name) {
                SixDraft.read(storedArchive(entries))
            }
        }
    }

    @Test
    fun `v1 passthrough CRX replays with proof derived store identity`() {
        val fixture = legacyPassthroughCrxFixture()
        val verified = SixDraft.read(fixture.bytes)

        val result = SixDraftReplay().replay(
            draft = verified,
            profile = fixture.profile,
            approvedAdapterIds = emptySet(),
        )
        val prepared = assertIs<DraftReplayResult.Ready>(result).prepared
        val identity = assertIs<LogicalSourceIdentityResult.Stable>(
            prepared.logicalSourceIdentity,
        )

        assertEquals("store:${fixture.extensionId}", identity.value)
        assertEquals(
            LogicalIdentityEvidence.VERIFIED_STORE_EXTENSION_ID,
            identity.evidence,
        )
        assertContentEquals(verified.payload.source, prepared.passthroughCrx)
    }

    @Test
    fun `v1 transformed draft is explicitly not replayable`() {
        val legacy = legacyConvertedFixture()

        assertFailsWith<LegacyDraftNotReplayableException> {
            SixDraftReplay().replay(
                draft = SixDraft.read(legacy.bytes),
                profile = legacy.profile,
                approvedAdapterIds = emptySet(),
            )
        }
    }

    @Test
    fun `schema v2 preserves exact Ed25519 public key and fingerprint`() {
        val fixture = convertedV2Fixture()
        val verified = SixDraft.read(fixture.bytes)

        assertContentEquals(fixture.signingKey.public.encoded, verified.signerPublicKey)
        assertEquals(
            sha256(fixture.signingKey.public.encoded),
            verified.signerFingerprint,
        )
    }

    private fun convertedV2Fixture(
        sourceIdentity: String = "project:signed-identity",
        embeddedMarker: String = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    ): Fixture {
        val profile = profile()
        val source = selectionFixtureZip(embeddedMarker)
        val prepared = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromZip(
                    ExtensionInputKind.ZIP,
                    source,
                    signedDraftSourceIdentity = sourceIdentity,
                ),
                profile,
                emptySet(),
            )
        }
        val applied = prepared.compatibilityAfter.adapterDecisions
            .filter { it.applied }
            .map { DraftSelectedAdapter(it.id, it.version) }
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.createV2(
                coreVersion = "test-v2",
                adapterVersions = applied.associate { it.id to it.version },
                sourceIdentity = sourceIdentity,
            ),
            input = DraftInputMetadata(
                inputKind = ExtensionInputKind.ZIP,
                originalSourceSha256 = prepared.originalSourceSha256,
                browserProfileSha256 = prepared.browserProfileSha256,
                originalExtensionId = prepared.originalExtensionId,
                originalExtensionVersion = requireNotNull(
                    prepared.compatibilityBefore.extensionVersion,
                ),
                sourceIdentity = sourceIdentity,
            ),
            sourceKind = DraftSourceKind.ZIP,
            source = source,
            plan = DraftConversionPlan.create(prepared.compatibilityBefore, applied),
            compatibilityBefore = prepared.compatibilityBefore,
            compatibilityAfter = prepared.compatibilityAfter,
        )
        val signingKey = ed25519()
        return Fixture(
            bytes = SixDraft.createBytes(payload, signingKey),
            profile = profile,
            signingKey = signingKey,
            embeddedMarker = embeddedMarker,
        )
    }

    private fun portableV2Draft(
        source: ByteArray,
        profile: BrowserProfile,
        approvals: Set<String>,
    ): PortablePlatformFixture {
        val sourceIdentity = "project:${"d".repeat(32)}"
        val direct = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromZip(
                    kind = ExtensionInputKind.ZIP,
                    zipBytes = source,
                    signedDraftSourceIdentity = sourceIdentity,
                ),
                profile,
                approvals,
            )
        }
        val selected = direct.compatibilityAfter.adapterDecisions
            .filter { it.applied }
            .map { DraftSelectedAdapter(it.id, it.version) }
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.createV2(
                coreVersion = "platform-parity-test",
                adapterVersions = selected.associate { it.id to it.version },
                sourceIdentity = sourceIdentity,
            ),
            input = DraftInputMetadata(
                inputKind = ExtensionInputKind.ZIP,
                originalSourceSha256 = direct.originalSourceSha256,
                browserProfileSha256 = direct.browserProfileSha256,
                originalExtensionId = direct.originalExtensionId,
                originalExtensionVersion = requireNotNull(
                    direct.compatibilityBefore.extensionVersion,
                ),
                sourceIdentity = sourceIdentity,
            ),
            sourceKind = DraftSourceKind.ZIP,
            source = source,
            plan = DraftConversionPlan.create(
                direct.compatibilityBefore,
                selected,
            ),
            compatibilityBefore = direct.compatibilityBefore,
            compatibilityAfter = direct.compatibilityAfter,
        )
        return PortablePlatformFixture(
            draft = SixDraft.read(SixDraft.createBytes(payload, ed25519())),
            direct = direct,
        )
    }

    private fun popupAndInternalUrlFixtureZip(): ByteArray {
        val root = Files.createTempDirectory("six-draft-platform-popup-url")
        root.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Portable Platform Fixture",
              "version": "1",
              "action": { "default_popup": "popup.html" },
              "options_ui": { "page": "options.html", "open_in_tab": true }
            }
            """.trimIndent(),
        )
        root.resolve("popup.html").writeText(
            """
            <!doctype html><html><head><script src="popup.js"></script></head>
            <body><input type="email"><a href="chrome://extensions">add-ons</a></body></html>
            """.trimIndent(),
        )
        root.resolve("popup.js").writeText("const addOns = 'edge://extensions/';")
        root.resolve("options.html").writeText(
            "<!doctype html><html><head></head><body>options</body></html>",
        )
        return ExtensionZip.createBytes(root, includeProjectMarker = true)
    }

    private fun popupOnlyFixtureZip(): ByteArray {
        val root = Files.createTempDirectory("six-draft-platform-popup-only")
        root.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Portable Popup Only Fixture",
              "version": "1",
              "action": { "default_popup": "popup.html" },
              "options_ui": { "page": "options.html", "open_in_tab": true }
            }
            """.trimIndent(),
        )
        root.resolve("popup.html").writeText(
            "<!doctype html><html><head></head><body><input type=\"email\"></body></html>",
        )
        root.resolve("options.html").writeText(
            "<!doctype html><html><head></head><body>options</body></html>",
        )
        return ExtensionZip.createBytes(root, includeProjectMarker = true)
    }

    private fun portablePassthroughV2Fixture(): Fixture {
        val profile = profile()
        val sourceIdentity = "project:portable-passthrough"
        val sourceRoot = Files.createTempDirectory("six-draft-portable-source").also {
            it.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Portable Replay","version":"1"}""",
            )
        }
        val source = ExtensionZip.createBytes(
            sourceRoot,
            includeProjectMarker = true,
        )
        val prepared = withSecureEngine {
            it.prepare(
                PortableExtensionInput.fromZip(
                    ExtensionInputKind.ZIP,
                    source,
                    signedDraftSourceIdentity = sourceIdentity,
                ),
                profile,
                emptySet(),
            )
        }
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.createV2(
                coreVersion = "test-portable-v2",
                adapterVersions = emptyMap(),
                sourceIdentity = sourceIdentity,
            ),
            input = DraftInputMetadata(
                inputKind = ExtensionInputKind.ZIP,
                originalSourceSha256 = prepared.originalSourceSha256,
                browserProfileSha256 = prepared.browserProfileSha256,
                originalExtensionId = prepared.originalExtensionId,
                originalExtensionVersion = requireNotNull(
                    prepared.compatibilityBefore.extensionVersion,
                ),
                sourceIdentity = sourceIdentity,
            ),
            sourceKind = DraftSourceKind.ZIP,
            source = source,
            plan = DraftConversionPlan.create(
                prepared.compatibilityBefore,
                emptyList(),
            ),
            compatibilityBefore = prepared.compatibilityBefore,
            compatibilityAfter = prepared.compatibilityAfter,
        )
        val key = ed25519()
        return Fixture(
            bytes = SixDraft.createBytes(payload, key),
            profile = profile,
            signingKey = key,
            embeddedMarker = "portable-passthrough",
        )
    }

    private fun legacyPassthroughCrxFixture(): LegacyCrxFixture {
        val profile = profile()
        val source = Files.createTempDirectory("six-draft-v1-crx").also {
            it.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Legacy CRX","version":"1"}""",
            )
        }
        val packed = dev.sixbridge.core.crx.Crx3.pack(source, rsa())
        val prepared = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromCrx(
                    ExtensionInputKind.CRX3,
                    packed.bytes,
                    packed.extensionId,
                ),
                profile,
                emptySet(),
            )
        }
        val identity = "store:${packed.extensionId}:profile-${profile.majorVersion}"
        val before = prepared.compatibilityBefore.copy(
            originalExtensionId = packed.extensionId,
        )
        val after = prepared.compatibilityAfter.copy(
            originalExtensionId = packed.extensionId,
        )
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.create("legacy", emptyMap(), identity),
            input = DraftInputMetadata(
                ExtensionInputKind.CRX3,
                prepared.originalSourceSha256,
                prepared.browserProfileSha256,
                packed.extensionId,
                "1",
                identity,
            ),
            sourceKind = DraftSourceKind.CRX,
            source = packed.bytes,
            plan = DraftConversionPlan.create(
                before,
                emptyList(),
            ),
            compatibilityBefore = before,
            compatibilityAfter = after,
        )
        return LegacyCrxFixture(
            SixDraft.createBytes(payload, ed25519()),
            profile,
            packed.extensionId,
        )
    }

    private fun legacyConvertedFixture(): Fixture {
        val profile = profile()
        val source = selectionFixtureZip("cccccccccccccccccccccccccccccccc")
        val prepared = ConversionPreparationEngine().use {
            it.prepare(
                PortableExtensionInput.fromZip(ExtensionInputKind.ZIP, source),
                profile,
                emptySet(),
            )
        }
        val selected = prepared.compatibilityAfter.adapterDecisions
            .filter { it.applied }
            .map { DraftSelectedAdapter(it.id, it.version) }
        val identity = "legacy-converted"
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.create(
                "legacy",
                selected.associate { it.id to it.version },
                identity,
            ),
            input = DraftInputMetadata(
                ExtensionInputKind.ZIP,
                prepared.originalSourceSha256,
                prepared.browserProfileSha256,
                prepared.originalExtensionId,
                "1",
                identity,
            ),
            sourceKind = DraftSourceKind.ZIP,
            source = prepared.transformedSourceZip,
            plan = DraftConversionPlan.create(
                prepared.compatibilityBefore,
                selected,
            ),
            compatibilityBefore = prepared.compatibilityBefore,
            compatibilityAfter = prepared.compatibilityAfter.copy(
                convertedExtensionId = "a".repeat(32),
            ),
        )
        val key = ed25519()
        return Fixture(
            SixDraft.createBytes(payload, key),
            profile,
            key,
            "cccccccccccccccccccccccccccccccc",
        )
    }

    private fun selectionFixtureZip(marker: String): ByteArray {
        val root = Files.createTempDirectory("six-draft-v2-source")
        root.resolve(".sixbridge-project-id").writeText(
            "sixbridge-project-v1:$marker",
        )
        root.resolve("content.js").writeText(
            """
            document.addEventListener("mouseup", (event) => {
              const value = getSelection()?.toString();
              if (value) console.log(value);
            });
            """.trimIndent(),
        )
        root.resolve("manifest.json").writeText(
            """
            {
              "manifest_version": 3,
              "name": "Draft Replay",
              "version": "1",
              "content_scripts": [{
                "matches": ["https://example.com/*"],
                "js": ["content.js"]
              }]
            }
            """.trimIndent(),
        )
        return ExtensionZip.createBytes(root, includeProjectMarker = true)
    }

    private fun profile() = BrowserProfile(
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

    private fun internalUrlProfile() = profile().copy(
        supportedFeatures = profile().supportedFeatures +
            "browser.internal-url.extensions-root",
    )

    private fun readEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        return entries
    }

    private fun resign(entries: MutableMap<String, ByteArray>, keyPair: KeyPair) {
        entries.remove(DraftSignature.ENTRY_NAME)
        entries[DraftSignature.ENTRY_NAME] = SixDraftJson.encodeSignature(
            DraftSignature.sign(entries, keyPair),
        )
    }

    private fun storedArchive(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.toSortedMap().forEach { (name, bytes) ->
                    val crc = CRC32().apply { update(bytes) }
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            time = 0L
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            this.crc = crc.value
                        },
                    )
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun replaceRequired(
        bytes: ByteArray,
        oldValue: String,
        newValue: String,
    ): ByteArray {
        val text = bytes.decodeToString()
        require(oldValue in text) {
            "test mutation 대상을 찾지 못했습니다: $oldValue"
        }
        return text.replace(oldValue, newValue).encodeToByteArray()
    }

    private fun <T> withSecureEngine(
        block: (ConversionPreparationEngine) -> T,
    ): T = Jimfs.newFileSystem(
        Configuration.unix().toBuilder()
            .setAttributeViews("basic", "owner", "posix", "unix")
            .build(),
    ).use { fileSystem ->
        val root = Files.createDirectory(fileSystem.getPath("/secure-root"))
        ConversionPreparationEngine.create(
            CompatibilityAnalyzer(),
            ConversionTempTreeFactory(SecureConversionRoot.verify(root)),
        ).use(block)
    }

    private fun ed25519(): KeyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val bytes: ByteArray,
        val profile: BrowserProfile,
        val signingKey: KeyPair,
        val embeddedMarker: String,
    )

    private data class PortablePlatformFixture(
        val draft: VerifiedSixDraft,
        val direct: dev.sixbridge.core.conversion.PreparedConversion,
    )

    private data class LegacyCrxFixture(
        val bytes: ByteArray,
        val profile: BrowserProfile,
        val extensionId: String,
    )
}
