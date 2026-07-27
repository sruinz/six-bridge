package dev.sixbridge.manager.packager

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.CRC32

data class ApkZipEntryFact(
    val name: String,
    val method: Int,
    val dataOffset: Long,
    val size: Long,
)

object ApkZipStructureVerifier {
    fun inspect(apk: File): List<ApkZipEntryFact> = parse(apk).map { it.fact }

    internal fun entries(apk: File): List<ApkZipEntryData> = parse(apk)

    private fun parse(apk: File): List<ApkZipEntryData> {
        val path = apk.toPath()
        require(Files.isRegularFile(path) && !Files.isSymbolicLink(path)) { "APK 파일이 올바르지 않습니다." }
        val fileSize = Files.size(path)
        require(fileSize in MIN_ARCHIVE_SIZE..MAX_ARCHIVE_SIZE) { "APK ZIP 크기가 제한을 벗어났습니다." }
        val bytes = Files.readAllBytes(path)
        require(bytes.size.toLong() == fileSize) { "APK 파일 크기가 변경되었습니다." }

        val eocdOffset = bytes.size - EOCD_SIZE
        require(bytes.u32(eocdOffset) == EOCD_MAGIC) { "ZIP EOCD가 파일 끝에 없습니다." }
        require(bytes.u16(eocdOffset + 20) == 0) { "ZIP comment는 허용하지 않습니다." }
        require(bytes.u16(eocdOffset + 4) == 0 && bytes.u16(eocdOffset + 6) == 0) {
            "multi-disk ZIP은 허용하지 않습니다."
        }
        val diskEntries = bytes.u16(eocdOffset + 8)
        val entryCount = bytes.u16(eocdOffset + 10)
        require(diskEntries == entryCount && entryCount in 1..MAX_ENTRIES) { "ZIP entry 수가 올바르지 않습니다." }
        val centralSize = bytes.u32(eocdOffset + 12)
        val centralOffset = bytes.u32(eocdOffset + 16)
        require(centralSize != UINT32_MAX && centralOffset != UINT32_MAX) { "ZIP64는 허용하지 않습니다." }
        val centralEnd = checkedAdd(centralOffset, centralSize)
        require(centralEnd == eocdOffset.toLong()) { "central directory 범위가 올바르지 않습니다." }
        require(centralOffset >= 0 && centralOffset <= bytes.size) { "central directory offset이 올바르지 않습니다." }
        rejectApkSigningBlock(bytes, centralOffset)

        var cursor = centralOffset.toInt()
        val entries = ArrayList<ApkZipEntryData>(entryCount)
        val names = HashSet<String>()
        repeat(entryCount) {
            requireRange(bytes, cursor, CENTRAL_HEADER_SIZE)
            require(bytes.u32(cursor) == CENTRAL_MAGIC) { "central directory header가 올바르지 않습니다." }
            val flags = bytes.u16(cursor + 8)
            val method = bytes.u16(cursor + 10)
            val crc32 = bytes.u32(cursor + 16)
            val compressedSize = bytes.u32(cursor + 20)
            val size = bytes.u32(cursor + 24)
            val nameSize = bytes.u16(cursor + 28)
            val extraSize = bytes.u16(cursor + 30)
            val commentSize = bytes.u16(cursor + 32)
            val diskNumber = bytes.u16(cursor + 34)
            val localOffset = bytes.u32(cursor + 42)
            require(
                compressedSize != UINT32_MAX && size != UINT32_MAX && localOffset != UINT32_MAX &&
                    diskNumber == 0 && commentSize == 0
            ) { "ZIP64 또는 multi-disk entry는 허용하지 않습니다." }
            require(flags and (FLAG_ENCRYPTED or FLAG_DATA_DESCRIPTOR) == 0) {
                "암호화 또는 data descriptor ZIP entry는 허용하지 않습니다."
            }
            require(method == STORED) { "압축된 ZIP entry는 허용하지 않습니다." }
            require(compressedSize == size && size <= MAX_ENTRY_SIZE) { "ZIP entry 크기가 올바르지 않습니다." }
            val recordSize = checkedAdd(CENTRAL_HEADER_SIZE.toLong(), checkedAdd(nameSize.toLong(), extraSize.toLong()))
            require(checkedAdd(recordSize, commentSize.toLong()) <= centralEnd - cursor) { "central directory entry 범위가 올바르지 않습니다." }
            val nameBytes = bytes.copyOfRange(cursor + CENTRAL_HEADER_SIZE, cursor + CENTRAL_HEADER_SIZE + nameSize)
            val name = canonicalName(nameBytes)
            require(names.add(name)) { "중복 ZIP entry는 허용하지 않습니다: $name" }
            rejectZip64Extra(bytes, cursor + CENTRAL_HEADER_SIZE + nameSize, extraSize)

            val local = readLocalEntry(
                bytes = bytes,
                centralOffset = centralOffset,
                centralName = name,
                flags = flags,
                method = method,
                crc32 = crc32,
                size = size,
                localOffset = localOffset,
            )
            entries += ApkZipEntryData(
                fact = ApkZipEntryFact(name, method, local.dataOffset, size),
                bytes = bytes.copyOfRange(local.dataOffset.toInt(), checkedAdd(local.dataOffset, size).toInt()),
            )
            cursor = checkedAdd(cursor.toLong(), checkedAdd(recordSize, commentSize.toLong())).toInt()
        }
        require(cursor.toLong() == centralEnd) { "central directory가 정확히 끝나지 않습니다." }
        return entries
    }

    private fun readLocalEntry(
        bytes: ByteArray,
        centralOffset: Long,
        centralName: String,
        flags: Int,
        method: Int,
        crc32: Long,
        size: Long,
        localOffset: Long,
    ): LocalEntry {
        require(localOffset < centralOffset && localOffset <= Int.MAX_VALUE) { "local header offset이 올바르지 않습니다." }
        val offset = localOffset.toInt()
        requireRange(bytes, offset, LOCAL_HEADER_SIZE)
        require(bytes.u32(offset) == LOCAL_MAGIC) { "local header가 올바르지 않습니다." }
        val localFlags = bytes.u16(offset + 6)
        val localMethod = bytes.u16(offset + 8)
        val localCrc = bytes.u32(offset + 14)
        val localCompressedSize = bytes.u32(offset + 18)
        val localSize = bytes.u32(offset + 22)
        val localNameSize = bytes.u16(offset + 26)
        val localExtraSize = bytes.u16(offset + 28)
        require(
            localFlags == flags && localMethod == method && localCrc == crc32 &&
                localCompressedSize == size && localSize == size
        ) { "local header와 central directory가 일치하지 않습니다." }
        val nameStart = checkedAdd(localOffset, LOCAL_HEADER_SIZE.toLong()).toInt()
        requireRange(bytes, nameStart, checkedAdd(localNameSize.toLong(), localExtraSize.toLong()).toInt())
        val localName = canonicalName(bytes.copyOfRange(nameStart, nameStart + localNameSize))
        require(localName == centralName) { "local header entry 이름이 central directory와 다릅니다." }
        rejectZip64Extra(bytes, nameStart + localNameSize, localExtraSize)
        val dataOffset = checkedAdd(nameStart.toLong(), checkedAdd(localNameSize.toLong(), localExtraSize.toLong()))
        val dataEnd = checkedAdd(dataOffset, size)
        require(dataEnd <= centralOffset && dataEnd <= bytes.size) { "ZIP entry data 범위가 올바르지 않습니다." }
        val actualCrc = CRC32().apply { update(bytes, dataOffset.toInt(), size.toInt()) }.value
        require(actualCrc == crc32) { "ZIP entry CRC32가 일치하지 않습니다." }
        return LocalEntry(dataOffset)
    }

    private fun rejectApkSigningBlock(bytes: ByteArray, centralOffset: Long) {
        if (centralOffset < APK_SIGNING_BLOCK_FOOTER_SIZE) return
        val footer = centralOffset.toInt() - APK_SIGNING_BLOCK_FOOTER_SIZE
        val magicOffset = footer + 8
        require(
            !bytes.copyOfRange(magicOffset, magicOffset + APK_SIGNING_BLOCK_MAGIC.size)
                .contentEquals(APK_SIGNING_BLOCK_MAGIC),
        ) { "기존 APK signing block은 허용하지 않습니다." }
    }

    private fun rejectZip64Extra(bytes: ByteArray, start: Int, length: Int) {
        var cursor = start
        val end = checkedAdd(start.toLong(), length.toLong()).toInt()
        while (cursor < end) {
            if (end - cursor < 4) {
                require(bytes.copyOfRange(cursor, end).all { it == 0.toByte() }) {
                    "ZIP extra field padding이 올바르지 않습니다."
                }
                cursor = end
                break
            }
            requireRange(bytes, cursor, 4)
            val id = bytes.u16(cursor)
            val size = bytes.u16(cursor + 2)
            cursor = checkedAdd(cursor.toLong(), 4L).toInt()
            require(checkedAdd(cursor.toLong(), size.toLong()) <= end) { "ZIP extra field 범위가 올바르지 않습니다." }
            require(id != ZIP64_EXTRA_ID) { "ZIP64 extra field는 허용하지 않습니다." }
            cursor = checkedAdd(cursor.toLong(), size.toLong()).toInt()
        }
        require(cursor == end) { "ZIP extra field가 정확히 끝나지 않습니다." }
    }

    private fun canonicalName(bytes: ByteArray): String {
        val name = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IllegalArgumentException("ZIP entry 이름 UTF-8이 올바르지 않습니다.", error)
        }
        require(name.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) { "ZIP entry 이름이 canonical UTF-8이 아닙니다." }
        require(
            name.isNotEmpty() && !name.startsWith('/') && !name.contains('\\') && !name.contains('\u0000') &&
                name.split('/').all { it.isNotEmpty() && it != "." && it != ".." },
        ) { "ZIP entry 경로가 올바르지 않습니다." }
        return name
    }

    private fun requireRange(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset.toLong() + length <= bytes.size) { "ZIP 범위가 올바르지 않습니다." }
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        require(left >= 0 && right >= 0 && left <= Long.MAX_VALUE - right) { "ZIP offset 계산이 overflow했습니다." }
        return left + right
    }

    private fun ByteArray.u16(offset: Int): Int {
        requireRange(this, offset, 2)
        return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun ByteArray.u32(offset: Int): Long {
        requireRange(this, offset, 4)
        return (this[offset].toLong() and 0xff) or
            ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or
            ((this[offset + 3].toLong() and 0xff) shl 24)
    }

    private data class LocalEntry(val dataOffset: Long)

    private const val MIN_ARCHIVE_SIZE = 22L
    private const val MAX_ARCHIVE_SIZE = 256L * 1024L * 1024L
    private const val MAX_ENTRY_SIZE = 128L * 1024L * 1024L
    private const val MAX_ENTRIES = 64
    private const val STORED = 0
    private const val FLAG_ENCRYPTED = 0x0001
    private const val FLAG_DATA_DESCRIPTOR = 0x0008
    private const val LOCAL_HEADER_SIZE = 30
    private const val CENTRAL_HEADER_SIZE = 46
    private const val EOCD_SIZE = 22
    private const val APK_SIGNING_BLOCK_FOOTER_SIZE = 24
    private const val ZIP64_EXTRA_ID = 0x0001
    private const val UINT32_MAX = 0xffffffffL
    private const val LOCAL_MAGIC = 0x04034b50L
    private const val CENTRAL_MAGIC = 0x02014b50L
    private const val EOCD_MAGIC = 0x06054b50L
    private val APK_SIGNING_BLOCK_MAGIC = "APK Sig Block 42".toByteArray(StandardCharsets.US_ASCII)
}

internal data class ApkZipEntryData(
    val fact: ApkZipEntryFact,
    val bytes: ByteArray,
)
