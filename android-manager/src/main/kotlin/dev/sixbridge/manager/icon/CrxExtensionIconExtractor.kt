package dev.sixbridge.manager.icon

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

internal data class CrxIconLimits(
    val maxHeaderBytes: Long = 1L * 1024 * 1024,
    val maxZipBytes: Long = 256L * 1024 * 1024,
    val maxEntries: Int = 4_096,
    val maxManifestBytes: Int = 256 * 1024,
    val maxIconBytes: Int = 1 * 1024 * 1024,
    val maxPngDimension: Int = 1_024,
)

internal class CrxExtensionIconExtractor(
    private val cacheDirectory: File,
    private val limits: CrxIconLimits = CrxIconLimits(),
) {
    fun extract(input: InputStream): ByteArray? = try {
        extractChecked(input)
    } catch (exception: Exception) {
        if (exception is CancellationException) {
            throw exception
        }
        null
    }

    private fun extractChecked(input: InputStream): ByteArray? {
        require(cacheDirectory.isDirectory || cacheDirectory.mkdirs())
        val prefix = input.readExactly(12)
        require(prefix.copyOfRange(0, 4).contentEquals("Cr24".toByteArray()))
        require(prefix.littleEndianUInt(4) == 3L)
        val headerSize = prefix.littleEndianUInt(8)
        require(headerSize <= limits.maxHeaderBytes)
        input.skipExactly(headerSize)

        val zipFile = File.createTempFile("extension-icon-", ".zip", cacheDirectory)
        try {
            zipFile.outputStream().use { output ->
                input.copyBoundedTo(output, limits.maxZipBytes)
            }
            return readIcon(zipFile)
        } finally {
            zipFile.delete()
        }
    }

    private fun readIcon(file: File): ByteArray? =
        ZipFile(file).use { zip ->
            require(limits.maxEntries >= 0)
            val entriesByName = mutableMapOf<String, MutableList<ZipEntry>>()
            val entries = zip.entries()
            var entryCount = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                entryCount += 1
                require(entryCount <= limits.maxEntries)
                entriesByName.getOrPut(entry.name) { mutableListOf() }.add(entry)
            }

            val manifests = entriesByName["manifest.json"].orEmpty()
            require(manifests.size == 1)
            val manifestBytes = zip.getInputStream(manifests.single()).use { input ->
                input.readBounded(limits.maxManifestBytes)
            }
            val icons = Json.parseToJsonElement(
                manifestBytes.toString(Charsets.UTF_8),
            ).jsonObject["icons"]?.jsonObject ?: return null
            val candidates = icons.entries.mapNotNull { (size, value) ->
                val numericSize = size.toIntOrNull() ?: return@mapNotNull null
                val path = (value as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.content
                    ?: return@mapNotNull null
                numericSize to path
            }.sortedByDescending { it.first }

            require(candidates.all { (_, path) -> path.isValidArchivePath() })
            candidates.forEach { (_, path) ->
                val matchingEntries = entriesByName[path].orEmpty()
                if (matchingEntries.isEmpty()) {
                    return@forEach
                }
                require(matchingEntries.size == 1)
                val bytes = try {
                    zip.getInputStream(matchingEntries.single()).use { input ->
                        input.readBounded(limits.maxIconBytes)
                    }
                } catch (_: SizeLimitExceededException) {
                    return@forEach
                }
                if (bytes.isValidPng(limits.maxPngDimension)) {
                    return bytes
                }
            }
            null
        }
}

private fun InputStream.readExactly(size: Int): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val read = read(bytes, offset, size - offset)
        require(read >= 0)
        offset += read
    }
    return bytes
}

private fun InputStream.skipExactly(byteCount: Long) {
    require(byteCount >= 0)
    var remaining = byteCount
    while (remaining > 0) {
        val skipped = skip(remaining)
        if (skipped > 0) {
            remaining -= skipped
        } else {
            require(read() >= 0)
            remaining -= 1
        }
    }
}

private fun InputStream.copyBoundedTo(
    output: java.io.OutputStream,
    maxBytes: Long,
) {
    require(maxBytes >= 0)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var copied = 0L
    while (true) {
        val read = read(buffer)
        if (read < 0) {
            return
        }
        if (read.toLong() > maxBytes - copied) {
            throw SizeLimitExceededException()
        }
        output.write(buffer, 0, read)
        copied += read
    }
}

private fun InputStream.readBounded(maxBytes: Int): ByteArray {
    require(maxBytes >= 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    copyBoundedTo(output, maxBytes.toLong())
    return output.toByteArray()
}

private fun ByteArray.littleEndianUInt(offset: Int): Long =
    (this[offset].toLong() and 0xff) or
        ((this[offset + 1].toLong() and 0xff) shl 8) or
        ((this[offset + 2].toLong() and 0xff) shl 16) or
        ((this[offset + 3].toLong() and 0xff) shl 24)

private fun String.isValidArchivePath(): Boolean =
    isNotEmpty() &&
        !startsWith("/") &&
        '\\' !in this &&
        '\u0000' !in this &&
        split('/').none { it.isEmpty() || it == "." || it == ".." }

private fun ByteArray.isValidPng(maxDimension: Int): Boolean {
    if (size < 24 || maxDimension < 1) {
        return false
    }
    val signature = byteArrayOf(
        0x89.toByte(),
        0x50,
        0x4e,
        0x47,
        0x0d,
        0x0a,
        0x1a,
        0x0a,
    )
    if (!copyOfRange(0, signature.size).contentEquals(signature)) {
        return false
    }
    if (!copyOfRange(12, 16).contentEquals("IHDR".toByteArray())) {
        return false
    }
    val width = bigEndianUInt(16)
    val height = bigEndianUInt(20)
    return width in 1..maxDimension.toLong() &&
        height in 1..maxDimension.toLong()
}

private fun ByteArray.bigEndianUInt(offset: Int): Long =
    ((this[offset].toLong() and 0xff) shl 24) or
        ((this[offset + 1].toLong() and 0xff) shl 16) or
        ((this[offset + 2].toLong() and 0xff) shl 8) or
        (this[offset + 3].toLong() and 0xff)

private class SizeLimitExceededException : IOException()
