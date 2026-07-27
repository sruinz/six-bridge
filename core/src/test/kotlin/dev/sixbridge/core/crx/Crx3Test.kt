package dev.sixbridge.core.crx

import dev.sixbridge.contract.crx.Crx3Writer
import dev.sixbridge.core.input.ExtensionZip
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyPairGenerator
import java.security.MessageDigest
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Crx3Test {
    @Test
    fun `desktop pack은 공용 writer와 동일한 CRX3를 만든다`() {
        val source = Files.createTempDirectory("six-crx-shared-writer")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Tiny","version":"1.0.0"}""",
        )
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val packed = Crx3.pack(source, keyPair)
        val zip = ExtensionZip.createBytes(source, keyPair.public.encoded)
        val shared = Crx3Writer.create(zip, KeyPairCrx3SigningKey(keyPair))

        assertContentEquals(shared.bytes, packed.bytes)
    }

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
    fun `verified payload hash validates a CRX without copying its ZIP`() {
        val source = Files.createTempDirectory("six-crx-stream-hash")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Tiny","version":"1"}""",
        )
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            .generateKeyPair()
        val packed = Crx3.pack(source, keyPair)
        val prefix = ByteArray(17) { 3 }
        val container = prefix + packed.bytes + ByteArray(11) { 4 }

        assertEquals(
            MessageDigest.getInstance("SHA-256")
                .digest(packed.zipBytes)
                .joinToString("") { "%02x".format(it) },
            Crx3.verifiedPayloadSha256(
                container,
                prefix.size,
                packed.bytes.size,
            ),
        )

        container[prefix.size + packed.bytes.lastIndex] =
            (container[prefix.size + packed.bytes.lastIndex].toInt() xor 1).toByte()
        assertFailsWith<InvalidCrxException> {
            Crx3.verifiedPayloadSha256(container, prefix.size, packed.bytes.size)
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
