package dev.sixbridge.manager.packager

import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.util.zip.CRC32

data class ApkZipEntryFact(
    val name: String,
    val method: Int,
    val dataOffset: Long,
    val size: Long,
)

object ApkZipStructureVerifier {
    fun inspect(apk: File): List<ApkZipEntryFact> = parse(apk, requireContiguousLocals = true).map { it.fact }

    internal fun templateEntries(apk: File): List<ApkZipEntryData> =
        parse(apk, requireContiguousLocals = false)

    internal fun copyEntryData(apk: File, entry: ApkZipEntryData, output: OutputStream) {
        FileChannel.open(apk.toPath(), READ).use { channel ->
            var remaining = entry.fact.size
            var position = entry.fact.dataOffset
            val crc = CRC32()
            val buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE)
            while (remaining > 0) {
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                val read = channel.read(buffer, position)
                require(read > 0) { "template APK entry를 끝까지 읽을 수 없습니다." }
                output.write(buffer.array(), 0, read)
                crc.update(buffer.array(), 0, read)
                position = checkedAdd(position, read.toLong())
                remaining -= read
            }
            require(crc.value == entry.crc32) { "template APK entry가 조립 중 변경되었습니다." }
        }
    }

    private fun parse(apk: File, requireContiguousLocals: Boolean): List<ApkZipEntryData> {
        val path = apk.toPath()
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) { "APK 파일이 올바르지 않습니다." }
        val fileSize = Files.size(path)
        require(fileSize in MIN_ARCHIVE_SIZE..MAX_ARCHIVE_SIZE) { "APK ZIP 크기가 제한을 벗어났습니다." }
        FileChannel.open(path, READ).use { channel ->
            val eocdOffset = fileSize - EOCD_SIZE
            val eocd = readAt(channel, eocdOffset, EOCD_SIZE.toInt())
            require(eocd.u32(0) == EOCD_MAGIC && eocd.u16(20) == 0) { "ZIP EOCD 또는 comment가 올바르지 않습니다." }
            require(eocd.u16(4) == 0 && eocd.u16(6) == 0) { "multi-disk ZIP은 허용하지 않습니다." }
            val diskEntries = eocd.u16(8)
            val entryCount = eocd.u16(10)
            require(diskEntries == entryCount && entryCount in 1..MAX_ENTRIES) { "ZIP entry 수가 올바르지 않습니다." }
            val centralSize = eocd.u32(12)
            val centralOffset = eocd.u32(16)
            require(centralSize != UINT32_MAX && centralOffset != UINT32_MAX) { "ZIP64는 허용하지 않습니다." }
            val centralEnd = checkedAdd(centralOffset, centralSize)
            require(centralEnd == eocdOffset && centralOffset <= fileSize) { "central directory 범위가 올바르지 않습니다." }
            rejectApkSigningBlock(channel, centralOffset)

            var cursor = centralOffset
            val entries = ArrayList<ApkZipEntryData>(entryCount)
            val localRecords = ArrayList<LocalRecord>(entryCount)
            val names = HashSet<String>()
            repeat(entryCount) {
                val header = readAt(channel, cursor, CENTRAL_HEADER_SIZE)
                require(header.u32(0) == CENTRAL_MAGIC) { "central directory header가 올바르지 않습니다." }
                val flags = header.u16(8)
                val method = header.u16(10)
                val crc32 = header.u32(16)
                val compressedSize = header.u32(20)
                val size = header.u32(24)
                val nameSize = header.u16(28)
                val extraSize = header.u16(30)
                val commentSize = header.u16(32)
                val diskNumber = header.u16(34)
                val localOffset = header.u32(42)
                require(
                    compressedSize != UINT32_MAX && size != UINT32_MAX && localOffset != UINT32_MAX &&
                        diskNumber == 0 && commentSize == 0
                ) { "ZIP64 또는 multi-disk entry는 허용하지 않습니다." }
                require(flags and (FLAG_ENCRYPTED or FLAG_DATA_DESCRIPTOR) == 0) {
                    "암호화 또는 data descriptor ZIP entry는 허용하지 않습니다."
                }
                require(method == STORED && compressedSize == size && size <= MAX_ENTRY_SIZE) {
                    "ZIP entry 압축 방식 또는 크기가 올바르지 않습니다."
                }
                val recordSize = checkedAdd(CENTRAL_HEADER_SIZE.toLong(), checkedAdd(nameSize.toLong(), extraSize.toLong()))
                require(checkedAdd(cursor, checkedAdd(recordSize, commentSize.toLong())) <= centralEnd) {
                    "central directory entry 범위가 올바르지 않습니다."
                }
                val name = canonicalName(readAt(channel, checkedAdd(cursor, CENTRAL_HEADER_SIZE.toLong()), nameSize))
                require(names.add(name)) { "중복 ZIP entry는 허용하지 않습니다: $name" }
                rejectZip64Extra(readAt(channel, checkedAdd(cursor, CENTRAL_HEADER_SIZE.toLong() + nameSize), extraSize))
                val local = readLocalEntry(
                    channel, centralOffset, name, flags, method, crc32, size, localOffset,
                )
                entries += ApkZipEntryData(ApkZipEntryFact(name, method, local.dataOffset, size), crc32)
                localRecords += local
                cursor = checkedAdd(cursor, checkedAdd(recordSize, commentSize.toLong()))
            }
            require(cursor == centralEnd) { "central directory가 정확히 끝나지 않습니다." }
            verifyLocalStructure(channel, localRecords, centralOffset, requireContiguousLocals)
            return entries
        }
    }

    private fun readLocalEntry(
        channel: FileChannel,
        centralOffset: Long,
        centralName: String,
        flags: Int,
        method: Int,
        crc32: Long,
        size: Long,
        localOffset: Long,
    ): LocalRecord {
        require(localOffset < centralOffset) { "local header offset이 올바르지 않습니다." }
        val header = readAt(channel, localOffset, LOCAL_HEADER_SIZE)
        require(header.u32(0) == LOCAL_MAGIC) { "local header가 올바르지 않습니다." }
        val localFlags = header.u16(6)
        val localMethod = header.u16(8)
        val localCrc = header.u32(14)
        val localCompressedSize = header.u32(18)
        val localSize = header.u32(22)
        val localNameSize = header.u16(26)
        val localExtraSize = header.u16(28)
        require(
            localFlags == flags && localMethod == method && localCrc == crc32 &&
                localCompressedSize == size && localSize == size
        ) { "local header와 central directory가 일치하지 않습니다." }
        val nameStart = checkedAdd(localOffset, LOCAL_HEADER_SIZE.toLong())
        val localName = canonicalName(readAt(channel, nameStart, localNameSize))
        require(localName == centralName) { "local header entry 이름이 central directory와 다릅니다." }
        rejectZip64Extra(readAt(channel, checkedAdd(nameStart, localNameSize.toLong()), localExtraSize))
        val dataOffset = checkedAdd(nameStart, checkedAdd(localNameSize.toLong(), localExtraSize.toLong()))
        val dataEnd = checkedAdd(dataOffset, size)
        require(dataEnd <= centralOffset) { "ZIP entry data 범위가 올바르지 않습니다." }
        require(crc32(channel, dataOffset, size) == crc32) { "ZIP entry CRC32가 일치하지 않습니다." }
        return LocalRecord(localOffset, dataOffset, dataEnd)
    }

    private fun rejectApkSigningBlock(channel: FileChannel, centralOffset: Long) {
        if (centralOffset < APK_SIGNING_BLOCK_FOOTER_SIZE) return
        require(!readAt(channel, centralOffset - APK_SIGNING_BLOCK_MAGIC.size, APK_SIGNING_BLOCK_MAGIC.size)
            .contentEquals(APK_SIGNING_BLOCK_MAGIC)) { "기존 APK signing block은 허용하지 않습니다." }
    }

    private fun rejectZip64Extra(extra: ByteArray) {
        var cursor = 0
        while (cursor < extra.size) {
            if (extra.size - cursor < 4) {
                require(extra.copyOfRange(cursor, extra.size).all { it == 0.toByte() }) { "ZIP extra field padding이 올바르지 않습니다." }
                return
            }
            val id = extra.u16(cursor)
            val size = extra.u16(cursor + 2)
            cursor += 4
            require(cursor + size <= extra.size) { "ZIP extra field 범위가 올바르지 않습니다." }
            require(id != ZIP64_EXTRA_ID) { "ZIP64 extra field는 허용하지 않습니다." }
            cursor += size
        }
        require(cursor == extra.size) { "ZIP extra field가 정확히 끝나지 않습니다." }
    }

    private fun crc32(channel: FileChannel, offset: Long, size: Long): Long {
        var remaining = size
        var position = offset
        val buffer = ByteBuffer.allocate(COPY_BUFFER_SIZE)
        val crc = CRC32()
        while (remaining > 0) {
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
            val read = channel.read(buffer, position)
            require(read > 0) { "ZIP entry data를 끝까지 읽을 수 없습니다." }
            crc.update(buffer.array(), 0, read)
            position = checkedAdd(position, read.toLong())
            remaining -= read
        }
        return crc.value
    }

    private fun verifyLocalStructure(
        channel: FileChannel,
        localRecords: List<LocalRecord>,
        centralOffset: Long,
        requireContiguous: Boolean,
    ) {
        val records = localRecords.sortedBy { it.start }
        require(records.first().start == 0L) { "ZIP local record 앞에 gap이 있습니다." }
        records.zipWithNext().forEach { (left, right) ->
            require(right.start >= left.end) { "ZIP local record가 overlap합니다." }
            val gap = right.start - left.end
            if (requireContiguous) {
                require(gap == 0L) { "ZIP local record 사이에 gap이 있습니다." }
            } else {
                require(gap <= ZIPALIGN_MAX_PADDING && zeroRange(channel, left.end, gap)) {
                    "template ZIP local record gap이 올바르지 않습니다."
                }
            }
        }
        require(records.last().end == centralOffset) { "ZIP local record 뒤에 gap이 있습니다." }
    }

    private fun zeroRange(channel: FileChannel, offset: Long, size: Long): Boolean {
        if (size == 0L) return true
        val bytes = readAt(channel, offset, size.toInt())
        return bytes.all { it == 0.toByte() }
    }

    private fun readAt(channel: FileChannel, offset: Long, size: Int): ByteArray {
        require(size in 0..MAX_METADATA_READ && offset >= 0 && checkedAdd(offset, size.toLong()) <= channel.size()) {
            "ZIP metadata 범위가 올바르지 않습니다."
        }
        val bytes = ByteArray(size)
        var position = 0
        while (position < size) {
            val read = channel.read(ByteBuffer.wrap(bytes, position, size - position), offset + position)
            require(read > 0) { "ZIP metadata를 끝까지 읽을 수 없습니다." }
            position += read
        }
        return bytes
    }

    private fun canonicalName(bytes: ByteArray): String {
        val name = try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("ZIP entry 이름 UTF-8이 올바르지 않습니다.", error)
        }
        require(name.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) { "ZIP entry 이름이 canonical UTF-8이 아닙니다." }
        require(name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && !name.contains('\u0000') &&
            name.split('/').all { it.isNotEmpty() && it != "." && it != ".." }) { "ZIP entry 경로가 올바르지 않습니다." }
        return name
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.u32(offset: Int): Long =
        (this[offset].toLong() and 0xff) or ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or ((this[offset + 3].toLong() and 0xff) shl 24)

    private fun checkedAdd(left: Long, right: Long): Long {
        require(left >= 0 && right >= 0 && left <= Long.MAX_VALUE - right) { "ZIP offset 계산이 overflow했습니다." }
        return left + right
    }

    private data class LocalRecord(val start: Long, val dataOffset: Long, val end: Long)

    private const val MIN_ARCHIVE_SIZE = 22L
    private const val MAX_ARCHIVE_SIZE = 256L * 1024L * 1024L
    private const val MAX_ENTRY_SIZE = 128L * 1024L * 1024L
    private const val MAX_ENTRIES = 64
    private const val MAX_METADATA_READ = 128 * 1024
    private const val COPY_BUFFER_SIZE = 64 * 1024
    private const val ZIPALIGN_MAX_PADDING = 3L
    private const val STORED = 0
    private const val FLAG_ENCRYPTED = 0x0001
    private const val FLAG_DATA_DESCRIPTOR = 0x0008
    private const val LOCAL_HEADER_SIZE = 30
    private const val CENTRAL_HEADER_SIZE = 46
    private const val EOCD_SIZE = 22L
    private const val APK_SIGNING_BLOCK_FOOTER_SIZE = 24L
    private const val ZIP64_EXTRA_ID = 0x0001
    private const val UINT32_MAX = 0xffffffffL
    private const val LOCAL_MAGIC = 0x04034b50L
    private const val CENTRAL_MAGIC = 0x02014b50L
    private const val EOCD_MAGIC = 0x06054b50L
    private val APK_SIGNING_BLOCK_MAGIC = "APK Sig Block 42".toByteArray(StandardCharsets.US_ASCII)
}

internal data class ApkZipEntryData(val fact: ApkZipEntryFact, val crc32: Long)
