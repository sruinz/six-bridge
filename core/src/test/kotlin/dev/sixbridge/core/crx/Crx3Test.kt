package dev.sixbridge.core.crx

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Crx3Test {
    @Test
    fun `packs verifies and parses a CRX3 archive`() {
        val source = Files.createTempDirectory("six-crx-source")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Tiny","version":"1.0.0"}""",
        )
        source.resolve("background.js").writeText("chrome.runtime.getManifest();")
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val packed = Crx3.pack(source, keyPair)
        val parsed = Crx3.parse(packed.bytes)

        assertEquals(packed.extensionId, parsed.extensionId)
        assertContentEquals(packed.zipBytes, parsed.zipBytes)
        assertContentEquals(keyPair.public.encoded, parsed.publicKey)
        assertTrue(parsed.signaturesVerified)
        assertEquals(32, parsed.extensionId.length)
        assertTrue(parsed.extensionId.all { it in 'a'..'p' })
    }

    @Test
    fun `rejects a damaged signed payload`() {
        val source = Files.createTempDirectory("six-crx-damaged")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Tiny","version":"1"}""",
        )
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val packed = Crx3.pack(source, keyPair).bytes.copyOf()
        packed[packed.lastIndex] = (packed.last().toInt() xor 0xff).toByte()

        assertFailsWith<InvalidCrxException> {
            Crx3.parse(packed)
        }
    }

    @Test
    fun `the persisted extension key keeps the CRX id stable`() {
        val keyRoot = Files.createTempDirectory("six-crx-keys")
        val manager = ExtensionKeyManager(requireNotNull(keyRoot.parent), keyRoot)

        val first = manager.getOrCreate("smart-context-menu")
        val second = manager.getOrCreate("smart-context-menu")

        assertContentEquals(first.private.encoded, second.private.encoded)
        assertEquals(
            Crx3.extensionId(first.public.encoded),
            Crx3.extensionId(second.public.encoded),
        )
        assertEquals(
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
            ),
            Files.getPosixFilePermissions(
                keyRoot.listDirectoryEntries("id-*.pk8").single(),
            ),
        )
    }
}
