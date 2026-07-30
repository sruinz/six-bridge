package dev.sixbridge.core.compat

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

enum class AnalysisArchiveKind {
    ZIP,
    CRX3,
}

object AnalysisInputMaterializer {
    fun <T> snapshotDirectory(
        root: Path,
        control: AnalysisControl,
        snapshotter: (Path) -> T,
    ): T {
        val ledger = AnalysisBudgetLedger(control.policy)
        Files.walkFileTree(
            root,
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
                    ledger.reserveSource(file, attributes.size())
                    return FileVisitResult.CONTINUE
                }
            },
        )
        control.checkCancelled()
        return snapshotter(root)
    }

    fun readArchive(
        path: Path,
        kind: AnalysisArchiveKind,
        control: AnalysisControl,
        payloadReader: (Path) -> ByteArray = Files::readAllBytes,
    ): ByteArray {
        preflightArchive(path, kind, control)
        control.checkCancelled()
        return payloadReader(path)
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
            val (zipOffset, zipLength) = when (kind) {
                AnalysisArchiveKind.ZIP -> 0L to channel.size()
                AnalysisArchiveKind.CRX3 -> crxZipRange(
                    channel,
                    0L,
                    channel.size(),
                    control,
                )
            }
            ZipBudgetPreflight.inspect(channel, zipOffset, zipLength, control)
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
            require(offset >= 0L && length >= 0L && offset <= channel.size() - length) {
                "분석 archive range가 올바르지 않습니다."
            }
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
            val name = readAt(channel, cursor + CENTRAL_HEADER_SIZE, nameLength)
                .decodeToString()
            val directory = name.endsWith("/")
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
