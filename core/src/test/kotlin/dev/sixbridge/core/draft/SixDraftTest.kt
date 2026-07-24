package dev.sixbridge.core.draft

import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.AdapterRisk
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.input.ExtensionInputKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
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
    fun `non Ed25519 algorithm and schema two are rejected`() {
        val bytes = SixDraft.createBytes(payload(DraftSourceKind.ZIP), keyPair())
        assertSignatureMutationRejected(bytes, "algorithm", "SHA256withRSA")

        val entries = readEntries(bytes).toMutableMap()
        entries["draft.json"] = entries.getValue("draft.json")
            .decodeToString()
            .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
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
