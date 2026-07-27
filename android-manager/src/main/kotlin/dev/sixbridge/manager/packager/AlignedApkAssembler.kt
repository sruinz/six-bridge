package dev.sixbridge.manager.packager

import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.CRC32

data class ApkAssemblyRequest(
    val templateApk: File,
    val manifestBytes: ByteArray,
    val crxBytes: ByteArray,
    val outputApk: File,
)

data class ApkAssemblyPathRequest(
    val templateApk: File,
    val manifestBytes: ByteArray,
    val crxFile: File,
    val outputApk: File,
)

/**
 * 앱 전용 작업 디렉터리만 publish 대상으로 허용하는 unsigned wrapper APK 조립기다.
 * Task 6은 `context.filesDir` 아래의 private working directory를 이 생성자에 전달한다.
 */
class AlignedApkAssembler(trustedOutputDirectory: Path) {
    private val trustedDirectory = TrustedDirectory.capture(trustedOutputDirectory)

    fun assemble(request: ApkAssemblyRequest) {
        assemblePrepared(
            templateFile = request.templateApk,
            manifest = request.manifestBytes,
            crx = PreparedEntry.bytes("assets/extension.crx", request.crxBytes),
            outputFile = request.outputApk,
        )
    }

    fun assemble(request: ApkAssemblyPathRequest) {
        val crx = request.crxFile.toPath().toAbsolutePath().normalize()
        require(Files.isRegularFile(crx, NOFOLLOW_LINKS) && !Files.isSymbolicLink(crx)) {
            "CRX3 file이 올바르지 않습니다."
        }
        val state = StableSource.capture(crx)
        require(state.size in 1..MAX_CRX_SIZE) { "CRX3 크기가 올바르지 않습니다." }
        val crc32 = crc32(crx, state)
        assemblePrepared(
            templateFile = request.templateApk,
            manifest = request.manifestBytes,
            crx = PreparedEntry.path("assets/extension.crx", crx, state, crc32),
            outputFile = request.outputApk,
        )
        state.verify(crx)
    }

    private fun assemblePrepared(
        templateFile: File,
        manifest: ByteArray,
        crx: PreparedEntry,
        outputFile: File,
    ) {
        val template = templateFile.toPath().toAbsolutePath().normalize()
        val output = outputFile.toPath().toAbsolutePath().normalize()
        trustedDirectory.verifyUnchanged()
        require(output.parent == trustedDirectory.path) { "output APK는 신뢰된 작업 디렉터리에 있어야 합니다." }
        require(Files.isRegularFile(template, NOFOLLOW_LINKS) && !Files.isSymbolicLink(template)) {
            "래퍼 template APK가 올바르지 않습니다."
        }
        rejectUnsafeOutput(template, output)

        require(manifest.isNotEmpty() && manifest.size.toLong() <= MAX_ENTRY_SIZE) { "AndroidManifest.xml 크기가 올바르지 않습니다." }
        require(crx.size in 1..MAX_CRX_SIZE) { "CRX3 크기가 올바르지 않습니다." }

        val templateEntries = ApkZipStructureVerifier.templateEntries(template.toFile())
        val byName = templateEntries.associateBy { it.fact.name }
        require(templateEntries.size == REQUIRED_ENTRY_NAMES.size && byName.keys == REQUIRED_ENTRY_NAMES.toSet()) {
            "래퍼 template APK entry 계약이 올바르지 않습니다."
        }
        val prepared = listOf(
            PreparedEntry.bytes("AndroidManifest.xml", manifest),
            PreparedEntry.template("classes.dex", byName.getValue("classes.dex")),
            PreparedEntry.template("resources.arsc", byName.getValue("resources.arsc")),
            crx,
        )
        require(byName.getValue("resources.arsc").fact.method == STORED) { "template resources.arsc는 STORED여야 합니다." }
        prepared.forEach { entry -> require(entry.size in 1L..MAX_ENTRY_SIZE) { "래퍼 APK entry 크기가 올바르지 않습니다: ${entry.name}" } }
        require(calculateOutputSize(prepared) <= MAX_OUTPUT_SIZE) { "래퍼 APK 출력 크기가 제한을 초과합니다." }

        val temp = Files.createTempFile(trustedDirectory.path, ".${output.fileName}.", ".tmp")
        try {
            writeArchive(temp.toFile(), template.toFile(), prepared)
            FileChannel.open(temp, WRITE).use { it.force(true) }
            verifyAssembly(temp.toFile(), crx.size)
            trustedDirectory.verifyUnchanged()
            rejectUnsafeOutput(template, output)
            Files.move(temp, output, ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun rejectUnsafeOutput(template: Path, output: Path) {
        require(!Files.exists(output, NOFOLLOW_LINKS) || !Files.isSymbolicLink(output)) { "output APK symlink는 허용하지 않습니다." }
        if (Files.exists(output, NOFOLLOW_LINKS)) {
            require(!Files.isDirectory(output, NOFOLLOW_LINKS) && !Files.isSameFile(template, output)) {
                "template APK와 output APK는 서로 달라야 합니다."
            }
        }
    }

    private fun calculateOutputSize(entries: List<PreparedEntry>): Long {
        var offset = 0L
        entries.forEach { entry ->
            val extraSize = if (entry.name == "resources.arsc") alignmentExtraSize(offset, entry.name.encodeToByteArray().size) else 0
            offset = checkedAdd(offset, LOCAL_HEADER_SIZE.toLong() + entry.name.encodeToByteArray().size + extraSize + entry.size)
        }
        val centralSize = entries.sumOf { CENTRAL_HEADER_SIZE.toLong() + it.name.encodeToByteArray().size }
        return checkedAdd(checkedAdd(offset, centralSize), EOCD_SIZE.toLong())
    }

    private fun writeArchive(output: File, template: File, entries: List<PreparedEntry>) {
        val records = ArrayList<WrittenEntry>(entries.size)
        var offset = 0L
        BufferedOutputStream(Files.newOutputStream(output.toPath(), WRITE)).use { stream ->
            entries.forEach { entry ->
                val written = writeLocalEntry(stream, template, offset, entry)
                records += written
                offset = checkedAdd(offset, written.localLength)
            }
            val centralOffset = offset
            val central = java.io.ByteArrayOutputStream()
            records.forEach { writeCentralEntry(central, it) }
            val centralBytes = central.toByteArray()
            stream.write(centralBytes)
            writeEndOfCentralDirectory(stream, records.size, centralBytes.size.toLong(), centralOffset)
            stream.flush()
        }
    }

    private fun writeLocalEntry(stream: OutputStream, template: File, offset: Long, entry: PreparedEntry): WrittenEntry {
        val name = entry.name.encodeToByteArray()
        val extra = if (entry.name == "resources.arsc") alignmentExtra(offset, name.size) else ByteArray(0)
        stream.writeLeInt(LOCAL_MAGIC)
        stream.writeLeShort(VERSION_NEEDED)
        stream.writeLeShort(0)
        stream.writeLeShort(STORED)
        stream.writeLeShort(DOS_TIME)
        stream.writeLeShort(DOS_DATE)
        stream.writeLeInt(entry.crc32)
        stream.writeLeInt(entry.size)
        stream.writeLeInt(entry.size)
        stream.writeLeShort(name.size)
        stream.writeLeShort(extra.size)
        stream.write(name)
        stream.write(extra)
        val dataOffset = checkedAdd(offset, LOCAL_HEADER_SIZE.toLong() + name.size + extra.size)
        require(entry.name != "resources.arsc" || dataOffset % 4L == 0L) { "resources.arsc 정렬 계산이 올바르지 않습니다." }
        when (val source = entry.source) {
            is EntrySource.Bytes -> writeBytesAndVerify(stream, source.bytes, entry.crc32)
            is EntrySource.Template -> ApkZipStructureVerifier.copyEntryData(template, source.entry, stream)
            is EntrySource.PathSource -> writePathAndVerify(stream, source, entry.crc32)
        }
        return WrittenEntry(entry.name, entry.size, entry.crc32, extra, offset)
    }

    private fun writeBytesAndVerify(stream: OutputStream, bytes: ByteArray, expectedCrc: Long) {
        stream.write(bytes)
        require(CRC32().apply { update(bytes) }.value == expectedCrc) { "래퍼 APK entry가 조립 중 변경되었습니다." }
    }

    private fun writePathAndVerify(
        stream: OutputStream,
        source: EntrySource.PathSource,
        expectedCrc: Long,
    ) {
        source.state.verify(source.path)
        val crc = CRC32()
        var copied = 0L
        Files.newInputStream(source.path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            while (copied < source.state.size) {
                val count = input.read(
                    buffer,
                    0,
                    minOf(buffer.size.toLong(), source.state.size - copied).toInt(),
                )
                require(count > 0) { "CRX3 file이 조립 중 잘렸습니다." }
                stream.write(buffer, 0, count)
                crc.update(buffer, 0, count)
                copied += count
            }
            require(input.read() < 0) { "CRX3 file 크기가 조립 중 변경되었습니다." }
        }
        source.state.verify(source.path)
        require(crc.value == expectedCrc) { "CRX3 file이 조립 중 변경되었습니다." }
    }

    private fun crc32(path: Path, state: StableSource): Long {
        state.verify(path)
        val crc = CRC32()
        Files.newInputStream(path, NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(STREAM_BUFFER_SIZE)
            var readTotal = 0L
            while (readTotal < state.size) {
                val count = input.read(
                    buffer,
                    0,
                    minOf(buffer.size.toLong(), state.size - readTotal).toInt(),
                )
                require(count > 0) { "CRX3 file이 잘렸습니다." }
                crc.update(buffer, 0, count)
                readTotal += count
            }
            require(input.read() < 0) { "CRX3 file 크기가 변경되었습니다." }
        }
        state.verify(path)
        return crc.value
    }

    private fun alignmentExtra(offset: Long, nameSize: Int): ByteArray {
        val payloadSize = alignmentExtraSize(offset, nameSize) - EXTRA_HEADER_SIZE
        return java.io.ByteArrayOutputStream(EXTRA_HEADER_SIZE + payloadSize).apply {
            writeLeShort(ALIGNMENT_EXTRA_ID)
            writeLeShort(payloadSize)
            write(ByteArray(payloadSize))
        }.toByteArray()
    }

    private fun alignmentExtraSize(offset: Long, nameSize: Int): Int {
        val baseDataOffset = checkedAdd(offset, LOCAL_HEADER_SIZE.toLong() + nameSize)
        return EXTRA_HEADER_SIZE + ((4L - ((baseDataOffset + EXTRA_HEADER_SIZE) % 4L)) % 4L).toInt()
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
        stream.writeLeInt(record.crc32)
        stream.writeLeInt(record.size)
        stream.writeLeInt(record.size)
        stream.writeLeShort(name.size)
        stream.writeLeShort(0)
        stream.writeLeShort(0)
        stream.writeLeShort(0)
        stream.writeLeShort(0)
        stream.writeLeInt(0)
        stream.writeLeInt(record.localOffset)
        stream.write(name)
    }

    private fun writeEndOfCentralDirectory(stream: OutputStream, count: Int, centralSize: Long, centralOffset: Long) {
        stream.writeLeInt(EOCD_MAGIC)
        stream.writeLeShort(0)
        stream.writeLeShort(0)
        stream.writeLeShort(count)
        stream.writeLeShort(count)
        stream.writeLeInt(centralSize)
        stream.writeLeInt(centralOffset)
        stream.writeLeShort(0)
    }

    private fun verifyAssembly(apk: File, expectedCrxSize: Long) {
        val facts = ApkZipStructureVerifier.inspect(apk)
        require(facts.map { it.name } == REQUIRED_ENTRY_NAMES && facts.all { it.method == STORED }) { "래퍼 APK entry 계약이 올바르지 않습니다." }
        require(facts.single { it.name == "resources.arsc" }.dataOffset % 4L == 0L) { "resources.arsc가 4-byte 정렬되지 않았습니다." }
        require(facts.single { it.name == "assets/extension.crx" }.size == expectedCrxSize) { "래퍼 APK CRX3 크기가 올바르지 않습니다." }
    }

    private fun OutputStream.writeLeShort(value: Int) {
        require(value in 0..0xffff) { "ZIP 16-bit 값이 범위를 벗어났습니다." }
        write(value and 0xff)
        write(value ushr 8 and 0xff)
    }

    private fun OutputStream.writeLeInt(value: Long) {
        require(value in 0..UINT32_MAX) { "ZIP 32-bit 값이 범위를 벗어났습니다." }
        write((value and 0xff).toInt()); write((value ushr 8 and 0xff).toInt())
        write((value ushr 16 and 0xff).toInt()); write((value ushr 24 and 0xff).toInt())
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        require(left >= 0 && right >= 0 && left <= Long.MAX_VALUE - right) { "ZIP offset 계산이 overflow했습니다." }
        return left + right
    }

    private sealed interface EntrySource {
        data class Bytes(val bytes: ByteArray) : EntrySource
        data class Template(val entry: ApkZipEntryData) : EntrySource
        data class PathSource(
            val path: Path,
            val state: StableSource,
        ) : EntrySource
    }

    private data class PreparedEntry(val name: String, val size: Long, val crc32: Long, val source: EntrySource) {
        companion object {
            fun bytes(name: String, bytes: ByteArray): PreparedEntry = PreparedEntry(
                name, bytes.size.toLong(), CRC32().apply { update(bytes) }.value, EntrySource.Bytes(bytes),
            )

            fun template(name: String, entry: ApkZipEntryData): PreparedEntry =
                PreparedEntry(name, entry.fact.size, entry.crc32, EntrySource.Template(entry))

            fun path(
                name: String,
                path: Path,
                state: StableSource,
                crc32: Long,
            ): PreparedEntry =
                PreparedEntry(name, state.size, crc32, EntrySource.PathSource(path, state))
        }
    }

    private data class StableSource(
        val fileKey: Any?,
        val size: Long,
        val modifiedMillis: Long,
    ) {
        fun verify(path: Path) {
            require(capture(path) == this) { "CRX3 file identity가 변경되었습니다." }
        }

        companion object {
            fun capture(path: Path): StableSource {
                val attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    NOFOLLOW_LINKS,
                )
                require(attributes.isRegularFile && !attributes.isSymbolicLink) {
                    "CRX3 file은 regular file이어야 합니다."
                }
                return StableSource(
                    attributes.fileKey(),
                    attributes.size(),
                    attributes.lastModifiedTime().toMillis(),
                )
            }
        }
    }

    private data class WrittenEntry(val name: String, val size: Long, val crc32: Long, val extra: ByteArray, val localOffset: Long) {
        val localLength: Long get() = LOCAL_HEADER_SIZE.toLong() + name.encodeToByteArray().size + extra.size + size
    }

    private data class TrustedDirectory(val path: Path, val realPath: Path, val fileKey: Any) {
        fun verifyUnchanged() {
            require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "신뢰된 작업 디렉터리가 변경되었습니다." }
            val current = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(current.fileKey() == fileKey && path.toRealPath(NOFOLLOW_LINKS) == realPath) {
                "신뢰된 작업 디렉터리가 변경되었습니다."
            }
        }

        companion object {
            fun capture(directory: Path): TrustedDirectory {
                val path = directory.toAbsolutePath().normalize()
                require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "신뢰된 작업 디렉터리가 올바르지 않습니다." }
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val fileKey = attributes.fileKey() ?: throw IllegalArgumentException("신뢰된 작업 디렉터리 fileKey가 없습니다.")
                return TrustedDirectory(path, path.toRealPath(NOFOLLOW_LINKS), fileKey)
            }
        }
    }

    private companion object {
        val REQUIRED_ENTRY_NAMES = listOf("AndroidManifest.xml", "classes.dex", "resources.arsc", "assets/extension.crx")
        const val MAX_CRX_SIZE = 129L * 1024L * 1024L + 12L
        const val MAX_ENTRY_SIZE = MAX_CRX_SIZE
        const val MAX_OUTPUT_SIZE = 256L * 1024L * 1024L
        const val STREAM_BUFFER_SIZE = 64 * 1024
        const val STORED = 0
        const val LOCAL_HEADER_SIZE = 30
        const val CENTRAL_HEADER_SIZE = 46
        const val EOCD_SIZE = 22
        const val EXTRA_HEADER_SIZE = 4
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
