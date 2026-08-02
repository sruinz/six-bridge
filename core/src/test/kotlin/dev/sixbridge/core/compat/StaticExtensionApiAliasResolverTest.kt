package dev.sixbridge.core.compat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StaticExtensionApiAliasResolverTest {
    private val resolver = StaticExtensionApiAliasResolver()

    @Test
    fun `globalThis const 구조 분해의 chrome와 browser namespace를 해석한다`() {
        val source = "const root=globalThis; const {chrome:C,browser:B}=root; C.userScripts.register([]); B.scripting.executeScript({});"

        val result = resolve(source)

        assertEquals(setOf("userScripts", "scripting"), result.namespaces)
        assertFalse(result.dynamicUsage)
    }

    @Test
    fun `mutable 또는 computed alias는 dynamic으로 남긴다`() {
        assertTrue(resolve("let root=globalThis; root.chrome.tabs.query({});").dynamicUsage)
        assertTrue(resolve("const root=globalThis; root[name].tabs.query({});").dynamicUsage)
    }

    @Test
    fun `기본값이 있는 구조 분해 alias는 dynamic으로 남긴다`() {
        val result = resolve(
            "const root=globalThis; const {chrome:C=fallback}=root; C.tabs.query({});",
        )

        assertTrue(result.dynamicUsage)
    }

    @Test
    fun `rest 구조 분해 alias는 dynamic으로 남긴다`() {
        val result = resolve(
            "const root=globalThis; const {...aliases}=root; aliases.chrome.tabs.query({});",
        )

        assertTrue(result.dynamicUsage)
    }

    @Test
    fun `const root alias chain은 namespace를 해석한다`() {
        val result = resolve(
            "const root=globalThis; const api=root.chrome; const copy=api; copy.tabs.query({});",
        )

        assertEquals(setOf("tabs"), result.namespaces)
        assertFalse(result.dynamicUsage)
    }

    @Test
    fun `선언 이전 alias 사용은 dynamic으로 남긴다`() {
        val result = resolve("api.tabs.query({}); const api=chrome;")

        assertTrue(result.dynamicUsage)
    }

    @Test
    fun `함수 인자로 shadow된 alias 사용은 dynamic으로 남긴다`() {
        val result = resolve("const api=chrome; function run(api) { api.tabs.query({}); }")

        assertTrue(result.dynamicUsage)
    }

    @Test
    fun `함수 내부 const로 shadow된 alias 사용은 dynamic으로 남긴다`() {
        val result = resolve(
            "const api=chrome; function run() { const api=other; api.tabs.query({}); }",
        )

        assertTrue(result.dynamicUsage)
    }

    @Test
    fun `for initializer alias는 top level const로 해석하지 않는다`() {
        val result = resolve("for (const api=chrome; ready;) { api.tabs.query({}); break; }")

        assertTrue(result.dynamicUsage)
    }

    @Test
    fun `논리와 shift assignment alias는 dynamic으로 남긴다`() {
        listOf("&&=", "||=", "??=", "**=", "<<=", ">>=", ">>>=").forEach { operator ->
            val result = resolve("const api=chrome; api $operator fallback;")

            assertTrue(result.dynamicUsage, operator)
        }
    }

    @Test
    fun `구조 분해 assignment alias는 dynamic으로 남긴다`() {
        val result = resolve("const api=chrome; ({ api } = source);")

        assertTrue(result.dynamicUsage)
    }

    @Test
    fun `달러가 포함된 alias namespace를 해석한다`() {
        listOf("\$api", "api\$").forEach { alias ->
            val result = resolve("const $alias=chrome; $alias.tabs.query({});")

            assertEquals(setOf("tabs"), result.namespaces, alias)
            assertFalse(result.dynamicUsage, alias)
        }
    }

    @Test
    fun `optional static bracket alias namespace를 해석한다`() {
        val result = resolve("const api=chrome; api?.[\"tabs\"].query({});")

        assertEquals(setOf("tabs"), result.namespaces)
        assertFalse(result.dynamicUsage)
    }

    @Test
    fun `grouping 안의 immutable alias namespace를 해석한다`() {
        val result = resolve("const api=chrome; (api.tabs.query({}));")

        assertEquals(setOf("tabs"), result.namespaces)
        assertFalse(result.dynamicUsage)
    }

    @Test
    fun `if 조건의 immutable alias namespace를 해석한다`() {
        val result = resolve("const api=chrome; if (api.tabs) {}")

        assertEquals(setOf("tabs"), result.namespaces)
        assertFalse(result.dynamicUsage)
    }

    private fun resolve(source: String): StaticExtensionApiAliases = resolver.resolve(
        source = JavaScriptLexicalMask.mask(source, maskLiterals = false),
        codeOnly = JavaScriptLexicalMask.mask(source, maskLiterals = true),
        checkCancelled = {},
    )
}
