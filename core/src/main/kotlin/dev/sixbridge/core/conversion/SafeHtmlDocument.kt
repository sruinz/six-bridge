package dev.sixbridge.core.conversion

internal object SafeHtmlDocument {
    data class Input(
        val normalizedType: String,
        val line: Int,
    )

    data class Result(
        val headContentOffset: Int?,
        val inputs: List<Input>,
        val safeToInject: Boolean,
    )

    fun insertExternalScript(
        source: String,
        absolutePath: String,
    ): String {
        require(
            absolutePath.startsWith('/') &&
                ActionPopupEntrySafety.isSafeUrlPath(absolutePath),
        ) {
            "script asset은 extension 절대 경로만 허용합니다."
        }
        val offset = parse(source) {}.headContentOffset
            ?: throw IllegalStateException("popup head 삽입 경계를 확인할 수 없습니다.")
        val scriptTag = "<script src=\"$absolutePath\"></script>"
        return source.substring(0, offset) + scriptTag + source.substring(offset)
    }

    fun parse(
        source: String,
        checkCancelled: () -> Unit,
    ): Result {
        var nextCheckpoint = 0
        fun checkThrough(endExclusive: Int) {
            while (nextCheckpoint < endExclusive) {
                checkCancelled()
                nextCheckpoint += CHECKPOINT_INTERVAL
            }
        }

        var position = 0
        var line = 1
        fun advance(endExclusive: Int) {
            checkThrough(endExclusive)
            for (index in position until endExclusive) {
                if (source[index] == '\n') line += 1
            }
            position = endExclusive
        }

        val inputs = mutableListOf<Input>()
        var headContentOffset: Int? = null
        var headOpen = false
        var headClosed = false
        var headOpenCount = 0
        var headCloseCount = 0
        var bodySeen = false
        var malformed = false
        var rawTextTag: String? = null

        while (position < source.length) {
            checkThrough(position + 1)
            val rawTag = rawTextTag
            if (rawTag != null) {
                val closing = findRawTextClosingTag(
                    source,
                    position,
                    rawTag,
                    ::checkThrough,
                )
                if (closing == null) {
                    malformed = true
                    advance(source.length)
                    break
                }
                advance(closing)
            }

            if (source.startsWith("<!--", position)) {
                val commentEnd = findCommentEnd(source, position + 4, ::checkThrough)
                if (commentEnd == null) {
                    malformed = true
                    advance(source.length)
                    break
                }
                advance(commentEnd)
                continue
            }
            if (source[position] != '<') {
                advance(position + 1)
                continue
            }

            val tagLine = line
            val tag = parseTag(source, position, ::checkThrough)
            if (tag == null) {
                if (looksLikeTagStart(source, position)) malformed = true
                advance(position + 1)
                continue
            }
            advance(tag.endExclusive)
            when (tag.name) {
                "head" -> if (tag.closing) {
                    headCloseCount += 1
                    if (!headOpen || headClosed ||
                        source.substring(tag.attributesStart, tag.attributesEnd)
                            .any { !it.isHtmlWhitespace() }
                    ) {
                        malformed = true
                    }
                    headOpen = false
                    headClosed = true
                } else {
                    headOpenCount += 1
                    if (tag.selfClosing || headOpen || headClosed || bodySeen) malformed = true
                    headOpen = true
                    headContentOffset = tag.endExclusive
                }
                "body" -> if (!tag.closing) {
                    bodySeen = true
                    if (headOpen) malformed = true
                }
                "input" -> if (!tag.closing) {
                    normalizedInputType(source, tag)?.let { type ->
                        inputs += Input(type, tagLine)
                    }
                }
            }
            if (!tag.closing && (tag.name == "script" || tag.name == "style")) {
                if (tag.selfClosing) {
                    malformed = true
                } else {
                    rawTextTag = tag.name
                }
            } else if (tag.closing && tag.name == rawTextTag) {
                rawTextTag = null
            }
        }

        val safeToInject = !malformed &&
            rawTextTag == null &&
            headOpenCount == 1 &&
            headCloseCount == 1 &&
            headClosed
        return Result(
            headContentOffset = headContentOffset.takeIf { safeToInject },
            inputs = inputs,
            safeToInject = safeToInject,
        )
    }

    private fun findCommentEnd(
        source: String,
        start: Int,
        checkThrough: (Int) -> Unit,
    ): Int? {
        var index = start
        while (index + 2 < source.length) {
            checkThrough(index + 3)
            if (source.startsWith("-->", index)) return index + 3
            index += 1
        }
        checkThrough(source.length)
        return null
    }

    private fun findRawTextClosingTag(
        source: String,
        start: Int,
        tagName: String,
        checkThrough: (Int) -> Unit,
    ): Int? {
        var index = start
        while (index < source.length) {
            checkThrough(index + 1)
            if (source[index] == '<' &&
                index + 2 + tagName.length <= source.length &&
                source[index + 1] == '/' &&
                source.regionMatches(
                    index + 2,
                    tagName,
                    0,
                    tagName.length,
                    ignoreCase = true,
                ) &&
                source.getOrNull(index + 2 + tagName.length).isTagNameBoundary()
            ) {
                return index
            }
            index += 1
        }
        return null
    }

    private fun parseTag(
        source: String,
        start: Int,
        checkThrough: (Int) -> Unit,
    ): Tag? {
        if (source[start] != '<') return null
        if (source.startsWith("<!--", start)) return null
        var index = start + 1
        val closing = source.getOrNull(index) == '/'
        if (closing) index += 1
        if (source.getOrNull(index)?.isAsciiLetter() != true) {
            if (source.getOrNull(index) == '!' || source.getOrNull(index) == '?') {
                return parseDeclaration(source, start, checkThrough)
            }
            return null
        }
        val nameStart = index
        while (source.getOrNull(index).isTagNameCharacter()) {
            checkThrough(index + 1)
            index += 1
        }
        val name = knownTagName(source, nameStart, index)
        if (!source.getOrNull(index).isTagNameBoundary()) return null

        var quote: Char? = null
        var cursor = index
        while (cursor < source.length) {
            checkThrough(cursor + 1)
            val character = source[cursor]
            if (quote != null) {
                if (character == quote) quote = null
            } else {
                when (character) {
                    '\'', '"' -> quote = character
                    '>' -> {
                        val last = source.lastNonWhitespace(index, cursor)
                        return Tag(
                            name = name,
                            closing = closing,
                            selfClosing = !closing && last >= index && source[last] == '/',
                            attributesStart = index,
                            attributesEnd = if (last >= index && source[last] == '/') {
                                last
                            } else {
                                cursor
                            },
                            endExclusive = cursor + 1,
                        )
                    }
                }
            }
            cursor += 1
        }
        return null
    }

    private fun parseDeclaration(
        source: String,
        start: Int,
        checkThrough: (Int) -> Unit,
    ): Tag? {
        var quote: Char? = null
        var index = start + 2
        while (index < source.length) {
            checkThrough(index + 1)
            val character = source[index]
            if (quote != null) {
                if (character == quote) quote = null
            } else {
                when (character) {
                    '\'', '"' -> quote = character
                    '>' -> return Tag(
                        name = "",
                        closing = false,
                        selfClosing = false,
                        attributesStart = index,
                        attributesEnd = index,
                        endExclusive = index + 1,
                    )
                }
            }
            index += 1
        }
        return null
    }

    private fun normalizedInputType(source: String, tag: Tag): String? {
        var index = tag.attributesStart
        var explicitType: String? = null
        while (index < tag.attributesEnd) {
            while (index < tag.attributesEnd && source[index].isHtmlWhitespace()) index += 1
            if (index >= tag.attributesEnd) break
            val nameStart = index
            while (index < tag.attributesEnd &&
                !source[index].isHtmlWhitespace() &&
                source[index] != '='
            ) {
                index += 1
            }
            val name = source.substring(nameStart, index).lowercaseAscii()
            while (index < tag.attributesEnd && source[index].isHtmlWhitespace()) index += 1
            var value: String? = null
            if (index < tag.attributesEnd && source[index] == '=') {
                index += 1
                while (index < tag.attributesEnd && source[index].isHtmlWhitespace()) index += 1
                if (index < tag.attributesEnd &&
                    (source[index] == '\'' || source[index] == '"')
                ) {
                    val quote = source[index++]
                    val valueStart = index
                    while (index < tag.attributesEnd && source[index] != quote) index += 1
                    value = source.substring(valueStart, index)
                    if (index < tag.attributesEnd) index += 1
                } else {
                    val valueStart = index
                    while (index < tag.attributesEnd && !source[index].isHtmlWhitespace()) index += 1
                    value = source.substring(valueStart, index)
                }
            }
            if (name == "type" && explicitType == null) explicitType = value.orEmpty()
        }
        val normalized = explicitType?.lowercaseAscii() ?: "text"
        if ('&' in normalized || '\\' in normalized) return null
        return normalized.takeIf { it in TARGET_INPUT_TYPES }
    }

    private fun looksLikeTagStart(source: String, start: Int): Boolean {
        val next = source.getOrNull(start + 1)
        return next.isAsciiLetter() ||
            (next == '/' && source.getOrNull(start + 2).isAsciiLetter()) ||
            next == '!' ||
            next == '?'
    }

    private fun knownTagName(source: String, start: Int, end: Int): String =
        KNOWN_TAG_NAMES.firstOrNull { name ->
            end - start == name.length && source.regionMatches(
                start,
                name,
                0,
                name.length,
                ignoreCase = true,
            )
        }.orEmpty()

    private fun String.lastNonWhitespace(start: Int, endExclusive: Int): Int {
        var index = endExclusive - 1
        while (index >= start && this[index].isHtmlWhitespace()) index -= 1
        return index
    }

    private fun String.lowercaseAscii(): String = buildString(length) {
        this@lowercaseAscii.forEach { character ->
            append(if (character in 'A'..'Z') character + 32 else character)
        }
    }

    private fun Char?.isAsciiLetter(): Boolean = this != null &&
        (this in 'A'..'Z' || this in 'a'..'z')

    private fun Char?.isTagNameCharacter(): Boolean = isAsciiLetter() ||
        this in '0'..'9' || this == '-' || this == ':'

    private fun Char?.isTagNameBoundary(): Boolean = this == null ||
        isHtmlWhitespace() || this == '/' || this == '>'

    private fun Char?.isHtmlWhitespace(): Boolean = this == ' ' ||
        this == '\t' || this == '\n' || this == '\r' || this == '\u000c'

    private data class Tag(
        val name: String,
        val closing: Boolean,
        val selfClosing: Boolean,
        val attributesStart: Int,
        val attributesEnd: Int,
        val endExclusive: Int,
    )

    private val TARGET_INPUT_TYPES = setOf(
        "text",
        "email",
        "password",
        "url",
        "tel",
        "search",
    )
    private val KNOWN_TAG_NAMES = listOf("head", "body", "input", "script", "style")
    private const val CHECKPOINT_INTERVAL = 16_384
}
