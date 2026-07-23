package dev.sixbridge.core.profile

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BrowserProfileExtractorTest {
    @Test
    fun `extracts the Samsung extension contract and supported namespaces`() {
        val directory = Files.createTempDirectory("browser-profile")
        val apk = directory.resolve(
            "sbrowser_30.0.2.61-1300261502_minAPI30.apk",
        )
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(
                """
                six://ex extension-id .contentprovider dev_ext
                unrelated 10.200004.10.1 versionCode 999999999
                """.trimIndent().toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libterrace.so"))
            zip.write(
                """{"namespace":"storage"}{"namespace":"tabs"}{"namespace":"offscreen"}"""
                    .toByteArray(),
            )
            zip.closeEntry()
        }

        val profile = BrowserProfileExtractor().extract(apk)

        assertEquals(30, profile.majorVersion)
        assertEquals(setOf("offscreen", "storage", "tabs"), profile.supportedApis)
        assertTrue(profile.wrapperContract.discoveryUri)
        assertTrue(profile.wrapperContract.extensionMetadata)
        assertTrue(profile.wrapperContract.contentProvider)
        assertTrue(profile.wrapperContract.devExtensionPath)
        assertTrue(profile.isSupportedBaseline)
    }

    @Test
    fun `marks an incomplete or new contract unknown`() {
        val apk = Files.createTempFile("browser", ".apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write("versionName 31.0.0.1 extension-id".toByteArray())
            zip.closeEntry()
        }

        val profile = BrowserProfileExtractor().extract(apk)

        assertFalse(profile.isSupportedBaseline)
        assertEquals(ProfileSupport.UNKNOWN, profile.support)
    }

    @Test
    fun `marks a changed 30 x API profile unknown until reviewed`() {
        val apk = Files.createTempFile("browser-30.1.0.1-", ".apk")
        ZipOutputStream(Files.newOutputStream(apk)).use { zip ->
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(
                "six://ex extension-id .contentprovider dev_ext versionName 30.1.0.1"
                    .toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("lib/arm64-v8a/libterrace.so"))
            zip.write("""{"namespace":"storage"}""".toByteArray())
            zip.closeEntry()
        }

        val profile = BrowserProfileExtractor().extract(apk)

        assertEquals(ProfileSupport.UNKNOWN, profile.support)
        assertTrue(profile.profileDifferences.any { it.startsWith("missing_api:") })
    }
}
