package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.JavaScriptLexicalMask
import java.security.MessageDigest

class JavaScriptTokenRewriter {
    fun rewrite(source: String): RewriteResult {
        val inspection = inspect(source)
        if (inspection.unsafeAccesses.isNotEmpty()) {
            return RewriteResult(
                source = source,
                replacements = inspection.syncAccesses,
                unsafeAccesses = inspection.unsafeAccesses,
                localAccesses = inspection.localAccesses,
            )
        }
        val rewritten = StringBuilder(source)
        inspection.syncAccesses
            .sortedByDescending(StaticStorageAccess::areaStart)
            .forEach { access ->
                rewritten.replace(
                    access.areaStart,
                    access.areaEndExclusive,
                    LOCAL,
                )
            }
        return RewriteResult(
            source = rewritten.toString(),
            replacements = inspection.syncAccesses,
            unsafeAccesses = emptyList(),
            localAccesses = inspection.localAccesses,
        )
    }

    internal fun inspect(source: String): StorageTokenInspection {
        val codeOnly = JavaScriptLexicalMask.mask(source, maskLiterals = true)
        val sourceWithoutComments = JavaScriptLexicalMask.mask(
            source,
            maskLiterals = false,
        )
        val syncAccesses = mutableListOf<StaticStorageAccess>()
        val localAccesses = mutableListOf<StaticStorageAccess>()
        val unsafe = mutableListOf<UnsafeStorageAccess>()

        scanStorageMembers(source, codeOnly).forEach { access ->
            when (access.kind) {
                StorageMemberKind.SYNC -> syncAccesses += StaticStorageAccess(
                    root = access.root,
                    area = SYNC,
                    areaStart = access.areaStart,
                    areaEndExclusive = access.areaEndExclusive,
                    line = lineAt(source, access.areaStart),
                )
                StorageMemberKind.LOCAL -> localAccesses += StaticStorageAccess(
                    root = access.root,
                    area = LOCAL,
                    areaStart = access.areaStart,
                    areaEndExclusive = access.areaEndExclusive,
                    line = lineAt(source, access.areaStart),
                )
                StorageMemberKind.UNSAFE -> unsafe += UnsafeStorageAccess(
                    offset = access.rootStart,
                    line = lineAt(source, access.rootStart),
                    detail = access.detail,
                )
            }
        }
        STORAGE_ALIAS.findAll(codeOnly)
            .filter { match ->
                val rootStart = requireNotNull(match.groups[1]).range.first
                isGlobalRoot(codeOnly, rootStart) &&
                    staticMemberAfter(
                        codeOnly,
                        requireNotNull(match.groups[2]).range.last + 1,
                    ) !in setOf(SYNC, LOCAL)
            }
            .forEach { match ->
                unsafe += UnsafeStorageAccess(
                    offset = match.range.first,
                    line = lineAt(source, match.range.first),
                    detail = "storage 객체 별칭을 정적으로 변환할 수 없습니다.",
                )
            }
        STORAGE_DESTRUCTURING.findAll(codeOnly)
            .filter {
                isGlobalRoot(
                    codeOnly,
                    requireNotNull(it.groups[1]).range.first,
                )
            }
            .forEach { match ->
                unsafe += UnsafeStorageAccess(
                    offset = match.range.first,
                    line = lineAt(source, match.range.first),
                    detail = "storage 영역 구조 분해를 정적으로 변환할 수 없습니다.",
                )
            }
        ROOT_ALIAS.findAll(codeOnly).forEach { match ->
            val alias = requireNotNull(match.groups[1]).value
            val rootEnd = requireNotNull(match.groups[2]).range.last + 1
            if (staticMemberAfter(codeOnly, rootEnd) == "storage") {
                return@forEach
            }
            val use = aliasStorageSyncUse(alias).find(
                codeOnly,
                match.range.last + 1,
            ) ?: return@forEach
            unsafe += UnsafeStorageAccess(
                offset = use.range.first,
                line = lineAt(source, use.range.first),
                detail = "browser API root 별칭의 storage.sync 접근을 정적으로 변환할 수 없습니다.",
            )
        }
        ROOT_STORAGE_DESTRUCTURING.findAll(codeOnly).forEach { match ->
            val alias = match.groups[1]?.value ?: "storage"
            val use = aliasSyncUse(alias).find(
                codeOnly,
                match.range.last + 1,
            ) ?: return@forEach
            unsafe += UnsafeStorageAccess(
                offset = use.range.first,
                line = lineAt(source, use.range.first),
                detail = "browser API root 구조 분해의 storage.sync 접근을 정적으로 변환할 수 없습니다.",
            )
        }
        PROPERTY_ROOT_STORAGE.findAll(codeOnly).forEach { match ->
            unsafe += UnsafeStorageAccess(
                offset = match.range.first,
                line = lineAt(source, match.range.first),
                detail = "객체 속성의 chrome 또는 browser storage 접근을 안전하게 변환할 수 없습니다.",
            )
        }
        val rawStorageMention = RAW_STORAGE_MENTION.containsMatchIn(
            sourceWithoutComments,
        )
        if (rawStorageMention) {
            EVAL_IDENTIFIER.findAll(codeOnly).forEach { match ->
                unsafe += UnsafeStorageAccess(
                    offset = match.range.first,
                    line = lineAt(source, match.range.first),
                    detail = "eval을 통한 storage 접근을 정적으로 변환할 수 없습니다.",
                )
            }
        }
        if (syncAccesses.isNotEmpty() || unsafe.isNotEmpty()) {
            shadowBinding(codeOnly)?.let { binding ->
                unsafe += UnsafeStorageAccess(
                    offset = binding.range.first,
                    line = lineAt(source, binding.range.first),
                    detail = "chrome 또는 browser binding shadowing 가능성 때문에 자동 변환할 수 없습니다.",
                )
            }
        }

        return StorageTokenInspection(
            syncAccesses = syncAccesses
                .distinctBy { Triple(it.areaStart, it.areaEndExclusive, it.area) }
                .sortedBy(StaticStorageAccess::areaStart),
            localAccesses = localAccesses
                .distinctBy { Triple(it.areaStart, it.areaEndExclusive, it.area) }
                .sortedBy(StaticStorageAccess::areaStart),
            unsafeAccesses = unsafe
                .distinctBy { it.offset to it.detail }
                .sortedWith(
                    compareBy(
                        UnsafeStorageAccess::offset,
                        UnsafeStorageAccess::detail,
                    ),
                ),
        )
    }

    private fun hasDirectAreaUse(source: String, start: Int): Boolean {
        var cursor = skipWhitespace(source, start)
        if (source.startsWith("?.", cursor)) {
            cursor += 2
        } else if (source.getOrNull(cursor) == '.') {
            cursor++
        } else if (source.getOrNull(cursor) == '[') {
            return true
        } else {
            return false
        }
        cursor = skipWhitespace(source, cursor)
        return source.getOrNull(cursor)?.isJavaIdentifierStart() == true
    }

    private fun scanStorageMembers(
        source: String,
        codeOnly: String,
    ): List<ScannedStorageMember> {
        val results = mutableListOf<ScannedStorageMember>()
        ROOT_IDENTIFIER.findAll(codeOnly).forEach { rootMatch ->
            val rootStart = rootMatch.range.first
            if (codeOnly.getOrNull(rootStart - 1)?.let(::isIdentifierPart) == true) {
                return@forEach
            }
            val rootIsProperty = previousNonWhitespace(codeOnly, rootStart) in
                setOf('.', '?')
            val storage = readProperty(source, codeOnly, rootMatch.range.last + 1)
                ?: return@forEach
            val storageKey = storage.staticKey
            if (storageKey != "storage") {
                if (!(storage.computed && storageKey == null)) return@forEach
            }
            val area = readProperty(source, codeOnly, storage.endExclusive)
                ?: return@forEach
            if (rootIsProperty) {
                if (area.staticKey == SYNC || area.staticKey == null) {
                    results += ScannedStorageMember.unsafe(
                        rootMatch.value,
                        rootStart,
                        area,
                        "객체 속성의 chrome 또는 browser storage 접근을 안전하게 변환할 수 없습니다.",
                    )
                }
                return@forEach
            }
            if (storage.computed) {
                if (area.staticKey == SYNC || area.staticKey == null) {
                    results += ScannedStorageMember.unsafe(
                        rootMatch.value,
                        rootStart,
                        area,
                        "계산된 storage 영역 접근을 정적으로 변환할 수 없습니다.",
                    )
                }
                return@forEach
            }
            when {
                area.computed && area.staticKey == SYNC ->
                    results += ScannedStorageMember.unsafe(
                        rootMatch.value,
                        rootStart,
                        area,
                        "계산된 storage 영역 접근을 정적으로 변환할 수 없습니다.",
                    )
                area.computed && area.staticKey == null ->
                    results += ScannedStorageMember.unsafe(
                        rootMatch.value,
                        rootStart,
                        area,
                        "계산된 storage 영역 접근을 정적으로 변환할 수 없습니다.",
                    )
                area.computed -> Unit
                area.staticKey == SYNC &&
                    hasDirectAreaUse(codeOnly, area.endExclusive) ->
                    results += ScannedStorageMember(
                        root = rootMatch.value,
                        rootStart = rootStart,
                        areaStart = area.keyStart,
                        areaEndExclusive = area.keyEndExclusive,
                        kind = StorageMemberKind.SYNC,
                        detail = "",
                    )
                area.staticKey == SYNC ->
                    results += ScannedStorageMember.unsafe(
                        rootMatch.value,
                        rootStart,
                        area,
                        "storage.sync 별칭 또는 값 참조를 정적으로 변환할 수 없습니다.",
                    )
                area.staticKey == LOCAL ->
                    results += ScannedStorageMember(
                        root = rootMatch.value,
                        rootStart = rootStart,
                        areaStart = area.keyStart,
                        areaEndExclusive = area.keyEndExclusive,
                        kind = StorageMemberKind.LOCAL,
                        detail = "",
                    )
            }
        }
        return results
    }

    private fun readProperty(
        source: String,
        codeOnly: String,
        start: Int,
    ): JavaScriptProperty? {
        var cursor = skipWhitespace(codeOnly, start)
        var computed = false
        if (codeOnly.startsWith("?.", cursor)) {
            cursor = skipWhitespace(codeOnly, cursor + 2)
            if (codeOnly.getOrNull(cursor) == '[') computed = true
        } else if (codeOnly.getOrNull(cursor) == '.') {
            cursor = skipWhitespace(codeOnly, cursor + 1)
        } else if (codeOnly.getOrNull(cursor) == '[') {
            computed = true
        } else {
            return null
        }
        if (!computed) {
            if (codeOnly.getOrNull(cursor)?.isJavaIdentifierStart() != true) {
                return null
            }
            var end = cursor + 1
            while (codeOnly.getOrNull(end)?.isJavaIdentifierPart() == true) end++
            return JavaScriptProperty(
                staticKey = codeOnly.substring(cursor, end),
                computed = false,
                keyStart = cursor,
                keyEndExclusive = end,
                endExclusive = end,
            )
        }
        val closing = matchingBracket(codeOnly, cursor) ?: return JavaScriptProperty(
            staticKey = null,
            computed = true,
            keyStart = cursor,
            keyEndExclusive = cursor + 1,
            endExclusive = codeOnly.length,
        )
        val expression = source.substring(cursor + 1, closing).trim()
        return JavaScriptProperty(
            staticKey = staticComputedKey(expression),
            computed = true,
            keyStart = cursor + 1,
            keyEndExclusive = closing,
            endExclusive = closing + 1,
        )
    }

    private fun matchingBracket(source: String, opening: Int): Int? {
        var depth = 0
        for (index in opening until source.length) {
            when (source[index]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return null
    }

    private fun staticComputedKey(expression: String): String? {
        val withoutComments = JavaScriptLexicalMask.mask(
            expression,
            maskLiterals = false,
        ).trim()
        val quoted = COMPUTED_STRING.matchEntire(withoutComments)
        if (quoted != null) return quoted.groupValues[2]
        val template = COMPUTED_TEMPLATE.matchEntire(expression.trim())
        return template?.groupValues?.get(1)
    }

    private fun shadowBinding(codeOnly: String): MatchResult? =
        SHADOW_BINDINGS.firstNotNullOfOrNull { it.find(codeOnly) }

    private fun previousNonWhitespace(source: String, start: Int): Char? {
        var cursor = start - 1
        while (cursor >= 0 && source[cursor].isWhitespace()) cursor--
        return source.getOrNull(cursor)
    }

    private fun staticMemberAfter(source: String, start: Int): String? {
        var cursor = skipWhitespace(source, start)
        if (source.startsWith("?.", cursor)) {
            cursor += 2
        } else if (source.getOrNull(cursor) == '.') {
            cursor++
        } else {
            return null
        }
        cursor = skipWhitespace(source, cursor)
        if (source.getOrNull(cursor)?.isJavaIdentifierStart() != true) return null
        var end = cursor + 1
        while (source.getOrNull(end)?.isJavaIdentifierPart() == true) end++
        return source.substring(cursor, end)
    }

    private fun isGlobalRoot(source: String, start: Int): Boolean {
        if (source.getOrNull(start - 1)?.let(::isIdentifierPart) == true) {
            return false
        }
        var cursor = start - 1
        while (cursor >= 0 && source[cursor].isWhitespace()) cursor--
        return source.getOrNull(cursor) !in setOf('.', '?')
    }

    private fun isIdentifierPart(value: Char): Boolean =
        value.isJavaIdentifierPart() || value == '$'

    private fun skipWhitespace(source: String, start: Int): Int {
        var cursor = start
        while (source.getOrNull(cursor)?.isWhitespace() == true) cursor++
        return cursor
    }

    private fun lineAt(source: String, offset: Int): Int =
        source.substring(0, offset.coerceIn(0, source.length))
            .count { it == '\n' } + 1

    companion object {
        private const val SYNC = "sync"
        private const val LOCAL = "local"
        private const val ROOT = """(chrome|browser)"""
        private const val MEMBER = """\s*(?:\?\.|\.)\s*"""

        private val STORAGE_ALIAS = Regex(
            """\b[A-Za-z_$][\w$]*\s*=\s*""" +
                ROOT + MEMBER + """(storage)\b""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val STORAGE_DESTRUCTURING = Regex(
            """\b(?:const|let|var)\s*\{[^}]*\bsync\b[^}]*}\s*=\s*""" +
                ROOT + MEMBER + """storage\b""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val ROOT_ALIAS = Regex(
            """\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*""" +
                """(chrome|browser)\b""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val ROOT_STORAGE_DESTRUCTURING = Regex(
            """\b(?:const|let|var)\s*\{[^}]*\bstorage\b""" +
                """(?:\s*:\s*([A-Za-z_$][\w$]*))?[^}]*}\s*=\s*""" +
                """(?:chrome|browser)\b""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val PROPERTY_ROOT_STORAGE = Regex(
            """(?:\?\.|\.)\s*(?:chrome|browser)\b""" +
                MEMBER + """storage\b""" + MEMBER + """sync\b""",
        )
        private val EVAL_IDENTIFIER = Regex("""\beval\b""")
        private val RAW_STORAGE_MENTION = Regex(
            """\b(?:chrome|browser)\b.*\bstorage\b.*\bsync\b""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        private val ROOT_IDENTIFIER = Regex("""\b(?:chrome|browser)\b""")
        private val COMPUTED_STRING = Regex("""(["'])([A-Za-z_$][\w$]*)\1""")
        private val COMPUTED_TEMPLATE = Regex("""`([A-Za-z_$][\w$]*)`""")
        private val SHADOW_BINDINGS = listOf(
            Regex("""\b(?:var|let|const)\s+(?:chrome|browser)\b"""),
            Regex("""\b(?:var|let|const)\s*[\{\[][^}\]]*\b(?:chrome|browser)\b"""),
            Regex("""\b(?:class|function)\s+(?:chrome|browser)\b"""),
            Regex("""\bfunction\b[^(]*\([^)]*\b(?:chrome|browser)\b[^)]*\)"""),
            Regex("""\([^)]*\b(?:chrome|browser)\b[^)]*\)\s*=>"""),
            Regex("""\b(?:chrome|browser)\b\s*=>"""),
            Regex("""\bcatch\s*\([^)]*\b(?:chrome|browser)\b[^)]*\)"""),
            Regex("""\bimport\s+(?:chrome|browser)\b"""),
            Regex("""\bimport\s*\{[^}]*\b(?:chrome|browser)\b[^}]*}"""),
            Regex("""\bimport\s+\*\s+as\s+(?:chrome|browser)\b"""),
            Regex(
                """\b(?:var|let|const)\b[^;]*,\s*(?:chrome|browser)\b""" +
                    """\s*(?==|,|;)""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ),
            Regex(
                """\b(?!(?:if|for|while|switch|with|catch)\b)""" +
                    """[A-Za-z_$][\w$]*\s*\([^)]*\b(?:chrome|browser)\b[^)]*\)\s*\{""",
            ),
            Regex("""(?:^|[;{}])\s*(?:chrome|browser)\s*="""),
            Regex("""[\{\[][^}\]]*\b(?:chrome|browser)\b[^}\]]*[\}\]]\s*="""),
        )

        private fun aliasStorageSyncUse(alias: String) = Regex(
            """\b${Regex.escape(alias)}\b""" +
                MEMBER + """storage\b""" + MEMBER + """sync\b""",
        )

        private fun aliasSyncUse(alias: String) = Regex(
            """\b${Regex.escape(alias)}\b""" + MEMBER + """sync\b""",
        )
    }

    private data class JavaScriptProperty(
        val staticKey: String?,
        val computed: Boolean,
        val keyStart: Int,
        val keyEndExclusive: Int,
        val endExclusive: Int,
    )

    private data class ScannedStorageMember(
        val root: String,
        val rootStart: Int,
        val areaStart: Int,
        val areaEndExclusive: Int,
        val kind: StorageMemberKind,
        val detail: String,
    ) {
        companion object {
            fun unsafe(
                root: String,
                rootStart: Int,
                area: JavaScriptProperty,
                detail: String,
            ) = ScannedStorageMember(
                root = root,
                rootStart = rootStart,
                areaStart = area.keyStart,
                areaEndExclusive = area.keyEndExclusive,
                kind = StorageMemberKind.UNSAFE,
                detail = detail,
            )
        }
    }

    private enum class StorageMemberKind {
        SYNC,
        LOCAL,
        UNSAFE,
    }
}

data class RewriteResult(
    val source: String,
    val replacements: List<StaticStorageAccess>,
    val unsafeAccesses: List<UnsafeStorageAccess>,
    val localAccesses: List<StaticStorageAccess>,
)

data class StaticStorageAccess(
    val root: String,
    val area: String,
    val areaStart: Int,
    val areaEndExclusive: Int,
    val line: Int,
)

data class UnsafeStorageAccess(
    val offset: Int,
    val line: Int,
    val detail: String,
)

internal data class StorageTokenInspection(
    val syncAccesses: List<StaticStorageAccess>,
    val localAccesses: List<StaticStorageAccess>,
    val unsafeAccesses: List<UnsafeStorageAccess>,
)

internal object StorageFallbackEvidence {
    fun detail(
        root: String,
        source: String,
        converted: String,
    ): String = "정적인 $root.storage.sync 사용; " +
        "source-sha256=${sha256(source)}; " +
        "converted-sha256=${sha256(converted)}"

    fun parse(detail: String): Parsed? {
        val match = DETAIL.matchEntire(detail) ?: return null
        return Parsed(
            root = match.groupValues[1],
            sourceSha256 = match.groupValues[2],
            convertedSha256 = match.groupValues[3],
        )
    }

    fun sha256(source: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(source.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    data class Parsed(
        val root: String,
        val sourceSha256: String,
        val convertedSha256: String,
    )

    private val DETAIL = Regex(
        """정적인 (chrome|browser)\.storage\.sync 사용; """ +
            """source-sha256=([0-9a-f]{64}); converted-sha256=([0-9a-f]{64})""",
    )
}
