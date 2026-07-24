package dev.sixbridge.core.input

import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExtensionZipTest {
    @Test
    fun `creates byte-identical sorted stored archives`() {
        val source = Files.createTempDirectory("six-deterministic-zip")
        source.resolve("z.txt").writeText("z")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"ZIP","version":"1"}""",
        )
        source.resolve("a.txt").writeText("a")

        val first = ExtensionZip.createBytes(source)
        val second = ExtensionZip.createBytes(source)

        assertContentEquals(first, second)
        ZipInputStream(first.inputStream()).use { zip ->
            val entries = generateSequence { zip.nextEntry }
                .map { Triple(it.name, it.method, it.time) }
                .toList()
            assertEquals(listOf("a.txt", "manifest.json", "z.txt"), entries.map { it.first })
            assertEquals(List(3) { ZipEntry.STORED }, entries.map { it.second })
            assertEquals(List(3) { 0L }, entries.map { it.third })
        }
    }

    @Test
    fun `rejects escaping case-colliding and normalized duplicate zip paths`() {
        val escaping = zipWith(
            "../manifest.json" to """{"name":"escape"}""",
        )
        val caseCollision = zipWith(
            "manifest.json" to """{"name":"case"}""",
            "Scripts/app.js" to "one",
            "scripts/other.js" to "two",
        )
        val normalizedDuplicate = zipWith(
            "manifest.json" to """{"name":"normalized"}""",
            "caf\u00e9.js" to "one",
            "cafe\u0301.js" to "two",
        )

        listOf(escaping, caseCollision, normalizedDuplicate).forEach { zip ->
            assertFailsWith<IllegalArgumentException> {
                ExtensionZip.extract(zip, Files.createTempDirectory("six-invalid-zip-output"))
            }
        }
    }

    @Test
    fun `rejects dot-segment aliases instead of overwriting an extracted path`() {
        val zip = zipWith(
            "manifest.json" to """{"name":"original"}""",
            "assets/../manifest.json" to """{"name":"overwrite"}""",
        )

        assertFailsWith<IllegalArgumentException> {
            ExtensionZip.extract(zip, Files.createTempDirectory("six-alias-zip-output"))
        }
    }

    @Test
    fun `rejects unix symlink entries declared by zip metadata`() {
        val zip = zipWith(
            "manifest.json" to """{"name":"symlink"}""",
            "linked.js" to "manifest.json",
        )
        markAsUnixSymlink(zip, "linked.js")

        assertFailsWith<IllegalArgumentException> {
            ExtensionZip.extract(zip, Files.createTempDirectory("six-symlink-zip-output"))
        }
    }

    @Test
    fun `does not accept a fake empty EOCD inside a zip comment`() {
        val zip = zipWith(
            "manifest.json" to """{"name":"symlink-comment"}""",
            "linked.js" to "manifest.json",
        )
        markAsUnixSymlink(zip, "linked.js")
        appendFakeEocdComment(zip)

        val error = assertFailsWith<IllegalArgumentException> {
            ExtensionZip.extract(zip, Files.createTempDirectory("six-fake-eocd-output"))
        }
        assertTrue(error.message.orEmpty().contains("특수 파일"))
    }

    @Test
    fun `rejects Greek sigma case-fold collisions in zip and source paths`() {
        val zip = zipWith(
            "manifest.json" to """{"name":"sigma-zip"}""",
            "\u03c3.js" to "normal sigma",
            "\u03c2.js" to "final sigma",
        )
        assertFailsWith<IllegalArgumentException> {
            ExtensionZip.extract(zip, Files.createTempDirectory("six-sigma-zip-output"))
        }

        val sourceZip = Files.createTempFile("six-sigma-source", ".zip")
        Files.delete(sourceZip)
        FileSystems.newFileSystem(
            URI.create("jar:${sourceZip.toUri()}"),
            mapOf("create" to "true"),
        ).use { fileSystem ->
            val source = fileSystem.getPath("/")
            source.resolve("manifest.json").writeText("""{"name":"sigma-source"}""")
            source.resolve("\u03c3.js").writeText("normal sigma")
            source.resolve("\u03c2.js").writeText("final sigma")
            assertFailsWith<IllegalArgumentException> {
                ExtensionZip.createBytes(source)
            }
        }
    }

    @Test
    fun `does not overwrite an existing extraction output`() {
        val zip = zipWith("manifest.json" to """{"name":"new"}""")
        val destination = Files.createTempDirectory("six-existing-output")
        val existing = destination.resolve("manifest.json")
        existing.writeText("""{"name":"existing"}""")

        assertFailsWith<Exception> {
            ExtensionZip.extract(zip, destination)
        }

        assertEquals("""{"name":"existing"}""", Files.readString(existing))
    }

    @Test
    fun `extracts a normal nested file`() {
        val zip = zipWith(
            "manifest.json" to """{"name":"nested"}""",
            "assets/payload.js" to "inside",
        )
        val destination = Files.createTempDirectory("six-nested-output")

        val extracted = ExtensionZip.extract(zip, destination)

        assertEquals(destination, extracted)
        assertEquals("inside", Files.readString(extracted.resolve("assets/payload.js")))
    }

    @Test
    fun `rejects a symlink directory below the extraction destination`() {
        val zip = zipWith(
            "manifest.json" to """{"name":"nested-symlink"}""",
            "assets/payload.js" to "outside",
        )
        val destination = Files.createTempDirectory("six-nested-symlink-output")
        val external = Files.createTempDirectory("six-nested-symlink-target")
        Files.createSymbolicLink(destination.resolve("assets"), external)

        try {
            assertFailsWith<IllegalArgumentException> {
                ExtensionZip.extract(zip, destination)
            }
        } finally {
            assertFalse(Files.exists(external.resolve("payload.js")))
        }
    }

    @Test
    fun `rejects an extraction destination that is itself a symlink`() {
        val zip = zipWith("manifest.json" to """{"name":"destination-symlink"}""")
        val parent = Files.createTempDirectory("six-destination-symlink-parent")
        val external = Files.createTempDirectory("six-destination-symlink-target")
        val destination = parent.resolve("output")
        Files.createSymbolicLink(destination, external)

        try {
            assertFailsWith<IllegalArgumentException> {
                ExtensionZip.extract(zip, destination)
            }
        } finally {
            assertFalse(Files.exists(external.resolve("manifest.json")))
        }
    }

    private fun zipWith(vararg entries: Pair<String, String>): Path =
        Files.createTempFile("six-extension", ".zip").also { path ->
            ZipOutputStream(Files.newOutputStream(path)).use { zip ->
                entries.forEach { (name, value) ->
                    zip.putNextEntry(ZipEntry(name).apply { time = 0L })
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
        }

    private fun markAsUnixSymlink(zip: Path, entryName: String) {
        val bytes = Files.readAllBytes(zip)
        var offset = 0
        while (offset <= bytes.size - 46) {
            if (littleEndianInt(bytes, offset) != 0x02014b50) {
                offset += 1
                continue
            }
            val nameLength = littleEndianShort(bytes, offset + 28)
            val extraLength = littleEndianShort(bytes, offset + 30)
            val commentLength = littleEndianShort(bytes, offset + 32)
            val name = bytes.copyOfRange(offset + 46, offset + 46 + nameLength).decodeToString()
            if (name == entryName) {
                bytes[offset + 5] = 3
                val attributes = 0xA1FF0000L
                repeat(4) { index ->
                    bytes[offset + 38 + index] =
                        ((attributes ushr (index * 8)) and 0xff).toByte()
                }
                Files.write(zip, bytes)
                return
            }
            offset += 46 + nameLength + extraLength + commentLength
        }
        error("central directory entry를 찾지 못했습니다: $entryName")
    }

    private fun appendFakeEocdComment(zip: Path) {
        val bytes = Files.readAllBytes(zip)
        val eocdOffset = (bytes.size - 22 downTo 0)
            .first { littleEndianInt(bytes, it) == 0x06054b50 }
        val fakeEocd = ByteArray(22).apply {
            this[0] = 0x50
            this[1] = 0x4b
            this[2] = 0x05
            this[3] = 0x06
        }
        bytes[eocdOffset + 20] = fakeEocd.size.toByte()
        bytes[eocdOffset + 21] = 0
        Files.write(zip, bytes + fakeEocd)
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        littleEndianShort(bytes, offset) or
            (littleEndianShort(bytes, offset + 2) shl 16)
}
