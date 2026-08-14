package dev.sixbridge.manager.conversion

import dev.sixbridge.manager.packager.AndroidKeystoreCrxSigningKey
import dev.sixbridge.manager.packager.AndroidSigningIdentity
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date
import javax.security.auth.x500.X500Principal
import kotlin.test.assertFailsWith
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
        val classified = ConversionKeyStoreException(IllegalStateException("classified"))
        boundaries.forEach { boundary ->
            assertSame(cancellation, boundary(cancellation))
            assertSame(classified, boundary(classified))
        }
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
    fun `CRX source stream I O 실패는 keystore가 아니라 package 오류다`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair()
        val signingKey = AndroidKeystoreCrxSigningKey(
            AndroidSigningIdentity(
                alias = "test",
                privateKey = keyPair.private,
                certificate = TestX509Certificate(keyPair.public),
            ),
        )
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
}
