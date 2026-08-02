package dev.sixbridge.core.compat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaScriptLexicalMaskTest {
    @Test
    fun `함수와 class 선언 및 표현식의 regex 경계를 보존한다`() {
        val source = """
            export default function named() { return /a/.test('a') }
            const expression = function () { return /b/.test('b') }
            async function task() { return /c/.test('c') }
            const Type = class { method() { return /d/.test('d') } }
        """.trimIndent()

        val masked = JavaScriptLexicalMask.mask(source, maskLiterals = true)

        assertEquals(source.length, masked.length)
        assertTrue("function named" in masked)
        assertTrue("const expression = function" in masked)
        assertTrue("const Type = class" in masked)
    }

    @Test
    fun `대형 declaration bundle 순회 중 bounded checkpoint를 호출한다`() {
        val source = buildString {
            repeat(10_000) { append("export function f$it(){return chrome.runtime.id;}\n") }
        }
        var checkpoints = 0

        JavaScriptLexicalMask.mask(source, true) { checkpoints++ }

        assertTrue(checkpoints >= source.length / 16_384)
    }
}
