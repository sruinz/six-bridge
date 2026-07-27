package dev.sixbridge.core.draft

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

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
        if (record.keyId != fingerprint(publicKeyBytes)) return false
        val publicKey = try {
            KeyFactory.getInstance(ALGORITHM)
                .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        } catch (_: Exception) {
            return false
        }
        return try {
            Signature.getInstance(ALGORITHM).run {
                initVerify(publicKey)
                update(canonicalPayload(entries))
                verify(record.signature)
            }
        } catch (_: Exception) {
            false
        }
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
        if (record.keyId != fingerprint(publicKeyBytes)) return false
        val publicKey = try {
            KeyFactory.getInstance(ALGORITHM)
                .generatePublic(X509EncodedKeySpec(publicKeyBytes))
        } catch (_: Exception) {
            return false
        }
        return try {
            Signature.getInstance(ALGORITHM).run {
                initVerify(publicKey)
                update(canonicalDigestPayload(entries))
                verify(record.signature)
            }
        } catch (_: Exception) {
            false
        }
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
            KeyFactory.getInstance(ALGORITHM)
                .generatePublic(X509EncodedKeySpec(publicKey))
        } catch (error: Exception) {
            throw IllegalArgumentException(
                "SixDraft signer 공개 키가 Ed25519 X.509 형식이 아닙니다.",
                error,
            )
        }
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
}
