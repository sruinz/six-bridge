package dev.sixbridge.core.compat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction.REPORT
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

internal data class LocalizedManifestName(
    val value: String,
    val issue: CompatibilityIssue?,
)

internal object ManifestLocalization {
    private val messageToken = Regex("^__MSG_([A-Za-z0-9_@]+)__$")
    private val localeName = Regex("^[A-Za-z0-9_@-]+$")
    private val json = Json { isLenient = false }

    fun resolveName(root: Path, manifest: JsonObject): LocalizedManifestName? {
        val raw = manifest.stringOrNull("name") ?: return null
        val key = messageToken.matchEntire(raw)?.groupValues?.get(1)
            ?: return LocalizedManifestName(raw, null)
        val locale = manifest.stringOrNull("default_locale")
            ?: return LocalizedManifestName(raw, missingIssue(key))
        if (!localeName.matches(locale)) return LocalizedManifestName(raw, missingIssue(key))

        val message = readMessages(root.resolve("_locales").resolve(locale).resolve("messages.json"))
            ?.get(key)
            .let { it as? JsonObject }
            ?.stringOrNull("message")
            ?.takeIf { it.isNotBlank() }
        return if (message == null) {
            LocalizedManifestName(raw, missingIssue(key))
        } else {
            LocalizedManifestName(message, null)
        }
    }

    private fun readMessages(path: Path): JsonObject? = runCatching {
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        require(attributes.isRegularFile && !attributes.isSymbolicLink)
        require(attributes.size() <= AnalysisSafetyPolicy.mobile.maxManifestBytes)
        val contents = FileChannel.open(path, READ, NOFOLLOW_LINKS).use { channel ->
            val size = channel.size()
            require(size == attributes.size() && size <= AnalysisSafetyPolicy.mobile.maxManifestBytes)
            val bytes = ByteBuffer.allocate(size.toInt())
            while (bytes.hasRemaining()) {
                require(channel.read(bytes) >= 0)
            }
            require(channel.size() == size)
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(REPORT)
                .onUnmappableCharacter(REPORT)
                .decode(ByteBuffer.wrap(bytes.array()))
                .toString()
        }
        require(StrictJsonKeyValidator(contents).isValidWithoutDuplicates())
        json.parseToJsonElement(contents) as? JsonObject
    }.getOrNull()

    private fun missingIssue(key: String) = CompatibilityIssue(
        code = "manifest_name_locale_missing",
        message = "default_locale messages.json에서 '$key' 이름을 해석할 수 없습니다.",
        blocking = false,
    )

    private fun JsonObject.stringOrNull(name: String): String? =
        (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
}

private class StrictJsonKeyValidator(private val source: String) {
    private var index = 0

    fun isValidWithoutDuplicates(): Boolean = runCatching {
        skipWhitespace()
        value()
        skipWhitespace()
        require(index == source.length)
    }.isSuccess

    private fun value() {
        skipWhitespace()
        when (peek()) {
            '{' -> obj()
            '[' -> array()
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            '-', in '0'..'9' -> number()
            else -> error("JSON value expected")
        }
    }

    private fun obj() {
        require(next() == '{')
        skipWhitespace()
        if (peek() == '}') {
            index++
            return
        }
        val keys = mutableSetOf<String>()
        while (true) {
            skipWhitespace()
            val key = string()
            require(keys.add(key))
            skipWhitespace()
            require(next() == ':')
            value()
            skipWhitespace()
            when (next()) {
                '}' -> return
                ',' -> Unit
                else -> error("object separator expected")
            }
        }
    }

    private fun array() {
        require(next() == '[')
        skipWhitespace()
        if (peek() == ']') {
            index++
            return
        }
        while (true) {
            value()
            skipWhitespace()
            when (next()) {
                ']' -> return
                ',' -> Unit
                else -> error("array separator expected")
            }
        }
    }

    private fun string(): String {
        require(next() == '"')
        return buildString {
            while (true) {
                val character = next()
                when (character) {
                    '"' -> return@buildString
                    '\\' -> append(escapedCharacter())
                    else -> {
                        require(character >= ' ')
                        append(character)
                    }
                }
            }
        }
    }

    private fun escapedCharacter(): Char = when (val escaped = next()) {
        '"', '\\', '/' -> escaped
        'b' -> '\b'
        'f' -> '\u000C'
        'n' -> '\n'
        'r' -> '\r'
        't' -> '\t'
        'u' -> unicodeCharacter()
        else -> error("invalid escape")
    }

    private fun unicodeCharacter(): Char = source.substring(index, index + 4)
        .also { index += 4 }
        .toInt(16)
        .toChar()

    private fun number() {
        if (peek() == '-') index++
        if (peek() == '0') {
            index++
        } else {
            digits()
        }
        if (peek() == '.') {
            index++
            digits()
        }
        if (peek() in setOf('e', 'E')) {
            index++
            if (peek() in setOf('+', '-')) index++
            digits()
        }
    }

    private fun digits() {
        require(peek() in '1'..'9')
        while (peek() in '0'..'9') index++
    }

    private fun literal(expected: String) {
        require(source.startsWith(expected, index))
        index += expected.length
    }

    private fun skipWhitespace() {
        while (peek() in setOf(' ', '\n', '\r', '\t')) index++
    }

    private fun peek(): Char? = source.getOrNull(index)
    private fun next(): Char = source.getOrNull(index++) ?: error("unexpected end")
}
