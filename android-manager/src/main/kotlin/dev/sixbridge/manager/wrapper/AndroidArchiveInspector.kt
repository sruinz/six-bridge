package dev.sixbridge.manager.wrapper

import android.content.pm.PackageManager
import android.os.Build
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class AndroidArchiveInspector(
    private val packageManager: PackageManager,
    private val manifestReader: BinaryAndroidManifestReader = BinaryAndroidManifestReader(),
) : ArchiveInspector {
    override fun inspect(apk: File): ArchiveFacts {
        val archive = ApkArchiveEntries.preflight(apk)
        val packageInfo = archiveInfo(apk)
            ?: invalid(ImportFailureCode.ARCHIVE_INVALID)
        val signers = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        if (signers.size != 1) invalid(ImportFailureCode.SIGNER_COUNT_INVALID)
        val manifest = try {
            manifestReader.read(archive.manifestBytes)
        } catch (error: ArchiveInspectionException) {
            throw error
        } catch (_: IllegalArgumentException) {
            invalid(ImportFailureCode.ARCHIVE_INVALID)
        }
        val extensionId = manifest.metadata[EXTENSION_ID_METADATA]
        if (packageInfo.applicationInfo?.metaData?.getString(EXTENSION_ID_METADATA) != extensionId) {
            invalid(ImportFailureCode.ARCHIVE_INVALID)
        }
        val platformActivities = packageInfo.activities.orEmpty()
        val manifestActivityCounts = manifest.activities
            .groupingBy { it.name to it.exported }
            .eachCount()
        val platformActivityCounts = platformActivities
            .groupingBy { it.name.orEmpty() to it.exported }
            .eachCount()
        if (manifestActivityCounts != platformActivityCounts) {
            invalid(ImportFailureCode.ARCHIVE_INVALID)
        }
        val platformProviders = packageInfo.providers.orEmpty()
        val manifestProviderCounts = manifest.providers
            .groupingBy { Triple(it.name, it.authority, it.exported) }
            .eachCount()
        val platformProviderCounts = platformProviders
            .groupingBy { Triple(it.name.orEmpty(), it.authority.orEmpty(), it.exported) }
            .eachCount()
        if (manifestProviderCounts != platformProviderCounts) {
            invalid(ImportFailureCode.ARCHIVE_INVALID)
        }
        return ArchiveFacts(
            packageName = packageInfo.packageName
                .takeIf(String::isNotBlank)
                ?: invalid(ImportFailureCode.ARCHIVE_INVALID),
            versionCode = packageInfo.longVersionCode,
            signerSha256 = sha256(signers.single().toByteArray()),
            metadata = manifest.metadata,
            activities = manifest.activities,
            providers = manifest.providers,
            crxBytes = archive.crxBytes,
        )
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(apk: File) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getPackageArchiveInfo(
            apk.absolutePath,
            PackageManager.PackageInfoFlags.of(PACKAGE_FLAGS.toLong()),
        )
    } else {
        packageManager.getPackageArchiveInfo(apk.absolutePath, PACKAGE_FLAGS)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun invalid(code: ImportFailureCode): Nothing =
        throw ArchiveInspectionException(ImportFailure(code))

    private companion object {
        const val EXTENSION_ID_METADATA = "extension-id"
        const val PACKAGE_FLAGS =
            PackageManager.GET_META_DATA or
                PackageManager.GET_ACTIVITIES or
                PackageManager.GET_PROVIDERS or
                PackageManager.GET_SIGNING_CERTIFICATES
    }
}

internal object ApkArchiveEntries {
    private const val MANIFEST_ENTRY = "AndroidManifest.xml"
    private const val EMBEDDED_CRX_ENTRY = "assets/extension.crx"
    private const val MAX_MANIFEST_SIZE = 4L * 1024 * 1024
    private const val MAX_ENTRY_COUNT = 64
    private const val MAX_TOTAL_UNCOMPRESSED_SIZE = 144L * 1024 * 1024
    private const val MAX_COMPRESSION_RATIO = 100L

    data class Contents(
        val manifestBytes: ByteArray,
        val crxBytes: ByteArray,
    )

    fun preflight(apk: File): Contents {
        if (!apk.isFile || apk.length() !in 1..SixBundleContract.MAX_WRAPPER_SIZE) {
            invalid(ImportFailureCode.ARCHIVE_INVALID)
        }
        try {
            ZipFile(apk).use { zip ->
                val entries = zip.entries().asSequence().toList()
                if (entries.size !in 1..MAX_ENTRY_COUNT) {
                    invalid(ImportFailureCode.ARCHIVE_INVALID)
                }
                val names = hashSetOf<String>()
                var totalUncompressed = 0L
                var manifest: ZipEntry? = null
                var crx: ZipEntry? = null
                entries.forEach { entry ->
                    val canonicalName = canonicalPath(entry.name)
                    if (canonicalName == null ||
                        canonicalName != entry.name ||
                        entry.isDirectory ||
                        !names.add(canonicalName)
                    ) {
                        invalid(ImportFailureCode.ARCHIVE_INVALID)
                    }
                    if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
                        invalid(ImportFailureCode.ARCHIVE_INVALID)
                    }
                    if (entry.size < 0 ||
                        entry.compressedSize < 0 ||
                        entry.compressedSize > apk.length()
                    ) {
                        invalid(ImportFailureCode.ARCHIVE_INVALID)
                    }
                    if (entry.size > SixBundleContract.MAX_WRAPPER_SIZE) {
                        invalid(
                            if (canonicalName == EMBEDDED_CRX_ENTRY) {
                                ImportFailureCode.CRX_TOO_LARGE
                            } else {
                                ImportFailureCode.ARCHIVE_INVALID
                            },
                        )
                    }
                    if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED_SIZE - entry.size) {
                        invalid(ImportFailureCode.ARCHIVE_INVALID)
                    }
                    totalUncompressed += entry.size
                    if (entry.size > 0) {
                        val minimumCompressed =
                            (entry.size + MAX_COMPRESSION_RATIO - 1) / MAX_COMPRESSION_RATIO
                        if (entry.compressedSize < minimumCompressed) {
                            invalid(ImportFailureCode.ARCHIVE_INVALID)
                        }
                    }
                    when (canonicalName) {
                        MANIFEST_ENTRY -> manifest = entry
                        EMBEDDED_CRX_ENTRY -> crx = entry
                    }
                }
                val manifestEntry = manifest ?: invalid(ImportFailureCode.ARCHIVE_INVALID)
                val crxEntry = crx ?: invalid(ImportFailureCode.CRX_ENTRY_INVALID)
                if (manifestEntry.size !in 1..MAX_MANIFEST_SIZE) {
                    invalid(ImportFailureCode.ARCHIVE_INVALID)
                }
                if (crxEntry.size <= 0) invalid(ImportFailureCode.CRX_ENTRY_INVALID)
                if (crxEntry.size > SixBundleContract.MAX_WRAPPER_SIZE) {
                    invalid(ImportFailureCode.CRX_TOO_LARGE)
                }
                return Contents(
                    manifestBytes = readBounded(
                        zip,
                        manifestEntry,
                        MAX_MANIFEST_SIZE,
                        ImportFailureCode.ARCHIVE_INVALID,
                    ),
                    crxBytes = readBounded(
                        zip,
                        crxEntry,
                        SixBundleContract.MAX_WRAPPER_SIZE,
                        ImportFailureCode.CRX_TOO_LARGE,
                    ),
                )
            }
        } catch (error: ArchiveInspectionException) {
            throw error
        } catch (_: Exception) {
            invalid(ImportFailureCode.ARCHIVE_INVALID)
        }
    }

    private fun readBounded(
        zip: ZipFile,
        entry: ZipEntry,
        maximumSize: Long,
        tooLarge: ImportFailureCode,
    ): ByteArray {
        if (entry.size > Int.MAX_VALUE) invalid(tooLarge)
        return zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream(
                minOf(entry.size, DEFAULT_BUFFER_SIZE.toLong()).toInt(),
            )
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (total > maximumSize - count) invalid(tooLarge)
                output.write(buffer, 0, count)
                total += count
            }
            if (total != entry.size) invalid(ImportFailureCode.ARCHIVE_INVALID)
            output.toByteArray()
        }
    }

    private fun canonicalPath(name: String): String? {
        if (name.isEmpty() ||
            name.indexOf('\u0000') >= 0 ||
            name.startsWith('/') ||
            name.startsWith('\\') ||
            DRIVE_PREFIX.containsMatchIn(name.substringBefore('/'))
        ) {
            return null
        }
        val parts = name.split('/')
        if (parts.any { it.isEmpty() || it == "." || it == ".." || '\\' in it }) return null
        return parts.joinToString("/")
    }

    private fun invalid(code: ImportFailureCode): Nothing =
        throw ArchiveInspectionException(ImportFailure(code))

    private val DRIVE_PREFIX = Regex("^[A-Za-z]:")
}
