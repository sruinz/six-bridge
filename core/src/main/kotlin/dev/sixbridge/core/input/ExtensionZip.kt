package dev.sixbridge.core.input

import dev.sixbridge.core.compat.AnalysisControl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.channels.WritableByteChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.BasicFileAttributeView
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.jvm.JvmSynthetic

object ExtensionZip {
    fun create(sourceRoot: Path, output: Path, publicKey: ByteArray? = null) {
        Files.write(output, createBytes(sourceRoot, publicKey, includeProjectMarker = true))
    }

    fun createBytes(
        sourceRoot: Path,
        publicKey: ByteArray? = null,
        includeProjectMarker: Boolean = false,
    ): ByteArray = createBytesInternal(
        sourceRoot,
        includeProjectMarker,
    ) { manifest ->
        if (publicKey == null) manifest else manifestWithKey(manifest, publicKey)
    }

    @JvmSynthetic
    internal fun createCanonicalManifestBytes(
        sourceRoot: Path,
        publicKey: ByteArray,
        includeProjectMarker: Boolean,
    ): ByteArray = createBytesInternal(
        sourceRoot,
        includeProjectMarker,
    ) { manifest ->
        canonicalManifestWithKey(manifest, publicKey)
    }

    private fun createBytesInternal(
        sourceRoot: Path,
        includeProjectMarker: Boolean,
        manifestTransform: (ByteArray) -> ByteArray,
    ): ByteArray {
        require(Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            "확장 소스 디렉터리가 아닙니다: $sourceRoot"
        }
        require(Files.isRegularFile(sourceRoot.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)) {
            "manifest.json 파일이 없습니다: $sourceRoot"
        }
        val entries = readSourceEntries(
            sourceRoot,
            includeProjectMarker,
        )
        return ByteArrayOutputStream().run {
            ZipOutputStream(this).use { zip ->
                entries.forEach { source ->
                    val bytes = if (source.name == "manifest.json") {
                        manifestTransform(source.bytes)
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
        return extract(bytes, destination, AnalysisControl())
    }

    fun extract(
        bytes: ByteArray,
        destination: Path,
        control: AnalysisControl,
    ): Path {
        control.checkCancelled()
        val temporary = Files.createTempFile("sixbridge-extension-bytes-", ".zip")
        return try {
            Files.write(temporary, bytes)
            control.checkCancelled()
            extract(temporary, destination, control)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun extract(zipPath: Path, destination: Path): Path {
        return extract(zipPath, destination, AnalysisControl())
    }

    fun extract(
        zipPath: Path,
        destination: Path,
        control: AnalysisControl,
    ): Path {
        control.checkCancelled()
        val normalizedDestination = prepareExtractionRoot(destination)
        val identity = extractionRootIdentity(normalizedDestination)
        return extractArchive(
            zipPath,
            normalizedDestination,
            destination,
            identity.fileKey,
            identity.afterSecureValidation,
            control,
        )
    }

    @JvmSynthetic
    internal fun extractVerified(
        bytes: ByteArray,
        root: ExtensionExtractionRoot,
        control: AnalysisControl = AnalysisControl(),
    ): Path {
        control.checkCancelled()
        val temporary = Files.createTempFile("sixbridge-extension-bytes-", ".zip")
        return try {
            Files.write(temporary, bytes)
            control.checkCancelled()
            extractVerified(temporary, root, control)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @JvmSynthetic
    internal fun extractVerified(
        zipPath: Path,
        root: ExtensionExtractionRoot,
        control: AnalysisControl = AnalysisControl(),
    ): Path {
        control.checkCancelled()
        return extractArchive(
            zipPath,
            root.path,
            root.path,
            root.fileKey,
            root.afterSecureValidation,
            control,
        )
    }

    @JvmSynthetic
    fun extractInto(bytes: ByteArray, destination: Path): Path {
        return extractInto(bytes, destination, AnalysisControl())
    }

    @JvmSynthetic
    fun extractInto(
        bytes: ByteArray,
        destination: Path,
        control: AnalysisControl,
    ): Path {
        control.checkCancelled()
        val temporary = Files.createTempFile("sixbridge-extension-bytes-", ".zip")
        return try {
            Files.write(temporary, bytes)
            control.checkCancelled()
            extractInto(temporary, destination, control)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    @JvmSynthetic
    fun extractInto(zipPath: Path, destination: Path): Path {
        return extractInto(zipPath, destination, AnalysisControl())
    }

    @JvmSynthetic
    fun extractInto(
        zipPath: Path,
        destination: Path,
        control: AnalysisControl,
    ): Path {
        control.checkCancelled()
        val normalizedDestination = destination.toAbsolutePath().normalize()
        requireExtractionDirectory(normalizedDestination)
        require(Files.list(normalizedDestination).use { it.findAny().isEmpty }) {
            "ZIP 압축 해제 대상은 등록 직후의 빈 디렉터리여야 합니다."
        }
        val identity = extractionRootIdentity(normalizedDestination)
        return extractArchive(
            zipPath,
            normalizedDestination,
            normalizedDestination,
            identity.fileKey,
            identity.afterSecureValidation,
            control,
        )
    }

    private fun extractArchive(
        zipPath: Path,
        normalizedDestination: Path,
        resultRoot: Path,
        expectedRootFileKey: Any,
        afterSecureValidation: (Path) -> Unit,
        control: AnalysisControl,
    ): Path {
        control.checkCancelled()
        val validatedEntryCount = validateUnixEntryTypes(zipPath, control)
        ZipFile(zipPath.toFile()).use { zip ->
            require(zip.size() == validatedEntryCount) {
                "ZIP 중앙 디렉터리 항목 수가 일치하지 않습니다."
            }
            require(zip.size() <= MAX_ENTRIES) { "ZIP 항목 수가 제한을 초과합니다." }
            var declaredTotal = 0L
            val rawNames = mutableSetOf<String>()
            val normalizedPaths = mutableMapOf<String, String>()
            val foldedPaths = mutableMapOf<String, String>()
            val entryTypes = mutableMapOf<String, Boolean>()
            val validated = zip.entries().asSequence().map { entry ->
                control.checkCancelled()
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
            withSecureExtractionRoot(
                normalizedDestination,
                expectedRootFileKey,
                entryTypes,
                afterSecureValidation,
                control,
            ) { secureRoot ->
                var totalSize = 0L
                validated.forEach { validatedEntry ->
                    control.checkCancelled()
                    val entry = validatedEntry.entry
                    val components = validatedEntry.name.split('/')
                    val output = normalizedDestination.resolve(validatedEntry.name).normalize()
                    require(output.startsWith(normalizedDestination)) {
                        "ZIP 경로 탈출 항목을 거부했습니다: ${entry.name}"
                    }
                    requireCurrentExtractionRoot(
                        normalizedDestination,
                        expectedRootFileKey,
                    )
                    if (entry.isDirectory) {
                        ensureDirectoryComponents(normalizedDestination, components)
                    } else {
                        ensureDirectoryComponents(normalizedDestination, components.dropLast(1))
                        zip.getInputStream(entry).use { input ->
                            totalSize += copyLimited(
                                input,
                                secureRoot,
                                components,
                                output,
                                control,
                            )
                            require(totalSize <= MAX_TOTAL_SIZE) {
                                "ZIP 전체 압축 해제 크기가 제한을 초과합니다."
                            }
                        }
                    }
                    requireCurrentExtractionRoot(
                        normalizedDestination,
                        expectedRootFileKey,
                    )
                }
            }
        }
        requireCurrentExtractionRoot(normalizedDestination, expectedRootFileKey)
        return locateManifestRoot(resultRoot).also {
            requireCurrentExtractionRoot(normalizedDestination, expectedRootFileKey)
        }
    }

    private fun validateUnixEntryTypes(
        zipPath: Path,
        control: AnalysisControl,
    ): Int =
        RandomAccessFile(zipPath.toFile(), "r").use { archive ->
            control.checkCancelled()
            val archiveSize = archive.length()
            require(archiveSize >= END_OF_CENTRAL_DIRECTORY_SIZE) {
                "ZIP 중앙 디렉터리를 찾지 못했습니다."
            }
            val tailSize = minOf(
                archiveSize,
                END_OF_CENTRAL_DIRECTORY_SIZE + MAX_ZIP_COMMENT_SIZE,
            ).toInt()
            val tail = ByteArray(tailSize)
            val tailOffset = archiveSize - tailSize
            archive.seek(tailOffset)
            archive.readFully(tail)
            val endOffset = (tail.size - END_OF_CENTRAL_DIRECTORY_SIZE.toInt() downTo 0)
                .firstOrNull { offset ->
                    if (littleEndianInt(tail, offset) != END_OF_CENTRAL_DIRECTORY_SIGNATURE) {
                        return@firstOrNull false
                    }
                    val commentLength = littleEndianShort(tail, offset + 20)
                    val disk = littleEndianShort(tail, offset + 4)
                    val centralDisk = littleEndianShort(tail, offset + 6)
                    val diskEntries = littleEndianShort(tail, offset + 8)
                    val totalEntries = littleEndianShort(tail, offset + 10)
                    val centralSize = littleEndianUnsignedInt(tail, offset + 12)
                    val centralOffset = littleEndianUnsignedInt(tail, offset + 16)
                    val absoluteOffset = tailOffset + offset
                    offset + END_OF_CENTRAL_DIRECTORY_SIZE + commentLength ==
                        tail.size.toLong() &&
                        disk == 0 &&
                        centralDisk == 0 &&
                        diskEntries == totalEntries &&
                        centralOffset + centralSize == absoluteOffset
                }
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
                control.checkCancelled()
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
            require(archive.filePointer == centralOffset + centralSize) {
                "ZIP 중앙 디렉터리 크기와 실제 항목이 일치하지 않습니다."
            }
            entryCount
        }

    private fun readSourceEntries(
        sourceRoot: Path,
        includeProjectMarker: Boolean,
    ): List<SourceEntry> {
        val inventory = captureInventory(sourceRoot, includeProjectMarker)
        val entries = inventory.map { entry ->
            readSourceEntry(entry)
        }
        val finalInventory = captureInventory(sourceRoot, includeProjectMarker)
        require(inventory == finalInventory) {
            "snapshot 중 확장 소스 inventory가 변경되었습니다."
        }
        entries.forEach { expected ->
            val inventoryEntry = inventory.single { it.name == expected.name }
            val verified = readSourceEntry(inventoryEntry)
            require(expected.sha256 == verified.sha256) {
                "snapshot 중 확장 소스 파일 hash가 변경되었습니다: ${inventoryEntry.path}"
            }
        }
        return entries.sortedBy(SourceEntry::name)
    }

    private fun captureInventory(
        sourceRoot: Path,
        includeProjectMarker: Boolean,
    ): List<SourceInventoryEntry> {
        val normalizedPaths = mutableMapOf<String, String>()
        val foldedPaths = mutableMapOf<String, String>()
        val inventory = mutableListOf<SourceInventoryEntry>()
        var totalSize = 0L
        Files.walkFileTree(
            sourceRoot,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    directory: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    if (directory != sourceRoot) {
                        val relative = sourceRoot.relativize(directory)
                        requireNoExcludedDirectoryAlias(relative)
                        if (isExcludedDirectory(relative)) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    path: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    val relativePath = sourceRoot.relativize(path)
                    requireNoExcludedAliases(relativePath)
                    if (!shouldInclude(relativePath, includeProjectMarker)) {
                        return FileVisitResult.CONTINUE
                    }
                    val relative = relativePath.invariantSeparatorsPathString
                    val name = validateRelativePath(relative, directory = false)
                    registerPath(name, relative, normalizedPaths, foldedPaths)
                    require(
                        !attributes.isSymbolicLink &&
                            attributes.isRegularFile,
                    ) {
                        "확장 소스에는 일반 파일만 허용됩니다: $path"
                    }
                    require(attributes.size() <= MAX_ENTRY_SIZE) {
                        "ZIP 개별 파일 크기가 제한을 초과합니다: $path"
                    }
                    require(inventory.size < MAX_ENTRIES) {
                        "ZIP 항목 수가 제한을 초과합니다: 감지 항목=${inventory.size + 1}"
                    }
                    require(attributes.size() <= MAX_TOTAL_SIZE - totalSize) {
                        "ZIP 전체 크기가 제한을 초과합니다: " +
                            "누적 바이트=${totalSize + attributes.size()}"
                    }
                    totalSize += attributes.size()
                    inventory += SourceInventoryEntry(
                        path = path,
                        name = name,
                        size = attributes.size(),
                        modifiedMillis = attributes.lastModifiedTime().toMillis(),
                        fileKey = attributes.fileKey()?.toString(),
                    )
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return inventory.sortedBy(SourceInventoryEntry::name)
    }

    private fun readSourceEntry(entry: SourceInventoryEntry): SourceEntry {
        val before = readAttributes(entry.path)
        require(entry.matches(before)) {
            "snapshot 중 확장 소스 파일이 변경되었습니다: ${entry.path}"
        }
        val bytes = try {
            FileChannel.open(
                entry.path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                require(channel.size() == entry.size) {
                    "snapshot 중 확장 소스 파일 크기가 변경되었습니다: ${entry.path}"
                }
                val buffer = ByteBuffer.allocate(entry.size.toInt())
                while (buffer.hasRemaining()) {
                    require(channel.read(buffer) >= 0) {
                        "snapshot 중 확장 소스 파일이 잘렸습니다: ${entry.path}"
                    }
                }
                require(channel.read(ByteBuffer.allocate(1)) < 0) {
                    "snapshot 중 확장 소스 파일이 커졌습니다: ${entry.path}"
                }
                buffer.array()
            }
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "snapshot 중 확장 소스 파일이 변경되었습니다: ${entry.path}",
                error,
            )
        }
        val after = readAttributes(entry.path)
        require(entry.matches(after)) {
            "snapshot 중 확장 소스 파일이 변경되었습니다: ${entry.path}"
        }
        return SourceEntry(
            name = entry.name,
            bytes = bytes,
            sha256 = sha256(bytes),
        )
    }

    private fun readAttributes(path: Path): BasicFileAttributes =
        try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "snapshot 중 확장 소스 파일이 변경되었습니다: $path",
                error,
            )
        }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun manifestWithKey(manifestBytes: ByteArray, publicKey: ByteArray): ByteArray {
        val json = Json { prettyPrint = true }
        val manifest = json.parseToJsonElement(manifestBytes.decodeToString()) as JsonObject
        val withKey = JsonObject(
            manifest + ("key" to JsonPrimitive(Base64.getEncoder().encodeToString(publicKey))),
        )
        return json.encodeToString(JsonObject.serializer(), withKey).toByteArray()
    }

    private fun canonicalManifestWithKey(
        manifestBytes: ByteArray,
        publicKey: ByteArray,
    ): ByteArray {
        val json = Json
        val manifest = json.parseToJsonElement(manifestBytes.decodeToString()) as JsonObject
        manifest["key"]?.let { existing ->
            val encoded = (existing as? JsonPrimitive)
                ?.takeIf(JsonPrimitive::isString)
                ?.content
                ?: throw IllegalArgumentException("manifest key는 Base64 문자열이어야 합니다.")
            val decoded = Base64.getDecoder().decode(encoded)
            require(Base64.getEncoder().encodeToString(decoded) == encoded) {
                "manifest key는 canonical Base64여야 합니다."
            }
            require(decoded.contentEquals(publicKey)) {
                "manifest key가 검증된 CRX proof 공개 키와 다릅니다."
            }
        }
        val withKey = JsonObject(
            manifest + ("key" to JsonPrimitive(Base64.getEncoder().encodeToString(publicKey))),
        )
        return canonicalJson(withKey).toString().toByteArray()
    }

    private fun canonicalJson(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(
            value.entries.sortedBy { it.key }
                .associate { (key, child) -> key to canonicalJson(child) },
        )
        is JsonArray -> JsonArray(value.map(::canonicalJson))
        else -> value
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

    private fun isExcludedDirectory(relative: Path): Boolean =
        relative.any { it.toString() in EXCLUDED_DIRECTORIES }

    private fun requireNoExcludedDirectoryAlias(relative: Path) {
        relative.forEach { component ->
            val raw = component.toString()
            val canonical = canonicalSegment(raw)
            require(raw in EXCLUDED_DIRECTORIES || canonical !in CANONICAL_EXCLUDED_DIRECTORIES) {
                "제외 디렉터리 이름의 canonical alias를 거부했습니다: $raw"
            }
        }
    }

    private fun requireNoExcludedAliases(relative: Path) {
        requireNoExcludedDirectoryAlias(relative)
        val raw = relative.fileName.toString()
        val canonical = canonicalSegment(raw)
        require(raw in EXCLUDED_FILES || canonical !in CANONICAL_EXCLUDED_FILES) {
            "제외 파일 이름의 canonical alias를 거부했습니다: $raw"
        }
    }

    private fun canonicalSegment(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .uppercase(Locale.ROOT)
            .lowercase(Locale.ROOT)

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
            val folded = Normalizer.normalize(normalizedPrefix, Normalizer.Form.NFKC)
                .uppercase(Locale.ROOT)
                .lowercase(Locale.ROOT)
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

    private fun prepareExtractionRoot(destination: Path): Path {
        val normalized = destination.toAbsolutePath().normalize()
        if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
            requireExtractionDirectory(normalized)
            return normalized.toRealPath().also(::requireExtractionDirectory)
        }

        val missing = ArrayDeque<String>()
        var existing = normalized
        while (!Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            missing.addFirst(existing.fileName.toString())
            existing = requireNotNull(existing.parent) {
                "ZIP 압축 해제 경로의 기존 상위 디렉터리를 찾지 못했습니다: $destination"
            }
        }
        requireExtractionDirectory(existing)
        return ensureDirectoryComponents(existing.toRealPath(), missing)
    }

    private fun ensureDirectoryComponents(root: Path, components: Iterable<String>): Path {
        var current = root
        requireExtractionDirectory(current)
        components.forEach { component ->
            val child = current.resolve(component)
            try {
                Files.createDirectory(child)
            } catch (_: FileAlreadyExistsException) {
                // 기존 컴포넌트는 아래 NOFOLLOW 속성 검증을 통과한 경우에만 허용한다.
            }
            requireExtractionDirectory(child)
            current = child
        }
        return current
    }

    private fun requireExtractionDirectory(path: Path) {
        val attributes = try {
            Files.readAttributes(
                path,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "ZIP 압축 해제 디렉터리를 확인할 수 없습니다: $path",
                error,
            )
        }
        require(attributes.isDirectory && !attributes.isSymbolicLink) {
            "ZIP 압축 해제 경로에 실제 디렉터리가 아닌 항목이 있습니다: $path"
        }
    }

    private fun extractionRootIdentity(path: Path): ExtensionExtractionRoot {
        val canonical = path.toRealPath()
        val attributes = Files.readAttributes(
            canonical,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        require(attributes.isDirectory && !attributes.isSymbolicLink) {
            "ZIP 압축 해제 root가 실제 디렉터리가 아닙니다: $canonical"
        }
        return ExtensionExtractionRoot(
            canonical,
            requireNotNull(attributes.fileKey()) {
                "ZIP 압축 해제 root identity를 확인할 수 없습니다: $canonical"
            },
        )
    }

    private fun requireCurrentExtractionRoot(path: Path, expectedFileKey: Any) {
        val current = extractionRootIdentity(path)
        require(current.fileKey == expectedFileKey) {
            "ZIP 압축 해제 root identity가 변경되었습니다: $path"
        }
    }

    private inline fun <T> withSecureExtractionRoot(
        root: Path,
        expectedFileKey: Any,
        expectedEntries: Map<String, Boolean>,
        afterSecureValidation: (Path) -> Unit,
        control: AnalysisControl,
        block: (SecureDirectoryStream<Path>) -> T,
    ): T {
        control.checkCancelled()
        val parent = requireNotNull(root.parent) {
            "ZIP 압축 해제 루트의 안전한 상위 디렉터리를 찾지 못했습니다: $root"
        }
        Files.newDirectoryStream(parent).use { parentStream ->
            require(parentStream is SecureDirectoryStream<Path>) {
                "파일시스템이 안전한 ZIP 압축 해제를 지원하지 않습니다: $root"
            }
            requireSecureRootIdentity(parentStream, root.fileName, expectedFileKey)
            parentStream.newDirectoryStream(
                root.fileName,
                LinkOption.NOFOLLOW_LINKS,
            ).use { directory ->
                requireSecureRootIdentity(parentStream, root.fileName, expectedFileKey)
                require(!directory.iterator().hasNext()) {
                    "ZIP 압축 해제 대상은 secure handle 확인 시점에도 비어 있어야 합니다."
                }
                val result = runAfterSecureExtractionHook(
                    hook = { afterSecureValidation(root) },
                    revalidate = {
                        requireSecureRootIdentity(
                            parentStream,
                            root.fileName,
                            expectedFileKey,
                        )
                        require(!directory.iterator().hasNext()) {
                            "ZIP 압축 해제 대상은 secure hook 뒤에도 비어 있어야 합니다."
                        }
                    },
                ) {
                    block(directory)
                }
                require(secureInventory(directory, control) == expectedEntries) {
                    "ZIP 압축 해제 중 예상하지 않은 root 항목이 생겼습니다."
                }
                requireSecureRootIdentity(parentStream, root.fileName, expectedFileKey)
                control.checkCancelled()
                return result
            }
        }
    }

    private fun secureInventory(
        directory: SecureDirectoryStream<Path>,
        control: AnalysisControl,
        prefix: String = "",
    ): Map<String, Boolean> {
        val entries = linkedMapOf<String, Boolean>()
        directory.forEach { entry ->
            control.checkCancelled()
            val name = entry.fileName.toString()
            val relative = if (prefix.isEmpty()) name else "$prefix/$name"
            val attributes = requireNotNull(
                directory.getFileAttributeView(
                    Path.of(name),
                    BasicFileAttributeView::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                ),
            ).readAttributes()
            require(!attributes.isSymbolicLink && (attributes.isDirectory || attributes.isRegularFile)) {
                "ZIP 압축 해제 root에 안전하지 않은 항목이 있습니다: $relative"
            }
            entries[relative] = attributes.isDirectory
            if (attributes.isDirectory) {
                directory.newDirectoryStream(
                    Path.of(name),
                    LinkOption.NOFOLLOW_LINKS,
                ).use { child ->
                    entries.putAll(secureInventory(child, control, relative))
                }
            }
        }
        return entries
    }

    private fun requireSecureRootIdentity(
        parent: SecureDirectoryStream<Path>,
        name: Path,
        expectedFileKey: Any,
    ) {
        val view = requireNotNull(
            parent.getFileAttributeView(
                name,
                BasicFileAttributeView::class.java,
                LinkOption.NOFOLLOW_LINKS,
            ),
        ) {
            "ZIP 압축 해제 root attribute view를 열 수 없습니다: $name"
        }
        val attributes = view.readAttributes()
        require(
            attributes.isDirectory &&
                !attributes.isSymbolicLink &&
                attributes.fileKey() == expectedFileKey,
        ) {
            "ZIP 압축 해제 root identity가 변경되었습니다: $name"
        }
    }

    private fun copyLimited(
        input: InputStream,
        directory: SecureDirectoryStream<Path>,
        components: List<String>,
        output: Path,
        control: AnalysisControl,
    ): Long = if (components.size == 1) {
        directory.newByteChannel(
            Path.of(components.single()),
            FILE_CREATE_OPTIONS,
        ).use { channel -> copyLimited(input, channel, output, control) }
    } else {
        directory.newDirectoryStream(
            Path.of(components.first()),
            LinkOption.NOFOLLOW_LINKS,
        ).use { child ->
            copyLimited(input, child, components.drop(1), output, control)
        }
    }

    private fun copyLimited(
        input: InputStream,
        channel: WritableByteChannel,
        output: Path,
        control: AnalysisControl,
    ): Long {
        var size = 0L
        val target = Channels.newOutputStream(channel)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            control.checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            size += read
            require(size <= MAX_ENTRY_SIZE) {
                "ZIP 개별 항목 크기가 제한을 초과합니다: $output"
            }
            target.write(buffer, 0, read)
            control.checkCancelled()
        }
        return size
    }

    private val EXCLUDED_DIRECTORIES = setOf(".git", ".gradle", "node_modules")
    private val EXCLUDED_FILES = setOf(".DS_Store")
    private val CANONICAL_EXCLUDED_DIRECTORIES =
        EXCLUDED_DIRECTORIES.mapTo(mutableSetOf(), ::canonicalSegment)
    private val CANONICAL_EXCLUDED_FILES =
        EXCLUDED_FILES.mapTo(mutableSetOf(), ::canonicalSegment)
    private val FILE_CREATE_OPTIONS = setOf<OpenOption>(
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE,
        LinkOption.NOFOLLOW_LINKS,
    )
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
        val sha256: String,
    )

    private data class SourceInventoryEntry(
        val path: Path,
        val name: String,
        val size: Long,
        val modifiedMillis: Long,
        val fileKey: String?,
    ) {
        fun matches(attributes: BasicFileAttributes): Boolean =
            !attributes.isSymbolicLink &&
                attributes.isRegularFile &&
                attributes.size() == size &&
                attributes.lastModifiedTime().toMillis() == modifiedMillis &&
                attributes.fileKey()?.toString() == fileKey
    }

    private data class ValidatedEntry(
        val entry: ZipEntry,
        val name: String,
    )
}

internal inline fun <T> runAfterSecureExtractionHook(
    hook: () -> Unit,
    revalidate: () -> Unit,
    extract: () -> T,
): T {
    hook()
    revalidate()
    return extract()
}
