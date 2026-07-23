package dev.sixbridge.core.profile

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

object BrowserProfileJson {
    private val json = Json { prettyPrint = true }

    fun encode(profile: BrowserProfile): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
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
                "sourceApkSignerSha256" to JsonPrimitive(
                    profile.sourceApkSignerSha256,
                ),
                "profileDifferences" to JsonArray(
                    profile.profileDifferences.map(::JsonPrimitive),
                ),
                "support" to JsonPrimitive(profile.support.name.lowercase()),
            ),
        ),
    )

    fun decode(value: String): BrowserProfile {
        val root = json.parseToJsonElement(value).jsonObject
        require(root.getValue("schemaVersion").jsonPrimitive.int == 1) {
            "지원하지 않는 browser profile schemaVersion입니다."
        }
        val contract = root.getValue("wrapperContract").jsonObject
        val supportedApis = root.getValue("supportedApis")
            .jsonArray
            .map { it.jsonPrimitive.content }
            .toSet()
        return BrowserProfile(
            browserPackage = root.string("browserPackage"),
            versionName = root.string("versionName"),
            versionCode = root.getValue("versionCode").jsonPrimitive.long,
            majorVersion = root.getValue("majorVersion").jsonPrimitive.int,
            supportedApis = supportedApis,
            detectedApis = root["detectedApis"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?.toSet()
                ?: supportedApis,
            wrapperContract = WrapperContract(
                discoveryUri = contract.boolean("discoveryUri"),
                extensionMetadata = contract.boolean("extensionMetadata"),
                contentProvider = contract.boolean("contentProvider"),
                devExtensionPath = contract.boolean("devExtensionPath"),
            ),
            sourceApkSha256 = root.string("sourceApkSha256"),
            sourceApkSignerSha256 = root["sourceApkSignerSha256"]
                ?.jsonPrimitive
                ?.content
                .orEmpty(),
            profileDifferences = root["profileDifferences"]
                ?.jsonArray
                ?.map { it.jsonPrimitive.content }
                .orEmpty(),
        )
    }

    private fun JsonObject.string(name: String): String =
        getValue(name).jsonPrimitive.content

    private fun JsonObject.boolean(name: String): Boolean =
        getValue(name).jsonPrimitive.boolean
}
