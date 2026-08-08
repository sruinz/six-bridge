package dev.sixbridge.core.compat

internal data class StaticExtensionApiAliases(
    val roots: Set<String>,
    val namespaces: Set<String>,
    val consumedRootOffsets: Set<Int>,
    val dynamicUsage: Boolean,
)

internal class StaticExtensionApiAliasResolver {
    fun resolve(
        source: String,
        codeOnly: String,
        checkCancelled: () -> Unit,
    ): StaticExtensionApiAliases {
        require(source.length == codeOnly.length)

        val aliases = linkedMapOf<String, Alias>()
        val safeReferenceOffsets = mutableSetOf<Int>()
        val roots = linkedSetOf<String>()
        val namespaces = linkedSetOf<String>()
        val consumedRootOffsets = mutableSetOf<Int>()
        var dynamicUsage = false
        val declarationScope = ScopeCursor(codeOnly, trackParentheses = true)

        DECLARATION.findAll(codeOnly).forEach { declaration ->
            checkCancelled()
            val kind = declaration.groupValues[1]
            val binding = declaration.groupValues[2]
            val name = declaration.groups[3]?.value
            val rawExpression = declaration.groupValues[4]
            val expression = rawExpression.trim()
            val expressionOffset = declaration.groups[4]!!.range.first +
                rawExpression.indexOf(expression)
            declarationScope.advanceTo(declaration.range.first)
            if (!declarationScope.isTopLevel) {
                if (referencesExtensionState(expression, aliases) &&
                    !STATIC_API_CALL_RESULT.matches(expression) &&
                    !STATIC_GLOBAL_CALL_RESULT.matches(expression)
                ) {
                    dynamicUsage = true
                }
                return@forEach
            }

            if (name != null) {
                val nameOffset = declaration.groups[3]!!.range.first
                val alias = resolveExpression(expression, aliases)
                val namespace = resolveNamespaceExpression(expression)
                if (kind == "const" && alias != null) {
                    if (alias.depth >= MAX_ALIAS_DEPTH || aliases.size >= MAX_ALIASES) {
                        dynamicUsage = true
                    } else {
                        val resolved = alias.copy(
                            depth = alias.depth + 1,
                            declarationOffset = nameOffset,
                        )
                        aliases[name] = resolved
                        if (resolved.kind == AliasKind.EXTENSION_ROOT) roots += name
                        consumeStaticRoot(expression, expressionOffset, consumedRootOffsets)
                        if (expression in aliases) safeReferenceOffsets += expressionOffset
                    }
                } else if (kind == "const" && namespace != null) {
                    aliases[name] = Alias(
                        kind = AliasKind.EXTENSION_NAMESPACE,
                        depth = 0,
                        declarationOffset = nameOffset,
                        namespace = namespace,
                    )
                    namespaces += namespace
                    consumeStaticRoot(expression, expressionOffset, consumedRootOffsets)
                } else if (!isFunctionValue(expression) &&
                    referencesExtensionState(expression, aliases)
                ) {
                    dynamicUsage = true
                }
            } else {
                val sourceAlias = resolveExpression(expression, aliases)
                val bindings = binding.removePrefix("{").removeSuffix("}")
                val bindingsOffset = declaration.groups[2]!!.range.first + 1
                if (kind != "const" &&
                    (sourceAlias != null || referencesExtensionState(expression, aliases))
                ) {
                    dynamicUsage = true
                    return@forEach
                }
                if (kind == "const" && sourceAlias != null) {
                    consumeStaticRoot(expression, expressionOffset, consumedRootOffsets)
                    if (expression in aliases) safeReferenceOffsets += expressionOffset
                    bindings.split(',').forEachIndexed { index, rawBinding ->
                        checkCancelled()
                        val property = rawBinding.substringBefore(':').trim()
                            .substringBefore('=').trim()
                        if (!IDENTIFIER.matches(property)) {
                            dynamicUsage = true
                            return@forEachIndexed
                        }
                        val aliasName = rawBinding.substringAfter(':', property).trim()
                        if (rawBinding.contains('=') ||
                            rawBinding.trimStart().startsWith("...") ||
                            (rawBinding.contains(':') && !IDENTIFIER.matches(aliasName))
                        ) {
                            dynamicUsage = true
                            return@forEachIndexed
                        }
                        val bindingStart = bindingStartOffset(bindings, index)
                        val propertyOffset = bindingsOffset + bindingStart +
                            rawBinding.indexOfFirst { !it.isWhitespace() }
                        if (property in ROOT_NAMES) consumedRootOffsets += propertyOffset
                        when (sourceAlias.kind) {
                            AliasKind.GLOBAL_OBJECT -> {
                                if (property in ROOT_NAMES) {
                                    if (IDENTIFIER.matches(aliasName)) {
                                        if (sourceAlias.depth >= MAX_ALIAS_DEPTH ||
                                            aliases.size >= MAX_ALIASES
                                        ) {
                                            dynamicUsage = true
                                        } else {
                                            val aliasNameOffset = bindingsOffset + bindingStart +
                                                rawBinding.indexOf(aliasName)
                                            aliases[aliasName] = Alias(
                                                AliasKind.EXTENSION_ROOT,
                                                sourceAlias.depth + 1,
                                                aliasNameOffset,
                                            )
                                            roots += aliasName
                                        }
                                    }
                                }
                            }

                            AliasKind.EXTENSION_ROOT -> namespaces += property
                            AliasKind.EXTENSION_NAMESPACE -> dynamicUsage = true
                        }
                    }
                } else if (referencesExtensionState(expression, aliases)) {
                    dynamicUsage = true
                }
            }
        }

        checkCancelled()
        if (DYNAMIC_GLOBAL_MEMBER.findAll(source).any { it.startsInCode(codeOnly) }) {
            dynamicUsage = true
        }
        val usages = aliases.flatMap { (name, alias) ->
            identifierRegex(name).findAll(source)
                .filter { it.startsInCode(codeOnly) }
                .map { Usage(it.range.first, name, alias) }
                .toList()
        }.sortedBy(Usage::offset)
        val usageScope = ScopeCursor(codeOnly, trackParentheses = false)
        usages.forEach { usage ->
            checkCancelled()
            usageScope.advanceTo(usage.offset)
            if (usage.offset == usage.alias.declarationOffset ||
                usage.offset in safeReferenceOffsets
            ) {
                return@forEach
            }
            if (usage.offset < usage.alias.declarationOffset || !usageScope.isTopLevel) {
                dynamicUsage = true
                return@forEach
            }
            when (usage.alias.kind) {
                AliasKind.EXTENSION_ROOT -> {
                    when (val usageKind = extensionRootUsage(source, usage.offset, usage.name)) {
                        is AliasUsage.Namespace -> namespaces += usageKind.name
                        AliasUsage.Dynamic -> dynamicUsage = true
                        AliasUsage.None -> Unit
                        else -> dynamicUsage = true
                    }
                }

                AliasKind.GLOBAL_OBJECT -> {
                    when (val usageKind = globalObjectUsage(source, usage.offset, usage.name)) {
                        is AliasUsage.RootNamespace -> {
                            consumedRootOffsets += usageKind.rootOffset
                            usageKind.namespace?.let(namespaces::add)
                        }

                        AliasUsage.Dynamic -> dynamicUsage = true
                        AliasUsage.None -> Unit
                        else -> dynamicUsage = true
                    }
                }

                AliasKind.EXTENSION_NAMESPACE -> dynamicUsage = true
            }
        }

        return StaticExtensionApiAliases(
            roots = roots,
            namespaces = namespaces,
            consumedRootOffsets = consumedRootOffsets,
            dynamicUsage = dynamicUsage,
        )
    }

    private fun resolveExpression(
        expression: String,
        aliases: Map<String, Alias>,
    ): Alias? = when {
        expression in GLOBAL_OBJECTS -> Alias(AliasKind.GLOBAL_OBJECT, 0, -1)
        expression in ROOT_NAMES -> Alias(AliasKind.EXTENSION_ROOT, 0, -1)
        STATIC_GLOBAL_ROOT.matches(expression) -> Alias(AliasKind.EXTENSION_ROOT, 0, -1)
        else -> STATIC_ALIAS_ROOT.matchEntire(expression)?.let { match ->
            aliases[match.groupValues[1]]
                ?.takeIf { it.kind == AliasKind.GLOBAL_OBJECT }
                ?.let { Alias(AliasKind.EXTENSION_ROOT, it.depth, -1) }
        } ?: aliases[expression]
    }

    private fun resolveNamespaceExpression(expression: String): String? =
        STATIC_ROOT_NAMESPACE.matchEntire(expression)?.groupValues?.get(1)

    private fun isFunctionValue(expression: String): Boolean {
        val trimmed = expression.trimStart()
        return trimmed.startsWith("function") ||
            trimmed.startsWith("class") ||
            "=>" in expression
    }

    private fun consumeStaticRoot(
        expression: String,
        expressionOffset: Int,
        consumedRootOffsets: MutableSet<Int>,
    ) {
        ROOT_TOKEN.findAll(expression).forEach { root ->
            consumedRootOffsets += expressionOffset + root.range.first
        }
    }

    private fun referencesExtensionState(
        expression: String,
        aliases: Map<String, Alias>,
    ): Boolean =
        ROOT_TOKEN.findAll(expression).any { root ->
            !isStaticObjectProperty(expression, root)
        } ||
            GLOBAL_OBJECT_TOKEN.containsMatchIn(expression) ||
            aliases.keys.any { alias -> identifierRegex(alias).containsMatchIn(expression) }

    private fun isStaticObjectProperty(
        expression: String,
        root: MatchResult,
    ): Boolean {
        var before = root.range.first - 1
        while (expression.getOrNull(before)?.isWhitespace() == true) before--
        var after = root.range.last + 1
        while (expression.getOrNull(after)?.isWhitespace() == true) after++
        return expression.getOrNull(before) in setOf('{', ',') &&
            expression.getOrNull(after) == ':'
    }

    private fun bindingStartOffset(bindings: String, bindingIndex: Int): Int {
        var offset = 0
        repeat(bindingIndex) {
            offset = bindings.indexOf(',', offset) + 1
        }
        return offset
    }

    private fun extensionRootUsage(source: String, offset: Int, name: String): AliasUsage {
        val position = skipWhitespace(source, offset + name.length)
        if (startsAssignmentToken(source, position)) return AliasUsage.Dynamic
        val bracketPosition = if (source.startsWith("?.", position)) {
            skipWhitespace(source, position + 2)
        } else {
            position
        }
        val member = memberAt(source, position) ?: bracketMemberAt(source, bracketPosition)
            ?: return AliasUsage.Dynamic
        val namespace = member.name.takeIf { API_NAME.matches(it) } ?: return AliasUsage.Dynamic
        return AliasUsage.Namespace(namespace)
    }

    private fun globalObjectUsage(source: String, offset: Int, name: String): AliasUsage {
        val position = skipWhitespace(source, offset + name.length)
        if (startsAssignmentToken(source, position) || source.getOrNull(position) == '[') {
            return AliasUsage.Dynamic
        }
        val root = memberAt(source, position) ?: return AliasUsage.Dynamic
        if (root.name !in ROOT_NAMES) return AliasUsage.None
        val next = memberAt(source, skipWhitespace(source, root.end))
        return AliasUsage.RootNamespace(
            root.offset,
            next?.name?.takeIf { API_NAME.matches(it) },
        )
    }

    private fun memberAt(source: String, position: Int): Member? {
        val start = when {
            source.startsWith("?.", position) -> position + 2
            source.getOrNull(position) == '.' -> position + 1
            else -> return null
        }
        val nameStart = skipWhitespace(source, start)
        if (source.getOrNull(nameStart)?.isAsciiApiIdentifierStart() != true) return null
        var end = nameStart + 1
        while (source.getOrNull(end)?.isAsciiApiIdentifierPart() == true) {
            end++
        }
        return Member(source.substring(nameStart, end), nameStart, end)
    }

    private fun bracketMemberAt(source: String, position: Int): Member? {
        if (source.getOrNull(position) != '[') return null
        val quotePosition = skipWhitespace(source, position + 1)
        val quote = source.getOrNull(quotePosition)
        if (quote != '\'' && quote != '"') return null
        val nameStart = quotePosition + 1
        if (source.getOrNull(nameStart)?.isAsciiApiIdentifierStart() != true) return null
        var end = nameStart + 1
        while (source.getOrNull(end)?.isAsciiApiIdentifierPart() == true) end++
        val closingQuote = skipWhitespace(source, end)
        val closingBracket = skipWhitespace(source, closingQuote + 1)
        if (source.getOrNull(closingQuote) != quote || source.getOrNull(closingBracket) != ']') {
            return null
        }
        return Member(source.substring(nameStart, end), nameStart, closingBracket + 1)
    }

    private fun skipWhitespace(source: String, start: Int): Int {
        var position = start
        while (source.getOrNull(position)?.isWhitespace() == true) position++
        return position
    }

    private fun startsAssignmentToken(source: String, position: Int): Boolean =
        ASSIGNMENT_TOKENS.any { source.startsWith(it, position) }

    private fun Char.isAsciiApiIdentifierStart(): Boolean =
        this in 'A'..'Z' || this in 'a'..'z'

    private fun Char.isAsciiApiIdentifierPart(): Boolean =
        isAsciiApiIdentifierStart() || isDigit() || this == '_'

    private fun MatchResult.startsInCode(codeOnly: String): Boolean =
        codeOnly.getOrNull(range.first)?.isJavaIdentifierStart() == true

    private enum class AliasKind {
        GLOBAL_OBJECT,
        EXTENSION_ROOT,
        EXTENSION_NAMESPACE,
    }

    private data class Alias(
        val kind: AliasKind,
        val depth: Int,
        val declarationOffset: Int,
        val namespace: String? = null,
    )

    private data class Usage(
        val offset: Int,
        val name: String,
        val alias: Alias,
    )

    private data class Member(
        val name: String,
        val offset: Int,
        val end: Int,
    )

    private sealed interface AliasUsage {
        data class Namespace(val name: String) : AliasUsage
        data class RootNamespace(val rootOffset: Int, val namespace: String?) : AliasUsage
        data object Dynamic : AliasUsage
        data object None : AliasUsage
    }

    private class ScopeCursor(
        private val codeOnly: String,
        private val trackParentheses: Boolean,
    ) {
        private var cursor = 0
        private var braceDepth = 0
        private var parenthesisDepth = 0

        val isTopLevel: Boolean
            get() = braceDepth == 0 && (!trackParentheses || parenthesisDepth == 0)

        fun advanceTo(offset: Int) {
            while (cursor < offset) {
                when (codeOnly[cursor++]) {
                    '{' -> braceDepth++
                    '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                    '(' -> if (trackParentheses) parenthesisDepth++
                    ')' -> if (trackParentheses) {
                        parenthesisDepth = (parenthesisDepth - 1).coerceAtLeast(0)
                    }
                }
            }
        }
    }

    private companion object {
        private const val MAX_ALIAS_DEPTH = 32
        private const val MAX_ALIASES = 256
        private val ASSIGNMENT_TOKENS = listOf(
            ">>>=", "**=", "<<=", ">>=", "&&=", "||=", "??=",
            "+=", "-=", "*=", "/=", "%=", "&=", "^=", "|=",
            "=", "++", "--",
        )
        private val ROOT_NAMES = setOf("chrome", "browser")
        private val GLOBAL_OBJECTS = setOf("globalThis", "window", "self")
        private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val API_NAME = Regex("[A-Za-z][A-Za-z0-9_]*")
        private val DECLARATION = Regex(
            """\b(const|let|var)\s+(?:([{][^}\r\n]*[}])|([A-Za-z_$][\w$]*))\s*=\s*([^;\r\n]+)""",
        )
        private val STATIC_GLOBAL_ROOT = Regex(
            """(?:globalThis|window|self)\s*(?:\?\.|\.)\s*(?:chrome|browser)""",
        )
        private val STATIC_ALIAS_ROOT = Regex(
            """([A-Za-z_$][A-Za-z0-9_$]*)\s*(?:\?\.|\.)\s*(?:chrome|browser)""",
        )
        private val STATIC_ROOT_NAMESPACE = Regex(
            """(?:chrome|browser)\s*(?:\?\.|\.)\s*([A-Za-z][A-Za-z0-9_]*)""",
        )
        private val STATIC_API_CALL_RESULT = Regex(
            """(?:await\s+)?(?:chrome|browser)\s*(?:\?\.|\.)\s*""" +
                """[A-Za-z][A-Za-z0-9_]*(?:\s*(?:\?\.|\.)\s*""" +
                """[A-Za-z][A-Za-z0-9_]*)+\s*\([^;]*\)""",
        )
        private val STATIC_GLOBAL_CALL_RESULT = Regex(
            """(?:await\s+)?(?:globalThis|window|self)\s*(?:\?\.|\.)\s*""" +
                """[A-Za-z][A-Za-z0-9_]*(?:\s*(?:\?\.|\.)\s*""" +
                """[A-Za-z][A-Za-z0-9_]*)*\s*\([^;]*\)""",
        )
        private val ROOT_TOKEN = Regex("""(?<![A-Za-z0-9_$])(?:chrome|browser)(?![A-Za-z0-9_$])""")
        private val GLOBAL_OBJECT_TOKEN =
            Regex("""(?<![A-Za-z0-9_$])(?:globalThis|window|self)(?![A-Za-z0-9_$])""")
        private val DYNAMIC_GLOBAL_MEMBER = Regex(
            """\b(?:globalThis|window|self)\s*(?:\?\.)?\s*\[""",
        )

        private fun identifierRegex(name: String) = Regex(
            """(?<![A-Za-z0-9_$])${Regex.escape(name)}(?![A-Za-z0-9_$])""",
        )
    }
}
