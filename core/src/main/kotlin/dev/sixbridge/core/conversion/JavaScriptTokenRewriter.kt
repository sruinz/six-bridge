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

        STATIC_STORAGE.findAll(codeOnly)
            .filter { isGlobalRoot(codeOnly, it.range.first) }
            .forEach { match ->
                val root = requireNotNull(match.groups[1]).value
                val areaGroup = requireNotNull(match.groups[2])
                val access = StaticStorageAccess(
                    root = root,
                    area = areaGroup.value,
                    areaStart = areaGroup.range.first,
                    areaEndExclusive = areaGroup.range.last + 1,
                    line = lineAt(source, areaGroup.range.first),
                )
                if (areaGroup.value == SYNC) {
                    if (hasDirectAreaUse(codeOnly, areaGroup.range.last + 1)) {
                        syncAccesses += access
                    } else {
                        unsafe += UnsafeStorageAccess(
                            offset = match.range.first,
                            line = lineAt(source, match.range.first),
                            detail = "storage.sync 별칭 또는 값 참조를 정적으로 변환할 수 없습니다.",
                        )
                    }
                } else {
                    localAccesses += access
                }
            }

        COMPUTED_STORAGE.findAll(codeOnly)
            .filter { match ->
                val root = ROOT_IDENTIFIER.find(match.value)
                    ?: return@filter false
                isGlobalRoot(
                    codeOnly,
                    match.range.first + root.range.first,
                )
            }
            .forEach { match ->
                unsafe += UnsafeStorageAccess(
                    offset = match.range.first,
                    line = lineAt(source, match.range.first),
                    detail = "계산된 storage 영역 접근을 정적으로 변환할 수 없습니다.",
                )
            }
        DOUBLE_COMPUTED.findAll(codeOnly)
            .filter { match ->
                val root = requireNotNull(match.groups[1])
                isGlobalRoot(codeOnly, root.range.first) &&
                    computedRootMayBeStorage(
                        source.substring(requireNotNull(match.groups[2]).range),
                    )
            }
            .forEach { match ->
                unsafe += UnsafeStorageAccess(
                    offset = match.range.first,
                    line = lineAt(source, match.range.first),
                    detail = "계산된 storage 영역 접근을 정적으로 변환할 수 없습니다.",
                )
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
            EVAL_CALL.findAll(codeOnly).forEach { match ->
                unsafe += UnsafeStorageAccess(
                    offset = match.range.first,
                    line = lineAt(source, match.range.first),
                    detail = "eval을 통한 storage 접근을 정적으로 변환할 수 없습니다.",
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

    private fun computedRootMayBeStorage(expression: String): Boolean {
        val literal = SIMPLE_STRING.matchEntire(expression.trim())
            ?.groupValues
            ?.get(2)
        return literal == null || literal == "storage"
    }

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

        private val STATIC_STORAGE = Regex(
            ROOT + MEMBER + """storage\b""" + MEMBER + """(sync|local)\b""",
        )
        private val COMPUTED_STORAGE = Regex(
            ROOT + """\s*(?:\?\.)?\s*\[[^\]\r\n]{1,160}]""" +
                MEMBER + """sync\b""" +
                "|" +
                ROOT + MEMBER + """storage\b\s*(?:\?\.)?\s*""" +
                """\[[^\]\r\n]{0,160}]""",
        )
        private val DOUBLE_COMPUTED = Regex(
            ROOT + """\s*(?:\?\.)?\s*\[([^\]\r\n]{0,160})]""" +
                """\s*(?:\?\.)?\s*\[[^\]\r\n]{0,160}]""",
        )
        private val STORAGE_ALIAS = Regex(
            """\b(?:const|let|var)\s+[A-Za-z_$][\w$]*\s*=\s*""" +
                ROOT + MEMBER + """(storage)\b""",
        )
        private val STORAGE_DESTRUCTURING = Regex(
            """\b(?:const|let|var)\s*\{[^}\r\n]*\bsync\b[^}\r\n]*}\s*=\s*""" +
                ROOT + MEMBER + """storage\b""",
        )
        private val ROOT_ALIAS = Regex(
            """\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*""" +
                """(chrome|browser)\b""",
        )
        private val ROOT_STORAGE_DESTRUCTURING = Regex(
            """\b(?:const|let|var)\s*\{[^}\r\n]*\bstorage\b""" +
                """(?:\s*:\s*([A-Za-z_$][\w$]*))?[^}\r\n]*}\s*=\s*""" +
                """(?:chrome|browser)\b""",
        )
        private val PROPERTY_ROOT_STORAGE = Regex(
            """(?:\?\.|\.)\s*(?:chrome|browser)\b""" +
                MEMBER + """storage\b""" + MEMBER + """sync\b""",
        )
        private val EVAL_CALL = Regex("""\beval\s*\(""")
        private val RAW_STORAGE_MENTION = Regex(
            """\b(?:chrome|browser)\b[^\r\n]{0,160}\bstorage\b""" +
                """[^\r\n]{0,160}\bsync\b""",
        )
        private val ROOT_IDENTIFIER = Regex("""\b(?:chrome|browser)\b""")
        private val SIMPLE_STRING = Regex("""(["'])([A-Za-z_$][\w$]*)\1""")

        private fun aliasStorageSyncUse(alias: String) = Regex(
            """\b${Regex.escape(alias)}\b""" +
                MEMBER + """storage\b""" + MEMBER + """sync\b""",
        )

        private fun aliasSyncUse(alias: String) = Regex(
            """\b${Regex.escape(alias)}\b""" + MEMBER + """sync\b""",
        )
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
