package dev.sixbridge.probe.catalog

import dev.sixbridge.core.profile.ApiExecutionContext
import java.io.InputStream
import java.security.MessageDigest
import java.util.Collections
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class CatalogDisposition {
    PROBE,
    PLATFORM_RESTRICTED,
    LEGACY_APP,
    INTERNAL,
}

enum class ProbeExpectedOutcome {
    VERIFIED,
    EXPOSED,
}

data class ProbeCatalogCase(
    val id: String,
    val member: String,
    val context: ApiExecutionContext,
    val permissions: Set<String>,
    val manifestKeys: Set<String>,
    val expectedOutcome: ProbeExpectedOutcome,
)

data class ProbeCatalogEntry(
    val namespace: String,
    val disposition: CatalogDisposition,
    val groupId: String?,
    val cases: List<ProbeCatalogCase>,
)

class ApiProbeCatalog private constructor(
    entries: List<ProbeCatalogEntry>,
) {
    val entries: List<ProbeCatalogEntry> = Collections.unmodifiableList(
        entries.sortedBy(ProbeCatalogEntry::namespace),
    )
    val probeCaseIds: Set<String> = Collections.unmodifiableSet(
        this.entries.asSequence()
            .filter { it.disposition == CatalogDisposition.PROBE }
            .flatMap { it.cases.asSequence() }
            .map(ProbeCatalogCase::id)
            .toCollection(linkedSetOf()),
    )
    val canonicalJson: String = encodeCanonical(this.entries)
    val sha256: String = MessageDigest.getInstance("SHA-256")
        .digest(canonicalJson.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    companion object {
        val PROBE_GROUP_IDS: Set<String> = Collections.unmodifiableSet(
            linkedSetOf(
                "advanced-runtime",
                "base-runtime",
                "identity-privacy",
                "network",
                "tab-page",
                "user-interface",
            ),
        )

        private val json = Json { prettyPrint = true }
        private val rootFields = setOf("schemaVersion", "entries")
        private val entryFields = setOf("namespace", "disposition", "groupId", "cases")
        private val caseFields = setOf(
            "id",
            "member",
            "context",
            "permissions",
            "manifestKeys",
            "expectedOutcome",
        )
        private val apiName = Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)*")
        private val apiMember = Regex("[A-Za-z][A-Za-z0-9_]*")
        private val caseId = Regex("[a-z0-9][a-z0-9._-]*")
        private val groupId = Regex("[a-z0-9][a-z0-9-]*")
        private val setValue = Regex("[A-Za-z][A-Za-z0-9_.-]*")

        fun load(input: InputStream): ApiProbeCatalog {
            val value = input.readBytes().toString(Charsets.UTF_8)
            rejectDuplicateObjectKeys(value)
            val root = try {
                json.parseToJsonElement(value).jsonObject
            } catch (error: IllegalArgumentException) {
                throw error
            } catch (error: Exception) {
                throw IllegalArgumentException("API probe catalog JSON을 읽을 수 없습니다.", error)
            }
            require(root.keys == rootFields) {
                "API probe catalog root 필드가 정확하지 않습니다."
            }
            require(root.integer("schemaVersion") == 1) {
                "지원하지 않는 API probe catalog schemaVersion입니다."
            }
            val entries = root.required("entries").jsonArray.map(::parseEntry)
            require(entries.isNotEmpty()) { "API probe catalog entry가 비어 있습니다." }
            require(entries.map(ProbeCatalogEntry::namespace).distinct().size == entries.size) {
                "API probe catalog namespace는 중복될 수 없습니다."
            }
            val cases = entries.flatMap(ProbeCatalogEntry::cases)
            require(cases.map(ProbeCatalogCase::id).distinct().size == cases.size) {
                "API probe catalog case ID는 중복될 수 없습니다."
            }
            return ApiProbeCatalog(entries)
        }

        private fun parseEntry(value: kotlinx.serialization.json.JsonElement): ProbeCatalogEntry {
            val objectValue = value.jsonObject
            require(objectValue.keys == entryFields) {
                "API probe catalog entry 필드가 정확하지 않습니다."
            }
            val namespace = objectValue.string("namespace").also {
                requireIdentifier("namespace", it, apiName)
            }
            val disposition = objectValue.enum<CatalogDisposition>("disposition")
            val parsedGroupId = objectValue.nullableString("groupId")?.also {
                requireIdentifier("groupId", it, groupId, 64)
                require(it in PROBE_GROUP_IDS) { "API probe groupId가 올바르지 않습니다." }
            }
            val cases = objectValue.required("cases").jsonArray.map(::parseCase).sortedBy {
                it.id
            }
            if (disposition == CatalogDisposition.PROBE) {
                require(parsedGroupId != null) { "PROBE entry에 groupId가 필요합니다." }
                require(cases.isNotEmpty()) { "PROBE entry에 최소 한 개의 case가 필요합니다." }
            } else {
                require(parsedGroupId == null) { "비 PROBE entry의 groupId는 null이어야 합니다." }
                require(cases.isEmpty()) { "비 PROBE entry에는 case를 둘 수 없습니다." }
            }
            return ProbeCatalogEntry(
                namespace = namespace,
                disposition = disposition,
                groupId = parsedGroupId,
                cases = Collections.unmodifiableList(cases),
            )
        }

        private fun parseCase(value: kotlinx.serialization.json.JsonElement): ProbeCatalogCase {
            val objectValue = value.jsonObject
            require(objectValue.keys == caseFields) {
                "API probe catalog case 필드가 정확하지 않습니다."
            }
            val id = objectValue.string("id").also {
                requireIdentifier("case ID", it, caseId)
            }
            val member = objectValue.string("member").also {
                requireIdentifier("member", it, apiMember)
            }
            return ProbeCatalogCase(
                id = id,
                member = member,
                context = objectValue.enum("context"),
                permissions = objectValue.stringSet("permissions"),
                manifestKeys = objectValue.stringSet("manifestKeys"),
                expectedOutcome = objectValue.enum("expectedOutcome"),
            )
        }

        private fun encodeCanonical(entries: List<ProbeCatalogEntry>): String =
            json.encodeToString(
                JsonObject.serializer(),
                JsonObject(
                    linkedMapOf(
                        "schemaVersion" to JsonPrimitive(1),
                        "entries" to JsonArray(
                            entries.sortedBy(ProbeCatalogEntry::namespace).map { entry ->
                                JsonObject(
                                    linkedMapOf(
                                        "namespace" to JsonPrimitive(entry.namespace),
                                        "disposition" to JsonPrimitive(
                                            entry.disposition.name.lowercase(),
                                        ),
                                        "groupId" to (
                                            entry.groupId?.let(::JsonPrimitive) ?: JsonNull
                                        ),
                                        "cases" to JsonArray(
                                            entry.cases.sortedBy(ProbeCatalogCase::id).map { case ->
                                                JsonObject(
                                                    linkedMapOf(
                                                        "id" to JsonPrimitive(case.id),
                                                        "member" to JsonPrimitive(case.member),
                                                        "context" to JsonPrimitive(
                                                            case.context.name.lowercase(),
                                                        ),
                                                        "permissions" to JsonArray(
                                                            case.permissions.sorted().map(
                                                                ::JsonPrimitive,
                                                            ),
                                                        ),
                                                        "manifestKeys" to JsonArray(
                                                            case.manifestKeys.sorted().map(
                                                                ::JsonPrimitive,
                                                            ),
                                                        ),
                                                        "expectedOutcome" to JsonPrimitive(
                                                            case.expectedOutcome.name.lowercase(),
                                                        ),
                                                    ),
                                                )
                                            },
                                        ),
                                    ),
                                )
                            },
                        ),
                    ),
                ),
            )

        private fun JsonObject.required(name: String) =
            this[name] ?: throw IllegalArgumentException("$name 필드가 없습니다.")

        private fun JsonObject.string(name: String): String =
            required(name).jsonPrimitive.also {
                require(it.isString) { "$name 값은 문자열이어야 합니다." }
            }.content

        private fun JsonObject.nullableString(name: String): String? {
            val value = required(name)
            if (value == JsonNull) return null
            return value.jsonPrimitive.also {
                require(it.isString) { "$name 값은 문자열 또는 null이어야 합니다." }
            }.content
        }

        private fun JsonObject.integer(name: String): Int =
            required(name).jsonPrimitive.also {
                require(!it.isString && it.content.matches(Regex("0|[1-9][0-9]*"))) {
                    "$name 값은 JSON 양의 정수여야 합니다."
                }
            }.content.toIntOrNull()
                ?: throw IllegalArgumentException("$name 값이 Int 범위를 벗어났습니다.")

        private fun JsonObject.stringSet(name: String): Set<String> {
            val values = required(name).jsonArray.map { value ->
                value.jsonPrimitive.also {
                    require(it.isString) { "$name 항목은 문자열이어야 합니다." }
                }.content.also {
                    requireIdentifier(name, it, setValue)
                }
            }
            require(values.distinct().size == values.size) { "$name 항목은 중복될 수 없습니다." }
            return Collections.unmodifiableSet(values.sorted().toCollection(linkedSetOf()))
        }

        private inline fun <reified T : Enum<T>> JsonObject.enum(name: String): T {
            val value = string(name)
            return enumValues<T>().singleOrNull { it.name.lowercase() == value }
                ?: throw IllegalArgumentException("$name enum 값이 올바르지 않습니다.")
        }

        private fun requireIdentifier(
            name: String,
            value: String,
            pattern: Regex,
            maxLength: Int = 128,
        ) {
            require(value.length in 1..maxLength && pattern.matches(value)) {
                "API probe $name 식별자가 올바르지 않습니다."
            }
        }

        private fun rejectDuplicateObjectKeys(value: String) {
            JsonStructureScanner(value).parse()
        }

        private class JsonStructureScanner(
            private val value: String,
        ) {
            private var index = 0

            fun parse() {
                parseValue()
                skipWhitespace()
                require(index == value.length) { "API probe catalog JSON 뒤에 값이 남아 있습니다." }
            }

            private fun parseValue() {
                skipWhitespace()
                require(index < value.length) { "API probe catalog JSON 값이 없습니다." }
                when (value[index]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    else -> parseLiteral()
                }
            }

            private fun parseObject() {
                expect('{')
                skipWhitespace()
                if (take('}')) return
                val keys = mutableSetOf<String>()
                while (true) {
                    val key = parseString()
                    require(keys.add(key)) {
                        "API probe catalog JSON object key가 중복되었습니다: $key"
                    }
                    skipWhitespace()
                    expect(':')
                    parseValue()
                    skipWhitespace()
                    if (take('}')) return
                    expect(',')
                    skipWhitespace()
                }
            }

            private fun parseArray() {
                expect('[')
                skipWhitespace()
                if (take(']')) return
                while (true) {
                    parseValue()
                    skipWhitespace()
                    if (take(']')) return
                    expect(',')
                    skipWhitespace()
                }
            }

            private fun parseString(): String {
                skipWhitespace()
                require(index < value.length && value[index] == '"') {
                    "API probe catalog JSON 문자열이 올바르지 않습니다."
                }
                val start = index
                index += 1
                while (index < value.length) {
                    when (value[index]) {
                        '\\' -> {
                            index += 1
                            require(index < value.length) {
                                "API probe catalog JSON escape가 끝나지 않았습니다."
                            }
                            index += 1
                        }
                        '"' -> {
                            index += 1
                            return Json.parseToJsonElement(value.substring(start, index))
                                .jsonPrimitive
                                .content
                        }
                        else -> index += 1
                    }
                }
                throw IllegalArgumentException("API probe catalog JSON 문자열이 끝나지 않았습니다.")
            }

            private fun parseLiteral() {
                val start = index
                while (
                    index < value.length &&
                    value[index] !in charArrayOf(',', '}', ']') &&
                    !value[index].isWhitespace()
                ) {
                    index += 1
                }
                require(index > start) { "API probe catalog JSON 값이 올바르지 않습니다." }
            }

            private fun skipWhitespace() {
                while (index < value.length && value[index].isWhitespace()) index += 1
            }

            private fun expect(expected: Char) {
                require(index < value.length && value[index] == expected) {
                    "API probe catalog JSON 구조가 올바르지 않습니다."
                }
                index += 1
            }

            private fun take(expected: Char): Boolean {
                if (index >= value.length || value[index] != expected) return false
                index += 1
                return true
            }
        }
    }
}
