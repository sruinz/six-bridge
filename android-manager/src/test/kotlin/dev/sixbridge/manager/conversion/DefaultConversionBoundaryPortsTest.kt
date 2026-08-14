package dev.sixbridge.manager.conversion

import dev.sixbridge.contract.bundle.ContractCompatibilityReport
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import dev.sixbridge.manager.packager.AndroidKeystoreCrxSigningKey
import dev.sixbridge.manager.packager.AndroidSigningIdentity
import dev.sixbridge.manager.packager.DefaultOnDevicePackagerKeyStoreAccess
import dev.sixbridge.manager.packager.OnDevicePackageIdentity
import dev.sixbridge.manager.packager.OnDevicePackagerKeyStoreSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.security.InvalidKeyException
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultConversionBoundaryPortsTest {
    @Test
    fun `각 경계는 같은 오류를 소유 domain type으로 감싼다`() {
        val identityCause = IllegalArgumentException("same failure")
        val keyStoreCause = IllegalArgumentException("same failure")
        val packageCause = IllegalArgumentException("same failure")
        val bundleCause = IllegalArgumentException("same failure")

        val identity = assertFailsWith<ConversionIdentityException> {
            withConversionIdentityBoundary<Unit> { throw identityCause }
        }
        val keyStore = assertFailsWith<ConversionKeyStoreException> {
            withConversionKeyStoreBoundary<Unit> { throw keyStoreCause }
        }
        val packageFailure = assertFailsWith<ConversionPackageException> {
            withConversionPackageBoundary<Unit> { throw packageCause }
        }
        val bundle = assertFailsWith<ConversionBundleException> {
            withConversionBundleBoundary<Unit> { throw bundleCause }
        }

        assertSame(identityCause, identity.cause)
        assertSame(keyStoreCause, keyStore.cause)
        assertSame(packageCause, packageFailure.cause)
        assertSame(bundleCause, bundle.cause)
    }

    @Test
    fun `경계는 cancellation과 이미 분류된 오류를 재래핑하지 않는다`() {
        val boundaries = listOf<(Throwable) -> Throwable>(
            { failure ->
                assertFailsWith<Throwable> {
                    withConversionIdentityBoundary<Unit> { throw failure }
                }
            },
            { failure ->
                assertFailsWith<Throwable> {
                    withConversionKeyStoreBoundary<Unit> { throw failure }
                }
            },
            { failure ->
                assertFailsWith<Throwable> {
                    withConversionPackageBoundary<Unit> { throw failure }
                }
            },
            { failure ->
                assertFailsWith<Throwable> {
                    withConversionBundleBoundary<Unit> { throw failure }
                }
            },
        )

        val cancellation = CancellationException("cancelled")
        val classified = listOf(
            ConversionIdentityException(IllegalStateException("identity classified")),
            ConversionKeyStoreException(IllegalStateException("keystore classified")),
            ConversionPackageException(IllegalStateException("package classified")),
            ConversionBundleException(IllegalStateException("bundle classified")),
        )
        boundaries.forEach { boundary ->
            assertSame(cancellation, boundary(cancellation))
            classified.forEach { failure ->
                assertSame(failure, boundary(failure))
            }
        }
    }

    @Test
    fun `actual default ports는 raw 실패를 소유 domain type으로 감싼다`() = runTest {
        val profile = profile()
        val prepared = prepared(profile)
        val resolved = resolved(profile)
        val reserved = ReservedPackageIdentity(resolved, versionCode = 1, opaque = Unit)
        val identityResolveCause = IllegalArgumentException("identity resolve raw")
        val identityReserveCause = IllegalArgumentException("identity reserve raw")
        val identityPort = DefaultConversionIdentityPort(
            object : ConversionIdentityPort {
                override suspend fun existingTargets(profileMajorVersion: Int) =
                    emptyList<ExistingConversionTarget>()

                override suspend fun resolve(
                    prepared: PreparedConversionSnapshot,
                    profile: BrowserProfile,
                    selection: InstallationIdentitySelection?,
                    ownerToken: String,
                ): ResolvedPackageIdentity = throw identityResolveCause

                override suspend fun reserve(
                    resolved: ResolvedPackageIdentity,
                    displayName: String,
                    installedVersionCode: Long?,
                    ownerToken: String,
                ): ReservedPackageIdentity = throw identityReserveCause

                override suspend fun releaseFailedAttempt(ownerToken: String) = Unit
            },
        )

        val resolveFailure = assertFailsWith<ConversionIdentityException> {
            identityPort.resolve(prepared, profile, null, OWNER_TOKEN)
        }
        val reserveFailure = assertFailsWith<ConversionIdentityException> {
            identityPort.reserve(resolved, "Example", null, OWNER_TOKEN)
        }

        assertSame(identityResolveCause, resolveFailure.cause)
        assertSame(identityReserveCause, reserveFailure.cause)

        val packageCause = IllegalArgumentException("package raw")
        val packagePort = DefaultConversionPackagePort(
            object : ConversionPackagePort {
                override suspend fun packageWrapper(
                    reservation: ReservedPackageIdentity,
                    bound: BoundConversionSnapshot,
                    token: String,
                ): PackagedConversion = throw packageCause
            },
        )
        val packageFailure = assertFailsWith<ConversionPackageException> {
            packagePort.packageWrapper(reserved, bound(profile), OWNER_TOKEN)
        }
        assertSame(packageCause, packageFailure.cause)

        val bundleCause = IllegalArgumentException("bundle raw")
        val bundlePort = DefaultConversionBundlePort(
            object : ConversionBundlePort {
                override fun publish(
                    token: String,
                    packaged: PackagedConversion,
                ): GeneratedBundle = throw bundleCause

                override fun recover(token: String): GeneratedBundle? = null
                override fun discard(token: String) = false
                override fun copyTo(token: String, output: java.io.OutputStream) = ""
            },
        )
        val bundleFailure = assertFailsWith<ConversionBundleException> {
            bundlePort.publish(OWNER_TOKEN, packaged())
        }
        assertSame(bundleCause, bundleFailure.cause)
    }

    @Test
    fun `generated import delegate 실패는 bundle 오류로 분류한다`() = runTest {
        val cause = IllegalStateException("import failed")
        val generated = GeneratedBundle(
            token = "11111111-1111-4111-8111-111111111111",
            file = File("ignored.six"),
            sha256 = "a".repeat(64),
            suggestedFileName = "ignored.six",
        )
        val port = DefaultConversionGeneratedImportPort(
            ConversionGeneratedImportPort { throw cause },
        )

        val failure = assertFailsWith<ConversionBundleException> {
            port.importGenerated(generated)
        }

        assertSame(cause, failure.cause)
    }

    @Test
    fun `CRX source stream read 실패는 keystore가 아니라 package 오류다`() {
        val signingKey = signingKey()
        val cause = IOException("source read failed")
        val source = object : InputStream() {
            override fun read(): Int = throw cause
        }

        val failure = assertFailsWith<ConversionPackageException> {
            withConversionPackageBoundary {
                signingKey.signSha256Rsa(source)
            }
        }

        assertSame(cause, failure.cause)
    }

    @Test
    fun `CRX source stream close 실패는 keystore가 아니라 package 오류다`() {
        val signingKey = signingKey()
        val cause = IOException("source close failed")
        val source = object : InputStream() {
            override fun read(): Int = -1
            override fun close() = throw cause
        }

        val failure = assertFailsWith<ConversionPackageException> {
            withConversionPackageBoundary {
                signingKey.signSha256Rsa(source)
            }
        }

        assertSame(cause, failure.cause)
    }

    @Test
    fun `actual JCA initSign 실패는 package 경계에서도 keystore 오류다`() = runTest {
        val signingKey = AndroidKeystoreCrxSigningKey(
            privateKey = incompatiblePrivateKey(),
            publicKeyDer = byteArrayOf(),
        )
        val operations = listOf<Pair<String, () -> Unit>>(
            "byte array" to {
                signingKey.signSha256Rsa("payload".encodeToByteArray())
            },
            "input stream" to {
                signingKey.signSha256Rsa(
                    ByteArrayInputStream("payload".encodeToByteArray()),
                )
            },
        )

        operations.forEach { (name, operation) ->
            val packagePort = DefaultConversionPackagePort(
                object : ConversionPackagePort {
                    override suspend fun packageWrapper(
                        reservation: ReservedPackageIdentity,
                        bound: BoundConversionSnapshot,
                        token: String,
                    ): PackagedConversion {
                        operation()
                        error("unreachable")
                    }
                },
            )
            val failure = assertFailsWith<ConversionKeyStoreException>(name) {
                packagePort.packageWrapper(
                    ReservedPackageIdentity(resolved(profile()), versionCode = 1, opaque = Unit),
                    bound(profile()),
                    OWNER_TOKEN,
                )
            }

            assertIs<InvalidKeyException>(failure.cause, name)
        }
    }

    @Test
    fun `actual packager keystore access는 raw key 생성 조회 proof 실패를 분류한다`() {
        data class Case(
            val name: String,
            val cause: Throwable,
            val operation: () -> Unit,
        )

        val aliasCause = IllegalStateException("alias failed")
        val wrapperCreateCause = IllegalStateException("wrapper create failed")
        val wrapperLookupCause = IllegalStateException("wrapper lookup failed")
        val missingCrxCause = IllegalStateException("missing CRX alias")
        val fallbackMissingCause = IllegalStateException("fallback CRX alias missing")
        val corruptProofCause = IllegalStateException("corrupt public-key proof")
        val createCrxCause = IllegalStateException("CRX create failed")
        val signingKeyCause = IllegalStateException("CRX signing key missing")
        val wrapperSigningCause = IllegalStateException("wrapper signing key missing")
        val corruptIdentity = AndroidSigningIdentity(
            alias = "corrupt",
            privateKey = incompatiblePrivateKey(),
            certificate = TestX509Certificate(
                object : PublicKey {
                    override fun getAlgorithm() = "RSA"
                    override fun getFormat() = "X.509"
                    override fun getEncoded(): ByteArray = throw corruptProofCause
                },
            ),
        )
        val cases = listOf(
            Case("planned alias", aliasCause) {
                keyStoreAccess(crxAlias = { throw aliasCause }).plannedCrxAlias(IDENTITY_SHA)
            },
            Case("wrapper create", wrapperCreateCause) {
                keyStoreAccess(wrapperIdentity = { throw wrapperCreateCause })
                    .wrapperSignerSha256()
            },
            Case("wrapper lookup", wrapperLookupCause) {
                keyStoreAccess(existingWrapperIdentity = { throw wrapperLookupCause })
                    .existingWrapperSignerSha256()
            },
            Case("missing CRX alias", missingCrxCause) {
                keyStoreAccess(existingCrxIdentity = { throw missingCrxCause })
                    .existingCrxIdentityProof(IDENTITY_SHA)
            },
            Case("corrupt public-key proof", corruptProofCause) {
                keyStoreAccess(existingCrxIdentity = { corruptIdentity })
                    .existingCrxIdentityProof(IDENTITY_SHA)
            },
            Case("fallback missing CRX alias", fallbackMissingCause) {
                keyStoreAccess(
                    createCrxIdentityIfAbsent = { null },
                    existingCrxIdentity = { throw fallbackMissingCause },
                ).createOrExistingCrxIdentityProof(IDENTITY_SHA)
            },
            Case("CRX create", createCrxCause) {
                keyStoreAccess(createCrxIdentityIfAbsent = { throw createCrxCause })
                    .createOrExistingCrxIdentityProof(IDENTITY_SHA)
            },
            Case("CRX signing key lookup", signingKeyCause) {
                keyStoreAccess(existingCrxSigningKey = { throw signingKeyCause })
                    .existingCrxSigningKey(IDENTITY_SHA)
            },
            Case("wrapper signing key lookup", wrapperSigningCause) {
                keyStoreAccess(wrapperIdentity = { throw wrapperSigningCause })
                    .wrapperSigningIdentity()
            },
        )

        cases.forEach { case ->
            val failure = assertFailsWith<ConversionKeyStoreException>(case.name) {
                case.operation()
            }
            assertSame(case.cause, failure.cause, case.name)
        }
    }

    @Test
    fun `actual packager keystore access는 cancellation과 기분류 오류를 재래핑하지 않는다`() {
        val failures = listOf(
            CancellationException("cancelled"),
            ConversionIdentityException(IllegalStateException("identity")),
            ConversionKeyStoreException(IllegalStateException("keystore")),
            ConversionPackageException(IllegalStateException("package")),
            ConversionBundleException(IllegalStateException("bundle")),
        )

        failures.forEach { failure ->
            val access = keyStoreAccess(existingCrxIdentity = { throw failure })
            val thrown = assertFailsWith<Throwable> {
                access.existingCrxIdentityProof(IDENTITY_SHA)
            }
            assertSame(failure, thrown)
        }
    }

    private fun signingKey(): AndroidKeystoreCrxSigningKey {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair()
        return AndroidKeystoreCrxSigningKey(
            AndroidSigningIdentity(
                alias = "test",
                privateKey = keyPair.private,
                certificate = TestX509Certificate(keyPair.public),
            ),
        )
    }

    private fun incompatiblePrivateKey() = object : PrivateKey {
        override fun getAlgorithm() = "EC"
        override fun getFormat() = "PKCS#8"
        override fun getEncoded() = byteArrayOf()
    }

    private fun keyStoreAccess(
        crxAlias: (String) -> String = { error("unexpected crxAlias") },
        wrapperIdentity: () -> AndroidSigningIdentity = { error("unexpected wrapperIdentity") },
        existingWrapperIdentity: () -> AndroidSigningIdentity = {
            error("unexpected existingWrapperIdentity")
        },
        createCrxIdentityIfAbsent: (String) -> AndroidSigningIdentity? = {
            error("unexpected createCrxIdentityIfAbsent")
        },
        existingCrxIdentity: (String) -> AndroidSigningIdentity = {
            error("unexpected existingCrxIdentity")
        },
        existingCrxSigningKey: (String) -> AndroidKeystoreCrxSigningKey = {
            error("unexpected existingCrxSigningKey")
        },
    ) = DefaultOnDevicePackagerKeyStoreAccess(
        object : OnDevicePackagerKeyStoreSource {
            override fun crxAlias(identitySha256: String) = crxAlias(identitySha256)
            override fun wrapperIdentity() = wrapperIdentity()
            override fun existingWrapperIdentity() = existingWrapperIdentity()
            override fun createCrxIdentityIfAbsent(identitySha256: String) =
                createCrxIdentityIfAbsent(identitySha256)
            override fun existingCrxIdentity(identitySha256: String) =
                existingCrxIdentity(identitySha256)
            override fun existingCrxSigningKey(identitySha256: String) =
                existingCrxSigningKey(identitySha256)
        },
    )

    private fun profile() = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1,
        majorVersion = 30,
        supportedApis = emptySet(),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        installationApkSetSha256 = "f".repeat(64),
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    )

    private fun prepared(profile: BrowserProfile) = PreparedConversionSnapshot(
        reportBefore = CompatibilityReport(
            status = CompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = "Example",
            extensionVersion = "1.0",
            declaredPermissions = emptySet(),
            usedApis = emptySet(),
            unsupportedApis = emptySet(),
            issues = emptyList(),
            conversionState = ConversionState.REQUIRES_APPROVAL,
            sourceSha256 = "4".repeat(64),
            browserProfileSha256 = profile.canonicalSha256(),
        ),
        originalSourceSha256 = "4".repeat(64),
        planSha256 = "5".repeat(64),
        transformedSourceZip = "zip".encodeToByteArray(),
        passthroughCrx = "crx".encodeToByteArray(),
        logicalSourceIdentity = LogicalSourceIdentity.storeExtensionId(EXTENSION_ID),
        identityMode = InstallationIdentityMode.PASSTHROUGH,
        descriptorName = "Example",
        descriptorVersion = "1.0",
        sourceKind = "CRX3",
        opaque = Unit,
    )

    private fun resolved(profile: BrowserProfile) = ResolvedPackageIdentity(
        key = InstallationIdentityKey.create(
            LogicalSourceIdentity.storeExtensionId(EXTENSION_ID),
            profile.majorVersion,
            InstallationIdentityMode.PASSTHROUGH,
        ),
        identity = OnDevicePackageIdentity(
            extensionId = EXTENSION_ID,
            packageName = PACKAGE,
            wrapperSignerSha256 = "a".repeat(64),
            crxKeyAlias = null,
            crxPublicKeyDer = null,
        ),
        unreservedLease = null,
    )

    private fun bound(profile: BrowserProfile) = BoundConversionSnapshot(
        sourceZip = "zip".encodeToByteArray(),
        passthroughCrx = "crx".encodeToByteArray(),
        compatibility = ContractCompatibilityReport(
            schemaVersion = 4,
            status = ContractCompatibilityStatus.PARTIAL,
            manifestVersion = 3,
            extensionName = "Example",
            extensionVersion = "1.0",
            manifestKeyPresent = false,
            manifestKeyExtensionId = null,
            declaredPermissions = emptyList(),
            usedApis = emptyList(),
            unsupportedApis = emptyList(),
            issues = emptyList(),
            conversionState = "passthrough",
            adapterDecisions = emptyList(),
            originalExtensionId = EXTENSION_ID,
            convertedExtensionId = EXTENSION_ID,
            sourceSha256 = "4".repeat(64),
            browserProfileSha256 = profile.canonicalSha256(),
            originalSourceSha256 = "4".repeat(64),
            planSha256 = "5".repeat(64),
        ),
        extensionId = EXTENSION_ID,
        sourceKind = "CRX3",
    )

    private fun packaged() = PackagedConversion(
        extensionId = EXTENSION_ID,
        packageName = PACKAGE,
        versionCode = 1,
        wrapperSha256 = "b".repeat(64),
        outputApk = File("ignored.apk"),
        opaque = Unit,
    )

    @Suppress("DEPRECATION")
    private class TestX509Certificate(
        private val key: PublicKey,
    ) : X509Certificate() {
        private val principal = X500Principal("CN=Test")

        override fun checkValidity() = Unit
        override fun checkValidity(date: Date?) = Unit
        override fun getVersion() = 3
        override fun getSerialNumber() = BigInteger.ONE
        override fun getIssuerDN(): Principal = principal
        override fun getSubjectDN(): Principal = principal
        override fun getNotBefore() = Date(0)
        override fun getNotAfter() = Date(Long.MAX_VALUE)
        override fun getTBSCertificate() = byteArrayOf()
        override fun getSignature() = byteArrayOf()
        override fun getSigAlgName() = "SHA256withRSA"
        override fun getSigAlgOID() = "1.2.840.113549.1.1.11"
        override fun getSigAlgParams(): ByteArray? = null
        override fun getIssuerUniqueID(): BooleanArray? = null
        override fun getSubjectUniqueID(): BooleanArray? = null
        override fun getKeyUsage(): BooleanArray? = null
        override fun getBasicConstraints() = -1
        override fun getEncoded() = byteArrayOf()
        override fun verify(key: PublicKey?) = Unit
        override fun verify(key: PublicKey?, sigProvider: String?) = Unit
        override fun toString() = "TestX509Certificate"
        override fun getPublicKey(): PublicKey = key
        override fun hasUnsupportedCriticalExtension() = false
        override fun getCriticalExtensionOIDs(): Set<String>? = null
        override fun getNonCriticalExtensionOIDs(): Set<String>? = null
        override fun getExtensionValue(oid: String?): ByteArray? = null
    }

    private companion object {
        const val OWNER_TOKEN = "11111111-1111-4111-8111-111111111111"
        val IDENTITY_SHA = "1".repeat(64)
        val EXTENSION_ID = "a".repeat(32)
        val PACKAGE = "dev.sixbridge.ext.$EXTENSION_ID"
    }
}
