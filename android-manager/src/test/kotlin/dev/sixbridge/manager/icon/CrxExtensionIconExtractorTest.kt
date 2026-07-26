package dev.sixbridge.manager.icon

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrxExtensionIconExtractorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `manifest icons에서 가장 큰 유효 PNG를 선택한다`() {
        val small = png(width = 16, height = 16, marker = 1)
        val large = png(width = 128, height = 128, marker = 2)
        val crx = crx3(
            "manifest.json" to
                """{"manifest_version":3,"icons":{"16":"icons/16.png","128":"icons/128.png"}}"""
                    .toByteArray(),
            "icons/16.png" to small,
            "icons/128.png" to large,
        )

        val result = extractor().extract(crx.inputStream())

        assertContentEquals(large, result)
        assertCacheIsEmpty()
    }

    @Test
    fun `가장 큰 후보가 누락되면 다음 유효 PNG를 선택한다`() {
        val fallback = png(width = 64, height = 64)
        val crx = crx3(
            "manifest.json" to
                """{"icons":{"128":"icons/missing.png","64":"icons/64.png"}}""".toByteArray(),
            "icons/64.png" to fallback,
        )

        assertContentEquals(fallback, extractor().extract(crx.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `가장 큰 후보가 PNG가 아니면 다음 유효 PNG를 선택한다`() {
        val fallback = png(width = 64, height = 64)
        val crx = crx3(
            "manifest.json" to
                """{"icons":{"128":"icons/not-png.bin","64":"icons/64.png"}}""".toByteArray(),
            "icons/not-png.bin" to "not png".toByteArray(),
            "icons/64.png" to fallback,
        )

        assertContentEquals(fallback, extractor().extract(crx.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `magic 또는 CRX version이 다르면 null이다`() {
        val validZip = zip("manifest.json" to """{"icons":{}}""".toByteArray())
        val wrongMagic = crx3WithZip(validZip).also { it[0] = 'X'.code.toByte() }
        val wrongVersion = crx3WithZip(validZip).also { writeLittleEndianInt(it, 4, 2) }

        assertNull(extractor().extract(wrongMagic.inputStream()))
        assertNull(extractor().extract(wrongVersion.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `header 상한은 허용하고 초과하면 null이다`() {
        val icon = png(width = 16, height = 16)
        val manifest = """{"icons":{"16":"icon.png"}}""".toByteArray()
        val validAtLimit = crx3WithZip(
            zip("manifest.json" to manifest, "icon.png" to icon),
            header = byteArrayOf(7, 8),
        )
        val overLimit = crx3WithZip(ByteArray(0), header = byteArrayOf(7, 8, 9))
        val limits = CrxIconLimits(maxHeaderBytes = 2)

        assertContentEquals(icon, extractor(limits).extract(validAtLimit.inputStream()))
        assertNull(extractor(limits).extract(overLimit.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `ZIP payload 상한은 허용하고 초과하면 null이다`() {
        val icon = png(width = 16, height = 16)
        val payload = zip(
            "manifest.json" to """{"icons":{"16":"icon.png"}}""".toByteArray(),
            "icon.png" to icon,
        )

        assertContentEquals(
            icon,
            extractor(CrxIconLimits(maxZipBytes = payload.size.toLong()))
                .extract(crx3WithZip(payload).inputStream()),
        )
        assertNull(
            extractor(CrxIconLimits(maxZipBytes = payload.size.toLong() - 1))
                .extract(crx3WithZip(payload).inputStream()),
        )
        assertCacheIsEmpty()
    }

    @Test
    fun `ZIP entry 수 상한은 허용하고 초과하면 null이다`() {
        val icon = png(width = 16, height = 16)
        val atLimit = crx3(
            "manifest.json" to """{"icons":{"16":"icon.png"}}""".toByteArray(),
            "icon.png" to icon,
        )
        val overLimit = crx3(
            "manifest.json" to """{"icons":{"16":"icon.png"}}""".toByteArray(),
            "icon.png" to icon,
            "extra" to byteArrayOf(),
        )
        val limits = CrxIconLimits(maxEntries = 2)

        assertContentEquals(icon, extractor(limits).extract(atLimit.inputStream()))
        assertNull(extractor(limits).extract(overLimit.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `manifest json이 누락되면 null이다`() {
        assertNull(extractor().extract(crx3("icon.png" to png(16, 16)).inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `manifest json이 중복이면 null이다`() {
        val manifest = """{"icons":{"16":"icon.png"}}""".toByteArray()
        val payload = rawZipWithCentralDirectory(
            "manifest.json" to manifest,
            "manifest.json" to manifest,
            "icon.png" to png(16, 16),
        )

        assertNull(extractor().extract(crx3WithZip(payload).inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `manifest 크기 상한은 허용하고 초과하면 null이다`() {
        val icon = png(16, 16)
        val manifest = """{"icons":{"16":"icon.png"}}""".toByteArray()
        val crx = crx3("manifest.json" to manifest, "icon.png" to icon)

        assertContentEquals(
            icon,
            extractor(CrxIconLimits(maxManifestBytes = manifest.size))
                .extract(crx.inputStream()),
        )
        assertNull(
            extractor(CrxIconLimits(maxManifestBytes = manifest.size - 1))
                .extract(crx.inputStream()),
        )
        assertCacheIsEmpty()
    }

    @Test
    fun `manifest JSON이 손상되면 null이다`() {
        val crx = crx3(
            "manifest.json" to """{"icons":""".toByteArray(),
            "icon.png" to png(16, 16),
        )

        assertNull(extractor().extract(crx.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `icons key가 숫자가 아니면 무시하고 다음 숫자 key를 선택한다`() {
        val expected = png(32, 32)
        val crx = crx3(
            "manifest.json" to
                """{"icons":{"largest":"missing.png","32":"icon.png"}}""".toByteArray(),
            "icon.png" to expected,
        )

        assertContentEquals(expected, extractor().extract(crx.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `icons value가 JSON string이 아니면 무시한다`() {
        val expected = png(32, 32)
        val crx = crx3(
            "manifest.json" to
                """{"icons":{"128":{"path":"bad.png"},"32":"icon.png"}}""".toByteArray(),
            "icon.png" to expected,
        )

        assertContentEquals(expected, extractor().extract(crx.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `후보 경로가 계약을 어기면 입력 전체를 거부한다`() {
        val invalidPaths = listOf(
            "/icon.png",
            "../icon.png",
            "icons/../icon.png",
            "icons\\icon.png",
            "icons\u0000/icon.png",
            "icons//icon.png",
            "icons/./icon.png",
        )

        invalidPaths.forEach { invalidPath ->
            val escapedPath = invalidPath
                .replace("\\", "\\\\")
                .replace("\u0000", "\\u0000")
            val crx = crx3(
                "manifest.json" to
                    """{"icons":{"128":"$escapedPath","16":"valid.png"}}""".toByteArray(),
                "valid.png" to png(16, 16),
            )

            assertNull(
                extractor().extract(crx.inputStream()),
                "거부해야 하는 경로: $invalidPath",
            )
            assertCacheIsEmpty()
        }
    }

    @Test
    fun `선택 icon entry가 중복이면 null이다`() {
        val manifest = """{"icons":{"16":"icon.png"}}""".toByteArray()
        val payload = rawZipWithCentralDirectory(
            "manifest.json" to manifest,
            "icon.png" to png(16, 16, marker = 1),
            "icon.png" to png(16, 16, marker = 2),
        )

        assertNull(extractor().extract(crx3WithZip(payload).inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `선택하지 않은 icon entry 중복은 추출을 막지 않는다`() {
        val expected = png(32, 32)
        val payload = rawZipWithCentralDirectory(
            "manifest.json" to
                """{"icons":{"32":"selected.png","16":"duplicate.png"}}""".toByteArray(),
            "selected.png" to expected,
            "duplicate.png" to png(16, 16, marker = 1),
            "duplicate.png" to png(16, 16, marker = 2),
        )

        assertContentEquals(expected, extractor().extract(crx3WithZip(payload).inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `icon 크기 상한은 허용하고 초과하면 다음 후보를 선택한다`() {
        val exact = png(32, 32)
        val fallback = png(16, 16)
        val manifest = """{"icons":{"32":"exact.png","16":"fallback.png"}}""".toByteArray()
        val atLimit = crx3(
            "manifest.json" to manifest,
            "exact.png" to exact,
            "fallback.png" to fallback,
        )
        val overLimit = crx3(
            "manifest.json" to manifest,
            "exact.png" to exact + byteArrayOf(99),
            "fallback.png" to fallback,
        )
        val limits = CrxIconLimits(maxIconBytes = exact.size)

        assertContentEquals(exact, extractor(limits).extract(atLimit.inputStream()))
        assertContentEquals(fallback, extractor(limits).extract(overLimit.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `PNG signature 또는 IHDR가 손상되면 다음 후보를 선택한다`() {
        val fallback = png(16, 16)
        val invalidSignature = png(64, 64).also { it[0] = 0 }
        val invalidIhdr = png(32, 32).also { it[12] = 'X'.code.toByte() }
        val crx = crx3(
            "manifest.json" to
                """{"icons":{"64":"bad-signature.png","32":"bad-ihdr.png","16":"fallback.png"}}"""
                    .toByteArray(),
            "bad-signature.png" to invalidSignature,
            "bad-ihdr.png" to invalidIhdr,
            "fallback.png" to fallback,
        )

        assertContentEquals(fallback, extractor().extract(crx.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `PNG dimension이 0이면 다음 후보를 선택한다`() {
        val fallback = png(16, 16)
        val zeroWidth = png(32, 32).also { writeBigEndianInt(it, 16, 0) }
        val zeroHeight = png(24, 24).also { writeBigEndianInt(it, 20, 0) }
        val crx = crx3(
            "manifest.json" to
                """{"icons":{"32":"zero-width.png","24":"zero-height.png","16":"fallback.png"}}"""
                    .toByteArray(),
            "zero-width.png" to zeroWidth,
            "zero-height.png" to zeroHeight,
            "fallback.png" to fallback,
        )

        assertContentEquals(fallback, extractor().extract(crx.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `PNG dimension 상한은 허용하고 초과하면 다음 후보를 선택한다`() {
        val exact = png(1_024, 1_024)
        val tooWide = png(1_025, 32)
        val fallback = png(16, 16)
        val exactCrx = crx3(
            "manifest.json" to """{"icons":{"1024":"exact.png"}}""".toByteArray(),
            "exact.png" to exact,
        )
        val overCrx = crx3(
            "manifest.json" to
                """{"icons":{"1025":"too-wide.png","16":"fallback.png"}}""".toByteArray(),
            "too-wide.png" to tooWide,
            "fallback.png" to fallback,
        )

        assertContentEquals(exact, extractor().extract(exactCrx.inputStream()))
        assertContentEquals(fallback, extractor().extract(overCrx.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `manifest icons가 없으면 null이다`() {
        assertNull(
            extractor().extract(
                crx3("manifest.json" to """{"manifest_version":3}""".toByteArray())
                    .inputStream(),
            ),
        )
        assertCacheIsEmpty()
    }

    @Test
    fun `CRX prefix가 잘리거나 header가 잘리면 null이고 임시 파일이 남지 않는다`() {
        val truncatedPrefix = "Cr24".toByteArray()
        val truncatedHeader = ByteArrayOutputStream().apply {
            write("Cr24".toByteArray())
            writeLittleEndian(3)
            writeLittleEndian(3)
            write(byteArrayOf(1, 2))
        }.toByteArray()

        assertNull(extractor().extract(truncatedPrefix.inputStream()))
        assertNull(extractor().extract(truncatedHeader.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `손상 ZIP이면 null이고 임시 파일이 남지 않는다`() {
        val corruptZip = crx3WithZip("not a zip".toByteArray())

        assertNull(extractor().extract(corruptZip.inputStream()))
        assertCacheIsEmpty()
    }

    @Test
    fun `입력 stream 취소 예외를 삼키지 않는다`() {
        val cancelledInput = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancelled")

            override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
                throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> {
            extractor().extract(cancelledInput)
        }
        assertCacheIsEmpty()
    }

    private fun extractor(limits: CrxIconLimits = CrxIconLimits()): CrxExtensionIconExtractor =
        CrxExtensionIconExtractor(cacheDirectory(), limits)

    private fun cacheDirectory(): File =
        File(temporaryFolder.root, "cache").apply { mkdirs() }

    private fun assertCacheIsEmpty() {
        val remaining = cacheDirectory().listFiles()?.toList().orEmpty()
        check(remaining.isEmpty()) {
            "임시 cache 파일이 남았습니다: ${remaining.joinToString { it.name }}"
        }
    }

    private fun crx3(vararg entries: Pair<String, ByteArray>): ByteArray =
        crx3WithZip(zip(*entries))

    private fun crx3WithZip(zip: ByteArray, header: ByteArray = byteArrayOf()): ByteArray =
        ByteArrayOutputStream().apply {
            write("Cr24".toByteArray())
            writeLittleEndian(3)
            writeLittleEndian(header.size)
            write(header)
            write(zip)
        }.toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            ZipOutputStream(bytes).use { zip ->
                entries.forEach { (name, contents) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(contents)
                    zip.closeEntry()
                }
            }
            bytes.toByteArray()
        }

    private fun rawZipWithCentralDirectory(
        vararg entries: Pair<String, ByteArray>,
    ): ByteArray {
        data class CentralRecord(
            val name: ByteArray,
            val contents: ByteArray,
            val crc: Long,
            val localHeaderOffset: Int,
        )

        val output = ByteArrayOutputStream()
        val records = entries.map { (name, contents) ->
            val nameBytes = name.toByteArray()
            val crc = CRC32().apply { update(contents) }.value
            val localHeaderOffset = output.size()
            output.writeLittleEndian(0x04034b50)
            output.writeLittleEndianShort(20)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianShort(0)
            output.writeLittleEndian(crc)
            output.writeLittleEndian(contents.size)
            output.writeLittleEndian(contents.size)
            output.writeLittleEndianShort(nameBytes.size)
            output.writeLittleEndianShort(0)
            output.write(nameBytes)
            output.write(contents)
            CentralRecord(nameBytes, contents, crc, localHeaderOffset)
        }

        val centralDirectoryOffset = output.size()
        records.forEach { record ->
            output.writeLittleEndian(0x02014b50)
            output.writeLittleEndianShort(20)
            output.writeLittleEndianShort(20)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianShort(0)
            output.writeLittleEndian(record.crc)
            output.writeLittleEndian(record.contents.size)
            output.writeLittleEndian(record.contents.size)
            output.writeLittleEndianShort(record.name.size)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianShort(0)
            output.writeLittleEndianShort(0)
            output.writeLittleEndian(0)
            output.writeLittleEndian(record.localHeaderOffset)
            output.write(record.name)
        }
        val centralDirectorySize = output.size() - centralDirectoryOffset

        output.writeLittleEndian(0x06054b50)
        output.writeLittleEndianShort(0)
        output.writeLittleEndianShort(0)
        output.writeLittleEndianShort(records.size)
        output.writeLittleEndianShort(records.size)
        output.writeLittleEndian(centralDirectorySize)
        output.writeLittleEndian(centralDirectoryOffset)
        output.writeLittleEndianShort(0)
        return output.toByteArray()
    }

    private fun png(
        width: Int,
        height: Int,
        marker: Int = 0,
    ): ByteArray =
        ByteArray(25).also { bytes ->
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4e,
                0x47,
                0x0d,
                0x0a,
                0x1a,
                0x0a,
            ).copyInto(bytes)
            writeBigEndianInt(bytes, 8, 13)
            "IHDR".toByteArray().copyInto(bytes, destinationOffset = 12)
            writeBigEndianInt(bytes, 16, width)
            writeBigEndianInt(bytes, 20, height)
            bytes[24] = marker.toByte()
        }

    private fun ByteArrayOutputStream.writeLittleEndian(value: Int) {
        writeLittleEndian(value.toLong())
    }

    private fun ByteArrayOutputStream.writeLittleEndian(value: Long) {
        repeat(Int.SIZE_BYTES) { shift ->
            write((value ushr (shift * Byte.SIZE_BITS)).toInt() and 0xff)
        }
    }

    private fun ByteArrayOutputStream.writeLittleEndianShort(value: Int) {
        repeat(Short.SIZE_BYTES) { shift ->
            write((value ushr (shift * Byte.SIZE_BITS)) and 0xff)
        }
    }

    private fun writeLittleEndianInt(target: ByteArray, offset: Int, value: Int) {
        repeat(Int.SIZE_BYTES) { shift ->
            target[offset + shift] = (value ushr (shift * Byte.SIZE_BITS)).toByte()
        }
    }

    private fun writeBigEndianInt(target: ByteArray, offset: Int, value: Int) {
        repeat(Int.SIZE_BYTES) { index ->
            val shift = (Int.SIZE_BYTES - index - 1) * Byte.SIZE_BITS
            target[offset + index] = (value ushr shift).toByte()
        }
    }
}
