package dev.sixbridge.core.draft

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.AdapterRisk
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.conversion.SourceEvidence
import dev.sixbridge.core.input.ExtensionInputKind
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SixDraftFixReviewTest {
    @Test
    fun `typed writer rejects unrelated source before after and plan provenance`() {
        val fixture = fixture()
        val unrelatedBefore = fixture.before.copy(
            sourceSha256 = "9".repeat(64),
        )

        assertFailsWith<InvalidSixDraftException> {
            SixDraftPayload.create(
                draft = fixture.draft,
                input = fixture.input,
                sourceKind = DraftSourceKind.ZIP,
                source = fixture.source,
                plan = fixture.plan,
                compatibilityBefore = unrelatedBefore,
                compatibilityAfter = fixture.after,
            )
        }
    }

    @Test
    fun `reader rejects a re-signed draft whose input provenance differs`() {
        val fixture = fixture()
        val entries = readEntries(
            SixDraft.createBytes(fixture.payload, fixture.signingKey),
        ).toMutableMap()
        entries["input.json"] = entries.getValue("input.json")
            .decodeToString()
            .replace(fixture.before.sourceSha256!!, "8".repeat(64))
            .encodeToByteArray()
        resign(entries, fixture.signingKey)

        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(storedArchive(entries))
        }
    }

    @Test
    fun `reader rejects duplicate signature fields before cryptographic verification`() {
        val fixture = fixture()
        val entries = readEntries(
            SixDraft.createBytes(fixture.payload, fixture.signingKey),
        ).toMutableMap()
        val signature = entries.getValue("draft-signature.json").decodeToString()
        entries["draft-signature.json"] = signature.replaceFirst(
            "\"algorithm\":\"Ed25519\"",
            "\"algorithm\":\"Ed25519\",\"algorithm\":\"Ed25519\"",
        ).encodeToByteArray()

        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(storedArchive(entries))
        }
    }

    @Test
    fun `reader rejects BOM malformed UTF-8 unknown fields and noncanonical JSON`() {
        val fixture = fixture()
        val base = readEntries(
            SixDraft.createBytes(fixture.payload, fixture.signingKey),
        )
        val mutations = listOf<(ByteArray) -> ByteArray>(
            { byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + it },
            { byteArrayOf(0xc0.toByte(), 0xaf.toByte()) },
            {
                it.decodeToString().dropLast(1)
                    .plus(",\"unknown\":true}")
                    .encodeToByteArray()
            },
            { it.decodeToString().replace(":", ": ").encodeToByteArray() },
        )

        mutations.forEach { mutation ->
            val entries = base.toMutableMap()
            entries["draft.json"] = mutation(entries.getValue("draft.json"))
            resign(entries, fixture.signingKey)
            assertFailsWith<InvalidSixDraftException> {
                SixDraft.read(storedArchive(entries))
            }
        }
    }

    @Test
    fun `compatibility schema is decoded strictly and semantically validated`() {
        val fixture = fixture()
        val entries = readEntries(
            SixDraft.createBytes(fixture.payload, fixture.signingKey),
        ).toMutableMap()
        entries["compatibility-before.json"] =
            entries.getValue("compatibility-before.json")
                .decodeToString()
                .dropLast(1)
                .plus(",\"unknown\":0}")
                .encodeToByteArray()
        resign(entries, fixture.signingKey)

        assertFailsWith<InvalidSixDraftException> {
            SixDraft.read(storedArchive(entries))
        }
    }

    @Test
    fun `verified result is sealed and source can be streamed without another stable copy`() {
        assertTrue(VerifiedSixDraft::class.java.isInterface)
        assertTrue(VerifiedSixDraft::class.java.isSealed)
        assertEquals(0, VerifiedSixDraft::class.java.constructors.size)

        val fixture = fixture(source = ByteArray(4 * 1024 * 1024) { 7 })
        val archive = SixDraft.createBytes(fixture.payload, fixture.signingKey)
        val verified = SixDraft.read(archive)
        val first = verified.openSourceStream().use { it.readBytes() }
        first[0] = 1

        assertEquals(7, verified.openSourceStream().use { it.read() })
        val retainedArrays = retainedByteArrays(verified.payload)
        assertTrue(
            retainedArrays.sumOf(ByteArray::size) <= archive.size + 2 * 1024 * 1024,
            "verified result가 source와 archive의 다중 안정 복사를 보유합니다.",
        )
    }

    @Test
    fun `broken pre-existing key state is filesystem-no-change`() {
        val trusted = Files.createTempDirectory("six-draft-broken-preflight")
        val root = trusted.resolve("keys")
        Files.createDirectory(root)
        Files.setPosixFilePermissions(
            root,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val privatePath = root.resolve("draft-transfer.pk8")
        val publicPath = root.resolve("draft-transfer.pub")
        Files.write(privatePath, pair.private.encoded)
        Files.write(publicPath, pair.public.encoded)
        listOf(privatePath, publicPath).forEach {
            Files.setPosixFilePermissions(
                it,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                ),
            )
        }
        val before = root.listDirectoryEntries()
            .associate { it.fileName.toString() to Files.readAllBytes(it) }

        assertFailsWith<IllegalStateException> {
            DraftSigningKeyStore(trusted, root).getOrCreate()
        }

        val after = root.listDirectoryEntries()
            .associate { it.fileName.toString() to Files.readAllBytes(it) }
        assertEquals(before.keys, after.keys)
        before.forEach { (name, bytes) ->
            assertContentEquals(bytes, after.getValue(name))
        }
        assertFalse(Files.exists(root.resolve(".sixbridge-draft-key.lock")))
    }

    private fun fixture(
        source: ByteArray = "converted-source".encodeToByteArray(),
    ): Fixture {
        val originalSourceSha256 = "1".repeat(64)
        val browserProfileSha256 = "2".repeat(64)
        val decision = AdapterDecision(
            id = "storage-fallback",
            version = 1,
            risk = AdapterRisk.SAFE,
            disposition = AdapterDisposition.AUTOMATIC,
            applied = false,
            evidence = listOf(SourceEvidence("worker.js", 1, "storage.sync")),
            changes = listOf("storage.sync -> storage.local"),
            remainingLimitations = emptyList(),
        )
        val before = CompatibilityReport(
            status = CompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = "Fixture",
            extensionVersion = "1.0",
            declaredPermissions = setOf("storage"),
            usedApis = setOf("storage.sync"),
            unsupportedApis = setOf("storage.sync"),
            issues = emptyList(),
            conversionState = ConversionState.AUTOMATIC,
            adapterDecisions = listOf(decision),
            sourceSha256 = originalSourceSha256,
            browserProfileSha256 = browserProfileSha256,
        )
        val convertedId = "a".repeat(32)
        val after = before.copy(
            status = CompatibilityStatus.COMPATIBLE,
            usedApis = setOf("storage.local"),
            unsupportedApis = emptySet(),
            verifiedApis = setOf("storage.local"),
            unavailableApis = emptySet(),
            conversionState = ConversionState.CONVERTED,
            adapterDecisions = listOf(decision.copy(applied = true)),
            convertedExtensionId = convertedId,
            sourceSha256 = sha256(source),
        )
        val sourceIdentity = "project-${"3".repeat(32)}"
        val draft = DraftMetadata.create(
            coreVersion = "0.1.0",
            adapterVersions = mapOf("storage-fallback" to 1),
            sourceIdentity = sourceIdentity,
        )
        val input = DraftInputMetadata(
            inputKind = ExtensionInputKind.SOURCE_DIRECTORY,
            originalSourceSha256 = originalSourceSha256,
            browserProfileSha256 = browserProfileSha256,
            originalExtensionId = null,
            originalExtensionVersion = "1.0",
            sourceIdentity = sourceIdentity,
        )
        val plan = DraftConversionPlan.create(
            report = before,
            selectedAdapters = listOf(DraftSelectedAdapter("storage-fallback", 1)),
        )
        val payload = SixDraftPayload.create(
            draft = draft,
            input = input,
            sourceKind = DraftSourceKind.ZIP,
            source = source,
            plan = plan,
            compatibilityBefore = before,
            compatibilityAfter = after,
        )
        return Fixture(
            source,
            draft,
            input,
            plan,
            before,
            after,
            payload,
            KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
        )
    }

    private fun resign(entries: MutableMap<String, ByteArray>, keyPair: KeyPair) {
        entries.remove("draft-signature.json")
        entries["draft-signature.json"] = SixDraftJson.encodeSignature(
            DraftSignature.sign(entries, keyPair),
        )
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

    private fun retainedByteArrays(root: Any): Set<ByteArray> {
        val seen = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>(),
        )
        val arrays = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<ByteArray, Boolean>(),
        )
        fun visit(value: Any?) {
            if (value == null || !seen.add(value)) return
            if (value is ByteArray) {
                arrays += value
                return
            }
            val type = value.javaClass
            if (type.name.startsWith("java.") ||
                type.name.startsWith("kotlin.") ||
                type.isEnum
            ) {
                return
            }
            type.declaredFields.forEach { field ->
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) return@forEach
                field.isAccessible = true
                visit(field.get(value))
            }
        }
        visit(root)
        return arrays
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val source: ByteArray,
        val draft: DraftMetadata,
        val input: DraftInputMetadata,
        val plan: DraftConversionPlan,
        val before: CompatibilityReport,
        val after: CompatibilityReport,
        val payload: SixDraftPayload,
        val signingKey: KeyPair,
    )
}
