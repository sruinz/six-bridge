package dev.sixbridge.manager.wrapper

import android.content.pm.PackageManager
import android.os.Build
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

class AndroidArchiveInspector(
    private val packageManager: PackageManager,
    private val manifestReader: BinaryAndroidManifestReader = BinaryAndroidManifestReader(),
) : ArchiveInspector {
    override fun inspect(apk: File): ArchiveFacts {
        val packageInfo = archiveInfo(apk)
            ?: invalid(ImportFailureCode.ARCHIVE_INVALID)
        val signers = packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        if (signers.size != 1) invalid(ImportFailureCode.SIGNER_COUNT_INVALID)
        val manifest = try {
            manifestReader.read(ApkArchiveEntries.readManifest(apk))
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
        if (manifest.activities.any { expected ->
                platformActivities.none {
                    it.name == expected.name && it.exported == expected.exported
                }
            }
        ) {
            invalid(ImportFailureCode.ARCHIVE_INVALID)
        }
        val platformProviders = packageInfo.providers.orEmpty()
        if (manifest.providers.any { expected ->
                platformProviders.none {
                    it.name == expected.name &&
                        it.authority == expected.authority &&
                        it.exported == expected.exported
                }
            }
        ) {
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
            crxBytes = ApkArchiveEntries.readUniqueEntry(
                apk,
                EMBEDDED_CRX_ENTRY,
                SixBundleContract.MAX_WRAPPER_SIZE,
            ),
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
        const val EMBEDDED_CRX_ENTRY = "assets/extension.crx"
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
    private const val MAX_MANIFEST_SIZE = 4L * 1024 * 1024

    fun readManifest(apk: File): ByteArray = readUnique(
        apk = apk,
        target = MANIFEST_ENTRY,
        maximumSize = MAX_MANIFEST_SIZE,
        missingOrMalformed = ImportFailureCode.ARCHIVE_INVALID,
        tooLarge = ImportFailureCode.ARCHIVE_INVALID,
    )

    fun readUniqueEntry(apk: File, target: String, maximumSize: Long): ByteArray = readUnique(
        apk = apk,
        target = target,
        maximumSize = maximumSize,
        missingOrMalformed = ImportFailureCode.CRX_ENTRY_INVALID,
        tooLarge = ImportFailureCode.CRX_TOO_LARGE,
    )

    private fun readUnique(
        apk: File,
        target: String,
        maximumSize: Long,
        missingOrMalformed: ImportFailureCode,
        tooLarge: ImportFailureCode,
    ): ByteArray {
        try {
            ZipFile(apk).use { zip ->
                val candidates = zip.entries().asSequence()
                    .filter { it.name == target || normalizedPath(it.name) == target }
                    .toList()
                if (candidates.size != 1 ||
                    candidates.single().name != target ||
                    candidates.single().isDirectory
                ) {
                    invalid(missingOrMalformed)
                }
                val entry = candidates.single()
                if (entry.size > maximumSize) invalid(tooLarge)
                if (entry.size <= 0 || entry.size > Int.MAX_VALUE || entry.compressedSize < 0) {
                    invalid(missingOrMalformed)
                }
                return zip.getInputStream(entry).use { input ->
                    val output = ByteArrayOutputStream(minOf(entry.size, DEFAULT_BUFFER_SIZE.toLong()).toInt())
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (total > maximumSize - count) invalid(tooLarge)
                        output.write(buffer, 0, count)
                        total += count
                    }
                    if (total != entry.size) invalid(missingOrMalformed)
                    output.toByteArray()
                }
            }
        } catch (error: ArchiveInspectionException) {
            throw error
        } catch (_: Exception) {
            invalid(missingOrMalformed)
        }
    }

    private fun normalizedPath(name: String): String? {
        if (name.startsWith('/') || name.startsWith('\\')) return null
        val parts = mutableListOf<String>()
        name.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return parts.joinToString("/")
    }

    private fun invalid(code: ImportFailureCode): Nothing =
        throw ArchiveInspectionException(ImportFailure(code))
}
