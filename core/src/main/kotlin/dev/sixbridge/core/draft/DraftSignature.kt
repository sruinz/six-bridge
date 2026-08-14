package dev.sixbridge.core.draft

import com.google.crypto.tink.subtle.Ed25519Verify
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature

class DraftSignatureRecord private constructor(
    val schemaVersion: Int,
    val algorithm: String,
    val keyId: String,
    publicKey: ByteArray,
    signature: ByteArray,
) {
    private val stablePublicKey = publicKey.copyOf()
    private val stableSignature = signature.copyOf()

    val publicKey: ByteArray
        get() = stablePublicKey.copyOf()

    val signature: ByteArray
        get() = stableSignature.copyOf()

    companion object {
        @JvmSynthetic
        internal fun create(
            schemaVersion: Int,
            algorithm: String,
            keyId: String,
            publicKey: ByteArray,
            signature: ByteArray,
        ): DraftSignatureRecord = DraftSignatureRecord(
            schemaVersion,
            algorithm,
            keyId,
            publicKey,
            signature,
        )
    }
}

internal data class DraftEntryDigest(
    val size: Long,
    val sha256: String,
)

object DraftSignature {
    const val ENTRY_NAME = "draft-signature.json"
    const val ALGORITHM = "Ed25519"
    const val SCHEMA_VERSION = 1

    fun canonicalPayload(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        entries.entries
            .asSequence()
            .filter { it.key != ENTRY_NAME }
            .sortedBy(Map.Entry<String, ByteArray>::key)
            .forEach { (name, bytes) ->
                require(isCanonicalEntryName(name)) {
                    "서명 대상 entry 이름이 canonical하지 않습니다: $name"
                }
                output.write(
                    "$name\t${bytes.size}\t${sha256(bytes)}\n"
                        .toByteArray(Charsets.UTF_8),
                )
            }
        return output.toByteArray()
    }

    fun sign(
        entries: Map<String, ByteArray>,
        keyPair: KeyPair,
    ): DraftSignatureRecord {
        validateEd25519PublicKey(keyPair.public.encoded)
        val proof = "sixbridge-draft-key-pair-proof".toByteArray(Charsets.UTF_8)
        val proofSignature = Signature.getInstance(ALGORITHM).run {
            initSign(keyPair.private)
            update(proof)
            sign()
        }
        require(
            Signature.getInstance(ALGORITHM).run {
                initVerify(keyPair.public)
                update(proof)
                verify(proofSignature)
            },
        ) {
            "SixDraft 공개 키와 개인 키가 서로 대응하지 않습니다."
        }
        val signature = Signature.getInstance(ALGORITHM).run {
            initSign(keyPair.private)
            update(canonicalPayload(entries))
            sign()
        }
        return DraftSignatureRecord.create(
            schemaVersion = SCHEMA_VERSION,
            algorithm = ALGORITHM,
            keyId = fingerprint(keyPair.public.encoded),
            publicKey = keyPair.public.encoded,
            signature = signature,
        )
    }

    fun verify(
        entries: Map<String, ByteArray>,
        record: DraftSignatureRecord,
    ): Boolean {
        if (record.schemaVersion != SCHEMA_VERSION ||
            record.algorithm != ALGORITHM ||
            !SHA256.matches(record.keyId)
        ) {
            return false
        }
        val publicKeyBytes = record.publicKey
        if (record.keyId != sha256(publicKeyBytes)) return false
        val rawPublicKey = try {
            rawEd25519PublicKey(publicKeyBytes)
        } catch (_: Exception) {
            return false
        }
        return verifyEd25519Signature(
            rawPublicKey,
            canonicalPayload(entries),
            record.signature,
        )
    }

    internal fun verifyDigests(
        entries: Map<String, DraftEntryDigest>,
        record: DraftSignatureRecord,
    ): Boolean {
        if (record.schemaVersion != SCHEMA_VERSION ||
            record.algorithm != ALGORITHM ||
            !SHA256.matches(record.keyId)
        ) {
            return false
        }
        val publicKeyBytes = record.publicKey
        if (record.keyId != sha256(publicKeyBytes)) return false
        val rawPublicKey = try {
            rawEd25519PublicKey(publicKeyBytes)
        } catch (_: Exception) {
            return false
        }
        return verifyEd25519Signature(
            rawPublicKey,
            canonicalDigestPayload(entries),
            record.signature,
        )
    }

    fun fingerprint(publicKey: ByteArray): String {
        validateEd25519PublicKey(publicKey)
        return sha256(publicKey)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun validateEd25519PublicKey(publicKey: ByteArray) {
        try {
            rawEd25519PublicKey(publicKey)
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "SixDraft signer 공개 키가 Ed25519 X.509 형식이 아닙니다.",
                error,
            )
        }
    }

    private fun rawEd25519PublicKey(encoded: ByteArray): ByteArray {
        require(
            encoded.size == ED25519_X509_PREFIX.size + ED25519_PUBLIC_KEY_SIZE &&
                encoded.copyOfRange(0, ED25519_X509_PREFIX.size)
                    .contentEquals(ED25519_X509_PREFIX),
        ) {
            "공개 키의 X.509 AlgorithmIdentifier가 Ed25519가 아닙니다."
        }
        return encoded.copyOfRange(ED25519_X509_PREFIX.size, encoded.size)
    }

    private fun verifyEd25519Signature(
        rawPublicKey: ByteArray,
        payload: ByteArray,
        signature: ByteArray,
    ): Boolean = try {
        Ed25519Verify(rawPublicKey).verify(signature, payload)
        true
    } catch (_: Exception) {
        false
    }

    private fun isCanonicalEntryName(name: String): Boolean =
        name.isNotEmpty() &&
            '/' !in name &&
            '\\' !in name &&
            '\u0000' !in name &&
            name != "." &&
            name != ".."

    private fun canonicalDigestPayload(
        entries: Map<String, DraftEntryDigest>,
    ): ByteArray = ByteArrayOutputStream().run {
        entries.entries
            .asSequence()
            .filter { it.key != ENTRY_NAME }
            .sortedBy(Map.Entry<String, DraftEntryDigest>::key)
            .forEach { (name, digest) ->
                require(isCanonicalEntryName(name)) {
                    "서명 대상 entry 이름이 canonical하지 않습니다: $name"
                }
                require(digest.size >= 0L && SHA256.matches(digest.sha256)) {
                    "서명 대상 entry digest가 올바르지 않습니다: $name"
                }
                write(
                    "$name\t${digest.size}\t${digest.sha256}\n"
                        .toByteArray(Charsets.UTF_8),
                )
            }
        toByteArray()
    }

    private val SHA256 = Regex("[0-9a-f]{64}")
    private val ED25519_X509_PREFIX = byteArrayOf(
        0x30,
        0x2a,
        0x30,
        0x05,
        0x06,
        0x03,
        0x2b,
        0x65,
        0x70,
        0x03,
        0x21,
        0x00,
    )
    private const val ED25519_PUBLIC_KEY_SIZE = 32
}
