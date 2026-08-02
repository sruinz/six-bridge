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
        val declarationOffsets = mutableSetOf<Int>()
        val roots = linkedSetOf<String>()
        val namespaces = linkedSetOf<String>()
        val consumedRootOffsets = mutableSetOf<Int>()
        var dynamicUsage = false
        var braceDepth = 0
        var scopeCursor = 0

        DECLARATION.findAll(codeOnly).forEach { declaration ->
            checkCancelled()
            while (scopeCursor < declaration.range.first) {
                when (codeOnly[scopeCursor++]) {
                    '{' -> braceDepth++
                    '}' -> braceDepth = (braceDepth - 1).coerceAtLeast(0)
                }
            }
            if (braceDepth != 0) return@forEach

            val kind = declaration.groupValues[1]
            val binding = declaration.groupValues[2]
            val name = declaration.groups[3]?.value
            val rawExpression = declaration.groupValues[4]
            val expression = rawExpression.trim()
            val expressionOffset = declaration.groups[4]!!.range.first +
                rawExpression.indexOf(expression)

            if (name != null) {
                val nameOffset = declaration.groups[3]!!.range.first
                declarationOffsets += nameOffset
                val alias = resolveExpression(expression, aliases)
                if (kind == "const" && alias != null) {
                    if (alias.depth >= MAX_ALIAS_DEPTH || aliases.size >= MAX_ALIASES) {
                        dynamicUsage = true
                    } else {
                        val resolved = alias.copy(depth = alias.depth + 1)
                        aliases[name] = resolved
                        if (resolved.kind == AliasKind.EXTENSION_ROOT) roots += name
                        consumeStaticRoot(expression, expressionOffset, consumedRootOffsets)
                    }
                } else if (referencesExtensionState(expression, aliases)) {
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
                        val propertyOffset = bindingsOffset + bindingOffset(bindings, index)
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
                                            aliases[aliasName] = Alias(
                                                AliasKind.EXTENSION_ROOT,
                                                sourceAlias.depth + 1,
                                            )
                                            roots += aliasName
                                        }
                                    }
                                }
                            }

                            AliasKind.EXTENSION_ROOT -> namespaces += property
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
        aliases.forEach { (name, alias) ->
            checkCancelled()
            if (assignmentTo(name, source, codeOnly, declarationOffsets)) {
                dynamicUsage = true
            }
            when (alias.kind) {
                AliasKind.EXTENSION_ROOT -> {
                    aliasMemberRegex(name).findAll(source)
                        .filter { it.startsInCode(codeOnly) }
                        .forEach { namespaces += it.groupValues[1] }
                    aliasBracketRegex(name).findAll(source)
                        .filter { it.startsInCode(codeOnly) }
                        .forEach { namespaces += it.groupValues[1] }
                    if (dynamicBracketRegex(name).findAll(source)
                            .any { it.startsInCode(codeOnly) }
                    ) {
                        dynamicUsage = true
                    }
                }

                AliasKind.GLOBAL_OBJECT -> {
                    globalRootMemberRegex(name).findAll(source)
                        .filter { it.startsInCode(codeOnly) }
                        .forEach { match ->
                            consumedRootOffsets += match.groups[1]!!.range.first
                            match.groups[2]?.value?.let(namespaces::add)
                        }
                    if (dynamicBracketRegex(name).findAll(source)
                            .any { it.startsInCode(codeOnly) }
                    ) {
                        dynamicUsage = true
                    }
                }
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
        expression in GLOBAL_OBJECTS -> Alias(AliasKind.GLOBAL_OBJECT, 0)
        expression in ROOT_NAMES -> Alias(AliasKind.EXTENSION_ROOT, 0)
        STATIC_GLOBAL_ROOT.matches(expression) -> Alias(AliasKind.EXTENSION_ROOT, 0)
        else -> STATIC_ALIAS_ROOT.matchEntire(expression)?.let { match ->
            aliases[match.groupValues[1]]
                ?.takeIf { it.kind == AliasKind.GLOBAL_OBJECT }
                ?.let { Alias(AliasKind.EXTENSION_ROOT, it.depth) }
        } ?: aliases[expression]
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
        ROOT_TOKEN.containsMatchIn(expression) ||
            GLOBAL_OBJECT_TOKEN.containsMatchIn(expression) ||
            aliases.keys.any { alias -> identifierRegex(alias).containsMatchIn(expression) }

    private fun bindingOffset(bindings: String, bindingIndex: Int): Int {
        var offset = 0
        repeat(bindingIndex) {
            offset = bindings.indexOf(',', offset) + 1
        }
        return offset + bindings.substring(offset).indexOfFirst { !it.isWhitespace() }
    }

    private fun assignmentTo(
        name: String,
        source: String,
        codeOnly: String,
        declarationOffsets: Set<Int>,
    ): Boolean = assignmentRegex(name).findAll(source).any { match ->
        match.range.first !in declarationOffsets && match.startsInCode(codeOnly)
    }

    private fun MatchResult.startsInCode(codeOnly: String): Boolean =
        codeOnly.getOrNull(range.first)?.isJavaIdentifierStart() == true

    private enum class AliasKind {
        GLOBAL_OBJECT,
        EXTENSION_ROOT,
    }

    private data class Alias(
        val kind: AliasKind,
        val depth: Int,
    )

    private companion object {
        private const val MAX_ALIAS_DEPTH = 32
        private const val MAX_ALIASES = 256
        private val ROOT_NAMES = setOf("chrome", "browser")
        private val GLOBAL_OBJECTS = setOf("globalThis", "window", "self")
        private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
        private val DECLARATION = Regex(
            """\b(const|let|var)\s+(?:(\{[^}\r\n]*})|([A-Za-z_$][\w$]*))\s*=\s*([^;\r\n]+)""",
        )
        private val STATIC_GLOBAL_ROOT = Regex(
            """(?:globalThis|window|self)\s*(?:\?\.|\.)\s*(?:chrome|browser)""",
        )
        private val STATIC_ALIAS_ROOT = Regex(
            """([A-Za-z_$][A-Za-z0-9_$]*)\s*(?:\?\.|\.)\s*(?:chrome|browser)""",
        )
        private val ROOT_TOKEN = Regex("""\b(?:chrome|browser)\b""")
        private val GLOBAL_OBJECT_TOKEN = Regex("""\b(?:globalThis|window|self)\b""")
        private val DYNAMIC_GLOBAL_MEMBER = Regex(
            """\b(?:globalThis|window|self)\s*(?:\?\.)?\s*\[""",
        )

        private fun identifierRegex(name: String) = Regex("""\b${Regex.escape(name)}\b""")

        private fun aliasMemberRegex(name: String) = Regex(
            """\b${Regex.escape(name)}\s*(?:\?\.|\.)\s*([A-Za-z][A-Za-z0-9_]*)""",
        )

        private fun aliasBracketRegex(name: String) = Regex(
            """\b${Regex.escape(name)}\s*(?:\?\.)?\s*""" +
                """\[\s*["']([A-Za-z][A-Za-z0-9_]*)["']\s*]""",
        )

        private fun dynamicBracketRegex(name: String) = Regex(
            """\b${Regex.escape(name)}\s*(?:\?\.)?\s*\[\s*(?!["'])""",
        )

        private fun globalRootMemberRegex(name: String) = Regex(
            """\b${Regex.escape(name)}\s*(?:\?\.|\.)\s*""" +
                """(chrome|browser)(?:\s*(?:\?\.|\.)\s*([A-Za-z][A-Za-z0-9_]*))?""",
        )

        private fun assignmentRegex(name: String) = Regex(
            """\b${Regex.escape(name)}\s*(?:=(?!=|>)|[+\-*/%&|^]=|\+\+|--)""",
        )
    }
}
