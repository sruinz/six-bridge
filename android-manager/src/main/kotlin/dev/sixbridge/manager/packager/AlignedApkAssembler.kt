package dev.sixbridge.manager.packager

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.zip.CRC32

data class ApkAssemblyRequest(
    val templateApk: File,
    val manifestBytes: ByteArray,
    val crxBytes: ByteArray,
    val outputApk: File,
)

class AlignedApkAssembler {
    fun assemble(request: ApkAssemblyRequest) {
        val template = request.templateApk.toPath().toAbsolutePath().normalize()
        val output = request.outputApk.toPath().toAbsolutePath().normalize()
        require(Files.isRegularFile(template, NOFOLLOW_LINKS) && !Files.isSymbolicLink(template)) {
            "래퍼 template APK가 올바르지 않습니다."
        }
        val outputParent = output.parent ?: throw IllegalArgumentException("output APK 경로가 올바르지 않습니다.")
        require(Files.isDirectory(outputParent, NOFOLLOW_LINKS) && !Files.isSymbolicLink(outputParent)) {
            "output APK 디렉터리가 올바르지 않습니다."
        }
        rejectUnsafeOutput(template, output)

        val manifest = request.manifestBytes.copyOf()
        val crx = request.crxBytes.copyOf()
        require(manifest.isNotEmpty() && manifest.size.toLong() <= MAX_ENTRY_SIZE) { "AndroidManifest.xml 크기가 올바르지 않습니다." }
        require(crx.isNotEmpty() && crx.size.toLong() <= MAX_CRX_SIZE) { "CRX3 크기가 올바르지 않습니다." }

        val templateEntries = ApkZipStructureVerifier.entries(template.toFile())
        val byName = templateEntries.associateBy { it.fact.name }
        require(templateEntries.size == REQUIRED_ENTRY_NAMES.size && byName.keys == REQUIRED_ENTRY_NAMES.toSet()) {
            "래퍼 template APK entry 계약이 올바르지 않습니다."
        }
        require(byName.getValue("resources.arsc").fact.method == STORED) { "template resources.arsc는 STORED여야 합니다." }

        val entries = listOf(
            OutputEntry("AndroidManifest.xml", manifest),
            OutputEntry("classes.dex", byName.getValue("classes.dex").bytes),
            OutputEntry("resources.arsc", byName.getValue("resources.arsc").bytes),
            OutputEntry("assets/extension.crx", crx),
        )
        entries.forEach { entry ->
            require(entry.bytes.isNotEmpty() && entry.bytes.size.toLong() <= MAX_ENTRY_SIZE) {
                "래퍼 APK entry 크기가 올바르지 않습니다: ${entry.name}"
            }
        }

        val temp = Files.createTempFile(outputParent, ".${output.fileName}.", ".tmp")
        try {
            writeArchive(temp.toFile(), entries)
            require(Files.size(temp) <= MAX_OUTPUT_SIZE) { "래퍼 APK 출력 크기가 제한을 초과합니다." }
            FileChannel.open(temp, WRITE).use { it.force(true) }
            verifyAssembly(temp.toFile(), crx.size.toLong())
            rejectUnsafeOutput(template, output)
            Files.move(temp, output, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun rejectUnsafeOutput(template: java.nio.file.Path, output: java.nio.file.Path) {
        require(!Files.exists(output, NOFOLLOW_LINKS) || !Files.isSymbolicLink(output)) {
            "output APK symlink는 허용하지 않습니다."
        }
        if (Files.exists(output, NOFOLLOW_LINKS)) {
            require(!Files.isDirectory(output, NOFOLLOW_LINKS) && !Files.isSameFile(template, output)) {
                "template APK와 output APK는 서로 달라야 합니다."
            }
        }
    }

    private fun writeArchive(output: File, entries: List<OutputEntry>) {
        val records = ArrayList<WrittenEntry>(entries.size)
        var offset = 0L
        BufferedOutputStream(Files.newOutputStream(output.toPath(), WRITE)).use { stream ->
            entries.forEach { entry ->
                val written = writeLocalEntry(stream, offset, entry)
                records += written
                offset = checkedAdd(offset, written.localLength)
            }
            val centralOffset = offset
            val central = java.io.ByteArrayOutputStream()
            records.forEach { record -> writeCentralEntry(central, record) }
            val centralBytes = central.toByteArray()
            stream.write(centralBytes)
            offset = checkedAdd(offset, centralBytes.size.toLong())
            writeEndOfCentralDirectory(stream, records.size, centralBytes.size.toLong(), centralOffset)
            stream.flush()
            require(checkedAdd(offset, EOCD_SIZE.toLong()) <= MAX_OUTPUT_SIZE) { "래퍼 APK 출력 크기가 제한을 초과합니다." }
        }
    }

    private fun writeLocalEntry(stream: OutputStream, offset: Long, entry: OutputEntry): WrittenEntry {
        val name = entry.name.encodeToByteArray()
        val extra = if (entry.name == "resources.arsc") alignmentExtra(offset, name.size) else ByteArray(0)
        val crc = CRC32().apply { update(entry.bytes) }.value
        stream.writeLeInt(LOCAL_MAGIC)
        stream.writeLeShort(VERSION_NEEDED)
        stream.writeLeShort(0)
        stream.writeLeShort(STORED)
        stream.writeLeShort(DOS_TIME)
        stream.writeLeShort(DOS_DATE)
        stream.writeLeInt(crc)
        stream.writeLeInt(entry.bytes.size.toLong())
        stream.writeLeInt(entry.bytes.size.toLong())
        stream.writeLeShort(name.size)
        stream.writeLeShort(extra.size)
        stream.write(name)
        stream.write(extra)
        val dataOffset = checkedAdd(offset, LOCAL_HEADER_SIZE.toLong() + name.size + extra.size)
        require(entry.name != "resources.arsc" || dataOffset % 4L == 0L) { "resources.arsc 정렬 계산이 올바르지 않습니다." }
        stream.write(entry.bytes)
        return WrittenEntry(entry.name, entry.bytes, crc, extra, offset, dataOffset)
    }

    private fun alignmentExtra(offset: Long, nameSize: Int): ByteArray {
        val baseDataOffset = checkedAdd(offset, LOCAL_HEADER_SIZE.toLong() + nameSize)
        val payloadSize = ((4L - ((baseDataOffset + EXTRA_HEADER_SIZE) % 4L)) % 4L).toInt()
        return java.io.ByteArrayOutputStream(EXTRA_HEADER_SIZE.toInt() + payloadSize).apply {
            writeLeShort(ALIGNMENT_EXTRA_ID)
            writeLeShort(payloadSize)
            write(ByteArray(payloadSize))
        }.toByteArray()
    }

    private fun writeCentralEntry(stream: OutputStream, record: WrittenEntry) {
        val name = record.name.encodeToByteArray()
        stream.writeLeInt(CENTRAL_MAGIC)
        stream.writeLeShort(VERSION_MADE_BY)
        stream.writeLeShort(VERSION_NEEDED)
        stream.writeLeShort(0)
        stream.writeLeShort(STORED)
        stream.writeLeShort(DOS_TIME)
        stream.writeLeShort(DOS_DATE)
        stream.writeLeInt(record.crc)
        stream.writeLeInt(record.bytes.size.toLong())
        stream.writeLeInt(record.bytes.size.toLong())
        stream.writeLeShort(name.size)
        stream.writeLeShort(0)
        stream.writeLeShort(0)
        stream.writeLeShort(0)
        stream.writeLeShort(0)
        stream.writeLeInt(0)
        stream.writeLeInt(record.localOffset)
        stream.write(name)
    }

    private fun writeEndOfCentralDirectory(
        stream: OutputStream,
        entryCount: Int,
        centralSize: Long,
        centralOffset: Long,
    ) {
        stream.writeLeInt(EOCD_MAGIC)
        stream.writeLeShort(0)
        stream.writeLeShort(0)
        stream.writeLeShort(entryCount)
        stream.writeLeShort(entryCount)
        stream.writeLeInt(centralSize)
        stream.writeLeInt(centralOffset)
        stream.writeLeShort(0)
    }

    private fun verifyAssembly(apk: File, expectedCrxSize: Long) {
        val facts = ApkZipStructureVerifier.inspect(apk)
        require(facts.map { it.name } == REQUIRED_ENTRY_NAMES) { "래퍼 APK entry 순서가 올바르지 않습니다." }
        require(facts.all { it.method == STORED }) { "래퍼 APK entry는 모두 STORED여야 합니다." }
        val resources = facts.single { it.name == "resources.arsc" }
        require(resources.dataOffset % 4L == 0L) { "resources.arsc가 4-byte 정렬되지 않았습니다." }
        require(facts.single { it.name == "assets/extension.crx" }.size == expectedCrxSize) {
            "래퍼 APK CRX3 크기가 올바르지 않습니다."
        }
    }

    private fun OutputStream.writeLeShort(value: Int) {
        require(value in 0..0xffff) { "ZIP 16-bit 값이 범위를 벗어났습니다." }
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun OutputStream.writeLeInt(value: Long) {
        require(value in 0..UINT32_MAX) { "ZIP 32-bit 값이 범위를 벗어났습니다." }
        write((value and 0xff).toInt())
        write((value ushr 8 and 0xff).toInt())
        write((value ushr 16 and 0xff).toInt())
        write((value ushr 24 and 0xff).toInt())
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        require(left >= 0 && right >= 0 && left <= Long.MAX_VALUE - right) { "ZIP offset 계산이 overflow했습니다." }
        return left + right
    }

    private data class OutputEntry(val name: String, val bytes: ByteArray)

    private data class WrittenEntry(
        val name: String,
        val bytes: ByteArray,
        val crc: Long,
        val extra: ByteArray,
        val localOffset: Long,
        val dataOffset: Long,
    ) {
        val localLength: Long
            get() = LOCAL_HEADER_SIZE.toLong() + name.encodeToByteArray().size + extra.size + bytes.size
    }

    private companion object {
        val REQUIRED_ENTRY_NAMES = listOf(
            "AndroidManifest.xml",
            "classes.dex",
            "resources.arsc",
            "assets/extension.crx",
        )
        const val MAX_CRX_SIZE = 128L * 1024L * 1024L
        const val MAX_ENTRY_SIZE = 128L * 1024L * 1024L
        const val MAX_OUTPUT_SIZE = 256L * 1024L * 1024L
        const val STORED = 0
        const val LOCAL_HEADER_SIZE = 30
        const val EOCD_SIZE = 22
        const val EXTRA_HEADER_SIZE = 4L
        const val ALIGNMENT_EXTRA_ID = 0xd935
        const val VERSION_MADE_BY = 20
        const val VERSION_NEEDED = 20
        const val DOS_TIME = 0
        const val DOS_DATE = 0x21
        const val UINT32_MAX = 0xffffffffL
        const val LOCAL_MAGIC = 0x04034b50L
        const val CENTRAL_MAGIC = 0x02014b50L
        const val EOCD_MAGIC = 0x06054b50L
    }
}
