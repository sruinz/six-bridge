package dev.sixbridge.manager.packager

import android.content.Context
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.Crx3Writer
import dev.sixbridge.manager.wrapper.AndroidArchiveInspector
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.WrapperVerificationResult
import java.io.File
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipFile

data class OnDeviceWrapperRequest(
    val sourceIdentity: ByteArray,
    val canonicalSourceZip: ByteArray,
    val versionCode: Int,
    val extensionName: String,
    val extensionVersion: String,
    val sourceKind: String,
    val sourceSha256: String,
    val browserProfileSha256: String,
    val outputApk: File,
)

data class OnDeviceWrapperResult(
    val extensionId: String,
    val packageName: String,
    val versionCode: Int,
    val signerSha256: String,
    val crxSha256: String,
    val wrapperSha256: String,
    val descriptor: ContractExtensionDescriptor,
    val compatibility: ContractCompatibilityReport,
    val outputApk: File,
)

/**
 * Android Keystore와 앱 private storage만 사용하는 최초 온디바이스 wrapper 생성 게이트다.
 * 이 단계는 canonical ZIP을 그대로 CRX3로 감싸며, 실제 extension 변환 판단은 후속 단계의 책임이다.
 */
class OnDeviceWrapperPackager(context: Context) {
    private val appContext = context.applicationContext
    private val workingDirectory = appPrivateWorkingDirectory(appContext.filesDir)
    private val identityStore = AndroidKeystoreIdentityStore()
    private val manifestPatcher = BinaryAndroidManifestPatcher()
    private val apkSigner = AndroidApkSigner()
    private val verifier = AndroidWrapperVerifier(AndroidArchiveInspector(appContext.packageManager))

    @Synchronized
    fun packageWrapper(request: OnDeviceWrapperRequest): OnDeviceWrapperResult {
        validateRequest(request)
        val output = validatedOutput(request.outputApk)
        val temporaryTemplate = Files.createTempFile(workingDirectory, ".wrapper-template.", ".apk")
        val unsigned = Files.createTempFile(workingDirectory, ".unsigned.", ".apk")
        val signed = Files.createTempFile(workingDirectory, ".signed.", ".apk")
        try {
            val sourceIdentity = request.sourceIdentity.copyOf()
            val canonicalZip = request.canonicalSourceZip.copyOf()
            val crx = Crx3Writer.create(canonicalZip, identityStore.crxSigningKey(sourceIdentity))
            val extensionId = crx.extensionId
            val packageName = "dev.sixbridge.ext.$extensionId"
            val template = readVerifiedTemplate(temporaryTemplate)
            val templateManifest = ZipFile(template.toFile()).use { archive ->
                archive.getInputStream(archive.getEntry(MANIFEST_ENTRY) ?: error("래퍼 template manifest가 없습니다.")).use(InputStream::readBytes)
            }
            val patchedManifest = manifestPatcher.patch(
                templateManifest,
                WrapperManifestRequest(extensionId = extensionId, versionCode = request.versionCode),
            )
            AlignedApkAssembler(workingDirectory).assemble(
                ApkAssemblyRequest(
                    templateApk = template.toFile(),
                    manifestBytes = patchedManifest,
                    crxBytes = crx.bytes,
                    outputApk = unsigned.toFile(),
                ),
            )
            val signing = apkSigner.sign(unsigned.toFile(), signed.toFile(), identityStore.wrapperIdentity())
            val descriptor = ContractExtensionDescriptor(
                schemaVersion = 1,
                extensionId = extensionId,
                name = request.extensionName,
                version = request.extensionVersion,
                sourceKind = request.sourceKind,
                crxSha256 = sha256(crx.bytes),
                wrapperSha256 = sha256(signed),
                wrapperPackage = packageName,
                wrapperVersionCode = request.versionCode,
                signerSha256 = signing.signerCertificateSha256,
            )
            val compatibility = ContractCompatibilityReport(
                schemaVersion = 3,
                status = ContractCompatibilityStatus.COMPATIBLE,
                manifestVersion = null,
                extensionName = request.extensionName,
                extensionVersion = request.extensionVersion,
                manifestKeyPresent = false,
                manifestKeyExtensionId = null,
                declaredPermissions = emptyList(),
                usedApis = emptyList(),
                unsupportedApis = emptyList(),
                issues = emptyList(),
                conversionState = "passthrough",
                adapterDecisions = emptyList(),
                originalExtensionId = null,
                convertedExtensionId = extensionId,
                sourceSha256 = request.sourceSha256,
                browserProfileSha256 = request.browserProfileSha256,
            )
            require(verifier.verify(signed.toFile(), descriptor, compatibility) is WrapperVerificationResult.Success) {
                "생성된 wrapper APK 검증에 실패했습니다."
            }
            forceFile(signed)
            rejectUnsafeOutput(output)
            Files.move(signed, output, ATOMIC_MOVE, REPLACE_EXISTING)
            return OnDeviceWrapperResult(
                extensionId = extensionId,
                packageName = packageName,
                versionCode = request.versionCode,
                signerSha256 = signing.signerCertificateSha256,
                crxSha256 = descriptor.crxSha256,
                wrapperSha256 = descriptor.wrapperSha256,
                descriptor = descriptor,
                compatibility = compatibility,
                outputApk = output.toFile(),
            )
        } finally {
            Files.deleteIfExists(temporaryTemplate)
            Files.deleteIfExists(unsigned)
            Files.deleteIfExists(signed)
        }
    }

    private fun validateRequest(request: OnDeviceWrapperRequest) {
        require(request.sourceIdentity.isNotEmpty()) { "source identity가 비어 있습니다." }
        require(request.canonicalSourceZip.size >= ZIP_MAGIC_SIZE) { "canonical source ZIP이 올바르지 않습니다." }
        require(request.versionCode > 0) { "versionCode는 0보다 커야 합니다." }
        require(request.extensionName.isNotBlank() && request.extensionVersion.isNotBlank()) {
            "extension name과 version은 비어 있지 않아야 합니다."
        }
        require(request.sourceKind in SOURCE_KINDS) { "sourceKind가 올바르지 않습니다." }
        require(SHA256.matches(request.sourceSha256) && request.sourceSha256 == sha256(request.canonicalSourceZip)) {
            "source SHA-256이 canonical source ZIP과 일치하지 않습니다."
        }
        require(SHA256.matches(request.browserProfileSha256)) { "browser profile SHA-256이 올바르지 않습니다." }
    }

    private fun appPrivateWorkingDirectory(filesDir: File): Path {
        val files = filesDir.toPath().toAbsolutePath().normalize()
        require(Files.isDirectory(files, NOFOLLOW_LINKS) && !Files.isSymbolicLink(files)) {
            "앱 private filesDir가 올바르지 않습니다."
        }
        val root = files.resolve(WORKING_DIRECTORY)
        if (!Files.exists(root, NOFOLLOW_LINKS)) Files.createDirectory(root)
        require(Files.isDirectory(root, NOFOLLOW_LINKS) && !Files.isSymbolicLink(root)) {
            "온디바이스 packager 작업 디렉터리가 올바르지 않습니다."
        }
        return root
    }

    private fun validatedOutput(file: File): Path {
        val output = file.toPath().toAbsolutePath().normalize()
        require(output.parent == workingDirectory && output.fileName.toString().endsWith(".apk")) {
            "output APK는 앱 private ondevice-packager-gate 디렉터리에 있어야 합니다."
        }
        rejectUnsafeOutput(output)
        return output
    }

    private fun rejectUnsafeOutput(output: Path) {
        require(!Files.exists(output, NOFOLLOW_LINKS) || !Files.isSymbolicLink(output)) {
            "output APK symlink는 허용하지 않습니다."
        }
        if (Files.exists(output, NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(output, NOFOLLOW_LINKS)) { "output APK는 regular file이어야 합니다." }
        }
    }

    private fun readVerifiedTemplate(destination: Path): Path {
        val expected = appContext.assets.open(TEMPLATE_SHA256_ASSET).bufferedReader().use { it.readText().trim() }
        require(SHA256.matches(expected)) { "래퍼 template SHA-256 asset이 올바르지 않습니다." }
        appContext.assets.open(TEMPLATE_ASSET).use { input ->
            Files.newOutputStream(destination, WRITE).use(input::copyTo)
        }
        forceFile(destination)
        require(sha256(destination) == expected) { "래퍼 template APK hash가 일치하지 않습니다." }
        return destination
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun sha256(file: Path): String = Files.newInputStream(file).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun forceFile(file: Path) {
        FileChannel.open(file, WRITE).use { it.force(true) }
    }

    private companion object {
        const val WORKING_DIRECTORY = "ondevice-packager-gate"
        const val TEMPLATE_ASSET = "wrapper-template.apk"
        const val TEMPLATE_SHA256_ASSET = "wrapper-template.sha256"
        const val MANIFEST_ENTRY = "AndroidManifest.xml"
        const val ZIP_MAGIC_SIZE = 4
        val SOURCE_KINDS = setOf("SOURCE_DIRECTORY", "ZIP", "CRX3", "CHROME_WEB_STORE")
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}
