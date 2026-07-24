package dev.sixbridge.core.draft

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyPair
import java.util.Collections
import java.util.LinkedHashSet
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class DraftSourceKind(
    val entryName: String,
) {
    ZIP("source.zip"),
    CRX("source.crx"),
}

class SixDraftPayload private constructor(
    draftJson: ByteArray,
    inputJson: ByteArray,
    val sourceKind: DraftSourceKind,
    source: ByteArray,
    conversionPlanJson: ByteArray,
    compatibilityBeforeJson: ByteArray,
    compatibilityAfterJson: ByteArray,
    expectedJson: ByteArray,
) {
    private val stableDraftJson = draftJson.copyOf()
    private val stableInputJson = inputJson.copyOf()
    private val stableSource = source.copyOf()
    private val stableConversionPlanJson = conversionPlanJson.copyOf()
    private val stableCompatibilityBeforeJson = compatibilityBeforeJson.copyOf()
    private val stableCompatibilityAfterJson = compatibilityAfterJson.copyOf()
    private val stableExpectedJson = expectedJson.copyOf()

    val draftJson: ByteArray
        get() = stableDraftJson.copyOf()
    val inputJson: ByteArray
        get() = stableInputJson.copyOf()
    val source: ByteArray
        get() = stableSource.copyOf()
    val conversionPlanJson: ByteArray
        get() = stableConversionPlanJson.copyOf()
    val compatibilityBeforeJson: ByteArray
        get() = stableCompatibilityBeforeJson.copyOf()
    val compatibilityAfterJson: ByteArray
        get() = stableCompatibilityAfterJson.copyOf()
    val expectedJson: ByteArray
        get() = stableExpectedJson.copyOf()

    @JvmSynthetic
    internal fun entries(): LinkedHashMap<String, ByteArray> = linkedMapOf(
        "draft.json" to stableDraftJson.copyOf(),
        "input.json" to stableInputJson.copyOf(),
        sourceKind.entryName to stableSource.copyOf(),
        "conversion-plan.json" to stableConversionPlanJson.copyOf(),
        "compatibility-before.json" to stableCompatibilityBeforeJson.copyOf(),
        "compatibility-after.json" to stableCompatibilityAfterJson.copyOf(),
        "expected.json" to stableExpectedJson.copyOf(),
    )

    companion object {
        fun create(
            draftJson: ByteArray,
            inputJson: ByteArray,
            sourceKind: DraftSourceKind,
            source: ByteArray,
            conversionPlanJson: ByteArray,
            compatibilityBeforeJson: ByteArray,
            compatibilityAfterJson: ByteArray,
            expectedJson: ByteArray,
        ): SixDraftPayload = SixDraftPayload(
            draftJson,
            inputJson,
            sourceKind,
            source,
            conversionPlanJson,
            compatibilityBeforeJson,
            compatibilityAfterJson,
            expectedJson,
        )
    }
}

class VerifiedSixDraft private constructor(
    val payload: SixDraftPayload,
    val signerFingerprint: String,
    signerPublicKey: ByteArray,
    names: Set<String>,
) {
    private val stablePublicKey = signerPublicKey.copyOf()
    val signerPublicKey: ByteArray
        get() = stablePublicKey.copyOf()
    val entryNames: Set<String> = Collections.unmodifiableSet(
        LinkedHashSet(names.sorted()),
    )

    companion object {
        @JvmSynthetic
        internal fun create(
            payload: SixDraftPayload,
            signerFingerprint: String,
            signerPublicKey: ByteArray,
            names: Set<String>,
        ): VerifiedSixDraft = VerifiedSixDraft(
            payload,
            signerFingerprint,
            signerPublicKey,
            names,
        )
    }
}

class InvalidSixDraftException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object SixDraft {
    fun createBytes(
        payload: SixDraftPayload,
        signingKey: KeyPair,
    ): ByteArray = invalidDraftBoundary("SixDraft를 만들 수 없습니다.") {
        val entries = payload.entries()
        validateUnsignedEntrySet(entries.keys)
        validateEntrySizes(entries)
        SixDraftJson.validatePayload(entries, payload.sourceKind.entryName)
        val signature = DraftSignature.sign(entries, signingKey)
        entries[DraftSignature.ENTRY_NAME] = SixDraftJson.encodeSignature(signature)
        validateEntrySet(entries.keys)
        validateEntrySizes(entries)
        writeStored(entries)
    }

    fun write(
        output: Path,
        payload: SixDraftPayload,
        signingKey: KeyPair,
    ): Path {
        val bytes = createBytes(payload, signingKey)
        val absolute = output.toAbsolutePath().normalize()
        val parent = requireNotNull(absolute.parent) {
            "SixDraft 출력 경로의 상위 디렉터리가 없습니다."
        }
        require(Files.isDirectory(parent, NOFOLLOW_LINKS)) {
            "SixDraft 출력 상위 경로가 실제 디렉터리가 아닙니다."
        }
        FileChannel.open(absolute, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        return absolute
    }

    fun read(input: Path): VerifiedSixDraft =
        invalidDraftBoundary("SixDraft 파일을 읽을 수 없습니다.") {
            val absolute = input.toAbsolutePath().normalize()
            val before = Files.readAttributes(
                absolute,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            require(before.isRegularFile && !before.isSymbolicLink) {
                "SixDraft 입력은 symlink가 아닌 일반 파일이어야 합니다."
            }
            require(before.size() <= MAX_ARCHIVE_SIZE) {
                "SixDraft archive 크기가 제한을 초과합니다."
            }
            require(before.size() <= Int.MAX_VALUE) {
                "SixDraft archive를 메모리에서 검증할 수 없습니다."
            }
            val bytes = FileChannel.open(absolute, READ, NOFOLLOW_LINKS).use { channel ->
                require(channel.size() == before.size()) {
                    "SixDraft 파일이 읽기 전에 변경되었습니다."
                }
                val buffer = ByteBuffer.allocate(before.size().toInt())
                while (buffer.hasRemaining()) {
                    require(channel.read(buffer) >= 0) {
                        "SixDraft 파일을 완전히 읽지 못했습니다."
                    }
                }
                require(channel.read(ByteBuffer.allocate(1)) < 0) {
                    "SixDraft 파일이 읽는 동안 커졌습니다."
                }
                buffer.array()
            }
            val after = Files.readAttributes(
                absolute,
                BasicFileAttributes::class.java,
                NOFOLLOW_LINKS,
            )
            require(
                before.fileKey() == after.fileKey() &&
                    before.size() == after.size() &&
                    before.lastModifiedTime() == after.lastModifiedTime(),
            ) {
                "SixDraft 파일이 읽는 동안 변경되었습니다."
            }
            readVerified(bytes)
        }

    fun read(bytes: ByteArray): VerifiedSixDraft =
        invalidDraftBoundary("SixDraft bytes를 읽을 수 없습니다.") {
            readVerified(bytes.copyOf())
        }

    private fun readVerified(bytes: ByteArray): VerifiedSixDraft {
        require(bytes.size.toLong() <= MAX_ARCHIVE_SIZE) {
            "SixDraft archive 크기가 제한을 초과합니다."
        }
        val entries = StrictDraftArchive.read(bytes)
        validateEntrySet(entries.keys)
        validateEntrySizes(entries)
        val sourceKind = when {
            "source.zip" in entries -> DraftSourceKind.ZIP
            "source.crx" in entries -> DraftSourceKind.CRX
            else -> throw IllegalArgumentException("SixDraft source entry가 없습니다.")
        }
        SixDraftJson.validatePayload(entries, sourceKind.entryName)
        val signature = SixDraftJson.decodeSignature(
            entries.getValue(DraftSignature.ENTRY_NAME),
        )
        require(DraftSignature.verify(entries, signature)) {
            "SixDraft Ed25519 전달 서명이 일치하지 않습니다."
        }
        val payload = SixDraftPayload.create(
            draftJson = entries.getValue("draft.json"),
            inputJson = entries.getValue("input.json"),
            sourceKind = sourceKind,
            source = entries.getValue(sourceKind.entryName),
            conversionPlanJson = entries.getValue("conversion-plan.json"),
            compatibilityBeforeJson = entries.getValue("compatibility-before.json"),
            compatibilityAfterJson = entries.getValue("compatibility-after.json"),
            expectedJson = entries.getValue("expected.json"),
        )
        return VerifiedSixDraft.create(
            payload = payload,
            signerFingerprint = DraftSignature.fingerprint(signature.publicKey),
            signerPublicKey = signature.publicKey,
            names = entries.keys,
        )
    }

    private fun writeStored(entries: Map<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.toSortedMap().forEach { (name, bytes) ->
                    val crc = CRC32().apply { update(bytes) }
                    zip.putNextEntry(
                        ZipEntry(name).apply {
                            time = 0L
                            method = ZipEntry.STORED
                            size = bytes.size.toLong()
                            compressedSize = size
                            this.crc = crc.value
                        },
                    )
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun validateEntrySet(names: Set<String>) {
        require(names.size <= MAX_ENTRIES) {
            "SixDraft entry 수가 제한을 초과합니다."
        }
        val sourceNames = names.intersect(SOURCE_ENTRIES)
        require(sourceNames.size == 1) {
            "SixDraft는 source.zip 또는 source.crx 중 하나만 포함해야 합니다."
        }
        val expected = METADATA_ENTRIES + sourceNames.single()
        require(names == expected) {
            "SixDraft entry allowlist가 일치하지 않습니다: ${names.sorted()}"
        }
    }

    private fun validateUnsignedEntrySet(names: Set<String>) {
        val sourceNames = names.intersect(SOURCE_ENTRIES)
        require(sourceNames.size == 1) {
            "SixDraft는 source.zip 또는 source.crx 중 하나만 포함해야 합니다."
        }
        val expected = (METADATA_ENTRIES - DraftSignature.ENTRY_NAME) +
            sourceNames.single()
        require(names == expected) {
            "SixDraft unsigned entry allowlist가 일치하지 않습니다: ${names.sorted()}"
        }
    }

    private fun validateEntrySizes(entries: Map<String, ByteArray>) {
        var total = 0L
        entries.forEach { (name, bytes) ->
            val size = bytes.size.toLong()
            val maximum = if (name in SOURCE_ENTRIES) {
                MAX_SOURCE_SIZE
            } else {
                MAX_METADATA_SIZE
            }
            require(size <= maximum) {
                "SixDraft entry 크기가 제한을 초과합니다: $name"
            }
            require(size <= MAX_TOTAL_SIZE - total) {
                "SixDraft 전체 압축 해제 크기가 제한을 초과합니다."
            }
            total += size
        }
    }

    private inline fun <T> invalidDraftBoundary(
        message: String,
        block: () -> T,
    ): T = try {
        block()
    } catch (error: InvalidSixDraftException) {
        throw error
    } catch (error: Exception) {
        throw InvalidSixDraftException("$message ${error.message}", error)
    }

    private val SOURCE_ENTRIES = setOf("source.zip", "source.crx")
    private val METADATA_ENTRIES = setOf(
        "compatibility-after.json",
        "compatibility-before.json",
        "conversion-plan.json",
        DraftSignature.ENTRY_NAME,
        "draft.json",
        "expected.json",
        "input.json",
    )
    internal const val MAX_ENTRIES = 8
    internal const val MAX_METADATA_SIZE = 1L * 1024 * 1024
    internal const val MAX_SOURCE_SIZE = 512L * 1024 * 1024
    internal const val MAX_TOTAL_SIZE = 520L * 1024 * 1024
    private const val MAX_ARCHIVE_SIZE = MAX_TOTAL_SIZE + 16L * 1024 * 1024
}

private object StrictDraftArchive {
    fun read(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        require(bytes.size >= EOCD_SIZE) {
            "SixDraft ZIP 중앙 디렉터리를 찾지 못했습니다."
        }
        val eocd = bytes.size - EOCD_SIZE
        require(le32(bytes, eocd) == EOCD_SIGNATURE) {
            "SixDraft ZIP 중앙 디렉터리를 찾지 못했습니다."
        }
        require(le16(bytes, eocd + 4) == 0 && le16(bytes, eocd + 6) == 0) {
            "SixDraft multi-disk ZIP은 허용되지 않습니다."
        }
        val diskEntries = le16(bytes, eocd + 8)
        val entryCount = le16(bytes, eocd + 10)
        require(diskEntries == entryCount && entryCount == SixDraft.MAX_ENTRIES) {
            "SixDraft ZIP entry 수가 정확하지 않습니다."
        }
        require(le16(bytes, eocd + 20) == 0) {
            "SixDraft ZIP comment는 허용되지 않습니다."
        }
        val centralSize = unsigned32(bytes, eocd + 12)
        val centralOffset = unsigned32(bytes, eocd + 16)
        require(
            centralSize != ZIP64_SENTINEL &&
                centralOffset != ZIP64_SENTINEL &&
                centralOffset <= Int.MAX_VALUE &&
                centralSize <= Int.MAX_VALUE &&
                centralOffset + centralSize == eocd.toLong(),
        ) {
            "SixDraft ZIP64 또는 손상된 중앙 디렉터리를 거부했습니다."
        }

        val central = readCentral(
            bytes,
            centralOffset.toInt(),
            centralSize.toInt(),
            entryCount,
        )
        val local = readLocal(bytes, centralOffset.toInt())
        require(local.size == central.size) {
            "SixDraft 로컬/중앙 entry 수가 일치하지 않습니다."
        }
        val verified = central.zip(local).map { (expected, actual) ->
            require(expected.matches(actual)) {
                "SixDraft 로컬/중앙 entry가 일치하지 않습니다: ${expected.name}"
            }
            expected.copy(dataOffset = actual.dataOffset)
        }

        var total = 0L
        val result = linkedMapOf<String, ByteArray>()
        verified.forEach { entry ->
            val maximum = if (entry.name == "source.zip" || entry.name == "source.crx") {
                SixDraft.MAX_SOURCE_SIZE
            } else {
                SixDraft.MAX_METADATA_SIZE
            }
            require(entry.uncompressedSize <= maximum) {
                "SixDraft entry 크기가 제한을 초과합니다: ${entry.name}"
            }
            require(entry.uncompressedSize <= SixDraft.MAX_TOTAL_SIZE - total) {
                "SixDraft 전체 압축 해제 크기가 제한을 초과합니다."
            }
            total += entry.uncompressedSize
            require(
                entry.uncompressedSize == 0L ||
                    entry.compressedSize > 0L,
            ) {
                "SixDraft entry 압축 크기가 올바르지 않습니다: ${entry.name}"
            }
            require(
                entry.compressedSize == 0L ||
                    entry.uncompressedSize <=
                    entry.compressedSize * MAX_COMPRESSION_RATIO,
            ) {
                "SixDraft entry 압축 비율이 제한을 초과합니다: ${entry.name}"
            }
            val decoded = decodeEntry(bytes, entry)
            require(decoded.size.toLong() == entry.uncompressedSize) {
                "SixDraft entry 압축 해제 크기가 일치하지 않습니다: ${entry.name}"
            }
            val crc = CRC32().apply { update(decoded) }.value
            require(crc == entry.crc) {
                "SixDraft entry CRC가 일치하지 않습니다: ${entry.name}"
            }
            require(result.put(entry.name, decoded) == null) {
                "SixDraft 중복 entry를 거부했습니다: ${entry.name}"
            }
        }
        return result
    }

    private fun readCentral(
        bytes: ByteArray,
        offset: Int,
        size: Int,
        count: Int,
    ): List<CentralEntry> {
        val end = offset + size
        var cursor = offset
        val names = mutableSetOf<String>()
        val entries = ArrayList<CentralEntry>(count)
        repeat(count) {
            requireRange(bytes, cursor, CENTRAL_HEADER_SIZE)
            require(le32(bytes, cursor) == CENTRAL_SIGNATURE) {
                "SixDraft 중앙 directory entry가 손상되었습니다."
            }
            val flags = le16(bytes, cursor + 8)
            validateFlags(flags)
            val method = le16(bytes, cursor + 10)
            validateMethod(method)
            val compressedSize = unsigned32(bytes, cursor + 20)
            val uncompressedSize = unsigned32(bytes, cursor + 24)
            require(
                compressedSize != ZIP64_SENTINEL &&
                    uncompressedSize != ZIP64_SENTINEL,
            ) {
                "SixDraft entry의 unknown 또는 ZIP64 크기를 거부했습니다."
            }
            val nameLength = le16(bytes, cursor + 28)
            val extraLength = le16(bytes, cursor + 30)
            val commentLength = le16(bytes, cursor + 32)
            require(le16(bytes, cursor + 34) == 0) {
                "SixDraft entry disk 번호가 올바르지 않습니다."
            }
            val bodySize = nameLength.toLong() + extraLength + commentLength
            require(bodySize <= end.toLong() - cursor - CENTRAL_HEADER_SIZE) {
                "SixDraft 중앙 directory entry 길이가 손상되었습니다."
            }
            val name = decodeName(bytes, cursor + CENTRAL_HEADER_SIZE, nameLength)
            validateName(name)
            require(names.add(name)) {
                "SixDraft 중앙 directory 중복 entry를 거부했습니다: $name"
            }
            val madeBy = le16(bytes, cursor + 4)
            val host = madeBy ushr 8
            val externalAttributes = unsigned32(bytes, cursor + 38)
            if (host == UNIX_HOST) {
                val fileType = ((externalAttributes ushr 16).toInt()) and UNIX_TYPE_MASK
                require(fileType == 0 || fileType == UNIX_REGULAR) {
                    "SixDraft symlink 또는 특수 entry를 거부했습니다: $name"
                }
            }
            val localOffset = unsigned32(bytes, cursor + 42)
            require(localOffset != ZIP64_SENTINEL && localOffset <= Int.MAX_VALUE) {
                "SixDraft local entry offset이 올바르지 않습니다."
            }
            if (method == ZipEntry.STORED) {
                require(compressedSize == uncompressedSize) {
                    "SixDraft STORED entry 크기가 일치하지 않습니다: $name"
                }
            }
            entries += CentralEntry(
                name = name,
                flags = flags,
                method = method,
                crc = unsigned32(bytes, cursor + 16),
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                localOffset = localOffset.toInt(),
                dataOffset = -1,
            )
            cursor += CENTRAL_HEADER_SIZE + bodySize.toInt()
        }
        require(cursor == end) {
            "SixDraft 중앙 directory 크기가 실제 entry와 일치하지 않습니다."
        }
        require(entries.map(CentralEntry::name) == entries.map(CentralEntry::name).sorted()) {
            "SixDraft 중앙 directory entry는 이름순이어야 합니다."
        }
        return entries
    }

    private fun readLocal(bytes: ByteArray, centralOffset: Int): List<CentralEntry> {
        var cursor = 0
        val names = mutableSetOf<String>()
        val entries = mutableListOf<CentralEntry>()
        while (cursor < centralOffset) {
            require(entries.size < SixDraft.MAX_ENTRIES) {
                "SixDraft 로컬 entry 수가 제한을 초과합니다."
            }
            requireRange(bytes, cursor, LOCAL_HEADER_SIZE)
            require(le32(bytes, cursor) == LOCAL_SIGNATURE) {
                "SixDraft orphan 또는 손상된 로컬 entry를 거부했습니다."
            }
            val flags = le16(bytes, cursor + 6)
            validateFlags(flags)
            val method = le16(bytes, cursor + 8)
            validateMethod(method)
            val compressedSize = unsigned32(bytes, cursor + 18)
            val uncompressedSize = unsigned32(bytes, cursor + 22)
            require(
                compressedSize != ZIP64_SENTINEL &&
                    uncompressedSize != ZIP64_SENTINEL,
            ) {
                "SixDraft 로컬 entry의 unknown 또는 ZIP64 크기를 거부했습니다."
            }
            val nameLength = le16(bytes, cursor + 26)
            val extraLength = le16(bytes, cursor + 28)
            val headerBody = nameLength.toLong() + extraLength
            require(headerBody <= centralOffset.toLong() - cursor - LOCAL_HEADER_SIZE) {
                "SixDraft 로컬 entry 길이가 손상되었습니다."
            }
            val name = decodeName(bytes, cursor + LOCAL_HEADER_SIZE, nameLength)
            validateName(name)
            require(names.add(name)) {
                "SixDraft 로컬 중복 entry를 거부했습니다: $name"
            }
            val dataOffset = cursor + LOCAL_HEADER_SIZE + headerBody.toInt()
            require(compressedSize <= centralOffset.toLong() - dataOffset) {
                "SixDraft entry 데이터 길이가 손상되었습니다: $name"
            }
            entries += CentralEntry(
                name = name,
                flags = flags,
                method = method,
                crc = unsigned32(bytes, cursor + 14),
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                localOffset = cursor,
                dataOffset = dataOffset,
            )
            cursor = dataOffset + compressedSize.toInt()
        }
        require(cursor == centralOffset) {
            "SixDraft 로컬 entry와 중앙 directory 사이에 잔여 데이터가 있습니다."
        }
        require(entries.map(CentralEntry::name) == entries.map(CentralEntry::name).sorted()) {
            "SixDraft 로컬 entry는 이름순이어야 합니다."
        }
        return entries
    }

    private fun decodeEntry(bytes: ByteArray, central: CentralEntry): ByteArray {
        val compressed = bytes.copyOfRange(
            central.dataOffset,
            central.dataOffset + central.compressedSize.toInt(),
        )
        if (central.method == ZipEntry.STORED) return compressed

        val inflater = Inflater(true)
        return try {
            inflater.setInput(compressed)
            val output = ByteArrayOutputStream(
                minOf(central.uncompressedSize, 8192L).toInt(),
            )
            val buffer = ByteArray(8192)
            var written = 0L
            while (!inflater.finished()) {
                val count = try {
                    inflater.inflate(buffer)
                } catch (error: DataFormatException) {
                    throw IllegalArgumentException(
                        "SixDraft DEFLATED entry가 손상되었습니다: ${central.name}",
                        error,
                    )
                }
                if (count == 0) {
                    require(!inflater.needsDictionary() && !inflater.needsInput()) {
                        "SixDraft DEFLATED entry를 완전히 해제할 수 없습니다: ${central.name}"
                    }
                    throw IllegalArgumentException(
                        "SixDraft DEFLATED entry가 진행되지 않습니다: ${central.name}",
                    )
                }
                require(count.toLong() <= central.uncompressedSize - written) {
                    "SixDraft entry 실제 크기가 선언값을 초과합니다: ${central.name}"
                }
                written += count
                output.write(buffer, 0, count)
            }
            require(inflater.remaining == 0) {
                "SixDraft DEFLATED entry 뒤에 잔여 데이터가 있습니다: ${central.name}"
            }
            output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun CentralEntry.matches(local: CentralEntry): Boolean =
        name == local.name &&
            flags == local.flags &&
            method == local.method &&
            crc == local.crc &&
            compressedSize == local.compressedSize &&
            uncompressedSize == local.uncompressedSize &&
            localOffset == local.localOffset &&
            local.dataOffset >= 0

    private fun validateFlags(flags: Int) {
        require(flags and ENCRYPTED_FLAG == 0) {
            "SixDraft 암호화 entry는 허용되지 않습니다."
        }
        require(flags and DATA_DESCRIPTOR_FLAG == 0) {
            "SixDraft data descriptor entry는 허용되지 않습니다."
        }
    }

    private fun validateMethod(method: Int) {
        require(method == ZipEntry.STORED || method == ZipEntry.DEFLATED) {
            "SixDraft ZIP 압축 method를 지원하지 않습니다."
        }
    }

    private fun validateName(name: String) {
        require(
            name.isNotEmpty() &&
                '/' !in name &&
                '\\' !in name &&
                '\u0000' !in name &&
                name != "." &&
                name != "..",
        ) {
            "SixDraft 경로 탈출 entry를 거부했습니다: $name"
        }
    }

    private fun decodeName(bytes: ByteArray, offset: Int, length: Int): String {
        requireRange(bytes, offset, length)
        return Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, length))
            .toString()
    }

    private fun requireRange(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length) {
            "SixDraft ZIP 구조가 archive 경계를 벗어났습니다."
        }
    }

    private fun le16(bytes: ByteArray, offset: Int): Int {
        requireRange(bytes, offset, 2)
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun le32(bytes: ByteArray, offset: Int): Int {
        requireRange(bytes, offset, 4)
        return le16(bytes, offset) or (le16(bytes, offset + 2) shl 16)
    }

    private fun unsigned32(bytes: ByteArray, offset: Int): Long =
        le32(bytes, offset).toLong() and 0xffff_ffffL

    private data class CentralEntry(
        val name: String,
        val flags: Int,
        val method: Int,
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localOffset: Int,
        val dataOffset: Int,
    )

    private const val LOCAL_SIGNATURE = 0x04034b50
    private const val CENTRAL_SIGNATURE = 0x02014b50
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val LOCAL_HEADER_SIZE = 30
    private const val CENTRAL_HEADER_SIZE = 46
    private const val EOCD_SIZE = 22
    private const val ZIP64_SENTINEL = 0xffff_ffffL
    private const val ENCRYPTED_FLAG = 0x0001
    private const val DATA_DESCRIPTOR_FLAG = 0x0008
    private const val UNIX_HOST = 3
    private const val UNIX_TYPE_MASK = 0xf000
    private const val UNIX_REGULAR = 0x8000
    private const val MAX_COMPRESSION_RATIO = 100L
}
