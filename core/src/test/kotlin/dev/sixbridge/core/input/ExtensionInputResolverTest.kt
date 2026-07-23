package dev.sixbridge.core.input

import dev.sixbridge.core.crx.Crx3
import java.nio.file.Files
import java.security.KeyPairGenerator
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ExtensionInputResolverTest {
    @Test
    fun `accepts directories zip files and CRX3 files`() {
        val source = Files.createTempDirectory("six-input")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Input","version":"1"}""",
        )
        val zip = source.parent.resolve("input.zip")
        ExtensionZip.create(source, zip)
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val crxBytes = Crx3.pack(source, keyPair).bytes
        val crx = source.parent.resolve("input.crx")
        crx.writeBytes(crxBytes)
        val resolver = ExtensionInputResolver()

        val directoryInput = resolver.resolve(source.toString())
        val zipInput = resolver.resolve(zip.toString())
        val crxInput = resolver.resolve(crx.toString())

        assertEquals(ExtensionInputKind.SOURCE_DIRECTORY, directoryInput.kind)
        assertEquals(ExtensionInputKind.ZIP, zipInput.kind)
        assertTrue(zipInput.sourceRoot?.resolve("manifest.json")?.toFile()?.isFile == true)
        assertEquals(ExtensionInputKind.CRX3, crxInput.kind)
        assertContentEquals(crxBytes, crxInput.crxBytes)
    }

    @Test
    fun `recognizes a Chrome Web Store extension id and rejects Edge store automation`() {
        val resolver = ExtensionInputResolver { _, expectedId ->
            byteArrayOf(1, 2, 3).also {
                assertEquals("fchehlladkkanoekpjffcfffpfbdaalj", expectedId)
            }
        }

        val chrome = resolver.resolve(
            "https://chromewebstore.google.com/detail/video-download-to-nas/" +
                "fchehlladkkanoekpjffcfffpfbdaalj?hl=ko",
        )

        assertEquals(ExtensionInputKind.CHROME_WEB_STORE, chrome.kind)
        assertEquals("fchehlladkkanoekpjffcfffpfbdaalj", chrome.expectedExtensionId)
        assertFailsWith<UnsupportedInputException> {
            resolver.resolve("https://microsoftedge.microsoft.com/addons/detail/example/abcdefghijkl")
        }
    }
}
