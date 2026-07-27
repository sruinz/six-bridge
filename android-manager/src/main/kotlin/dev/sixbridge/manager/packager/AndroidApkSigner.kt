package dev.sixbridge.manager.packager

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.channels.FileChannel
import java.security.MessageDigest

data class ApkSigningResult(
    val outputApk: File,
    val signerCertificateSha256: String,
    val v2Verified: Boolean,
    val v3Verified: Boolean,
)

class AndroidApkSigner internal constructor(
    private val forceFile: (Path) -> Unit,
) {
    constructor() : this(::forceFile)

    fun sign(
        unsignedApk: File,
        outputApk: File,
        identity: AndroidSigningIdentity,
    ): ApkSigningResult {
        val input = unsignedApk.toPath().toAbsolutePath().normalize()
        val output = outputApk.toPath().toAbsolutePath().normalize()
        require(Files.isRegularFile(input, NOFOLLOW_LINKS) && !Files.isSymbolicLink(input)) {
            "unsigned APK가 올바르지 않습니다."
        }
        val trustedParent = TrustedParent.capture(output.parent ?: throw IllegalArgumentException("output APK parent가 없습니다."))
        require(output.parent == trustedParent.path) { "output APK는 신뢰된 작업 디렉터리에 있어야 합니다." }
        rejectUnsafeOutput(input, output)

        val temporary = Files.createTempFile(trustedParent.path, ".${output.fileName}.", ".tmp")
        try {
            val signer = ApkSigner.SignerConfig.Builder(
                "sixbridge",
                identity.privateKey,
                listOf(identity.certificate),
            ).build()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(input.toFile())
                .setOutputApk(temporary.toFile())
                .setMinSdkVersion(MIN_SDK_VERSION)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setV4SigningEnabled(false)
                .setAlignmentPreserved(true)
                .build()
                .sign()

            val evidence = verifySignedApk(temporary.toFile(), identity)
            forceFile(temporary)
            trustedParent.verifyUnchanged()
            rejectUnsafeOutput(input, output)
            Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING)
            return ApkSigningResult(
                outputApk = output.toFile(),
                signerCertificateSha256 = evidence.certificateSha256,
                v2Verified = evidence.v2Verified,
                v3Verified = evidence.v3Verified,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun verifySignedApk(apk: File, identity: AndroidSigningIdentity): SigningEvidence {
        val currentResult = ApkVerifier.Builder(apk)
            .setMinCheckedPlatformVersion(MIN_SDK_VERSION)
            .build()
            .verify()
        require(
            currentResult.isVerified &&
                !currentResult.isVerifiedUsingV1Scheme &&
                currentResult.isVerifiedUsingV3Scheme &&
                !currentResult.isVerifiedUsingV4Scheme &&
                currentResult.v3SchemeSigners.size == 1
        ) {
            "APK v2/v3 서명 검증에 실패했습니다: " +
                "verified=${currentResult.isVerified}, v1=${currentResult.isVerifiedUsingV1Scheme}, " +
                "v3=${currentResult.isVerifiedUsingV3Scheme}, v4=${currentResult.isVerifiedUsingV4Scheme}, " +
                "errors=${currentResult.errors}"
        }
        val expected = sha256Hex(identity.certificate.encoded)
        require(certificateDigest(currentResult.signerCertificates, "v3") == expected) {
            "APK v3 signer 인증서가 Android Keystore 인증서와 다릅니다."
        }

        val legacyResult = ApkVerifier.Builder(apk)
            .setMinCheckedPlatformVersion(MIN_V2_SDK_VERSION)
            .build()
            .verify()
        require(
            legacyResult.isVerified &&
                !legacyResult.isVerifiedUsingV1Scheme &&
                legacyResult.isVerifiedUsingV2Scheme &&
                legacyResult.v2SchemeSigners.size == 1
        ) { "APK v2 signer 검증에 실패했습니다: ${legacyResult.errors}" }
        require(certificateDigest(legacyResult.v2SchemeSigners.map { it.certificate }, "v2") == expected) {
            "APK v2 signer 인증서가 Android Keystore 인증서와 다릅니다."
        }
        return SigningEvidence(expected, v2Verified = true, v3Verified = true)
    }

    private fun certificateDigest(
        certificates: List<java.security.cert.X509Certificate>,
        scheme: String,
    ): String {
        require(certificates.size == 1) { "APK $scheme signer 인증서 수가 올바르지 않습니다." }
        return sha256Hex(certificates.single().encoded)
    }

    private fun rejectUnsafeOutput(input: Path, output: Path) {
        require(!Files.exists(output, NOFOLLOW_LINKS) || !Files.isSymbolicLink(output)) {
            "output APK symlink는 허용하지 않습니다."
        }
        if (Files.exists(output, NOFOLLOW_LINKS)) {
            require(Files.isRegularFile(output, NOFOLLOW_LINKS) && !Files.isSameFile(input, output)) {
                "unsigned APK와 output APK는 서로 달라야 합니다."
            }
        }
    }

    private data class TrustedParent(
        val path: Path,
        val realPath: Path,
        val fileKey: Any,
    ) {
        fun verifyUnchanged() {
            require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                "신뢰된 작업 디렉터리가 변경되었습니다."
            }
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attributes.fileKey() == fileKey && path.toRealPath(NOFOLLOW_LINKS) == realPath) {
                "신뢰된 작업 디렉터리가 변경되었습니다."
            }
        }

        companion object {
            fun capture(directory: Path): TrustedParent {
                val path = directory.toAbsolutePath().normalize()
                require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                    "신뢰된 작업 디렉터리가 올바르지 않습니다."
                }
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val fileKey = attributes.fileKey()
                    ?: throw IllegalArgumentException("신뢰된 작업 디렉터리 fileKey가 없습니다.")
                return TrustedParent(path, path.toRealPath(NOFOLLOW_LINKS), fileKey)
            }
        }
    }

    private companion object {
        const val MIN_SDK_VERSION = 30
        const val MIN_V2_SDK_VERSION = 24

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private data class SigningEvidence(
        val certificateSha256: String,
        val v2Verified: Boolean,
        val v3Verified: Boolean,
    )
}

private fun forceFile(file: Path) {
    FileChannel.open(file, WRITE).use { it.force(true) }
}
