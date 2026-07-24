package dev.sixbridge.core.draft

import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.core.conversion.AdapterDecision
import dev.sixbridge.core.conversion.AdapterDisposition
import dev.sixbridge.core.conversion.AdapterRisk
import dev.sixbridge.core.conversion.ConversionState
import dev.sixbridge.core.conversion.SourceEvidence
import dev.sixbridge.core.input.ExtensionInputKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

internal object SixDraftJson {
    private val json = Json

    fun encodeDraft(value: DraftMetadata): ByteArray = canonicalBytes(
        objectOf(
            "adapterVersions" to JsonObject(
                value.adapterVersions.mapValues { JsonPrimitive(it.value) },
            ),
            "coreVersion" to JsonPrimitive(value.coreVersion),
            "schemaVersion" to JsonPrimitive(1),
            "sourceIdentity" to JsonPrimitive(value.sourceIdentity),
        ),
    )

    fun decodeDraft(bytes: ByteArray): DraftMetadata {
        val value = parseCanonicalObject("draft.json", bytes)
        value.requireKeys(DRAFT_KEYS, "draft.json")
        value.requireSchema("draft.json", 1)
        val versions = value.getValue("adapterVersions").jsonObject.also {
            require(it.keys.all(ID::matches)) {
                "draft.json adapterVersions ID가 올바르지 않습니다."
            }
        }.mapValues { (_, version) ->
            version.strictInt("adapterVersions.version").also {
                require(it > 0) { "adapter version은 양수여야 합니다." }
            }
        }
        return DraftMetadata.create(
            coreVersion = value.strictString("coreVersion"),
            adapterVersions = versions,
            sourceIdentity = value.strictString("sourceIdentity"),
        )
    }

    fun encodeInput(value: DraftInputMetadata): ByteArray = canonicalBytes(
        objectOf(
            "browserProfileSha256" to JsonPrimitive(value.browserProfileSha256),
            "inputKind" to JsonPrimitive(value.inputKind.jsonValue()),
            "originalExtensionId" to nullableString(value.originalExtensionId),
            "originalExtensionVersion" to JsonPrimitive(value.originalExtensionVersion),
            "originalSourceSha256" to JsonPrimitive(value.originalSourceSha256),
            "schemaVersion" to JsonPrimitive(1),
            "sourceIdentity" to JsonPrimitive(value.sourceIdentity),
        ),
    )

    fun decodeInput(bytes: ByteArray): DraftInputMetadata {
        val value = parseCanonicalObject("input.json", bytes)
        value.requireKeys(INPUT_KEYS, "input.json")
        value.requireSchema("input.json", 1)
        return DraftInputMetadata(
            inputKind = enumFromJson(value.strictString("inputKind")),
            originalSourceSha256 = value.strictSha256("originalSourceSha256"),
            browserProfileSha256 = value.strictSha256("browserProfileSha256"),
            originalExtensionId = value.nullableString("originalExtensionId"),
            originalExtensionVersion = value.strictString("originalExtensionVersion"),
            sourceIdentity = value.strictString("sourceIdentity"),
        )
    }

    fun encodePlan(value: DraftConversionPlan): ByteArray = canonicalBytes(
        objectOf(
            "browserProfileSha256" to JsonPrimitive(value.browserProfileSha256),
            "planSha256" to JsonPrimitive(value.planSha256),
            "reportSha256" to JsonPrimitive(value.reportSha256),
            "reportSourceSha256" to JsonPrimitive(value.reportSourceSha256),
            "schemaVersion" to JsonPrimitive(1),
            "selectedAdapters" to JsonArray(
                value.selectedAdapters.map {
                    objectOf(
                        "id" to JsonPrimitive(it.id),
                        "version" to JsonPrimitive(it.version),
                    )
                },
            ),
        ),
    )

    fun decodePlan(bytes: ByteArray): DraftConversionPlan {
        val value = parseCanonicalObject("conversion-plan.json", bytes)
        value.requireKeys(PLAN_KEYS, "conversion-plan.json")
        value.requireSchema("conversion-plan.json", 1)
        val selected = value.getValue("selectedAdapters").jsonArray.map { element ->
            val selectedValue = element.jsonObject
            selectedValue.requireKeys(SELECTED_ADAPTER_KEYS, "selectedAdapter")
            DraftSelectedAdapter(
                selectedValue.strictString("id"),
                selectedValue.strictPositiveInt("version"),
            )
        }
        require(selected == selected.sortedWith(compareBy(DraftSelectedAdapter::id, DraftSelectedAdapter::version))) {
            "selectedAdapters는 ID와 version 순서여야 합니다."
        }
        require(selected.distinct().size == selected.size) {
            "selectedAdapters는 중복될 수 없습니다."
        }
        return DraftConversionPlan.decoded(
            selectedAdapters = selected,
            reportSourceSha256 = value.strictSha256("reportSourceSha256"),
            browserProfileSha256 = value.strictSha256("browserProfileSha256"),
            reportSha256 = value.strictSha256("reportSha256"),
            planSha256 = value.strictSha256("planSha256"),
        )
    }

    fun encodeCompatibility(value: CompatibilityReport): ByteArray {
        validateCompatibilitySchema(value)
        requireCanonicalAdapterDecisions(value.adapterDecisions)
        requireNotNull(value.sourceSha256) {
            "compatibility schema 3에는 sourceSha256이 필요합니다."
        }
        requireNotNull(value.browserProfileSha256) {
            "compatibility schema 3에는 browserProfileSha256이 필요합니다."
        }
        return canonicalBytes(
            objectOf(
                "adapterDecisions" to JsonArray(
                    value.adapterDecisions.map(::encodeAdapterDecision),
                ),
                "browserProfileSha256" to JsonPrimitive(value.browserProfileSha256),
                "conversionState" to JsonPrimitive(value.conversionState.jsonValue()),
                "convertedExtensionId" to nullableString(value.convertedExtensionId),
                "declaredPermissions" to stringSet(value.declaredPermissions),
                "extensionName" to nullableString(value.extensionName),
                "extensionVersion" to nullableString(value.extensionVersion),
                "issues" to JsonArray(value.issues.map(::encodeIssue)),
                "manifestKeyExtensionId" to nullableString(value.manifestKeyExtensionId),
                "manifestKeyPresent" to JsonPrimitive(value.manifestKeyPresent),
                "manifestVersion" to (
                    value.manifestVersion?.let(::JsonPrimitive) ?: JsonNull
                    ),
                "originalExtensionId" to nullableString(value.originalExtensionId),
                "schemaVersion" to JsonPrimitive(3),
                "sourceSha256" to JsonPrimitive(value.sourceSha256),
                "status" to JsonPrimitive(value.status.jsonValue()),
                "unsupportedApis" to stringSet(value.unsupportedApis),
                "usedApis" to stringSet(value.usedApis),
            ),
        )
    }

    fun decodeCompatibility(name: String, bytes: ByteArray): CompatibilityReport {
        val value = parseCanonicalObject(name, bytes)
        value.requireKeys(COMPATIBILITY_KEYS, name)
        value.requireSchema(name, 3)
        val decisions = value.getValue("adapterDecisions").jsonArray.map {
            decodeAdapterDecision(it)
        }
        requireCanonicalAdapterDecisions(decisions)
        val report = CompatibilityReport(
            status = enumFromJson(value.strictString("status")),
            manifestVersion = value.nullableInt("manifestVersion"),
            extensionName = value.nullableString("extensionName"),
            extensionVersion = value.nullableString("extensionVersion"),
            manifestKeyPresent = value.strictBoolean("manifestKeyPresent"),
            manifestKeyExtensionId = value.nullableString("manifestKeyExtensionId"),
            declaredPermissions = value.strictStringSet("declaredPermissions"),
            usedApis = value.strictStringSet("usedApis"),
            unsupportedApis = value.strictStringSet("unsupportedApis"),
            issues = value.getValue("issues").jsonArray.map { element ->
                val issue = element.jsonObject
                issue.requireKeys(ISSUE_KEYS, "compatibility issue")
                CompatibilityIssue(
                    code = issue.strictString("code"),
                    message = issue.strictString("message"),
                    blocking = issue.strictBoolean("blocking"),
                )
            },
            conversionState = enumFromJson(value.strictString("conversionState")),
            adapterDecisions = decisions,
            originalExtensionId = value.nullableString("originalExtensionId"),
            convertedExtensionId = value.nullableString("convertedExtensionId"),
            sourceSha256 = value.strictSha256("sourceSha256"),
            browserProfileSha256 = value.strictSha256("browserProfileSha256"),
        )
        validateCompatibilitySchema(report)
        return report
    }

    fun compatibilitySha256(value: CompatibilityReport): String =
        DraftSignature.sha256(encodeCompatibility(value))

    fun encodeExpected(value: DraftExpected): ByteArray = canonicalBytes(
        objectOf(
            "planSha256" to JsonPrimitive(value.planSha256),
            "reportSha256" to JsonPrimitive(value.reportSha256),
            "schemaVersion" to JsonPrimitive(1),
            "sourceSha256" to JsonPrimitive(value.sourceSha256),
        ),
    )

    fun decodeExpected(bytes: ByteArray): DraftExpected {
        val value = parseCanonicalObject("expected.json", bytes)
        value.requireKeys(EXPECTED_KEYS, "expected.json")
        value.requireSchema("expected.json", 1)
        return DraftExpected(
            sourceSha256 = value.strictSha256("sourceSha256"),
            reportSha256 = value.strictSha256("reportSha256"),
            planSha256 = value.strictSha256("planSha256"),
        )
    }

    fun encodeSignature(value: DraftSignatureRecord): ByteArray = canonicalBytes(
        objectOf(
            "algorithm" to JsonPrimitive(value.algorithm),
            "keyId" to JsonPrimitive(value.keyId),
            "publicKey" to JsonPrimitive(Base64.getEncoder().encodeToString(value.publicKey)),
            "schemaVersion" to JsonPrimitive(value.schemaVersion),
            "signature" to JsonPrimitive(Base64.getEncoder().encodeToString(value.signature)),
        ),
    )

    fun decodeSignature(bytes: ByteArray): DraftSignatureRecord {
        val value = parseCanonicalObject(DraftSignature.ENTRY_NAME, bytes)
        value.requireKeys(SIGNATURE_KEYS, DraftSignature.ENTRY_NAME)
        value.requireSchema(DraftSignature.ENTRY_NAME, DraftSignature.SCHEMA_VERSION)
        val algorithm = value.strictString("algorithm")
        require(algorithm == DraftSignature.ALGORITHM) {
            "SixDraft 전달 서명 algorithm은 Ed25519여야 합니다."
        }
        return DraftSignatureRecord.create(
            schemaVersion = DraftSignature.SCHEMA_VERSION,
            algorithm = algorithm,
            keyId = value.strictSha256("keyId"),
            publicKey = value.canonicalBase64("publicKey"),
            signature = value.canonicalBase64("signature"),
        )
    }

    fun validateCanonicalDocument(name: String, bytes: ByteArray) {
        parseCanonicalObject(name, bytes)
    }

    private fun encodeIssue(value: CompatibilityIssue): JsonObject = objectOf(
        "blocking" to JsonPrimitive(value.blocking),
        "code" to JsonPrimitive(value.code),
        "message" to JsonPrimitive(value.message),
    )

    private fun encodeAdapterDecision(value: AdapterDecision): JsonObject = objectOf(
        "applied" to JsonPrimitive(value.applied),
        "changes" to stringList(value.changes.sorted()),
        "disposition" to JsonPrimitive(value.disposition.jsonValue()),
        "evidence" to JsonArray(
            value.evidence.sortedWith(
                compareBy(SourceEvidence::relativePath, SourceEvidence::line)
                    .thenBy(SourceEvidence::detail),
            ).map {
                objectOf(
                    "detail" to JsonPrimitive(it.detail),
                    "line" to (it.line?.let(::JsonPrimitive) ?: JsonNull),
                    "relativePath" to JsonPrimitive(it.relativePath),
                )
            },
        ),
        "id" to JsonPrimitive(value.id),
        "remainingLimitations" to stringList(value.remainingLimitations.sorted()),
        "risk" to JsonPrimitive(value.risk.jsonValue()),
        "version" to JsonPrimitive(value.version),
    )

    private fun requireCanonicalAdapterDecisions(values: List<AdapterDecision>) {
        require(
            values == values.sortedWith(
                compareBy(AdapterDecision::id, AdapterDecision::version),
            ),
        ) {
            "adapterDecisions는 ID와 version 순서여야 합니다."
        }
        values.forEach { value ->
            require(
                value.evidence == value.evidence.sortedWith(
                    compareBy(SourceEvidence::relativePath, SourceEvidence::line)
                        .thenBy(SourceEvidence::detail),
                ) &&
                    value.changes == value.changes.sorted() &&
                    value.remainingLimitations ==
                    value.remainingLimitations.sorted(),
            ) {
                "adapterDecision의 배열 순서가 canonical이 아닙니다."
            }
        }
    }

    private fun validateCompatibilitySchema(value: CompatibilityReport) {
        value.validate()
        require(value.manifestVersion == null || value.manifestVersion in 2..3) {
            "manifestVersion 범위가 올바르지 않습니다."
        }
        require(value.extensionName == null || value.extensionName.isNotBlank()) {
            "extensionName은 비어 있을 수 없습니다."
        }
        require(value.extensionVersion == null || value.extensionVersion.isNotBlank()) {
            "extensionVersion은 비어 있을 수 없습니다."
        }
        listOf(
            value.manifestKeyExtensionId,
            value.originalExtensionId,
            value.convertedExtensionId,
        ).filterNotNull().forEach {
            require(EXTENSION_ID.matches(it)) { "extension ID 형식이 올바르지 않습니다." }
        }
        require(
            (value.declaredPermissions + value.usedApis + value.unsupportedApis)
                .all(String::isNotBlank),
        ) {
            "compatibility 문자열 집합 항목은 비어 있을 수 없습니다."
        }
        require(value.issues.all { it.code.isNotBlank() && it.message.isNotBlank() }) {
            "compatibility issue code와 message는 비어 있을 수 없습니다."
        }
        value.adapterDecisions.forEach { decision ->
            require(ID.matches(decision.id) && decision.version > 0) {
                "adapterDecision 식별자가 올바르지 않습니다."
            }
            require(decision.evidence.all {
                it.relativePath.isNotBlank() &&
                    it.detail.isNotBlank() &&
                    (it.line == null || it.line > 0)
            }) {
                "adapterDecision evidence가 올바르지 않습니다."
            }
            require(
                (decision.changes + decision.remainingLimitations)
                    .all(String::isNotBlank),
            ) {
                "adapterDecision 문자열 항목은 비어 있을 수 없습니다."
            }
        }
    }

    private fun decodeAdapterDecision(element: JsonElement): AdapterDecision {
        val value = element.jsonObject
        value.requireKeys(ADAPTER_KEYS, "adapterDecision")
        val evidence = value.getValue("evidence").jsonArray.map { evidenceElement ->
            val evidence = evidenceElement.jsonObject
            evidence.requireKeys(EVIDENCE_KEYS, "sourceEvidence")
            SourceEvidence(
                relativePath = evidence.strictString("relativePath"),
                line = evidence.nullableInt("line")?.also {
                    require(it > 0) { "evidence line은 양수여야 합니다." }
                },
                detail = evidence.strictString("detail"),
            )
        }
        val decision = AdapterDecision(
            id = value.strictString("id").also {
                require(ID.matches(it)) { "adapter ID 형식이 올바르지 않습니다." }
            },
            version = value.strictPositiveInt("version"),
            risk = enumFromJson<AdapterRisk>(value.strictString("risk")),
            disposition = enumFromJson<AdapterDisposition>(
                value.strictString("disposition"),
            ),
            applied = value.strictBoolean("applied"),
            evidence = evidence,
            changes = value.strictStringList("changes"),
            remainingLimitations = value.strictStringList("remainingLimitations"),
        )
        require(evidence == evidence.sortedWith(
            compareBy(SourceEvidence::relativePath, SourceEvidence::line)
                .thenBy(SourceEvidence::detail),
        )) {
            "adapter evidence 순서가 canonical이 아닙니다."
        }
        require(decision.changes == decision.changes.sorted()) {
            "adapter changes 순서가 canonical이 아닙니다."
        }
        require(decision.remainingLimitations == decision.remainingLimitations.sorted()) {
            "adapter remainingLimitations 순서가 canonical이 아닙니다."
        }
        return decision
    }

    private fun parseCanonicalObject(name: String, bytes: ByteArray): JsonObject {
        require(bytes.size <= SixDraft.MAX_METADATA_SIZE) {
            "$name 크기가 제한을 초과합니다."
        }
        require(!(bytes.size >= 3 &&
            bytes[0] == 0xef.toByte() &&
            bytes[1] == 0xbb.toByte() &&
            bytes[2] == 0xbf.toByte())
        ) {
            "$name UTF-8 BOM은 허용되지 않습니다."
        }
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: Exception) {
            throw IllegalArgumentException("$name UTF-8이 올바르지 않습니다.", error)
        }
        requireValidUnicodeScalar(text, name)
        scanJsonLimits(text, name)
        val element = try {
            json.parseToJsonElement(text)
        } catch (error: Exception) {
            throw IllegalArgumentException("$name JSON을 읽을 수 없습니다.", error)
        }
        requireValidStrings(element, name, 0)
        val canonical = canonicalBytes(element)
        require(bytes.contentEquals(canonical)) {
            "$name JSON은 중복 키가 없고 canonical 형식이어야 합니다."
        }
        return element.jsonObject
    }

    private fun canonicalBytes(value: JsonElement): ByteArray =
        json.encodeToString(JsonElement.serializer(), canonicalize(value, 0))
            .encodeToByteArray()

    private fun canonicalize(value: JsonElement, depth: Int): JsonElement {
        require(depth <= MAX_JSON_DEPTH) {
            "JSON depth가 제한을 초과합니다."
        }
        return when (value) {
            is JsonObject -> JsonObject(
                value.toSortedMap().mapValues { canonicalize(it.value, depth + 1) },
            )
            is JsonArray -> JsonArray(value.map { canonicalize(it, depth + 1) })
            else -> value
        }
    }

    private fun objectOf(vararg entries: Pair<String, JsonElement>): JsonObject =
        JsonObject(entries.toMap().toSortedMap())

    private fun nullableString(value: String?): JsonElement =
        value?.let(::JsonPrimitive) ?: JsonNull

    private fun stringSet(values: Set<String>): JsonArray =
        stringList(values.sorted())

    private fun stringList(values: List<String>): JsonArray =
        JsonArray(values.map(::JsonPrimitive))

    private fun JsonObject.requireKeys(expected: Set<String>, name: String) {
        require(keys == expected) { "$name schema 항목이 정확하지 않습니다." }
    }

    private fun JsonObject.requireSchema(name: String, expected: Int) {
        require(getValue("schemaVersion").strictInt("schemaVersion") == expected) {
            "지원하지 않는 $name schemaVersion입니다."
        }
    }

    private fun JsonObject.strictString(name: String): String =
        getValue(name).jsonPrimitive.also {
            require(it.isString) { "$name 값은 JSON 문자열이어야 합니다." }
        }.content.also {
            require(it.isNotBlank()) { "$name 값은 비어 있을 수 없습니다." }
        }

    private fun JsonElement.strictInt(name: String): Int =
        jsonPrimitive.also {
            require(!it.isString && it.content.matches(Regex("-?(0|[1-9][0-9]*)"))) {
                "$name 값은 canonical JSON 정수여야 합니다."
            }
        }.int

    private fun JsonObject.strictPositiveInt(name: String): Int =
        getValue(name).strictInt(name).also {
            require(it > 0) { "$name 값은 양수여야 합니다." }
        }

    private fun JsonObject.strictBoolean(name: String): Boolean =
        getValue(name).jsonPrimitive.also {
            require(!it.isString && it.content in setOf("true", "false")) {
                "$name 값은 JSON boolean이어야 합니다."
            }
        }.boolean

    private fun JsonObject.nullableString(name: String): String? =
        getValue(name).let { element ->
            if (element === JsonNull) null else element.jsonPrimitive.also {
                require(it.isString) { "$name 값은 문자열 또는 null이어야 합니다." }
            }.content.also {
                require(it.isNotBlank()) { "$name 문자열은 비어 있을 수 없습니다." }
            }
        }

    private fun JsonObject.nullableInt(name: String): Int? =
        getValue(name).let { if (it === JsonNull) null else it.strictInt(name) }

    private fun JsonObject.strictSha256(name: String): String =
        strictString(name).also {
            require(SHA256.matches(it)) { "$name 값은 lowercase SHA-256이어야 합니다." }
        }

    private fun JsonObject.strictStringList(name: String): List<String> =
        getValue(name).jsonArray.map { element ->
            element.jsonPrimitive.also {
                require(it.isString) { "$name 항목은 JSON 문자열이어야 합니다." }
            }.content
        }.also {
            require(it.all(String::isNotBlank)) { "$name 항목은 비어 있을 수 없습니다." }
        }

    private fun JsonObject.strictStringSet(name: String): Set<String> {
        val values = strictStringList(name)
        require(values == values.sorted() && values.distinct().size == values.size) {
            "$name 배열은 정렬되고 중복이 없어야 합니다."
        }
        return values.toSet()
    }

    private fun JsonObject.canonicalBase64(name: String): ByteArray {
        val encoded = strictString(name)
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

    private fun requireValidStrings(
        value: JsonElement,
        name: String,
        depth: Int,
    ) {
        require(depth <= MAX_JSON_DEPTH) {
            "$name JSON depth가 제한을 초과합니다."
        }
        when (value) {
            is JsonObject -> value.forEach { (key, child) ->
                requireValidUnicodeScalar(key, name)
                requireValidStrings(child, name, depth + 1)
            }
            is JsonArray -> value.forEach {
                requireValidStrings(it, name, depth + 1)
            }
            is JsonPrimitive -> if (value.isString) {
                requireValidUnicodeScalar(value.content, name)
            }
        }
    }

    private fun scanJsonLimits(text: String, name: String) {
        var depth = 0
        var tokens = 0
        var keys = 0
        var inString = false
        var escaped = false
        var inScalar = false
        var closedString = false

        fun token() {
            tokens++
            require(tokens <= MAX_JSON_TOKENS) {
                "$name JSON token 수가 제한을 초과합니다."
            }
        }

        text.forEach { char ->
            if (inString) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> {
                        inString = false
                        closedString = true
                    }
                }
                return@forEach
            }
            when (char) {
                '"' -> {
                    if (!inScalar) token()
                    inScalar = false
                    inString = true
                    closedString = false
                }
                '{', '[' -> {
                    token()
                    depth++
                    require(depth <= MAX_JSON_DEPTH) {
                        "$name JSON depth가 제한을 초과합니다."
                    }
                    inScalar = false
                    closedString = false
                }
                '}', ']' -> {
                    token()
                    depth--
                    inScalar = false
                    closedString = false
                }
                ':' -> {
                    token()
                    if (closedString) {
                        keys++
                        require(keys <= MAX_JSON_KEYS) {
                            "$name JSON key 수가 제한을 초과합니다."
                        }
                    }
                    inScalar = false
                    closedString = false
                }
                ',' -> {
                    token()
                    inScalar = false
                    closedString = false
                }
                ' ', '\t', '\r', '\n' -> Unit
                else -> {
                    if (!inScalar) token()
                    inScalar = true
                    closedString = false
                }
            }
        }
    }

    private fun requireValidUnicodeScalar(value: String, name: String) {
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (Character.isHighSurrogate(char)) {
                require(
                    index + 1 < value.length &&
                        Character.isLowSurrogate(value[index + 1]),
                ) {
                    "$name 문자열에 잘못된 Unicode scalar가 있습니다."
                }
                index += 2
            } else {
                require(!Character.isLowSurrogate(char)) {
                    "$name 문자열에 잘못된 Unicode scalar가 있습니다."
                }
                index++
            }
        }
    }

    private fun Enum<*>.jsonValue(): String = name.lowercase().replace('_', '-')

    private inline fun <reified T> enumFromJson(value: String): T where T : Enum<T> =
        enumValues<T>().firstOrNull { it.jsonValue() == value }
            ?: throw IllegalArgumentException("지원하지 않는 enum 값입니다: $value")

    private val DRAFT_KEYS = setOf(
        "schemaVersion", "coreVersion", "adapterVersions", "sourceIdentity",
    )
    private val INPUT_KEYS = setOf(
        "schemaVersion", "inputKind", "originalSourceSha256",
        "browserProfileSha256", "originalExtensionId",
        "originalExtensionVersion", "sourceIdentity",
    )
    private val PLAN_KEYS = setOf(
        "schemaVersion", "selectedAdapters", "reportSourceSha256",
        "browserProfileSha256", "reportSha256", "planSha256",
    )
    private val SELECTED_ADAPTER_KEYS = setOf("id", "version")
    private val EXPECTED_KEYS = setOf(
        "schemaVersion", "sourceSha256", "reportSha256", "planSha256",
    )
    private val SIGNATURE_KEYS = setOf(
        "schemaVersion", "algorithm", "keyId", "publicKey", "signature",
    )
    private val COMPATIBILITY_KEYS = setOf(
        "schemaVersion", "status", "manifestVersion", "extensionName",
        "extensionVersion", "manifestKeyPresent", "manifestKeyExtensionId",
        "declaredPermissions", "usedApis", "unsupportedApis", "issues",
        "conversionState", "adapterDecisions", "originalExtensionId",
        "convertedExtensionId", "sourceSha256", "browserProfileSha256",
    )
    private val ISSUE_KEYS = setOf("code", "message", "blocking")
    private val ADAPTER_KEYS = setOf(
        "id", "version", "risk", "disposition", "applied", "evidence",
        "changes", "remainingLimitations",
    )
    private val EVIDENCE_KEYS = setOf("relativePath", "line", "detail")
    private const val MAX_JSON_DEPTH = 64
    private const val MAX_JSON_TOKENS = 100_000
    private const val MAX_JSON_KEYS = 20_000
}
