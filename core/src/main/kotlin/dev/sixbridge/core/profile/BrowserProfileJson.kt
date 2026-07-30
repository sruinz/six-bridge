package dev.sixbridge.core.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object BrowserProfileJson {
    private val json = Json { prettyPrint = true }
    private val wrapperFields = setOf(
        "contentProvider",
        "devExtensionPath",
        "discoveryUri",
        "extensionMetadata",
    )
    private val version1Fields = setOf(
        "browserPackage",
        "detectedApis",
        "majorVersion",
        "profileDifferences",
        "schemaVersion",
        "sourceApkSha256",
        "sourceApkSignerSha256",
        "support",
        "supportedApis",
        "versionCode",
        "versionName",
        "wrapperContract",
    )
    private val version2Fields = version1Fields + setOf(
        "supportedFeatures",
        "unavailableFeatures",
    )
    private val version3Fields = version2Fields + "installationApkSetSha256"

    fun encode(profile: BrowserProfile): String {
        profile.validate()
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                linkedMapOf(
                    "schemaVersion" to JsonPrimitive(3),
                    "browserPackage" to JsonPrimitive(profile.browserPackage),
                    "versionName" to JsonPrimitive(profile.versionName),
                    "versionCode" to JsonPrimitive(profile.versionCode),
                    "majorVersion" to JsonPrimitive(profile.majorVersion),
                    "detectedApis" to JsonArray(
                        profile.detectedApis.sorted().map(::JsonPrimitive),
                    ),
                    "supportedApis" to JsonArray(
                        profile.supportedApis.sorted().map(::JsonPrimitive),
                    ),
                    "wrapperContract" to JsonObject(
                        linkedMapOf(
                            "discoveryUri" to JsonPrimitive(
                                profile.wrapperContract.discoveryUri,
                            ),
                            "extensionMetadata" to JsonPrimitive(
                                profile.wrapperContract.extensionMetadata,
                            ),
                            "contentProvider" to JsonPrimitive(
                                profile.wrapperContract.contentProvider,
                            ),
                            "devExtensionPath" to JsonPrimitive(
                                profile.wrapperContract.devExtensionPath,
                            ),
                        ),
                    ),
                    "sourceApkSha256" to JsonPrimitive(profile.sourceApkSha256),
                    "installationApkSetSha256" to JsonPrimitive(
                        profile.installationApkSetSha256,
                    ),
                    "sourceApkSignerSha256" to JsonPrimitive(
                        profile.sourceApkSignerSha256,
                    ),
                    "profileDifferences" to JsonArray(
                        profile.profileDifferences.map(::JsonPrimitive),
                    ),
                    "supportedFeatures" to JsonArray(
                        profile.supportedFeatures.sorted().map(::JsonPrimitive),
                    ),
                    "unavailableFeatures" to JsonArray(
                        profile.unavailableFeatures.sorted().map(::JsonPrimitive),
                    ),
                    "support" to JsonPrimitive(profile.support.name.lowercase()),
                ),
            ),
        )
    }

    fun decode(value: String): BrowserProfile {
        rejectDuplicateObjectKeys(value)
        val root = try {
            json.parseToJsonElement(value).jsonObject
        } catch (error: IllegalArgumentException) {
            throw error
        } catch (error: Exception) {
            throw IllegalArgumentException("browser profile JSON을 읽을 수 없습니다.", error)
        }
        val schemaVersion = root.int("schemaVersion")
        require(schemaVersion in 1..3) {
            "지원하지 않는 browser profile schemaVersion입니다."
        }
        val expectedFields = when (schemaVersion) {
            1 -> version1Fields
            2 -> version2Fields
            else -> version3Fields
        }
        require(root.keys == expectedFields) {
            "browser profile schema 항목이 정확하지 않습니다."
        }
        val contract = root.required("wrapperContract").jsonObject
        require(contract.keys == wrapperFields) {
            "browser profile wrapper contract 항목이 정확하지 않습니다."
        }
        val supportedApis = root.stringSet("supportedApis")
        val profile = BrowserProfile(
            browserPackage = root.string("browserPackage"),
            versionName = root.string("versionName"),
            versionCode = root.long("versionCode"),
            majorVersion = root.int("majorVersion"),
            supportedApis = supportedApis,
            detectedApis = root.stringSet("detectedApis"),
            wrapperContract = WrapperContract(
                discoveryUri = contract.boolean("discoveryUri"),
                extensionMetadata = contract.boolean("extensionMetadata"),
                contentProvider = contract.boolean("contentProvider"),
                devExtensionPath = contract.boolean("devExtensionPath"),
            ),
            sourceApkSha256 = root.string("sourceApkSha256"),
            installationApkSetSha256 = if (schemaVersion < 3) {
                root.string("sourceApkSha256")
            } else {
                root.string("installationApkSetSha256")
            },
            sourceApkSignerSha256 = root.string("sourceApkSignerSha256"),
            profileDifferences = root.stringList("profileDifferences"),
            supportedFeatures = if (schemaVersion == 1) {
                emptySet()
            } else {
                root.stringSet("supportedFeatures")
            },
            unavailableFeatures = if (schemaVersion == 1) {
                emptySet()
            } else {
                root.stringSet("unavailableFeatures")
            },
        ).also(BrowserProfile::validate)
        require(root.string("support") == profile.support.name.lowercase()) {
            "browser profile support 값이 계산 결과와 일치하지 않습니다."
        }
        return profile
    }

    private fun JsonObject.required(name: String) =
        this[name] ?: throw IllegalArgumentException("$name 항목이 없습니다.")

    private fun JsonObject.string(name: String): String =
        required(name).jsonPrimitive.also {
            require(it.isString) { "$name 값은 문자열이어야 합니다." }
        }.content

    private fun JsonObject.boolean(name: String): Boolean =
        required(name).jsonPrimitive.also {
            require(!it.isString && it.content in setOf("true", "false")) {
                "$name 값은 boolean이어야 합니다."
            }
        }.content.toBooleanStrict()

    private fun JsonObject.int(name: String): Int =
        integer(name).toIntOrNull()
            ?: throw IllegalArgumentException("$name 값이 Int 범위를 벗어났습니다.")

    private fun JsonObject.long(name: String): Long =
        integer(name).toLongOrNull()
            ?: throw IllegalArgumentException("$name 값이 Long 범위를 벗어났습니다.")

    private fun JsonObject.integer(name: String): String =
        required(name).jsonPrimitive.also {
            require(!it.isString && it.content.matches(Regex("0|-?[1-9][0-9]*"))) {
                "$name 값은 JSON 정수여야 합니다."
            }
        }.content

    private fun JsonObject.stringList(name: String): List<String> =
        required(name).jsonArray.map { value ->
            value.jsonPrimitive.also {
                require(it.isString) { "$name 항목은 문자열이어야 합니다." }
            }.content
        }.also {
            require(it.distinct().size == it.size) { "$name 항목은 중복될 수 없습니다." }
        }

    private fun JsonObject.stringSet(name: String): Set<String> =
        stringList(name).toSet()

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
            require(index == value.length) { "browser profile JSON 뒤에 값이 남아 있습니다." }
        }

        private fun parseValue() {
            skipWhitespace()
            require(index < value.length) { "browser profile JSON 값이 없습니다." }
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
                require(keys.add(key)) { "browser profile JSON object key가 중복되었습니다: $key" }
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
                "browser profile JSON object key 또는 문자열이 올바르지 않습니다."
            }
            val start = index
            index += 1
            while (index < value.length) {
                when (value[index]) {
                    '\\' -> {
                        index += 1
                        require(index < value.length) { "browser profile JSON escape가 끝나지 않았습니다." }
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
            throw IllegalArgumentException("browser profile JSON 문자열이 끝나지 않았습니다.")
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
            require(index > start) { "browser profile JSON 값이 올바르지 않습니다." }
        }

        private fun skipWhitespace() {
            while (index < value.length && value[index].isWhitespace()) index += 1
        }

        private fun expect(expected: Char) {
            require(index < value.length && value[index] == expected) {
                "browser profile JSON 구조가 올바르지 않습니다."
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
