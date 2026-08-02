package dev.sixbridge.probe.session

import java.nio.charset.StandardCharsets.UTF_8
import dev.sixbridge.core.profile.ApiExecutionContext
import dev.sixbridge.probe.catalog.ProbeExpectedOutcome
import dev.sixbridge.probe.catalog.ProbeMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ProbeSessionConfigJson {
    private val json = Json { prettyPrint = true }
    private val rootFields = setOf(
        "schemaVersion", "sessionId", "catalogSha256", "browserPackage",
        "browserVersionName", "browserVersionCode", "browserApkSetSha256",
        "browserSignerSha256", "bindings",
    )
    private val bindingFields = setOf("groupId", "extensionId", "wrapperSignerSha256", "cases")
    private val caseFields = setOf("caseId", "namespace", "member", "context", "mode", "expectedOutcome")

    fun encode(config: ProbeSessionConfig): String {
        config.validate()
        val value = JsonObject(linkedMapOf(
            "schemaVersion" to JsonPrimitive(config.schemaVersion),
            "sessionId" to JsonPrimitive(config.sessionId),
            "catalogSha256" to JsonPrimitive(config.catalogSha256),
            "browserPackage" to JsonPrimitive(config.browserPackage),
            "browserVersionName" to JsonPrimitive(config.browserVersionName),
            "browserVersionCode" to JsonPrimitive(config.browserVersionCode),
            "browserApkSetSha256" to JsonPrimitive(config.browserApkSetSha256),
            "browserSignerSha256" to JsonPrimitive(config.browserSignerSha256),
            "bindings" to JsonArray(config.bindings.sortedBy { it.groupId }.map { binding ->
                JsonObject(linkedMapOf(
                    "groupId" to JsonPrimitive(binding.groupId),
                    "extensionId" to JsonPrimitive(binding.extensionId),
                    "wrapperSignerSha256" to JsonPrimitive(binding.wrapperSignerSha256),
                    "cases" to JsonArray(binding.cases.map { case ->
                        JsonObject(linkedMapOf(
                            "caseId" to JsonPrimitive(case.caseId),
                            "namespace" to JsonPrimitive(case.namespace),
                            "member" to JsonPrimitive(case.member),
                            "context" to JsonPrimitive(case.context.name.lowercase()),
                            "mode" to JsonPrimitive(case.mode.name.lowercase()),
                            "expectedOutcome" to JsonPrimitive(case.expectedOutcome.name.lowercase()),
                        ))
                    }),
                ))
            }),
        ))
        return json.encodeToString(JsonObject.serializer(), value) + "\n"
    }

    fun decodeStrict(value: String): ProbeSessionConfig {
        require(value.toByteArray(UTF_8).size <= MAX_BYTES) { "probe session config가 너무 큽니다." }
        rejectDuplicateObjectKeys(value)
        val root = try {
            json.parseToJsonElement(value).jsonObject
        } catch (error: Exception) {
            throw IllegalArgumentException("probe session config JSON을 읽을 수 없습니다.", error)
        }
        require(root.keys == rootFields) { "probe session config field가 정확하지 않습니다." }
        val config = ProbeSessionConfig(
            schemaVersion = root.int("schemaVersion"),
            sessionId = root.string("sessionId"),
            catalogSha256 = root.string("catalogSha256"),
            browserPackage = root.string("browserPackage"),
            browserVersionName = root.string("browserVersionName"),
            browserVersionCode = root.long("browserVersionCode"),
            browserApkSetSha256 = root.string("browserApkSetSha256"),
            browserSignerSha256 = root.string("browserSignerSha256"),
            bindings = root.required("bindings").jsonArray.map { element ->
                val binding = element.jsonObject
                require(binding.keys == bindingFields) { "probe session binding field가 정확하지 않습니다." }
                ProbeSessionBinding(
                    groupId = binding.string("groupId"),
                    extensionId = binding.string("extensionId"),
                    wrapperSignerSha256 = binding.string("wrapperSignerSha256"),
                    cases = binding.required("cases").jsonArray.map { caseElement ->
                        val case = caseElement.jsonObject
                        require(case.keys == caseFields) { "probe session case descriptor field가 정확하지 않습니다." }
                        ProbeSessionCaseDescriptor(
                            caseId = case.string("caseId"),
                            namespace = case.string("namespace"),
                            member = case.string("member"),
                            context = case.enumValue<ApiExecutionContext>("context"),
                            mode = case.enumValue<ProbeMode>("mode"),
                            expectedOutcome = case.enumValue<ProbeExpectedOutcome>("expectedOutcome"),
                        )
                    },
                )
            },
        ).also(ProbeSessionConfig::validate)
        require(encode(config) == value) { "probe session config는 canonical JSON이어야 합니다." }
        return config
    }

    private fun JsonObject.required(name: String) = this[name]
        ?: throw IllegalArgumentException("$name field가 없습니다.")

    private fun JsonObject.string(name: String): String = required(name).jsonPrimitive.also {
        require(it.isString) { "$name field는 string이어야 합니다." }
    }.content

    private fun JsonObject.int(name: String): Int = required(name).jsonPrimitive.also {
        require(!it.isString) { "$name field는 integer여야 합니다." }
    }.content.toIntOrNull() ?: throw IllegalArgumentException("$name field는 integer여야 합니다.")

    private fun JsonObject.long(name: String): Long = required(name).jsonPrimitive.also {
        require(!it.isString) { "$name field는 integer여야 합니다." }
    }.content.toLongOrNull() ?: throw IllegalArgumentException("$name field는 integer여야 합니다.")

    private inline fun <reified T : Enum<T>> JsonObject.enumValue(name: String): T {
        val value = string(name)
        return enumValues<T>().singleOrNull { it.name.lowercase() == value }
            ?: throw IllegalArgumentException("$name field가 올바르지 않습니다.")
    }

    private fun rejectDuplicateObjectKeys(value: String) {
        val stack = ArrayDeque<MutableSet<String>?>()
        var stringStart = -1
        var escaped = false
        var depth = 0
        value.forEachIndexed { index, char ->
            if (stringStart >= 0) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') {
                    var next = index + 1
                    while (next < value.length && value[next].isWhitespace()) next += 1
                    val keys = stack.lastOrNull()
                    if (next < value.length && value[next] == ':' && keys != null) {
                        val key = json.decodeFromString<String>(value.substring(stringStart, index + 1))
                        require(keys.add(key)) { "probe session config에 duplicate JSON field가 있습니다." }
                    }
                    stringStart = -1
                }
            } else when (char) {
                '"' -> stringStart = index
                '{' -> { depth += 1; require(depth <= 32); stack.addLast(linkedSetOf()) }
                '[' -> { depth += 1; require(depth <= 32); stack.addLast(null) }
                '}', ']' -> { require(depth > 0 && stack.isNotEmpty()); depth -= 1; stack.removeLast() }
            }
        }
        require(stringStart < 0 && depth == 0 && stack.isEmpty()) { "probe session config JSON structure가 올바르지 않습니다." }
    }

    private const val MAX_BYTES = 64 * 1024
}
