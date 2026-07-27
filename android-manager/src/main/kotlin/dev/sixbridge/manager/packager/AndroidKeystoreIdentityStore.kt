package dev.sixbridge.manager.packager

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyFactory
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
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
        val entry = keyStore().getEntry(alias, null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Android Keystore alias가 PrivateKeyEntry가 아닙니다: $alias")
        val certificate = entry.certificate as? X509Certificate
            ?: throw IllegalStateException("Android Keystore alias 인증서 형식이 올바르지 않습니다: $alias")
        validateExistingIdentity(alias, entry.privateKey, certificate)
        AndroidSigningIdentity(alias, entry.privateKey, certificate)
    }

    private fun validateExistingIdentity(alias: String, privateKey: PrivateKey, certificate: X509Certificate) {
        requirePolicy(privateKey.algorithm == KeyProperties.KEY_ALGORITHM_RSA, alias, "개인 키가 RSA가 아닙니다")
        requirePolicy(certificate.publicKey.algorithm == KeyProperties.KEY_ALGORITHM_RSA, alias, "인증서 공개 키가 RSA가 아닙니다")
        requirePolicy(certificate.publicKey is RSAPublicKey, alias, "인증서 공개 키 RSA 형식이 올바르지 않습니다")
        requirePolicy((certificate.publicKey as RSAPublicKey).modulus.bitLength() == KEY_SIZE_BITS, alias, "RSA 공개 키 길이가 올바르지 않습니다")
        requirePolicy(certificate.subjectX500Principal == CERTIFICATE_SUBJECT, alias, "인증서 subject가 올바르지 않습니다")
        requirePolicy(certificate.serialNumber == certificateSerial(alias), alias, "인증서 serial이 올바르지 않습니다")
        requirePolicy(certificate.serialNumber.signum() > 0, alias, "인증서 serial이 양수가 아닙니다")
        requirePolicy(certificate.notBefore == Date.from(CERTIFICATE_NOT_BEFORE), alias, "인증서 notBefore가 올바르지 않습니다")
        requirePolicy(certificate.notAfter == Date.from(CERTIFICATE_NOT_AFTER), alias, "인증서 notAfter가 올바르지 않습니다")

        val keyInfo = KeyFactory.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEYSTORE)
            .getKeySpec(privateKey, KeyInfo::class.java)
        requirePolicy(keyInfo.keystoreAlias == alias, alias, "KeyInfo alias가 올바르지 않습니다")
        requirePolicy(keyInfo.keySize == KEY_SIZE_BITS, alias, "KeyInfo keySize가 올바르지 않습니다")
        requirePolicy(keyInfo.purposes == REQUIRED_PURPOSES, alias, "KeyInfo purposes가 올바르지 않습니다")
        requirePolicy(keyInfo.digests.toSet() == REQUIRED_DIGESTS, alias, "KeyInfo digests가 올바르지 않습니다")
        requirePolicy(keyInfo.signaturePaddings.toSet() == REQUIRED_SIGNATURE_PADDINGS, alias, "KeyInfo signature paddings가 올바르지 않습니다")
    }

    private fun requirePolicy(condition: Boolean, alias: String, problem: String) {
        if (!condition) throw IllegalStateException("Android Keystore alias 정책 위반: $alias ($problem)")
    }

    private fun keyGenerationSpec(alias: String): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setKeySize(KEY_SIZE_BITS)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(
                KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
                KeyProperties.SIGNATURE_PADDING_RSA_PSS,
            )
            .setCertificateSubject(CERTIFICATE_SUBJECT)
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
        private const val KEY_SIZE_BITS = 2048
        private const val REQUIRED_PURPOSES = KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        private val TEST_NAMESPACE = Regex("sixbridge\\.test\\.[0-9a-f-]+")
        private val REQUIRED_DIGESTS = setOf(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
        private val REQUIRED_SIGNATURE_PADDINGS = setOf(
            KeyProperties.SIGNATURE_PADDING_RSA_PKCS1,
            KeyProperties.SIGNATURE_PADDING_RSA_PSS,
        )
        private val CERTIFICATE_SUBJECT = X500Principal("CN=SIX Bridge On-Device")
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
