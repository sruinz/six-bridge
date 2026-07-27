package dev.sixbridge.manager.packager

import dev.sixbridge.contract.crx.Crx3SigningKey
import dev.sixbridge.contract.crx.Crx3Writer
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test

class AlignedApkAssemblerTest {
    private val template = File("src/main/assets/wrapper-template.apk")
    private val work = Files.createTempDirectory("six-aligned-apk-test").toFile()
    private val extensionId = "bcdefghijklmnopabcdefghijklmnopa"
    private val manifestBytes = ZipFile(template).use { zip ->
        BinaryAndroidManifestPatcher().patch(
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes(),
            WrapperManifestRequest(extensionId, 42),
        )
    }
    private val crxBytes by lazy { validCrx() }

    @Test
    fun `래퍼 APK는 네 entry를 결정적으로 조립하고 resources를 정렬한다`() {
        val first = File(work, "first.apk")
        val second = File(work, "second.apk")

        assembler().assemble(request(first))
        assembler().assemble(request(second))

        assertContentEquals(first.readBytes(), second.readBytes())
        val facts = ApkZipStructureVerifier.inspect(first)
        assertEquals(
            listOf("AndroidManifest.xml", "classes.dex", "resources.arsc", "assets/extension.crx"),
            facts.map { it.name },
        )
        val resources = facts.single { it.name == "resources.arsc" }
        assertEquals(ZipEntry.STORED, resources.method)
        assertEquals(0L, resources.dataOffset % 4L)
    }

    @Test
    fun `template의 중복 경로 이동 ZIP64 data descriptor 기존 서명과 signing block을 거부한다`() {
        val cases = listOf(
            "duplicate" to storedZip(templateEntries() + ("classes.dex" to byteArrayOf(9))),
            "traversal" to storedZip(templateEntries().map { (name, bytes) ->
                if (name == "classes.dex") "../classes.dex" to bytes else name to bytes
            }),
            "zip64" to storedZip(templateEntries(), zip64 = true),
            "descriptor" to storedZip(templateEntries(), flags = 0x08),
            "signature" to storedZip(templateEntries() + ("META-INF/CERT.SF" to byteArrayOf(1))),
            "signing-block" to addSigningBlock(storedZip(templateEntries())),
        )

        cases.forEachIndexed { index, (_, bytes) ->
            val malformed = File(work, "malformed-$index.apk").apply { writeBytes(bytes) }
            assertFailsWith<IllegalArgumentException> {
                assembler().assemble(request(File(work, "bad-$index.apk"), malformed))
            }
        }
    }

    @Test
    fun `classes 또는 resources가 누락된 template을 거부한다`() {
        listOf("classes.dex", "resources.arsc").forEachIndexed { index, missing ->
            val malformed = File(work, "missing-$index.apk").apply {
                writeBytes(storedZip(templateEntries().filterNot { it.first == missing }))
            }
            assertFailsWith<IllegalArgumentException> {
                assembler().assemble(request(File(work, "missing-out-$index.apk"), malformed))
            }
        }
    }

    @Test
    fun `128 MiB source와 최대 header 예산을 초과하는 CRX는 거부한다`() {
        val output = File(work, "too-large.apk")
        val tooLarge = File(work, "too-large.crx")
        RandomAccessFile(tooLarge, "rw").use { it.setLength(MAX_CRX_BYTES.toLong() + 1) }

        assertFailsWith<IllegalArgumentException> {
            assembler().assemble(
                ApkAssemblyPathRequest(template, manifestBytes, tooLarge, output),
            )
        }
        assertTrue(!output.exists())
    }

    @Test
    fun `output symlink와 template output 동일 파일을 거부한다`() {
        val symlink = File(work, "output-link.apk")
        Files.createSymbolicLink(symlink.toPath(), File(work, "target.apk").toPath())
        assertFailsWith<IllegalArgumentException> {
            assembler().assemble(request(symlink))
        }

        assertFailsWith<IllegalArgumentException> {
            assembler().assemble(request(template))
        }
    }

    @Test
    fun `조립 실패는 기존 output을 보존한다`() {
        val output = File(work, "preserve.apk").apply { writeText("keep-this-output") }
        val malformed = File(work, "preserve-template.apk").apply {
            writeBytes(storedZip(templateEntries().map { (name, bytes) ->
                if (name == "classes.dex") "../classes.dex" to bytes else name to bytes
            }))
        }

        assertFailsWith<IllegalArgumentException> {
            assembler().assemble(request(output, malformed))
        }
        assertEquals("keep-this-output", output.readText())
    }

    @Test
    fun `local record gap overlap EOCD comment CRC mismatch malformed extra와 invalid UTF 8을 거부한다`() {
        val entries = templateEntries().map { it.first to byteArrayOf(1) }
        val leadingGap = storedZip(entries, leadingGap = 1)
        val middleGap = storedZip(entries, gaps = listOf(0, 1, 0))
        val trailingGap = storedZip(entries, trailingGap = 1)
        val overlap = storedZip(entries).also { bytes ->
            val central = bytes.leInt(bytes.size - 22 + 16).toInt()
            bytes.putLeInt(central + 46 + entries.first().first.encodeToByteArray().size + 42, 0)
        }
        val comment = storedZip(entries).also { it.putLeShort(it.size - 2, 1) }
        val centralCrcMismatch = storedZip(entries).also { bytes ->
            val central = bytes.leInt(bytes.size - 22 + 16).toInt()
            bytes[central + 16] = (bytes[central + 16].toInt() xor 1).toByte()
        }
        val localCrcMismatch = storedZip(entries).also { bytes -> bytes[14] = (bytes[14].toInt() xor 1).toByte() }
        val malformedExtra = storedZip(entries, localExtra = byteArrayOf(1, 0, 4))
        val invalidUtf8 = storedZip(entries).also { bytes ->
            val central = bytes.leInt(bytes.size - 22 + 16).toInt()
            bytes[central + 46] = 0xff.toByte()
        }

        listOf(leadingGap, middleGap, trailingGap, overlap, comment, centralCrcMismatch, localCrcMismatch, malformedExtra, invalidUtf8)
            .forEachIndexed { index, bytes ->
                val file = File(work, "structure-$index.apk").apply { writeBytes(bytes) }
                assertFailsWith<IllegalArgumentException> { ApkZipStructureVerifier.inspect(file) }
            }
    }

    @Test
    fun `최대 합계 출력은 temp 생성 전에 거부하고 기존 output을 보존한다`() {
        val output = File(work, "size-boundary.apk").apply { writeText("keep") }
        val oversizedManifest = ByteArray(MAX_CRX_BYTES)
        val maximumCrx = ByteArray(MAX_CRX_BYTES)

        assertFailsWith<IllegalArgumentException> {
            assembler().assemble(ApkAssemblyRequest(template, oversizedManifest, maximumCrx, output))
        }
        assertEquals("keep", output.readText())
        assertTrue(work.listFiles().orEmpty().none { it.name.startsWith(".size-boundary.apk.") })
    }

    @Test
    fun `신뢰 작업 디렉터리 교체는 publish를 거부하고 기존 output을 보존한다`() {
        val trusted = Files.createTempDirectory("six-trusted-parent")
        val output = trusted.resolve("output.apk").toFile().apply { writeText("keep") }
        val assembler = AlignedApkAssembler(trusted)
        val original = trusted.resolveSibling("original-${System.nanoTime()}")
        val replacement = trusted.resolveSibling("replacement-${System.nanoTime()}")
        Files.move(trusted, original)
        Files.createDirectory(replacement)
        Files.move(replacement, trusted)
        trusted.resolve("output.apk").toFile().writeText("keep")

        assertFailsWith<IllegalArgumentException> { assembler.assemble(request(output)) }
        assertEquals("keep", output.readText())
    }

    private fun assembler() = AlignedApkAssembler(work.toPath())

    private fun request(output: File, source: File = template) = ApkAssemblyRequest(
        templateApk = source,
        manifestBytes = manifestBytes,
        crxBytes = crxBytes,
        outputApk = output,
    )

    private fun templateEntries(): List<Pair<String, ByteArray>> = ZipFile(template).use { zip ->
        listOf("AndroidManifest.xml", "classes.dex", "resources.arsc", "assets/extension.crx").map { name ->
            name to zip.getInputStream(zip.getEntry(name)).readBytes()
        }
    }

    private fun validCrx(): ByteArray {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val zipBytes = ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("{\"manifest_version\":3}".encodeToByteArray())
                zip.closeEntry()
            }
            buffer.toByteArray()
        }
        return Crx3Writer.create(zipBytes, object : Crx3SigningKey {
            override fun publicKeyDer(): ByteArray = pair.public.encoded

            override fun signSha256Rsa(payload: ByteArray): ByteArray = Signature.getInstance("SHA256withRSA")
                .run {
                    initSign(pair.private)
                    update(payload)
                    sign()
                }
        }).bytes
    }

    private fun storedZip(
        entries: List<Pair<String, ByteArray>>,
        flags: Int = 0,
        zip64: Boolean = false,
        leadingGap: Int = 0,
        gaps: List<Int> = emptyList(),
        trailingGap: Int = 0,
        localExtra: ByteArray = ByteArray(0),
    ): ByteArray {
        val local = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        local.write(ByteArray(leadingGap))
        entries.forEachIndexed { index, (name, bytes) ->
            if (index > 0) local.write(ByteArray(gaps.getOrElse(index - 1) { 0 }))
            val nameBytes = name.encodeToByteArray()
            val crc = CRC32().apply { update(bytes) }.value
            val offset = local.size()
            local.writeLeInt(0x04034b50)
            local.writeLeShort(20)
            local.writeLeShort(flags)
            local.writeLeShort(ZipEntry.STORED)
            local.writeLeShort(0)
            local.writeLeShort(0x21)
            local.writeLeInt(crc)
            local.writeLeInt(bytes.size.toLong())
            local.writeLeInt(bytes.size.toLong())
            local.writeLeShort(nameBytes.size)
            local.writeLeShort(localExtra.size)
            local.write(nameBytes)
            local.write(localExtra)
            local.write(bytes)

            central.writeLeInt(0x02014b50)
            central.writeLeShort(20)
            central.writeLeShort(20)
            central.writeLeShort(flags)
            central.writeLeShort(ZipEntry.STORED)
            central.writeLeShort(0)
            central.writeLeShort(0x21)
            central.writeLeInt(crc)
            central.writeLeInt(bytes.size.toLong())
            central.writeLeInt(bytes.size.toLong())
            central.writeLeShort(nameBytes.size)
            central.writeLeShort(0)
            central.writeLeShort(0)
            central.writeLeShort(0)
            central.writeLeShort(0)
            central.writeLeInt(0)
            central.writeLeInt(offset.toLong())
            central.write(nameBytes)
        }
        return ByteArrayOutputStream().use { out ->
            val centralOffset = local.size()
            out.write(local.toByteArray())
            out.write(ByteArray(trailingGap))
            val adjustedCentralOffset = centralOffset + trailingGap
            out.write(central.toByteArray())
            out.writeLeInt(0x06054b50)
            out.writeLeShort(0)
            out.writeLeShort(0)
            out.writeLeShort(if (zip64) 0xffff else entries.size)
            out.writeLeShort(if (zip64) 0xffff else entries.size)
            out.writeLeInt(central.size().toLong())
            out.writeLeInt(adjustedCentralOffset.toLong())
            out.writeLeShort(0)
            out.toByteArray()
        }
    }

    private fun addSigningBlock(zip: ByteArray): ByteArray {
        val eocd = zip.size - 22
        val centralOffset = zip.leInt(eocd + 16).toInt()
        val block = ByteArrayOutputStream().apply {
            writeLeLong(24)
            writeLeLong(24)
            write("APK Sig Block 42".encodeToByteArray())
        }.toByteArray()
        return ByteArrayOutputStream().use { out ->
            out.write(zip, 0, centralOffset)
            out.write(block)
            out.write(zip, centralOffset, zip.size - centralOffset)
            val result = out.toByteArray()
            ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(eocd + block.size + 16, centralOffset + block.size)
            result
        }
    }

    private fun ByteArrayOutputStream.writeLeShort(value: Int) {
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun ByteArrayOutputStream.writeLeInt(value: Long) {
        write((value and 0xff).toInt())
        write((value ushr 8 and 0xff).toInt())
        write((value ushr 16 and 0xff).toInt())
        write((value ushr 24 and 0xff).toInt())
    }

    private fun ByteArrayOutputStream.writeLeLong(value: Long) {
        repeat(8) { shift -> write((value ushr (shift * 8) and 0xff).toInt()) }
    }

    private fun ByteArray.leInt(offset: Int): Long =
        ByteBuffer.wrap(this, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL

    private companion object {
        const val MAX_CRX_BYTES = 129 * 1024 * 1024 + 12
    }
}
    private fun ByteArray.putLeInt(offset: Int, value: Int) {
        repeat(4) { shift -> this[offset + shift] = (value ushr (shift * 8)).toByte() }
    }

    private fun ByteArray.putLeShort(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }
