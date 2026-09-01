package dev.sixbridge.core.draft

import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.AdapterRisk
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.conversion.CompatibilityForceScope
import dev.sixbridge.core.conversion.ForcedCompatibilityProvenance
import dev.sixbridge.core.bundle.toContractV4
import dev.sixbridge.core.input.ExtensionInputKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.io.path.writeText

class SixDraftTest {
    @Test
    fun `forced draft v4는 signed plan provenance를 strict하게 보존한다`() {
        val payload = forcedV4Payload()

        val verified = SixDraft.read(SixDraft.createBytes(payload, keyPair()))

        assertEquals(4, verified.payload.draft.schemaVersion)
        assertEquals(2, verified.payload.plan.schemaVersion)
        assertEquals(
            ForcedCompatibilityProvenance(
                CompatibilityForceScope.UNKNOWN_API_USAGE,
                CompatibilityStatus.UNKNOWN,
                listOf("api_usage_dynamic"),
            ),
            verified.payload.plan.forcedCompatibility,
        )
        val planJson = verified.payload.conversionPlanJson.decodeToString()
        assertTrue("\"forcedCompatibility\":" in planJson)

        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (requireNotNull(verified.payload.plan.forcedCompatibility).issueCodes as MutableList<String>) +=
                "mutated"
        }
    }

    @Test
    fun `forced provenance 필드 변조는 기존 signature로 검증되지 않는다`() {
        val bytes = SixDraft.createBytes(forcedV4Payload(), keyPair())
        val mutations = listOf(
            "\"scope\":\"UNKNOWN_API_USAGE\"" to
                "\"scope\":\"OTHER_SCOPE\"",
            "\"originalStatus\":\"UNKNOWN\"" to
                "\"originalStatus\":\"BLOCKED\"",
            "\"issueCodes\":[\"api_usage_dynamic\"]" to
                "\"issueCodes\":[\"browser_profile_unknown\"]",
        )

        mutations.forEach { (old, new) ->
            val entries = readEntries(bytes).toMutableMap()
            val original = entries.getValue("conversion-plan.json").decodeToString()
            require(old in original)
            entries["conversion-plan.json"] = original.replace(old, new).encodeToByteArray()

            assertFailsWith<InvalidSixDraftException>(old) {
                SixDraft.read(storedArchive(entries))
            }
        }
    }

    @Test
    fun `plan schema v2는 forcedCompatibility key를 null을 포함해 필수로 요구한다`() {
        val payload = forcedV4Payload()
        val encoded = payload.conversionPlanJson.decodeToString()
        val missing = encoded.replace(
            Regex("\\\"forcedCompatibility\\\":\\{[^}]+},"),
            "",
        ).encodeToByteArray()
        val nullValue = encoded.replace(
            Regex("\\\"forcedCompatibility\\\":\\{[^}]+}"),
            "\"forcedCompatibility\":null",
        ).encodeToByteArray()

        assertFailsWith<IllegalArgumentException> {
            SixDraftJson.decodePlan(missing)
        }
        val decodedNull = SixDraftJson.decodePlan(nullValue)
        assertEquals(2, decodedNull.schemaVersion)
        assertEquals(null, decodedNull.forcedCompatibility)
    }

    @Test
    fun `선택적 승인만 있는 빈 plan은 원본 유지 draft로 검증된다`() {
        val source = "optional-original-source".encodeToByteArray()
        val sourceSha256 = sha256(source)
        val profileSha256 = "b".repeat(64)
        val decision = AdapterDecision(
            id = "popup-native-autofill-guard",
            version = 1,
            risk = AdapterRisk.BEHAVIOR_CHANGE,
            disposition = AdapterDisposition.OPTIONAL_APPROVAL,
            applied = false,
            evidence = emptyList(),
            changes = emptyList(),
            remainingLimitations = listOf("원본 유지"),
        )
        val before = CompatibilityReport(
            status = CompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = "Optional",
            extensionVersion = "1",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            conversionState = ConversionState.REQUIRES_APPROVAL,
            adapterDecisions = listOf(decision),
            sourceSha256 = sourceSha256,
            browserProfileSha256 = profileSha256,
        )
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.createV2(
                coreVersion = "test",
                adapterVersions = emptyMap(),
                sourceIdentity = "optional-original-draft",
            ),
            input = DraftInputMetadata(
                inputKind = ExtensionInputKind.SOURCE_DIRECTORY,
                originalSourceSha256 = sourceSha256,
                browserProfileSha256 = profileSha256,
                originalExtensionId = null,
                originalExtensionVersion = "1",
                sourceIdentity = "optional-original-draft",
            ),
            sourceKind = DraftSourceKind.ZIP,
            source = source,
            plan = DraftConversionPlan.create(before, emptyList()),
            compatibilityBefore = before,
            compatibilityAfter = before.copy(
                conversionState = ConversionState.PASSTHROUGH,
            ),
        )

        val verified = SixDraft.read(SixDraft.createBytes(payload, keyPair()))

        assertTrue(verified.payload.plan.selectedAdapters.isEmpty())
        assertEquals(
            AdapterDisposition.OPTIONAL_APPROVAL,
            verified.payload.compatibilityBefore.adapterDecisions.single().disposition,
        )
        assertEquals(
            ConversionState.PASSTHROUGH,
            verified.payload.compatibilityAfter.conversionState,
        )
    }

    @Test
    fun `optional approval disposition을 canonical JSON으로 왕복한다`() {
        val decision = AdapterDecision(
            id = "popup-native-autofill-guard",
            version = 1,
            risk = AdapterRisk.BEHAVIOR_CHANGE,
            disposition = AdapterDisposition.OPTIONAL_APPROVAL,
            applied = false,
            evidence = emptyList(),
            changes = emptyList(),
            remainingLimitations = listOf("원본 유지 시 팝업 포커스 충돌 위험이 남습니다."),
        )
        val report = CompatibilityReport(
            status = CompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = "Popup",
            extensionVersion = "1",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            conversionState = ConversionState.REQUIRES_APPROVAL,
            adapterDecisions = listOf(decision),
            sourceSha256 = "a".repeat(64),
            browserProfileSha256 = "b".repeat(64),
        )

        val encoded = SixDraftJson.encodeCompatibility(report)

        assertTrue("\"optional-approval\"" in encoded.decodeToString())
        assertEquals(
            AdapterDisposition.OPTIONAL_APPROVAL,
            SixDraftJson.decodeCompatibility("compatibility.json", encoded)
                .adapterDecisions.single().disposition,
        )
    }

    @Test
    fun `compatibility schema 4는 API 검증 상태를 보존한다`() {
        val report = CompatibilityReport(
            status = CompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = "Capability",
            extensionVersion = "1",
            declaredPermissions = emptySet(),
            usedApis = setOf("tabs", "userScripts", "downloads"),
            unsupportedApis = setOf("downloads.download"),
            issues = emptyList(),
            sourceSha256 = "a".repeat(64),
            browserProfileSha256 = "b".repeat(64),
            verifiedApis = setOf("tabs.query"),
            unverifiedApis = setOf("userScripts.register"),
            unavailableApis = setOf("downloads.download"),
        )

        val decoded = SixDraftJson.decodeCompatibility(
            "compatibility.json",
            SixDraftJson.encodeCompatibility(report),
        )

        assertEquals(report, decoded)
    }

    @Test
    fun `same key payload and plan produce identical draft bytes`() {
        val keyPair = keyPair()
        val payload = payload(DraftSourceKind.ZIP)

        val first = SixDraft.createBytes(payload, keyPair)
        val second = SixDraft.createBytes(payload, keyPair)

        assertContentEquals(first, second)
    }

    @Test
    fun `source zip and passthrough crx are mutually exclusive and secrets are absent`() {
        val keyPair = keyPair()
        val sourceDraft = SixDraft.read(
            SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair),
        )
        val crxDraft = SixDraft.read(
            SixDraft.createBytes(payload(DraftSourceKind.CRX), keyPair),
        )

        assertTrue("source.zip" in sourceDraft.entryNames)
        assertFalse("source.crx" in sourceDraft.entryNames)
        assertTrue("source.crx" in crxDraft.entryNames)
        assertFalse("source.zip" in crxDraft.entryNames)
        listOf(sourceDraft, crxDraft).forEach { draft ->
            assertEquals(8, draft.entryNames.size)
            assertTrue(
                draft.entryNames.none {
                    it == "wrapper.apk" ||
                        it.endsWith(".pk8") ||
                        it.contains("private", ignoreCase = true)
                },
            )
        }
    }

    @Test
    fun `passthrough CRX expected source hash binds its verified ZIP payload`() {
        val verified = SixDraft.read(
            SixDraft.createBytes(validCrxPayload(), keyPair()),
        )
        val parsed = Crx3.parse(verified.payload.source)
        val expected = Json.parseToJsonElement(
            verified.payload.expectedJson.decodeToString(),
        ) as JsonObject

        assertEquals(
            sha256(parsed.zipBytes),
            (expected.getValue("sourceSha256") as JsonPrimitive).content,
        )
        assertFalse(
            sha256(verified.payload.source) ==
                (expected.getValue("sourceSha256") as JsonPrimitive).content,
        )
    }

    @Test
    fun `expected plan hash binds semantic plan proof rather than plan document bytes`() {
        val draftPayload = payload(DraftSourceKind.ZIP)

        val verified = SixDraft.read(
            SixDraft.createBytes(draftPayload, keyPair()),
        )
        val plan = verified.payload.conversionPlanJson
        val expected = Json.parseToJsonElement(
            verified.payload.expectedJson.decodeToString(),
        ) as JsonObject
        val planProof = (expected.getValue("planSha256") as JsonPrimitive).content

        assertContentEquals(plan, verified.payload.conversionPlanJson)
        assertFalse(sha256(plan) == planProof)
    }

    @Test
    fun `reader returns defensive bytes and immutable entry names`() {
        val verified = SixDraft.read(
            SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair()),
        )
        val first = verified.payload.source
        val original = first[0]
        first[0] = (first[0].toInt() xor 0xff).toByte()

        assertEquals(original, verified.payload.source[0])
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (verified.entryNames as MutableSet<String>).clear()
        }
    }

    @Test
    fun `reader fingerprint is exact SHA-256 of X509 public key`() {
        val keyPair = keyPair()
        val verified = SixDraft.read(
            SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair),
        )

        assertEquals(sha256(keyPair.public.encoded), verified.signerFingerprint)
        assertContentEquals(keyPair.public.encoded, verified.signerPublicKey)
    }

    @Test
    fun `schema v2 writer separates original and transformed expected hashes`() {
        val original = "original-v2-source".encodeToByteArray()
        val transformed = "transformed-v2-source".encodeToByteArray()
        val profile = "5".repeat(64)
        val decision = AdapterDecision(
            id = "desktop-selection-event-bridge",
            version = 1,
            risk = AdapterRisk.SAFE,
            disposition = AdapterDisposition.AUTOMATIC,
            applied = false,
            evidence = emptyList(),
            changes = listOf("selection event bridge"),
            remainingLimitations = emptyList(),
        )
        val before = CompatibilityReport(
            status = CompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = "Draft v2",
            extensionVersion = "1",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            conversionState = ConversionState.AUTOMATIC,
            adapterDecisions = listOf(decision),
            sourceSha256 = sha256(original),
            browserProfileSha256 = profile,
        )
        val after = before.copy(
            status = CompatibilityStatus.COMPATIBLE,
            conversionState = ConversionState.CONVERTED,
            adapterDecisions = listOf(decision.copy(applied = true)),
            sourceSha256 = sha256(transformed),
        )
        val identity = "project:0123456789abcdef0123456789abcdef"
        val payload = SixDraftPayload.create(
            draft = DraftMetadata.createV2(
                coreVersion = "test",
                adapterVersions = mapOf(decision.id to decision.version),
                sourceIdentity = identity,
            ),
            input = DraftInputMetadata(
                inputKind = ExtensionInputKind.ZIP,
                originalSourceSha256 = sha256(original),
                browserProfileSha256 = profile,
                originalExtensionId = null,
                originalExtensionVersion = "1",
                sourceIdentity = identity,
            ),
            sourceKind = DraftSourceKind.ZIP,
            source = original,
            plan = DraftConversionPlan.create(
                before,
                listOf(DraftSelectedAdapter(decision.id, decision.version)),
            ),
            compatibilityBefore = before,
            compatibilityAfter = after,
        )

        val verified = SixDraft.read(SixDraft.createBytes(payload, keyPair()))
        val expected = verified.payload.expected
        val expectedKeys = (
            Json.parseToJsonElement(
                verified.payload.expectedJson.decodeToString(),
            ) as JsonObject
            ).keys

        assertEquals(2, verified.payload.draft.schemaVersion)
        assertContentEquals(original, verified.payload.source)
        assertEquals(sha256(original), expected.originalSourceSha256)
        assertEquals(sha256(transformed), expected.transformedSourceSha256)
        assertEquals(verified.payload.plan.planSha256, expected.planSha256)
        assertEquals(
            setOf(
                "originalSourceSha256",
                "planSha256",
                "reportSha256",
                "schemaVersion",
                "transformedSourceSha256",
            ),
            expectedKeys,
        )
    }

    @Test
    fun `every signed entry rejects a one byte change`() {
        val bytes = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())
        val signedNames = setOf(
            "compatibility-after.json",
            "compatibility-before.json",
            "conversion-plan.json",
            "draft.json",
            "expected.json",
            "input.json",
            "source.zip",
        )

        signedNames.forEach { name ->
            val entries = readEntries(bytes).toMutableMap()
            entries[name] = entries.getValue(name).copyOf().also {
                val offset = if (it.isEmpty()) 0 else it.lastIndex
                if (it.isEmpty()) {
                    entries[name] = byteArrayOf(1)
                } else {
                    it[offset] = (it[offset].toInt() xor 1).toByte()
                }
            }
            assertFailsWith<InvalidSixDraftException>(name) {
                SixDraft.read(storedArchive(entries))
            }
        }
    }

    @Test
    fun `signature public key key id and signature tampering are rejected`() {
        val bytes = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())

        val otherPublicKey = Base64.getEncoder().encodeToString(keyPair().public.encoded)
        assertSignatureMutationRejected(bytes, "publicKey", otherPublicKey)

        val signature = signatureObject(bytes)
        val keyId = (signature.getValue("keyId") as JsonPrimitive).content
        val changedId = (if (keyId.first() == '0') "1" else "0") + keyId.drop(1)
        assertSignatureMutationRejected(bytes, "keyId", changedId)

        val encodedSignature = (signature.getValue("signature") as JsonPrimitive).content
        val changedSignature = Base64.getDecoder().decode(encodedSignature).also {
            it[0] = (it[0].toInt() xor 1).toByte()
        }
        assertSignatureMutationRejected(
            bytes,
            "signature",
            Base64.getEncoder().encodeToString(changedSignature),
        )
    }

    @Test
    fun `non Ed25519 algorithm and unsupported draft schema are rejected`() {
        val bytes = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())
        assertSignatureMutationRejected(bytes, "algorithm", "SHA256withRSA")

        val entries = readEntries(bytes).toMutableMap()
        entries["draft.json"] = entries.getValue("draft.json")
            .decodeToString()
            .replace("\"schemaVersion\":1", "\"schemaVersion\":3")
            .encodeToByteArray()
        entries.remove("draft-signature.json")
        entries["draft-signature.json"] = SixDraftJson.encodeSignature(
            DraftSignature.sign(entries, keyPair()),
        )
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(storedArchive(entries))
        }
    }

    @Test
    fun `unsupported duplicate traversal and symlink entries are rejected`() {
        val bytes = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())
        val entries = readEntries(bytes)

        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(
                storedArchive(entries + ("wrapper.apk" to byteArrayOf(1))),
            )
        }

        val duplicate = replaceAllAscii(bytes, "input.json", "draft.json")
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(duplicate)
        }

        val traversal = replaceAllAscii(bytes, "input.json", "../ut.json")
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(traversal)
        }

        val symlink = patchCentralEntry(bytes, "draft.json") { archive, offset ->
            archive[offset + 5] = 3
            putLe32(archive, offset + 38, 0xa000L shl 16)
        }
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(symlink)
        }
    }

    @Test
    fun `metadata source total size and compression ratio limits fail closed`() {
        val bytes = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())

        val metadataOversize = patchEntry(bytes, "draft.json") { archive, local, central ->
            putLe32(archive, local + 22, 1_048_577)
            putLe32(archive, central + 24, 1_048_577)
        }
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(metadataOversize)
        }

        val sourceOversize = patchEntry(bytes, "source.zip") { archive, local, central ->
            putLe32(archive, local + 22, 512L * 1024 * 1024 + 1)
            putLe32(archive, central + 24, 512L * 1024 * 1024 + 1)
        }
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(sourceOversize)
        }

        val highRatioEntries = readEntries(bytes).toMutableMap().apply {
            this["source.zip"] = ByteArray(64 * 1024)
        }
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(deflatedArchive(highRatioEntries))
        }
    }

    @Test
    fun `data descriptors and unknown sizes fail closed`() {
        val bytes = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())
        val descriptor = patchEntry(bytes, "draft.json") { archive, local, central ->
            putLe16(archive, local + 6, le16(archive, local + 6) or 0x0008)
            putLe16(archive, central + 8, le16(archive, central + 8) or 0x0008)
        }
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(descriptor)
        }

        val unknown = patchEntry(bytes, "source.zip") { archive, local, central ->
            putLe32(archive, local + 18, 0xffff_ffffL)
            putLe32(archive, local + 22, 0xffff_ffffL)
            putLe32(archive, central + 20, 0xffff_ffffL)
            putLe32(archive, central + 24, 0xffff_ffffL)
        }
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(unknown)
        }
    }

    @Test
    fun `reserved strong encryption and DOS directory flags fail closed`() {
        val bytes = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())
        listOf(0x0040, 0x2000, 0x4000).forEach { unsupported ->
            val patched = patchEntry(bytes, "draft.json") { archive, local, central ->
                putLe16(
                    archive,
                    local + 6,
                    le16(archive, local + 6) or unsupported,
                )
                putLe16(
                    archive,
                    central + 8,
                    le16(archive, central + 8) or unsupported,
                )
            }
            assertFailsWith<InvalidSixDraftException>("flag=$unsupported") {
                SixDraft.read(patched)
            }
        }

        val dosDirectory = patchCentralEntry(bytes, "draft.json") { archive, offset ->
            archive[offset + 5] = 0
            putLe32(archive, offset + 38, 0x10)
        }
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(dosDirectory)
        }
    }

    @Test
    fun `ZIP64 extra comment and trailing bytes fail closed`() {
        val bytes = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())
        val entries = readEntries(bytes)
        val ordinaryExtra = storedArchive(
            entries,
            "draft.json" to byteArrayOf(
                0xfe.toByte(), 0xca.toByte(), 4, 0, 0, 0, 0, 0,
            ),
        )
        val zip64Extra = patchEntry(
            ordinaryExtra,
            "draft.json",
        ) { archive, local, central ->
            putLe16(archive, local + 30 + le16(archive, local + 26), 1)
            putLe16(archive, central + 46 + le16(archive, central + 28), 1)
        }
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(zip64Extra)
        }
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(bytes + byteArrayOf(1))
        }
        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(storedArchive(entries, comment = "comment"))
        }
    }

    @Test
    fun `path reader rejects a symlink without changing signing keys`() {
        val trusted = Files.createTempDirectory("six-draft-path-trusted")
        val store = DraftSigningKeyStore(trusted, trusted.resolve("keys"))
        val keyPair = store.getOrCreate()
        val actual = trusted.resolve("draft.sixdraft")
        Files.write(actual, SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair))
        val link = trusted.resolve("linked.sixdraft")
        Files.createSymbolicLink(link, actual)
        val publicBefore = keyPair.public.encoded.copyOf()

        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(link)
        }
        assertContentEquals(publicBefore, store.getOrCreate().public.encoded)
    }

    @Test
    fun `bounded path probe returns metadata without materializing source payload`() {
        val bytes = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())
        val path = Files.createTempFile("six-draft-probe", ".sixdraft")
        Files.write(path, bytes)

        val result = SixDraft.probe(path, bytes.size.toLong())
        val valid = result as SixDraftProbe.Valid

        assertEquals(1, valid.schemaVersion)
        assertEquals(null, valid.originalExtensionId)
    }

    @Test
    fun `bounded path probe rejects an archive above the caller limit`() {
        val bytes = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())
        val path = Files.createTempFile("six-draft-probe-limit", ".sixdraft")
        Files.write(path, bytes)

        assertEquals(
            SixDraftProbe.MalformedDraft,
            SixDraft.probe(path, bytes.size.toLong() - 1),
        )
    }

    @Test
    fun `draft source signature and one metadata marker classify malformed draft`() {
        val valid = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())
        val entries = readEntries(valid).filterKeys {
            it in setOf(
                DraftSourceKind.ZIP.entryName,
                DraftSignature.ENTRY_NAME,
                "draft.json",
            )
        }
        val malformed = storedArchive(entries)
        val path = Files.createTempFile("six-draft-probe-markers", ".sixdraft")
        Files.write(path, malformed)

        assertEquals(
            SixDraftProbe.MalformedDraft,
            SixDraft.probe(path, malformed.size.toLong()),
        )
    }

    @Test
    fun `ZIP32 maximum entry inventory stays within a fixed allocation ceiling`() {
        val path = rawEmptyZip(entryCount = 65_535, nameLength = 16)
        val allocation = threadAllocation()
        val before = allocation.currentThreadAllocatedBytes

        val result = SixDraft.probe(path, 128L * 1024 * 1024)

        val allocated = allocation.currentThreadAllocatedBytes - before
        assertEquals(SixDraftProbe.NotDraft, result)
        assertTrue(allocated < 8L * 1024 * 1024, "allocated=$allocated")
    }

    @Test
    fun `long name ZIP inventory stays within a fixed allocation ceiling`() {
        val path = rawEmptyZip(entryCount = 10_000, nameLength = 400)
        val allocation = threadAllocation()
        val before = allocation.currentThreadAllocatedBytes

        val result = SixDraft.probe(path, 128L * 1024 * 1024)

        val allocated = allocation.currentThreadAllocatedBytes - before
        assertEquals(SixDraftProbe.NotDraft, result)
        assertTrue(allocated < 8L * 1024 * 1024, "allocated=$allocated")
    }

    @Test
    fun `bounded path probe rejects same size mutation before returning`() {
        val bytes = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())
        val path = Files.createTempFile("six-draft-probe-mutation", ".sixdraft")
        Files.write(path, bytes)

        val result = StrictDraftPathProbe.probe(
            path = path,
            maximumBytes = bytes.size.toLong(),
            afterValidation = {
                val mutated = bytes.copyOf()
                mutated[mutated.lastIndex] =
                    (mutated.last().toInt() xor 1).toByte()
                Files.write(path, mutated)
            },
        )

        assertEquals(SixDraftProbe.MalformedDraft, result)
    }

    @Test
    fun `near 128 MiB valid draft probe stays below a fixed heap allocation ceiling`() {
        val path = largeValidDraftPath(127L * 1024 * 1024)
        val allocation = threadAllocation()
        val before = allocation.currentThreadAllocatedBytes

        val result = SixDraft.probe(path, 128L * 1024 * 1024)

        val allocated = allocation.currentThreadAllocatedBytes - before
        assertTrue(result is SixDraftProbe.Valid)
        assertEquals(2, result.schemaVersion)
        assertTrue(allocated < 32L * 1024 * 1024, "allocated=$allocated")
    }

    private fun threadAllocation(): com.sun.management.ThreadMXBean =
        (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean)
            .also {
                assertTrue(it.isThreadAllocatedMemorySupported)
                it.isThreadAllocatedMemoryEnabled = true
            }

    private fun rawEmptyZip(
        entryCount: Int,
        nameLength: Int,
    ): java.nio.file.Path {
        require(entryCount in 1..65_535)
        require(nameLength >= 5)
        val path = Files.createTempFile("six-draft-entry-inventory", ".zip")
        val name = ByteArray(nameLength) { 'a'.code.toByte() }
        val localSize = 30L + nameLength
        val centralOffset = entryCount * localSize
        val centralSize = entryCount * (46L + nameLength)
        BufferedOutputStream(Files.newOutputStream(path), 64 * 1024).use { output ->
            repeat(entryCount) { index ->
                writeNameIndex(name, index)
                output.writeLe32(0x04034b50)
                output.writeLe16(20)
                output.writeLe16(0x0800)
                output.writeLe16(0)
                output.writeLe16(0)
                output.writeLe16(0)
                output.writeLe32(0)
                output.writeLe32(0)
                output.writeLe32(0)
                output.writeLe16(name.size)
                output.writeLe16(0)
                output.write(name)
            }
            repeat(entryCount) { index ->
                writeNameIndex(name, index)
                output.writeLe32(0x02014b50)
                output.writeLe16(20)
                output.writeLe16(20)
                output.writeLe16(0x0800)
                output.writeLe16(0)
                output.writeLe16(0)
                output.writeLe16(0)
                output.writeLe32(0)
                output.writeLe32(0)
                output.writeLe32(0)
                output.writeLe16(name.size)
                output.writeLe16(0)
                output.writeLe16(0)
                output.writeLe16(0)
                output.writeLe16(0)
                output.writeLe32(0)
                output.writeLe32(index * localSize)
                output.write(name)
            }
            output.writeLe32(0x06054b50)
            output.writeLe16(0)
            output.writeLe16(0)
            output.writeLe16(entryCount)
            output.writeLe16(entryCount)
            output.writeLe32(centralSize)
            output.writeLe32(centralOffset)
            output.writeLe16(0)
        }
        return path
    }

    private fun writeNameIndex(name: ByteArray, index: Int) {
        var value = index
        for (position in 4 downTo 0) {
            name[position] = ('0'.code + value % 10).toByte()
            value /= 10
        }
    }

    private fun OutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun OutputStream.writeLe32(value: Long) {
        writeLe16((value and 0xffff).toInt())
        writeLe16(((value ushr 16) and 0xffff).toInt())
    }

    private fun OutputStream.writeLe32(value: Int) {
        writeLe32(value.toLong())
    }

    private fun assertSignatureMutationRejected(
        bytes: ByteArray,
        field: String,
        replacement: String,
    ) {
        val entries = readEntries(bytes).toMutableMap()
        val signature = signatureObject(bytes)
        entries["draft-signature.json"] = Json.encodeToString(
            JsonObject.serializer(),
            JsonObject(signature + (field to JsonPrimitive(replacement))),
        ).encodeToByteArray()
        assertFailsWith<InvalidSixDraftException>(field) {
            SixDraft.read(storedArchive(entries))
        }
    }

    private fun signatureObject(bytes: ByteArray): JsonObject =
        Json.parseToJsonElement(
            readEntries(bytes).getValue("draft-signature.json").decodeToString(),
        ) as JsonObject

    private fun payload(kind: DraftSourceKind): SixDraftPayload {
        if (kind == DraftSourceKind.CRX) return validCrxPayload()
        val source = "source-body-${kind.name}".encodeToByteArray()
        val originalSource = "1".repeat(64)
        val profile = "2".repeat(64)
        val decision = AdapterDecision(
            id = "storage-fallback",
            version = 1,
            risk = AdapterRisk.SAFE,
            disposition = AdapterDisposition.AUTOMATIC,
            applied = false,
            evidence = emptyList(),
            changes = listOf("storage.sync -> storage.local"),
            remainingLimitations = emptyList(),
        )
        val before = CompatibilityReport(
            status = CompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = "Draft ZIP",
            extensionVersion = "1",
            declaredPermissions = setOf("storage"),
            usedApis = setOf("storage.sync"),
            unsupportedApis = setOf("storage.sync"),
            issues = emptyList(),
            conversionState = ConversionState.AUTOMATIC,
            adapterDecisions = listOf(decision),
            sourceSha256 = originalSource,
            browserProfileSha256 = profile,
        )
        val after = before.copy(
            status = CompatibilityStatus.COMPATIBLE,
            usedApis = setOf("storage.local"),
            unsupportedApis = emptySet(),
            verifiedApis = setOf("storage.local"),
            unavailableApis = emptySet(),
            conversionState = ConversionState.CONVERTED,
            adapterDecisions = listOf(decision.copy(applied = true)),
            convertedExtensionId = "a".repeat(32),
            sourceSha256 = sha256(source),
        )
        val identity = "six-draft-test"
        val draft = DraftMetadata.create(
            coreVersion = "test",
            adapterVersions = mapOf("storage-fallback" to 1),
            sourceIdentity = identity,
        )
        val input = DraftInputMetadata(
            inputKind = ExtensionInputKind.SOURCE_DIRECTORY,
            originalSourceSha256 = originalSource,
            browserProfileSha256 = profile,
            originalExtensionId = null,
            originalExtensionVersion = "1",
            sourceIdentity = identity,
        )
        return SixDraftPayload.create(
            draft = draft,
            input = input,
            sourceKind = kind,
            source = source,
            plan = DraftConversionPlan.create(
                before,
                listOf(DraftSelectedAdapter("storage-fallback", 1)),
            ),
            compatibilityBefore = before,
            compatibilityAfter = after,
        )
    }

    private fun forcedV4Payload(): SixDraftPayload {
        val source = "forced-v4-portable-source".encodeToByteArray()
        val sourceSha256 = sha256(source)
        val profileSha256 = "7".repeat(64)
        val identity = "project:forced-v4-draft"
        val before = CompatibilityReport(
            status = CompatibilityStatus.UNKNOWN,
            manifestVersion = 3,
            extensionName = "Forced v4",
            extensionVersion = "1",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = listOf(
                CompatibilityIssue("api_usage_dynamic", "dynamic", true),
            ),
            conversionState = ConversionState.PASSTHROUGH,
            sourceSha256 = sourceSha256,
            browserProfileSha256 = profileSha256,
        )
        val forced = ForcedCompatibilityProvenance(
            CompatibilityForceScope.UNKNOWN_API_USAGE,
            CompatibilityStatus.UNKNOWN,
            listOf("api_usage_dynamic"),
        )
        return SixDraftPayload.create(
            draft = DraftMetadata.createV4("test", emptyMap(), identity),
            input = DraftInputMetadata(
                ExtensionInputKind.ZIP,
                sourceSha256,
                profileSha256,
                null,
                "1",
                identity,
            ),
            sourceKind = DraftSourceKind.ZIP,
            source = source,
            plan = DraftConversionPlan.create(before, emptyList(), forced),
            compatibilityBefore = before,
            compatibilityAfter = before,
        )
    }

    private fun validCrxPayload(): SixDraftPayload {
        val extension = Files.createTempDirectory("six-draft-crx-source").also {
            it.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Draft CRX","version":"1"}""",
            )
        }
        val packed = Crx3.pack(
            extension,
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
                .generateKeyPair(),
        )
        val profile = "4".repeat(64)
        val payloadHash = sha256(packed.zipBytes)
        val before = CompatibilityReport(
            status = CompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = "Draft CRX",
            extensionVersion = "1",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            conversionState = ConversionState.PASSTHROUGH,
            originalExtensionId = packed.extensionId,
            sourceSha256 = payloadHash,
            browserProfileSha256 = profile,
        )
        val after = before.copy(convertedExtensionId = packed.extensionId)
        val identity = "six-draft-crx-test"
        return SixDraftPayload.create(
            draft = DraftMetadata.create("test", emptyMap(), identity),
            input = DraftInputMetadata(
                ExtensionInputKind.CRX3,
                payloadHash,
                profile,
                packed.extensionId,
                "1",
                identity,
            ),
            sourceKind = DraftSourceKind.CRX,
            source = packed.bytes,
            plan = DraftConversionPlan.create(before, emptyList()),
            compatibilityBefore = before,
            compatibilityAfter = after,
        )
    }

    private fun keyPair(): KeyPair =
        KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun largeValidDraftPath(sourceSize: Long): java.nio.file.Path {
        val sourceHash = hashRepeatedZeroes(sourceSize)
        val profileHash = "2".repeat(64)
        val identity = "large-draft-path-test"
        val draft = DraftMetadata.createV2("test", emptyMap(), identity)
        val input = DraftInputMetadata(
            inputKind = ExtensionInputKind.ZIP,
            originalSourceSha256 = sourceHash,
            browserProfileSha256 = profileHash,
            originalExtensionId = null,
            originalExtensionVersion = "1",
            sourceIdentity = identity,
        )
        val report = CompatibilityReport(
            status = CompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = "Large Draft",
            extensionVersion = "1",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            conversionState = ConversionState.PASSTHROUGH,
            sourceSha256 = sourceHash,
            browserProfileSha256 = profileHash,
        )
        val plan = DraftConversionPlan.create(report, emptyList())
        val expected = DraftExpected(
            originalSourceSha256 = sourceHash,
            transformedSourceSha256 = sourceHash,
            reportSha256 = DraftSignature.sha256(
                BundleJson.encodeCompatibility(
                    report.toContractV4(
                        originalSourceSha256 = sourceHash,
                        planSha256 = plan.planSha256,
                    ),
                ).toByteArray(Charsets.UTF_8),
            ),
            planSha256 = plan.planSha256,
        )
        val metadata = linkedMapOf(
            "draft.json" to SixDraftJson.encodeDraft(draft),
            "input.json" to SixDraftJson.encodeInput(input),
            "conversion-plan.json" to SixDraftJson.encodePlan(plan),
            "compatibility-before.json" to SixDraftJson.encodeCompatibility(report),
            "compatibility-after.json" to SixDraftJson.encodeCompatibility(report),
            "expected.json" to SixDraftJson.encodeExpected(expected, 2),
        )
        val digests = metadata.mapValuesTo(linkedMapOf()) { (_, bytes) ->
            DraftEntryDigest(bytes.size.toLong(), DraftSignature.sha256(bytes))
        }
        digests["source.zip"] = DraftEntryDigest(sourceSize, sourceHash)
        val signingKey = keyPair()
        val canonical = buildString {
            digests.toSortedMap().forEach { (name, digest) ->
                append(name)
                append('\t')
                append(digest.size)
                append('\t')
                append(digest.sha256)
                append('\n')
            }
        }.toByteArray(Charsets.UTF_8)
        val signature = Signature.getInstance(DraftSignature.ALGORITHM).run {
            initSign(signingKey.private)
            update(canonical)
            sign()
        }
        metadata[DraftSignature.ENTRY_NAME] = SixDraftJson.encodeSignature(
            DraftSignatureRecord.create(
                schemaVersion = DraftSignature.SCHEMA_VERSION,
                algorithm = DraftSignature.ALGORITHM,
                keyId = DraftSignature.fingerprint(signingKey.public.encoded),
                publicKey = signingKey.public.encoded,
                signature = signature,
            ),
        )

        val path = Files.createTempFile("large-valid-draft", ".sixdraft")
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            (metadata.keys + "source.zip").sorted().forEach { name ->
                if (name == "source.zip") {
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            time = 0L
                            method = ZipEntry.STORED
                            size = sourceSize
                            compressedSize = sourceSize
                            crc = crcRepeatedZeroes(sourceSize)
                        },
                    )
                    writeRepeatedZeroes(zip, sourceSize)
                } else {
                    val bytes = metadata.getValue(name)
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            time = 0L
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            crc = CRC32().apply { update(bytes) }.value
                        },
                    )
                    zip.write(bytes)
                }
                zip.closeEntry()
            }
        }
        return path
    }

    private fun hashRepeatedZeroes(size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        repeatZeroChunks(size) { bytes, count -> digest.update(bytes, 0, count) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun crcRepeatedZeroes(size: Long): Long {
        val crc = CRC32()
        repeatZeroChunks(size) { bytes, count -> crc.update(bytes, 0, count) }
        return crc.value
    }

    private fun writeRepeatedZeroes(output: java.io.OutputStream, size: Long) {
        repeatZeroChunks(size) { bytes, count -> output.write(bytes, 0, count) }
    }

    private fun repeatZeroChunks(
        size: Long,
        consume: (ByteArray, Int) -> Unit,
    ) {
        val bytes = ByteArray(64 * 1024)
        var remaining = size
        while (remaining > 0L) {
            val count = minOf(bytes.size.toLong(), remaining).toInt()
            consume(bytes, count)
            remaining -= count
        }
    }

    private fun readEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
            }
        }
        return result
    }

    private fun storedArchive(
        entries: Map<String, ByteArray>,
        extra: Pair<String, ByteArray>? = null,
        comment: String? = null,
    ): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                if (comment != null) zip.setComment(comment)
                entries.toSortedMap().forEach { (name, bytes) ->
                    val crc = CRC32().apply { update(bytes) }
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            time = 0L
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            this.crc = crc.value
                            if (extra?.first == name) this.extra = extra.second
                        },
                    )
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun deflatedArchive(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.toSortedMap().forEach { (name, bytes) ->
                    val crc = CRC32().apply { update(bytes) }
                    val compressedSize = rawDeflate(bytes).size.toLong()
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            time = 0L
                            method = ZipEntry.DEFLATED
                            size = bytes.size.toLong()
                            this.compressedSize = compressedSize
                            this.crc = crc.value
                        },
                    )
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun rawDeflate(bytes: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        return try {
            deflater.setInput(bytes)
            deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) {
                output.write(buffer, 0, deflater.deflate(buffer))
            }
            output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun patchEntry(
        source: ByteArray,
        name: String,
        patch: (ByteArray, Int, Int) -> Unit,
    ): ByteArray {
        val result = source.copyOf()
        val local = findHeader(result, 0x04034b50, name, 26)
        val central = findHeader(result, 0x02014b50, name, 28)
        patch(result, local, central)
        return result
    }

    private fun patchCentralEntry(
        source: ByteArray,
        name: String,
        patch: (ByteArray, Int) -> Unit,
    ): ByteArray {
        val result = source.copyOf()
        patch(result, findHeader(result, 0x02014b50, name, 28))
        return result
    }

    private fun findHeader(
        bytes: ByteArray,
        signature: Int,
        name: String,
        nameLengthOffset: Int,
    ): Int {
        val expected = name.encodeToByteArray()
        for (offset in 0..bytes.size - 4) {
            if (le32(bytes, offset).toInt() != signature) continue
            val nameLength = le16(bytes, offset + nameLengthOffset)
            val nameOffset = offset + if (signature == 0x04034b50) 30 else 46
            if (nameLength == expected.size &&
                bytes.copyOfRange(nameOffset, nameOffset + nameLength)
                    .contentEquals(expected)
            ) {
                return offset
            }
        }
        error("header not found: $name")
    }

    private fun replaceAllAscii(source: ByteArray, before: String, after: String): ByteArray {
        require(before.length == after.length)
        val result = source.copyOf()
        val needle = before.encodeToByteArray()
        val replacement = after.encodeToByteArray()
        var count = 0
        for (offset in 0..result.size - needle.size) {
            if (result.copyOfRange(offset, offset + needle.size).contentEquals(needle)) {
                replacement.copyInto(result, offset)
                count++
            }
        }
        assertTrue(count >= 2)
        return result
    }

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Long =
        le16(bytes, offset).toLong() or
            (le16(bytes, offset + 2).toLong() shl 16)

    private fun putLe16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun putLe32(bytes: ByteArray, offset: Int, value: Long) {
        putLe16(bytes, offset, value.toInt())
        putLe16(bytes, offset + 2, (value ushr 16).toInt())
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
