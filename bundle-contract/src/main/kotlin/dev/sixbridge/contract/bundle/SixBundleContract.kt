package dev.sixbridge.contract.bundle

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
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
        val archive = input.readNBytes((MAX_ARCHIVE_SIZE + 1).toInt())
        require(archive.size.toLong() <= MAX_ARCHIVE_SIZE) { "SixBundle archive 크기가 제한을 초과합니다." }

        val entries = linkedMapOf<String, ByteArray>()
        var totalSize = 0L
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                validateEntryName(entry)
                require(entry.name !in entries) { "SixBundle 항목이 중복되었습니다: ${entry.name}" }
                val limit = when (entry.name) {
                    WRAPPER_ENTRY -> MAX_WRAPPER_SIZE
                    EXTENSION_ENTRY, COMPATIBILITY_ENTRY -> MAX_METADATA_SIZE
                    else -> throw IllegalArgumentException("SixBundle에 허용되지 않은 항목이 있습니다: ${entry.name}")
                }
                val bytes = readEntry(zip, entry.name, limit)
                totalSize += bytes.size
                require(totalSize <= MAX_TOTAL_SIZE) { "SixBundle 압축 해제 크기가 제한을 초과합니다." }
                entries[entry.name] = bytes
                zip.closeEntry()
            }
        }
        require(entries.keys == REQUIRED_ENTRIES) {
            "SixBundle 항목은 ${REQUIRED_ENTRIES.sorted()}만 포함해야 합니다. 감지: ${entries.keys.sorted()}"
        }
        require(totalSize.toDouble() / archive.size.coerceAtLeast(1) <= MAX_COMPRESSION_RATIO) {
            "SixBundle 압축 비율이 제한을 초과합니다."
        }

        val extension = BundleJson.decodeExtension(entries.getValue(EXTENSION_ENTRY).decodeToString())
        val compatibility = BundleJson.decodeCompatibility(entries.getValue(COMPATIBILITY_ENTRY).decodeToString())
        val wrapper = entries.getValue(WRAPPER_ENTRY)
        validateContents(wrapper, extension, compatibility)
        return ContractBundleContents(wrapper, extension, compatibility)
    }

    fun write(output: OutputStream, contents: ContractBundleContents) {
        require(contents.wrapperApk.size.toLong() <= MAX_WRAPPER_SIZE) {
            "wrapper.apk 크기가 제한을 초과합니다."
        }
        require(contents.compatibility.schemaVersion == 3) {
            "SixBundle writer는 compatibility schema v3만 기록합니다."
        }
        ZipOutputStream(output).use { zip ->
            add(zip, WRAPPER_ENTRY, contents.wrapperApk)
            add(zip, EXTENSION_ENTRY, BundleJson.encodeExtension(contents.extension).encodeToByteArray())
            add(zip, COMPATIBILITY_ENTRY, BundleJson.encodeCompatibility(contents.compatibility).encodeToByteArray())
        }
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
            compatibility.schemaVersion != 3 ||
                compatibility.convertedExtensionId == null ||
                compatibility.convertedExtensionId == extension.extensionId,
        ) {
            "schema v3 convertedExtensionId가 descriptor와 일치하지 않습니다."
        }
    }

    private fun validateEntryName(entry: ZipEntry) {
        require(!entry.isDirectory) { "SixBundle directory entry는 허용되지 않습니다: ${entry.name}" }
        require('/' !in entry.name && '\\' !in entry.name && ".." !in entry.name) {
            "안전하지 않은 SixBundle 항목 이름입니다: ${entry.name}"
        }
    }

    private fun readEntry(input: InputStream, name: String, limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            require(output.size().toLong() <= limit) { "SixBundle 항목이 너무 큽니다: $name" }
        }
        return output.toByteArray()
    }

    private fun add(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private val REQUIRED_ENTRIES = setOf(WRAPPER_ENTRY, EXTENSION_ENTRY, COMPATIBILITY_ENTRY)
}
