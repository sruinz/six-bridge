package dev.sixbridge.core.draft

import dev.sixbridge.core.crx.Crx3
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

internal object SixDraftJson {
    private val json = Json
    private val signatureKeys = setOf(
        "schemaVersion",
        "algorithm",
        "keyId",
        "publicKey",
        "signature",
    )
    private val expectedKeys = setOf(
        "schemaVersion",
        "sourceSha256",
        "reportSha256",
        "planSha256",
    )

    fun encodeSignature(value: DraftSignatureRecord): ByteArray =
        json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                linkedMapOf(
                    "schemaVersion" to JsonPrimitive(value.schemaVersion),
                    "algorithm" to JsonPrimitive(value.algorithm),
                    "keyId" to JsonPrimitive(value.keyId),
                    "publicKey" to JsonPrimitive(
                        Base64.getEncoder().encodeToString(value.publicKey),
                    ),
                    "signature" to JsonPrimitive(
                        Base64.getEncoder().encodeToString(value.signature),
                    ),
                ),
            ),
        ).toByteArray(Charsets.UTF_8)

    fun decodeSignature(bytes: ByteArray): DraftSignatureRecord {
        val value = parseObject(DraftSignature.ENTRY_NAME, bytes)
        require(value.keys == signatureKeys) {
            "draft-signature.json schema 항목이 정확하지 않습니다."
        }
        val schemaVersion = value.requiredInt("schemaVersion")
        require(schemaVersion == DraftSignature.SCHEMA_VERSION) {
            "지원하지 않는 draft signature schemaVersion입니다."
        }
        val algorithm = value.requiredString("algorithm")
        require(algorithm == DraftSignature.ALGORITHM) {
            "SixDraft 전달 서명 algorithm은 Ed25519여야 합니다."
        }
        val publicKey = value.requiredCanonicalBase64("publicKey")
        val signature = value.requiredCanonicalBase64("signature")
        return DraftSignatureRecord.create(
            schemaVersion = schemaVersion,
            algorithm = algorithm,
            keyId = value.requiredString("keyId"),
            publicKey = publicKey,
            signature = signature,
        )
    }

    fun validatePayload(entries: Map<String, ByteArray>, sourceName: String) {
        requireSchema("draft.json", entries.getValue("draft.json"), 1)
        requireSchema("input.json", entries.getValue("input.json"), 1)
        val plan = requireSchema(
            "conversion-plan.json",
            entries.getValue("conversion-plan.json"),
            1,
        )
        requireSchema(
            "compatibility-before.json",
            entries.getValue("compatibility-before.json"),
            3,
        )
        requireSchema(
            "compatibility-after.json",
            entries.getValue("compatibility-after.json"),
            3,
        )
        val expected = parseObject(
            "expected.json",
            entries.getValue("expected.json"),
        )
        require(expected.keys == expectedKeys) {
            "expected.json schema 항목이 정확하지 않습니다."
        }
        require(expected.requiredInt("schemaVersion") == 1) {
            "지원하지 않는 expected.json schemaVersion입니다."
        }
        val sourceSha256 = expected.requiredSha256("sourceSha256")
        val reportSha256 = expected.requiredSha256("reportSha256")
        val planSha256 = expected.requiredSha256("planSha256")
        val sourceProof = if (sourceName == "source.crx") {
            Crx3.parse(entries.getValue(sourceName)).zipBytes
        } else {
            entries.getValue(sourceName)
        }
        require(sourceSha256 == DraftSignature.sha256(sourceProof)) {
            "expected.json sourceSha256이 source entry와 일치하지 않습니다."
        }
        require(
            reportSha256 ==
                DraftSignature.sha256(entries.getValue("compatibility-after.json")),
        ) {
            "expected.json reportSha256이 변환 후 보고서와 일치하지 않습니다."
        }
        require(
            planSha256 == plan.requiredSha256("planSha256"),
        ) {
            "expected.json planSha256이 변환 계획과 일치하지 않습니다."
        }
    }

    private fun requireSchema(
        name: String,
        bytes: ByteArray,
        expected: Int,
    ): JsonObject {
        val value = parseObject(name, bytes)
        require(value.requiredInt("schemaVersion") == expected) {
            "지원하지 않는 $name schemaVersion입니다."
        }
        return value
    }

    private fun parseObject(name: String, bytes: ByteArray): JsonObject = try {
        json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
    } catch (error: Exception) {
        throw IllegalArgumentException("$name JSON을 읽을 수 없습니다.", error)
    }

    private fun JsonObject.requiredString(name: String): String =
        getValue(name).jsonPrimitive.also {
            require(it.isString) { "$name 값은 JSON 문자열이어야 합니다." }
        }.content

    private fun JsonObject.requiredInt(name: String): Int =
        getValue(name).jsonPrimitive.also {
            require(!it.isString) { "$name 값은 JSON 정수여야 합니다." }
        }.int

    private fun JsonObject.requiredSha256(name: String): String =
        requiredString(name).also {
            require(SHA256.matches(it)) {
                "$name 값은 lowercase SHA-256이어야 합니다."
            }
        }

    private fun JsonObject.requiredCanonicalBase64(name: String): ByteArray {
        val encoded = requiredString(name)
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

    private val SHA256 = Regex("[0-9a-f]{64}")
}
