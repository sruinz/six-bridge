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

        val manifestVersion = (manifest["manifest_version"] as? JsonPrimitive)?.intOrNull
        val name = manifest.stringOrNull("name")
        val version = manifest.stringOrNull("version")
        val manifestKey = manifest.stringOrNull("key")
        val manifestKeyExtensionId = manifestKey?.let(::extensionIdFromManifestKey)
        val permissions = (
            readStringSet(manifest["permissions"]) +
                readStringSet(manifest["optional_permissions"])
            ).toSet()
        val apiScan = scanApiCalls(extensionRoot, manifest)
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

    private fun scanApiCalls(root: Path, manifest: JsonObject): ApiScan {
        val found = mutableSetOf<String>()
        var dynamicUsage = false
        val runtimeScripts = runtimeResourceClosure(root, manifest)
        Files.walk(root).use { paths ->
            paths.filter { path ->
                path.isRegularFile() &&
                    path.extension.lowercase() in SCRIPT_EXTENSIONS &&
                    (
                        ".test." !in path.fileName.toString().lowercase() ||
                            path.toAbsolutePath().normalize() in runtimeScripts
                        )
            }.forEach { path ->
                val source = runCatching { path.readText() }.getOrDefault("")
                val apiSource = if (path.extension.lowercase() == "html") {
                    scanHtmlScripts(source).inlineSource
                } else {
                    source
                }
                val sourceWithoutComments = maskJsLiteralsAndComments(
                    apiSource,
                    maskLiterals = false,
                )
                val codeOnly = maskJsLiteralsAndComments(apiSource, maskLiterals = true)
                val aliases = mutableSetOf<String>()
                val consumedRoots = mutableSetOf<Int>()
                ROOT_ALIAS_DECLARATION.findAll(sourceWithoutComments)
                    .filter { it.startsInCode(codeOnly) }
                    .forEach { declaration ->
                    val kind = declaration.groupValues[1]
                    val alias = declaration.groupValues[2]
                    val expression = declaration.groupValues[3].trim()
                    val dynamicExpression = maskJsLiteralsAndComments(
                        expression,
                        maskLiterals = true,
                    )
                    val directAlias = aliases.singleOrNull { it == expression }
                    val referencesAlias = aliases.any {
                        Regex(
                            """\b${Regex.escape(it)}\b""" +
                                """(?!\s*(?:\?\.|\.|\[\s*["']))""",
                        ).containsMatchIn(dynamicExpression)
                    }
                    if (kind == "const" &&
                        (STATIC_ROOT_EXPRESSION.matches(expression) || directAlias != null)
                    ) {
                        aliases += alias
                        if (STATIC_ROOT_EXPRESSION.matches(expression)) {
                            ROOT_TOKEN.findAll(declaration.groups[3]!!.value).forEach { root ->
                                consumedRoots += declaration.groups[3]!!.range.first +
                                    root.range.first
                            }
                        }
                    } else if (
                        DYNAMIC_GLOBAL_ROOT_LOOKUP.containsMatchIn(dynamicExpression) ||
                        referencesAlias
                    ) {
                        dynamicUsage = true
                    }
                }
                API_CALL_REGEX.findAll(sourceWithoutComments)
                    .filter { it.startsInCode(codeOnly) }
                    .forEach { match ->
                    found += match.groupValues[1]
                    consumedRoots += ROOT_TOKEN.find(match.value)!!.range.first +
                        match.range.first
                }
                BRACKET_API_REGEX.findAll(sourceWithoutComments)
                    .filter { it.startsInCode(codeOnly) }
                    .forEach { match ->
                    found += match.groupValues[1]
                    consumedRoots += ROOT_TOKEN.find(match.value)!!.range.first +
                        match.range.first
                }
                DESTRUCTURING_REGEX.findAll(sourceWithoutComments)
                    .filter { it.startsInCode(codeOnly) }
                    .forEach { match ->
                    consumedRoots += ROOT_TOKEN.find(match.value)!!.range.first +
                        match.range.first
                    match.groupValues[1].split(',').forEach { binding ->
                        val namespace = binding.trim()
                            .substringBefore(':')
                            .substringBefore('=')
                            .trim()
                        if (API_NAME.matches(namespace)) found += namespace
                    }
                }
                aliases.forEach { alias ->
                    aliasMemberRegex(alias).findAll(sourceWithoutComments)
                        .filter { it.startsInCode(codeOnly) }
                        .forEach { match ->
                        found += match.groupValues[1]
                    }
                    aliasBracketRegex(alias).findAll(sourceWithoutComments)
                        .filter { it.startsInCode(codeOnly) }
                        .forEach { match ->
                        found += match.groupValues[1]
                    }
                    if (aliasDynamicBracketRegex(alias).findAll(sourceWithoutComments)
                            .any { it.startsInCode(codeOnly) }
                    ) {
                        dynamicUsage = true
                    }
                }
                if (DYNAMIC_BRACKET_REGEX.findAll(sourceWithoutComments)
                        .any { it.startsInCode(codeOnly) }
                ) {
                    dynamicUsage = true
                }
                ROOT_SCHEME_LITERAL_REGEX.findAll(codeOnly).forEach { match ->
                    consumedRoots += ROOT_TOKEN.find(match.value)!!.range.first +
                        match.range.first
                }
                if (ROOT_TOKEN.findAll(codeOnly).any {
                        it.range.first !in consumedRoots
                    }
                ) {
                    dynamicUsage = true
                }
                if (NATIVE_MESSAGING_REGEX.findAll(sourceWithoutComments)
                        .any { it.startsInCode(codeOnly) }
                ) {
                    found += "nativeMessaging"
                }
            }
        }
        return ApiScan(found, dynamicUsage)
    }

    private fun runtimeResourceClosure(root: Path, manifest: JsonObject): Set<Path> {
        val resources = mutableSetOf<String>()
        (manifest["background"] as? JsonObject)
            ?.stringOrNull("service_worker")
            ?.let(resources::add)
        (manifest["content_scripts"] as? JsonArray)?.forEach { item ->
            val script = item as? JsonObject ?: return@forEach
            resources += readStringSet(script["js"])
        }
        (manifest["action"] as? JsonObject)
            ?.stringOrNull("default_popup")
            ?.let(resources::add)
        manifest.stringOrNull("options_page")?.let(resources::add)
        (manifest["options_ui"] as? JsonObject)
            ?.stringOrNull("page")
            ?.let(resources::add)

        val normalizedRoot = root.toAbsolutePath().normalize()
        val realRoot = runCatching { normalizedRoot.toRealPath() }.getOrNull()
            ?: return emptySet()
        val runtimeResources = mutableSetOf<Path>()
        val pending = mutableListOf<Path>()
        var pendingIndex = 0

        fun enqueue(base: Path, resource: String) {
            val path = resolveLocalRuntimeResource(
                normalizedRoot,
                realRoot,
                base,
                resource,
            ) ?: return
            if (runtimeResources.add(path)) pending.add(path)
        }

        resources.forEach { enqueue(normalizedRoot, it) }
        while (pendingIndex < pending.size) {
            val resource = pending[pendingIndex++]
            val source = runCatching { resource.readText() }.getOrDefault("")
            val dependencies = when (resource.extension.lowercase()) {
                "html" -> scanHtmlScripts(source).dependencies
                "js", "mjs", "cjs" -> javaScriptDependencies(source)
                else -> emptySet()
            }
            dependencies.forEach { enqueue(resource.parent, it) }
        }
        return runtimeResources
    }

    private fun resolveLocalRuntimeResource(
        root: Path,
        realRoot: Path,
        base: Path,
        resource: String,
    ): Path? {
        if (resource.isBlank() ||
            '\\' in resource ||
            ':' in resource ||
            '?' in resource ||
            '#' in resource ||
            resource.startsWith("//")
        ) {
            return null
        }
        val target = if (resource.startsWith("/")) {
            root.resolve(resource.removePrefix("/"))
        } else {
            base.resolve(resource)
        }.toAbsolutePath().normalize()
        if (!target.startsWith(root) || !target.isRegularFile()) return null
        val insideRealRoot = runCatching { target.toRealPath().startsWith(realRoot) }
            .getOrDefault(false)
        return target.takeIf { insideRealRoot }
    }

    private fun scanHtmlScripts(source: String): HtmlScriptScan {
        val dependencies = mutableSetOf<String>()
        val inlineSource = source.map {
            if (it == '\r' || it == '\n') it else ' '
        }.toCharArray()
        var cursor = 0
        while (cursor < source.length) {
            if (source.startsWith("<!--", cursor)) {
                val commentEnd = source.indexOf("-->", cursor + 4)
                if (commentEnd < 0) break
                cursor = commentEnd + 3
                continue
            }
            if (source[cursor] != '<') {
                cursor++
                continue
            }
            val scriptNameEnd = cursor + "<script".length
            val isScript = source.regionMatches(
                cursor,
                "<script",
                0,
                "<script".length,
                ignoreCase = true,
            ) && isHtmlTagNameBoundary(source.getOrNull(scriptNameEnd))
            if (!isScript) {
                val tagEnd = findHtmlTagEnd(source, cursor + 1) ?: break
                cursor = tagEnd + 1
                continue
            }

            val openingEnd = findHtmlTagEnd(source, scriptNameEnd) ?: break
            val scriptSource = readScriptSourceAttribute(
                source,
                scriptNameEnd,
                openingEnd,
            )
            scriptSource?.let(dependencies::add)
            val bodyStart = openingEnd + 1
            val closingStart = findClosingScriptTag(source, bodyStart)
            val bodyEnd = closingStart ?: source.length
            if (scriptSource == null) {
                for (index in bodyStart until bodyEnd) {
                    inlineSource[index] = source[index]
                }
            }
            if (closingStart == null) break
            val closingNameEnd = closingStart + "</script".length
            val closingEnd = findHtmlTagEnd(source, closingNameEnd) ?: break
            cursor = closingEnd + 1
        }
        return HtmlScriptScan(dependencies, inlineSource.concatToString())
    }

    private fun findHtmlTagEnd(source: String, start: Int): Int? {
        var quote: Char? = null
        var cursor = start
        while (cursor < source.length) {
            val current = source[cursor]
            if (quote != null) {
                if (current == quote) quote = null
            } else if (current == '\'' || current == '"') {
                quote = current
            } else if (current == '>') {
                return cursor
            }
            cursor++
        }
        return null
    }

    private fun readScriptSourceAttribute(
        source: String,
        start: Int,
        tagEnd: Int,
    ): String? {
        var cursor = start
        while (cursor < tagEnd) {
            while (cursor < tagEnd &&
                (source[cursor].isWhitespace() || source[cursor] == '/')
            ) {
                cursor++
            }
            val nameStart = cursor
            while (cursor < tagEnd &&
                !source[cursor].isWhitespace() &&
                source[cursor] != '=' &&
                source[cursor] != '/'
            ) {
                cursor++
            }
            if (cursor == nameStart) {
                cursor++
                continue
            }
            val name = source.substring(nameStart, cursor)
            while (cursor < tagEnd && source[cursor].isWhitespace()) cursor++
            var value: String? = null
            if (cursor < tagEnd && source[cursor] == '=') {
                cursor++
                while (cursor < tagEnd && source[cursor].isWhitespace()) cursor++
                if (cursor < tagEnd && (source[cursor] == '\'' || source[cursor] == '"')) {
                    val quote = source[cursor++]
                    val valueStart = cursor
                    while (cursor < tagEnd && source[cursor] != quote) cursor++
                    value = source.substring(valueStart, cursor)
                    if (cursor < tagEnd) cursor++
                } else {
                    val valueStart = cursor
                    while (cursor < tagEnd &&
                        !source[cursor].isWhitespace() &&
                        source[cursor] != '>'
                    ) {
                        cursor++
                    }
                    value = source.substring(valueStart, cursor)
                }
            }
            if (name.equals("src", ignoreCase = true)) return value.orEmpty()
        }
        return null
    }

    private fun findClosingScriptTag(source: String, start: Int): Int? {
        var cursor = start
        while (cursor < source.length) {
            val closing = source.indexOf("</script", cursor, ignoreCase = true)
            if (closing < 0) return null
            val nameEnd = closing + "</script".length
            if (isHtmlTagNameBoundary(source.getOrNull(nameEnd))) return closing
            cursor = nameEnd
        }
        return null
    }

    private fun isHtmlTagNameBoundary(value: Char?): Boolean =
        value == null || value.isWhitespace() || value == '/' || value == '>'

    private fun javaScriptDependencies(source: String): Set<String> {
        val sourceWithoutComments = maskJsLiteralsAndComments(source, maskLiterals = false)
        val codeOnly = maskJsLiteralsAndComments(source, maskLiterals = true)
        val dependencies = mutableSetOf<String>()
        var cursor = 0
        while (cursor < codeOnly.length) {
            if (!codeOnly[cursor].isJavaIdentifierStart()) {
                cursor++
                continue
            }
            val identifierEnd = readJavaScriptIdentifierEnd(codeOnly, cursor)
            val identifier = codeOnly.substring(cursor, identifierEnd)
            cursor = when (identifier) {
                "importScripts" -> scanImportScriptsDependencies(
                    sourceWithoutComments,
                    codeOnly,
                    identifierEnd,
                    dependencies,
                )
                "import", "export" -> scanStaticModuleDependency(
                    sourceWithoutComments,
                    codeOnly,
                    identifierEnd,
                    identifier == "import",
                    dependencies,
                )
                else -> identifierEnd
            }
        }
        return dependencies
    }

    private fun scanStaticModuleDependency(
        source: String,
        codeOnly: String,
        start: Int,
        isImport: Boolean,
        dependencies: MutableSet<String>,
    ): Int {
        var cursor = skipMaskedWhitespace(source, start)
        if (isImport &&
            (source.getOrNull(cursor) == '(' || source.getOrNull(cursor) == '.')
        ) {
            return cursor + 1
        }
        if (isImport) {
            readJavaScriptString(source, cursor)?.let { literal ->
                dependencies += literal.value
                return literal.end
            }
        } else if (source.getOrNull(cursor) != '{' && source.getOrNull(cursor) != '*') {
            return cursor
        }
        while (cursor < source.length) {
            if (source[cursor] == ';') return cursor + 1
            if (codeOnly[cursor].isJavaIdentifierStart()) {
                val identifierEnd = readJavaScriptIdentifierEnd(codeOnly, cursor)
                if (codeOnly.substring(cursor, identifierEnd) == "from") {
                    val specifierStart = skipMaskedWhitespace(source, identifierEnd)
                    val literal = readJavaScriptString(source, specifierStart)
                    if (literal != null) {
                        dependencies += literal.value
                        return literal.end
                    }
                }
                cursor = identifierEnd
            } else {
                cursor++
            }
        }
        return cursor
    }

    private fun scanImportScriptsDependencies(
        source: String,
        codeOnly: String,
        start: Int,
        dependencies: MutableSet<String>,
    ): Int {
        var cursor = skipMaskedWhitespace(source, start)
        if (source.getOrNull(cursor) != '(') return cursor
        cursor++
        while (cursor < source.length) {
            cursor = skipMaskedWhitespace(source, cursor)
            if (source.getOrNull(cursor) == ')') return cursor + 1
            val literal = readJavaScriptString(source, cursor)
            if (literal != null) {
                val afterLiteral = skipMaskedWhitespace(source, literal.end)
                if (source.getOrNull(afterLiteral) == ',' ||
                    source.getOrNull(afterLiteral) == ')'
                ) {
                    dependencies += literal.value
                }
                cursor = afterLiteral
            }
            cursor = skipJavaScriptArgument(source, codeOnly, cursor)
            when (source.getOrNull(cursor)) {
                ',' -> cursor++
                ')' -> return cursor + 1
                null -> return cursor
                else -> cursor++
            }
        }
        return cursor
    }

    private fun skipJavaScriptArgument(
        source: String,
        codeOnly: String,
        start: Int,
    ): Int {
        var cursor = start
        var parentheses = 0
        var brackets = 0
        var braces = 0
        while (cursor < source.length) {
            val literal = readJavaScriptString(source, cursor)
            if (literal != null) {
                cursor = literal.end
                continue
            }
            val current = codeOnly[cursor]
            when (current) {
                '(' -> parentheses++
                ')' -> if (parentheses == 0 && brackets == 0 && braces == 0) {
                    return cursor
                } else if (parentheses > 0) {
                    parentheses--
                }
                '[' -> brackets++
                ']' -> if (brackets > 0) brackets--
                '{' -> braces++
                '}' -> if (braces > 0) braces--
                ',' -> if (parentheses == 0 && brackets == 0 && braces == 0) {
                    return cursor
                }
            }
            cursor++
        }
        return cursor
    }

    private fun skipMaskedWhitespace(source: String, start: Int): Int {
        var cursor = start
        while (cursor < source.length && source[cursor].isWhitespace()) cursor++
        return cursor
    }

    private fun readJavaScriptIdentifierEnd(source: String, start: Int): Int {
        var cursor = start + 1
        while (source.getOrNull(cursor)?.isJavaIdentifierPart() == true) cursor++
        return cursor
    }

    private fun readJavaScriptString(source: String, start: Int): JavaScriptString? {
        val quote = source.getOrNull(start)
        if (quote != '\'' && quote != '"') return null
        var cursor = start + 1
        while (cursor < source.length) {
            when (source[cursor]) {
                '\\' -> cursor += if (cursor + 1 < source.length) 2 else 1
                quote -> return JavaScriptString(
                    source.substring(start + 1, cursor),
                    cursor + 1,
                )
                '\r', '\n' -> return null
                else -> cursor++
            }
        }
        return null
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
                ?.let { normalizedRoot.resolve(it).normalize() }
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
            !value.startsWith("/") &&
            '\\' !in value &&
            ':' !in value

    private fun maskJsLiteralsAndComments(source: String, maskLiterals: Boolean): String {
        val masked = source.toCharArray()
        var state = CODE
        var index = 0
        var regexCharacterClass = false
        var regexAllowed = true
        var pendingControlKeyword: String? = null
        val templateExpressionDepths = mutableListOf<Int>()
        val controlParentheses = mutableListOf<String?>()

        fun mask(position: Int) {
            if (masked[position] != '\r' && masked[position] != '\n') {
                masked[position] = ' '
            }
        }

        while (index < masked.size) {
            val current = source[index]
            when (state) {
                CODE -> when {
                    templateExpressionDepths.isNotEmpty() && current == '{' -> {
                        val last = templateExpressionDepths.lastIndex
                        templateExpressionDepths[last]++
                        pendingControlKeyword = null
                        regexAllowed = true
                        index++
                    }
                    templateExpressionDepths.isNotEmpty() && current == '}' -> {
                        val last = templateExpressionDepths.lastIndex
                        templateExpressionDepths[last]--
                        if (templateExpressionDepths[last] == 0) {
                            templateExpressionDepths.removeAt(last)
                            mask(index)
                            state = TEMPLATE_TEXT
                        }
                        pendingControlKeyword = null
                        regexAllowed = false
                        index++
                    }
                    current.isWhitespace() -> index++
                    current.isJavaIdentifierStart() -> {
                        var end = index + 1
                        while (source.getOrNull(end)?.isJavaIdentifierPart() == true) end++
                        val word = source.substring(index, end)
                        if (!(pendingControlKeyword == "for" && word == "await")) {
                            pendingControlKeyword = word.takeIf {
                                it in CONTROL_PAREN_KEYWORDS
                            }
                        }
                        regexAllowed = word in REGEX_PREFIX_KEYWORDS
                        index = end
                    }
                    current.isDigit() -> {
                        pendingControlKeyword = null
                        regexAllowed = false
                        index++
                    }
                    current == '\'' -> {
                        pendingControlKeyword = null
                        regexAllowed = false
                        state = SINGLE_QUOTED
                        if (maskLiterals) mask(index)
                        index++
                    }
                    current == '"' -> {
                        pendingControlKeyword = null
                        regexAllowed = false
                        state = DOUBLE_QUOTED
                        if (maskLiterals) mask(index)
                        index++
                    }
                    current == '`' -> {
                        pendingControlKeyword = null
                        regexAllowed = false
                        state = TEMPLATE_TEXT
                        mask(index)
                        index++
                    }
                    current == '/' && source.getOrNull(index + 1) == '/' -> {
                        mask(index)
                        mask(index + 1)
                        state = LINE_COMMENT
                        index += 2
                    }
                    current == '/' && source.getOrNull(index + 1) == '*' -> {
                        mask(index)
                        mask(index + 1)
                        state = BLOCK_COMMENT
                        index += 2
                    }
                    current == '/' && regexAllowed -> {
                        pendingControlKeyword = null
                        regexAllowed = false
                        mask(index)
                        regexCharacterClass = false
                        state = REGEX_LITERAL
                        index++
                    }
                    current == '/' -> {
                        pendingControlKeyword = null
                        regexAllowed = true
                        index++
                    }
                    current == '(' -> {
                        controlParentheses.add(pendingControlKeyword)
                        pendingControlKeyword = null
                        regexAllowed = true
                        index++
                    }
                    current == ')' -> {
                        regexAllowed = if (controlParentheses.isEmpty()) {
                            false
                        } else {
                            controlParentheses.removeAt(controlParentheses.lastIndex) != null
                        }
                        pendingControlKeyword = null
                        index++
                    }
                    current == '[' || current == '{' -> {
                        pendingControlKeyword = null
                        regexAllowed = true
                        index++
                    }
                    current == ']' || current == '}' || current == '.' -> {
                        pendingControlKeyword = null
                        regexAllowed = false
                        index++
                    }
                    (current == '+' || current == '-') &&
                        source.getOrNull(index + 1) == current -> {
                        pendingControlKeyword = null
                        regexAllowed = false
                        index += 2
                    }
                    current in REGEX_PREFIX_CHARS -> {
                        pendingControlKeyword = null
                        regexAllowed = true
                        index++
                    }
                    else -> {
                        pendingControlKeyword = null
                        regexAllowed = false
                        index++
                    }
                }
                SINGLE_QUOTED, DOUBLE_QUOTED -> {
                    val closing = when (state) {
                        SINGLE_QUOTED -> '\''
                        else -> '"'
                    }
                    if (current == '\\' && index + 1 < masked.size) {
                        if (maskLiterals) {
                            mask(index)
                            mask(index + 1)
                        }
                        index += 2
                    } else {
                        if (maskLiterals) mask(index)
                        index++
                        if (current == closing) state = CODE
                    }
                }
                TEMPLATE_TEXT -> when {
                    current == '\\' && index + 1 < masked.size -> {
                        mask(index)
                        mask(index + 1)
                        index += 2
                    }
                    current == '`' -> {
                        mask(index)
                        state = CODE
                        pendingControlKeyword = null
                        regexAllowed = false
                        index++
                    }
                    current == '$' && source.getOrNull(index + 1) == '{' -> {
                        mask(index)
                        mask(index + 1)
                        templateExpressionDepths += 1
                        state = CODE
                        pendingControlKeyword = null
                        regexAllowed = true
                        index += 2
                    }
                    else -> {
                        mask(index)
                        index++
                    }
                }
                LINE_COMMENT -> {
                    if (current == '\r' || current == '\n') {
                        state = CODE
                    } else {
                        mask(index)
                    }
                    index++
                }
                BLOCK_COMMENT -> {
                    if (current == '*' && source.getOrNull(index + 1) == '/') {
                        mask(index)
                        mask(index + 1)
                        state = CODE
                        index += 2
                    } else {
                        mask(index)
                        index++
                    }
                }
                REGEX_LITERAL -> when {
                    current == '\r' || current == '\n' -> {
                        state = CODE
                        index++
                    }
                    current == '\\' && index + 1 < masked.size -> {
                        mask(index)
                        mask(index + 1)
                        index += 2
                    }
                    current == '[' && !regexCharacterClass -> {
                        mask(index)
                        regexCharacterClass = true
                        index++
                    }
                    current == ']' && regexCharacterClass -> {
                        mask(index)
                        regexCharacterClass = false
                        index++
                    }
                    current == '/' && !regexCharacterClass -> {
                        mask(index)
                        index++
                        while (source.getOrNull(index)?.isLetter() == true) {
                            mask(index)
                            index++
                        }
                        pendingControlKeyword = null
                        regexAllowed = false
                        state = CODE
                    }
                    else -> {
                        mask(index)
                        index++
                    }
                }
            }
        }
        return masked.concatToString()
    }

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

    private data class HtmlScriptScan(
        val dependencies: Set<String>,
        val inlineSource: String,
    )

    private data class JavaScriptString(
        val value: String,
        val end: Int,
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
        private val DESTRUCTURING_REGEX =
            Regex(
                """(?:const|let|var)\s*\{([^}]*)}\s*=\s*""" +
                    """(?:(?:globalThis|window|self)\s*\.\s*)?(?:chrome|browser)\b""",
            )
        private val DYNAMIC_BRACKET_REGEX =
            Regex("""(?:chrome|browser)\s*(?:\?\.)?\s*\[\s*(?!["'])""")
        private val ROOT_ALIAS_DECLARATION = Regex(
            """\b(const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*([^;\r\n]+)""",
        )
        private val STATIC_ROOT_EXPRESSION = Regex(
            """(?:(?:globalThis|window|self)\s*(?:\?\.|\.)\s*)?(?:chrome|browser)""",
        )
        private val DYNAMIC_GLOBAL_ROOT_LOOKUP =
            Regex("""(?:globalThis|window|self)\s*\[""")
        private val ROOT_TOKEN = Regex("""\b(?:chrome|browser)\b""")
        private val ROOT_SCHEME_LITERAL_REGEX =
            Regex("""\b(?:chrome|browser)\b(?:-extension)?\s*:""")
        private const val CODE = 0
        private const val SINGLE_QUOTED = 1
        private const val DOUBLE_QUOTED = 2
        private const val TEMPLATE_TEXT = 3
        private const val LINE_COMMENT = 4
        private const val BLOCK_COMMENT = 5
        private const val REGEX_LITERAL = 6
        private val REGEX_PREFIX_CHARS = setOf(
            '(', '[', '{', '=', ':', ',', ';', '!', '?',
            '&', '|', '+', '-', '*', '%', '^', '~', '<', '>',
        )
        private val REGEX_PREFIX_KEYWORDS = setOf(
            "await",
            "case",
            "delete",
            "do",
            "else",
            "in",
            "instanceof",
            "of",
            "return",
            "throw",
            "typeof",
            "void",
            "yield",
        )
        private val CONTROL_PAREN_KEYWORDS =
            setOf("if", "while", "for", "with", "switch", "catch")
        private val API_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")
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

        private fun aliasMemberRegex(alias: String) = Regex(
            """\b${Regex.escape(alias)}\s*(?:\?\.|\.)\s*([A-Za-z][A-Za-z0-9_]*)""",
        )

        private fun aliasBracketRegex(alias: String) = Regex(
            """\b${Regex.escape(alias)}\s*(?:\?\.)?\s*""" +
                """\[\s*["']([A-Za-z][A-Za-z0-9_]*)["']\s*]""",
        )

        private fun aliasDynamicBracketRegex(alias: String) = Regex(
            """\b${Regex.escape(alias)}\s*(?:\?\.)?\s*\[\s*(?!["'])""",
        )
    }
}
