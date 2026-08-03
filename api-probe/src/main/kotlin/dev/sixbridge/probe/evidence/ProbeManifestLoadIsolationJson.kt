package dev.sixbridge.probe.evidence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ProbeManifestLoadIsolationJson {
    private const val MAX_BYTES = 16_384
    private val json = Json { prettyPrint = false }
    private val fields = setOf(
        "browserApkSetSha256",
        "browserPackage",
        "browserSignerSha256",
        "browserVersionCode",
        "browserVersionName",
        "catalogSha256",
        "controlExtensionId",
        "controlManifestSha256",
        "controlOutcome",
        "controlSessionId",
        "excludedCaseId",
        "excludedPermission",
        "groupId",
        "schemaVersion",
        "variantEvidenceSha256",
        "variantExtensionId",
        "variantManifestSha256",
        "variantOutcome",
        "variantSessionId",
        "wrapperSignerSha256",
    )

    fun decodeStrict(value: String): ProbeManifestLoadIsolation {
        val canonicalInput = value.removeSuffix("\n")
        require(canonicalInput.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
            "manifest 로드 분리 증거는 16 KiB를 초과할 수 없습니다."
        }
        rejectDuplicateObjectKeys(canonicalInput)
        val root = try {
            json.parseToJsonElement(canonicalInput).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("manifest 로드 분리 증거 JSON을 읽을 수 없습니다.", error)
        }
        require(root.keys == fields) { "manifest 로드 분리 증거 필드가 정확하지 않습니다." }
        return ProbeManifestLoadIsolation(
            schemaVersion = root.integer("schemaVersion").toInt(),
            groupId = root.string("groupId"),
            catalogSha256 = root.string("catalogSha256"),
            excludedCaseId = root.string("excludedCaseId"),
            excludedPermission = root.string("excludedPermission"),
            controlSessionId = root.string("controlSessionId"),
            controlExtensionId = root.string("controlExtensionId"),
            controlManifestSha256 = root.string("controlManifestSha256"),
            controlOutcome = root.enumExact("controlOutcome"),
            variantSessionId = root.string("variantSessionId"),
            variantExtensionId = root.string("variantExtensionId"),
            variantManifestSha256 = root.string("variantManifestSha256"),
            variantEvidenceSha256 = root.string("variantEvidenceSha256"),
            variantOutcome = root.enumExact("variantOutcome"),
            wrapperSignerSha256 = root.string("wrapperSignerSha256"),
            browserPackage = root.string("browserPackage"),
            browserVersionName = root.string("browserVersionName"),
            browserVersionCode = root.integer("browserVersionCode"),
            browserApkSetSha256 = root.string("browserApkSetSha256"),
            browserSignerSha256 = root.string("browserSignerSha256"),
        ).also {
            require(encodeCanonical(it) == canonicalInput) {
                "manifest 로드 분리 증거는 canonical JSON이어야 합니다."
            }
        }
    }

    fun encodeCanonical(value: ProbeManifestLoadIsolation): String = json.encodeToString(
        JsonElement.serializer(),
        JsonObject(
            linkedMapOf(
                "browserApkSetSha256" to JsonPrimitive(value.browserApkSetSha256),
                "browserPackage" to JsonPrimitive(value.browserPackage),
                "browserSignerSha256" to JsonPrimitive(value.browserSignerSha256),
                "browserVersionCode" to JsonPrimitive(value.browserVersionCode),
                "browserVersionName" to JsonPrimitive(value.browserVersionName),
                "catalogSha256" to JsonPrimitive(value.catalogSha256),
                "controlExtensionId" to JsonPrimitive(value.controlExtensionId),
                "controlManifestSha256" to JsonPrimitive(value.controlManifestSha256),
                "controlOutcome" to JsonPrimitive(value.controlOutcome.name),
                "controlSessionId" to JsonPrimitive(value.controlSessionId),
                "excludedCaseId" to JsonPrimitive(value.excludedCaseId),
                "excludedPermission" to JsonPrimitive(value.excludedPermission),
                "groupId" to JsonPrimitive(value.groupId),
                "schemaVersion" to JsonPrimitive(value.schemaVersion),
                "variantEvidenceSha256" to JsonPrimitive(value.variantEvidenceSha256),
                "variantExtensionId" to JsonPrimitive(value.variantExtensionId),
                "variantManifestSha256" to JsonPrimitive(value.variantManifestSha256),
                "variantOutcome" to JsonPrimitive(value.variantOutcome.name),
                "variantSessionId" to JsonPrimitive(value.variantSessionId),
                "wrapperSignerSha256" to JsonPrimitive(value.wrapperSignerSha256),
            ),
        ),
    )

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.also {
        require(it.isString) { "$name 값은 문자열이어야 합니다." }
    }.content

    private fun JsonObject.integer(name: String): Long {
        val value = getValue(name).jsonPrimitive
        require(!value.isString && value.content.matches(Regex("0|[1-9][0-9]*"))) {
            "$name 값은 JSON 음이 아닌 정수여야 합니다."
        }
        return value.content.toLongOrNull()
            ?: throw IllegalArgumentException("$name 값이 Long 범위를 벗어났습니다.")
    }

    private inline fun <reified T : Enum<T>> JsonObject.enumExact(name: String): T {
        val raw = string(name)
        return enumValues<T>().singleOrNull { it.name == raw }
            ?: throw IllegalArgumentException("$name enum 값이 올바르지 않습니다.")
    }

    private fun rejectDuplicateObjectKeys(value: String) {
        val objectKeys = ArrayDeque<MutableSet<String>?>()
        var stringStart = -1
        var escaped = false
        value.forEachIndexed { index, ch ->
            if (stringStart >= 0) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') {
                    var next = index + 1
                    while (next < value.length && value[next].isWhitespace()) next += 1
                    if (next < value.length && value[next] == ':' && objectKeys.lastOrNull() != null) {
                        val key = json.parseToJsonElement(value.substring(stringStart, index + 1))
                            .jsonPrimitive.content
                        require(objectKeys.last()!!.add(key)) {
                            "manifest 로드 분리 증거 JSON field는 중복될 수 없습니다."
                        }
                    }
                    stringStart = -1
                }
            } else when (ch) {
                '"' -> stringStart = index
                '{' -> objectKeys.addLast(linkedSetOf())
                '[' -> objectKeys.addLast(null)
                '}', ']' -> require(objectKeys.removeLastOrNull() != null || ch == ']') {
                    "manifest 로드 분리 증거 JSON nesting이 올바르지 않습니다."
                }
            }
            require(objectKeys.size <= 16) {
                "manifest 로드 분리 증거 JSON nesting이 너무 깊습니다."
            }
        }
        require(stringStart == -1 && objectKeys.isEmpty()) {
            "manifest 로드 분리 증거 JSON 구조가 올바르지 않습니다."
        }
    }
}
