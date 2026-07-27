package dev.sixbridge.manager.packager

import android.content.Context
import dev.sixbridge.contract.bundle.BundleJson
import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractExtensionDescriptor
import dev.sixbridge.contract.crx.Crx3Verifier
import dev.sixbridge.contract.crx.Crx3Writer
import dev.sixbridge.core.crx.Crx3
import dev.sixbridge.manager.conversion.ConversionIdentityLedger
import dev.sixbridge.manager.conversion.InstallationIdentityKey
import dev.sixbridge.manager.conversion.InstallationIdentityMode
import dev.sixbridge.manager.wrapper.AndroidArchiveInspector
import dev.sixbridge.manager.wrapper.AndroidWrapperVerifier
import dev.sixbridge.manager.wrapper.WrapperVerificationResult
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OnDeviceWrapperRequest(
    val reservation: ConversionIdentityLedger.Reservation,
    val canonicalSourceZip: ByteArray?,
    val passthroughCrx: ByteArray?,
    val descriptorName: String,
    val descriptorVersion: String,
    val sourceKind: String,
    val compatibility: ContractCompatibilityReport,
    val outputApk: File,
)

data class OnDeviceWrapperPathRequest(
    val reservation: ConversionIdentityLedger.Reservation,
    val canonicalSourceZip: File?,
    val passthroughCrx: File?,
    val descriptorName: String,
    val descriptorVersion: String,
    val sourceKind: String,
    val compatibility: ContractCompatibilityReport,
    val outputApk: File,
)

data class OnDevicePackageIdentity(
    val extensionId: String,
    val packageName: String,
    val wrapperSignerSha256: String,
    val crxKeyAlias: String?,
    val crxPublicKeyDer: ByteArray?,
)

class UnreservedIdentityLease internal constructor(
    val identitySha256: String,
    val identity: OnDevicePackageIdentity,
    internal val ownerToken: UUID,
) {
    val cleanupToken: String
        get() = ownerToken.toString()
}

@ConsistentCopyVisibility
data class OnDeviceWrapperResult internal constructor(
    val extensionId: String,
    val packageName: String,
    val versionCode: Int,
    val signerSha256: String,
    val crxSha256: String,
    val wrapperSha256: String,
    val descriptor: ContractExtensionDescriptor,
    val compatibility: ContractCompatibilityReport,
    val outputApk: File,
    internal val publishedOutputProof: PublishedOutputProof? = null,
)

internal data class PublishedOutputProof(
    val fileKey: Any,
    val size: Long,
    val lastModifiedEpochMillis: Long,
)

internal data class ValidatedOnDeviceWrapperRequest(
    val reservation: ConversionIdentityLedger.Reservation,
    val canonicalSourceZip: ByteArray?,
    val passthroughCrx: ByteArray?,
    val descriptorName: String,
    val descriptorVersion: String,
    val sourceKind: String,
    val compatibility: ContractCompatibilityReport,
    val outputApk: File,
)

private data class ValidatedPathWrapperRequest(
    val reservation: ConversionIdentityLedger.Reservation,
    val canonicalSourceZip: Path?,
    val passthroughCrx: Path?,
    val descriptorName: String,
    val descriptorVersion: String,
    val sourceKind: String,
    val compatibility: ContractCompatibilityReport,
    val outputApk: File,
)

/**
 * 검증된 변환 결과 또는 원본 CRX3를 Android Keystore wrapper identity와 결합한다.
 */
class OnDeviceWrapperPackager(context: Context) {
    private var testHooks: OnDevicePackagerTestHooks? = null

    internal constructor(context: Context, testHooks: OnDevicePackagerTestHooks) : this(context) {
        this.testHooks = testHooks
    }

    private val appContext = context.applicationContext
    private val workingDirectory = TrustedWorkingDirectory.capture(appPrivateWorkingDirectory(appContext.filesDir))
    private val temporaryDirectory = TrustedWorkingDirectory.capture(
        appPrivateTemporaryDirectory(workingDirectory.path),
    )
    private val identityStore = AndroidKeystoreIdentityStore()
    private val manifestPatcher = BinaryAndroidManifestPatcher()
    private val apkSigner = AndroidApkSigner()
    private val verifier = AndroidWrapperVerifier(
        AndroidArchiveInspector(appContext.packageManager),
        temporaryDirectory.path.toFile(),
    )
    init {
        reconcileOwnedTemporaryFiles()
    }

    fun outputFile(ownerToken: String): File {
        val canonical = UUID.fromString(ownerToken).toString()
        require(canonical == ownerToken) {
            "output owner token은 canonical UUID여야 합니다."
        }
        val output = workingDirectory.path.resolve("$canonical.apk").toFile()
        validatedOutput(output)
        return output
    }

    internal fun stageSource(ownerToken: String, bytes: ByteArray): File {
        require(UUID.fromString(ownerToken).toString() == ownerToken) {
            "source owner token은 canonical UUID여야 합니다."
        }
        require(bytes.size.toLong() in 1..ON_DEVICE_MAX_SOURCE_BYTES) {
            "온디바이스 source 크기가 올바르지 않습니다."
        }
        val staged = createTemporaryFile(".source.", ".bin")
        return try {
            writeSourceBytes(staged, bytes)
            staged.toFile()
        } catch (error: Throwable) {
            Files.deleteIfExists(staged)
            throw error
        }
    }

    @Synchronized
    fun resolveIdentity(
        identityKey: InstallationIdentityKey,
        passthroughCrx: ByteArray?,
    ): OnDevicePackageIdentity = resolveIdentitySnapshot(identityKey, passthroughCrx)

    @Synchronized
    fun resolveIdentityFromFile(
        identityKey: InstallationIdentityKey,
        passthroughCrx: File?,
    ): OnDevicePackageIdentity = resolveIdentityProof(
        identityKey,
        passthroughCrx?.let {
            Crx3Verifier.verify(it.toPath(), ON_DEVICE_MAX_SOURCE_BYTES)
        },
    )

    internal fun plannedCrxAlias(identityKey: InstallationIdentityKey): String {
        require(identityKey.mode == InstallationIdentityMode.CONVERTED) {
            "converted 신원만 CRX alias를 가집니다."
        }
        return identityStore.crxAlias(identityKey.identitySha256)
    }

    private fun resolveIdentitySnapshot(
        identityKey: InstallationIdentityKey,
        passthroughCrx: ByteArray?,
    ): OnDevicePackageIdentity = resolveIdentityProof(
        identityKey,
        passthroughCrx?.let {
            require(it.size.toLong() in 1..ON_DEVICE_MAX_SOURCE_BYTES) {
                "passthrough CRX3 크기가 올바르지 않습니다."
            }
            Crx3Verifier.verify(it)
        },
    )

    private fun resolveIdentityProof(
        identityKey: InstallationIdentityKey,
        passthroughProof: dev.sixbridge.contract.crx.VerifiedCrx3?,
    ): OnDevicePackageIdentity {
        val wrapperIdentity = identityStore.wrapperIdentity()
        val wrapperSignerSha256 = sha256(wrapperIdentity.certificate.encoded)
        return when (identityKey.mode) {
            InstallationIdentityMode.PASSTHROUGH -> {
                val proof = passthroughProof
                    ?: throw IllegalArgumentException("passthrough 신원에는 원본 CRX3가 필요합니다.")
                OnDevicePackageIdentity(
                    extensionId = proof.extensionId,
                    packageName = "dev.sixbridge.ext.${proof.extensionId}",
                    wrapperSignerSha256 = wrapperSignerSha256,
                    crxKeyAlias = null,
                    crxPublicKeyDer = null,
                )
            }
            InstallationIdentityMode.CONVERTED -> {
                require(passthroughProof == null) {
                    "converted 신원에는 passthrough CRX3를 사용할 수 없습니다."
                }
                val crxIdentity = identityStore.existingCrxIdentity(identityKey.identitySha256)
                val publicKey = crxIdentity.certificate.publicKey.encoded.copyOf()
                val extensionId = Crx3.extensionId(publicKey)
                OnDevicePackageIdentity(
                    extensionId = extensionId,
                    packageName = "dev.sixbridge.ext.$extensionId",
                    wrapperSignerSha256 = wrapperSignerSha256,
                    crxKeyAlias = crxIdentity.alias,
                    crxPublicKeyDer = publicKey,
                )
            }
        }
    }

    private fun resolveReservedIdentity(
        reservation: ConversionIdentityLedger.Reservation,
        passthroughProof: dev.sixbridge.contract.crx.VerifiedCrx3?,
    ): OnDevicePackageIdentity {
        val wrapperIdentity = identityStore.existingWrapperIdentity()
        val wrapperSignerSha256 = sha256(wrapperIdentity.certificate.encoded)
        val current = when (reservation.identityKey.mode) {
            InstallationIdentityMode.PASSTHROUGH -> {
                val proof = passthroughProof
                    ?: throw IllegalArgumentException("passthrough 예약에는 원본 CRX3가 필요합니다.")
                OnDevicePackageIdentity(
                    extensionId = proof.extensionId,
                    packageName = "dev.sixbridge.ext.${proof.extensionId}",
                    wrapperSignerSha256 = wrapperSignerSha256,
                    crxKeyAlias = null,
                    crxPublicKeyDer = null,
                )
            }
            InstallationIdentityMode.CONVERTED -> {
                require(passthroughProof == null) {
                    "converted 예약에는 passthrough CRX3를 사용할 수 없습니다."
                }
                val crxIdentity = identityStore.existingCrxIdentity(reservation.identitySha256)
                val publicKey = crxIdentity.certificate.publicKey.encoded.copyOf()
                val extensionId = Crx3.extensionId(publicKey)
                OnDevicePackageIdentity(
                    extensionId = extensionId,
                    packageName = "dev.sixbridge.ext.$extensionId",
                    wrapperSignerSha256 = wrapperSignerSha256,
                    crxKeyAlias = crxIdentity.alias,
                    crxPublicKeyDer = publicKey,
                )
            }
        }
        require(
            reservation.extensionId == current.extensionId &&
                reservation.packageName == current.packageName &&
                reservation.wrapperSignerSha256 == current.wrapperSignerSha256 &&
                reservation.crxKeyAlias == current.crxKeyAlias &&
                reservation.crxPublicKeySha256 == current.crxPublicKeyDer?.let(::sha256),
        ) {
            "예약된 설치 신원과 현재 Android Keystore package identity가 일치하지 않습니다."
        }
        return current
    }

    @Synchronized
    internal fun createOrRecoverUnreservedIdentity(
        identityKey: InstallationIdentityKey,
        ownerToken: UUID,
    ): UnreservedIdentityLease {
        require(identityKey.mode == InstallationIdentityMode.CONVERTED) {
            "converted CreateNew 신원만 미예약 lease를 만들 수 있습니다."
        }
        val wrapperIdentity = identityStore.wrapperIdentity()
        val crxIdentity = identityStore.createCrxIdentityIfAbsent(identityKey.identitySha256)
            ?: identityStore.existingCrxIdentity(identityKey.identitySha256)
        val publicKey = crxIdentity.certificate.publicKey.encoded.copyOf()
        val extensionId = Crx3.extensionId(publicKey)
        val identity = OnDevicePackageIdentity(
            extensionId = extensionId,
            packageName = "dev.sixbridge.ext.$extensionId",
            wrapperSignerSha256 = sha256(wrapperIdentity.certificate.encoded),
            crxKeyAlias = crxIdentity.alias,
            crxPublicKeyDer = publicKey,
        )
        testHooks?.afterUnreservedIdentityCreated?.invoke()
        return UnreservedIdentityLease(identityKey.identitySha256, identity, ownerToken)
    }

    @Synchronized
    internal fun deleteUnreservedIdentity(
        identitySha256: String,
        expectedAlias: String,
        expectedExtensionId: String?,
        expectedPublicKeySha256: String?,
    ): Boolean {
        if (!identityStore.hasCrxIdentity(identitySha256)) return true
        val current = identityStore.existingCrxIdentity(identitySha256)
        val currentPublicKey = current.certificate.publicKey.encoded
        require(
            current.alias == expectedAlias &&
                (expectedExtensionId == null || expectedExtensionId == Crx3.extensionId(currentPublicKey)) &&
                (expectedPublicKeySha256 == null || expectedPublicKeySha256 == sha256(currentPublicKey)),
        ) {
            "정리할 미예약 CRX 신원 소유권이 일치하지 않습니다."
        }
        return identityStore.deleteCrxIdentity(identitySha256, expectedAlias)
    }

    suspend fun packageWrapper(
        ledger: ConversionIdentityLedger,
        request: OnDeviceWrapperRequest,
    ): OnDeviceWrapperResult = ledger.packageWrapper(this, request)

    suspend fun packageWrapper(
        ledger: ConversionIdentityLedger,
        request: OnDeviceWrapperPathRequest,
    ): OnDeviceWrapperResult = ledger.packageWrapper(this, request)

    internal suspend fun packageClaimed(
        ledger: ConversionIdentityLedger,
        receipt: ConversionIdentityLedger.PackagingReceipt,
        request: OnDeviceWrapperRequest,
    ): OnDeviceWrapperResult {
        ledger.requirePackagingReceipt(receipt, request.reservation)
        val snapshot = request.snapshot()
        validateBasicRequest(snapshot)
        val stagedSource = createTemporaryFile(".source.", ".bin")
        try {
            val sourceBytes = snapshot.canonicalSourceZip ?: requireNotNull(snapshot.passthroughCrx)
            writeSourceBytes(stagedSource, sourceBytes)
            return packageStaged(
                ledger = ledger,
                receipt = receipt,
                request = ValidatedPathWrapperRequest(
                    reservation = snapshot.reservation,
                    canonicalSourceZip =
                        stagedSource.takeIf { snapshot.canonicalSourceZip != null },
                    passthroughCrx =
                        stagedSource.takeIf { snapshot.passthroughCrx != null },
                    descriptorName = snapshot.descriptorName,
                    descriptorVersion = snapshot.descriptorVersion,
                    sourceKind = snapshot.sourceKind,
                    compatibility = snapshot.compatibility,
                    outputApk = snapshot.outputApk,
                ),
            )
        } finally {
            Files.deleteIfExists(stagedSource)
        }
    }

    internal suspend fun packageClaimed(
        ledger: ConversionIdentityLedger,
        receipt: ConversionIdentityLedger.PackagingReceipt,
        request: OnDeviceWrapperPathRequest,
    ): OnDeviceWrapperResult {
        ledger.requirePackagingReceipt(receipt, request.reservation)
        val snapshot = request.snapshot()
        validatePathRequest(snapshot)
        val source = snapshot.canonicalSourceZip ?: requireNotNull(snapshot.passthroughCrx)
        val stagedSource = createTemporaryFile(".source.", ".bin")
        try {
            copyStableSource(source, stagedSource)
            return packageStaged(
                ledger = ledger,
                receipt = receipt,
                request = snapshot.copy(
                    canonicalSourceZip =
                        stagedSource.takeIf { snapshot.canonicalSourceZip != null },
                    passthroughCrx =
                        stagedSource.takeIf { snapshot.passthroughCrx != null },
                ),
            )
        } finally {
            Files.deleteIfExists(stagedSource)
        }
    }

    private suspend fun packageStaged(
        ledger: ConversionIdentityLedger,
        receipt: ConversionIdentityLedger.PackagingReceipt,
        request: ValidatedPathWrapperRequest,
    ): OnDeviceWrapperResult {
        val passthroughProof = request.passthroughCrx?.let {
            Crx3Verifier.verify(it, ON_DEVICE_MAX_SOURCE_BYTES)
        }
        val identity = resolveReservedIdentity(request.reservation, passthroughProof)
        val generatedCrx = createTemporaryFile(".extension.", ".crx")
        try {
            val crxPath = when (request.reservation.identityKey.mode) {
                InstallationIdentityMode.PASSTHROUGH -> requireNotNull(request.passthroughCrx)
                InstallationIdentityMode.CONVERTED -> {
                    Crx3Writer.create(
                        requireNotNull(request.canonicalSourceZip),
                        generatedCrx,
                        identityStore.existingCrxSigningKey(request.reservation.identitySha256),
                    )
                    generatedCrx
                }
            }
            val crxProof = Crx3Verifier.verify(crxPath, ON_DEVICE_MAX_CRX_BYTES)
            validateBoundPathRequest(request, identity, crxProof)
            testHooks?.afterRequestValidated?.invoke()
            val output = validatedOutput(request.outputApk)
            val temporaryTemplate = createTemporaryFile(".wrapper-template.", ".apk")
            val unsigned = createTemporaryFile(".unsigned.", ".apk")
            val signed = createTemporaryFile(".signed.", ".apk")
            try {
                val template = readVerifiedTemplate(temporaryTemplate)
                val templateManifest = ZipFile(template.toFile()).use { archive ->
                    archive.getInputStream(
                        archive.getEntry(MANIFEST_ENTRY)
                            ?: error("래퍼 template manifest가 없습니다."),
                    ).use(InputStream::readBytes)
                }
                val patchedManifest = manifestPatcher.patch(
                    templateManifest,
                    WrapperManifestRequest(
                        extensionId = identity.extensionId,
                        versionCode = request.reservation.versionCode,
                    ),
                )
                AlignedApkAssembler(temporaryDirectory.path).assemble(
                    ApkAssemblyPathRequest(
                        templateApk = template.toFile(),
                        manifestBytes = patchedManifest,
                        crxFile = crxPath.toFile(),
                        outputApk = unsigned.toFile(),
                    ),
                )
                val signing = apkSigner.sign(
                    unsigned.toFile(),
                    signed.toFile(),
                    identityStore.wrapperIdentity(),
                )
                require(signing.signerCertificateSha256 == identity.wrapperSignerSha256) {
                    "wrapper signer가 사전 해석한 package identity와 일치하지 않습니다."
                }
                val descriptor = ContractExtensionDescriptor(
                    schemaVersion = 1,
                    extensionId = identity.extensionId,
                    name = request.descriptorName,
                    version = request.descriptorVersion,
                    sourceKind = request.sourceKind,
                    crxSha256 = crxProof.crxSha256,
                    wrapperSha256 = sha256(signed),
                    wrapperPackage = identity.packageName,
                    wrapperVersionCode = request.reservation.versionCode,
                    signerSha256 = signing.signerCertificateSha256,
                )
                require(
                    verifier.verify(
                        signed.toFile(),
                        descriptor,
                        request.compatibility,
                    ) is WrapperVerificationResult.Success,
                ) {
                    "생성된 wrapper APK 검증에 실패했습니다."
                }
                verifyPublishTarget(output)
                testHooks?.beforeFinalPublish?.invoke()
                verifyPublishTarget(output)
                forceFile(signed)
                ledger.markPublishing(
                    receipt = receipt,
                    reservation = request.reservation,
                    wrapperSha256 = descriptor.wrapperSha256,
                    outputFileName = output.fileName.toString(),
                )
                verifyPublishTarget(output)
                Files.move(signed, output, ATOMIC_MOVE, REPLACE_EXISTING)
                testHooks?.afterFinalPublish?.invoke()
                forceDirectory(workingDirectory.path)
                verifyPublishTarget(output)
                val publishedOutputProof = verifyPublishedOutputIdentity(
                    output = output,
                    expectedSha256 = descriptor.wrapperSha256,
                    label = "publish된 wrapper APK",
                )
                return OnDeviceWrapperResult(
                    extensionId = identity.extensionId,
                    packageName = identity.packageName,
                    versionCode = request.reservation.versionCode,
                    signerSha256 = signing.signerCertificateSha256,
                    crxSha256 = descriptor.crxSha256,
                    wrapperSha256 = descriptor.wrapperSha256,
                    descriptor = descriptor,
                    compatibility = request.compatibility,
                    outputApk = output.toFile(),
                    publishedOutputProof = publishedOutputProof,
                )
            } finally {
                Files.deleteIfExists(temporaryTemplate)
                Files.deleteIfExists(unsigned)
                Files.deleteIfExists(signed)
            }
        } finally {
            Files.deleteIfExists(generatedCrx)
        }
    }

    private fun validateBasicRequest(request: ValidatedOnDeviceWrapperRequest) {
        requireBasicRequest(request)
    }

    private fun validateBoundRequest(
        request: ValidatedOnDeviceWrapperRequest,
        identity: OnDevicePackageIdentity,
        crxBytes: ByteArray,
    ) {
        requireBoundRequest(request, identity, crxBytes)
    }

    private fun validatePathRequest(request: ValidatedPathWrapperRequest) {
        val hasCanonicalZip = request.canonicalSourceZip != null
        val hasPassthroughCrx = request.passthroughCrx != null
        require(hasCanonicalZip.xor(hasPassthroughCrx)) {
            "canonical source ZIP과 passthrough CRX3 중 정확히 하나가 필요합니다."
        }
        when (request.reservation.identityKey.mode) {
            InstallationIdentityMode.PASSTHROUGH -> require(hasPassthroughCrx) {
                "passthrough 신원에는 원본 CRX3가 필요합니다."
            }
            InstallationIdentityMode.CONVERTED -> require(hasCanonicalZip) {
                "converted 신원에는 canonical source ZIP이 필요합니다."
            }
        }
        val source = request.canonicalSourceZip ?: requireNotNull(request.passthroughCrx)
        val attributes = Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(
            attributes.isRegularFile && !attributes.isSymbolicLink &&
                attributes.size() in 4..ON_DEVICE_MAX_SOURCE_BYTES,
        ) {
            "온디바이스 source file 크기 또는 형식이 올바르지 않습니다."
        }
        require(request.reservation.versionCode > 0) { "versionCode는 0보다 커야 합니다." }
        require(request.descriptorName.isNotBlank() && request.descriptorVersion.isNotBlank()) {
            "extension name과 version은 비어 있지 않아야 합니다."
        }
        require(request.sourceKind in ON_DEVICE_SOURCE_KINDS) { "sourceKind가 올바르지 않습니다." }
        require(request.compatibility.schemaVersion == 4) {
            "온디바이스 package에는 compatibility schema v4가 필요합니다."
        }
        BundleJson.validateCompatibility(request.compatibility)
    }

    private fun validateBoundPathRequest(
        request: ValidatedPathWrapperRequest,
        identity: OnDevicePackageIdentity,
        proof: dev.sixbridge.contract.crx.VerifiedCrx3,
    ) {
        validatePathRequest(request)
        require(ON_DEVICE_EXTENSION_ID.matches(identity.extensionId)) {
            "package identity extension ID가 올바르지 않습니다."
        }
        require(identity.packageName == "dev.sixbridge.ext.${identity.extensionId}") {
            "package identity package가 extension ID와 일치하지 않습니다."
        }
        require(ON_DEVICE_SHA256.matches(identity.wrapperSignerSha256)) {
            "package identity wrapper signer가 올바르지 않습니다."
        }
        require(proof.extensionId == identity.extensionId) {
            "CRX3 extension ID가 package identity와 일치하지 않습니다."
        }
        val report = request.compatibility
        require(
            report.convertedExtensionId == identity.extensionId &&
                report.sourceSha256 == proof.zipSha256,
        ) {
            "compatibility source 또는 converted ID가 CRX3 proof와 일치하지 않습니다."
        }
        require(
            report.extensionName == request.descriptorName &&
                report.extensionVersion == request.descriptorVersion,
        ) {
            "compatibility extension identity가 descriptor와 일치하지 않습니다."
        }
        when (request.reservation.identityKey.mode) {
            InstallationIdentityMode.PASSTHROUGH -> {
                require(identity.crxKeyAlias == null && identity.crxPublicKeyDer == null) {
                    "passthrough package identity에 CRX Keystore key가 포함되었습니다."
                }
                require(
                    report.conversionState == "passthrough" &&
                        report.originalExtensionId == identity.extensionId,
                ) {
                    "passthrough compatibility identity가 원본 CRX3와 일치하지 않습니다."
                }
            }
            InstallationIdentityMode.CONVERTED -> {
                val publicKey = identity.crxPublicKeyDer
                    ?: throw IllegalArgumentException("converted package identity 공개 키가 없습니다.")
                require(!identity.crxKeyAlias.isNullOrBlank()) {
                    "converted package identity alias가 없습니다."
                }
                require(
                    proof.publicKeySha256 == sha256Hex(publicKey) &&
                        sha256(request.canonicalSourceZip!!) == proof.zipSha256,
                ) {
                    "converted CRX3 proof가 package identity 또는 canonical ZIP과 일치하지 않습니다."
                }
                require(report.conversionState != "passthrough") {
                    "converted package에는 passthrough compatibility를 사용할 수 없습니다."
                }
            }
        }
    }

    private fun writeSourceBytes(destination: Path, source: ByteArray) {
        FileChannel.open(destination, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { channel ->
            var offset = 0
            while (offset < source.size) {
                val count = channel.write(ByteBuffer.wrap(source, offset, source.size - offset))
                require(count > 0) { "온디바이스 source 기록이 진행되지 않습니다." }
                offset += count
            }
            channel.force(true)
        }
    }

    private fun copyStableSource(source: Path, destination: Path) {
        val before = Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(
            before.isRegularFile && !before.isSymbolicLink &&
                before.size() in 4..ON_DEVICE_MAX_SOURCE_BYTES,
        ) {
            "온디바이스 source file이 올바르지 않습니다."
        }
        FileChannel.open(source, READ, NOFOLLOW_LINKS).use { input ->
            FileChannel.open(destination, WRITE, TRUNCATE_EXISTING, NOFOLLOW_LINKS).use { output ->
                val buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE)
                var remaining = before.size()
                while (remaining > 0L) {
                    buffer.clear()
                    buffer.limit(minOf(buffer.capacity().toLong(), remaining).toInt())
                    val count = input.read(buffer)
                    require(count > 0) { "온디바이스 source file이 잘렸습니다." }
                    buffer.flip()
                    while (buffer.hasRemaining()) output.write(buffer)
                    remaining -= count
                }
                require(input.read(ByteBuffer.allocate(1)) < 0) {
                    "온디바이스 source file 크기가 변경되었습니다."
                }
                output.force(true)
            }
        }
        val after = Files.readAttributes(source, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(
            after.fileKey() == before.fileKey() &&
                after.size() == before.size() &&
                after.lastModifiedTime() == before.lastModifiedTime(),
        ) {
            "온디바이스 source file이 snapshot 중 변경되었습니다."
        }
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

    private fun appPrivateTemporaryDirectory(root: Path): Path {
        val temporary = root.resolve(TEMPORARY_DIRECTORY)
        if (!Files.exists(temporary, NOFOLLOW_LINKS)) Files.createDirectory(temporary)
        require(Files.isDirectory(temporary, NOFOLLOW_LINKS) && !Files.isSymbolicLink(temporary)) {
            "온디바이스 packager temporary 디렉터리가 올바르지 않습니다."
        }
        return temporary
    }

    private fun createTemporaryFile(prefix: String, suffix: String): Path {
        temporaryDirectory.verifyUnchanged()
        return Files.createTempFile(temporaryDirectory.path, prefix, suffix)
    }

    private fun reconcileOwnedTemporaryFiles() {
        temporaryDirectory.verifyUnchanged()
        Files.newDirectoryStream(temporaryDirectory.path).use { entries ->
            entries.forEach { path ->
                if (!PACKAGER_TEMPORARY_FILE.matches(path.fileName.toString())) {
                    return@forEach
                }
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                require(
                    attributes.isRegularFile &&
                        !attributes.isSymbolicLink &&
                        unixLinkCount(path) == 1,
                ) {
                    "중단된 packager temporary 파일이 단일 regular file이 아닙니다."
                }
                Files.delete(path)
            }
        }
        forceDirectory(temporaryDirectory.path)
    }

    private fun validatedOutput(file: File): Path {
        val output = file.toPath().toAbsolutePath().normalize()
        verifyPublishTarget(output)
        return output
    }

    private fun verifyPublishTarget(output: Path) {
        workingDirectory.verifyUnchanged()
        require(output.parent == workingDirectory.path && output.fileName.toString().endsWith(".apk")) {
            "output APK는 앱 private ondevice-packager-gate 디렉터리에 있어야 합니다."
        }
        rejectUnsafeOutput(output)
    }

    private fun rejectUnsafeOutput(output: Path) {
        require(!Files.exists(output, NOFOLLOW_LINKS) || !Files.isSymbolicLink(output)) {
            "output APK symlink는 허용하지 않습니다."
        }
        if (Files.exists(output, NOFOLLOW_LINKS)) {
            require(
                Files.isRegularFile(output, NOFOLLOW_LINKS) && unixLinkCount(output) == 1,
            ) {
                "output APK는 단일 regular file이어야 합니다."
            }
        }
    }

    internal suspend fun discardPublishedResult(
        result: OnDeviceWrapperResult,
        identitySha256: String,
        claimUuid: UUID,
    ) {
        require(SHA256.matches(identitySha256)) {
            "identity SHA-256이 올바르지 않습니다."
        }
        discardPublishedOutputLocked(
            claimUuid = claimUuid,
            outputFileName = result.outputApk.name,
            wrapperSha256 = result.wrapperSha256,
            expectedProof = requireNotNull(result.publishedOutputProof) {
                "정리할 published wrapper의 publish proof가 없습니다."
            },
        )
    }

    internal suspend fun verifyPublishedOutput(
        identitySha256: String,
        outputFileName: String,
        wrapperSha256: String,
    ): Boolean = withIdentityMutationLock(identitySha256) {
        require(SHA256.matches(wrapperSha256)) {
            "복구할 published wrapper SHA-256이 올바르지 않습니다."
        }
        val output = validatedOutput(File(workingDirectory.path.toFile(), outputFileName))
        if (!Files.exists(output, NOFOLLOW_LINKS)) return@withIdentityMutationLock false
        val before = Files.readAttributes(output, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(
            before.fileKey() != null &&
                before.isRegularFile &&
                !before.isSymbolicLink &&
                unixLinkCount(output) == 1,
        ) {
            "복구할 published wrapper identity가 올바르지 않습니다."
        }
        val actualSha256 = sha256(output)
        val after = Files.readAttributes(output, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(
            after.fileKey() == before.fileKey() &&
                after.size() == before.size() &&
                after.lastModifiedTime() == before.lastModifiedTime() &&
                unixLinkCount(output) == 1 &&
                actualSha256 == wrapperSha256,
        ) {
            "복구할 published wrapper proof가 원장과 일치하지 않습니다."
        }
        true
    }

    internal suspend fun discardPublishedOutput(
        identitySha256: String,
        claimUuid: UUID,
        outputFileName: String,
        wrapperSha256: String,
    ) {
        discardPublishedOutput(
            identitySha256 = identitySha256,
            claimUuid = claimUuid,
            outputFileName = outputFileName,
            wrapperSha256 = wrapperSha256,
            expectedProof = null,
        )
    }

    private suspend fun discardPublishedOutput(
        identitySha256: String,
        claimUuid: UUID,
        outputFileName: String,
        wrapperSha256: String,
        expectedProof: PublishedOutputProof?,
    ) = withIdentityMutationLock(identitySha256) {
        discardPublishedOutputLocked(
            claimUuid = claimUuid,
            outputFileName = outputFileName,
            wrapperSha256 = wrapperSha256,
            expectedProof = expectedProof,
        )
    }

    private fun discardPublishedOutputLocked(
        claimUuid: UUID,
        outputFileName: String,
        wrapperSha256: String,
        expectedProof: PublishedOutputProof?,
    ) {
        require(SHA256.matches(wrapperSha256)) {
            "정리할 published wrapper SHA-256이 올바르지 않습니다."
        }
        val output = validatedOutput(File(workingDirectory.path.toFile(), outputFileName))
        val quarantine = temporaryDirectory.path.resolve(".published-cleanup.$claimUuid.apk")
        testHooks?.beforePublishedCleanupMove?.invoke(output.toFile())
        if (!Files.exists(output, NOFOLLOW_LINKS)) {
            if (!Files.exists(quarantine, NOFOLLOW_LINKS)) {
                require(expectedProof == null) {
                    "해당 package claim이 publish한 wrapper가 정리 전에 유실되었습니다."
                }
                return
            }
            verifyAndDeletePublishedQuarantine(quarantine, wrapperSha256)
            return
        }
        require(!Files.exists(quarantine, NOFOLLOW_LINKS)) {
            "동일 package claim의 output과 cleanup quarantine이 동시에 존재합니다."
        }
        val before = Files.readAttributes(output, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(!before.isSymbolicLink && before.isRegularFile && unixLinkCount(output) == 1) {
            "정리할 published wrapper identity가 올바르지 않습니다."
        }
        val fileKey = before.fileKey()
            ?: throw IllegalArgumentException("정리할 published wrapper fileKey가 없습니다.")
        require(
            expectedProof == null ||
                (
                    expectedProof.fileKey == fileKey &&
                        expectedProof.size == before.size() &&
                        expectedProof.lastModifiedEpochMillis ==
                        before.lastModifiedTime().toMillis()
                    )
        ) {
            "정리할 published wrapper가 해당 package claim이 publish한 파일이 아닙니다."
        }
        Files.move(output, quarantine, ATOMIC_MOVE)
        forceDirectory(workingDirectory.path)
        try {
            verifyAndDeletePublishedQuarantine(quarantine, wrapperSha256, fileKey)
        } catch (error: Throwable) {
            if (!Files.exists(output, NOFOLLOW_LINKS) && Files.exists(quarantine, NOFOLLOW_LINKS)) {
                Files.move(quarantine, output, ATOMIC_MOVE)
                forceDirectory(workingDirectory.path)
            }
            throw error
        }
    }

    private fun verifyAndDeletePublishedQuarantine(
        quarantine: Path,
        wrapperSha256: String,
        expectedFileKey: Any? = null,
    ) {
        val before = Files.readAttributes(quarantine, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val fileKey = before.fileKey()
            ?: throw IllegalArgumentException("cleanup quarantine fileKey가 없습니다.")
        require(
            (expectedFileKey == null || expectedFileKey == fileKey) &&
                before.isRegularFile &&
                !before.isSymbolicLink &&
                unixLinkCount(quarantine) == 1,
        ) {
            "cleanup quarantine identity가 claim proof와 일치하지 않습니다."
        }
        val actualSha256 = sha256(quarantine)
        val after = Files.readAttributes(quarantine, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(
            after.fileKey() == fileKey &&
                after.size() == before.size() &&
                after.lastModifiedTime() == before.lastModifiedTime() &&
                unixLinkCount(quarantine) == 1 &&
                actualSha256 == wrapperSha256,
        ) {
            "cleanup quarantine가 claim-owned wrapper와 일치하지 않습니다."
        }
        check(Files.deleteIfExists(quarantine)) {
            "claim-owned published wrapper를 정리하지 못했습니다."
        }
        forceDirectory(temporaryDirectory.path)
    }

    private fun verifyPublishedOutputIdentity(
        output: Path,
        expectedSha256: String,
        label: String,
    ): PublishedOutputProof {
        val before = Files.readAttributes(output, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        val fileKey = before.fileKey()
            ?: throw IllegalArgumentException("$label fileKey가 없습니다.")
        require(
            before.isRegularFile &&
                !before.isSymbolicLink &&
                unixLinkCount(output) == 1,
        ) {
            "$label identity가 올바르지 않습니다."
        }
        val actualSha256 = sha256(output)
        val after = Files.readAttributes(output, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(
            after.fileKey() == fileKey &&
                after.size() == before.size() &&
                after.lastModifiedTime() == before.lastModifiedTime() &&
                unixLinkCount(output) == 1 &&
                actualSha256 == expectedSha256,
        ) {
            "$label proof가 일치하지 않습니다."
        }
        return PublishedOutputProof(
            fileKey = fileKey,
            size = before.size(),
            lastModifiedEpochMillis = before.lastModifiedTime().toMillis(),
        )
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

    private suspend fun <T> withIdentityMutationLock(
        identitySha256: String,
        block: suspend () -> T,
    ): T {
        require(SHA256.matches(identitySha256)) {
            "identity SHA-256이 올바르지 않습니다."
        }
        val key = "${workingDirectory.path}:$identitySha256"
        return IDENTITY_MUTEXES.computeIfAbsent(key) { Mutex() }.withLock {
            workingDirectory.verifyUnchanged()
            val lockPath = workingDirectory.path.resolve(".identity-lock.$identitySha256.lck")
            require(
                !Files.exists(lockPath, NOFOLLOW_LINKS) ||
                    (
                        Files.isRegularFile(lockPath, NOFOLLOW_LINKS) &&
                            !Files.isSymbolicLink(lockPath) &&
                            unixLinkCount(lockPath) == 1
                        ),
            ) {
                "identity mutation lock 파일이 올바르지 않습니다."
            }
            FileChannel.open(lockPath, CREATE, WRITE, NOFOLLOW_LINKS).use { channel ->
                val attributes = Files.readAttributes(
                    lockPath,
                    BasicFileAttributes::class.java,
                    NOFOLLOW_LINKS,
                )
                require(
                    attributes.isRegularFile &&
                        !attributes.isSymbolicLink &&
                        unixLinkCount(lockPath) == 1,
                ) {
                    "identity mutation lock 파일이 단일 regular file이 아닙니다."
                }
                channel.lock().use {
                    block()
                }
            }
        }
    }

    internal suspend fun <T> withIdentityMutationLockForTest(
        identitySha256: String,
        block: suspend () -> T,
    ): T = withIdentityMutationLock(identitySha256, block)

    internal suspend fun <T> withIdentityMutationLockForLedger(
        identitySha256: String,
        block: suspend () -> T,
    ): T = withIdentityMutationLock(identitySha256, block)

    private fun forceFile(file: Path) {
        FileChannel.open(file, WRITE).use { it.force(true) }
    }

    private fun forceDirectory(directory: Path) {
        FileChannel.open(directory, READ).use { it.force(true) }
    }

    private fun unixLinkCount(path: Path): Int =
        (Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) as Number).toInt()

    private data class TrustedWorkingDirectory(
        val path: Path,
        val realPath: Path,
        val fileKey: Any,
    ) {
        fun verifyUnchanged() {
            require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                "온디바이스 packager 작업 디렉터리가 변경되었습니다."
            }
            val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            require(attributes.fileKey() == fileKey && path.toRealPath(NOFOLLOW_LINKS) == realPath) {
                "온디바이스 packager 작업 디렉터리가 변경되었습니다."
            }
        }

        companion object {
            fun capture(directory: Path): TrustedWorkingDirectory {
                val path = directory.toAbsolutePath().normalize()
                require(Files.isDirectory(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)) {
                    "온디바이스 packager 작업 디렉터리가 올바르지 않습니다."
                }
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                val fileKey = attributes.fileKey()
                    ?: throw IllegalArgumentException("온디바이스 packager 작업 디렉터리 fileKey가 없습니다.")
                return TrustedWorkingDirectory(path, path.toRealPath(NOFOLLOW_LINKS), fileKey)
            }
        }
    }

    private companion object {
        val IDENTITY_MUTEXES = ConcurrentHashMap<String, Mutex>()
        const val WORKING_DIRECTORY = "ondevice-packager-gate"
        const val TEMPORARY_DIRECTORY = "tmp"
        const val TEMPLATE_ASSET = "wrapper-template.apk"
        const val TEMPLATE_SHA256_ASSET = "wrapper-template.sha256"
        const val MANIFEST_ENTRY = "AndroidManifest.xml"
        val PACKAGER_TEMPORARY_FILE = Regex(
            """(?:\.source\.[0-9]{1,20}\.bin|""" +
                """\.extension\.[0-9]{1,20}\.crx|""" +
                """\.wrapper-template\.[0-9]{1,20}\.apk|""" +
                """\.unsigned\.[0-9]{1,20}\.apk|""" +
                """\.signed\.[0-9]{1,20}\.apk|""" +
                """\.\.(?:unsigned|signed)\.[0-9]{1,20}\.apk\.[0-9]{1,20}\.tmp)""",
        )
        val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal fun OnDeviceWrapperRequest.validateFor(
    identity: OnDevicePackageIdentity,
    embeddedCrx: ByteArray,
): ValidatedOnDeviceWrapperRequest = snapshot().also { request ->
    requireBasicRequest(request)
    requireBoundRequest(request, identity, embeddedCrx)
}

private fun OnDeviceWrapperRequest.snapshot() = ValidatedOnDeviceWrapperRequest(
    reservation = reservation,
    canonicalSourceZip = canonicalSourceZip?.copyOf(),
    passthroughCrx = passthroughCrx?.copyOf(),
    descriptorName = descriptorName,
    descriptorVersion = descriptorVersion,
    sourceKind = sourceKind,
    compatibility = compatibility.snapshot(),
    outputApk = outputApk,
)

private fun OnDeviceWrapperPathRequest.snapshot() = ValidatedPathWrapperRequest(
    reservation = reservation,
    canonicalSourceZip = canonicalSourceZip?.toPath()?.toAbsolutePath()?.normalize(),
    passthroughCrx = passthroughCrx?.toPath()?.toAbsolutePath()?.normalize(),
    descriptorName = descriptorName,
    descriptorVersion = descriptorVersion,
    sourceKind = sourceKind,
    compatibility = compatibility.snapshot(),
    outputApk = outputApk,
)

private fun ContractCompatibilityReport.snapshot(): ContractCompatibilityReport =
    BundleJson.decodeCompatibility(BundleJson.encodeCompatibility(this))

private fun requireBasicRequest(request: ValidatedOnDeviceWrapperRequest) {
    val hasCanonicalZip = request.canonicalSourceZip != null
    val hasPassthroughCrx = request.passthroughCrx != null
    require(hasCanonicalZip.xor(hasPassthroughCrx)) {
        "canonical source ZIP과 passthrough CRX3 중 정확히 하나가 필요합니다."
    }
    when (request.reservation.identityKey.mode) {
        InstallationIdentityMode.PASSTHROUGH -> require(hasPassthroughCrx) {
            "passthrough 신원에는 원본 CRX3가 필요합니다."
        }
        InstallationIdentityMode.CONVERTED -> require(hasCanonicalZip) {
            "converted 신원에는 canonical source ZIP이 필요합니다."
        }
    }
    request.canonicalSourceZip?.let {
        require(it.size.toLong() in 4..ON_DEVICE_MAX_SOURCE_BYTES) {
            "canonical source ZIP 크기가 올바르지 않습니다."
        }
    }
    request.passthroughCrx?.let {
        require(it.size.toLong() in 1..ON_DEVICE_MAX_SOURCE_BYTES) {
            "passthrough CRX3 크기가 올바르지 않습니다."
        }
    }
    require(request.reservation.versionCode > 0) { "versionCode는 0보다 커야 합니다." }
    require(request.descriptorName.isNotBlank() && request.descriptorVersion.isNotBlank()) {
        "extension name과 version은 비어 있지 않아야 합니다."
    }
    require(request.sourceKind in ON_DEVICE_SOURCE_KINDS) {
        "sourceKind가 올바르지 않습니다."
    }
    require(request.compatibility.schemaVersion == 4) {
        "온디바이스 package에는 compatibility schema v4가 필요합니다."
    }
    BundleJson.validateCompatibility(request.compatibility)
}

private fun requireBoundRequest(
    request: ValidatedOnDeviceWrapperRequest,
    identity: OnDevicePackageIdentity,
    crxBytes: ByteArray,
) {
    requireBasicRequest(request)
    require(ON_DEVICE_EXTENSION_ID.matches(identity.extensionId)) {
        "package identity extension ID가 올바르지 않습니다."
    }
    require(identity.packageName == "dev.sixbridge.ext.${identity.extensionId}") {
        "package identity package가 extension ID와 일치하지 않습니다."
    }
    require(ON_DEVICE_SHA256.matches(identity.wrapperSignerSha256)) {
        "package identity wrapper signer가 올바르지 않습니다."
    }
    val proof = Crx3Verifier.verify(crxBytes)
    require(proof.extensionId == identity.extensionId) {
        "CRX3 extension ID가 package identity와 일치하지 않습니다."
    }
    val report = request.compatibility
    require(report.convertedExtensionId == identity.extensionId) {
        "compatibility converted extension ID가 package identity와 일치하지 않습니다."
    }
    require(report.sourceSha256 == proof.zipSha256) {
        "compatibility source SHA-256이 CRX3 ZIP payload와 일치하지 않습니다."
    }
    require(
        report.extensionName == request.descriptorName &&
            report.extensionVersion == request.descriptorVersion,
    ) {
        "compatibility extension identity가 descriptor와 일치하지 않습니다."
    }
    require(
        request.reservation.extensionId == identity.extensionId &&
            request.reservation.packageName == identity.packageName &&
            request.reservation.wrapperSignerSha256 == identity.wrapperSignerSha256 &&
            request.reservation.crxKeyAlias == identity.crxKeyAlias,
    ) {
        "예약된 설치 신원이 package identity와 일치하지 않습니다."
    }
    when (request.reservation.identityKey.mode) {
        InstallationIdentityMode.PASSTHROUGH -> {
            require(request.passthroughCrx!!.contentEquals(crxBytes)) {
                "passthrough CRX3 bytes가 원본과 일치하지 않습니다."
            }
            require(identity.crxKeyAlias == null && identity.crxPublicKeyDer == null) {
                "passthrough package identity에 CRX Keystore key가 포함되었습니다."
            }
            require(
                report.conversionState == "passthrough" &&
                    report.originalExtensionId == identity.extensionId,
            ) {
                "passthrough compatibility identity가 원본 CRX3와 일치하지 않습니다."
            }
        }
        InstallationIdentityMode.CONVERTED -> {
            val publicKey = identity.crxPublicKeyDer
                ?: throw IllegalArgumentException("converted package identity 공개 키가 없습니다.")
            require(!identity.crxKeyAlias.isNullOrBlank()) {
                "converted package identity alias가 없습니다."
            }
            require(
                proof.publicKeySha256 == sha256Hex(publicKey) &&
                    request.canonicalSourceZip!!.let(::sha256Hex) == proof.zipSha256,
            ) {
                "converted CRX3 proof가 package identity 또는 canonical ZIP과 일치하지 않습니다."
            }
            require(report.conversionState != "passthrough") {
                "converted package에는 passthrough compatibility를 사용할 수 없습니다."
            }
        }
    }
}

private fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

private val ON_DEVICE_EXTENSION_ID = Regex("[a-p]{32}")
private val ON_DEVICE_SHA256 = Regex("[0-9a-f]{64}")
private val ON_DEVICE_SOURCE_KINDS =
    setOf("SOURCE_DIRECTORY", "ZIP", "CRX3", "CHROME_WEB_STORE")
// Task 6 source 계약 128 MiB에 CRX3 고정 header prefix와 최대 protobuf header를 더한다.
private const val ON_DEVICE_MAX_SOURCE_BYTES = 128L * 1024 * 1024
private const val ON_DEVICE_MAX_CRX_BYTES = ON_DEVICE_MAX_SOURCE_BYTES + 1024L * 1024 + 12L
private const val STREAM_BUFFER_SIZE = 64 * 1024

internal data class OnDevicePackagerTestHooks(
    val afterUnreservedIdentityCreated: (() -> Unit)? = null,
    val afterRequestValidated: (() -> Unit)? = null,
    val beforeFinalPublish: (() -> Unit)? = null,
    val afterFinalPublish: (() -> Unit)? = null,
    val beforePublishedCleanupMove: ((File) -> Unit)? = null,
)
