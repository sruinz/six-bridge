package dev.sixbridge.core.profile

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

data class VerifiedSignedBrowserProfile(
    val profile: BrowserProfile,
    val keyId: String,
    val profileSha256: String,
)

object SignedBrowserProfile {
    private const val SCHEMA_VERSION = 1
    private const val ALGORITHM = "Ed25519"
    private const val MAX_ENVELOPE_BYTES = 1_048_576
    private const val MAX_PROFILE_BYTES = 524_288
    private const val MAX_SIGNATURE_BYTES = 512
    private val sha256 = Regex("[0-9a-f]{64}")
    private val json = Json
    private val fields = setOf(
        "schemaVersion",
        "profileBase64",
        "profileSha256",
        "algorithm",
        "keyId",
        "signatureBase64",
    )

    fun sign(
        profileJson: ByteArray,
        keyId: String,
        privateKey: PrivateKey,
    ): String {
        require(profileJson.size <= MAX_PROFILE_BYTES) {
            "browser profile 원문 크기가 제한을 초과합니다."
        }
        require(keyId.isNotBlank()) { "profile signing key ID는 비어 있을 수 없습니다." }
        val profileSha256 = sha256(profileJson)
        val signature = Signature.getInstance(ALGORITHM).run {
            initSign(privateKey)
            update(payload(SCHEMA_VERSION, keyId, profileSha256, profileJson))
            sign()
        }
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                linkedMapOf(
                    "algorithm" to JsonPrimitive(ALGORITHM),
                    "keyId" to JsonPrimitive(keyId),
                    "profileBase64" to JsonPrimitive(Base64.getEncoder().encodeToString(profileJson)),
                    "profileSha256" to JsonPrimitive(profileSha256),
                    "schemaVersion" to JsonPrimitive(SCHEMA_VERSION),
                    "signatureBase64" to JsonPrimitive(Base64.getEncoder().encodeToString(signature)),
                ),
            ),
        )
    }

    fun verify(
        envelopeJson: String,
        trustedKeys: Map<String, PublicKey>,
    ): VerifiedSignedBrowserProfile {
        val envelopeBytes = envelopeJson.encodeToByteArray()
        require(envelopeBytes.size <= MAX_ENVELOPE_BYTES) {
            "browser profile envelope 크기가 제한을 초과합니다."
        }
        val envelope = parseStrictEnvelope(envelopeJson, envelopeBytes)
        val profileJson = envelope.base64("profileBase64")
        val signature = envelope.base64("signatureBase64")
        require(profileJson.size <= MAX_PROFILE_BYTES) {
            "browser profile 원문 크기가 제한을 초과합니다."
        }
        require(signature.size in 1..MAX_SIGNATURE_BYTES) {
            "browser profile 서명 크기가 올바르지 않습니다."
        }
        val profileSha256 = envelope.string("profileSha256")
        require(sha256.matches(profileSha256) && sha256(profileJson) == profileSha256) {
            "browser profile SHA-256이 일치하지 않습니다."
        }
        val keyId = envelope.string("keyId")
        val key = trustedKeys[keyId]
            ?: throw IllegalArgumentException("신뢰하지 않는 browser profile signing key입니다.")
        val schemaVersion = envelope.int("schemaVersion")
        require(schemaVersion == SCHEMA_VERSION) {
            "지원하지 않는 browser profile envelope schemaVersion입니다."
        }
        require(envelope.string("algorithm") == ALGORITHM) {
            "browser profile envelope algorithm은 Ed25519여야 합니다."
        }
        val signatureMatches = try {
            Signature.getInstance(ALGORITHM).run {
                initVerify(key)
                update(payload(schemaVersion, keyId, profileSha256, profileJson))
                verify(signature)
            }
        } catch (_: Exception) {
            false
        }
        require(signatureMatches) {
            "browser profile 서명이 일치하지 않습니다."
        }
        return VerifiedSignedBrowserProfile(
            profile = BrowserProfileJson.decode(profileJson.decodeToString()),
            keyId = keyId,
            profileSha256 = profileSha256,
        )
    }

    private fun parseStrictEnvelope(value: String, bytes: ByteArray): JsonObject {
        val parsed = try {
            json.parseToJsonElement(value) as? JsonObject
                ?: throw IllegalArgumentException("browser profile envelope는 JSON object여야 합니다.")
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("browser profile envelope JSON을 읽을 수 없습니다.", error)
        }
        require(parsed.keys == fields) {
            "browser profile envelope schema 항목이 정확하지 않습니다."
        }
        val canonical = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(parsed.toSortedMap()),
        ).encodeToByteArray()
        require(bytes.contentEquals(canonical)) {
            "browser profile envelope는 중복 키가 없고 canonical 형식이어야 합니다."
        }
        return parsed
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.also { require(it.isString) { "$name 값은 문자열이어야 합니다." } }
            .content.also { require(it.isNotBlank()) { "$name 값은 비어 있을 수 없습니다." } }

    private fun JsonObject.int(name: String): Int =
        getValue(name).jsonPrimitive.also {
            require(!it.isString && it.content.matches(Regex("0|[1-9][0-9]*"))) {
                "$name 값은 canonical JSON 정수여야 합니다."
            }
        }.content.toInt()

    private fun JsonObject.base64(name: String): ByteArray {
        val encoded = string(name)
        val decoded = try {
            Base64.getDecoder().decode(encoded)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("$name 값이 Base64가 아닙니다.", error)
        }
        require(Base64.getEncoder().encodeToString(decoded) == encoded) {
            "$name 값은 canonical Base64여야 합니다."
        }
        return decoded
    }

    private fun sha256(value: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

    private fun payload(
        schemaVersion: Int,
        keyId: String,
        profileSha256: String,
        profileJson: ByteArray,
    ): ByteArray = ByteArrayOutputStream().run {
        field("sixbridge-browser-profile-v1".encodeToByteArray())
        field(ByteBuffer.allocate(4).putInt(schemaVersion).array())
        field(keyId.encodeToByteArray())
        field(profileSha256.encodeToByteArray())
        field(profileJson)
        toByteArray()
    }

    private fun ByteArrayOutputStream.field(value: ByteArray) {
        write(ByteBuffer.allocate(4).putInt(value.size).array())
        write(value)
    }
}
