package dev.sixbridge.manager.ui

import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import org.junit.Test

class HomeScreenTest {
    @Test
    fun `icon decode는 일반 Exception만 fallback한다`() {
        assertEquals("decoded", decodeIconOrNull { "decoded" })
        assertNull(
            decodeIconOrNull<String> {
                throw IllegalArgumentException("malformed bitmap")
            },
        )
    }

    @Test
    fun `icon decode는 cancellation과 Error를 전파한다`() {
        val cancellation = CancellationException("cancelled")
        val fatal = LinkageError("fatal")

        assertSame(
            cancellation,
            assertFailsWith<CancellationException> {
                decodeIconOrNull<String> { throw cancellation }
            },
        )
        assertSame(
            fatal,
            assertFailsWith<LinkageError> {
                decodeIconOrNull<String> { throw fatal }
            },
        )
    }

    @Test
    fun `fallback은 첫 extended grapheme cluster를 표시한다`() {
        mapOf(
            "😀 extension" to "😀",
            "e\u0301clair" to "e\u0301",
            "👨‍👩‍👧‍👦 family" to "👨‍👩‍👧‍👦",
        ).forEach { (name, expected) ->
            assertEquals(expected, firstGraphemeOrQuestionMark(name))
        }
        assertEquals("?", firstGraphemeOrQuestionMark(" \t\n "))
    }
}
