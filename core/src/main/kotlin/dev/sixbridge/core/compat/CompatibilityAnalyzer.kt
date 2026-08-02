package dev.sixbridge.core.compat

import dev.sixbridge.core.profile.BrowserProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import kotlin.jvm.JvmSynthetic

class CompatibilityAnalyzer(
    private val control: AnalysisControl = AnalysisControl(),
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    @JvmSynthetic
    internal fun withControl(control: AnalysisControl): CompatibilityAnalyzer =
        CompatibilityAnalyzer(control, json)

    fun analyze(extensionRoot: Path, profile: BrowserProfile): CompatibilityReport {
        return StableExtensionAnalysis.captureLive(extensionRoot, control).use { stable ->
            val report = analyzeStable(
                stable.root,
                profile,
                stable.sourceSha256,
            )
            stable.verifyLiveSource()
            report
        }
    }

    @JvmSynthetic
    internal fun analyzeStable(
        extensionRoot: Path,
        profile: BrowserProfile,
        sourceSha256: String?,
        ledger: AnalysisBudgetLedger = AnalysisBudgetLedger(control.policy),
    ): CompatibilityReport {
        profile.validate()
        val report = analyzeUnsealed(extensionRoot, profile, ledger)
        return report.copy(
            sourceSha256 = sourceSha256,
            browserProfileSha256 = profile.canonicalSha256(),
        ).also(CompatibilityReport::validate)
    }

    private fun analyzeUnsealed(
        extensionRoot: Path,
        profile: BrowserProfile,
        ledger: AnalysisBudgetLedger,
    ): CompatibilityReport {
        val manifestPath = extensionRoot.resolve("manifest.json")
        if (!manifestPath.isRegularFile()) {
            return blocked("manifest_missing", "manifest.json 파일을 찾을 수 없습니다.")
        }
        val manifestBytes = Files.size(manifestPath)
        if (manifestBytes > control.policy.maxManifestBytes) {
            return analysisBudgetExceededReport(
                manifestVersion = null,
                name = null,
                version = null,
                permissions = emptySet(),
                exceeded = AnalysisInventoryResult.Exceeded(
                    metric = AnalysisBudgetMetric.MANIFEST_BYTES,
                    actual = manifestBytes,
                    limit = control.policy.maxManifestBytes,
                ),
            )
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

        val manifestVersion = (manifest["manifest_version"] as? JsonPrimitive)?.intOrNull
        val name = manifest.stringOrNull("name")
        val version = manifest.stringOrNull("version")
        val manifestKey = manifest.stringOrNull("key")
        val manifestKeyExtensionId = manifestKey?.let(::extensionIdFromManifestKey)
        val permissions = (
            readStringSet(manifest["permissions"]) +
                readStringSet(manifest["optional_permissions"])
            ).toSet()
        when (val inventory = AnalysisInventory.inspect(
            extensionRoot,
            ledger,
            control::checkCancelled,
        )) {
            AnalysisInventoryResult.Allowed -> Unit
            is AnalysisInventoryResult.Exceeded -> return analysisBudgetExceededReport(
                exceeded = inventory,
                manifestVersion = manifestVersion,
                name = name,
                version = version,
                permissions = permissions,
            )
        }
        val apiScan = try {
            scanApiCalls(extensionRoot, manifest, ledger)
        } catch (error: AnalysisBudgetExceededException) {
            return analysisBudgetExceededReport(
                error.exceeded,
                manifestVersion,
                name,
                version,
                permissions,
            )
        }
        val usedApis = (
            apiScan.namespaces + permissions.filter { it in KNOWN_API_NAMESPACES }
            ).toSortedSet()
        val unsupported = (usedApis - profile.supportedApis).toSortedSet()
        val issues = mutableListOf<CompatibilityIssue>()

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
        if (name.isNullOrBlank() || version.isNullOrBlank()) {
            issues += CompatibilityIssue(
                code = "manifest_identity_invalid",
                message = "manifest name과 version은 비어 있지 않은 문자열이어야 합니다.",
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
        val resourceIssues = validateResources(extensionRoot, manifest)
        if (resourceIssues.isNotEmpty()) {
            issues += resourceIssues
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
        if (apiScan.dynamicUsage) {
            issues += CompatibilityIssue(
                code = "api_usage_dynamic",
                message = "계산된 namespace 또는 browser 객체 alias는 안전하게 판정할 수 없습니다.",
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

    private fun scanApiCalls(
        root: Path,
        manifest: JsonObject,
        ledger: AnalysisBudgetLedger,
    ): ApiScan {
        val found = mutableSetOf<String>()
        val runtimeClosure = RuntimeResourceIndex.create(
            root,
            manifest,
            control,
            ledger,
        )
        var dynamicUsage = runtimeClosure.unresolvedDependency
        Files.walk(root).use { paths ->
            paths.filter { path ->
                path.isRegularFile() &&
                    path.extension.lowercase() in SCRIPT_EXTENSIONS &&
                    (
                        ".test." !in path.fileName.toString().lowercase() ||
                            path.toAbsolutePath().normalize() in runtimeClosure.resources
                    )
            }.forEach { path ->
                control.checkCancelled()
                ledger.reserveText(path, Files.size(path))
                val source = try {
                    path.readText()
                } catch (error: java.util.concurrent.CancellationException) {
                    throw error
                } catch (error: AnalysisBudgetExceededException) {
                    throw error
                } catch (_: Exception) {
                    ""
                }
                control.checkCancelled()
                val apiSource = if (path.extension.lowercase() == "html") {
                    RuntimeSourceScanner.scanHtmlScripts(source).inlineSource
                } else {
                    source
                }
                val sourceWithoutComments = JavaScriptLexicalMask.mask(
                    apiSource,
                    maskLiterals = false,
                    checkCancelled = control::checkCancelled,
                )
                control.checkCancelled()
                val codeOnly = JavaScriptLexicalMask.mask(
                    apiSource,
                    maskLiterals = true,
                    checkCancelled = control::checkCancelled,
                )
                control.checkCancelled()
                val consumedRoots = mutableSetOf<Int>()
                val aliases = StaticExtensionApiAliasResolver().resolve(
                    sourceWithoutComments,
                    codeOnly,
                    control::checkCancelled,
                )
                found += aliases.namespaces
                consumedRoots += aliases.consumedRootOffsets
                dynamicUsage = dynamicUsage || aliases.dynamicUsage
                control.checkCancelled()
                API_CALL_REGEX.findAll(sourceWithoutComments)
                    .filter { it.startsInCode(codeOnly) }
                    .forEach { match ->
                    found += match.groupValues[1]
                    consumedRoots += ROOT_TOKEN.find(match.value)!!.range.first +
                        match.range.first
                }
                control.checkCancelled()
                BRACKET_API_REGEX.findAll(sourceWithoutComments)
                    .filter { it.startsInCode(codeOnly) }
                    .forEach { match ->
                    found += match.groupValues[1]
                    consumedRoots += ROOT_TOKEN.find(match.value)!!.range.first +
                        match.range.first
                }
                control.checkCancelled()
                control.checkCancelled()
                if (DYNAMIC_BRACKET_REGEX.findAll(sourceWithoutComments)
                        .any { it.startsInCode(codeOnly) }
                ) {
                    dynamicUsage = true
                }
                control.checkCancelled()
                ROOT_SCHEME_LITERAL_REGEX.findAll(codeOnly).forEach { match ->
                    consumedRoots += ROOT_TOKEN.find(match.value)!!.range.first +
                        match.range.first
                }
                control.checkCancelled()
                if (ROOT_TOKEN.findAll(codeOnly).any {
                        it.range.first !in consumedRoots
                    }
                ) {
                    dynamicUsage = true
                }
                control.checkCancelled()
                if (NATIVE_MESSAGING_REGEX.findAll(sourceWithoutComments)
                        .any { it.startsInCode(codeOnly) }
                ) {
                    found += "nativeMessaging"
                }
            }
        }
        return ApiScan(found, dynamicUsage)
    }

    private fun validateResources(
        root: Path,
        manifest: JsonObject,
    ): List<CompatibilityIssue> {
        val declared = mutableListOf<Pair<String, kotlinx.serialization.json.JsonElement?>>()
        if ("background" in manifest && manifest["background"] !is JsonObject) {
            declared += "background" to null
        }
        (manifest["background"] as? JsonObject)?.let { background ->
            if ("service_worker" in background) {
                declared += "background.service_worker" to background["service_worker"]
            }
        }
        if ("content_scripts" in manifest && manifest["content_scripts"] !is JsonArray) {
            declared += "content_scripts" to null
        }
        (manifest["content_scripts"] as? JsonArray)?.forEachIndexed { index, item ->
            val script = item as? JsonObject
            if (script == null) {
                declared += "content_scripts[$index]" to null
            } else {
                listOf("js", "css").forEach { kind ->
                    (script[kind] as? JsonArray)?.forEachIndexed { resourceIndex, resource ->
                        declared +=
                            "content_scripts[$index].$kind[$resourceIndex]" to resource
                    }
                    if (kind in script && script[kind] !is JsonArray) {
                        declared += "content_scripts[$index].$kind" to null
                    }
                }
            }
        }
        if ("action" in manifest && manifest["action"] !is JsonObject) {
            declared += "action" to null
        }
        (manifest["action"] as? JsonObject)?.let { action ->
            if ("default_popup" in action) {
                declared += "action.default_popup" to action["default_popup"]
            }
        }
        if ("options_page" in manifest) {
            declared += "options_page" to manifest["options_page"]
        }
        if ("options_ui" in manifest && manifest["options_ui"] !is JsonObject) {
            declared += "options_ui" to null
        }
        (manifest["options_ui"] as? JsonObject)?.let { options ->
            if ("page" in options) declared += "options_ui.page" to options["page"]
        }

        val normalizedRoot = root.toAbsolutePath().normalize()
        val realRoot = normalizedRoot.toRealPath()
        return declared.mapNotNull { (field, element) ->
            val resource = (element as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
            val target = resource?.takeIf(::isSafeResourcePath)
                ?.let { normalizedRoot.resolve(it.removePrefix("/")).normalize() }
            val insideRealRoot = target?.takeIf { it.isRegularFile() }?.let {
                runCatching { it.toRealPath().startsWith(realRoot) }.getOrDefault(false)
            } ?: false
            if (target == null ||
                !target.startsWith(normalizedRoot) ||
                !insideRealRoot
            ) {
                CompatibilityIssue(
                    code = "manifest_resource_invalid",
                    message = "$field 리소스가 없거나 확장 루트 밖을 가리킵니다: $resource",
                    blocking = true,
                )
            } else {
                null
            }
        }
    }

    private fun isSafeResourcePath(value: String): Boolean =
        value.isNotBlank() &&
            !value.startsWith("//") &&
            '\\' !in value &&
            '%' !in value &&
            ':' !in value &&
            '?' !in value &&
            '#' !in value

    private fun MatchResult.startsInCode(codeOnly: String): Boolean =
        codeOnly.getOrNull(range.first)?.isJavaIdentifierStart() == true

    private fun JsonObject.stringOrNull(name: String): String? =
        (get(name) as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content

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

    private data class ApiScan(
        val namespaces: Set<String>,
        val dynamicUsage: Boolean,
    )

    companion object {
        private val SCRIPT_EXTENSIONS = setOf("js", "mjs", "cjs", "html")
        private val API_CALL_REGEX =
            Regex("""(?:chrome|browser)\s*(?:\?\.|\.)\s*([A-Za-z][A-Za-z0-9_]*)""")
        private val BRACKET_API_REGEX =
            Regex(
                """(?:chrome|browser)\s*(?:\?\.)?\s*""" +
                    """\[\s*["']([A-Za-z][A-Za-z0-9_]*)["']\s*]""",
            )
        private val DYNAMIC_BRACKET_REGEX =
            Regex("""(?:chrome|browser)\s*(?:\?\.)?\s*\[\s*(?!["'])""")
        private val ROOT_TOKEN = Regex("""\b(?:chrome|browser)\b""")
        private val ROOT_SCHEME_LITERAL_REGEX =
            Regex("""\b(?:chrome|browser)\b(?:-extension)?\s*:""")
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
            "debugger",
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
