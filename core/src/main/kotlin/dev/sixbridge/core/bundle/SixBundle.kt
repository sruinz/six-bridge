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
import java.security.MessageDigest
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
    val wrapperSha256: String,
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
            var totalSize = 0L
            requiredEntries.forEach { name ->
                val entry = zip.getEntry(name)
                validateEntry(entry)
                totalSize += entry.size
            }
            require(totalSize <= MAX_TOTAL_SIZE) { "SixBundle 압축 해제 크기가 제한을 초과합니다." }
            val wrapper = readEntry(zip, "wrapper.apk", MAX_WRAPPER_SIZE)
            val extensionJson = readEntry(zip, "extension.json", MAX_METADATA_SIZE)
                .decodeToString()
            val compatibilityJson = readEntry(zip, "compatibility.json", MAX_METADATA_SIZE)
                .decodeToString()
            val extension = decodeExtension(extensionJson)
            require(sha256(wrapper) == extension.wrapperSha256) {
                "wrapper.apk SHA-256이 descriptor와 일치하지 않습니다."
            }
            SixBundleContents(
                wrapperApk = wrapper,
                extension = extension,
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
                "wrapperSha256" to JsonPrimitive(value.wrapperSha256),
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
        require(objectValue.requiredInt("schemaVersion") == SCHEMA_VERSION) {
            "지원하지 않는 extension.json schemaVersion입니다."
        }
        val descriptor = ExtensionDescriptor(
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
        require(EXTENSION_ID.matches(descriptor.extensionId)) {
            "올바르지 않은 extension ID입니다."
        }
        require(descriptor.sourceKind in SOURCE_KINDS) { "올바르지 않은 sourceKind입니다." }
        require(SHA256.matches(descriptor.crxSha256)) { "올바르지 않은 CRX SHA-256입니다." }
        require(SHA256.matches(descriptor.wrapperSha256)) {
            "올바르지 않은 wrapper SHA-256입니다."
        }
        require(SHA256.matches(descriptor.signerSha256)) {
            "올바르지 않은 signer SHA-256입니다."
        }
        require(descriptor.wrapperVersionCode > 0) { "wrapperVersionCode는 양수여야 합니다." }
        require(
            descriptor.wrapperPackage == "dev.sixbridge.ext.${descriptor.extensionId}",
        ) {
            "wrapper package가 extension ID와 일치하지 않습니다."
        }
        return descriptor
    }

    private fun decodeCompatibility(value: String): CompatibilityReport {
        val objectValue = json.parseToJsonElement(value).jsonObject
        require(objectValue.requiredInt("schemaVersion") == SCHEMA_VERSION) {
            "지원하지 않는 compatibility.json schemaVersion입니다."
        }
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
        getValue(name).jsonPrimitive.let { primitive ->
            require(primitive.isString && primitive.content.isNotBlank()) {
                "$name 필드는 비어 있지 않은 문자열이어야 합니다."
            }
            primitive.content
        }

    private fun JsonObject.requiredInt(name: String): Int =
        getValue(name).jsonPrimitive.int

    private fun JsonObject.stringSet(name: String): Set<String> =
        getValue(name).jsonArray.map { it.jsonPrimitive.content }.toSet()

    private fun stringArray(values: Set<String>) =
        JsonArray(values.sorted().map(::JsonPrimitive))

    private fun add(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name).apply { time = 0L })
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun validateEntry(entry: ZipEntry) {
        require(!entry.isDirectory && entry.size in 0..MAX_WRAPPER_SIZE) {
            "SixBundle 항목 크기가 제한을 초과합니다: ${entry.name}"
        }
        if (entry.compressedSize > 0 && entry.size > 0) {
            require(entry.size.toDouble() / entry.compressedSize <= MAX_COMPRESSION_RATIO) {
                "SixBundle 압축 비율이 제한을 초과합니다: ${entry.name}"
            }
        }
    }

    private fun readEntry(zip: ZipFile, name: String, limit: Long): ByteArray {
        val bytes = zip.getInputStream(zip.getEntry(name)).use { input ->
            input.readNBytes((limit + 1).toInt())
        }
        require(bytes.size.toLong() <= limit) { "SixBundle 항목이 너무 큽니다: $name" }
        return bytes
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private const val SCHEMA_VERSION = 1
    private const val MAX_METADATA_SIZE = 1024L * 1024
    private const val MAX_WRAPPER_SIZE = 128L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = MAX_WRAPPER_SIZE + (2 * MAX_METADATA_SIZE)
    private const val MAX_COMPRESSION_RATIO = 100.0
    private val EXTENSION_ID = Regex("[a-p]{32}")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val SOURCE_KINDS = setOf(
        "SOURCE_DIRECTORY",
        "ZIP",
        "CRX3",
        "CHROME_WEB_STORE",
    )
}
