package dev.sixbridge.contract.bundle

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SixBundleContractTest {
    @Test
    fun `round trips the fixed three-entry bundle contract`() {
        val contents = validContents()
        val output = ByteArrayOutputStream()

        SixBundleContract.write(output, contents)
        val read = SixBundleContract.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(contents.extension, read.extension)
        assertEquals(contents.compatibility, read.compatibility)
        assertContentEquals(contents.wrapperApk, read.wrapperApk)
        ZipFile(writeTempBundle(output.toByteArray()).toFile()).use { zip ->
            assertEquals(
                listOf("wrapper.apk", "extension.json", "compatibility.json"),
                zip.entries().asSequence().map { it.name }.toList(),
            )
            val wrapper = requireNotNull(zip.getEntry(SixBundleContract.WRAPPER_ENTRY))
            assertEquals(ZipEntry.STORED, wrapper.method)
            assertEquals(wrapper.size, wrapper.compressedSize)
            val extension = requireNotNull(zip.getEntry(SixBundleContract.EXTENSION_ENTRY))
            assertEquals(ZipEntry.STORED, extension.method)
            assertEquals(extension.size, extension.compressedSize)
            val compatibility = requireNotNull(zip.getEntry(SixBundleContract.COMPATIBILITY_ENTRY))
            assertEquals(ZipEntry.STORED, compatibility.method)
            assertEquals(compatibility.size, compatibility.compressedSize)
        }
    }

    @Test
    fun `byte array writer round trips highly compressible near-limit metadata as stored`() {
        val contents = nearLimitMetadataContents()
        val output = ByteArrayOutputStream()

        SixBundleContract.write(output, contents)
        val read = SixBundleContract.read(ByteArrayInputStream(output.toByteArray()))

        assertEquals(contents.extension, read.extension)
        assertEquals(contents.compatibility, read.compatibility)
        assertAllEntriesStored(output.toByteArray())
    }

    @Test
    fun `path writer round trips highly compressible near-limit metadata as stored`() {
        val contents = nearLimitMetadataContents()
        val wrapper = Files.createTempFile("six-bundle-near-limit", ".apk")
        val output = ByteArrayOutputStream()
        try {
            Files.write(wrapper, contents.wrapperApk)

            SixBundleContract.write(
                output,
                wrapper,
                contents.extension,
                contents.compatibility,
            )
            val read = SixBundleContract.read(ByteArrayInputStream(output.toByteArray()))

            assertEquals(contents.extension, read.extension)
            assertEquals(contents.compatibility, read.compatibility)
            assertAllEntriesStored(output.toByteArray())
        } finally {
            Files.deleteIfExists(wrapper)
        }
    }

    @Test
    fun `v4 compatibility preserves original plan and result proofs`() {
        val report = validContents().compatibility.copy(
            schemaVersion = 4,
            originalSourceSha256 = "e".repeat(64),
            planSha256 = "f".repeat(64),
        )

        val decoded = BundleJson.decodeCompatibility(BundleJson.encodeCompatibility(report))

        assertEquals(report, decoded)
    }

    @Test
    fun `v4 compatibility rejects missing provenance proofs`() {
        val encoded = BundleJson.encodeCompatibility(
            validContents().compatibility.copy(
                schemaVersion = 4,
                originalSourceSha256 = "e".repeat(64),
                planSha256 = "f".repeat(64),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            BundleJson.decodeCompatibility(
                encoded.replace(",\"originalSourceSha256\":\"${"e".repeat(64)}\"", ""),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BundleJson.decodeCompatibility(
                encoded.replace(",\"planSha256\":\"${"f".repeat(64)}\"", ""),
            )
        }
    }

    @Test
    fun `v4 compatibility rejects malformed and unknown provenance fields`() {
        val encoded = BundleJson.encodeCompatibility(
            validContents().compatibility.copy(
                schemaVersion = 4,
                originalSourceSha256 = "e".repeat(64),
                planSha256 = "f".repeat(64),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            BundleJson.decodeCompatibility(
                encoded.replace("\"planSha256\":\"${"f".repeat(64)}\"", "\"planSha256\":\"${"F".repeat(64)}\""),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BundleJson.decodeCompatibility(encoded.dropLast(1) + ",\"unexpected\":true}")
        }
    }

    @Test
    fun `writer only records compatibility schemas three and four`() {
        val legacy = validContents().copy(
            compatibility = validContents().compatibility.copy(
                schemaVersion = 2,
                sourceSha256 = null,
                browserProfileSha256 = null,
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.write(ByteArrayOutputStream(), legacy)
        }
    }

    @Test
    fun `rejects unknown extension metadata keys`() {
        val malformed = rewrite(encoded(validContents())) { name, bytes ->
            if (name == SixBundleContract.EXTENSION_ENTRY) {
                bytes.decodeToString().replace(
                    "\"schemaVersion\":1",
                    "\"schemaVersion\":1,\"unexpected\":true",
                ).encodeToByteArray()
            } else {
                bytes
            }
        }

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(malformed))
        }
    }

    @Test
    fun `writer rejects wrapper whose digest differs from descriptor`() {
        val contents = validContents().copy(wrapperApk = byteArrayOf(9, 8, 7))

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.write(ByteArrayOutputStream(), contents)
        }
    }

    @Test
    fun `reader rejects raw wrapper whose digest differs from descriptor`() {
        val malformed = rewrite(encoded(validContents())) { name, bytes ->
            if (name == SixBundleContract.WRAPPER_ENTRY) byteArrayOf(9, 8, 7) else bytes
        }

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(malformed))
        }
    }

    @Test
    fun `rejects archive without central directory and end record`() {
        val localHeaderOnly = withoutCentralDirectory(encoded(validContents()))

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(localHeaderOnly))
        }
    }

    @Test
    fun `rejects trailing data after end record`() {
        val trailing = encoded(validContents()) + byteArrayOf(0)

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(trailing))
        }
    }

    @Test
    fun `deletes temporary archive after read`() {
        val before = temporaryArchiveNames()

        SixBundleContract.read(ByteArrayInputStream(encoded(validContents())))
        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(withoutCentralDirectory(encoded(validContents()))))
        }

        assertEquals(before, temporaryArchiveNames())
    }

    @Test
    fun `writes reproducible archives in the required order`() {
        val first = ByteArrayOutputStream()
        val second = ByteArrayOutputStream()

        SixBundleContract.write(first, validContents())
        SixBundleContract.write(second, validContents())

        assertContentEquals(first.toByteArray(), second.toByteArray())
    }

    @Test
    fun `rejects duplicate entries`() {
        val archive = rawStoredZip(
            listOf(
                SixBundleContract.WRAPPER_ENTRY to byteArrayOf(1),
                SixBundleContract.WRAPPER_ENTRY to byteArrayOf(2),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(archive))
        }
    }

    @Test
    fun `rejects traversal and directory entries`() {
        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(rawStoredZip(listOf("../wrapper.apk" to byteArrayOf()))))
        }
        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(rawStoredZip(listOf("folder/" to byteArrayOf()))))
        }
    }

    @Test
    fun `rejects extra entries`() {
        val archive = append(encoded(validContents()), "extra.txt", byteArrayOf(1))

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(archive))
        }
    }

    @Test
    fun `rejects archive whose total expansion exceeds compression ratio`() {
        val wrapper = ByteArray(1024 * 1024)
        val contents = validContents().copy(
            wrapperApk = wrapper,
            extension = validContents().extension.copy(wrapperSha256 = sha256(wrapper)),
        )

        val untrustedDeflated = rewrite(encoded(contents)) { _, bytes -> bytes }

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(untrustedDeflated))
        }
    }

    @Test
    fun `rejects compression ratio padding between local entries and central directory`() {
        val wrapper = ByteArray(1024 * 1024)
        val contents = validContents().copy(
            wrapperApk = wrapper,
            extension = validContents().extension.copy(wrapperSha256 = sha256(wrapper)),
        )
        val untrustedDeflated = rewrite(encoded(contents)) { _, bytes -> bytes }
        val padded = withLocalCentralPadding(untrustedDeflated, 256 * 1024)

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(padded))
        }
    }

    @Test
    fun `rejects invalid extension id`() {
        val invalid = rewrite(encoded(validContents())) { name, bytes ->
            if (name == SixBundleContract.EXTENSION_ENTRY) {
                bytes.decodeToString()
                    .replace("abcdefghijklmnopabcdefghijklmnop", "q".repeat(32))
                    .encodeToByteArray()
            } else {
                bytes
            }
        }

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(invalid))
        }
    }

    @Test
    fun `rejects invalid wrapper package`() {
        val invalid = rewrite(encoded(validContents())) { name, bytes ->
            if (name == SixBundleContract.EXTENSION_ENTRY) {
                bytes.decodeToString()
                    .replace(
                        "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop",
                        "dev.sixbridge.ext.other",
                    )
                    .encodeToByteArray()
            } else {
                bytes
            }
        }

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(invalid))
        }
    }

    @Test
    fun `rejects unsupported metadata schema versions`() {
        val unsupportedExtension = rewrite(encoded(validContents())) { name, bytes ->
            if (name == SixBundleContract.EXTENSION_ENTRY) {
                bytes.decodeToString().replace("\"schemaVersion\":1", "\"schemaVersion\":9").encodeToByteArray()
            } else {
                bytes
            }
        }

        assertFailsWith<IllegalArgumentException> {
            SixBundleContract.read(ByteArrayInputStream(unsupportedExtension))
        }
    }

    @Test
    fun `reads compatibility schema versions one and two`() {
        val v1 = rewrite(encoded(validContents())) { name, bytes ->
            if (name == SixBundleContract.COMPATIBILITY_ENTRY) legacyCompatibility(1).encodeToByteArray() else bytes
        }
        val v2 = rewrite(encoded(validContents())) { name, bytes ->
            if (name == SixBundleContract.COMPATIBILITY_ENTRY) legacyCompatibility(2).encodeToByteArray() else bytes
        }

        assertEquals(1, SixBundleContract.read(ByteArrayInputStream(v1)).compatibility.schemaVersion)
        assertEquals(2, SixBundleContract.read(ByteArrayInputStream(v2)).compatibility.schemaVersion)
    }

    private fun validContents(): ContractBundleContents {
        val wrapper = byteArrayOf(0x50, 0x4b, 1, 2, 3)
        return ContractBundleContents(
            wrapperApk = wrapper,
            extension = ContractExtensionDescriptor(
                schemaVersion = 1,
                extensionId = "abcdefghijklmnopabcdefghijklmnop",
                name = "Tiny",
                version = "1.0.0",
                sourceKind = "SOURCE_DIRECTORY",
                crxSha256 = "a".repeat(64),
                wrapperSha256 = sha256(wrapper),
                wrapperPackage = "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop",
                wrapperVersionCode = 7,
                signerSha256 = "b".repeat(64),
            ),
            compatibility = ContractCompatibilityReport(
                schemaVersion = 3,
                status = ContractCompatibilityStatus.COMPATIBLE,
                manifestVersion = 3,
                extensionName = "Tiny",
                extensionVersion = "1.0.0",
                manifestKeyPresent = false,
                manifestKeyExtensionId = null,
                declaredPermissions = listOf("storage"),
                usedApis = listOf("storage"),
                unsupportedApis = emptyList(),
                issues = emptyList(),
                conversionState = "requires-approval",
                adapterDecisions = emptyList(),
                originalExtensionId = "abcdefghijklmnopabcdefghijklmnop",
                convertedExtensionId = "abcdefghijklmnopabcdefghijklmnop",
                sourceSha256 = "c".repeat(64),
                browserProfileSha256 = "d".repeat(64),
            ),
        )
    }

    private fun nearLimitMetadataContents(): ContractBundleContents {
        val contents = validContents()
        return contents.copy(
            extension = contents.extension.copy(name = "E".repeat(900_000)),
            compatibility = contents.compatibility.copy(
                extensionName = null,
                issues = listOf(
                    ContractIssue(
                        code = "near-limit",
                        message = "R".repeat(900_000),
                        blocking = false,
                    ),
                ),
            ),
        )
    }

    private fun assertAllEntriesStored(archive: ByteArray) {
        ZipFile(writeTempBundle(archive).toFile()).use { zip ->
            listOf(
                SixBundleContract.WRAPPER_ENTRY,
                SixBundleContract.EXTENSION_ENTRY,
                SixBundleContract.COMPATIBILITY_ENTRY,
            ).forEach { name ->
                val entry = requireNotNull(zip.getEntry(name))
                assertEquals(ZipEntry.STORED, entry.method, name)
                assertEquals(entry.size, entry.compressedSize, name)
            }
        }
    }

    private fun encoded(contents: ContractBundleContents): ByteArray {
        val output = ByteArrayOutputStream()
        SixBundleContract.write(output, contents)
        return output.toByteArray()
    }

    private fun rewrite(
        archive: ByteArray,
        transform: (String, ByteArray) -> ByteArray,
    ): ByteArray = ByteArrayOutputStream().also { output ->
        ZipInputStream(ByteArrayInputStream(archive)).use { input ->
            ZipOutputStream(output).use { zip ->
                generateSequence { input.nextEntry }.forEach { entry ->
                    zip.putNextEntry(ZipEntry(entry.name).apply { time = 0L })
                    zip.write(transform(entry.name, input.readBytes()))
                    zip.closeEntry()
                }
            }
        }
    }.toByteArray()

    private fun append(archive: ByteArray, name: String, bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipInputStream(ByteArrayInputStream(archive)).use { input ->
                ZipOutputStream(output).use { zip ->
                    generateSequence { input.nextEntry }.forEach { entry ->
                        zip.putNextEntry(entry)
                        zip.write(input.readBytes())
                        zip.closeEntry()
                    }
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun legacyCompatibility(schemaVersion: Int): String =
        if (schemaVersion == 1) {
            """{"schemaVersion":1,"status":"compatible","manifestKeyPresent":false,"declaredPermissions":[],"usedApis":[],"unsupportedApis":[],"issues":[]}"""
        } else {
            """{"schemaVersion":2,"status":"compatible","manifestKeyPresent":false,"declaredPermissions":[],"usedApis":[],"unsupportedApis":[],"issues":[],"conversionState":"passthrough","adapterDecisions":[]}"""
        }

    private fun rawStoredZip(entries: List<Pair<String, ByteArray>>): ByteArray = ByteArrayOutputStream().also { output ->
        entries.forEach { (name, bytes) ->
            val encodedName = name.encodeToByteArray()
            val crc = CRC32().apply { update(bytes) }.value
            output.writeLe(0x04034b50, 4)
            output.writeLe(20, 2)
            output.writeLe(0, 2)
            output.writeLe(0, 2)
            output.writeLe(0, 2)
            output.writeLe(0, 2)
            output.writeLe(crc, 4)
            output.writeLe(bytes.size, 4)
            output.writeLe(bytes.size, 4)
            output.writeLe(encodedName.size, 2)
            output.writeLe(0, 2)
            output.write(encodedName)
            output.write(bytes)
        }
    }.toByteArray()

    private fun withoutCentralDirectory(archive: ByteArray): ByteArray {
        val centralDirectory = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val offset = archive.indices.first { index ->
            index <= archive.size - centralDirectory.size &&
                centralDirectory.indices.all { archive[index + it] == centralDirectory[it] }
        }
        return archive.copyOf(offset)
    }

    private fun withLocalCentralPadding(archive: ByteArray, paddingSize: Int): ByteArray {
        val centralOffset = signatureOffset(archive, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        val endOffset = signatureOffset(archive, byteArrayOf(0x50, 0x4b, 0x05, 0x06))
        val padded = ByteArray(archive.size + paddingSize)
        archive.copyInto(padded, endIndex = centralOffset)
        archive.copyInto(padded, centralOffset + paddingSize, centralOffset)
        padded.writeLe(endOffset + paddingSize + 16, centralOffset + paddingSize)
        return padded
    }

    private fun signatureOffset(archive: ByteArray, signature: ByteArray): Int = archive.indices.first { index ->
        index <= archive.size - signature.size && signature.indices.all { archive[index + it] == signature[it] }
    }

    private fun ByteArray.writeLe(offset: Int, value: Int) {
        repeat(4) { index -> this[offset + index] = (value ushr (index * 8)).toByte() }
    }

    private fun temporaryArchiveNames(): Set<String> = File(System.getProperty("java.io.tmpdir"))
        .listFiles()
        .orEmpty()
        .filter { it.name.startsWith("six-bundle-contract-") }
        .map { it.name }
        .toSet()

    private fun ByteArrayOutputStream.writeLe(value: Number, bytes: Int) {
        repeat(bytes) { index -> write((value.toLong() shr (index * 8)).toInt() and 0xff) }
    }

    private fun writeTempBundle(bytes: ByteArray) =
        kotlin.io.path.createTempFile("six-bundle-contract", ".six").also { path ->
            Files.write(path, bytes)
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
