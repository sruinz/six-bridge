package dev.sixbridge.core.input

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.invariantSeparatorsPathString

object ExtensionZip {
    fun create(sourceRoot: Path, output: Path, publicKey: ByteArray? = null) {
        Files.write(output, createBytes(sourceRoot, publicKey, includeProjectMarker = true))
    }

    fun createBytes(
        sourceRoot: Path,
        publicKey: ByteArray? = null,
        includeProjectMarker: Boolean = false,
    ): ByteArray {
        require(Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            "확장 소스 디렉터리가 아닙니다: $sourceRoot"
        }
        require(Files.isRegularFile(sourceRoot.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)) {
            "manifest.json 파일이 없습니다: $sourceRoot"
        }
        val entries = readSourceEntries(sourceRoot, includeProjectMarker)
        return ByteArrayOutputStream().run {
            ZipOutputStream(this).use { zip ->
                entries.forEach { source ->
                    val bytes = if (source.name == "manifest.json" && publicKey != null) {
                        manifestWithKey(source.bytes, publicKey)
                    } else {
                        source.bytes
                    }
                    val crc = CRC32().apply { update(bytes) }
                    val entry = ZipEntry(source.name).apply {
                        time = 0L
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = size
                        this.crc = crc.value
                    }
                    zip.putNextEntry(entry)
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            toByteArray()
        }
    }

    fun extract(bytes: ByteArray, destination: Path): Path {
        val temporary = Files.createTempFile("sixbridge-extension-bytes-", ".zip")
        return try {
            Files.write(temporary, bytes)
            extract(temporary, destination)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun extract(zipPath: Path, destination: Path): Path {
        validateUnixEntryTypes(zipPath)
        Files.createDirectories(destination)
        ZipFile(zipPath.toFile()).use { zip ->
            require(zip.size() <= MAX_ENTRIES) { "ZIP 항목 수가 제한을 초과합니다." }
            var declaredTotal = 0L
            val rawNames = mutableSetOf<String>()
            val normalizedPaths = mutableMapOf<String, String>()
            val foldedPaths = mutableMapOf<String, String>()
            val entryTypes = mutableMapOf<String, Boolean>()
            val validated = zip.entries().asSequence().map { entry ->
                require(rawNames.add(entry.name)) {
                    "ZIP 중복 항목을 거부했습니다: ${entry.name}"
                }
                val name = validateRelativePath(entry.name, entry.isDirectory)
                registerPath(name, entry.name, normalizedPaths, foldedPaths)
                registerEntryType(name, entry.isDirectory, entryTypes)
                validateEntry(entry)
                if (!entry.isDirectory) {
                    declaredTotal += entry.size
                    require(declaredTotal <= MAX_TOTAL_SIZE) {
                        "ZIP 전체 압축 해제 크기가 제한을 초과합니다."
                    }
                }
                ValidatedEntry(entry, name)
            }.toList()
            var totalSize = 0L
            val normalizedDestination = destination.toAbsolutePath().normalize()
            validated.forEach { validatedEntry ->
                val entry = validatedEntry.entry
                val output = normalizedDestination.resolve(validatedEntry.name).normalize()
                require(output.startsWith(normalizedDestination)) {
                    "ZIP 경로 탈출 항목을 거부했습니다: ${entry.name}"
                }
                if (entry.isDirectory) {
                    Files.createDirectories(output)
                } else {
                    Files.createDirectories(output.parent)
                    zip.getInputStream(entry).use { input ->
                        totalSize += copyLimited(input, output)
                        require(totalSize <= MAX_TOTAL_SIZE) {
                            "ZIP 전체 압축 해제 크기가 제한을 초과합니다."
                        }
                    }
                }
            }
        }
        return locateManifestRoot(destination)
    }

    private fun validateUnixEntryTypes(zipPath: Path) {
        RandomAccessFile(zipPath.toFile(), "r").use { archive ->
            val archiveSize = archive.length()
            require(archiveSize >= END_OF_CENTRAL_DIRECTORY_SIZE) {
                "ZIP 중앙 디렉터리를 찾지 못했습니다."
            }
            val tailSize = minOf(
                archiveSize,
                END_OF_CENTRAL_DIRECTORY_SIZE + MAX_ZIP_COMMENT_SIZE,
            ).toInt()
            val tail = ByteArray(tailSize)
            archive.seek(archiveSize - tailSize)
            archive.readFully(tail)
            val endOffset = (
                tail.size - END_OF_CENTRAL_DIRECTORY_SIZE.toInt() downTo 0
                )
                .firstOrNull { littleEndianInt(tail, it) == END_OF_CENTRAL_DIRECTORY_SIGNATURE }
                ?: throw IllegalArgumentException("ZIP 중앙 디렉터리를 찾지 못했습니다.")
            val entryCount = littleEndianShort(tail, endOffset + 10)
            val centralSize = littleEndianUnsignedInt(tail, endOffset + 12)
            val centralOffset = littleEndianUnsignedInt(tail, endOffset + 16)
            require(entryCount != ZIP64_SHORT &&
                centralSize != ZIP64_INT &&
                centralOffset != ZIP64_INT &&
                centralOffset + centralSize <= archiveSize
            ) {
                "지원하지 않는 ZIP64 또는 손상된 중앙 디렉터리입니다."
            }

            archive.seek(centralOffset)
            repeat(entryCount) {
                val header = ByteArray(CENTRAL_DIRECTORY_HEADER_SIZE)
                archive.readFully(header)
                require(littleEndianInt(header, 0) == CENTRAL_DIRECTORY_SIGNATURE) {
                    "ZIP 중앙 디렉터리 항목이 손상되었습니다."
                }
                val nameLength = littleEndianShort(header, 28)
                val extraLength = littleEndianShort(header, 30)
                val commentLength = littleEndianShort(header, 32)
                val nameBytes = ByteArray(nameLength)
                archive.readFully(nameBytes)
                val hostSystem = header[5].toInt() and 0xff
                if (hostSystem == UNIX_HOST_SYSTEM) {
                    val mode = (
                        littleEndianUnsignedInt(header, 38) ushr UNIX_MODE_SHIFT
                        ).toInt()
                    val type = mode and UNIX_FILE_TYPE_MASK
                    require(type == 0 || type == UNIX_REGULAR_FILE || type == UNIX_DIRECTORY) {
                        "ZIP 특수 파일 항목을 거부했습니다: ${nameBytes.decodeToString()}"
                    }
                }
                val next = archive.filePointer + extraLength + commentLength
                require(next <= centralOffset + centralSize && next <= archiveSize) {
                    "ZIP 중앙 디렉터리 항목 길이가 손상되었습니다."
                }
                archive.seek(next)
            }
        }
    }

    private fun readSourceEntries(
        sourceRoot: Path,
        includeProjectMarker: Boolean,
    ): List<SourceEntry> {
        val normalizedPaths = mutableMapOf<String, String>()
        val foldedPaths = mutableMapOf<String, String>()
        var totalSize = 0L
        val entries = mutableListOf<SourceEntry>()
        Files.walk(sourceRoot).use { stream ->
            stream.iterator().asSequence()
                .filter { it != sourceRoot }
                .forEach { path ->
                    val relative = sourceRoot.relativize(path).invariantSeparatorsPathString
                    val name = validateRelativePath(relative, Files.isDirectory(path))
                    registerPath(name, relative, normalizedPaths, foldedPaths)
                    val attributes = Files.readAttributes(
                        path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    require(!attributes.isSymbolicLink &&
                        (attributes.isDirectory || attributes.isRegularFile)
                    ) {
                        "확장 소스에는 일반 파일과 디렉터리만 허용됩니다: $path"
                    }
                    if (!attributes.isRegularFile ||
                        !shouldInclude(sourceRoot.relativize(path), includeProjectMarker)
                    ) {
                        return@forEach
                    }
                    require(attributes.size() <= MAX_ENTRY_SIZE) {
                        "ZIP 개별 파일 크기가 제한을 초과합니다: $path"
                    }
                    val bytes = Files.readAllBytes(path)
                    require(bytes.size.toLong() == attributes.size() &&
                        Files.size(path) == attributes.size()
                    ) {
                        "snapshot 중 확장 소스 파일이 변경되었습니다: $path"
                    }
                    totalSize += bytes.size
                    require(totalSize <= MAX_TOTAL_SIZE) {
                        "ZIP 전체 크기가 제한을 초과합니다."
                    }
                    entries += SourceEntry(name, bytes)
                    require(entries.size <= MAX_ENTRIES) {
                        "ZIP 항목 수가 제한을 초과합니다."
                    }
                }
        }
        return entries.sortedBy(SourceEntry::name)
    }

    private fun manifestWithKey(manifestBytes: ByteArray, publicKey: ByteArray): ByteArray {
        val json = Json { prettyPrint = true }
        val manifest = json.parseToJsonElement(manifestBytes.decodeToString()) as JsonObject
        val withKey = JsonObject(
            manifest + ("key" to JsonPrimitive(Base64.getEncoder().encodeToString(publicKey))),
        )
        return json.encodeToString(JsonObject.serializer(), withKey).toByteArray()
    }

    private fun shouldInclude(relative: Path, includeProjectMarker: Boolean): Boolean {
        val components = relative.map { it.toString() }
        if (components.any { it in EXCLUDED_DIRECTORIES }) return false
        if (!includeProjectMarker &&
            relative.fileName.toString() == SourceProjectIdentityStore.MARKER_FILE
        ) {
            return false
        }
        return relative.fileName.toString() !in EXCLUDED_FILES
    }

    private fun locateManifestRoot(extracted: Path): Path {
        if (Files.isRegularFile(extracted.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)) {
            return extracted
        }
        val candidates = Files.walk(extracted, 2).use { stream ->
            stream.filter {
                it.fileName.toString() == "manifest.json" &&
                    Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS)
            }
                .map { it.parent }
                .toList()
        }
        require(candidates.size == 1) {
            "ZIP에서 단일 manifest.json 루트를 찾지 못했습니다."
        }
        return candidates.single()
    }

    private fun validateEntry(entry: ZipEntry) {
        if (!entry.isDirectory && entry.size >= 0) {
            require(entry.size <= MAX_ENTRY_SIZE) {
                "ZIP 개별 항목 크기가 제한을 초과합니다: ${entry.name}"
            }
        }
        if (entry.size > 0 && entry.compressedSize > 0) {
            require(entry.size.toDouble() / entry.compressedSize <= MAX_COMPRESSION_RATIO) {
                "ZIP 압축 비율이 제한을 초과합니다: ${entry.name}"
            }
        }
    }

    private fun validateRelativePath(rawName: String, directory: Boolean): String {
        require('\\' !in rawName && '\u0000' !in rawName) {
            "ZIP 경로 구분자가 올바르지 않습니다: $rawName"
        }
        val withoutTrailingSlash = if (directory) rawName.trimEnd('/') else rawName
        require(withoutTrailingSlash.isNotEmpty() && !withoutTrailingSlash.startsWith("/") &&
            !WINDOWS_DRIVE.matches(withoutTrailingSlash)
        ) {
            "ZIP 경로 탈출 항목을 거부했습니다: $rawName"
        }
        val components = withoutTrailingSlash.split('/')
        require(components.none { it.isEmpty() || it == "." || it == ".." }) {
            "ZIP 경로 탈출 항목을 거부했습니다: $rawName"
        }
        return components.joinToString("/") { Normalizer.normalize(it, Normalizer.Form.NFC) }
    }

    private fun registerPath(
        normalized: String,
        raw: String,
        normalizedPaths: MutableMap<String, String>,
        foldedPaths: MutableMap<String, String>,
    ) {
        val normalizedComponents = normalized.split('/')
        val rawComponents = raw.trimEnd('/').split('/')
        normalizedComponents.indices.forEach { index ->
            val normalizedPrefix = normalizedComponents.take(index + 1).joinToString("/")
            val rawPrefix = rawComponents.take(index + 1).joinToString("/")
            val previousRaw = normalizedPaths.putIfAbsent(normalizedPrefix, rawPrefix)
            require(previousRaw == null || previousRaw == rawPrefix) {
                "정규화 후 중복되는 경로를 거부했습니다: $raw"
            }
            val folded = normalizedPrefix.lowercase(Locale.ROOT)
            val previousNormalized = foldedPaths.putIfAbsent(folded, normalizedPrefix)
            require(previousNormalized == null || previousNormalized == normalizedPrefix) {
                "대소문자가 충돌하는 경로를 거부했습니다: $raw"
            }
        }
    }

    private fun registerEntryType(
        name: String,
        directory: Boolean,
        entryTypes: MutableMap<String, Boolean>,
    ) {
        val components = name.split('/')
        components.indices.forEach { index ->
            val path = components.take(index + 1).joinToString("/")
            val isDirectory = index < components.lastIndex || directory
            val previous = entryTypes.putIfAbsent(path, isDirectory)
            require(previous == null || previous == isDirectory) {
                "ZIP 파일과 디렉터리 경로가 충돌합니다: $name"
            }
        }
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        littleEndianShort(bytes, offset) or
            (littleEndianShort(bytes, offset + 2) shl 16)

    private fun littleEndianUnsignedInt(bytes: ByteArray, offset: Int): Long =
        littleEndianInt(bytes, offset).toLong() and 0xffff_ffffL

    private fun copyLimited(input: InputStream, output: Path): Long {
        var size = 0L
        Files.newOutputStream(output).use { target ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                size += read
                require(size <= MAX_ENTRY_SIZE) {
                    "ZIP 개별 항목 크기가 제한을 초과합니다: $output"
                }
                target.write(buffer, 0, read)
            }
        }
        return size
    }

    private val EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", "node_modules")
    private val EXCLUDED_FILES = setOf(".DS_Store")
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:.*")
    private const val MAX_ENTRIES = 10_000
    private const val MAX_ENTRY_SIZE = 128L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 512L * 1024 * 1024
    private const val MAX_COMPRESSION_RATIO = 100.0
    private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50
    private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50
    private const val END_OF_CENTRAL_DIRECTORY_SIZE = 22L
    private const val CENTRAL_DIRECTORY_HEADER_SIZE = 46
    private const val MAX_ZIP_COMMENT_SIZE = 65_535L
    private const val ZIP64_SHORT = 0xffff
    private const val ZIP64_INT = 0xffff_ffffL
    private const val UNIX_HOST_SYSTEM = 3
    private const val UNIX_MODE_SHIFT = 16
    private const val UNIX_FILE_TYPE_MASK = 0xf000
    private const val UNIX_REGULAR_FILE = 0x8000
    private const val UNIX_DIRECTORY = 0x4000

    private data class SourceEntry(
        val name: String,
        val bytes: ByteArray,
    )

    private data class ValidatedEntry(
        val entry: ZipEntry,
        val name: String,
    )
}
