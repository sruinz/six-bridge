package dev.sixbridge.core.bundle

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
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
                        .replace("\"schemaVersion\": 1", "\"schemaVersion\": 2")
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
                    .replace("\"wrapperVersionCode\": 7", "\"wrapperVersionCode\": 0")
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
        val wrapper = Files.createTempFile("six-bundle-wrapper", ".apk")
        wrapper.writeBytes(byteArrayOf(0x50, 0x4b, 1, 2, 3))
        return Files.createTempFile("six-bundle-valid", ".six").also { output ->
            SixBundle.write(
                output,
                wrapper,
                ExtensionDescriptor(
                    extensionId = "abcdefghijklmnopabcdefghijklmnop",
                    name = "Tiny",
                    version = "1.0.0",
                    sourceKind = "SOURCE_DIRECTORY",
                    crxSha256 = "a".repeat(64),
                    wrapperSha256 = sha256(wrapper.toFile().readBytes()),
                    wrapperPackage =
                        "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop",
                    wrapperVersionCode = 7,
                    signerSha256 = "b".repeat(64),
                ),
                CompatibilityReport(
                    status = CompatibilityStatus.COMPATIBLE,
                    manifestVersion = 3,
                    extensionName = "Tiny",
                    extensionVersion = "1.0.0",
                    declaredPermissions = emptySet(),
                    usedApis = emptySet(),
                    unsupportedApis = emptySet(),
                    issues = emptyList(),
                ),
            )
        }
    }

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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
