package dev.sixbridge.core.input

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisSafetyPolicy
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
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
    fun `secure inventory 순회와 추출은 각각 새 handle을 사용한다`() {
        class SingleIteratorHandle(val id: Int) : AutoCloseable {
            var traversed = false
            var closed = false

            fun traverse() {
                check(!traversed) { "handle $id iterator reused" }
                traversed = true
            }

            override fun close() {
                check(!closed) { "handle $id closed twice" }
                closed = true
            }
        }
        val opened = mutableListOf<SingleIteratorHandle>()
        val inventoryHandles = mutableListOf<Int>()
        var extractionHandle: Int? = null
        var hookRan = false

        val result = runSecureExtractionWithFreshHandles(
            openRoot = {
                SingleIteratorHandle(opened.size + 1).also(opened::add)
            },
            requireEmpty = { handle ->
                handle.traverse()
                inventoryHandles += handle.id
            },
            hook = { hookRan = true },
            extract = { handle ->
                check(hookRan)
                extractionHandle = handle.id
                "extracted"
            },
            requireFinalInventory = { handle ->
                handle.traverse()
                inventoryHandles += handle.id
            },
        )

        assertEquals("extracted", result)
        assertEquals(listOf(1, 2, 3, 4), opened.map { it.id })
        assertEquals(listOf(1, 2, 4), inventoryHandles)
        assertEquals(3, extractionHandle)
        assertTrue(opened.all { it.closed })
    }

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
    fun `prunes excluded native subtrees before validating their entries`() {
        val source = Files.createTempDirectory("six-pruned-source")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Pruned","version":"1"}""",
        )
        val excluded = Files.createDirectories(source.resolve(".git/objects"))
        val outside = Files.createTempFile("six-pruned-outside", ".bin")
        Files.createSymbolicLink(excluded.resolve("linked-object"), outside)

        val archive = ExtensionZip.createBytes(source)
        val names = ZipInputStream(archive.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.map(ZipEntry::getName).toList()
        }

        assertEquals(listOf("manifest.json"), names)
    }

    @Test
    fun `rejects canonical aliases of excluded directory and file names`() {
        val aliases = listOf(
            ".GIT" to true,
            "．ｇｉｔ" to true,
            "NODE_MODULES" to true,
            "ｎｏｄｅ＿ｍｏｄｕｌｅｓ" to true,
            ".ds_store" to false,
            "．ＤＳ＿Ｓｔｏｒｅ" to false,
        )

        aliases.forEach { (alias, directory) ->
            val source = Files.createTempDirectory("six-excluded-alias")
            source.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"Alias","version":"1"}""",
            )
            val entry = source.resolve(alias)
            if (directory) {
                Files.createDirectories(entry).resolve("payload.js").writeText("payload")
            } else {
                entry.writeText("metadata")
            }

            val error = assertFailsWith<IllegalArgumentException> {
                ExtensionZip.createBytes(source)
            }
            assertTrue(error.message.orEmpty().contains("제외"))
        }
    }

    @Test
    fun `rejects a large excluded alias tree before traversing its contents`() {
        val source = Files.createTempDirectory("six-large-excluded-alias")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Large alias","version":"1"}""",
        )
        val alias = Files.createDirectories(source.resolve("ＮＯＤＥ＿ＭＯＤＵＬＥＳ"))
        repeat(10_100) { index ->
            alias.resolve("entry-$index.js").writeText("")
        }

        val error = assertFailsWith<IllegalArgumentException> {
            ExtensionZip.createBytes(source)
        }

        assertTrue(error.message.orEmpty().contains("제외"))
        assertFalse(error.message.orEmpty().contains("항목 수"))
    }

    @Test
    fun `stops source traversal as soon as the included entry limit is exceeded`() {
        val source = Files.createTempDirectory("six-source-entry-limit")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Entry limit","version":"1"}""",
        )
        repeat(10_000) { index ->
            source.resolve("entry-${index.toString().padStart(5, '0')}.js")
                .writeText("")
        }
        val error = assertFailsWith<IllegalArgumentException> {
            ExtensionZip.createBytes(source)
        }

        assertTrue(error.message.orEmpty().contains("감지 항목=10001"))
    }

    @Test
    fun `stops source traversal as soon as cumulative size exceeds the limit`() {
        val source = Files.createTempDirectory("six-source-total-limit")
        source.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Size limit","version":"1"}""",
        )
        repeat(5) { index ->
            Files.newByteChannel(
                source.resolve("large-$index.bin"),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.position(128L * 1024 * 1024 - 1)
                channel.write(java.nio.ByteBuffer.wrap(byteArrayOf(0)))
            }
        }
        val error = assertFailsWith<IllegalArgumentException> {
            ExtensionZip.createBytes(source)
        }

        assertTrue(error.message.orEmpty().contains("누적 바이트="))
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
    fun `rejects encrypted entries before creating output`() {
        val zip = zipWith(
            "manifest.json" to """{"name":"encrypted"}""",
        )
        markAsEncrypted(zip)
        Jimfs.newFileSystem(Configuration.unix()).use { fileSystem ->
            val destination = Files.createDirectory(fileSystem.getPath("/encrypted-output"))

            val error = assertFailsWith<IllegalArgumentException> {
                ExtensionZip.extract(zip, destination)
            }

            assertTrue(error.message.orEmpty().contains("암호화"))
            assertTrue(Files.list(destination).use { it.findAny().isEmpty })
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
        Jimfs.newFileSystem(Configuration.unix()).use { fileSystem ->
            val destination = Files.createDirectory(fileSystem.getPath("/six-nested-output"))

            val extracted = ExtensionZip.extract(zip, destination)

            assertEquals(destination, extracted)
            assertEquals("inside", Files.readString(extracted.resolve("assets/payload.js")))
        }
    }

    @Test
    fun `byte extraction supports a relative destination and creates missing parents`() {
        val bytes = Files.readAllBytes(
            zipWith(
                "manifest.json" to """{"name":"relative"}""",
                "payload.js" to "relative",
            ),
        )
        Jimfs.newFileSystem(Configuration.unix()).use { fileSystem ->
            val destinations = listOf(
                fileSystem.getPath("relative-output"),
                fileSystem.getPath("/missing/parent/output"),
            )

            destinations.forEach { destination ->
                val extracted = ExtensionZip.extract(bytes, destination)

                assertEquals(destination, extracted)
                assertEquals("relative", Files.readString(extracted.resolve("payload.js")))
            }
        }
    }

    @Test
    fun `byte extractInto supports an existing relative destination`() {
        val bytes = Files.readAllBytes(
            zipWith(
                "manifest.json" to """{"name":"relative-into"}""",
            ),
        )
        Jimfs.newFileSystem(Configuration.unix()).use { fileSystem ->
            val destination = Files.createDirectory(
                fileSystem.getPath("/work/existing-relative"),
            )
            val relativeDestination = fileSystem.getPath("existing-relative")

            val extracted = ExtensionZip.extractInto(bytes, relativeDestination)

            assertEquals(destination, extracted)
            assertTrue(Files.isRegularFile(destination.resolve("manifest.json")))
        }
    }

    @Test
    fun `extract entry scan은 caller AnalysisControl 취소 전에 output을 만들지 않는다`() {
        val zip = zipWith(
            "manifest.json" to """{"name":"cancel"}""",
            "first.js" to "first",
            "second.js" to "second",
        )
        val destination = Files.createTempDirectory("six-cancel-output")
        var checkpoints = 0
        val control = AnalysisControl(AnalysisSafetyPolicy.mobile) {
            checkpoints += 1
            if (checkpoints == 3) throw CancellationException("cancel extraction")
        }

        assertFailsWith<CancellationException> {
            ExtensionZip.extract(zip, destination, control)
        }

        assertTrue(Files.list(destination).use { it.findAny().isEmpty })
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

    private fun markAsEncrypted(zip: Path) {
        val bytes = Files.readAllBytes(zip)
        var localFound = false
        var centralFound = false
        for (offset in 0..bytes.size - 4) {
            when (littleEndianInt(bytes, offset)) {
                0x04034b50 -> {
                    bytes[offset + 6] = (bytes[offset + 6].toInt() or 1).toByte()
                    localFound = true
                }
                0x02014b50 -> {
                    bytes[offset + 8] = (bytes[offset + 8].toInt() or 1).toByte()
                    centralFound = true
                }
            }
        }
        check(localFound && centralFound)
        Files.write(zip, bytes)
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
