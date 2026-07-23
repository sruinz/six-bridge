package dev.sixbridge.core.compat

import dev.sixbridge.core.profile.BrowserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

class CompatibilityAnalyzer(
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    fun analyze(extensionRoot: Path, profile: BrowserProfile): CompatibilityReport {
        val manifestPath = extensionRoot.resolve("manifest.json")
        if (!manifestPath.isRegularFile()) {
            return blocked("manifest_missing", "manifest.json 파일을 찾을 수 없습니다.")
        }

        val manifest = try {
            json.parseToJsonElement(manifestPath.readText()) as? JsonObject
                ?: return blocked("manifest_invalid", "manifest.json의 루트가 객체가 아닙니다.")
        } catch (error: Exception) {
            return blocked(
                "manifest_invalid",
                "manifest.json을 파싱할 수 없습니다: ${error.message}",
            )
        }

        val manifestVersion = manifest["manifest_version"]?.jsonPrimitive?.intOrNull
        val name = manifest["name"]?.jsonPrimitive?.content
        val version = manifest["version"]?.jsonPrimitive?.content
        val manifestKey = manifest["key"]?.jsonPrimitive?.content
        val manifestKeyExtensionId = manifestKey?.let(::extensionIdFromManifestKey)
        val permissions = (
            readStringSet(manifest["permissions"]) +
                readStringSet(manifest["optional_permissions"])
            ).toSet()
        val apiCalls = scanApiCalls(extensionRoot)
        val usedApis = (
            apiCalls + permissions.filter { it in KNOWN_API_NAMESPACES }
            ).toSortedSet()
        val unsupported = (usedApis - profile.supportedApis).toSortedSet()
        val issues = mutableListOf<CompatibilityIssue>()

        if (!profile.isSupportedBaseline) {
            issues += CompatibilityIssue(
                code = "browser_profile_unknown",
                message = "삼성 브라우저 ${profile.versionName}의 래퍼 계약은 검증되지 않았습니다.",
                blocking = true,
            )
            return report(
                CompatibilityStatus.UNKNOWN,
                manifestVersion,
                name,
                version,
                manifestKey,
                manifestKeyExtensionId,
                permissions,
                usedApis,
                unsupported,
                issues,
            )
        }

        if (manifestVersion != 3) {
            issues += CompatibilityIssue(
                code = "manifest_version_unsupported",
                message = "Manifest V3만 PoC 지원 대상입니다. 감지 값: $manifestVersion",
                blocking = true,
            )
            return report(
                CompatibilityStatus.BLOCKED,
                manifestVersion,
                name,
                version,
                manifestKey,
                manifestKeyExtensionId,
                permissions,
                usedApis,
                unsupported,
                issues,
            )
        }

        unsupported.forEach { api ->
            issues += CompatibilityIssue(
                code = "unsupported_api",
                message = "chrome.$api API는 이 삼성 브라우저 프로필에서 확인되지 않았습니다.",
                blocking = false,
            )
        }
        if (manifestKey != null && manifestKeyExtensionId == null) {
            issues += CompatibilityIssue(
                code = "manifest_key_invalid",
                message = "manifest key가 올바른 Base64 공개 키가 아닙니다.",
                blocking = false,
            )
        }

        val status = if (unsupported.isEmpty()) {
            CompatibilityStatus.COMPATIBLE
        } else {
            CompatibilityStatus.PARTIAL
        }
        return report(
            status,
            manifestVersion,
            name,
            version,
            manifestKey,
            manifestKeyExtensionId,
            permissions,
            usedApis,
            unsupported,
            issues,
        )
    }

    private fun scanApiCalls(root: Path): Set<String> {
        val found = mutableSetOf<String>()
        Files.walk(root).use { paths ->
            paths.filter { path ->
                path.isRegularFile() && path.extension.lowercase() in SCRIPT_EXTENSIONS
            }.forEach { path ->
                val source = runCatching { path.readText() }.getOrDefault("")
                API_CALL_REGEX.findAll(source).forEach { match ->
                    found += match.groupValues[1]
                }
                if (NATIVE_MESSAGING_REGEX.containsMatchIn(source)) {
                    found += "nativeMessaging"
                }
            }
        }
        return found
    }

    private fun readStringSet(element: kotlinx.serialization.json.JsonElement?): Set<String> =
        (element as? JsonArray)
            ?.mapNotNull { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.toSet()
            .orEmpty()

    private fun blocked(code: String, message: String) = CompatibilityReport(
        status = CompatibilityStatus.BLOCKED,
        manifestVersion = null,
        extensionName = null,
        extensionVersion = null,
        declaredPermissions = emptySet(),
        usedApis = emptySet(),
        unsupportedApis = emptySet(),
        issues = listOf(CompatibilityIssue(code, message, blocking = true)),
    )

    private fun report(
        status: CompatibilityStatus,
        manifestVersion: Int?,
        name: String?,
        version: String?,
        manifestKey: String?,
        manifestKeyExtensionId: String?,
        permissions: Set<String>,
        usedApis: Set<String>,
        unsupported: Set<String>,
        issues: List<CompatibilityIssue>,
    ) = CompatibilityReport(
        status = status,
        manifestVersion = manifestVersion,
        extensionName = name,
        extensionVersion = version,
        manifestKeyPresent = manifestKey != null,
        manifestKeyExtensionId = manifestKeyExtensionId,
        declaredPermissions = permissions.toSortedSet(),
        usedApis = usedApis,
        unsupportedApis = unsupported,
        issues = issues,
    )

    private fun extensionIdFromManifestKey(value: String): String? = runCatching {
        val publicKey = Base64.getDecoder().decode(value)
        val specification = X509EncodedKeySpec(publicKey)
        val recognized = listOf("RSA", "EC").any { algorithm ->
            runCatching {
                KeyFactory.getInstance(algorithm).generatePublic(specification)
            }.isSuccess
        }
        require(recognized) { "알 수 없는 공개 키 형식" }
        val idBytes = MessageDigest.getInstance("SHA-256")
            .digest(publicKey)
            .copyOfRange(0, 16)
        buildString(32) {
            idBytes.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(('a'.code + (unsigned ushr 4)).toChar())
                append(('a'.code + (unsigned and 0x0f)).toChar())
            }
        }
    }.getOrNull()

    companion object {
        private val SCRIPT_EXTENSIONS = setOf("js", "mjs", "cjs", "html")
        private val API_CALL_REGEX =
            Regex("""(?:chrome|browser)\.([A-Za-z][A-Za-z0-9_]*)""")
        private val NATIVE_MESSAGING_REGEX =
            Regex("""(?:sendNativeMessage|connectNative)\s*\(""")
        private val KNOWN_API_NAMESPACES = setOf(
            "action",
            "alarms",
            "bookmarks",
            "browserAction",
            "commands",
            "contentSettings",
            "contextMenus",
            "cookies",
            "declarativeContent",
            "declarativeNetRequest",
            "downloads",
            "history",
            "i18n",
            "identity",
            "idle",
            "management",
            "nativeMessaging",
            "notifications",
            "offscreen",
            "pageAction",
            "permissions",
            "privacy",
            "proxy",
            "runtime",
            "scripting",
            "search",
            "sidePanel",
            "storage",
            "tabGroups",
            "tabs",
            "tts",
            "userScripts",
            "webNavigation",
            "webRequest",
            "windows",
        )
    }
}
