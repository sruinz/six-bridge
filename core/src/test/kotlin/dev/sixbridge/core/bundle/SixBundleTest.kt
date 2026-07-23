package dev.sixbridge.core.bundle

import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import java.nio.file.Files
import java.util.zip.ZipFile
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
            crxSha256 = "deadbeef",
            wrapperPackage = "dev.sixbridge.ext.abcdefghijklmnopabcdefghijklmnop",
            wrapperVersionCode = 7,
            signerSha256 = "cafebabe",
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
}
