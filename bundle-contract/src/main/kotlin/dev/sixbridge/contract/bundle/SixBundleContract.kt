package dev.sixbridge.contract.bundle

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object SixBundleContract {
    const val EXTENSION_ENTRY = "extension.json"
    const val COMPATIBILITY_ENTRY = "compatibility.json"
    const val WRAPPER_ENTRY = "wrapper.apk"
    const val MAX_METADATA_SIZE: Long = 1024L * 1024
    const val MAX_WRAPPER_SIZE: Long = 128L * 1024 * 1024
    const val MAX_TOTAL_SIZE: Long = MAX_WRAPPER_SIZE + 2 * MAX_METADATA_SIZE
    const val MAX_ARCHIVE_SIZE: Long = MAX_TOTAL_SIZE + 4L * 1024 * 1024
    const val MAX_COMPRESSION_RATIO: Double = 100.0

    fun read(input: InputStream): ContractBundleContents {
        val archive = stageArchive(input)
        try {
            val centralDirectory = validateArchiveBoundary(archive)
            ZipFile(archive.file).use { zip ->
                val entries = validateEntries(zip, centralDirectory)
                val extensionBytes = readEntry(
                    zip,
                    entries.getValue(EXTENSION_ENTRY),
                    MAX_METADATA_SIZE,
                )
                val compatibilityBytes = readEntry(
                    zip,
                    entries.getValue(COMPATIBILITY_ENTRY),
                    MAX_METADATA_SIZE,
                )
                val wrapper = readEntry(zip, entries.getValue(WRAPPER_ENTRY), MAX_WRAPPER_SIZE)
                val extension = BundleJson.decodeExtension(extensionBytes.decodeToString())
                val compatibility = BundleJson.decodeCompatibility(compatibilityBytes.decodeToString())
                validateContents(wrapper, extension, compatibility)
                return ContractBundleContents(wrapper, extension, compatibility)
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("SixBundle을 읽을 수 없습니다: ${error.message}", error)
        } finally {
            deleteTemporaryFile(archive.file)
        }
    }

    fun write(output: OutputStream, contents: ContractBundleContents) {
        require(contents.compatibility.schemaVersion in 3..4) {
            "SixBundle writer는 compatibility schema v3 또는 v4만 기록합니다."
        }
        val extension = BundleJson.encodeExtension(contents.extension).encodeToByteArray()
        val compatibility = BundleJson.encodeCompatibility(contents.compatibility).encodeToByteArray()
        require(extension.size.toLong() <= MAX_METADATA_SIZE) { "extension.json 크기가 제한을 초과합니다." }
        require(compatibility.size.toLong() <= MAX_METADATA_SIZE) { "compatibility.json 크기가 제한을 초과합니다." }
        require(contents.wrapperApk.size.toLong() + extension.size + compatibility.size <= MAX_TOTAL_SIZE) {
            "SixBundle 압축 해제 크기가 제한을 초과합니다."
        }
        validateContents(contents.wrapperApk, contents.extension, contents.compatibility)
        ZipOutputStream(output).use { zip ->
            add(zip, WRAPPER_ENTRY, contents.wrapperApk)
            add(zip, EXTENSION_ENTRY, extension)
            add(zip, COMPATIBILITY_ENTRY, compatibility)
        }
    }

    private fun stageArchive(input: InputStream): TemporaryArchive {
        val file = File.createTempFile(TEMP_FILE_PREFIX, ".zip")
        var size = 0L
        try {
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    size += read
                    require(size <= MAX_ARCHIVE_SIZE) { "SixBundle archive 크기가 제한을 초과합니다." }
                    output.write(buffer, 0, read)
                }
            }
            return TemporaryArchive(file, size)
        } catch (error: Throwable) {
            deleteTemporaryFile(file)
            throw error
        }
    }

    private fun validateArchiveBoundary(archive: TemporaryArchive): List<CentralDirectoryEntry> {
        require(archive.file.length() == archive.size) { "SixBundle archive 크기가 변경되었습니다." }
        RandomAccessFile(archive.file, "r").use { file ->
            val endOffset = findEndOfCentralDirectory(file, archive.size)
            file.seek(endOffset + 4)
            val diskNumber = file.readUInt16()
            val centralDirectoryDisk = file.readUInt16()
            val entriesOnDisk = file.readUInt16()
            val entryCount = file.readUInt16()
            val centralDirectorySize = file.readUInt32()
            val centralDirectoryOffset = file.readUInt32()
            val commentLength = file.readUInt16()
            require(endOffset + END_OF_CENTRAL_DIRECTORY_SIZE + commentLength == archive.size) {
                "SixBundle archive 뒤에 허용되지 않은 trailing data가 있습니다."
            }
            require(diskNumber == 0 && centralDirectoryDisk == 0 && entriesOnDisk == entryCount) {
                "multi-disk SixBundle archive는 지원하지 않습니다."
            }
            require(entryCount != ZIP16_SENTINEL && centralDirectorySize != ZIP32_SENTINEL &&
                centralDirectoryOffset != ZIP32_SENTINEL) {
                "ZIP64 SixBundle archive는 지원하지 않습니다."
            }
            require(centralDirectoryOffset + centralDirectorySize == endOffset) {
                "SixBundle central directory 범위가 올바르지 않습니다."
            }
            return parseCentralDirectory(file, centralDirectoryOffset, endOffset, entryCount)
        }
    }

    private fun findEndOfCentralDirectory(file: RandomAccessFile, size: Long): Long {
        require(size >= END_OF_CENTRAL_DIRECTORY_SIZE) { "SixBundle central directory가 없습니다." }
        val firstOffset = maxOf(0L, size - MAX_END_OF_CENTRAL_DIRECTORY_SEARCH)
        for (offset in size - END_OF_CENTRAL_DIRECTORY_SIZE downTo firstOffset) {
            file.seek(offset)
            if (file.readUInt32() == END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                file.seek(offset + 20)
                val commentLength = file.readUInt16()
                if (offset + END_OF_CENTRAL_DIRECTORY_SIZE + commentLength == size) return offset
            }
        }
        throw IllegalArgumentException("SixBundle central directory 또는 end record가 없습니다.")
    }

    private fun parseCentralDirectory(
        file: RandomAccessFile,
        offset: Long,
        endOffset: Long,
        entryCount: Int,
    ): List<CentralDirectoryEntry> {
        var position = offset
        val entries = ArrayList<CentralDirectoryEntry>(entryCount)
        repeat(entryCount) {
            require(position + CENTRAL_DIRECTORY_HEADER_SIZE <= endOffset) {
                "SixBundle central directory 항목이 잘렸습니다."
            }
            file.seek(position)
            require(file.readUInt32() == CENTRAL_DIRECTORY_SIGNATURE) {
                "SixBundle central directory signature가 올바르지 않습니다."
            }
            file.readUInt16() // version made by
            file.readUInt16() // version needed
            val flags = file.readUInt16()
            val method = file.readUInt16()
            file.readUInt16() // modified time
            file.readUInt16() // modified date
            val crc = file.readUInt32()
            val compressedSize = file.readUInt32()
            val size = file.readUInt32()
            val nameLength = file.readUInt16()
            val extraLength = file.readUInt16()
            val entryCommentLength = file.readUInt16()
            val diskStart = file.readUInt16()
            file.readUInt16() // internal attributes
            file.readUInt32() // external attributes
            val localHeaderOffset = file.readUInt32()
            require(compressedSize != ZIP32_SENTINEL && size != ZIP32_SENTINEL &&
                localHeaderOffset != ZIP32_SENTINEL && diskStart != ZIP16_SENTINEL) {
                "ZIP64 SixBundle 항목은 지원하지 않습니다."
            }
            val recordLength = CENTRAL_DIRECTORY_HEADER_SIZE + nameLength + extraLength + entryCommentLength
            require(position + recordLength <= endOffset) { "SixBundle central directory 항목이 잘렸습니다." }
            val nameBytes = ByteArray(nameLength)
            file.readFully(nameBytes)
            val name = nameBytes.toString(StandardCharsets.UTF_8)
            entries += CentralDirectoryEntry(
                name = name,
                flags = flags,
                method = method,
                crc = crc,
                compressedSize = compressedSize,
                size = size,
                localHeaderOffset = localHeaderOffset,
            )
            position += recordLength
        }
        require(position == endOffset) { "SixBundle central directory에 허용되지 않은 data가 있습니다." }
        entries.forEach { validateLocalHeader(file, it, offset) }
        return entries
    }

    private fun validateLocalHeader(
        file: RandomAccessFile,
        entry: CentralDirectoryEntry,
        centralDirectoryOffset: Long,
    ) {
        require(entry.localHeaderOffset + LOCAL_FILE_HEADER_SIZE <= centralDirectoryOffset) {
            "SixBundle local header offset이 올바르지 않습니다."
        }
        file.seek(entry.localHeaderOffset)
        require(file.readUInt32() == LOCAL_FILE_HEADER_SIGNATURE) {
            "SixBundle local header signature가 올바르지 않습니다."
        }
        file.readUInt16() // version needed
        val flags = file.readUInt16()
        val method = file.readUInt16()
        file.readUInt16() // modified time
        file.readUInt16() // modified date
        val crc = file.readUInt32()
        val compressedSize = file.readUInt32()
        val size = file.readUInt32()
        val nameLength = file.readUInt16()
        val extraLength = file.readUInt16()
        val nameBytes = ByteArray(nameLength)
        file.readFully(nameBytes)
        require(nameBytes.toString(StandardCharsets.UTF_8) == entry.name && flags == entry.flags && method == entry.method) {
            "SixBundle local header metadata가 central directory와 일치하지 않습니다."
        }
        val dataOffset = entry.localHeaderOffset + LOCAL_FILE_HEADER_SIZE + nameLength + extraLength
        require(dataOffset + entry.compressedSize <= centralDirectoryOffset) {
            "SixBundle compressed entry 범위가 올바르지 않습니다."
        }
        if (flags and DATA_DESCRIPTOR_FLAG == 0) {
            require(crc == entry.crc && compressedSize == entry.compressedSize && size == entry.size) {
                "SixBundle local header 크기가 central directory와 일치하지 않습니다."
            }
        }
    }

    private fun validateEntries(
        zip: ZipFile,
        centralDirectory: List<CentralDirectoryEntry>,
    ): Map<String, ZipEntry> {
        val zipEntries = zip.entries().asSequence().toList()
        require(zipEntries.size == centralDirectory.size) { "SixBundle central directory 항목 수가 일치하지 않습니다." }
        val entries = linkedMapOf<String, ZipEntry>()
        centralDirectory.zip(zipEntries).forEach { (central, zipEntry) ->
            require(
                zipEntry.name == central.name && zipEntry.method == central.method &&
                    zipEntry.crc == central.crc && zipEntry.compressedSize == central.compressedSize &&
                    zipEntry.size == central.size,
            ) { "SixBundle entry metadata가 central directory와 일치하지 않습니다." }
            validateEntryName(zipEntry)
            require(entries.put(zipEntry.name, zipEntry) == null) {
                "SixBundle 항목이 중복되었습니다: ${zipEntry.name}"
            }
        }
        require(entries.keys == REQUIRED_ENTRIES) {
            "SixBundle 항목은 ${REQUIRED_ENTRIES.sorted()}만 포함해야 합니다. 감지: ${entries.keys.sorted()}"
        }
        val totalSize = entries.values.fold(0L) { total, entry ->
            val limit = entryLimit(entry.name)
            require(entry.size in 0..limit && entry.compressedSize >= 0) {
                "SixBundle 항목 크기가 제한을 초과합니다: ${entry.name}"
            }
            when {
                entry.size == 0L -> Unit
                entry.compressedSize == 0L -> {
                    throw IllegalArgumentException("SixBundle 압축 항목 크기가 올바르지 않습니다: ${entry.name}")
                }
                else -> require(entry.size.toDouble() / entry.compressedSize <= MAX_COMPRESSION_RATIO) {
                    "SixBundle 압축 비율이 제한을 초과합니다: ${entry.name}"
                }
            }
            total + entry.size
        }
        require(totalSize <= MAX_TOTAL_SIZE) { "SixBundle 압축 해제 크기가 제한을 초과합니다." }
        return entries
    }

    private fun readEntry(zip: ZipFile, entry: ZipEntry, limit: Long): ByteArray {
        require(entry.size in 0..limit && entry.size <= Int.MAX_VALUE) {
            "SixBundle 항목 크기가 제한을 초과합니다: ${entry.name}"
        }
        val bytes = ByteArray(entry.size.toInt())
        zip.getInputStream(entry).use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                require(read >= 0) { "SixBundle 항목이 잘렸습니다: ${entry.name}" }
                offset += read
            }
            require(input.read() == -1) { "SixBundle 항목 크기가 일치하지 않습니다: ${entry.name}" }
        }
        return bytes
    }

    private fun validateContents(
        wrapper: ByteArray,
        extension: ContractExtensionDescriptor,
        compatibility: ContractCompatibilityReport,
    ) {
        require(wrapper.size.toLong() <= MAX_WRAPPER_SIZE) { "wrapper.apk 크기가 제한을 초과합니다." }
        BundleJson.validateExtension(extension)
        BundleJson.validateCompatibility(compatibility)
        require(sha256(wrapper) == extension.wrapperSha256) {
            "wrapper.apk SHA-256이 descriptor와 일치하지 않습니다."
        }
        require(compatibility.extensionName == null || compatibility.extensionName == extension.name) {
            "compatibility extensionName이 descriptor와 일치하지 않습니다."
        }
        require(compatibility.extensionVersion == null || compatibility.extensionVersion == extension.version) {
            "compatibility extensionVersion이 descriptor와 일치하지 않습니다."
        }
        require(
            compatibility.schemaVersion !in 3..4 ||
                compatibility.convertedExtensionId == null ||
                compatibility.convertedExtensionId == extension.extensionId,
        ) {
            "schema v3/v4 convertedExtensionId가 descriptor와 일치하지 않습니다."
        }
    }

    private fun validateEntryName(entry: ZipEntry) {
        require(!entry.isDirectory) { "SixBundle directory entry는 허용되지 않습니다: ${entry.name}" }
        require('/' !in entry.name && '\\' !in entry.name && ".." !in entry.name) {
            "안전하지 않은 SixBundle 항목 이름입니다: ${entry.name}"
        }
    }

    private fun entryLimit(name: String): Long = when (name) {
        WRAPPER_ENTRY -> MAX_WRAPPER_SIZE
        EXTENSION_ENTRY, COMPATIBILITY_ENTRY -> MAX_METADATA_SIZE
        else -> throw IllegalArgumentException("SixBundle에 허용되지 않은 항목이 있습니다: $name")
    }

    private fun add(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun deleteTemporaryFile(file: File) {
        if (file.exists() && !file.delete()) file.deleteOnExit()
    }

    private fun RandomAccessFile.readUInt16(): Int = readUnsignedByte() or (readUnsignedByte() shl 8)

    private fun RandomAccessFile.readUInt32(): Long =
        readUInt16().toLong() or (readUInt16().toLong() shl 16)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class TemporaryArchive(val file: File, val size: Long)

    private data class CentralDirectoryEntry(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val localHeaderOffset: Long,
    )

    private val REQUIRED_ENTRIES = setOf(WRAPPER_ENTRY, EXTENSION_ENTRY, COMPATIBILITY_ENTRY)

    private const val TEMP_FILE_PREFIX = "six-bundle-contract-"
    private const val DATA_DESCRIPTOR_FLAG = 0x08
    private const val LOCAL_FILE_HEADER_SIGNATURE = 0x04034b50L
    private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
    private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L
    private const val LOCAL_FILE_HEADER_SIZE = 30L
    private const val CENTRAL_DIRECTORY_HEADER_SIZE = 46L
    private const val END_OF_CENTRAL_DIRECTORY_SIZE = 22L
    private const val MAX_END_OF_CENTRAL_DIRECTORY_SEARCH = 65_557L
    private const val ZIP16_SENTINEL = 0xffff
    private const val ZIP32_SENTINEL = 0xffffffffL
}
