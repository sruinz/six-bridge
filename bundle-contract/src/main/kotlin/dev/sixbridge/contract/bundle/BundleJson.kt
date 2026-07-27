@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.sixbridge.contract.bundle

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

internal val strictBundleJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
    explicitNulls = false
    encodeDefaults = true
}

object BundleJson {
    fun encodeExtension(value: ContractExtensionDescriptor): String =
        strictBundleJson.encodeToString(JsonObject.serializer(), extensionObject(value))

    fun encodeCompatibility(value: ContractCompatibilityReport): String =
        strictBundleJson.encodeToString(JsonObject.serializer(), compatibilityObject(value))

    fun decodeExtension(value: String): ContractExtensionDescriptor {
        val objectValue = strictBundleJson.parseToJsonElement(value).jsonObject
        requireExactKeys(objectValue, EXTENSION_KEYS, EXTENSION_REQUIRED_KEYS, "extension.json")
        val descriptor = ContractExtensionDescriptor(
            schemaVersion = objectValue.requiredInt("schemaVersion"),
            extensionId = objectValue.requiredString("extensionId"),
            name = objectValue.requiredString("name"),
            version = objectValue.requiredString("version"),
            sourceKind = objectValue.requiredString("sourceKind"),
            crxSha256 = objectValue.requiredString("crxSha256"),
            wrapperSha256 = objectValue.requiredString("wrapperSha256"),
            wrapperPackage = objectValue.requiredString("wrapperPackage"),
            wrapperVersionCode = objectValue.requiredInt("wrapperVersionCode"),
            signerSha256 = objectValue.requiredString("signerSha256"),
        )
        validateExtension(descriptor)
        return descriptor
    }

    fun decodeCompatibility(value: String): ContractCompatibilityReport {
        val objectValue = strictBundleJson.parseToJsonElement(value).jsonObject
        val schemaVersion = objectValue.requiredInt("schemaVersion")
        val keys = when (schemaVersion) {
            1 -> COMPATIBILITY_V1_KEYS
            2 -> COMPATIBILITY_V2_KEYS
            3 -> COMPATIBILITY_V3_KEYS
            4 -> COMPATIBILITY_V4_KEYS
            else -> throw IllegalArgumentException("지원하지 않는 compatibility.json schemaVersion입니다.")
        }
        requireExactKeys(
            objectValue,
            keys,
            if (schemaVersion == 4) COMPATIBILITY_V4_REQUIRED_KEYS else COMPATIBILITY_BASE_REQUIRED_KEYS,
            "compatibility.json",
        )
        val report = ContractCompatibilityReport(
            schemaVersion = schemaVersion,
            status = compatibilityStatus(objectValue.requiredString("status")),
            manifestVersion = objectValue.nullableInt("manifestVersion"),
            extensionName = objectValue.nullableString("extensionName"),
            extensionVersion = objectValue.nullableString("extensionVersion"),
            manifestKeyPresent = objectValue.requiredBoolean("manifestKeyPresent"),
            manifestKeyExtensionId = objectValue.nullableString("manifestKeyExtensionId"),
            declaredPermissions = objectValue.stringList("declaredPermissions"),
            usedApis = objectValue.stringList("usedApis"),
            unsupportedApis = objectValue.stringList("unsupportedApis"),
            issues = objectValue.issueList(),
            conversionState = if (schemaVersion == 1) {
                "passthrough"
            } else {
                objectValue.requiredString("conversionState")
            },
            adapterDecisions = if (schemaVersion == 1) {
                emptyList()
            } else {
                objectValue.adapterDecisionList()
            },
            originalExtensionId = if (schemaVersion == 1) null else objectValue.nullableString("originalExtensionId"),
            convertedExtensionId = if (schemaVersion == 1) null else objectValue.nullableString("convertedExtensionId"),
            sourceSha256 = if (schemaVersion < 3) null else objectValue.requiredSha256("sourceSha256"),
            browserProfileSha256 = if (schemaVersion < 3) null else objectValue.requiredSha256("browserProfileSha256"),
            originalSourceSha256 = if (schemaVersion < 4) null else objectValue.requiredSha256("originalSourceSha256"),
            planSha256 = if (schemaVersion < 4) null else objectValue.requiredSha256("planSha256"),
        )
        validateCompatibility(report)
        return report
    }

    fun validateExtension(value: ContractExtensionDescriptor) {
        require(value.schemaVersion == 1) { "지원하지 않는 extension.json schemaVersion입니다." }
        require(EXTENSION_ID.matches(value.extensionId)) { "올바르지 않은 extension ID입니다." }
        require(value.name.isNotBlank() && value.version.isNotBlank()) {
            "extension name과 version은 비어 있지 않아야 합니다."
        }
        require(value.sourceKind in SOURCE_KINDS) { "올바르지 않은 sourceKind입니다." }
        require(SHA256.matches(value.crxSha256)) { "올바르지 않은 CRX SHA-256입니다." }
        require(SHA256.matches(value.wrapperSha256)) { "올바르지 않은 wrapper SHA-256입니다." }
        require(SHA256.matches(value.signerSha256)) { "올바르지 않은 signer SHA-256입니다." }
        require(value.wrapperVersionCode > 0) { "wrapperVersionCode는 양수여야 합니다." }
        require(value.wrapperPackage == "dev.sixbridge.ext.${value.extensionId}") {
            "wrapper package가 extension ID와 일치하지 않습니다."
        }
    }

    fun validateCompatibility(value: ContractCompatibilityReport) {
        require(value.schemaVersion in 1..4) { "지원하지 않는 compatibility.json schemaVersion입니다." }
        require(value.conversionState in CONVERSION_STATES) { "지원하지 않는 conversionState입니다." }
        require(value.adapterDecisions.map { it.id to it.version }.toSet().size == value.adapterDecisions.size) {
            "어댑터 결정 ID와 version 조합은 중복될 수 없습니다."
        }
        value.adapterDecisions.forEach(::validateAdapterDecision)
        if (value.schemaVersion == 1) {
            require(value.conversionState == "passthrough" && value.adapterDecisions.isEmpty()) {
                "schema v1 compatibility에는 변환 결정이 있을 수 없습니다."
            }
            require(value.originalExtensionId == null && value.convertedExtensionId == null) {
                "schema v1 compatibility에는 extension ID 변환 정보가 있을 수 없습니다."
            }
            require(
                value.sourceSha256 == null && value.browserProfileSha256 == null &&
                    value.originalSourceSha256 == null && value.planSha256 == null,
            ) {
                "schema v1 compatibility에는 provenance proof가 있을 수 없습니다."
            }
        } else if (value.schemaVersion == 2) {
            require(
                value.sourceSha256 == null && value.browserProfileSha256 == null &&
                    value.originalSourceSha256 == null && value.planSha256 == null,
            ) {
                "schema v2 compatibility에는 provenance proof가 있을 수 없습니다."
            }
        } else if (value.schemaVersion == 3) {
            require(value.sourceSha256 != null && SHA256.matches(value.sourceSha256)) {
                "schema v3 compatibility에는 올바른 sourceSha256이 필요합니다."
            }
            require(value.browserProfileSha256 != null && SHA256.matches(value.browserProfileSha256)) {
                "schema v3 compatibility에는 올바른 browserProfileSha256이 필요합니다."
            }
            require(value.originalSourceSha256 == null && value.planSha256 == null) {
                "schema v3 compatibility에는 on-device provenance proof가 있을 수 없습니다."
            }
        } else {
            require(value.sourceSha256 != null && SHA256.matches(value.sourceSha256)) {
                "schema v4 compatibility에는 올바른 sourceSha256이 필요합니다."
            }
            require(value.browserProfileSha256 != null && SHA256.matches(value.browserProfileSha256)) {
                "schema v4 compatibility에는 올바른 browserProfileSha256이 필요합니다."
            }
            require(value.originalSourceSha256 != null && SHA256.matches(value.originalSourceSha256)) {
                "schema v4 compatibility에는 올바른 originalSourceSha256이 필요합니다."
            }
            require(value.planSha256 != null && SHA256.matches(value.planSha256)) {
                "schema v4 compatibility에는 올바른 planSha256이 필요합니다."
            }
        }
    }

    private fun extensionObject(value: ContractExtensionDescriptor) = JsonObject(
        linkedMapOf(
            "schemaVersion" to JsonPrimitive(value.schemaVersion),
            "extensionId" to JsonPrimitive(value.extensionId),
            "name" to JsonPrimitive(value.name),
            "version" to JsonPrimitive(value.version),
            "sourceKind" to JsonPrimitive(value.sourceKind),
            "crxSha256" to JsonPrimitive(value.crxSha256),
            "wrapperSha256" to JsonPrimitive(value.wrapperSha256),
            "wrapperPackage" to JsonPrimitive(value.wrapperPackage),
            "wrapperVersionCode" to JsonPrimitive(value.wrapperVersionCode),
            "signerSha256" to JsonPrimitive(value.signerSha256),
        ),
    )

    private fun compatibilityObject(value: ContractCompatibilityReport): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "schemaVersion" to JsonPrimitive(value.schemaVersion),
            "status" to JsonPrimitive(value.status.jsonValue()),
            "manifestKeyPresent" to JsonPrimitive(value.manifestKeyPresent),
            "declaredPermissions" to stringArray(value.declaredPermissions),
            "usedApis" to stringArray(value.usedApis),
            "unsupportedApis" to stringArray(value.unsupportedApis),
            "issues" to JsonArray(value.issues.map(::issueObject)),
        )
        fields.putNullable("manifestVersion", value.manifestVersion?.let(::JsonPrimitive))
        fields.putNullable("extensionName", value.extensionName?.let(::JsonPrimitive))
        fields.putNullable("extensionVersion", value.extensionVersion?.let(::JsonPrimitive))
        fields.putNullable("manifestKeyExtensionId", value.manifestKeyExtensionId?.let(::JsonPrimitive))
        if (value.schemaVersion >= 2) {
            fields["conversionState"] = JsonPrimitive(value.conversionState)
            fields["adapterDecisions"] = JsonArray(
                value.adapterDecisions
                    .sortedWith(compareBy(ContractAdapterDecision::id, ContractAdapterDecision::version))
                    .map(::adapterDecisionObject),
            )
            fields.putNullable("originalExtensionId", value.originalExtensionId?.let(::JsonPrimitive))
            fields.putNullable("convertedExtensionId", value.convertedExtensionId?.let(::JsonPrimitive))
        }
        if (value.schemaVersion >= 3) {
            fields["sourceSha256"] = JsonPrimitive(requireNotNull(value.sourceSha256))
            fields["browserProfileSha256"] = JsonPrimitive(requireNotNull(value.browserProfileSha256))
        }
        if (value.schemaVersion == 4) {
            fields["originalSourceSha256"] = JsonPrimitive(requireNotNull(value.originalSourceSha256))
            fields["planSha256"] = JsonPrimitive(requireNotNull(value.planSha256))
        }
        return JsonObject(fields)
    }

    private fun issueObject(value: ContractIssue) = JsonObject(
        linkedMapOf(
            "code" to JsonPrimitive(value.code),
            "message" to JsonPrimitive(value.message),
            "blocking" to JsonPrimitive(value.blocking),
        ),
    )

    private fun adapterDecisionObject(value: ContractAdapterDecision) = JsonObject(
        linkedMapOf(
            "id" to JsonPrimitive(value.id),
            "version" to JsonPrimitive(value.version),
            "risk" to JsonPrimitive(value.risk),
            "disposition" to JsonPrimitive(value.disposition),
            "applied" to JsonPrimitive(value.applied),
            "evidence" to JsonArray(
                value.evidence
                    .sortedWith(compareBy(ContractSourceEvidence::relativePath, ContractSourceEvidence::line)
                        .thenBy(ContractSourceEvidence::detail))
                    .map(::sourceEvidenceObject),
            ),
            "changes" to stringArray(value.changes),
            "remainingLimitations" to stringArray(value.remainingLimitations),
        ),
    )

    private fun sourceEvidenceObject(value: ContractSourceEvidence): JsonObject {
        val fields = linkedMapOf<String, JsonElement>(
            "relativePath" to JsonPrimitive(value.relativePath),
        )
        fields.putNullable("line", value.line?.let(::JsonPrimitive))
        fields["detail"] = JsonPrimitive(value.detail)
        return JsonObject(fields)
    }

    private fun validateAdapterDecision(value: ContractAdapterDecision) {
        require(value.id.isNotBlank() && value.version > 0) { "adapter decision ID와 version은 올바라야 합니다." }
        require(value.risk in ADAPTER_RISKS) { "지원하지 않는 adapter risk입니다." }
        require(value.disposition in ADAPTER_DISPOSITIONS) { "지원하지 않는 adapter disposition입니다." }
    }

    private fun JsonObject.issueList(): List<ContractIssue> = getValue("issues").jsonArray.map { element ->
        val issue = element.jsonObject
        requireExactKeys(issue, ISSUE_KEYS, ISSUE_KEYS, "compatibility issue")
        ContractIssue(
            code = issue.requiredString("code"),
            message = issue.requiredString("message"),
            blocking = issue.requiredBoolean("blocking"),
        )
    }

    private fun JsonObject.adapterDecisionList(): List<ContractAdapterDecision> =
        getValue("adapterDecisions").jsonArray.map { element ->
            val decision = element.jsonObject
            requireExactKeys(decision, ADAPTER_KEYS, ADAPTER_KEYS, "adapter decision")
            ContractAdapterDecision(
                id = decision.requiredString("id"),
                version = decision.requiredInt("version"),
                risk = decision.requiredString("risk"),
                disposition = decision.requiredString("disposition"),
                applied = decision.requiredBoolean("applied"),
                evidence = decision.sourceEvidenceList(),
                changes = decision.stringList("changes"),
                remainingLimitations = decision.stringList("remainingLimitations"),
            )
        }

    private fun JsonObject.sourceEvidenceList(): List<ContractSourceEvidence> =
        getValue("evidence").jsonArray.map { element ->
            val evidence = element.jsonObject
            requireExactKeys(evidence, EVIDENCE_KEYS, EVIDENCE_REQUIRED_KEYS, "adapter evidence")
            ContractSourceEvidence(
                relativePath = evidence.requiredString("relativePath"),
                line = evidence.nullableInt("line"),
                detail = evidence.requiredString("detail"),
            )
        }

    private fun JsonObject.requiredString(name: String): String = getValue(name).jsonPrimitive.let { primitive ->
        require(primitive.isString && primitive.content.isNotBlank()) {
            "$name 필드는 비어 있지 않은 문자열이어야 합니다."
        }
        primitive.content
    }

    private fun JsonObject.requiredSha256(name: String): String = requiredString(name).also {
        require(SHA256.matches(it)) { "$name 필드는 소문자 SHA-256이어야 합니다." }
    }

    private fun JsonObject.requiredInt(name: String): Int = getValue(name).jsonPrimitive.let { primitive ->
        require(!primitive.isString) { "$name 필드는 정수여야 합니다." }
        primitive.int
    }

    private fun JsonObject.nullableInt(name: String): Int? = nullableElement(name)?.let { element ->
        val primitive = element.jsonPrimitive
        require(!primitive.isString) { "$name 필드는 정수여야 합니다." }
        primitive.int
    }

    private fun JsonObject.requiredBoolean(name: String): Boolean = getValue(name).jsonPrimitive.let { primitive ->
        require(!primitive.isString) { "$name 필드는 boolean이어야 합니다." }
        primitive.boolean
    }

    private fun JsonObject.nullableString(name: String): String? = nullableElement(name)?.jsonPrimitive?.let { primitive ->
        require(primitive.isString && primitive.content.isNotBlank()) {
            "$name 필드는 비어 있지 않은 문자열이어야 합니다."
        }
        primitive.content
    }

    private fun JsonObject.nullableElement(name: String): JsonElement? = get(name)?.takeUnless { it is JsonNull }

    private fun JsonObject.stringList(name: String): List<String> = getValue(name).jsonArray.map { element ->
        val primitive = element.jsonPrimitive
        require(primitive.isString) { "$name 항목은 문자열이어야 합니다." }
        primitive.content
    }

    private fun requireExactKeys(
        value: JsonObject,
        allowed: Set<String>,
        required: Set<String>,
        scope: String,
    ) {
        require(value.keys.all { it in allowed }) { "${scope}에 알 수 없는 JSON key가 있습니다." }
        require(required.all { it in value }) { "${scope}에 필수 JSON key가 없습니다." }
    }

    private fun compatibilityStatus(value: String): ContractCompatibilityStatus =
        ContractCompatibilityStatus.entries.firstOrNull { it.jsonValue() == value }
            ?: throw IllegalArgumentException("지원하지 않는 compatibility status입니다.")

    private fun ContractCompatibilityStatus.jsonValue(): String = name.lowercase()

    private fun stringArray(values: List<String>): JsonArray = JsonArray(values.sorted().map(::JsonPrimitive))

    private fun MutableMap<String, JsonElement>.putNullable(name: String, value: JsonElement?) {
        if (value != null) put(name, value)
    }

    private val EXTENSION_ID = Regex("[a-p]{32}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val SOURCE_KINDS = setOf("SOURCE_DIRECTORY", "ZIP", "CRX3", "CHROME_WEB_STORE")
    private val CONVERSION_STATES = setOf(
        "passthrough", "automatic", "requires-approval", "converted", "unavailable",
    )
    private val ADAPTER_RISKS = setOf("safe", "behavior-change")
    private val ADAPTER_DISPOSITIONS = setOf("automatic", "requires-approval", "unavailable")
    private val EXTENSION_KEYS = setOf(
        "schemaVersion", "extensionId", "name", "version", "sourceKind", "crxSha256",
        "wrapperSha256", "wrapperPackage", "wrapperVersionCode", "signerSha256",
    )
    private val EXTENSION_REQUIRED_KEYS = EXTENSION_KEYS
    private val COMPATIBILITY_BASE_REQUIRED_KEYS = setOf(
        "schemaVersion", "status", "manifestKeyPresent", "declaredPermissions", "usedApis",
        "unsupportedApis", "issues",
    )
    private val COMPATIBILITY_V1_KEYS = COMPATIBILITY_BASE_REQUIRED_KEYS + setOf(
        "manifestVersion", "extensionName", "extensionVersion", "manifestKeyExtensionId",
    )
    private val COMPATIBILITY_V2_KEYS = COMPATIBILITY_V1_KEYS + setOf(
        "conversionState", "adapterDecisions", "originalExtensionId", "convertedExtensionId",
    )
    private val COMPATIBILITY_V3_KEYS = COMPATIBILITY_V2_KEYS + setOf(
        "sourceSha256", "browserProfileSha256",
    )
    private val COMPATIBILITY_V4_KEYS = COMPATIBILITY_V3_KEYS + setOf(
        "originalSourceSha256", "planSha256",
    )
    private val COMPATIBILITY_V4_REQUIRED_KEYS = COMPATIBILITY_BASE_REQUIRED_KEYS + setOf(
        "sourceSha256", "browserProfileSha256", "originalSourceSha256", "planSha256",
    )
    private val ISSUE_KEYS = setOf("code", "message", "blocking")
    private val ADAPTER_KEYS = setOf(
        "id", "version", "risk", "disposition", "applied", "evidence", "changes",
        "remainingLimitations",
    )
    private val EVIDENCE_KEYS = setOf("relativePath", "line", "detail")
    private val EVIDENCE_REQUIRED_KEYS = setOf("relativePath", "detail")
}
