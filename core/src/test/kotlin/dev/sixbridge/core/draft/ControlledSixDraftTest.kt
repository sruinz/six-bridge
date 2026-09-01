package dev.sixbridge.core.draft

import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisSafetyPolicy
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.conversion.CompatibilityForceScope
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.conversion.ForcedCompatibilityProvenance
import dev.sixbridge.core.conversion.ConversionAuthorization
import dev.sixbridge.core.conversion.ConversionPreparationEngine
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.PortableExtensionInput
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.io.path.writeText

class ControlledSixDraftTest {
    @Test
    fun `schema v4 ZIP과 CRX는 controlled legacy이며 portable replay 가능하다`() {
        listOf(DraftSourceKind.ZIP, DraftSourceKind.CRX).forEach { kind ->
            val path = forcedPortableDraft(kind)

            val controlled = SixDraft.readControlled(
                path,
                AnalysisControl(AnalysisSafetyPolicy.mobile),
            )

            val legacy = assertIs<ControlledSixDraft.Legacy>(controlled, kind.name)
            assertEquals(kind, legacy.verified.payload.sourceKind)
            assertIs<DraftReplayResult.Ready>(
                SixDraftReplay().replay(
                    legacy.verified,
                    profile(),
                    emptySet(),
                ),
                kind.name,
            )
        }
    }

    @Test
    fun `schema v4 PREPARED_CRX는 controlled prepared이며 replay 대상이 아니다`() {
        val fixture = preparedDraft(
            sourceEntryBytes = 3 * 1024 * 1024,
            forced = true,
        )

        val controlled = SixDraft.readControlled(
            fixture.path,
            AnalysisControl(AnalysisSafetyPolicy.mobile),
        )

        val prepared = assertIs<ControlledSixDraft.Prepared>(controlled).verified
        assertEquals(4, prepared.draft.schemaVersion)
        assertEquals(2, prepared.plan.schemaVersion)
        assertEquals(
            CompatibilityForceScope.UNKNOWN_API_USAGE,
            prepared.plan.forcedCompatibility?.scope,
        )
        assertFailsWith<LegacyDraftNotReplayableException> {
            SixDraftReplay().replay(
                SixDraft.read(fixture.path),
                profile(),
                emptySet(),
            )
        }
    }

    @Test
    fun `schema v3 prepared CRX는 모바일 원본 예산을 넘어도 replay 없이 검증한다`() {
        val fixture = preparedDraft(sourceEntryBytes = 3 * 1024 * 1024)

        val controlled = SixDraft.readControlled(
            fixture.path,
            AnalysisControl(AnalysisSafetyPolicy.mobile),
        )

        val prepared = assertIs<ControlledSixDraft.Prepared>(controlled).verified
        assertEquals(3, prepared.draft.schemaVersion)
        assertEquals(
            true,
            DraftSourceKind.PREPARED_CRX.entryName in prepared.entryNames,
        )
        assertEquals(
            prepared.compatibilityAfter.convertedExtensionId,
            prepared.preparedCrx.proof.extensionId,
        )
        assertEquals(
            prepared.expected.transformedSourceSha256,
            prepared.preparedCrx.proof.zipSha256,
        )
        val copied = Files.createTempFile("six-prepared-copy-", ".crx")
        prepared.preparedCrx.copyTo(
            copied,
            AnalysisControl(AnalysisSafetyPolicy.mobile),
        )
        assertContentEquals(fixture.crx, Files.readAllBytes(copied))
    }

    @Test
    fun `legacy schema v2 source 예산 초과는 전체 draft materialization 전에 결과로 반환한다`() {
        val source = zip(2 * 1024 * 1024 + 1)
        val payload = legacyPayload(source)
        val path = Files.createTempFile("six-legacy-budget-", ".six").also {
            Files.write(it, SixDraft.createBytes(payload, ed25519()))
        }

        val controlled = SixDraft.readControlled(
            path,
            AnalysisControl(AnalysisSafetyPolicy.mobile),
        )

        val exceeded = assertIs<ControlledSixDraft.BudgetExceeded>(controlled)
        assertEquals(
            dev.sixbridge.core.compat.AnalysisBudgetMetric.SINGLE_SOURCE_BYTES,
            exceeded.exceeded.metric,
        )
    }

    @Test
    fun `schema v4 portable source 예산 초과도 전체 draft materialization 전에 결과로 반환한다`() {
        val source = zip(2 * 1024 * 1024 + 1)
        val payload = legacyPayload(source, forced = true)
        val path = Files.createTempFile("six-v4-portable-budget-", ".six").also {
            Files.write(it, SixDraft.createBytes(payload, ed25519()))
        }

        val controlled = SixDraft.readControlled(
            path,
            AnalysisControl(AnalysisSafetyPolicy.mobile),
        )

        val exceeded = assertIs<ControlledSixDraft.BudgetExceeded>(controlled)
        assertEquals(
            dev.sixbridge.core.compat.AnalysisBudgetMetric.SINGLE_SOURCE_BYTES,
            exceeded.exceeded.metric,
        )
    }

    @Test
    fun `prepared CRX proof loop는 같은 AnalysisControl 취소를 전파한다`() {
        val fixture = preparedDraft(sourceEntryBytes = 3 * 1024 * 1024)
        var checkpoints = 0
        val control = AnalysisControl(AnalysisSafetyPolicy.mobile) {
            checkpoints += 1
            if (checkpoints == 12) throw CancellationException("cancel prepared proof")
        }

        assertFailsWith<CancellationException> {
            SixDraft.readControlled(fixture.path, control)
        }
    }

    @Test
    fun `SixDraft probe는 caller AnalysisControl 취소를 hash와 inspect 전 구간에 전파한다`() {
        val fixture = preparedDraft(sourceEntryBytes = 3 * 1024 * 1024)
        var checkpoints = 0
        val control = AnalysisControl(AnalysisSafetyPolicy.mobile) {
            checkpoints += 1
            if (checkpoints == 8) throw CancellationException("cancel probe")
        }

        assertFailsWith<CancellationException> {
            SixDraft.probe(
                fixture.path,
                SixDraft.MAX_ARCHIVE_SIZE,
                control,
            )
        }
    }

    @Test
    fun `controlled read 중 draft path inode 교체는 source descriptor와 proof를 섞지 않는다`() {
        val original = preparedDraft(sourceEntryBytes = 3 * 1024 * 1024)
        val replacement = preparedDraft(sourceEntryBytes = 3 * 1024 * 1024 + 1)
        var checkpoints = 0
        val control = AnalysisControl(AnalysisSafetyPolicy.mobile) {
            checkpoints += 1
            if (checkpoints == 2) {
                Files.move(
                    replacement.path,
                    original.path,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }

        val error = assertFailsWith<IllegalArgumentException> {
            SixDraft.readControlled(original.path, control)
        }
        assertEquals(
            "SixDraft archive가 검증 중 변경되었습니다.",
            error.message,
        )
    }

    @Test
    fun `probe 중 draft path 교체는 valid 결과를 반환하지 않는다`() {
        val original = preparedDraft(sourceEntryBytes = 3 * 1024 * 1024)
        val replacement = preparedDraft(sourceEntryBytes = 3 * 1024 * 1024 + 1)
        var checkpoints = 0
        val control = AnalysisControl(AnalysisSafetyPolicy.mobile) {
            checkpoints += 1
            if (checkpoints == 2) {
                Files.move(
                    replacement.path,
                    original.path,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        }

        val result = SixDraft.probe(
            original.path,
            SixDraft.MAX_ARCHIVE_SIZE,
            control,
        )

        assertIs<SixDraftProbe.MalformedDraft>(result)
    }

    @Test
    fun `prepared CRX 검증 후 stage 전 draft path 교체는 output을 남기지 않는다`() {
        val original = preparedDraft(sourceEntryBytes = 3 * 1024 * 1024)
        val replacement = preparedDraft(sourceEntryBytes = 3 * 1024 * 1024 + 1)
        val controlled = SixDraft.readControlled(
            original.path,
            AnalysisControl(AnalysisSafetyPolicy.mobile),
        )
        val prepared = assertIs<ControlledSixDraft.Prepared>(controlled).verified
        Files.move(
            replacement.path,
            original.path,
            StandardCopyOption.REPLACE_EXISTING,
        )
        val output = Files.createTempFile("six-prepared-replaced-", ".crx")

        assertFailsWith<IllegalArgumentException> {
            prepared.preparedCrx.copyTo(
                output,
                AnalysisControl(AnalysisSafetyPolicy.mobile),
            )
        }
        assertFalse(Files.exists(output))
    }

    @Test
    fun `schema v3는 변환 후 extension version mix-and-match를 거부한다`() {
        assertFailsWith<IllegalArgumentException> {
            preparedDraft(
                sourceEntryBytes = 3 * 1024 * 1024,
                afterExtensionVersion = "2",
            )
        }
    }

    @Test
    fun `schema v3 outer metadata CRX signer mix-and-match를 모두 거부한다`() {
        val first = preparedDraft(sourceEntryBytes = 4 * 1024)
        val second = preparedDraft(sourceEntryBytes = 4 * 1024 + 1)
        val firstBytes = Files.readAllBytes(first.path)
        val firstEntries = readEntries(firstBytes)
        val secondEntries = readEntries(Files.readAllBytes(second.path))
        val mutations = linkedMapOf(
            "outer bytes" to (firstBytes + byteArrayOf(0)),
            "metadata" to storedArchive(
                firstEntries + ("input.json" to firstEntries.getValue("input.json").flipped()),
            ),
            "prepared CRX" to storedArchive(
                firstEntries + ("prepared.crx" to secondEntries.getValue("prepared.crx")),
            ),
            "signer proof" to storedArchive(
                firstEntries +
                    ("draft-signature.json" to secondEntries.getValue("draft-signature.json")),
            ),
        )

        mutations.forEach { (name, bytes) ->
            val path = Files.createTempFile("six-prepared-tamper-", ".six")
            Files.write(path, bytes)
            assertFailsWith<IllegalArgumentException>(name) {
                SixDraft.readControlled(
                    path,
                    AnalysisControl(AnalysisSafetyPolicy.mobile),
                )
            }
        }
    }

    @Test
    fun `schema v3는 재서명된 profile approvals plan identity hash CRX proof 혼합을 거부한다`() {
        val first = preparedDraft(sourceEntryBytes = 4 * 1024)
        val second = preparedDraft(sourceEntryBytes = 4 * 1024 + 1)
        val entries = readEntries(Files.readAllBytes(first.path))
        val otherHash = "9".repeat(64)
        val otherProfile = "6".repeat(64)
        val otherId = "a".repeat(32)
        val mutations = linkedMapOf<String, Map<String, ByteArray>>(
            "profile" to entries.replacingText("input.json", "7".repeat(64), otherProfile),
            "approvals" to entries.replacingText(
                "draft.json",
                "\"adapterVersions\":{}",
                "\"adapterVersions\":{\"ghost\":1}",
            ),
            "plan" to entries.replacingText(
                "conversion-plan.json",
                "8".repeat(64),
                otherHash,
            ),
            "original ID" to entries.replacingText(
                "input.json",
                "\"originalExtensionId\":null",
                "\"originalExtensionId\":\"$otherId\"",
            ),
            "converted ID" to entries.replacingText(
                "compatibility-after.json",
                first.extensionId,
                otherId,
            ),
            "before hash" to entries.replacingText(
                "compatibility-before.json",
                "8".repeat(64),
                otherHash,
            ),
            "after hash" to entries.replacingText(
                "compatibility-after.json",
                first.zipSha256,
                otherHash,
            ),
            "CRX proof" to (
                entries + ("prepared.crx" to second.crx)
                ),
        )

        mutations.forEach { (name, mixed) ->
            val path = Files.createTempFile("six-prepared-resigned-mix-", ".six")
            Files.write(path, resignedArchive(mixed, first.signingKey))
            assertFailsWith<IllegalArgumentException>(name) {
                SixDraft.readControlled(
                    path,
                    AnalysisControl(AnalysisSafetyPolicy.mobile),
                )
            }
        }
    }

    private fun preparedDraft(
        sourceEntryBytes: Int,
        afterExtensionVersion: String = "1",
        forced: Boolean = false,
    ): PreparedFixture {
        val sourceZip = zip(sourceEntryBytes)
        val crx = Crx3.packCanonicalZip(sourceZip, rsa()).bytes
        val proof = dev.sixbridge.contract.crx.Crx3Verifier.verify(crx)
        val profile = "7".repeat(64)
        val original = "8".repeat(64)
        val identity = "project:0123456789abcdef0123456789abcdef"
        val before = report(
            sourceSha256 = original,
            profileSha256 = profile,
            convertedExtensionId = null,
            forced = forced,
        )
        val after = report(
            sourceSha256 = proof.zipSha256,
            profileSha256 = profile,
            convertedExtensionId = proof.extensionId,
            extensionVersion = afterExtensionVersion,
            forced = forced,
        )
        val forcedCompatibility = forced.takeIf { it }?.let {
            ForcedCompatibilityProvenance(
                CompatibilityForceScope.UNKNOWN_API_USAGE,
                CompatibilityStatus.UNKNOWN,
                listOf("api_usage_dynamic"),
            )
        }
        val payload = SixDraftPayload.create(
            draft = if (forcedCompatibility == null) {
                DraftMetadata.createV3("test", emptyMap(), identity)
            } else {
                DraftMetadata.createV4("test", emptyMap(), identity)
            },
            input = DraftInputMetadata(
                inputKind = ExtensionInputKind.ZIP,
                originalSourceSha256 = original,
                browserProfileSha256 = profile,
                originalExtensionId = null,
                originalExtensionVersion = "1",
                sourceIdentity = identity,
            ),
            sourceKind = DraftSourceKind.PREPARED_CRX,
            source = crx,
            plan = DraftConversionPlan.create(
                before,
                emptyList(),
                forcedCompatibility,
            ),
            compatibilityBefore = before,
            compatibilityAfter = after,
        )
        val signingKey = ed25519()
        val path = Files.createTempFile("six-prepared-", ".six").also {
            Files.write(it, SixDraft.createBytes(payload, signingKey))
        }
        return PreparedFixture(
            path = path,
            crx = crx,
            signingKey = signingKey,
            extensionId = proof.extensionId,
            zipSha256 = proof.zipSha256,
        )
    }

    private fun forcedPortableDraft(kind: DraftSourceKind): java.nio.file.Path {
        val sourceRoot = Files.createTempDirectory("six-forced-portable-${kind.name.lowercase()}")
        sourceRoot.resolve("content.js").writeText("chrome[namespace].sync.get('value');")
        sourceRoot.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Forced portable","version":"1","content_scripts":[{"matches":["<all_urls>"],"js":["content.js"]}]}""",
        )
        val identity = "project:forced-${kind.name.lowercase()}-controlled"
        val source: ByteArray
        val input: PortableExtensionInput
        val inputKind: ExtensionInputKind
        if (kind == DraftSourceKind.ZIP) {
            source = ExtensionZip.createBytes(sourceRoot, includeProjectMarker = true)
            input = PortableExtensionInput.fromZip(
                ExtensionInputKind.ZIP,
                source,
                signedDraftSourceIdentity = identity,
            )
            inputKind = ExtensionInputKind.ZIP
        } else {
            val packed = Crx3.pack(sourceRoot, rsa())
            source = packed.bytes
            input = PortableExtensionInput.fromCrx(
                ExtensionInputKind.CRX3,
                source,
                packed.extensionId,
                signedDraftSourceIdentity = identity,
            )
            inputKind = ExtensionInputKind.CRX3
        }
        val prepared = ConversionPreparationEngine().use {
            it.prepare(
                input,
                profile(),
                emptySet(),
                authorization = ConversionAuthorization.forceUnknownApiUsage(),
            )
        }
        val forced = requireNotNull(prepared.forcedCompatibility)
        val before = prepared.compatibilityBefore.copy(
            originalExtensionId = prepared.originalExtensionId,
        )
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.createV4("test", emptyMap(), identity),
            input = DraftInputMetadata(
                inputKind,
                prepared.originalSourceSha256,
                prepared.browserProfileSha256,
                prepared.originalExtensionId,
                requireNotNull(prepared.compatibilityBefore.extensionVersion),
                identity,
            ),
            sourceKind = kind,
            source = source,
            plan = DraftConversionPlan.create(
                before,
                emptyList(),
                forced,
            ),
            compatibilityBefore = before,
            compatibilityAfter = prepared.compatibilityAfter,
        )
        return Files.createTempFile("six-forced-portable-", ".sixdraft").also {
            Files.write(it, SixDraft.createBytes(payload, ed25519()))
        }
    }

    private fun legacyPayload(
        source: ByteArray,
        forced: Boolean = false,
    ): SixDraftPayload {
        val profile = "9".repeat(64)
        val sourceSha = sha256(source)
        val identity = "project:fedcba9876543210fedcba9876543210"
        val report = report(
            sourceSha256 = sourceSha,
            profileSha256 = profile,
            convertedExtensionId = null,
            forced = forced,
        )
        val forcedCompatibility = forced.takeIf { it }?.let {
            ForcedCompatibilityProvenance(
                CompatibilityForceScope.UNKNOWN_API_USAGE,
                CompatibilityStatus.UNKNOWN,
                listOf("api_usage_dynamic"),
            )
        }
        return SixDraftPayload.create(
            draft = if (forcedCompatibility == null) {
                DraftMetadata.createV2("test", emptyMap(), identity)
            } else {
                DraftMetadata.createV4("test", emptyMap(), identity)
            },
            input = DraftInputMetadata(
                inputKind = ExtensionInputKind.ZIP,
                originalSourceSha256 = sourceSha,
                browserProfileSha256 = profile,
                originalExtensionId = null,
                originalExtensionVersion = "1",
                sourceIdentity = identity,
            ),
            sourceKind = DraftSourceKind.ZIP,
            source = source,
            plan = DraftConversionPlan.create(
                report,
                emptyList(),
                forcedCompatibility,
            ),
            compatibilityBefore = report,
            compatibilityAfter = report,
        )
    }

    private fun report(
        sourceSha256: String,
        profileSha256: String,
        convertedExtensionId: String?,
        extensionVersion: String = "1",
        forced: Boolean = false,
    ) = CompatibilityReport(
        status = if (forced) CompatibilityStatus.UNKNOWN else CompatibilityStatus.COMPATIBLE,
        manifestVersion = 3,
        extensionName = "Prepared",
        extensionVersion = extensionVersion,
        declaredPermissions = emptySet(),
        usedApis = emptySet(),
        unsupportedApis = emptySet(),
        issues = if (forced) {
            listOf(CompatibilityIssue("api_usage_dynamic", "dynamic", true))
        } else {
            emptyList()
        },
        conversionState = ConversionState.PASSTHROUGH,
        originalExtensionId = null,
        convertedExtensionId = convertedExtensionId,
        sourceSha256 = sourceSha256,
        browserProfileSha256 = profileSha256,
    )

    private fun profile() = dev.sixbridge.core.profile.BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = dev.sixbridge.core.profile.BrowserProfile.BASELINE_VERSION,
        versionCode = 1_300_261_502,
        majorVersion = dev.sixbridge.core.profile.BrowserProfile.SUPPORTED_MAJOR_VERSION,
        supportedApis = dev.sixbridge.core.profile.BrowserProfile.POC_SUPPORTED_APIS,
        wrapperContract = dev.sixbridge.core.profile.WrapperContract.complete(),
        sourceApkSha256 = dev.sixbridge.core.profile.BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = dev.sixbridge.core.profile.BrowserProfile.SAMSUNG_SIGNER_SHA256,
        supportedFeatures = setOf("extension.options-page.user-accessible"),
    )

    private fun zip(sourceEntryBytes: Int): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { output ->
                val manifest = """{"manifest_version":3,"name":"Prepared","version":"1"}"""
                    .encodeToByteArray()
                stored(output, "manifest.json", manifest)
                stored(output, "large.js", ByteArray(sourceEntryBytes))
            }
        }.toByteArray()

    private fun ByteArray.flipped(): ByteArray = copyOf().also {
        it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
    }

    private fun stored(output: ZipOutputStream, name: String, bytes: ByteArray) {
        output.putNextEntry(
            ZipEntry(name).apply {
                time = 0L
                method = ZipEntry.STORED
                size = bytes.size.toLong()
                compressedSize = size
                crc = CRC32().apply { update(bytes) }.value
            },
        )
        output.write(bytes)
        output.closeEntry()
    }

    private fun readEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> =
        linkedMapOf<String, ByteArray>().also { entries ->
            ZipInputStream(bytes.inputStream()).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    entries[entry.name] = input.readBytes()
                    input.closeEntry()
                }
            }
        }

    private fun storedArchive(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { output ->
                entries.forEach { (name, contents) ->
                    stored(output, name, contents)
                }
            }
        }.toByteArray()

    private fun Map<String, ByteArray>.replacingText(
        entryName: String,
        old: String,
        new: String,
    ): Map<String, ByteArray> {
        val original = getValue(entryName).decodeToString()
        require(old in original) { "$entryName fixture에 교체 대상이 없습니다." }
        return this + (entryName to original.replace(old, new).encodeToByteArray())
    }

    private fun resignedArchive(
        entries: Map<String, ByteArray>,
        signingKey: KeyPair,
    ): ByteArray {
        val unsigned = LinkedHashMap(entries).apply {
            remove(DraftSignature.ENTRY_NAME)
        }
        return storedArchive(
            unsigned +
                (
                    DraftSignature.ENTRY_NAME to SixDraftJson.encodeSignature(
                        DraftSignature.sign(unsigned, signingKey),
                    )
                    ),
        )
    }

    private fun rsa() = KeyPairGenerator.getInstance("RSA")
        .apply { initialize(2048) }
        .generateKeyPair()

    private fun ed25519() =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class PreparedFixture(
        val path: java.nio.file.Path,
        val crx: ByteArray,
        val signingKey: KeyPair,
        val extensionId: String,
        val zipSha256: String,
    )
}
