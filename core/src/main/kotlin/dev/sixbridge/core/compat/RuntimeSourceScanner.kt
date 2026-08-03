package dev.sixbridge.core.compat

internal object RuntimeSourceScanner {
    fun scanHtmlScripts(source: String): HtmlScriptScan {
        val records = mutableListOf<HtmlScriptRecord>()
        val inlineSource = source.map {
            if (it == '\r' || it == '\n') it else ' '
        }.toCharArray()
        var unresolvedDependency = false
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
            val attributes = readHtmlAttributes(
                source,
                scriptNameEnd,
                openingEnd,
            )
            val decodedType = attributes["type"]?.let(::decodeHtmlAttribute)
            val normalizedType = decodedType?.value?.trim()?.lowercase()
            val executable = decodedType?.valid != false &&
                isExecutableScriptType(normalizedType)
            val hasSource = "src" in attributes
            val decodedSource = attributes["src"]?.let(::decodeHtmlAttribute)
            val bodyStart = openingEnd + 1
            val closingStart = findClosingScriptTag(source, bodyStart)
            val bodyEnd = closingStart ?: source.length
            if (decodedType?.valid == false ||
                (executable && decodedSource?.valid == false)
            ) {
                unresolvedDependency = true
            }
            records += HtmlScriptRecord(
                source = decodedSource?.value,
                hasSource = hasSource,
                normalizedType = normalizedType,
                bodyRange = bodyStart until bodyEnd,
                executable = executable,
            )
            if (executable && !hasSource) {
                for (index in bodyStart until bodyEnd) {
                    inlineSource[index] = source[index]
                }
            }
            if (closingStart == null) break
            val closingNameEnd = closingStart + "</script".length
            val closingEnd = findHtmlTagEnd(source, closingNameEnd) ?: break
            cursor = closingEnd + 1
        }
        val hasImportMap = records.any { it.normalizedType == "importmap" }
        val hasExecutableModule = records.any {
            it.executable && it.normalizedType == "module"
        }
        return HtmlScriptScan(
            records = records,
            inlineSource = inlineSource.concatToString(),
            unresolvedDependency = unresolvedDependency ||
                (hasImportMap && hasExecutableModule),
        )
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

    private fun readHtmlAttributes(
        source: String,
        start: Int,
        tagEnd: Int,
    ): Map<String, String> {
        val attributes = linkedMapOf<String, String>()
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
                if (cursor < tagEnd &&
                    (source[cursor] == '\'' || source[cursor] == '"')
                ) {
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
            attributes.putIfAbsent(name.lowercase(), value.orEmpty())
        }
        return attributes
    }

    private fun decodeHtmlAttribute(source: String): DecodedText {
        val decoded = StringBuilder(source.length)
        var cursor = 0
        while (cursor < source.length) {
            if (source[cursor] != '&') {
                decoded.append(source[cursor++])
                continue
            }
            val entityEnd = source.indexOf(';', cursor + 1)
            if (entityEnd < 0) return DecodedText(null, false)
            val entity = source.substring(cursor + 1, entityEnd)
            val codePoint = when {
                entity.startsWith("#x", ignoreCase = true) ->
                    entity.substring(2).takeIf { it.isNotEmpty() }
                        ?.toIntOrNull(16)
                entity.startsWith("#") ->
                    entity.substring(1).takeIf { it.isNotEmpty() }
                        ?.toIntOrNull(10)
                else -> HTML_NAMED_ENTITIES[entity]
            }
            if (codePoint == null ||
                !Character.isValidCodePoint(codePoint) ||
                codePoint in 0xd800..0xdfff ||
                codePoint in 0x80..0x9f ||
                codePoint == 0
            ) {
                return DecodedText(null, false)
            }
            decoded.appendCodePoint(codePoint)
            cursor = entityEnd + 1
        }
        return DecodedText(decoded.toString(), true)
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

    private fun isExecutableScriptType(normalizedType: String?): Boolean =
        normalizedType == null ||
            normalizedType.isEmpty() ||
            normalizedType == "module" ||
            normalizedType.substringBefore(';').trim() in JAVA_SCRIPT_MIME_TYPES

    fun javaScriptDependencies(
        source: String,
        checkCancelled: () -> Unit = {},
    ): JavaScriptDependencyScan {
        val sourceWithoutComments = JavaScriptLexicalMask.mask(
            source,
            maskLiterals = false,
            checkCancelled = checkCancelled,
        )
        val codeOnly = JavaScriptLexicalMask.mask(
            source,
            maskLiterals = true,
            checkCancelled = checkCancelled,
        )
        val dependencies = JavaScriptDependencyCollector()
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
        return JavaScriptDependencyScan(
            dependencies.values,
            dependencies.unresolvedDependency,
        )
    }

    private fun scanStaticModuleDependency(
        source: String,
        codeOnly: String,
        start: Int,
        isImport: Boolean,
        dependencies: JavaScriptDependencyCollector,
    ): Int {
        var cursor = skipMaskedWhitespace(source, start)
        if (isImport && source.getOrNull(cursor) == '(') {
            return scanDynamicImportDependency(
                source,
                cursor,
                dependencies,
            )
        }
        if (isImport && source.getOrNull(cursor) == '.') return cursor + 1
        if (isImport) {
            readJavaScriptString(source, cursor)?.let { literal ->
                dependencies.add(literal)
                return literal.end
            }
        } else if (source.getOrNull(cursor) != '{' &&
            source.getOrNull(cursor) != '*'
        ) {
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
                        dependencies.add(literal)
                        return literal.end
                    }
                    dependencies.unresolvedDependency = true
                }
                cursor = identifierEnd
            } else {
                cursor++
            }
        }
        return cursor
    }

    private fun scanDynamicImportDependency(
        source: String,
        openingParenthesis: Int,
        dependencies: JavaScriptDependencyCollector,
    ): Int {
        var cursor = skipMaskedWhitespace(source, openingParenthesis + 1)
        readJavaScriptString(source, cursor)?.let { literal ->
            val closing = skipMaskedWhitespace(source, literal.end)
            if (source.getOrNull(closing) == ')') {
                dependencies.add(literal)
                return closing + 1
            }
            dependencies.unresolvedDependency = true
            return closing
        }

        val prefix = DYNAMIC_IMPORT_GET_URL_PREFIXES.firstOrNull {
            source.startsWith(it, cursor)
        }
        if (prefix == null) {
            dependencies.unresolvedDependency = true
            return cursor + 1
        }
        cursor = skipMaskedWhitespace(source, cursor + prefix.length)
        if (source.getOrNull(cursor) != '(') {
            dependencies.unresolvedDependency = true
            return cursor
        }
        cursor = skipMaskedWhitespace(source, cursor + 1)
        val literal = readJavaScriptString(source, cursor)
        if (literal == null) {
            dependencies.unresolvedDependency = true
            return cursor + 1
        }
        val getUrlClosing = skipMaskedWhitespace(source, literal.end)
        val importClosing = skipMaskedWhitespace(source, getUrlClosing + 1)
        if (source.getOrNull(getUrlClosing) != ')' || source.getOrNull(importClosing) != ')') {
            dependencies.unresolvedDependency = true
            return importClosing
        }
        dependencies.add(literal)
        return importClosing + 1
    }

    private fun scanImportScriptsDependencies(
        source: String,
        codeOnly: String,
        start: Int,
        dependencies: JavaScriptDependencyCollector,
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
                    dependencies.add(literal)
                }
                cursor = afterLiteral
            } else {
                dependencies.unresolvedDependency = true
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
            when (codeOnly[cursor]) {
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
        val decoded = StringBuilder()
        var valid = true
        var cursor = start + 1
        while (cursor < source.length) {
            val current = source[cursor]
            when {
                current == quote -> {
                    val value = decoded.toString().takeIf {
                        valid && hasValidUtf16(it)
                    }
                    return JavaScriptString(value, cursor + 1)
                }
                current == '\r' ||
                    current == '\n' ||
                    current == '\u2028' ||
                    current == '\u2029' -> {
                    return JavaScriptString(null, cursor)
                }
                current != '\\' -> {
                    decoded.append(current)
                    cursor++
                }
                cursor + 1 >= source.length ->
                    return JavaScriptString(null, source.length)
                else -> {
                    val escaped = source[cursor + 1]
                    when (escaped) {
                        '\n', '\u2028', '\u2029' -> cursor += 2
                        '\r' -> {
                            cursor += if (source.getOrNull(cursor + 2) == '\n') {
                                3
                            } else {
                                2
                            }
                        }
                        '\'', '"', '\\', '/' -> {
                            decoded.append(escaped)
                            cursor += 2
                        }
                        'b' -> {
                            decoded.append('\b')
                            cursor += 2
                        }
                        'f' -> {
                            decoded.append('\u000c')
                            cursor += 2
                        }
                        'n' -> {
                            decoded.append('\n')
                            cursor += 2
                        }
                        'r' -> {
                            decoded.append('\r')
                            cursor += 2
                        }
                        't' -> {
                            decoded.append('\t')
                            cursor += 2
                        }
                        'v' -> {
                            decoded.append('\u000b')
                            cursor += 2
                        }
                        '0' -> {
                            if (source.getOrNull(cursor + 2)?.isDigit() == true) {
                                valid = false
                            } else {
                                decoded.append('\u0000')
                            }
                            cursor += 2
                        }
                        'x' -> {
                            val digitsEnd = cursor + 4
                            val codePoint = source.substringOrNull(
                                cursor + 2,
                                digitsEnd,
                            )?.takeIf {
                                it.all { character -> character.isHexDigit() }
                            }?.toIntOrNull(16)
                            if (codePoint == null) {
                                valid = false
                                cursor += 2
                            } else {
                                decoded.append(codePoint.toChar())
                                cursor = digitsEnd
                            }
                        }
                        'u' -> {
                            val result = decodeUnicodeEscape(source, cursor)
                            if (result == null) {
                                valid = false
                                cursor += 2
                            } else {
                                decoded.appendCodePoint(result.first)
                                cursor = result.second
                            }
                        }
                        else -> {
                            if (escaped.isDigit()) valid = false else decoded.append(escaped)
                            cursor += 2
                        }
                    }
                }
            }
        }
        return JavaScriptString(null, source.length)
    }

    private val DYNAMIC_IMPORT_GET_URL_PREFIXES = listOf(
        "chrome.runtime.getURL",
        "browser.runtime.getURL",
        "globalThis.chrome.runtime.getURL",
        "globalThis.browser.runtime.getURL",
        "window.chrome.runtime.getURL",
        "window.browser.runtime.getURL",
        "self.chrome.runtime.getURL",
        "self.browser.runtime.getURL",
    )

    private fun decodeUnicodeEscape(
        source: String,
        slash: Int,
    ): Pair<Int, Int>? {
        if (source.getOrNull(slash + 2) == '{') {
            val closing = source.indexOf('}', slash + 3)
            if (closing < 0) return null
            val digits = source.substring(slash + 3, closing)
            val codePoint = digits.takeIf {
                it.isNotEmpty() &&
                    it.length <= 6 &&
                    it.all { character -> character.isHexDigit() }
            }?.toIntOrNull(16)
                ?.takeIf(Character::isValidCodePoint)
                ?.takeUnless { it in 0xd800..0xdfff }
                ?: return null
            return codePoint to closing + 1
        }
        val digitsEnd = slash + 6
        val codePoint = source.substringOrNull(slash + 2, digitsEnd)
            ?.takeIf { it.all { character -> character.isHexDigit() } }
            ?.toIntOrNull(16)
            ?: return null
        return codePoint to digitsEnd
    }

    private fun String.substringOrNull(start: Int, end: Int): String? =
        takeIf { start >= 0 && end >= start && end <= length }
            ?.substring(start, end)

    private fun Char.isHexDigit(): Boolean =
        this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun hasValidUtf16(value: String): Boolean {
        var cursor = 0
        while (cursor < value.length) {
            val current = value[cursor]
            when {
                Character.isHighSurrogate(current) -> {
                    if (!Character.isLowSurrogate(
                            value.getOrNull(cursor + 1) ?: return false,
                        )
                    ) {
                        return false
                    }
                    cursor += 2
                }
                Character.isLowSurrogate(current) -> return false
                else -> cursor++
            }
        }
        return true
    }

    data class HtmlScriptScan(
        val records: List<HtmlScriptRecord>,
        val inlineSource: String,
        val unresolvedDependency: Boolean,
    )

    data class HtmlScriptRecord(
        val source: String?,
        val hasSource: Boolean,
        val normalizedType: String?,
        val bodyRange: IntRange,
        val executable: Boolean,
    )

    private data class DecodedText(
        val value: String?,
        val valid: Boolean,
    )

    data class JavaScriptDependencyScan(
        val dependencies: Set<String>,
        val unresolvedDependency: Boolean,
    )

    private data class JavaScriptString(
        val value: String?,
        val end: Int,
    )

    private class JavaScriptDependencyCollector {
        val values = mutableSetOf<String>()
        var unresolvedDependency = false

        fun add(literal: JavaScriptString) {
            val value = literal.value
            if (value == null) {
                unresolvedDependency = true
            } else {
                values += value
            }
        }
    }

    private val JAVA_SCRIPT_MIME_TYPES = setOf(
        "application/ecmascript",
        "application/javascript",
        "application/x-ecmascript",
        "application/x-javascript",
        "text/ecmascript",
        "text/javascript",
        "text/javascript1.0",
        "text/javascript1.1",
        "text/javascript1.2",
        "text/javascript1.3",
        "text/javascript1.4",
        "text/javascript1.5",
        "text/jscript",
        "text/livescript",
        "text/x-ecmascript",
        "text/x-javascript",
    )

    private val HTML_NAMED_ENTITIES = mapOf(
        "amp" to '&'.code,
        "AMP" to '&'.code,
        "apos" to '\''.code,
        "quot" to '"'.code,
        "QUOT" to '"'.code,
        "lt" to '<'.code,
        "LT" to '<'.code,
        "gt" to '>'.code,
        "GT" to '>'.code,
        "period" to '.'.code,
        "sol" to '/'.code,
        "bsol" to '\\'.code,
        "colon" to ':'.code,
        "num" to '#'.code,
        "quest" to '?'.code,
        "equals" to '='.code,
        "percnt" to '%'.code,
        "semi" to ';'.code,
        "lpar" to '('.code,
        "rpar" to ')'.code,
    )
}

internal object JavaScriptLexicalMask {
    private const val CHECKPOINT_INTERVAL = 16_384

    fun mask(
        source: String,
        maskLiterals: Boolean,
        checkCancelled: () -> Unit = {},
    ): String {
        val masked = source.toCharArray()
        var state = CODE
        var index = 0
        var nextCheckpoint = 0
        var regexCharacterClass = false
        var regexAllowed = true
        var pendingControlKeyword: String? = null
        var pendingFunctionBlockResult: Boolean? = null
        var pendingClassBlockResult: Boolean? = null
        var blockExpected = false
        var expectedBlockResult = true
        var statementPosition = true
        var previousCodeWord: String? = null
        var previousSignificantCharacter: Char? = null
        val templateExpressionDepths = mutableListOf<Int>()
        val controlParentheses = mutableListOf<String?>()
        val blockBraces = mutableListOf<Boolean>()

        fun maskAt(position: Int) {
            if (masked[position] != '\r' && masked[position] != '\n') {
                masked[position] = ' '
            }
        }

        fun checkCancellationCheckpoint(position: Int) {
            while (position >= nextCheckpoint) {
                checkCancelled()
                nextCheckpoint += CHECKPOINT_INTERVAL
            }
        }

        fun recordCodeToken(word: String? = null, significant: Char) {
            previousCodeWord = word
            previousSignificantCharacter = significant
        }

        while (index < masked.size) {
            checkCancellationCheckpoint(index)
            val current = source[index]
            when (state) {
                CODE -> when {
                    templateExpressionDepths.isNotEmpty() && current == '{' -> {
                        val last = templateExpressionDepths.lastIndex
                        templateExpressionDepths[last]++
                        pendingControlKeyword = null
                        regexAllowed = true
                        recordCodeToken(significant = current)
                        index++
                    }
                    templateExpressionDepths.isNotEmpty() && current == '}' -> {
                        val last = templateExpressionDepths.lastIndex
                        templateExpressionDepths[last]--
                        if (templateExpressionDepths[last] == 0) {
                            templateExpressionDepths.removeAt(last)
                            maskAt(index)
                            state = TEMPLATE_TEXT
                        }
                        pendingControlKeyword = null
                        regexAllowed = false
                        recordCodeToken(significant = current)
                        index++
                    }
                    current.isWhitespace() -> index++
                    current.isJavaIdentifierStart() -> {
                        var end = index + 1
                        while (source.getOrNull(end)?.isJavaIdentifierPart() == true) {
                            end++
                            checkCancellationCheckpoint(end)
                        }
                        val word = source.substring(index, end)
                        if (!(pendingControlKeyword == "for" && word == "await")) {
                            pendingControlKeyword = word.takeIf {
                                it in CONTROL_PAREN_KEYWORDS
                            }
                        }
                        val declaration = statementPosition ||
                            previousCodeWord in DECLARATION_PREFIX_KEYWORDS ||
                            previousSignificantCharacter in DECLARATION_PREFIX_CHARS
                        if (word == "function") {
                            pendingFunctionBlockResult = declaration
                        }
                        if (word == "class") {
                            pendingClassBlockResult = declaration
                        }
                        blockExpected = if (pendingClassBlockResult != null) {
                            true
                        } else {
                            word in BLOCK_PREFIX_KEYWORDS
                        }
                        if (blockExpected) expectedBlockResult = true
                        if (pendingClassBlockResult == null &&
                            word !in STATEMENT_PREFIX_KEYWORDS
                        ) {
                            statementPosition = false
                        }
                        regexAllowed = word in REGEX_PREFIX_KEYWORDS
                        recordCodeToken(word, source[end - 1])
                        index = end
                    }
                    current.isDigit() -> {
                        statementPosition = false
                        pendingControlKeyword = null
                        regexAllowed = false
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == '\'' -> {
                        statementPosition = false
                        pendingControlKeyword = null
                        regexAllowed = false
                        state = SINGLE_QUOTED
                        if (maskLiterals) maskAt(index)
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == '"' -> {
                        statementPosition = false
                        pendingControlKeyword = null
                        regexAllowed = false
                        state = DOUBLE_QUOTED
                        if (maskLiterals) maskAt(index)
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == '`' -> {
                        statementPosition = false
                        pendingControlKeyword = null
                        regexAllowed = false
                        state = TEMPLATE_TEXT
                        maskAt(index)
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == '/' && source.getOrNull(index + 1) == '/' -> {
                        maskAt(index)
                        maskAt(index + 1)
                        state = LINE_COMMENT
                        index += 2
                    }
                    current == '/' && source.getOrNull(index + 1) == '*' -> {
                        maskAt(index)
                        maskAt(index + 1)
                        state = BLOCK_COMMENT
                        index += 2
                    }
                    current == '/' && regexAllowed -> {
                        pendingControlKeyword = null
                        regexAllowed = false
                        maskAt(index)
                        regexCharacterClass = false
                        state = REGEX_LITERAL
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == '/' -> {
                        pendingControlKeyword = null
                        regexAllowed = true
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == '(' -> {
                        statementPosition = false
                        controlParentheses.add(
                            when {
                                pendingFunctionBlockResult == true ->
                                    FUNCTION_DECLARATION_CONTEXT
                                pendingFunctionBlockResult == false ->
                                    FUNCTION_EXPRESSION_CONTEXT
                                pendingControlKeyword != null -> pendingControlKeyword
                                else -> null
                            },
                        )
                        pendingFunctionBlockResult = null
                        blockExpected = false
                        pendingControlKeyword = null
                        regexAllowed = true
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == ')' -> {
                        val context = if (controlParentheses.isEmpty()) {
                            null
                        } else {
                            controlParentheses.removeAt(controlParentheses.lastIndex)
                        }
                        blockExpected = context != null
                        expectedBlockResult =
                            context != FUNCTION_EXPRESSION_CONTEXT
                        regexAllowed = blockExpected
                        pendingControlKeyword = null
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == '{' -> {
                        blockBraces += when {
                            pendingClassBlockResult != null ->
                                pendingClassBlockResult == true
                            blockExpected -> expectedBlockResult
                            else -> statementPosition
                        }
                        pendingClassBlockResult = null
                        blockExpected = false
                        expectedBlockResult = true
                        statementPosition = true
                        pendingControlKeyword = null
                        regexAllowed = true
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == '}' -> {
                        regexAllowed = if (blockBraces.isEmpty()) {
                            false
                        } else {
                            blockBraces.removeAt(blockBraces.lastIndex)
                        }
                        blockExpected = false
                        expectedBlockResult = true
                        statementPosition = regexAllowed
                        pendingControlKeyword = null
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == '[' -> {
                        statementPosition = false
                        blockExpected = false
                        pendingControlKeyword = null
                        regexAllowed = true
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == ']' || current == '.' -> {
                        statementPosition = false
                        blockExpected = false
                        pendingControlKeyword = null
                        regexAllowed = false
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == '=' && source.getOrNull(index + 1) == '>' -> {
                        statementPosition = false
                        blockExpected = true
                        expectedBlockResult = false
                        pendingControlKeyword = null
                        regexAllowed = true
                        recordCodeToken(significant = '>')
                        index += 2
                    }
                    (current == '+' || current == '-') &&
                        source.getOrNull(index + 1) == current -> {
                        pendingControlKeyword = null
                        regexAllowed = false
                        recordCodeToken(significant = current)
                        index += 2
                    }
                    current in REGEX_PREFIX_CHARS -> {
                        statementPosition = current == ';'
                        pendingControlKeyword = null
                        regexAllowed = true
                        recordCodeToken(significant = current)
                        index++
                    }
                    else -> {
                        pendingControlKeyword = null
                        regexAllowed = false
                        recordCodeToken(significant = current)
                        index++
                    }
                }
                SINGLE_QUOTED, DOUBLE_QUOTED -> {
                    val closing = if (state == SINGLE_QUOTED) '\'' else '"'
                    if (current == '\\' && index + 1 < masked.size) {
                        if (maskLiterals) {
                            maskAt(index)
                            maskAt(index + 1)
                        }
                        index += 2
                    } else {
                        if (maskLiterals) maskAt(index)
                        index++
                        if (current == closing) {
                            state = CODE
                            recordCodeToken(significant = current)
                        }
                    }
                }
                TEMPLATE_TEXT -> when {
                    current == '\\' && index + 1 < masked.size -> {
                        maskAt(index)
                        maskAt(index + 1)
                        index += 2
                    }
                    current == '`' -> {
                        maskAt(index)
                        state = CODE
                        pendingControlKeyword = null
                        regexAllowed = false
                        recordCodeToken(significant = current)
                        index++
                    }
                    current == '$' && source.getOrNull(index + 1) == '{' -> {
                        maskAt(index)
                        maskAt(index + 1)
                        templateExpressionDepths += 1
                        state = CODE
                        pendingControlKeyword = null
                        regexAllowed = true
                        recordCodeToken(significant = '{')
                        index += 2
                    }
                    else -> {
                        maskAt(index)
                        index++
                    }
                }
                LINE_COMMENT -> {
                    if (current == '\r' || current == '\n') {
                        state = CODE
                    } else {
                        maskAt(index)
                    }
                    index++
                }
                BLOCK_COMMENT -> {
                    if (current == '*' && source.getOrNull(index + 1) == '/') {
                        maskAt(index)
                        maskAt(index + 1)
                        state = CODE
                        index += 2
                    } else {
                        maskAt(index)
                        index++
                    }
                }
                REGEX_LITERAL -> when {
                    current == '\r' || current == '\n' -> {
                        state = CODE
                        index++
                    }
                    current == '\\' && index + 1 < masked.size -> {
                        maskAt(index)
                        maskAt(index + 1)
                        index += 2
                    }
                    current == '[' && !regexCharacterClass -> {
                        maskAt(index)
                        regexCharacterClass = true
                        index++
                    }
                    current == ']' && regexCharacterClass -> {
                        maskAt(index)
                        regexCharacterClass = false
                        index++
                    }
                    current == '/' && !regexCharacterClass -> {
                        maskAt(index)
                        index++
                        while (source.getOrNull(index)?.isLetter() == true) {
                            maskAt(index)
                            index++
                            checkCancellationCheckpoint(index)
                        }
                        pendingControlKeyword = null
                        regexAllowed = false
                        state = CODE
                        recordCodeToken(significant = current)
                    }
                    else -> {
                        maskAt(index)
                        index++
                    }
                }
            }
        }
        checkCancelled()
        return masked.concatToString()
    }

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
    private val BLOCK_PREFIX_KEYWORDS =
        setOf("class", "do", "else", "finally", "try")
    private val STATEMENT_PREFIX_KEYWORDS =
        setOf("class", "do", "else", "finally", "try")
    private val DECLARATION_PREFIX_KEYWORDS = setOf("async", "default", "export")
    private val DECLARATION_PREFIX_CHARS = setOf(';', '{', '}')
    private const val FUNCTION_DECLARATION_CONTEXT = "function-declaration"
    private const val FUNCTION_EXPRESSION_CONTEXT = "function-expression"
}
