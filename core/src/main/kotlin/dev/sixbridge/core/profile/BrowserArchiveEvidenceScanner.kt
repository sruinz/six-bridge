package dev.sixbridge.core.profile

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.DigestInputStream
import java.security.MessageDigest
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

enum class BrowserArchiveEvidenceFailure {
    INVALID_EVIDENCE,
    DECLARED_SHA256_MISMATCH,
    CONTENT_CHANGED,
    READ_FAILED,
}

class BrowserArchiveEvidenceException(
    val failure: BrowserArchiveEvidenceFailure,
    val archiveName: String?,
    cause: Throwable? = null,
) : IllegalStateException(
    listOfNotNull(failure.name.lowercase(), archiveName).joinToString(":"),
    cause,
)

class BrowserArchiveEvidenceScanner {
    fun scan(evidence: BrowserPackageEvidence): BrowserProfile {
        val archives = listOf(evidence.baseApk) + evidence.splitApks
        validateEvidence(archives)
        val scan = ArchiveScan()
        archives.forEach { archive ->
            val firstSha256 = digest(archive)
            val secondSha256 = scanAndDigest(archive, scan)
            if (firstSha256 != secondSha256) {
                throw BrowserArchiveEvidenceException(
                    BrowserArchiveEvidenceFailure.CONTENT_CHANGED,
                    archive.name,
                )
            }
            if (firstSha256 != archive.sha256) {
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
        val differences = if (
            majorVersion == BrowserProfile.SUPPORTED_MAJOR_VERSION &&
            versionName != BrowserProfile.BASELINE_VERSION
        ) {
            buildList {
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

    private fun validateEvidence(archives: List<BrowserArchiveInput>) {
        if (
            archives.any {
                it.name.isBlank() || !SHA256.matches(it.sha256)
            } ||
            archives.map(BrowserArchiveInput::name).toSet().size != archives.size
        ) {
            throw BrowserArchiveEvidenceException(
                BrowserArchiveEvidenceFailure.INVALID_EVIDENCE,
                null,
            )
        }
    }

    private fun digest(archive: BrowserArchiveInput): String {
        val digest = MessageDigest.getInstance("SHA-256")
        readArchive(archive) { input ->
            updateDigest(input, digest)
        }
        return digest.hex()
    }

    private fun scanAndDigest(
        archive: BrowserArchiveInput,
        scan: ArchiveScan,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        readArchive(archive) { input ->
            val digested = DigestInputStream(input, digest)
            val zip = ZipInputStream(digested)
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name.endsWith(".dex") -> scan.scanContractData(zip)
                    entry.name.endsWith("/libterrace.so") -> scan.scanTerraceLibrary(zip)
                }
                zip.closeEntry()
            }
            updateDigest(digested, digest = null)
        }
        return digest.hex()
    }

    private fun readArchive(
        archive: BrowserArchiveInput,
        consume: (InputStream) -> Unit,
    ) {
        try {
            archive.openStream().use(consume)
        } catch (error: BrowserArchiveEvidenceException) {
            throw error
        } catch (error: Exception) {
            throw BrowserArchiveEvidenceException(
                BrowserArchiveEvidenceFailure.READ_FAILED,
                archive.name,
                error,
            )
        }
    }

    private fun updateDigest(input: InputStream, digest: MessageDigest?) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) {
                throw IllegalStateException("archive stream returned zero bytes")
            }
            digest?.update(buffer, 0, read)
        }
    }

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
            val archives = (listOf(baseApk) + splitApks)
                .sortedWith(compareBy(BrowserArchiveInput::name, BrowserArchiveInput::sha256))
            digest.bytes(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(archives.size).array())
            archives.forEach { archive ->
                digest.bytes(archive.name.toByteArray(UTF_8))
                digest.bytes(archive.sha256.toByteArray(UTF_8))
            }
            return digest.hex()
        }

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
