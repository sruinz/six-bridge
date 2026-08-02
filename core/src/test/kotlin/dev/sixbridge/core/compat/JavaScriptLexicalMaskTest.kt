package dev.sixbridge.core.compat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    @Test
    fun `주석 사이의 선언 뒤 regex literal body를 mask한다`() {
        val source = """
            export /* comment */ function named() {} /chrome.runtime/.test('function')
            export default /* comment */ class Named {} /chrome.runtime/.test('class')
            async /* comment */ function task() {} /chrome.runtime/.test('async')
        """.trimIndent()

        val masked = JavaScriptLexicalMask.mask(source, maskLiterals = true)

        assertFalse("chrome.runtime" in masked)
    }
}
