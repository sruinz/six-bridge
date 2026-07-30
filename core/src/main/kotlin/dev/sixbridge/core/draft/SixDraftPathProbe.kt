package dev.sixbridge.core.draft

import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.core.compat.AnalysisArchiveKind
import dev.sixbridge.core.compat.AnalysisBudgetExceededException
import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.AnalysisInputMaterializer
import dev.sixbridge.core.compat.CompatibilityReport
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import java.util.zip.ZipEntry

sealed interface SixDraftProbe {
    data object NotDraft : SixDraftProbe

    data object MalformedDraft : SixDraftProbe

    data class Valid(
        val schemaVersion: Int,
        val originalExtensionId: String?,
    ) : SixDraftProbe
}

internal object StrictDraftPathProbe {
    fun probe(path: Path, maximumBytes: Long): SixDraftProbe =
        probe(path, maximumBytes, AnalysisControl()) {}

    fun probe(
        path: Path,
        maximumBytes: Long,
        control: AnalysisControl,
    ): SixDraftProbe = probe(path, maximumBytes, control) {}

    internal fun probe(
        path: Path,
        maximumBytes: Long,
        afterValidation: () -> Unit,
    ): SixDraftProbe = probe(
        path,
        maximumBytes,
        AnalysisControl(),
        afterValidation,
    )

    private fun probe(
        path: Path,
        maximumBytes: Long,
        control: AnalysisControl,
        afterValidation: () -> Unit,
    ): SixDraftProbe {
        control.checkCancelled()
        val before = try {
            readPathState(path)
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (_: Exception) {
            return SixDraftProbe.NotDraft
        }
        if (maximumBytes < 0L ||
            maximumBytes > SixDraft.MAX_ARCHIVE_SIZE ||
            before.size > maximumBytes ||
            before.size > SixDraft.MAX_ARCHIVE_SIZE
        ) {
            return SixDraftProbe.MalformedDraft
        }
        val candidate = try {
            FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
                DraftCandidatePreflight.isCandidate(
                    channel,
                    before.size,
                    control,
                )
            }
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        }
        if (!candidate) return SixDraftProbe.NotDraft
        return try {
            val initialHash = sha256(path, before.size, control)
            val result = inspect(path, maximumBytes, control).valid
            afterValidation()
            control.checkCancelled()
            require(readPathState(path) == before) {
                "SixDraft archive가 검증 중 변경되었습니다."
            }
            require(sha256(path, before.size, control) == initialHash) {
                "SixDraft archive가 검증 중 변경되었습니다."
            }
            result
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (_: Exception) {
            SixDraftProbe.MalformedDraft
        }
    }

    internal fun readControlled(
        path: Path,
        maximumBytes: Long,
        control: AnalysisControl,
    ): ControlledSixDraft {
        control.checkCancelled()
        val before = readPathState(path)
        val initialHash = sha256(path, before.size, control)
        return try {
            val result = readControlledVerified(path, maximumBytes, control)
            requireStablePath(path, before, initialHash, control)
            result
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: Throwable) {
            requireStablePath(path, before, initialHash, control)
            throw error
        }
    }

    private fun readControlledVerified(
        path: Path,
        maximumBytes: Long,
        control: AnalysisControl,
    ): ControlledSixDraft {
        control.checkCancelled()
        val descriptor = sourceDescriptor(path, maximumBytes, control)
        if (descriptor.schemaVersion <= 2) {
            try {
                AnalysisInputMaterializer.preflightArchiveRange(
                    path = path,
                    offset = descriptor.sourceOffset,
                    length = descriptor.sourceLength,
                    kind = descriptor.archiveKind,
                    control = control,
                )
            } catch (error: AnalysisBudgetExceededException) {
                return ControlledSixDraft.BudgetExceeded(error.exceeded)
            }
        }
        val inspected = inspect(path, maximumBytes, control)
        control.checkCancelled()
        if (inspected.decoded.draft.schemaVersion == 3) {
            val proof = requireNotNull(inspected.sourceProof) {
                "schema v3 prepared CRX proof가 없습니다."
            }
            return ControlledSixDraft.Prepared(
                VerifiedPreparedSixDraft(
                    draft = inspected.decoded.draft,
                    input = inspected.decoded.input,
                    plan = inspected.plan,
                    compatibilityBefore = inspected.before,
                    compatibilityAfter = inspected.after,
                    expected = inspected.decoded.signerIndependentExpected,
                    signerFingerprint = DraftSignature.fingerprint(
                        inspected.signature.publicKey,
                    ),
                    signerPublicKey = inspected.signature.publicKey,
                    entryNames = inspected.names,
                    preparedCrx = VerifiedPreparedCrxArtifact(
                        path = path,
                        offset = inspected.sourceOffset,
                        length = inspected.sourceLength,
                        proof = proof,
                    ),
                ),
            )
        }
        val bytes = readWholeBounded(path, maximumBytes, control)
        control.checkCancelled()
        return ControlledSixDraft.Legacy(SixDraft.read(bytes))
    }

    private fun requireStablePath(
        path: Path,
        before: PathState,
        initialHash: String,
        control: AnalysisControl,
    ) {
        require(readPathState(path) == before) {
            "SixDraft archive가 검증 중 변경되었습니다."
        }
        require(sha256(path, before.size, control) == initialHash) {
            "SixDraft archive가 검증 중 변경되었습니다."
        }
    }

    private fun inspect(
        path: Path,
        maximumBytes: Long,
        control: AnalysisControl,
    ): PathInspection {
        require(maximumBytes >= 0L && maximumBytes <= SixDraft.MAX_ARCHIVE_SIZE) {
            "SixDraft caller archive 제한이 올바르지 않습니다."
        }
        return FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            require(size <= maximumBytes && size <= SixDraft.MAX_ARCHIVE_SIZE) {
                "SixDraft archive 크기가 제한을 초과합니다."
            }
            val entries = StrictPathArchive.index(channel, size)
            val names = entries.mapTo(linkedSetOf()) { it.name }
            require(names.size == entries.size) {
                "SixDraft 중복 entry를 거부했습니다."
            }
            val sourceNames = names.intersect(SixDraft.SOURCE_ENTRIES)
            require(sourceNames.size == 1) {
                "SixDraft source entry 수가 올바르지 않습니다."
            }
            require(names == SixDraft.METADATA_ENTRIES + sourceNames.single()) {
                "SixDraft entry allowlist가 일치하지 않습니다."
            }

            var total = 0L
            val metadata = linkedMapOf<String, ByteArray>()
            val digests = linkedMapOf<String, DraftEntryDigest>()
            entries.forEach { entry ->
                control.checkCancelled()
                val isSource = entry.name in SixDraft.SOURCE_ENTRIES
                val maximum = if (isSource) {
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
                total += entry.uncompressedSize
                if (isSource) {
                    require(entry.method == ZipEntry.STORED) {
                        "SixDraft source entry는 STORED method여야 합니다."
                    }
                    val digest = digestStored(channel, entry, control)
                    digests[entry.name] = digest
                } else {
                    val decoded = decodeMetadata(channel, entry, control)
                    metadata[entry.name] = decoded
                    digests[entry.name] = DraftEntryDigest(
                        size = decoded.size.toLong(),
                        sha256 = sha256(decoded),
                    )
                }
            }
            val sourceName = sourceNames.single()
            val sourceEntry = entries.single { it.name == sourceName }
            val sourceKind = if (sourceName == DraftSourceKind.ZIP.entryName) {
                DraftSourceKind.ZIP
            } else if (sourceName == DraftSourceKind.PREPARED_CRX.entryName) {
                DraftSourceKind.PREPARED_CRX
            } else {
                DraftSourceKind.CRX
            }
            val sourceDigest = digests.getValue(sourceName)
            val sourceProof: VerifiedCrx3?
            val sourceEvidence = if (
                sourceKind == DraftSourceKind.CRX ||
                sourceKind == DraftSourceKind.PREPARED_CRX
            ) {
                val verified = Crx3Verifier.verify(
                    path = path,
                    offset = sourceEntry.dataOffset,
                    length = sourceEntry.uncompressedSize,
                    maximumBytes = sourceEntry.uncompressedSize,
                    checkpoint = control::checkCancelled,
                )
                sourceProof = verified
                DraftSourceEvidence(
                    actualSha256 = verified.zipSha256,
                    crxExtensionId = verified.extensionId,
                )
            } else {
                sourceProof = null
                DraftSourceEvidence(
                    actualSha256 = sourceDigest.sha256,
                    crxExtensionId = null,
                )
            }
            val signature = SixDraftJson.decodeSignature(
                metadata.getValue(DraftSignature.ENTRY_NAME),
            )
            require(DraftSignature.verifyDigests(digests, signature)) {
                "SixDraft Ed25519 전달 서명이 일치하지 않습니다."
            }
            val decoded = SixDraftPayload.inspect(
                draftJson = metadata.getValue("draft.json"),
                inputJson = metadata.getValue("input.json"),
                sourceKind = sourceKind,
                sourceEvidence = sourceEvidence,
                conversionPlanJson = metadata.getValue("conversion-plan.json"),
                compatibilityBeforeJson = metadata.getValue("compatibility-before.json"),
                compatibilityAfterJson = metadata.getValue("compatibility-after.json"),
                expectedJson = metadata.getValue("expected.json"),
            )
            require(channel.size() == size) {
                "SixDraft archive가 검증 중 변경되었습니다."
            }
            control.checkCancelled()
            PathInspection(
                valid = SixDraftProbe.Valid(
                    schemaVersion = decoded.draft.schemaVersion,
                    originalExtensionId = decoded.input.originalExtensionId,
                ),
                decoded = decoded,
                plan = SixDraftJson.decodePlan(
                    metadata.getValue("conversion-plan.json"),
                ),
                before = SixDraftJson.decodeCompatibility(
                    "compatibility-before.json",
                    metadata.getValue("compatibility-before.json"),
                ),
                after = SixDraftJson.decodeCompatibility(
                    "compatibility-after.json",
                    metadata.getValue("compatibility-after.json"),
                ),
                signature = signature,
                names = names,
                sourceOffset = sourceEntry.dataOffset,
                sourceLength = sourceEntry.uncompressedSize,
                sourceProof = sourceProof,
            )
        }
    }

    private fun digestStored(
        channel: FileChannel,
        entry: PathEntry,
        control: AnalysisControl,
    ): DraftEntryDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        readRange(
            channel,
            entry.dataOffset,
            entry.compressedSize,
            control,
        ) { buffer, count ->
            digest.update(buffer.array(), 0, count)
            crc.update(buffer.array(), 0, count)
        }
        require(crc.value == entry.crc) {
            "SixDraft entry CRC가 일치하지 않습니다: ${entry.name}"
        }
        return DraftEntryDigest(
            size = entry.uncompressedSize,
            sha256 = digest.digest().toHex(),
        )
    }

    private fun decodeMetadata(
        channel: FileChannel,
        entry: PathEntry,
        control: AnalysisControl,
    ): ByteArray {
        require(entry.uncompressedSize <= SixDraft.MAX_METADATA_SIZE) {
            "SixDraft metadata entry가 너무 큽니다."
        }
        val decoded = if (entry.method == ZipEntry.STORED) {
            readBytes(channel, entry.dataOffset, entry.compressedSize.toInt())
        } else {
            inflate(channel, entry, control)
        }
        require(decoded.size.toLong() == entry.uncompressedSize) {
            "SixDraft entry 압축 해제 크기가 일치하지 않습니다: ${entry.name}"
        }
        require(CRC32().apply { update(decoded) }.value == entry.crc) {
            "SixDraft entry CRC가 일치하지 않습니다: ${entry.name}"
        }
        return decoded
    }

    private fun inflate(
        channel: FileChannel,
        entry: PathEntry,
        control: AnalysisControl,
    ): ByteArray {
        val inflater = Inflater(true)
        val output = ByteArrayOutputStream(
            minOf(entry.uncompressedSize, 8192L).toInt(),
        )
        val decoded = ByteArray(STREAM_BUFFER_SIZE)
        var position = entry.dataOffset
        var remaining = entry.compressedSize
        try {
            while (remaining > 0L && !inflater.finished()) {
                control.checkCancelled()
                val count = minOf(STREAM_BUFFER_SIZE.toLong(), remaining).toInt()
                val chunk = readBytes(channel, position, count)
                inflater.setInput(chunk)
                position += count
                remaining -= count
                while (!inflater.needsInput() && !inflater.finished()) {
                    control.checkCancelled()
                    val written = try {
                        inflater.inflate(decoded)
                    } catch (error: DataFormatException) {
                        throw IllegalArgumentException(
                            "SixDraft DEFLATED entry가 손상되었습니다.",
                            error,
                        )
                    }
                    require(written > 0 || inflater.finished()) {
                        "SixDraft DEFLATED entry가 진행되지 않습니다."
                    }
                    require(output.size().toLong() + written <= entry.uncompressedSize) {
                        "SixDraft entry 실제 크기가 선언값을 초과합니다."
                    }
                    output.write(decoded, 0, written)
                }
            }
            require(
                inflater.finished() &&
                    inflater.bytesRead == entry.compressedSize &&
                    remaining == 0L,
            ) {
                "SixDraft DEFLATED entry를 완전히 해제할 수 없습니다."
            }
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun sha256(
        path: Path,
        expectedSize: Long,
        control: AnalysisControl,
    ): String =
        FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            require(channel.size() == expectedSize) {
                "SixDraft archive가 검증 중 변경되었습니다."
            }
            val digest = MessageDigest.getInstance("SHA-256")
            readRange(
                channel,
                0L,
                expectedSize,
                control,
            ) { buffer, count ->
                digest.update(buffer.array(), 0, count)
            }
            require(channel.size() == expectedSize) {
                "SixDraft archive가 검증 중 변경되었습니다."
            }
            digest.digest().toHex()
        }

    private fun readPathState(path: Path): PathState {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            NOFOLLOW_LINKS,
        )
        require(attributes.isRegularFile && !attributes.isSymbolicLink) {
            "SixDraft 경로는 일반 파일이어야 합니다."
        }
        return PathState(
            fileKey = attributes.fileKey(),
            size = attributes.size(),
            modifiedTime = attributes.lastModifiedTime(),
        )
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun readRange(
        channel: FileChannel,
        offset: Long,
        length: Long,
        control: AnalysisControl,
        consume: (ByteBuffer, Int) -> Unit,
    ) {
        val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
        var position = offset
        var remaining = length
        while (remaining > 0L) {
            control.checkCancelled()
            buffer.clear()
            buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
            val count = channel.read(buffer, position)
            require(count > 0) { "SixDraft entry를 완전히 읽지 못했습니다." }
            consume(buffer, count)
            position += count
            remaining -= count
        }
    }

    private data class PathState(
        val fileKey: Any?,
        val size: Long,
        val modifiedTime: FileTime,
    )

    private fun sourceDescriptor(
        path: Path,
        maximumBytes: Long,
        control: AnalysisControl,
    ): SourceDescriptor = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
        require(channel.size() <= maximumBytes) {
            "SixDraft archive 크기가 제한을 초과합니다."
        }
        val entries = StrictPathArchive.index(channel, channel.size())
        val source = entries.single { it.name in SixDraft.SOURCE_ENTRIES }
        require(source.method == ZipEntry.STORED) {
            "SixDraft source entry는 STORED method여야 합니다."
        }
        val draftEntry = entries.single { it.name == "draft.json" }
        val draft = SixDraftJson.decodeDraft(
            decodeMetadata(channel, draftEntry, control),
        )
        val kind = if (source.name == DraftSourceKind.ZIP.entryName) {
            AnalysisArchiveKind.ZIP
        } else {
            AnalysisArchiveKind.CRX3
        }
        SourceDescriptor(
            schemaVersion = draft.schemaVersion,
            archiveKind = kind,
            sourceOffset = source.dataOffset,
            sourceLength = source.uncompressedSize,
        )
    }

    private fun readWholeBounded(
        path: Path,
        maximumBytes: Long,
        control: AnalysisControl,
    ): ByteArray = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
        val size = channel.size()
        require(size <= maximumBytes && size <= Int.MAX_VALUE) {
            "legacy SixDraft archive 크기가 제한을 초과합니다."
        }
        val bytes = ByteArray(size.toInt())
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            control.checkCancelled()
            val count = channel.read(buffer)
            require(count > 0) { "legacy SixDraft archive를 완전히 읽지 못했습니다." }
        }
        bytes
    }

    private data class SourceDescriptor(
        val schemaVersion: Int,
        val archiveKind: AnalysisArchiveKind,
        val sourceOffset: Long,
        val sourceLength: Long,
    )

    private data class PathInspection(
        val valid: SixDraftProbe.Valid,
        val decoded: DecodedSixDraftMetadata,
        val plan: DraftConversionPlan,
        val before: CompatibilityReport,
        val after: CompatibilityReport,
        val signature: DraftSignatureRecord,
        val names: Set<String>,
        val sourceOffset: Long,
        val sourceLength: Long,
        val sourceProof: VerifiedCrx3?,
    )
}

private object DraftCandidatePreflight {
    fun isCandidate(
        channel: FileChannel,
        size: Long,
        control: AnalysisControl,
    ): Boolean {
        control.checkCancelled()
        val eocd = findEocd(channel, size) ?: return false
        if (eocd.diskNumber != 0 ||
            eocd.centralDisk != 0 ||
            eocd.diskEntries != eocd.entryCount ||
            eocd.entryCount > MAX_PREFLIGHT_ENTRIES ||
            eocd.centralSize > MAX_PREFLIGHT_CENTRAL_BYTES ||
            eocd.centralOffset > eocd.offset ||
            eocd.centralSize != eocd.offset - eocd.centralOffset
        ) {
            return false
        }

        var cursor = eocd.centralOffset
        val centralEnd = eocd.offset
        var nameBytes = 0L
        var metadataMask = 0
        var sourceMask = 0
        var sourceOccurrences = 0
        var matchedOccurrences = 0
        repeat(eocd.entryCount) {
            control.checkCancelled()
            if (cursor > centralEnd - CENTRAL_HEADER_SIZE) return false
            val header = readBytes(channel, cursor, CENTRAL_HEADER_SIZE)
            if (le32(header, 0) != CENTRAL_SIGNATURE) return false
            val nameLength = le16(header, 28)
            val extraLength = le16(header, 30)
            val commentLength = le16(header, 32)
            val bodySize = nameLength.toLong() + extraLength + commentLength
            if (bodySize > centralEnd - cursor - CENTRAL_HEADER_SIZE) return false
            if (nameLength > MAX_PREFLIGHT_NAME_LENGTH ||
                nameBytes > MAX_PREFLIGHT_NAME_BYTES - nameLength
            ) {
                return false
            }
            nameBytes += nameLength
            val name = readBytes(
                channel,
                cursor + CENTRAL_HEADER_SIZE,
                nameLength,
            )
            val metadataIndex = METADATA_NAMES.indexOfFirst(name::contentEquals)
            if (metadataIndex >= 0) {
                metadataMask = metadataMask or (1 shl metadataIndex)
                matchedOccurrences += 1
            } else {
                val sourceIndex = SOURCE_NAMES.indexOfFirst(name::contentEquals)
                if (sourceIndex >= 0) {
                    sourceMask = sourceMask or (1 shl sourceIndex)
                    sourceOccurrences += 1
                    matchedOccurrences += 1
                }
            }
            cursor += CENTRAL_HEADER_SIZE + bodySize
        }
        if (cursor != centralEnd) return false

        val oneSource = Integer.bitCount(sourceMask) == 1 &&
            sourceOccurrences == 1
        val exact = oneSource &&
            eocd.entryCount == SixDraft.MAX_ENTRIES &&
            matchedOccurrences == SixDraft.MAX_ENTRIES &&
            metadataMask == ALL_METADATA_MASK
        val signedPartial = oneSource &&
            metadataMask and SIGNATURE_MASK != 0 &&
            Integer.bitCount(metadataMask) >= 2
        val unsignedCanonical = oneSource &&
            metadataMask and SIGNATURE_MASK == 0 &&
            metadataMask and UNSIGNED_METADATA_MASK == UNSIGNED_METADATA_MASK
        return exact || signedPartial || unsignedCanonical
    }

    private fun findEocd(channel: FileChannel, size: Long): PreflightEocd? {
        if (size < EOCD_SIZE) return null
        val tailSize = minOf(size, EOCD_SIZE + MAX_ZIP_COMMENT).toInt()
        val tailOffset = size - tailSize
        val tail = readBytes(channel, tailOffset, tailSize)
        for (offset in tail.size - EOCD_SIZE downTo 0) {
            if (le32(tail, offset) != EOCD_SIGNATURE) continue
            val commentLength = le16(tail, offset + 20)
            if (offset + EOCD_SIZE + commentLength != tail.size) continue
            return PreflightEocd(
                offset = tailOffset + offset,
                diskNumber = le16(tail, offset + 4),
                centralDisk = le16(tail, offset + 6),
                diskEntries = le16(tail, offset + 8),
                entryCount = le16(tail, offset + 10),
                centralSize = unsigned32(tail, offset + 12),
                centralOffset = unsigned32(tail, offset + 16),
            )
        }
        return null
    }

    private data class PreflightEocd(
        val offset: Long,
        val diskNumber: Int,
        val centralDisk: Int,
        val diskEntries: Int,
        val entryCount: Int,
        val centralSize: Long,
        val centralOffset: Long,
    )

    private val METADATA_NAMES = listOf(
        "draft.json",
        "input.json",
        "conversion-plan.json",
        "compatibility-before.json",
        "compatibility-after.json",
        "expected.json",
        DraftSignature.ENTRY_NAME,
    ).map(String::encodeToByteArray)
    private val SOURCE_NAMES = listOf(
        DraftSourceKind.ZIP.entryName,
        DraftSourceKind.CRX.entryName,
        DraftSourceKind.PREPARED_CRX.entryName,
    ).map(String::encodeToByteArray)
    private const val SIGNATURE_INDEX = 6
    private const val SIGNATURE_MASK = 1 shl SIGNATURE_INDEX
    private const val UNSIGNED_METADATA_MASK = SIGNATURE_MASK - 1
    private const val ALL_METADATA_MASK = (SIGNATURE_MASK shl 1) - 1
    private const val MAX_PREFLIGHT_ENTRIES = 10_000
    private const val MAX_PREFLIGHT_CENTRAL_BYTES = 8L * 1024 * 1024
    private const val MAX_PREFLIGHT_NAME_BYTES = 64L * 1024
    private const val MAX_PREFLIGHT_NAME_LENGTH = 255
    private const val MAX_ZIP_COMMENT = 65_535L
}

private object StrictPathArchive {
    fun index(channel: FileChannel, size: Long): List<PathEntry> {
        require(size >= EOCD_SIZE) {
            "SixDraft ZIP 중앙 디렉터리를 찾지 못했습니다."
        }
        val eocdOffset = size - EOCD_SIZE
        val eocd = readBytes(channel, eocdOffset, EOCD_SIZE)
        require(le32(eocd, 0) == EOCD_SIGNATURE) {
            "SixDraft ZIP 중앙 디렉터리를 찾지 못했습니다."
        }
        require(le16(eocd, 4) == 0 && le16(eocd, 6) == 0) {
            "SixDraft multi-disk ZIP은 허용되지 않습니다."
        }
        val diskEntries = le16(eocd, 8)
        val entryCount = le16(eocd, 10)
        require(diskEntries == entryCount && entryCount == SixDraft.MAX_ENTRIES) {
            "SixDraft ZIP entry 수가 정확하지 않습니다."
        }
        require(le16(eocd, 20) == 0) {
            "SixDraft ZIP comment는 허용되지 않습니다."
        }
        val centralSize = unsigned32(eocd, 12)
        val centralOffset = unsigned32(eocd, 16)
        require(
            centralSize != ZIP64_SENTINEL &&
                centralOffset != ZIP64_SENTINEL &&
                centralOffset + centralSize == eocdOffset,
        ) {
            "SixDraft ZIP64 또는 손상된 중앙 디렉터리를 거부했습니다."
        }
        val central = readCentral(
            channel,
            centralOffset,
            centralSize,
            entryCount,
        )
        val local = readLocal(channel, centralOffset)
        require(local.size == central.size) {
            "SixDraft 로컬/중앙 entry 수가 일치하지 않습니다."
        }
        return central.zip(local).map { (expected, actual) ->
            require(expected.matches(actual)) {
                "SixDraft 로컬/중앙 entry가 일치하지 않습니다: ${expected.name}"
            }
            expected.copy(dataOffset = actual.dataOffset)
        }
    }

    private fun readCentral(
        channel: FileChannel,
        offset: Long,
        size: Long,
        count: Int,
    ): List<PathEntry> {
        val end = offset + size
        var cursor = offset
        val names = mutableSetOf<String>()
        val entries = ArrayList<PathEntry>(count)
        repeat(count) {
            val header = readBytes(channel, cursor, CENTRAL_HEADER_SIZE)
            require(le32(header, 0) == CENTRAL_SIGNATURE) {
                "SixDraft 중앙 directory entry가 손상되었습니다."
            }
            val flags = le16(header, 8)
            validateFlags(flags)
            val method = le16(header, 10)
            validateMethod(method)
            val compressedSize = unsigned32(header, 20)
            val uncompressedSize = unsigned32(header, 24)
            require(
                compressedSize != ZIP64_SENTINEL &&
                    uncompressedSize != ZIP64_SENTINEL,
            ) {
                "SixDraft entry의 unknown 또는 ZIP64 크기를 거부했습니다."
            }
            val nameLength = le16(header, 28)
            val extraLength = le16(header, 30)
            val commentLength = le16(header, 32)
            require(le16(header, 34) == 0) {
                "SixDraft entry disk 번호가 올바르지 않습니다."
            }
            val bodySize = nameLength.toLong() + extraLength + commentLength
            require(bodySize <= end - cursor - CENTRAL_HEADER_SIZE) {
                "SixDraft 중앙 directory entry 길이가 손상되었습니다."
            }
            val body = readBytes(
                channel,
                cursor + CENTRAL_HEADER_SIZE,
                bodySize.toInt(),
            )
            val name = decodeName(body, 0, nameLength)
            validateName(name)
            validateExtra(body, nameLength, extraLength)
            require(names.add(name)) {
                "SixDraft 중앙 directory 중복 entry를 거부했습니다."
            }
            val madeBy = le16(header, 4)
            val host = madeBy ushr 8
            val externalAttributes = unsigned32(header, 38)
            require(externalAttributes and DOS_SPECIAL_ATTRIBUTES == 0L) {
                "SixDraft DOS directory 또는 특수 entry를 거부했습니다."
            }
            if (host == UNIX_HOST) {
                val fileType = ((externalAttributes ushr 16).toInt()) and UNIX_TYPE_MASK
                require(fileType == 0 || fileType == UNIX_REGULAR) {
                    "SixDraft symlink 또는 특수 entry를 거부했습니다."
                }
            }
            val localOffset = unsigned32(header, 42)
            require(localOffset != ZIP64_SENTINEL) {
                "SixDraft local entry offset이 올바르지 않습니다."
            }
            if (method == ZipEntry.STORED) {
                require(compressedSize == uncompressedSize) {
                    "SixDraft STORED entry 크기가 일치하지 않습니다."
                }
            }
            entries += PathEntry(
                name = name,
                flags = flags,
                method = method,
                crc = unsigned32(header, 16),
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                localOffset = localOffset,
                dataOffset = -1L,
            )
            cursor += CENTRAL_HEADER_SIZE + bodySize
        }
        require(cursor == end) {
            "SixDraft 중앙 directory 크기가 실제 entry와 일치하지 않습니다."
        }
        require(entries.map(PathEntry::name) == entries.map(PathEntry::name).sorted()) {
            "SixDraft 중앙 directory entry는 이름순이어야 합니다."
        }
        return entries
    }

    private fun readLocal(
        channel: FileChannel,
        centralOffset: Long,
    ): List<PathEntry> {
        var cursor = 0L
        val names = mutableSetOf<String>()
        val entries = mutableListOf<PathEntry>()
        while (cursor < centralOffset) {
            require(entries.size < SixDraft.MAX_ENTRIES) {
                "SixDraft 로컬 entry 수가 제한을 초과합니다."
            }
            val header = readBytes(channel, cursor, LOCAL_HEADER_SIZE)
            require(le32(header, 0) == LOCAL_SIGNATURE) {
                "SixDraft orphan 또는 손상된 로컬 entry를 거부했습니다."
            }
            val flags = le16(header, 6)
            validateFlags(flags)
            val method = le16(header, 8)
            validateMethod(method)
            val compressedSize = unsigned32(header, 18)
            val uncompressedSize = unsigned32(header, 22)
            require(
                compressedSize != ZIP64_SENTINEL &&
                    uncompressedSize != ZIP64_SENTINEL,
            ) {
                "SixDraft 로컬 entry의 unknown 또는 ZIP64 크기를 거부했습니다."
            }
            val nameLength = le16(header, 26)
            val extraLength = le16(header, 28)
            val headerBody = nameLength.toLong() + extraLength
            require(headerBody <= centralOffset - cursor - LOCAL_HEADER_SIZE) {
                "SixDraft 로컬 entry 길이가 손상되었습니다."
            }
            val body = readBytes(
                channel,
                cursor + LOCAL_HEADER_SIZE,
                headerBody.toInt(),
            )
            val name = decodeName(body, 0, nameLength)
            validateName(name)
            validateExtra(body, nameLength, extraLength)
            require(names.add(name)) {
                "SixDraft 로컬 중복 entry를 거부했습니다."
            }
            val dataOffset = cursor + LOCAL_HEADER_SIZE + headerBody
            require(compressedSize <= centralOffset - dataOffset) {
                "SixDraft entry 데이터 길이가 손상되었습니다."
            }
            entries += PathEntry(
                name = name,
                flags = flags,
                method = method,
                crc = unsigned32(header, 14),
                compressedSize = compressedSize,
                uncompressedSize = uncompressedSize,
                localOffset = cursor,
                dataOffset = dataOffset,
            )
            cursor = dataOffset + compressedSize
        }
        require(cursor == centralOffset) {
            "SixDraft 로컬 entry와 중앙 directory 사이에 잔여 데이터가 있습니다."
        }
        require(entries.map(PathEntry::name) == entries.map(PathEntry::name).sorted()) {
            "SixDraft 로컬 entry는 이름순이어야 합니다."
        }
        return entries
    }
}

private data class PathEntry(
    val name: String,
    val flags: Int,
    val method: Int,
    val crc: Long,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val localOffset: Long,
    val dataOffset: Long,
) {
    fun matches(local: PathEntry): Boolean =
        name == local.name &&
            flags == local.flags &&
            method == local.method &&
            crc == local.crc &&
            compressedSize == local.compressedSize &&
            uncompressedSize == local.uncompressedSize &&
            localOffset == local.localOffset &&
            local.dataOffset >= 0L
}

private fun readBytes(
    channel: FileChannel,
    offset: Long,
    length: Int,
): ByteArray {
    require(length >= 0 && offset >= 0L && offset <= channel.size() - length) {
        "SixDraft ZIP 구조가 archive 경계를 벗어났습니다."
    }
    val result = ByteArray(length)
    val buffer = ByteBuffer.wrap(result)
    var position = offset
    while (buffer.hasRemaining()) {
        val count = channel.read(buffer, position)
        require(count > 0) { "SixDraft archive를 완전히 읽지 못했습니다." }
        position += count
    }
    return result
}

private fun validateFlags(flags: Int) {
    require(flags and ALLOWED_FLAGS.inv() == 0) {
        "SixDraft 암호화, reserved 또는 지원하지 않는 flag는 허용되지 않습니다."
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
        "SixDraft 경로 탈출 entry를 거부했습니다."
    }
}

private fun validateExtra(bytes: ByteArray, offset: Int, length: Int) {
    var cursor = offset
    val end = offset + length
    require(offset >= 0 && length >= 0 && end <= bytes.size) {
        "SixDraft ZIP extra field가 손상되었습니다."
    }
    while (cursor < end) {
        require(end - cursor >= 4) {
            "SixDraft ZIP extra field가 손상되었습니다."
        }
        val id = le16(bytes, cursor)
        val size = le16(bytes, cursor + 2)
        cursor += 4
        require(size <= end - cursor) {
            "SixDraft ZIP extra field 길이가 손상되었습니다."
        }
        require(id != ZIP64_EXTRA_ID) {
            "SixDraft ZIP64 extra field는 허용되지 않습니다."
        }
        cursor += size
    }
}

private fun decodeName(bytes: ByteArray, offset: Int, length: Int): String =
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, offset, length))
        .toString()

private fun le16(bytes: ByteArray, offset: Int): Int {
    require(offset >= 0 && offset <= bytes.size - 2) {
        "SixDraft ZIP 구조가 archive 경계를 벗어났습니다."
    }
    return (bytes[offset].toInt() and 0xff) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8)
}

private fun le32(bytes: ByteArray, offset: Int): Int =
    le16(bytes, offset) or (le16(bytes, offset + 2) shl 16)

private fun unsigned32(bytes: ByteArray, offset: Int): Long =
    le32(bytes, offset).toLong() and 0xffff_ffffL

private const val LOCAL_SIGNATURE = 0x04034b50
private const val CENTRAL_SIGNATURE = 0x02014b50
private const val EOCD_SIGNATURE = 0x06054b50
private const val LOCAL_HEADER_SIZE = 30
private const val CENTRAL_HEADER_SIZE = 46
private const val EOCD_SIZE = 22
private const val ZIP64_SENTINEL = 0xffff_ffffL
private const val UTF8_FLAG = 0x0800
private const val ALLOWED_FLAGS = UTF8_FLAG
private const val ZIP64_EXTRA_ID = 0x0001
private const val UNIX_HOST = 3
private const val UNIX_TYPE_MASK = 0xf000
private const val UNIX_REGULAR = 0x8000
private const val DOS_SPECIAL_ATTRIBUTES = 0x10L or 0x40L
private const val MAX_COMPRESSION_RATIO = 100L
private const val STREAM_BUFFER_SIZE = 64 * 1024
