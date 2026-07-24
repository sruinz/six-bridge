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
        val scan = scanRootMembers(source, codeOnly)
        val hazards = environmentHazards(
            source,
            codeOnly,
            sourceWithoutComments,
            scan.recognizedRootStarts,
        )
        val unsafe = (scan.unsafeAccesses + hazards)
            .distinctBy { it.offset to it.detail }
            .sortedWith(
                compareBy(
                    UnsafeStorageAccess::offset,
                    UnsafeStorageAccess::detail,
                ),
            )
        return StorageTokenInspection(
            syncAccesses = scan.syncAccesses
                .distinctBy { Triple(it.areaStart, it.areaEndExclusive, it.area) }
                .sortedBy(StaticStorageAccess::areaStart),
            localAccesses = scan.localAccesses
                .distinctBy { Triple(it.areaStart, it.areaEndExclusive, it.area) }
                .sortedBy(StaticStorageAccess::areaStart),
            unsafeAccesses = unsafe,
            storageIntent = scan.syncAccesses.isNotEmpty() ||
                scan.unsafeAccesses.isNotEmpty() ||
                (
                    unsafe.isNotEmpty() &&
                        RAW_STORAGE_MENTION.containsMatchIn(sourceWithoutComments)
                    ),
        )
    }

    private fun scanRootMembers(
        source: String,
        codeOnly: String,
    ): RootMemberScan {
        val sync = mutableListOf<StaticStorageAccess>()
        val local = mutableListOf<StaticStorageAccess>()
        val unsafe = mutableListOf<UnsafeStorageAccess>()
        val recognized = mutableSetOf<Int>()
        ROOT_IDENTIFIER.findAll(codeOnly).forEach { rootMatch ->
            val rootStart = rootMatch.range.first
            if (codeOnly.getOrNull(rootStart - 1)?.let(::isIdentifierPart) == true) {
                return@forEach
            }
            val rootIsProperty = previousNonWhitespace(codeOnly, rootStart) in
                setOf('.', '?')
            val namespace = readProperty(
                source,
                codeOnly,
                rootMatch.range.last + 1,
            ) ?: return@forEach
            recognized += rootStart
            if (namespace.staticKey != STORAGE) {
                if (!rootIsProperty && namespace.computed && namespace.staticKey == null) {
                    unsafe += unsafe(
                        source,
                        rootStart,
                        "계산된 browser API namespace를 안전하게 분석할 수 없습니다.",
                    )
                }
                return@forEach
            }
            val area = readProperty(source, codeOnly, namespace.endExclusive)
            if (area == null) {
                unsafe += unsafe(
                    source,
                    rootStart,
                    "storage 객체 별칭 또는 구조 분해를 정적으로 변환할 수 없습니다.",
                )
                return@forEach
            }
            if (rootIsProperty) {
                if (area.staticKey == SYNC || area.staticKey == null) {
                    unsafe += unsafe(
                        source,
                        rootStart,
                        "객체 속성의 chrome 또는 browser storage 접근을 안전하게 변환할 수 없습니다.",
                    )
                }
                return@forEach
            }
            if (namespace.computed) {
                if (area.staticKey == SYNC || area.staticKey == null) {
                    unsafe += unsafe(
                        source,
                        rootStart,
                        "계산된 storage 영역 접근을 정적으로 변환할 수 없습니다.",
                    )
                }
                return@forEach
            }
            when {
                area.computed &&
                    (area.staticKey == SYNC || area.staticKey == null) ->
                    unsafe += unsafe(
                        source,
                        rootStart,
                        "계산된 storage 영역 접근을 정적으로 변환할 수 없습니다.",
                    )
                area.computed -> Unit
                area.staticKey == SYNC &&
                    hasDirectAreaUse(codeOnly, area.endExclusive) ->
                    sync += access(source, rootMatch.value, SYNC, area)
                area.staticKey == SYNC ->
                    unsafe += unsafe(
                        source,
                        rootStart,
                        "storage.sync 별칭 또는 값 참조를 정적으로 변환할 수 없습니다.",
                    )
                area.staticKey == LOCAL ->
                    local += access(source, rootMatch.value, LOCAL, area)
            }
        }
        return RootMemberScan(sync, local, unsafe, recognized)
    }

    private fun environmentHazards(
        source: String,
        codeOnly: String,
        sourceWithoutComments: String,
        recognizedRootStarts: Set<Int>,
    ): List<UnsafeStorageAccess> = buildList {
        EVAL_IDENTIFIER.findAll(codeOnly).forEach { match ->
            add(unsafe(source, match.range.first, "eval 실행 환경은 자동 변환할 수 없습니다."))
        }
        WITH_STATEMENT.findAll(codeOnly).forEach { match ->
            add(unsafe(source, match.range.first, "with 실행 환경은 자동 변환할 수 없습니다."))
        }
        GLOBAL_ROOT_ACCESS.findAll(sourceWithoutComments).forEach { match ->
            if (codeOnly.getOrNull(match.range.first)?.isJavaIdentifierStart() == true) {
                add(
                    unsafe(
                        source,
                        match.range.first,
                        "global root assignment 또는 접근은 자동 변환할 수 없습니다.",
                    ),
                )
            }
        }
        ROOT_IDENTIFIER.findAll(codeOnly).forEach { match ->
            val start = match.range.first
            if (codeOnly.getOrNull(start - 1)?.let(::isIdentifierPart) == true ||
                start in recognizedRootStarts ||
                previousNonWhitespace(codeOnly, start) in setOf('.', '?') ||
                allowedPropertyNameAlias(codeOnly, match)
            ) {
                return@forEach
            }
            add(
                unsafe(
                    source,
                    start,
                    "chrome 또는 browser root 별칭, binding, assignment 또는 구조 분해가 있어 " +
                        "실행 환경을 안전하게 증명할 수 없습니다.",
                ),
            )
        }
    }

    private fun allowedPropertyNameAlias(
        codeOnly: String,
        root: MatchResult,
    ): Boolean {
        var cursor = skipWhitespace(codeOnly, root.range.last + 1)
        if (codeOnly.startsWith("as", cursor) &&
            codeOnly.getOrNull(cursor + 2)?.isJavaIdentifierPart() != true
        ) {
            cursor = skipWhitespace(codeOnly, cursor + 2)
            val alias = readIdentifier(codeOnly, cursor) ?: return false
            return alias.first !in ROOT_NAMES
        }
        if (codeOnly.getOrNull(cursor) == ':') {
            cursor = skipWhitespace(codeOnly, cursor + 1)
            val alias = readIdentifier(codeOnly, cursor) ?: return false
            return alias.first !in ROOT_NAMES
        }
        return false
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
            val identifier = readIdentifier(codeOnly, cursor) ?: return null
            return JavaScriptProperty(
                staticKey = identifier.first,
                computed = false,
                keyStart = cursor,
                keyEndExclusive = identifier.second,
                endExclusive = identifier.second,
            )
        }
        val closing = matchingBracket(codeOnly, cursor) ?: return JavaScriptProperty(
            staticKey = null,
            computed = true,
            keyStart = cursor,
            keyEndExclusive = cursor + 1,
            endExclusive = codeOnly.length,
        )
        return JavaScriptProperty(
            staticKey = staticComputedKey(
                source.substring(cursor + 1, closing).trim(),
            ),
            computed = true,
            keyStart = cursor + 1,
            keyEndExclusive = closing,
            endExclusive = closing + 1,
        )
    }

    private fun staticComputedKey(expression: String): String? {
        val withoutComments = JavaScriptLexicalMask.mask(
            expression,
            maskLiterals = false,
        ).trim()
        val quoted = COMPUTED_STRING.matchEntire(withoutComments)
        if (quoted != null) return quoted.groupValues[2]
        return COMPUTED_TEMPLATE.matchEntire(expression.trim())
            ?.groupValues
            ?.get(1)
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

    private fun hasDirectAreaUse(source: String, start: Int): Boolean {
        var cursor = skipWhitespace(source, start)
        if (source.startsWith("?.", cursor)) {
            cursor += 2
        } else if (source.getOrNull(cursor) == '.') {
            cursor++
        } else {
            return source.getOrNull(cursor) == '['
        }
        cursor = skipWhitespace(source, cursor)
        return source.getOrNull(cursor)?.isJavaIdentifierStart() == true
    }

    private fun access(
        source: String,
        root: String,
        areaName: String,
        area: JavaScriptProperty,
    ) = StaticStorageAccess(
        root = root,
        area = areaName,
        areaStart = area.keyStart,
        areaEndExclusive = area.keyEndExclusive,
        line = lineAt(source, area.keyStart),
    )

    private fun unsafe(
        source: String,
        offset: Int,
        detail: String,
    ) = UnsafeStorageAccess(
        offset = offset,
        line = lineAt(source, offset),
        detail = detail,
    )

    private fun readIdentifier(source: String, start: Int): Pair<String, Int>? {
        if (source.getOrNull(start)?.isJavaIdentifierStart() != true) return null
        var end = start + 1
        while (source.getOrNull(end)?.isJavaIdentifierPart() == true) end++
        return source.substring(start, end) to end
    }

    private fun previousNonWhitespace(source: String, start: Int): Char? {
        var cursor = start - 1
        while (cursor >= 0 && source[cursor].isWhitespace()) cursor--
        return source.getOrNull(cursor)
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

    private companion object {
        const val STORAGE = "storage"
        const val SYNC = "sync"
        const val LOCAL = "local"
        val ROOT_NAMES = setOf("chrome", "browser")
        val ROOT_IDENTIFIER = Regex("""\b(?:chrome|browser)\b""")
        val EVAL_IDENTIFIER = Regex("""\beval\b""")
        val WITH_STATEMENT = Regex("""\bwith\s*\(""")
        val RAW_STORAGE_MENTION = Regex(
            """\b(?:chrome|browser)\b.*\bstorage\b.*\bsync\b""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val GLOBAL_ROOT_ACCESS = Regex(
            """\b(?:globalThis|window)\s*(?:\.\s*(?:chrome|browser)\b|""" +
                """\[\s*(["'])(?:chrome|browser)\1\s*])""",
        )
        val COMPUTED_STRING = Regex("""(["'])([A-Za-z_$][\w$]*)\1""")
        val COMPUTED_TEMPLATE = Regex("""`([A-Za-z_$][\w$]*)`""")
    }

    private data class RootMemberScan(
        val syncAccesses: List<StaticStorageAccess>,
        val localAccesses: List<StaticStorageAccess>,
        val unsafeAccesses: List<UnsafeStorageAccess>,
        val recognizedRootStarts: Set<Int>,
    )

    private data class JavaScriptProperty(
        val staticKey: String?,
        val computed: Boolean,
        val keyStart: Int,
        val keyEndExclusive: Int,
        val endExclusive: Int,
    )
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
    val storageIntent: Boolean,
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
