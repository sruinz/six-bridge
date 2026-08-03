package dev.sixbridge.probe.evidence

import dev.sixbridge.core.profile.ApiExecutionContext
import dev.sixbridge.probe.catalog.ApiProbeCatalog
import java.math.BigDecimal
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ProbeEvidenceJson {
    private const val SCHEMA_VERSION = 1
    private const val MAX_JSON_BYTES = 1_048_576
    private const val MAX_JSON_DEPTH = 64
    private const val MAX_RESULTS = 512
    private const val MAX_DETAIL_BYTES = 2_048
    private const val MAX_DETAIL_DEPTH = 6
    private const val MAX_DETAIL_VALUES = 64
    private val json = Json { prettyPrint = false }
    private val rootFields = setOf(
        "schemaVersion",
        "sessionId",
        "groupId",
        "catalogSha256",
        "extensionId",
        "wrapperSignerSha256",
        "browserPackage",
        "browserVersionName",
        "browserVersionCode",
        "browserApkSetSha256",
        "browserSignerSha256",
        "results",
    )
    private val resultFields = setOf(
        "caseId",
        "context",
        "detail",
        "durationMs",
        "member",
        "namespace",
        "runToken",
        "status",
    )
    private val sessionId = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val groupId = Regex("[a-z0-9][a-z0-9-]{0,63}")
    private val sha256 = Regex("[0-9a-f]{64}")
    private val extensionId = Regex("[a-p]{32}")
    private val browserPackage = Regex("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+")
    private val caseId = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val namespace = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*")
    private val member = Regex("[A-Za-z][A-Za-z0-9_]{0,127}")
    private val runToken = Regex("[A-Za-z0-9_-]{16,64}")

    fun decodeStrict(value: String): ProbeEvidence {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_JSON_BYTES) { "API probe evidence는 1 MiB를 초과할 수 없습니다." }
        validateStructure(value)
        val root = try {
            json.parseToJsonElement(value).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("API probe evidence JSON을 읽을 수 없습니다.", error)
        }
        require(root.keys == rootFields) { "API probe evidence root 필드가 정확하지 않습니다." }
        require(root.integer("schemaVersion", Int.MAX_VALUE.toLong()) == SCHEMA_VERSION.toLong()) {
            "지원하지 않는 API probe evidence schemaVersion입니다."
        }
        val results = root.required("results").jsonArray.also {
            require(it.isNotEmpty() && it.size <= MAX_RESULTS) {
                "API probe evidence results 개수가 올바르지 않습니다."
            }
        }.map(::parseResult)
        require(results.map(ProbeCaseResult::caseId).distinct().size == results.size) {
            "API probe evidence case ID는 중복될 수 없습니다."
        }
        require(results.map { Triple(it.namespace, it.member, it.context) }.distinct().size == results.size) {
            "API probe evidence capability key는 중복될 수 없습니다."
        }
        require(results.map(ProbeCaseResult::runToken).distinct().size == results.size) {
            "API probe evidence runToken은 중복될 수 없습니다."
        }
        return ProbeEvidence(
            schemaVersion = SCHEMA_VERSION,
            sessionId = root.string("sessionId").validated("sessionId", sessionId),
            groupId = root.string("groupId").validated("groupId", groupId).also {
                require(it in ApiProbeCatalog.PROBE_GROUP_IDS) {
                    "API probe evidence groupId가 catalog에 없습니다."
                }
            },
            catalogSha256 = root.digest("catalogSha256"),
            extensionId = root.string("extensionId").validated("extensionId", extensionId),
            wrapperSignerSha256 = root.digest("wrapperSignerSha256"),
            browserPackage = root.string("browserPackage").also {
                require(it.length <= 200 && browserPackage.matches(it)) {
                    "browserPackage 값이 올바르지 않습니다."
                }
            },
            browserVersionName = root.string("browserVersionName").also {
                require(it.length in 1..100 && it == it.trim() && it.all { ch -> ch.code in 0x20..0x7e }) {
                    "browserVersionName 값이 올바르지 않습니다."
                }
            },
            browserVersionCode = root.integer("browserVersionCode", MAX_SAFE_INTEGER),
            browserApkSetSha256 = root.digest("browserApkSetSha256"),
            browserSignerSha256 = root.digest("browserSignerSha256"),
            results = results.sortedBy(ProbeCaseResult::caseId),
        )
    }

    fun encodeCanonical(evidence: ProbeEvidence): String = encode(
        JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(evidence.schemaVersion),
                "sessionId" to JsonPrimitive(evidence.sessionId),
                "groupId" to JsonPrimitive(evidence.groupId),
                "catalogSha256" to JsonPrimitive(evidence.catalogSha256),
                "extensionId" to JsonPrimitive(evidence.extensionId),
                "wrapperSignerSha256" to JsonPrimitive(evidence.wrapperSignerSha256),
                "browserPackage" to JsonPrimitive(evidence.browserPackage),
                "browserVersionName" to JsonPrimitive(evidence.browserVersionName),
                "browserVersionCode" to JsonPrimitive(evidence.browserVersionCode),
                "browserApkSetSha256" to JsonPrimitive(evidence.browserApkSetSha256),
                "browserSignerSha256" to JsonPrimitive(evidence.browserSignerSha256),
                "results" to JsonArray(
                    evidence.results.sortedBy(ProbeCaseResult::caseId).map { result ->
                        JsonObject(
                            linkedMapOf(
                                "caseId" to JsonPrimitive(result.caseId),
                                "context" to JsonPrimitive(result.context.name.lowercase()),
                                "detail" to json.parseToJsonElement(result.detail),
                                "durationMs" to JsonPrimitive(result.durationMs),
                                "member" to JsonPrimitive(result.member),
                                "namespace" to JsonPrimitive(result.namespace),
                                "runToken" to JsonPrimitive(result.runToken),
                                "status" to JsonPrimitive(result.status.name),
                            ),
                        )
                    },
                ),
            ),
        ),
    )

    fun canonicalSha256(evidence: ProbeEvidence): String = MessageDigest.getInstance("SHA-256")
        .digest(encodeCanonical(evidence).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun parseResult(value: JsonElement): ProbeCaseResult {
        val objectValue = value.jsonObject
        require(objectValue.keys == resultFields) { "API probe result 필드가 정확하지 않습니다." }
        val status = objectValue.enumExact<ProbeCaseStatus>("status")
        val context = objectValue.enumLowercase<ApiExecutionContext>("context")
        val detailValue = objectValue.required("detail")
        validateStatusDetail(status, context, detailValue)
        return ProbeCaseResult(
            caseId = objectValue.string("caseId").validated("caseId", caseId),
            context = context,
            detail = normalizeDetail(detailValue),
            durationMs = objectValue.integer("durationMs", 600_000),
            member = objectValue.string("member").validated("member", member),
            namespace = objectValue.string("namespace").validated("namespace", namespace),
            runToken = objectValue.string("runToken").validated("runToken", runToken),
            status = status,
        )
    }

    private fun validateStatusDetail(
        status: ProbeCaseStatus,
        context: ApiExecutionContext,
        detail: JsonElement,
    ) {
        val objectValue = detail as? JsonObject
            ?: throw IllegalArgumentException("API probe result detail은 object여야 합니다.")
        val expectedKind = when (status) {
            ProbeCaseStatus.ABSENT -> "absent"
            ProbeCaseStatus.EXPOSED -> "exposed"
            ProbeCaseStatus.NOT_RUN -> "gesture-required"
            ProbeCaseStatus.VERIFIED -> "success"
            ProbeCaseStatus.FAILED, ProbeCaseStatus.RESTRICTED -> "error"
        }
        require(objectValue.string("kind") == expectedKind) {
            "API probe result status와 detail kind가 일치하지 않습니다."
        }
        when (expectedKind) {
            "success" -> {
                require(objectValue.keys == setOf("kind", "valueType")) {
                    "VERIFIED detail 필드가 정확하지 않습니다."
                }
                require(
                    objectValue.string("valueType") in setOf(
                        "bigint",
                        "boolean",
                        "function",
                        "null",
                        "number",
                        "object",
                        "string",
                        "symbol",
                        "undefined",
                    ),
                ) { "VERIFIED detail valueType이 올바르지 않습니다." }
            }
            "error" -> {
                require(objectValue.keys == setOf("kind", "message", "name")) {
                    "오류 detail 필드가 정확하지 않습니다."
                }
                require(objectValue.string("message").length <= 512) {
                    "오류 detail message가 너무 깁니다."
                }
                require(objectValue.string("name").length in 1..80) {
                    "오류 detail name이 올바르지 않습니다."
                }
            }
            "gesture-required" -> {
                require(objectValue.keys == setOf("kind")) {
                    "NOT_RUN detail 필드가 정확하지 않습니다."
                }
                require(context == ApiExecutionContext.USER_GESTURE) {
                    "NOT_RUN은 user_gesture context에서만 허용됩니다."
                }
            }
            "absent", "exposed" -> require(objectValue.keys == setOf("kind")) {
                "$status detail 필드가 정확하지 않습니다."
            }
        }
    }

    private fun normalizeDetail(detail: JsonElement): String {
        require(detail is JsonObject) { "API probe result detail은 object여야 합니다." }
        var valueCount = 0
        fun validate(value: JsonElement, depth: Int) {
            require(depth <= MAX_DETAIL_DEPTH) { "API probe result detail nesting이 너무 깊습니다." }
            when (value) {
                is JsonObject -> {
                    require(value.size <= MAX_DETAIL_VALUES) { "API probe result detail 값이 너무 많습니다." }
                    value.forEach { (key, child) ->
                        require(key.length <= 128) { "API probe result detail key가 너무 깁니다." }
                        valueCount += 1
                        require(valueCount <= MAX_DETAIL_VALUES) { "API probe result detail 값이 너무 많습니다." }
                        validate(child, depth + 1)
                    }
                }
                is JsonArray -> {
                    require(value.size <= MAX_DETAIL_VALUES) { "API probe result detail 값이 너무 많습니다." }
                    value.forEach { child ->
                        valueCount += 1
                        require(valueCount <= MAX_DETAIL_VALUES) { "API probe result detail 값이 너무 많습니다." }
                        validate(child, depth + 1)
                    }
                }
                is JsonPrimitive -> {
                    if (value.isString) {
                        require(value.content.length <= 1_024) {
                            "API probe result detail 문자열이 너무 깁니다."
                        }
                    } else if (value !== JsonNull && value.booleanOrNull == null) {
                        val number = runCatching { BigDecimal(value.content) }.getOrNull()
                            ?: throw IllegalArgumentException("API probe result detail 숫자가 올바르지 않습니다.")
                        require(number.abs() <= MAX_DETAIL_NUMBER) {
                            "API probe result detail 숫자 범위를 벗어났습니다."
                        }
                    }
                }
            }
        }
        validate(detail, 0)
        val canonical = encode(canonicalize(detail))
        require(canonical.toByteArray(Charsets.UTF_8).size <= MAX_DETAIL_BYTES) {
            "API probe result detail은 2 KiB를 초과할 수 없습니다."
        }
        return canonical
    }

    private fun canonicalize(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.toSortedMap().mapValues { canonicalize(it.value) })
        is JsonArray -> JsonArray(value.map(::canonicalize))
        else -> value
    }

    private fun encode(value: JsonElement): String = json.encodeToString(JsonElement.serializer(), value)

    private fun JsonObject.required(name: String): JsonElement =
        this[name] ?: throw IllegalArgumentException("$name 필드가 없습니다.")

    private fun JsonObject.string(name: String): String = required(name).jsonPrimitive.also {
        require(it.isString) { "$name 값은 문자열이어야 합니다." }
    }.content

    private fun JsonObject.digest(name: String): String = string(name).also {
        require(sha256.matches(it)) { "$name 값은 소문자 SHA-256이어야 합니다." }
    }

    private fun JsonObject.integer(name: String, max: Long): Long {
        val primitive = required(name).jsonPrimitive
        require(!primitive.isString && primitive.content.matches(Regex("0|[1-9][0-9]*"))) {
            "$name 값은 JSON 음이 아닌 정수여야 합니다."
        }
        val result = primitive.content.toLongOrNull()
            ?: throw IllegalArgumentException("$name 값이 Long 범위를 벗어났습니다.")
        require(result <= max) { "$name 값이 허용 범위를 벗어났습니다." }
        return result
    }

    private inline fun <reified T : Enum<T>> JsonObject.enumLowercase(name: String): T {
        val raw = string(name)
        return enumValues<T>().singleOrNull { it.name.lowercase() == raw }
            ?: throw IllegalArgumentException("$name enum 값이 올바르지 않습니다.")
    }

    private inline fun <reified T : Enum<T>> JsonObject.enumExact(name: String): T {
        val raw = string(name)
        return enumValues<T>().singleOrNull { it.name == raw }
            ?: throw IllegalArgumentException("$name enum 값이 올바르지 않습니다.")
    }

    private fun String.validated(name: String, pattern: Regex): String = also {
        require(pattern.matches(it)) { "$name 값이 올바르지 않습니다." }
    }

    private fun validateStructure(value: String) {
        val objectKeys = ArrayDeque<MutableSet<String>?>()
        var stringStart = -1
        var escaped = false
        var maxDepth = 0
        var depth = 0
        var index = 0
        while (index < value.length) {
            val ch = value[index]
            if (stringStart >= 0) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') {
                    val raw = value.substring(stringStart, index + 1)
                    var next = index + 1
                    while (next < value.length && value[next].isWhitespace()) next += 1
                    if (next < value.length && value[next] == ':' && objectKeys.lastOrNull() != null) {
                        val key = json.parseToJsonElement(raw).jsonPrimitive.content
                        require(objectKeys.last()!!.add(key)) { "JSON object field는 중복될 수 없습니다." }
                    }
                    stringStart = -1
                }
            } else when (ch) {
                '"' -> stringStart = index
                '{' -> {
                    depth += 1
                    maxDepth = maxOf(maxDepth, depth)
                    objectKeys.addLast(linkedSetOf())
                }
                '[' -> {
                    depth += 1
                    maxDepth = maxOf(maxDepth, depth)
                    objectKeys.addLast(null)
                }
                '}', ']' -> {
                    require(depth > 0 && objectKeys.isNotEmpty()) { "JSON nesting이 올바르지 않습니다." }
                    depth -= 1
                    objectKeys.removeLast()
                }
            }
            require(maxDepth <= MAX_JSON_DEPTH) { "API probe evidence JSON nesting이 너무 깊습니다." }
            index += 1
        }
        require(stringStart == -1 && depth == 0 && objectKeys.isEmpty()) {
            "API probe evidence JSON 구조가 올바르지 않습니다."
        }
    }

    private val MAX_DETAIL_NUMBER = BigDecimal("1e308")
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
}
