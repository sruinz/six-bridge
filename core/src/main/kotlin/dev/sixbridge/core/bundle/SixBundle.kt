package dev.sixbridge.core.bundle

import dev.sixbridge.core.compat.CompatibilityIssue
import dev.sixbridge.core.compat.CompatibilityReport
import dev.sixbridge.core.compat.CompatibilityStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.readBytes

data class ExtensionDescriptor(
    val extensionId: String,
    val name: String,
    val version: String,
    val sourceKind: String,
    val crxSha256: String,
    val wrapperPackage: String,
    val wrapperVersionCode: Int,
    val signerSha256: String,
)

data class SixBundleContents(
    val wrapperApk: ByteArray,
    val extension: ExtensionDescriptor,
    val compatibility: CompatibilityReport,
)

class InvalidSixBundleException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)

object SixBundle {
    private val json = Json { prettyPrint = true }
    private val requiredEntries = setOf(
        "wrapper.apk",
        "extension.json",
        "compatibility.json",
    )

    fun write(
        output: Path,
        wrapperApk: Path,
        extension: ExtensionDescriptor,
        compatibility: CompatibilityReport,
    ) {
        Files.createDirectories(output.toAbsolutePath().parent)
        ZipOutputStream(Files.newOutputStream(output)).use { zip ->
            add(zip, "wrapper.apk", wrapperApk.readBytes())
            add(zip, "extension.json", encodeExtension(extension).toByteArray())
            add(zip, "compatibility.json", encodeCompatibility(compatibility).toByteArray())
        }
    }

    fun read(input: Path): SixBundleContents = try {
        ZipFile(input.toFile()).use { zip ->
            val names = zip.entries().asSequence().map { it.name }.toSet()
            if (names != requiredEntries || zip.size() != requiredEntries.size) {
                throw InvalidSixBundleException(
                    "SixBundle 항목은 ${requiredEntries.sorted()}만 포함해야 합니다. 감지: ${names.sorted()}",
                )
            }
            val wrapper = zip.getInputStream(zip.getEntry("wrapper.apk")).readBytes()
            val extensionJson = zip.getInputStream(zip.getEntry("extension.json"))
                .bufferedReader()
                .readText()
            val compatibilityJson = zip.getInputStream(zip.getEntry("compatibility.json"))
                .bufferedReader()
                .readText()
            SixBundleContents(
                wrapperApk = wrapper,
                extension = decodeExtension(extensionJson),
                compatibility = decodeCompatibility(compatibilityJson),
            )
        }
    } catch (error: InvalidSixBundleException) {
        throw error
    } catch (error: Exception) {
        throw InvalidSixBundleException("SixBundle을 읽을 수 없습니다: ${error.message}", error)
    }

    fun encodeExtension(value: ExtensionDescriptor): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "extensionId" to JsonPrimitive(value.extensionId),
                "name" to JsonPrimitive(value.name),
                "version" to JsonPrimitive(value.version),
                "sourceKind" to JsonPrimitive(value.sourceKind),
                "crxSha256" to JsonPrimitive(value.crxSha256),
                "wrapperPackage" to JsonPrimitive(value.wrapperPackage),
                "wrapperVersionCode" to JsonPrimitive(value.wrapperVersionCode),
                "signerSha256" to JsonPrimitive(value.signerSha256),
            ),
        ),
    )

    fun encodeCompatibility(value: CompatibilityReport): String = json.encodeToString(
        JsonObject.serializer(),
        JsonObject(
            linkedMapOf(
                "schemaVersion" to JsonPrimitive(1),
                "status" to JsonPrimitive(value.status.name.lowercase()),
                "manifestVersion" to (
                    value.manifestVersion?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull
                    ),
                "extensionName" to (
                    value.extensionName?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull
                    ),
                "extensionVersion" to (
                    value.extensionVersion?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull
                    ),
                "manifestKeyPresent" to JsonPrimitive(value.manifestKeyPresent),
                "manifestKeyExtensionId" to (
                    value.manifestKeyExtensionId?.let(::JsonPrimitive)
                        ?: kotlinx.serialization.json.JsonNull
                    ),
                "declaredPermissions" to stringArray(value.declaredPermissions),
                "usedApis" to stringArray(value.usedApis),
                "unsupportedApis" to stringArray(value.unsupportedApis),
                "issues" to JsonArray(
                    value.issues.map { issue ->
                        JsonObject(
                            linkedMapOf(
                                "code" to JsonPrimitive(issue.code),
                                "message" to JsonPrimitive(issue.message),
                                "blocking" to JsonPrimitive(issue.blocking),
                            ),
                        )
                    },
                ),
            ),
        ),
    )

    private fun decodeExtension(value: String): ExtensionDescriptor {
        val objectValue = json.parseToJsonElement(value).jsonObject
        return ExtensionDescriptor(
            extensionId = objectValue.requiredString("extensionId"),
            name = objectValue.requiredString("name"),
            version = objectValue.requiredString("version"),
            sourceKind = objectValue.requiredString("sourceKind"),
            crxSha256 = objectValue.requiredString("crxSha256"),
            wrapperPackage = objectValue.requiredString("wrapperPackage"),
            wrapperVersionCode = objectValue.getValue("wrapperVersionCode").jsonPrimitive.int,
            signerSha256 = objectValue.requiredString("signerSha256"),
        )
    }

    private fun decodeCompatibility(value: String): CompatibilityReport {
        val objectValue = json.parseToJsonElement(value).jsonObject
        return CompatibilityReport(
            status = CompatibilityStatus.valueOf(
                objectValue.requiredString("status").uppercase(),
            ),
            manifestVersion = objectValue["manifestVersion"]
                ?.jsonPrimitive
                ?.content
                ?.toIntOrNull(),
            extensionName = objectValue["extensionName"]
                ?.jsonPrimitive
                ?.content
                ?.takeUnless { it == "null" },
            extensionVersion = objectValue["extensionVersion"]
                ?.jsonPrimitive
                ?.content
                ?.takeUnless { it == "null" },
            manifestKeyPresent = objectValue["manifestKeyPresent"]
                ?.jsonPrimitive
                ?.boolean
                ?: false,
            manifestKeyExtensionId = objectValue["manifestKeyExtensionId"]
                ?.jsonPrimitive
                ?.content
                ?.takeUnless { it == "null" },
            declaredPermissions = objectValue.stringSet("declaredPermissions"),
            usedApis = objectValue.stringSet("usedApis"),
            unsupportedApis = objectValue.stringSet("unsupportedApis"),
            issues = objectValue.getValue("issues").jsonArray.map { item ->
                val issue = item.jsonObject
                CompatibilityIssue(
                    code = issue.requiredString("code"),
                    message = issue.requiredString("message"),
                    blocking = issue.getValue("blocking").jsonPrimitive.boolean,
                )
            },
        )
    }

    private fun JsonObject.requiredString(name: String): String =
        getValue(name).jsonPrimitive.content

    private fun JsonObject.stringSet(name: String): Set<String> =
        getValue(name).jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun stringArray(values: Set<String>) =
        JsonArray(values.sorted().map(::JsonPrimitive))

    private fun add(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        zip.write(bytes)
        zip.closeEntry()
    }
}
