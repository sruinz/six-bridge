package dev.sixbridge.core.profile

import java.io.FilterInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.DigestInputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream

interface BrowserArchiveInput {
    val name: String
    val sha256: String
    fun openStream(): InputStream
}

data class BrowserPackageEvidence(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signerSha256: String,
    val baseApk: BrowserArchiveInput,
    val splitApks: List<BrowserArchiveInput>,
) {
    fun installationApkSetSha256(): String =
        BrowserArchiveEvidenceScanner.installationApkSetSha256(baseApk, splitApks)
}

data class BrowserArchiveScanLimits(
    val maxArchiveCount: Int = 64,
    val maxRawBytesPerArchive: Long = 512L * 1024 * 1024,
    val maxTotalRawBytes: Long = 1024L * 1024 * 1024,
    val maxEntryCountPerArchive: Int = 20_000,
    val maxInflatedBytesPerEntry: Long = 256L * 1024 * 1024,
    val maxTotalInflatedBytesPerArchive: Long = 1024L * 1024 * 1024,
    val maxInflateRatio: Int = 100,
) {
    init {
        require(maxArchiveCount > 0)
        require(maxRawBytesPerArchive > 0)
        require(maxTotalRawBytes > 0)
        require(maxEntryCountPerArchive > 0)
        require(maxInflatedBytesPerEntry > 0)
        require(maxTotalInflatedBytesPerArchive > 0)
        require(maxInflateRatio > 0)
    }
}

enum class BrowserArchiveEvidenceFailure {
    INVALID_EVIDENCE,
    DECLARED_SHA256_MISMATCH,
    CONTENT_CHANGED,
    READ_FAILED,
    RESOURCE_LIMIT_EXCEEDED,
}

class BrowserArchiveEvidenceException(
    val failure: BrowserArchiveEvidenceFailure,
    val archiveName: String?,
    cause: Throwable? = null,
) : IllegalStateException(
    listOfNotNull(failure.name.lowercase(), archiveName).joinToString(":"),
    cause,
)

class BrowserArchiveEvidenceScanner(
    private val checkCancelled: () -> Unit = {},
    private val limits: BrowserArchiveScanLimits = BrowserArchiveScanLimits(),
) {
    fun scan(evidence: BrowserPackageEvidence): BrowserProfile {
        if (1 + evidence.splitApks.size > limits.maxArchiveCount) {
            limitExceeded(evidence.baseApk.name)
        }
        val archives = canonicalArchives(
            evidence.baseApk,
            evidence.splitApks,
            limits.maxArchiveCount,
        )
        val scan = ArchiveScan()
        var totalRawBytes = 0L
        archives.forEach { canonical ->
            val archive = canonical.input
            val firstSha256 = digest(archive)
            val secondSha256 = scanAndDigest(archive, scan)
            totalRawBytes += firstSha256.byteCount
            if (totalRawBytes > limits.maxTotalRawBytes) {
                limitExceeded(archive.name)
            }
            if (
                firstSha256.sha256 != secondSha256.sha256 ||
                firstSha256.byteCount != secondSha256.byteCount
            ) {
                throw BrowserArchiveEvidenceException(
                    BrowserArchiveEvidenceFailure.CONTENT_CHANGED,
                    archive.name,
                )
            }
            if (firstSha256.sha256 != archive.sha256) {
                throw BrowserArchiveEvidenceException(
                    BrowserArchiveEvidenceFailure.DECLARED_SHA256_MISMATCH,
                    archive.name,
                )
            }
        }

        val versionName = evidence.versionName.takeUnless { it == "unknown" }
            ?: scan.versionName
            ?: "unknown"
        val versionCode = evidence.versionCode.takeUnless { it == 0L }
            ?: scan.versionCode
            ?: 0L
        val majorVersion = versionName.substringBefore('.').toIntOrNull() ?: 0
        val compareNamespaces = (
            majorVersion == BrowserProfile.SUPPORTED_MAJOR_VERSION &&
                (
                    versionName != BrowserProfile.BASELINE_VERSION ||
                        evidence.splitApks.isNotEmpty()
                    )
            )
        val differences = if (compareNamespaces) {
            buildList {
                if (
                    versionName == BrowserProfile.BASELINE_VERSION &&
                    evidence.splitApks.isNotEmpty()
                ) {
                    evidence.splitApks
                        .map { canonicalName(it.name) }
                        .sorted()
                        .forEach { add("unexpected_split:$it") }
                }
                (BrowserProfile.BASELINE_APIS - scan.namespaces).sorted().forEach {
                    add("missing_api:$it")
                }
                (scan.namespaces - BrowserProfile.BASELINE_APIS).sorted().forEach {
                    add("added_api:$it")
                }
            }
        } else {
            emptyList()
        }

        return BrowserProfile(
            browserPackage = evidence.packageName,
            versionName = versionName,
            versionCode = versionCode,
            majorVersion = majorVersion,
            supportedApis = (
                scan.namespaces intersect BrowserProfile.POC_SUPPORTED_APIS
                ).toSortedSet(),
            detectedApis = scan.namespaces.toSortedSet(),
            wrapperContract = WrapperContract(
                discoveryUri = "six://ex" in scan.markers,
                extensionMetadata = "extension-id" in scan.markers,
                contentProvider = ".contentprovider" in scan.markers,
                devExtensionPath = "dev_ext" in scan.markers,
            ),
            sourceApkSha256 = evidence.baseApk.sha256,
            installationApkSetSha256 = evidence.installationApkSetSha256(),
            sourceApkSignerSha256 = evidence.signerSha256,
            profileDifferences = differences,
            supportedFeatures = scan.supportedFeatures(),
        )
    }

    private fun digest(archive: BrowserArchiveInput): ArchiveDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteCount = 0L
        readArchive(archive) { input ->
            val bounded = BoundedRawInputStream(input, archive.name)
            drain(bounded) { buffer, read ->
                digest.update(buffer, 0, read)
            }
            byteCount = bounded.byteCount
        }
        return ArchiveDigest(digest.hex(), byteCount)
    }

    private fun scanAndDigest(
        archive: BrowserArchiveInput,
        scan: ArchiveScan,
    ): ArchiveDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        var rawByteCount = 0L
        readArchive(archive) { input ->
            val raw = BoundedRawInputStream(input, archive.name)
            val digested = DigestInputStream(raw, digest)
            val budget = InflateBudget(
                archiveName = archive.name,
                rawBytes = { raw.byteCount },
            )
            ZipInputStream(NonClosingInputStream(digested)).use { zip ->
                while (true) {
                    checkCancelled()
                    val entry = zip.nextEntry ?: break
                    budget.entryCount += 1
                    if (budget.entryCount > limits.maxEntryCountPerArchive) {
                        limitExceeded(archive.name)
                    }
                    val boundedEntry = BoundedEntryInputStream(zip, budget)
                    when {
                        entry.name.endsWith(".dex") ->
                            scan.scanContractData(boundedEntry)
                        entry.name.endsWith("/libterrace.so") ->
                            scan.scanTerraceLibrary(boundedEntry)
                        else -> drain(boundedEntry)
                    }
                    zip.closeEntry()
                }
            }
            drain(digested)
            rawByteCount = raw.byteCount
            if (
                budget.totalInflatedBytes >
                rawByteCount * limits.maxInflateRatio.toLong()
            ) {
                limitExceeded(archive.name)
            }
        }
        return ArchiveDigest(digest.hex(), rawByteCount)
    }

    private fun readArchive(
        archive: BrowserArchiveInput,
        consume: (InputStream) -> Unit,
    ) {
        try {
            checkCancelled()
            archive.openStream().use(consume)
        } catch (error: BrowserArchiveEvidenceException) {
            throw error
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw BrowserArchiveEvidenceException(
                BrowserArchiveEvidenceFailure.READ_FAILED,
                archive.name,
                error,
            )
        }
    }

    private fun drain(
        input: InputStream,
        consume: ((ByteArray, Int) -> Unit)? = null,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            checkCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                throw IllegalStateException("archive stream returned zero bytes")
            }
            consume?.invoke(buffer, read)
        }
    }

    private inner class BoundedRawInputStream(
        input: InputStream,
        private val archiveName: String,
    ) : FilterInputStream(input) {
        var byteCount: Long = 0
            private set

        override fun read(): Int {
            checkCancelled()
            val value = super.read()
            if (value >= 0) addBytes(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            val read = super.read(buffer, offset, length)
            if (read > 0) addBytes(read)
            return read
        }

        private fun addBytes(read: Int) {
            byteCount += read
            if (byteCount > limits.maxRawBytesPerArchive) {
                limitExceeded(archiveName)
            }
        }
    }

    private inner class BoundedEntryInputStream(
        input: InputStream,
        private val budget: InflateBudget,
    ) : FilterInputStream(input) {
        private var entryBytes = 0L

        override fun read(): Int {
            checkCancelled()
            val value = super.read()
            if (value >= 0) addBytes(1)
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            val read = super.read(buffer, offset, length)
            if (read > 0) addBytes(read)
            return read
        }

        override fun close() = Unit

        private fun addBytes(read: Int) {
            entryBytes += read
            budget.totalInflatedBytes += read
            if (
                entryBytes > limits.maxInflatedBytesPerEntry ||
                budget.totalInflatedBytes > limits.maxTotalInflatedBytesPerArchive ||
                budget.totalInflatedBytes >
                maxOf(budget.rawBytes(), 1L) * limits.maxInflateRatio.toLong()
            ) {
                limitExceeded(budget.archiveName)
            }
        }
    }

    private data class ArchiveDigest(
        val sha256: String,
        val byteCount: Long,
    )

    private data class InflateBudget(
        val archiveName: String,
        val rawBytes: () -> Long,
        var entryCount: Int = 0,
        var totalInflatedBytes: Long = 0,
    )

    private class NonClosingInputStream(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private fun limitExceeded(archiveName: String): Nothing =
        throw BrowserArchiveEvidenceException(
            BrowserArchiveEvidenceFailure.RESOURCE_LIMIT_EXCEEDED,
            archiveName,
        )

    private class ArchiveScan {
        val markers = mutableSetOf<String>()
        val namespaces = mutableSetOf<String>()
        private val dexMarkers = mutableSetOf<String>()
        private val terraceMarkerSets = mutableListOf<Set<String>>()
        var versionName: String? = null
        var versionCode: Long? = null

        fun scanContractData(input: InputStream) {
            scanText(input) { text ->
                REQUIRED_MARKERS.filterTo(markers) { it in text }
                OPTION_PAGE_DEX_MARKERS.filterTo(dexMarkers) { it in text }
                if (versionName == null) {
                    versionName = VERSION_NAME_REGEX.find(text)?.groupValues?.get(1)
                }
                if (versionCode == null) {
                    versionCode = VERSION_CODE_TEXT_REGEX.find(text)
                        ?.groupValues
                        ?.get(1)
                        ?.toLongOrNull()
                }
            }
        }

        fun scanTerraceLibrary(input: InputStream) {
            val libraryMarkers = mutableSetOf<String>()
            scanText(input) { text ->
                NAMESPACE_REGEX.findAll(text).forEach { match ->
                    namespaces += match.groupValues[1]
                }
                OPTION_PAGE_TERRACE_MARKERS.filterTo(libraryMarkers) { it in text }
            }
            terraceMarkerSets += libraryMarkers
        }

        fun supportedFeatures(): Set<String> = buildSet {
            if (
                dexMarkers.containsAll(OPTION_PAGE_DEX_MARKERS) &&
                terraceMarkerSets.any { it.containsAll(OPTION_PAGE_TERRACE_MARKERS) }
            ) {
                add(OPTIONS_PAGE_ACCESSIBLE_FEATURE)
            }
        }

        private fun scanText(input: InputStream, consume: (String) -> Unit) {
            val buffer = ByteArray(64 * 1024)
            var tail = ""
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    throw IllegalStateException("archive entry returned zero bytes")
                }
                val current = tail + buffer.decodeToString(endIndex = read)
                consume(current)
                tail = current.takeLast(SCAN_OVERLAP)
            }
        }
    }

    companion object {
        fun installationApkSetSha256(
            baseApk: BrowserArchiveInput,
            splitApks: List<BrowserArchiveInput>,
        ): String {
            val digest = MessageDigest.getInstance("SHA-256")
            digest.bytes(INSTALLATION_DIGEST_DOMAIN.toByteArray(UTF_8))
            val archives = canonicalArchives(baseApk, splitApks)
            digest.bytes(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(archives.size).array())
            archives.forEach { archive ->
                digest.bytes(archive.canonicalName.toByteArray(UTF_8))
                digest.bytes(archive.input.sha256.toByteArray(UTF_8))
            }
            return digest.hex()
        }

        private fun canonicalArchives(
            baseApk: BrowserArchiveInput,
            splitApks: List<BrowserArchiveInput>,
            maxArchiveCount: Int = BrowserArchiveScanLimits().maxArchiveCount,
        ): List<CanonicalArchive> {
            val archives = listOf(baseApk) + splitApks
            if (archives.isEmpty() || archives.size > maxArchiveCount) {
                invalidEvidence(null)
            }
            val canonical = archives.map { archive ->
                if (!SHA256.matches(archive.sha256)) invalidEvidence(archive.name)
                CanonicalArchive(canonicalName(archive.name), archive)
            }
            val foldedNames = canonical.map {
                it.canonicalName
                    .uppercase(Locale.ROOT)
                    .lowercase(Locale.ROOT)
            }
            if (foldedNames.toSet().size != foldedNames.size) invalidEvidence(null)
            return canonical.sortedWith(
                compareBy<CanonicalArchive>(
                    { it.canonicalName },
                    { it.input.sha256 },
                ),
            )
        }

        private fun canonicalName(name: String): String {
            val canonical = Normalizer.normalize(name, Normalizer.Form.NFC)
            if (
                canonical.isBlank() ||
                canonical == "." ||
                canonical == ".." ||
                '/' in canonical ||
                '\\' in canonical ||
                '\u0000' in canonical
            ) {
                invalidEvidence(name)
            }
            return canonical
        }

        private fun invalidEvidence(name: String?): Nothing =
            throw BrowserArchiveEvidenceException(
                BrowserArchiveEvidenceFailure.INVALID_EVIDENCE,
                name,
            )

        private data class CanonicalArchive(
            val canonicalName: String,
            val input: BrowserArchiveInput,
        )

        private const val INSTALLATION_DIGEST_DOMAIN =
            "sixbridge-browser-installation-apk-set-v1"
        private const val SCAN_OVERLAP = 512
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val REQUIRED_MARKERS = setOf(
            "six://ex",
            "extension-id",
            ".contentprovider",
            "dev_ext",
        )
        private const val OPTIONS_PAGE_ACCESSIBLE_FEATURE =
            "extension.options-page.user-accessible"
        private val OPTION_PAGE_DEX_MARKERS = setOf(
            "getExtensionOptionPage",
            "six_add_on_settings",
            "updateGoAddonSettings",
        )
        private val OPTION_PAGE_TERRACE_MARKERS = setOf(
            "options_ui",
            "options_page",
        )
        private val NAMESPACE_REGEX =
            Regex("""["']namespace["']\s*:\s*["']([A-Za-z][A-Za-z0-9_.]*)["']""")
        private val VERSION_NAME_REGEX =
            Regex("""(?:versionName[\s:=_-]*)?(\d{2}\.\d+\.\d+\.\d+)""")
        private val VERSION_CODE_TEXT_REGEX =
            Regex("""versionCode[\s:=_-]*(\d{6,})""")

        private fun MessageDigest.bytes(value: ByteArray) {
            update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(value.size).array())
            update(value)
        }

        private fun MessageDigest.hex(): String =
            digest().joinToString("") { "%02x".format(it) }
    }
}
