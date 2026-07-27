package dev.sixbridge.manager.wrapper

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.bundle.SixBundleContract
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.contract.crx.VerifiedCrx3
import dev.sixbridge.manager.importer.ImportFailure
import dev.sixbridge.manager.importer.ImportFailureCode
import dev.sixbridge.manager.importer.VerifiedWrapperApk
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

sealed interface WrapperVerificationResult {
    data class Success(val value: VerifiedWrapperApk) : WrapperVerificationResult
    data class Failure(val reason: ImportFailure) : WrapperVerificationResult
}

class AndroidWrapperVerifier(
    private val inspector: ArchiveInspector,
    temporaryDirectory: File? = null,
) {
    private val managedTemporaryDirectory = temporaryDirectory?.let(::prepareTemporaryDirectory)

    fun verify(
        apk: File,
        descriptor: ContractExtensionDescriptor,
        report: ContractCompatibilityReport,
    ): WrapperVerificationResult {
        val wrapperSha256 = runCatching { sha256(apk) }
            .getOrElse { return failure(ImportFailureCode.ARCHIVE_INVALID) }
        if (wrapperSha256 != descriptor.wrapperSha256) {
            return failure(ImportFailureCode.WRAPPER_HASH_MISMATCH)
        }
        val inspected = try {
            inspectAndVerifyCrx(apk)
        } catch (error: ArchiveInspectionException) {
            return WrapperVerificationResult.Failure(error.failure)
        } catch (_: Exception) {
            return failure(ImportFailureCode.ARCHIVE_INVALID)
        }
        val facts = inspected.facts
        val crx = inspected.crx
        val inspectedSha256 = runCatching { sha256(apk) }
            .getOrElse { return failure(ImportFailureCode.WRAPPER_HASH_MISMATCH) }
        if (inspectedSha256 != wrapperSha256) {
            return failure(ImportFailureCode.WRAPPER_HASH_MISMATCH)
        }
        val expectedPackage = "dev.sixbridge.ext.${descriptor.extensionId}"
        if (descriptor.wrapperPackage != expectedPackage ||
            facts.packageName != descriptor.wrapperPackage
        ) {
            return failure(ImportFailureCode.PACKAGE_MISMATCH)
        }
        if (facts.versionCode != descriptor.wrapperVersionCode.toLong()) {
            return failure(ImportFailureCode.VERSION_CODE_MISMATCH)
        }
        if (facts.signerSha256 != descriptor.signerSha256) {
            return failure(ImportFailureCode.SIGNER_MISMATCH)
        }
        if (facts.metadata[EXTENSION_ID_METADATA] != descriptor.extensionId) {
            return failure(ImportFailureCode.METADATA_MISMATCH)
        }
        val activity = facts.activities.singleOrNull()
            ?: return failure(ImportFailureCode.ACTIVITY_MISMATCH)
        val filter = activity.intentFilters.singleOrNull()
            ?: return failure(ImportFailureCode.ACTIVITY_MISMATCH)
        if (activity.name != DISCOVERY_ACTIVITY ||
            !activity.exported ||
            filter.actionCount != 1 ||
            filter.actions != setOf(VIEW_ACTION) ||
            filter.categoryCount != 2 ||
            filter.categories != setOf(DEFAULT_CATEGORY, BROWSABLE_CATEGORY) ||
            filter.dataCount != 1 ||
            filter.schemes != setOf(SIX_SCHEME) ||
            filter.hosts != setOf(EX_HOST)
        ) {
            return failure(ImportFailureCode.ACTIVITY_MISMATCH)
        }

        val expectedAuthority = "${facts.packageName}.contentprovider"
        val provider = facts.providers.singleOrNull()
            ?: return failure(ImportFailureCode.PROVIDER_MISMATCH)
        if (provider.name != EXTENSION_PROVIDER ||
            !provider.exported ||
            provider.authority != expectedAuthority
        ) {
            return failure(ImportFailureCode.PROVIDER_MISMATCH)
        }

        if (crx.crxSha256 != descriptor.crxSha256) {
            return failure(ImportFailureCode.CRX_HASH_MISMATCH)
        }
        if (crx.extensionId != descriptor.extensionId) {
            return failure(ImportFailureCode.CRX_ID_MISMATCH)
        }
        if (report.schemaVersion !in SUPPORTED_REPORT_SCHEMAS) {
            return failure(ImportFailureCode.REPORT_SCHEMA_UNSUPPORTED)
        }
        if (report.convertedExtensionId != descriptor.extensionId ||
            report.extensionName != descriptor.name ||
            report.extensionVersion != descriptor.version
        ) {
            return failure(ImportFailureCode.REPORT_IDENTITY_MISMATCH)
        }
        if (report.sourceSha256 != crx.zipSha256 ||
            !SHA256.matches(report.sourceSha256.orEmpty()) ||
            !SHA256.matches(report.browserProfileSha256.orEmpty()) ||
            (report.schemaVersion == 4 &&
                (!SHA256.matches(report.originalSourceSha256.orEmpty()) ||
                    !SHA256.matches(report.planSha256.orEmpty())))
        ) {
            return failure(ImportFailureCode.REPORT_PROOF_INVALID)
        }
        return WrapperVerificationResult.Success(
            VerifiedWrapperApk(
                packageName = facts.packageName,
                versionCode = facts.versionCode,
                signerSha256 = facts.signerSha256,
                wrapperSha256 = wrapperSha256,
                extensionId = descriptor.extensionId,
                providerAuthority = provider.authority,
                crx = crx,
            ),
        )
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun inspectAndVerifyCrx(apk: File): InspectedWrapper {
        if (inspector !is StreamingArchiveInspector) {
            val facts = inspector.inspect(apk)
            val crx = try {
                Crx3Verifier.verify(facts.crxBytes)
            } catch (_: IllegalArgumentException) {
                throw ArchiveInspectionException(ImportFailure(ImportFailureCode.CRX_INVALID))
            }
            return InspectedWrapper(facts, crx)
        }
        val temporaryRoot = managedTemporaryDirectory ?: prepareTemporaryDirectory(
            requireNotNull(apk.absoluteFile.parentFile) {
                "wrapper APK parent directory가 없습니다."
            },
        )
        val extracted = synchronized(TEMPORARY_LOCK) {
            cleanupOrphansLocked(temporaryRoot)
            Files.createTempFile(
                temporaryRoot.toPath(),
                TEMPORARY_PREFIX,
                TEMPORARY_SUFFIX,
            ).also { ACTIVE_TEMPORARY_FILES += it.toAbsolutePath().normalize().toString() }
        }
        try {
            val facts = inspector.inspect(apk, extracted.toFile())
            val crx = try {
                Crx3Verifier.verify(extracted, SixBundleContract.MAX_WRAPPER_SIZE)
            } catch (_: IllegalArgumentException) {
                throw ArchiveInspectionException(ImportFailure(ImportFailureCode.CRX_INVALID))
            }
            return InspectedWrapper(facts, crx)
        } finally {
            val cleanupFailed = synchronized(TEMPORARY_LOCK) {
                val failed = runCatching { !Files.deleteIfExists(extracted) }.getOrDefault(true)
                ACTIVE_TEMPORARY_FILES -= extracted.toAbsolutePath().normalize().toString()
                failed
            }
            if (cleanupFailed) {
                throw ArchiveInspectionException(ImportFailure(ImportFailureCode.ARCHIVE_INVALID))
            }
        }
    }

    private fun prepareTemporaryDirectory(directory: File): File {
        val path = directory.toPath().toAbsolutePath().normalize()
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            runCatching { Files.createDirectory(path) }
        }
        require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
            "wrapper verifier temporary directory가 올바르지 않습니다."
        }
        cleanupOrphans(path.toFile())
        return path.toFile()
    }

    private fun cleanupOrphans(directory: File) = synchronized(TEMPORARY_LOCK) {
        cleanupOrphansLocked(directory)
    }

    private fun cleanupOrphansLocked(directory: File) {
        directory.listFiles().orEmpty()
            .filter { file ->
                file.name.startsWith(TEMPORARY_PREFIX) &&
                    file.name.endsWith(TEMPORARY_SUFFIX) &&
                    file.toPath().toAbsolutePath().normalize().toString() !in ACTIVE_TEMPORARY_FILES
            }
            .forEach { file ->
                val path = file.toPath()
                require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                    "wrapper verifier orphan temporary 파일이 올바르지 않습니다."
                }
                require(Files.deleteIfExists(path)) {
                    "wrapper verifier orphan temporary 파일을 정리할 수 없습니다."
                }
            }
    }

    private fun failure(code: ImportFailureCode) =
        WrapperVerificationResult.Failure(ImportFailure(code))

    private data class InspectedWrapper(
        val facts: ArchiveFacts,
        val crx: VerifiedCrx3,
    )

    private companion object {
        val SUPPORTED_REPORT_SCHEMAS = setOf(3, 4)
        const val EXTENSION_ID_METADATA = "extension-id"
        const val DISCOVERY_ACTIVITY = "dev.sixbridge.runtime.SixDiscoveryActivity"
        const val EXTENSION_PROVIDER = "dev.sixbridge.runtime.ExtensionProvider"
        const val VIEW_ACTION = "android.intent.action.VIEW"
        const val DEFAULT_CATEGORY = "android.intent.category.DEFAULT"
        const val BROWSABLE_CATEGORY = "android.intent.category.BROWSABLE"
        const val SIX_SCHEME = "six"
        const val EX_HOST = "ex"
        val SHA256 = Regex("[0-9a-f]{64}")
        const val TEMPORARY_PREFIX = ".sixbridge-wrapper-crx-"
        const val TEMPORARY_SUFFIX = ".tmp"
        val TEMPORARY_LOCK = Any()
        val ACTIVE_TEMPORARY_FILES = mutableSetOf<String>()
    }
}
