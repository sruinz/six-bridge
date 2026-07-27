package dev.sixbridge.manager.packager

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Date
import javax.security.auth.x500.X500Principal

data class AndroidSigningIdentity(
    val alias: String,
    val privateKey: PrivateKey,
    val certificate: X509Certificate,
)

class AndroidKeystoreIdentityStore private constructor(
    private val aliases: AliasNamespace,
) {
    constructor() : this(AliasNamespace.production())

    internal constructor(testNamespace: String) : this(AliasNamespace.test(testNamespace))

    fun wrapperIdentity(): AndroidSigningIdentity = identity(aliases.wrapperAlias)

    fun crxIdentity(sourceIdentity: ByteArray): AndroidSigningIdentity =
        identity(aliases.crxAlias(sourceIdentity.copyOf()))

    fun crxSigningKey(sourceIdentity: ByteArray): AndroidKeystoreCrxSigningKey =
        AndroidKeystoreCrxSigningKey(crxIdentity(sourceIdentity))

    private fun identity(alias: String): AndroidSigningIdentity = synchronized(lock) {
        val keyStore = keyStore()
        if (!keyStore.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE).apply {
                initialize(keyGenerationSpec(alias))
                generateKeyPair()
            }
        }
        val entry = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Android Keystore 서명 키를 읽을 수 없습니다.")
        val certificate = entry.certificate as? X509Certificate
            ?: throw IllegalStateException("Android Keystore 인증서 형식이 올바르지 않습니다.")
        AndroidSigningIdentity(alias, entry.privateKey, certificate)
    }

    private fun keyGenerationSpec(alias: String): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setKeySize(2048)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS,
            )
            .setCertificateSubject(X500Principal("CN=SIX Bridge On-Device"))
            .setCertificateSerialNumber(certificateSerial(alias))
            .setCertificateNotBefore(Date.from(CERTIFICATE_NOT_BEFORE))
            .setCertificateNotAfter(Date.from(CERTIFICATE_NOT_AFTER))
            .build()

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun certificateSerial(alias: String): BigInteger =
        BigInteger(1, sha256(alias.encodeToByteArray()).copyOfRange(0, 16)).max(BigInteger.ONE)

    private data class AliasNamespace(
        val wrapperAlias: String,
        val crxPrefix: String,
    ) {
        fun crxAlias(sourceIdentity: ByteArray): String =
            crxPrefix + sha256(sourceIdentity).joinToString("") { "%02x".format(it) }

        companion object {
            fun production(): AliasNamespace = AliasNamespace(
                wrapperAlias = WRAPPER_ALIAS,
                crxPrefix = CRX_ALIAS_PREFIX,
            )

            fun test(namespace: String): AliasNamespace {
                require(TEST_NAMESPACE.matches(namespace)) { "테스트 Keystore namespace 형식이 올바르지 않습니다." }
                return AliasNamespace(
                    wrapperAlias = "$namespace.wrapper.rsa.v1",
                    crxPrefix = "$namespace.crx.rsa.v1.",
                )
            }
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val WRAPPER_ALIAS = "sixbridge.wrapper.rsa.v1"
        private const val CRX_ALIAS_PREFIX = "sixbridge.crx.rsa.v1."
        private val TEST_NAMESPACE = Regex("sixbridge\\.test\\.[0-9a-f-]+")
        private val CERTIFICATE_NOT_BEFORE: Instant = Instant.parse("2025-01-01T00:00:00Z")
        private val CERTIFICATE_NOT_AFTER: Instant = Instant.parse("2050-01-01T00:00:00Z")
        private val lock = Any()

        internal fun deleteTestAliases(namespace: String) {
            require(TEST_NAMESPACE.matches(namespace)) { "테스트 Keystore namespace 형식이 올바르지 않습니다." }
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val matchingAliases = keyStore.aliases().asSequence().filter { it.startsWith("$namespace.") }.toList()
            matchingAliases.forEach(keyStore::deleteEntry)
        }
    }
}

private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
