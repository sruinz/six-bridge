package dev.sixbridge.core.compat

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException

enum class AnalysisArchiveKind {
    ZIP,
    CRX3,
}

object AnalysisInputMaterializer {
    fun <T> snapshotDirectory(
        root: Path,
        control: AnalysisControl,
        snapshotter: (Path) -> T,
    ): T = snapshotDirectory(
        root = root,
        control = control,
        cleanupSnapshot = ::deleteTree,
        snapshotter = snapshotter,
    )

    internal fun <T> snapshotDirectory(
        root: Path,
        control: AnalysisControl,
        cleanupSnapshot: (Path) -> Unit,
        snapshotter: (Path) -> T,
    ): T {
        val ledger = AnalysisBudgetLedger(control.policy)
        val normalizedRoot = root.toAbsolutePath().normalize()
        val rootIdentity = fileIdentity(normalizedRoot, directory = true)
        val snapshot = Files.createTempDirectory("sixbridge-analysis-input-")
        var primaryFailure: Throwable? = null
        return try {
            Files.walkFileTree(
                normalizedRoot,
                setOf(),
                control.policy.maxScriptFiles + 1,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(
                        directory: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        control.checkCancelled()
                        require(attributes.isDirectory && !attributes.isSymbolicLink) {
                            "분석 source에는 실제 디렉터리만 허용됩니다."
                        }
                        if (directory != normalizedRoot) {
                            ledger.reserveSourceEntry(directory)
                            val target = snapshotTarget(
                                normalizedRoot,
                                snapshot,
                                directory,
                            )
                            Files.createDirectory(target)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(
                        file: Path,
                        attributes: BasicFileAttributes,
                    ): FileVisitResult {
                        control.checkCancelled()
                        require(attributes.isRegularFile && !attributes.isSymbolicLink) {
                            "분석 source에는 일반 파일만 허용됩니다."
                        }
                        ledger.reserveSourceEntry(file)
                        ledger.reserveSource(file, attributes.size())
                        copyStableFile(
                            source = file,
                            expected = FileIdentity.from(attributes),
                            target = snapshotTarget(normalizedRoot, snapshot, file),
                            control = control,
                        )
                        return FileVisitResult.CONTINUE
                    }
                },
            )
            require(fileIdentity(normalizedRoot, directory = true) == rootIdentity) {
                "분석 source root가 snapshot 중 변경되었습니다."
            }
            control.checkCancelled()
            snapshotter(snapshot)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            completeSnapshotCleanup(snapshot, cleanupSnapshot, primaryFailure)
        }
    }

    fun readArchive(
        path: Path,
        kind: AnalysisArchiveKind,
        control: AnalysisControl,
        payloadReader: (Path) -> ByteArray = Files::readAllBytes,
    ): ByteArray = readArchive(
        path = path,
        kind = kind,
        control = control,
        cleanupSnapshot = {
            Files.deleteIfExists(it)
        },
        payloadReader = payloadReader,
    )

    internal fun readArchive(
        path: Path,
        kind: AnalysisArchiveKind,
        control: AnalysisControl,
        cleanupSnapshot: (Path) -> Unit,
        payloadReader: (Path) -> ByteArray,
    ): ByteArray {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink) {
            "분석 archive는 일반 파일이어야 합니다."
        }
        val snapshot = Files.createTempFile("sixbridge-analysis-archive-", ".bin")
        var primaryFailure: Throwable? = null
        return try {
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                val size = channel.size()
                preflightArchive(channel, 0L, size, kind, control)
                copyChannel(channel, size, snapshot, control)
                require(channel.size() == size) {
                    "분석 archive 크기가 materialization 중 변경되었습니다."
                }
            }
            preflightArchive(snapshot, kind, control)
            control.checkCancelled()
            payloadReader(snapshot)
        } catch (error: Throwable) {
            primaryFailure = error
            throw error
        } finally {
            completeSnapshotCleanup(snapshot, cleanupSnapshot, primaryFailure)
        }
    }

    fun preflightArchive(
        path: Path,
        kind: AnalysisArchiveKind,
        control: AnalysisControl,
    ) {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink) {
            "분석 archive는 일반 파일이어야 합니다."
        }
        val maximumContainer = checkedAdd(
            control.policy.maxTotalScriptBytes,
            checkedAdd(control.policy.maxManifestBytes, CONTAINER_OVERHEAD_BYTES),
        )
        budget(
            AnalysisBudgetMetric.TOTAL_SOURCE_BYTES,
            attributes.size(),
            maximumContainer,
        )
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() == attributes.size()) {
                "분석 archive 크기가 preflight 중 변경되었습니다."
            }
            preflightArchive(channel, 0L, channel.size(), kind, control)
            require(channel.size() == attributes.size()) {
                "분석 archive 크기가 preflight 중 변경되었습니다."
            }
        }
    }

    fun preflightArchiveRange(
        path: Path,
        offset: Long,
        length: Long,
        kind: AnalysisArchiveKind,
        control: AnalysisControl,
    ) {
        val maximumContainer = checkedAdd(
            control.policy.maxTotalScriptBytes,
            checkedAdd(control.policy.maxManifestBytes, CONTAINER_OVERHEAD_BYTES),
        )
        budget(
            AnalysisBudgetMetric.TOTAL_SOURCE_BYTES,
            length,
            maximumContainer,
        )
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            preflightArchive(channel, offset, length, kind, control)
        }
    }

    private fun preflightArchive(
        channel: FileChannel,
        offset: Long,
        length: Long,
        kind: AnalysisArchiveKind,
        control: AnalysisControl,
    ) {
        require(offset >= 0L && length >= 0L && offset <= channel.size() - length) {
            "분석 archive range가 올바르지 않습니다."
        }
        val maximumContainer = checkedAdd(
            control.policy.maxTotalScriptBytes,
            checkedAdd(control.policy.maxManifestBytes, CONTAINER_OVERHEAD_BYTES),
        )
        budget(AnalysisBudgetMetric.TOTAL_SOURCE_BYTES, length, maximumContainer)
        val (zipOffset, zipLength) = when (kind) {
            AnalysisArchiveKind.ZIP -> offset to length
            AnalysisArchiveKind.CRX3 -> crxZipRange(
                channel,
                offset,
                length,
                control,
            )
        }
        ZipBudgetPreflight.inspect(channel, zipOffset, zipLength, control)
    }

    private fun copyChannel(
        source: FileChannel,
        size: Long,
        target: Path,
        control: AnalysisControl,
    ) {
        FileChannel.open(target, WRITE, NOFOLLOW_LINKS).use { output ->
            val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
            var position = 0L
            while (position < size) {
                control.checkCancelled()
                buffer.clear()
                buffer.limit(minOf(buffer.capacity().toLong(), size - position).toInt())
                val count = source.read(buffer, position)
                require(count > 0) { "분석 archive가 materialization 중 잘렸습니다." }
                buffer.flip()
                while (buffer.hasRemaining()) {
                    control.checkCancelled()
                    output.write(buffer)
                }
                position += count
            }
            output.force(true)
        }
    }

    private fun copyStableFile(
        source: Path,
        expected: FileIdentity,
        target: Path,
        control: AnalysisControl,
    ) {
        FileChannel.open(source, READ, NOFOLLOW_LINKS).use { input ->
            require(input.size() == expected.size) {
                "분석 source 파일 크기가 snapshot 전에 변경되었습니다."
            }
            FileChannel.open(target, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
                var remaining = expected.size
                while (remaining > 0L) {
                    control.checkCancelled()
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                    val count = input.read(buffer)
                    require(count > 0) {
                        "분석 source 파일이 snapshot 중 잘렸습니다."
                    }
                    buffer.flip()
                    while (buffer.hasRemaining()) {
                        control.checkCancelled()
                        output.write(buffer)
                    }
                    remaining -= count
                }
                require(input.read(ByteBuffer.allocate(1)) < 0) {
                    "분석 source 파일이 snapshot 중 커졌습니다."
                }
                output.force(true)
            }
        }
        require(fileIdentity(source, directory = false) == expected) {
            "분석 source 파일이 snapshot 중 교체되었습니다."
        }
    }

    private fun snapshotTarget(root: Path, snapshot: Path, source: Path): Path =
        snapshot.resolve(root.relativize(source)).normalize().also {
            require(it.startsWith(snapshot)) {
                "분석 source 경로가 snapshot root를 벗어났습니다."
            }
        }

    private fun fileIdentity(path: Path, directory: Boolean): FileIdentity {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(
            !attributes.isSymbolicLink &&
                if (directory) attributes.isDirectory else attributes.isRegularFile,
        ) {
            "분석 source type이 올바르지 않습니다."
        }
        return FileIdentity.from(attributes)
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, NOFOLLOW_LINKS)) return
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attributes: BasicFileAttributes,
                ): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    directory: Path,
                    error: java.io.IOException?,
                ): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(directory)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun completeSnapshotCleanup(
        snapshot: Path,
        cleanupSnapshot: (Path) -> Unit,
        primaryFailure: Throwable?,
    ) {
        val cleanupFailure = try {
            cleanupSnapshot(snapshot)
            null
        } catch (error: Throwable) {
            error
        }
        if (cleanupFailure != null) {
            if (primaryFailure is CancellationException) {
                if (cleanupFailure !== primaryFailure) {
                    primaryFailure.addSuppressed(cleanupFailure)
                }
            } else {
                throw cleanupFailure
            }
        }
    }

    private data class FileIdentity(
        val key: String?,
        val size: Long,
        val modified: FileTime,
    ) {
        companion object {
            fun from(attributes: BasicFileAttributes) = FileIdentity(
                key = attributes.fileKey()?.toString(),
                size = attributes.size(),
                modified = attributes.lastModifiedTime(),
            )
        }
    }

    private fun crxZipRange(
        channel: FileChannel,
        offset: Long,
        length: Long,
        control: AnalysisControl,
    ): Pair<Long, Long> {
        control.checkCancelled()
        require(length >= CRX_PREFIX_SIZE) { "CRX3 header가 없습니다." }
        val prefix = readAt(channel, offset, CRX_PREFIX_SIZE)
        require(prefix.copyOfRange(0, 4).contentEquals(CRX_MAGIC)) {
            "CRX3 magic header가 없습니다."
        }
        val version = littleEndianUInt(prefix, 4)
        require(version == CRX_VERSION) { "지원하지 않는 CRX version입니다." }
        val headerLength = littleEndianUInt(prefix, 8)
        require(
                headerLength <= MAX_CRX_HEADER_SIZE &&
                CRX_PREFIX_SIZE.toLong() + headerLength + 4 <= length,
        ) {
            "CRX3 header 길이가 올바르지 않습니다."
        }
        val zipOffset = offset + CRX_PREFIX_SIZE + headerLength
        require(readAt(channel, zipOffset, 4).contentEquals(ZIP_MAGIC)) {
            "CRX3 ZIP payload가 없습니다."
        }
        return zipOffset to (length - CRX_PREFIX_SIZE - headerLength)
    }

    private fun budget(metric: AnalysisBudgetMetric, actual: Long, limit: Long) {
        if (actual > limit) {
            throw AnalysisBudgetExceededException(
                AnalysisInventoryResult.Exceeded(metric, actual, limit),
            )
        }
    }

    private fun checkedAdd(first: Long, second: Long): Long =
        try {
            Math.addExact(first, second)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

    private fun littleEndianUInt(bytes: ByteArray, offset: Int): Long =
        ByteBuffer.wrap(bytes, offset, 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .int.toLong() and 0xffff_ffffL

    private const val CRX_PREFIX_SIZE = 12
    private const val CRX_VERSION = 3L
    private const val MAX_CRX_HEADER_SIZE = 1L * 1024 * 1024
    private const val CONTAINER_OVERHEAD_BYTES = 8L * 1024 * 1024
    private const val STREAM_BUFFER_SIZE = 64 * 1024
    private val CRX_MAGIC = "Cr24".encodeToByteArray()
    private val ZIP_MAGIC = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 3, 4)
}

private object ZipBudgetPreflight {
    fun inspect(
        channel: FileChannel,
        zipOffset: Long,
        zipLength: Long,
        control: AnalysisControl,
    ) {
        require(zipOffset >= 0L && zipLength >= EOCD_SIZE) {
            "ZIP 중앙 디렉터리를 찾지 못했습니다."
        }
        val tailLength = minOf(zipLength, EOCD_SIZE + MAX_COMMENT_SIZE).toInt()
        val tailOffset = zipOffset + zipLength - tailLength
        val tail = readAt(channel, tailOffset, tailLength)
        val eocd = (tail.size - EOCD_SIZE downTo 0).firstOrNull { offset ->
            le32(tail, offset) == EOCD_SIGNATURE &&
                offset + EOCD_SIZE + le16(tail, offset + 20) == tail.size
        } ?: throw IllegalArgumentException("ZIP 중앙 디렉터리를 찾지 못했습니다.")
        require(le16(tail, eocd + 4) == 0 && le16(tail, eocd + 6) == 0) {
            "ZIP multi-disk 입력을 거부했습니다."
        }
        val diskEntries = le16(tail, eocd + 8)
        val entryCount = le16(tail, eocd + 10)
        val centralSize = unsigned32(tail, eocd + 12)
        val centralOffset = unsigned32(tail, eocd + 16)
        require(
            diskEntries == entryCount &&
                entryCount != ZIP64_SHORT &&
                centralSize != ZIP64_INT &&
                centralOffset != ZIP64_INT,
        ) {
            "ZIP64 또는 unknown 중앙 디렉터리를 거부했습니다."
        }
        budget(
            AnalysisBudgetMetric.SOURCE_FILE_COUNT,
            entryCount.toLong(),
            control.policy.maxScriptFiles.toLong(),
        )
        val eocdRelative = tailOffset + eocd - zipOffset
        require(centralOffset + centralSize == eocdRelative) {
            "ZIP 중앙 디렉터리 범위가 올바르지 않습니다."
        }

        var cursor = zipOffset + centralOffset
        val centralEnd = cursor + centralSize
        var files = 0L
        var total = 0L
        val rawNames = mutableSetOf<String>()
        val normalizedPaths = mutableMapOf<String, String>()
        val foldedPaths = mutableMapOf<String, String>()
        val entryTypes = mutableMapOf<String, Boolean>()
        repeat(entryCount) {
            control.checkCancelled()
            require(cursor <= centralEnd - CENTRAL_HEADER_SIZE) {
                "ZIP 중앙 디렉터리가 잘렸습니다."
            }
            val header = readAt(channel, cursor, CENTRAL_HEADER_SIZE)
            require(le32(header, 0) == CENTRAL_SIGNATURE) {
                "ZIP 중앙 디렉터리 entry가 손상되었습니다."
            }
            val compressed = unsigned32(header, 20)
            val uncompressed = unsigned32(header, 24)
            require(compressed != ZIP64_INT && uncompressed != ZIP64_INT) {
                "ZIP entry unknown 또는 ZIP64 크기를 거부했습니다."
            }
            val nameLength = le16(header, 28)
            val extraLength = le16(header, 30)
            val commentLength = le16(header, 32)
            val body = nameLength.toLong() + extraLength + commentLength
            require(body <= centralEnd - cursor - CENTRAL_HEADER_SIZE) {
                "ZIP 중앙 디렉터리 entry 길이가 올바르지 않습니다."
            }
            val rawName = decodeName(
                readAt(channel, cursor + CENTRAL_HEADER_SIZE, nameLength),
            )
            require(rawNames.add(rawName)) {
                "ZIP 중복 entry를 거부했습니다: $rawName"
            }
            val directory = rawName.endsWith("/")
            val name = validatePath(rawName, directory)
            registerPath(name, rawName, normalizedPaths, foldedPaths)
            registerEntryType(name, directory, entryTypes)
            validateEntryType(header, directory, rawName)
            if (!directory) {
                files += 1
                budget(
                    AnalysisBudgetMetric.SOURCE_FILE_COUNT,
                    files,
                    control.policy.maxScriptFiles.toLong(),
                )
                val singleLimit = if (name == "manifest.json") {
                    control.policy.maxManifestBytes
                } else {
                    control.policy.maxSingleScriptBytes
                }
                budget(
                    AnalysisBudgetMetric.SINGLE_SOURCE_BYTES,
                    uncompressed,
                    singleLimit,
                )
                total = checkedAdd(total, uncompressed)
                budget(
                    AnalysisBudgetMetric.TOTAL_SOURCE_BYTES,
                    total,
                    control.policy.maxTotalScriptBytes,
                )
                require(uncompressed == 0L || compressed > 0L) {
                    "ZIP entry 압축 크기가 올바르지 않습니다."
                }
                require(
                    compressed == 0L ||
                        uncompressed <= compressed * MAX_COMPRESSION_RATIO,
                ) {
                    "ZIP entry 압축 비율이 제한을 초과합니다."
                }
            }
            cursor += CENTRAL_HEADER_SIZE + body
        }
        require(cursor == centralEnd) {
            "ZIP 중앙 디렉터리 선언 크기가 일치하지 않습니다."
        }
    }

    private fun decodeName(bytes: ByteArray): String =
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun validatePath(raw: String, directory: Boolean): String {
        require('\\' !in raw && '\u0000' !in raw) {
            "ZIP entry 경로 구분자가 올바르지 않습니다: $raw"
        }
        val value = if (directory) raw.trimEnd('/') else raw
        require(
            value.isNotEmpty() &&
                !value.startsWith("/") &&
                !WINDOWS_DRIVE.matches(value),
        ) {
            "ZIP 절대 경로 entry를 거부했습니다: $raw"
        }
        val components = value.split('/')
        require(components.none { it.isEmpty() || it == "." || it == ".." }) {
            "ZIP traversal entry를 거부했습니다: $raw"
        }
        return components.joinToString("/") {
            Normalizer.normalize(it, Normalizer.Form.NFC)
        }
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
                "ZIP 정규화 중복 entry를 거부했습니다: $raw"
            }
            val folded = Normalizer.normalize(normalizedPrefix, Normalizer.Form.NFKC)
                .uppercase(Locale.ROOT)
                .lowercase(Locale.ROOT)
            val previousNormalized = foldedPaths.putIfAbsent(folded, normalizedPrefix)
            require(previousNormalized == null || previousNormalized == normalizedPrefix) {
                "ZIP 대소문자 충돌 entry를 거부했습니다: $raw"
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
                "ZIP 파일·디렉터리 충돌 entry를 거부했습니다: $name"
            }
        }
    }

    private fun validateEntryType(
        header: ByteArray,
        directory: Boolean,
        name: String,
    ) {
        val host = le16(header, 4) ushr 8
        val external = unsigned32(header, 38)
        if (host == UNIX_HOST) {
            val type = ((external ushr 16).toInt()) and UNIX_TYPE_MASK
            require(type == 0 || type == UNIX_REGULAR || type == UNIX_DIRECTORY) {
                "ZIP symlink 또는 특수 entry를 거부했습니다: $name"
            }
            require(type == 0 || directory == (type == UNIX_DIRECTORY)) {
                "ZIP entry type과 경로가 일치하지 않습니다: $name"
            }
        }
        val dosDirectory = external and DOS_DIRECTORY != 0L
        require(!dosDirectory || directory) {
            "ZIP DOS directory type과 경로가 일치하지 않습니다: $name"
        }
    }

    private fun budget(metric: AnalysisBudgetMetric, actual: Long, limit: Long) {
        if (actual > limit) {
            throw AnalysisBudgetExceededException(
                AnalysisInventoryResult.Exceeded(metric, actual, limit),
            )
        }
    }

    private fun checkedAdd(first: Long, second: Long): Long =
        try {
            Math.addExact(first, second)
        } catch (_: ArithmeticException) {
            Long.MAX_VALUE
        }

    private fun le16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset <= bytes.size - 2) { "ZIP 구조가 잘렸습니다." }
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun le32(bytes: ByteArray, offset: Int): Int =
        le16(bytes, offset) or (le16(bytes, offset + 2) shl 16)

    private fun unsigned32(bytes: ByteArray, offset: Int): Long =
        le32(bytes, offset).toLong() and 0xffff_ffffL

    private const val EOCD_SIGNATURE = 0x06054b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val EOCD_SIZE = 22
    private const val CENTRAL_HEADER_SIZE = 46
    private const val MAX_COMMENT_SIZE = 65_535L
    private const val ZIP64_SHORT = 0xffff
    private const val ZIP64_INT = 0xffff_ffffL
    private const val MAX_COMPRESSION_RATIO = 100L
    private const val UNIX_HOST = 3
    private const val UNIX_TYPE_MASK = 0xf000
    private const val UNIX_REGULAR = 0x8000
    private const val UNIX_DIRECTORY = 0x4000
    private const val DOS_DIRECTORY = 0x10L
    private val WINDOWS_DRIVE = Regex("^[A-Za-z]:.*")
}

private fun readAt(channel: FileChannel, offset: Long, length: Int): ByteArray {
    require(length >= 0 && offset >= 0L && offset <= channel.size() - length) {
        "archive 범위가 올바르지 않습니다."
    }
    val result = ByteArray(length)
    val buffer = ByteBuffer.wrap(result)
    var position = offset
    while (buffer.hasRemaining()) {
        val count = channel.read(buffer, position)
        require(count > 0) { "archive metadata를 완전히 읽지 못했습니다." }
        position += count
    }
    return result
}
