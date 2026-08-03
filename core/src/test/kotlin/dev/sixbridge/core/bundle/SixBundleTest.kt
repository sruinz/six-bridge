package dev.sixbridge.core.bundle

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.AdapterRisk
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.conversion.SourceEvidence
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SixBundleTest {
    @Test
    fun `writes and reads the fixed three-file bundle contract`() {
        val wrapper = Files.createTempFile("wrapper", ".apk")
        wrapper.writeBytes(byteArrayOf(0x50, 0x4b, 1, 2, 3))
        val output = Files.createTempFile("tiny", ".six")
        val descriptor = ExtensionDescriptor(
            extensionId = "abcdefghijklmnopabcdefghijklmnop",
            name = "Tiny",
            version = "1.0.0",
            sourceKind = "SOURCE_DIRECTORY",
            crxSha256 = "a".repeat(64),
            wrapperSha256 = sha256(wrapper.toFile().readBytes()),
            wrapperPackage = "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop",
            wrapperVersionCode = 7,
            signerSha256 = "b".repeat(64),
        )
        val compatibility = CompatibilityReport(
            status = CompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = "Tiny",
            extensionVersion = "1.0.0",
            declaredPermissions = setOf("storage"),
            usedApis = setOf("storage"),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            conversionState = ConversionState.REQUIRES_APPROVAL,
            adapterDecisions = listOf(
                AdapterDecision(
                    id = "popup-fallback",
                    version = 2,
                    risk = AdapterRisk.BEHAVIOR_CHANGE,
                    disposition = AdapterDisposition.REQUIRES_APPROVAL,
                    applied = false,
                    evidence = listOf(
                        SourceEvidence("popup.js", 4, "action popup"),
                    ),
                    changes = listOf("manifest.json"),
                    remainingLimitations = listOf("popup rendering differs"),
                ),
            ),
            originalExtensionId = "abcdefghijklmnopabcdefghijklmnop",
            convertedExtensionId = "abcdefghijklmnopabcdefghijklmnop",
            sourceSha256 = "c".repeat(64),
            browserProfileSha256 = "d".repeat(64),
        )

        SixBundle.write(output, wrapper, descriptor, compatibility)
        val read = SixBundle.read(output)

        assertEquals(descriptor, read.extension)
        assertEquals(compatibility, read.compatibility)
        assertContentEquals(wrapper.toFile().readBytes(), read.wrapperApk)
        ZipFile(output.toFile()).use { zip ->
            assertNotNull(zip.getEntry("wrapper.apk"))
            assertNotNull(zip.getEntry("extension.json"))
            assertNotNull(zip.getEntry("compatibility.json"))
            assertEquals(3, zip.size())
        }
    }

    @Test
    fun `encodes conversion decisions in deterministic order`() {
        val compatibility = CompatibilityReport(
            status = CompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = "Tiny",
            extensionVersion = "1.0.0",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            conversionState = ConversionState.REQUIRES_APPROVAL,
            sourceSha256 = "c".repeat(64),
            browserProfileSha256 = "d".repeat(64),
            adapterDecisions = listOf(
                AdapterDecision(
                    id = "zeta",
                    version = 1,
                    risk = AdapterRisk.SAFE,
                    disposition = AdapterDisposition.AUTOMATIC,
                    applied = false,
                    evidence = listOf(
                        SourceEvidence("z.js", 9, "second"),
                        SourceEvidence("a.js", 2, "first"),
                    ),
                    changes = listOf("z.js", "a.js"),
                    remainingLimitations = emptyList(),
                ),
                AdapterDecision(
                    id = "alpha",
                    version = 2,
                    risk = AdapterRisk.BEHAVIOR_CHANGE,
                    disposition = AdapterDisposition.REQUIRES_APPROVAL,
                    applied = false,
                    evidence = emptyList(),
                    changes = emptyList(),
                    remainingLimitations = emptyList(),
                ),
            ),
        )

        val encoded = SixBundle.encodeCompatibility(compatibility)
        val changesIndex = encoded.indexOf("\"changes\"")

        assertTrue(encoded.indexOf("\"id\": \"alpha\"") < encoded.indexOf("\"id\": \"zeta\""))
        assertTrue(encoded.indexOf("\"relativePath\": \"a.js\"") < encoded.indexOf("\"relativePath\": \"z.js\""))
        assertTrue(encoded.indexOf("\"a.js\"", changesIndex) < encoded.indexOf("\"z.js\"", changesIndex))
        assertTrue("\"conversionState\": \"requires-approval\"" in encoded)
    }

    @Test
    fun `storage fallback capability survives compatibility JSON roundtrip`() {
        val capability =
            "schema=1; source-closure-sha256=${"c".repeat(64)}; " +
                "converted-closure-sha256=${"d".repeat(64)}; " +
                "profile-sha256=${"e".repeat(64)}; " +
                "storage.local=supported; storage.sync=unavailable"
        val compatibility = CompatibilityReport(
            status = CompatibilityStatus.COMPATIBLE,
            manifestVersion = 3,
            extensionName = "Storage",
            extensionVersion = "1",
            declaredPermissions = setOf("storage"),
            usedApis = setOf("storage"),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            conversionState = ConversionState.AUTOMATIC,
            sourceSha256 = "c".repeat(64),
            browserProfileSha256 = "d".repeat(64),
            adapterDecisions = listOf(
                AdapterDecision(
                    id = "storage-fallback",
                    version = 1,
                    risk = AdapterRisk.SAFE,
                    disposition = AdapterDisposition.AUTOMATIC,
                    applied = false,
                    evidence = listOf(
                        SourceEvidence(
                            "@sixbridge/storage-fallback-capability",
                            null,
                            capability,
                        ),
                    ),
                    changes = listOf("정적인 storage.sync member를 storage.local로 변경합니다."),
                    remainingLimitations = listOf("기기 간 동기화가 제공되지 않습니다."),
                ),
            ),
        )

        val roundtripped = SixBundle.read(
            bundleWithCompatibility(compatibility),
        ).compatibility

        assertEquals(compatibility, roundtripped)
    }

    @Test
    fun `schema v3 requires both lowercase provenance digests`() {
        val missing = compatibilityWithProofs().copy(
            sourceSha256 = null,
        )
        val uppercase = compatibilityWithProofs().copy(
            browserProfileSha256 = "A".repeat(64),
        )

        assertFailsWith<IllegalArgumentException> {
            SixBundle.encodeCompatibility(missing)
        }
        assertFailsWith<IllegalArgumentException> {
            SixBundle.encodeCompatibility(uppercase)
        }

        val bundle = bundleWithCompatibility(compatibilityWithProofs())
        rewrite(bundle) { name, bytes ->
            if (name == "compatibility.json") {
                bytes.decodeToString()
                    .replace(
                        "\"sourceSha256\":\"${"c".repeat(64)}\"",
                        "\"sourceSha256\":null",
                    )
                    .toByteArray()
            } else {
                bytes
            }
        }
        assertFailsWith<InvalidSixBundleException> {
            SixBundle.read(bundle)
        }
    }

    @Test
    fun `maps explicit on-device proofs to a v5 contract report`() {
        val converted = compatibilityWithProofs().toContractV4(
            originalSourceSha256 = "a".repeat(64),
            planSha256 = "b".repeat(64),
        )

        assertEquals(5, converted.schemaVersion)
        assertEquals("a".repeat(64), converted.originalSourceSha256)
        assertEquals("b".repeat(64), converted.planSha256)
        assertEquals("c".repeat(64), converted.sourceSha256)
        assertEquals("d".repeat(64), converted.browserProfileSha256)
    }

    @Test
    fun `schema v5 provenance cannot be downgraded to schema v2`() {
        val bundle = bundleWithCompatibility(compatibilityWithProofs())
        rewrite(bundle) { name, bytes ->
            if (name == "compatibility.json") {
                bytes.decodeToString()
                    .replace("\"schemaVersion\":5", "\"schemaVersion\":2")
                    .toByteArray()
            } else {
                bytes
            }
        }

        assertFailsWith<InvalidSixBundleException> {
            SixBundle.read(bundle)
        }
    }

    @Test
    fun `reads schema v1 compatibility with passthrough defaults`() {
        val bundle = validBundle()
        rewrite(bundle) { name, bytes ->
            if (name == "compatibility.json") legacyCompatibilityJson().toByteArray() else bytes
        }

        val compatibility = SixBundle.read(bundle).compatibility

        assertEquals(ConversionState.PASSTHROUGH, compatibility.conversionState)
        assertEquals(emptyList(), compatibility.adapterDecisions)
        assertEquals(null, compatibility.originalExtensionId)
        assertEquals(null, compatibility.convertedExtensionId)
    }

    @Test
    fun `rejects duplicate adapter decision identities`() {
        val decision = AdapterDecision(
            id = "popup-fallback",
            version = 1,
            risk = AdapterRisk.SAFE,
            disposition = AdapterDisposition.AUTOMATIC,
            applied = false,
            evidence = emptyList(),
            changes = emptyList(),
            remainingLimitations = emptyList(),
        )
        val compatibility = CompatibilityReport(
            status = CompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = "Tiny",
            extensionVersion = "1.0.0",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            adapterDecisions = listOf(decision, decision),
        )

        assertFailsWith<IllegalArgumentException> { compatibility.validate() }
    }

    @Test
    fun `rejects bundles with missing contract entries`() {
        val notBundle = Files.createTempFile("not-a-bundle", ".zip")
        notBundle.writeBytes(byteArrayOf(1, 2, 3))

        assertFailsWith<InvalidSixBundleException> {
            SixBundle.read(notBundle)
        }
    }

    @Test
    fun `rejects unsupported metadata schema versions`() {
        val bundle = validBundle()
        rewrite(bundle) { name, bytes ->
            when (name) {
                "extension.json", "compatibility.json" ->
                    bytes.decodeToString()
                        .replace("\"schemaVersion\":1", "\"schemaVersion\":2")
                        .toByteArray()
                else -> bytes
            }
        }

        assertFailsWith<InvalidSixBundleException> {
            SixBundle.read(bundle)
        }
    }

    @Test
    fun `rejects malformed extension metadata`() {
        val bundle = validBundle()
        rewrite(bundle) { name, bytes ->
            if (name == "extension.json") {
                bytes.decodeToString()
                    .replace(
                        "abcdefghijklmnopabcdefghijklmnop",
                        "invalid-extension-id",
                    )
                    .replace("\"wrapperVersionCode\":7", "\"wrapperVersionCode\":0")
                    .toByteArray()
            } else {
                bytes
            }
        }

        assertFailsWith<InvalidSixBundleException> {
            SixBundle.read(bundle)
        }
    }

    @Test
    fun `rejects unknown extension metadata through the contract adapter`() {
        val bundle = validBundle()
        rewrite(bundle) { name, bytes ->
            if (name == "extension.json") {
                bytes.decodeToString()
                    .replace(
                        "\"schemaVersion\":1,",
                        "\"schemaVersion\":1,\"unexpected\":true,",
                    )
                    .toByteArray()
            } else {
                bytes
            }
        }

        assertFailsWith<InvalidSixBundleException> {
            SixBundle.read(bundle)
        }
    }

    @Test
    fun `rejects a replaced wrapper apk whose digest differs from the descriptor`() {
        val bundle = validBundle()
        rewrite(bundle) { name, bytes ->
            when (name) {
                "wrapper.apk" -> byteArrayOf(0x50, 0x4b, 9, 9, 9)
                else -> bytes
            }
        }

        assertFailsWith<InvalidSixBundleException> {
            SixBundle.read(bundle)
        }
    }

    private fun validBundle(): java.nio.file.Path {
        return bundleWithCompatibility(
            compatibilityWithProofs(),
        )
    }

    private fun bundleWithCompatibility(
        compatibility: CompatibilityReport,
    ): java.nio.file.Path {
        val wrapper = Files.createTempFile("six-bundle-wrapper", ".apk")
        wrapper.writeBytes(byteArrayOf(0x50, 0x4b, 1, 2, 3))
        return Files.createTempFile("six-bundle-valid", ".six").also { output ->
            SixBundle.write(
                output,
                wrapper,
                ExtensionDescriptor(
                    extensionId = "abcdefghijklmnopabcdefghijklmnop",
                    name = compatibility.extensionName ?: "Tiny",
                    version = compatibility.extensionVersion ?: "1.0.0",
                    sourceKind = "SOURCE_DIRECTORY",
                    crxSha256 = "a".repeat(64),
                    wrapperSha256 = sha256(wrapper.toFile().readBytes()),
                    wrapperPackage =
                        "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop",
                    wrapperVersionCode = 7,
                    signerSha256 = "b".repeat(64),
                ),
                compatibility,
            )
        }
    }

    private fun compatibilityWithProofs() = CompatibilityReport(
        status = CompatibilityStatus.COMPATIBLE,
        manifestVersion = 3,
        extensionName = "Tiny",
        extensionVersion = "1.0.0",
        declaredPermissions = emptySet(),
        usedApis = emptySet(),
        unsupportedApis = emptySet(),
        issues = emptyList(),
        sourceSha256 = "c".repeat(64),
        browserProfileSha256 = "d".repeat(64),
    )

    private fun rewrite(
        bundle: java.nio.file.Path,
        transform: (String, ByteArray) -> ByteArray,
    ) {
        val entries = ZipFile(bundle.toFile()).use { zip ->
            zip.entries().asSequence().associate { entry ->
                entry.name to zip.getInputStream(entry).readBytes()
            }
        }
        ZipOutputStream(Files.newOutputStream(bundle)).use { output ->
            entries.forEach { (name, bytes) ->
                output.putNextEntry(ZipEntry(name))
                output.write(transform(name, bytes))
                output.closeEntry()
            }
        }
    }

    private fun legacyCompatibilityJson() =
        """
        {
          "schemaVersion": 1,
          "status": "compatible",
          "manifestVersion": 3,
          "extensionName": "Tiny",
          "extensionVersion": "1.0.0",
          "manifestKeyPresent": false,
          "manifestKeyExtensionId": null,
          "declaredPermissions": [],
          "usedApis": [],
          "unsupportedApis": [],
          "issues": []
        }
        """.trimIndent()

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
