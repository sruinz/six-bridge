package dev.sixbridge.core.conversion

import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SafeHtmlDocumentTest {
    @Test
    fun `주석과 script 문자열을 제외하고 실제 input만 찾는다`() {
        val source = """
            <!doctype html><html><head><script>const fake='<input type="email">';</script></head>
            <body><!-- <input type="password"> -->
            <input><input type="EMAIL"><input type="checkbox"><input type="file">
            </body></html>
        """.trimIndent()

        val result = SafeHtmlDocument.parse(source) {}

        assertTrue(result.safeToInject)
        assertEquals(listOf("text", "email"), result.inputs.map { it.normalizedType })
        assertEquals(listOf(3, 3), result.inputs.map { it.line })
    }

    @Test
    fun `닫히지 않은 head 또는 중복 head는 삽입 불가로 판정한다`() {
        assertFalse(SafeHtmlDocument.parse("<html><head><body></html>") {}.safeToInject)
        assertFalse(SafeHtmlDocument.parse("<head></head><head></head>") {}.safeToInject)
        assertFalse(SafeHtmlDocument.parse("<head></head invalid>") {}.safeToInject)
        assertFalse(SafeHtmlDocument.parse("<body></body><head></head>") {}.safeToInject)
    }

    @Test
    fun `head 속성의 quoted 경계와 style raw text를 실제 tag와 구분한다`() {
        val source = """
            <html><head data-label="> <head></head>"><style>.x::after{content:'<input type="tel">'}</style></head>
            <body><input TYPE = 'PASSWORD'><input type=url><input type="search"></body></html>
        """.trimIndent()

        val result = SafeHtmlDocument.parse(source) {}

        assertTrue(result.safeToInject)
        assertNotNull(result.headContentOffset)
        assertEquals(
            listOf("password", "url", "search"),
            result.inputs.map { it.normalizedType },
        )
    }

    @Test
    fun `title과 textarea 본문의 URL attribute 문자열을 markup으로 보지 않는다`() {
        val source = """
            <html><head><title><a href="chrome://flags"><img src="chrome://inspect"></title></head>
            <body><textarea><form action="chrome://flags"><button formaction="chrome://inspect"></textarea>
            <a href="chrome://extensions">extensions</a></body></html>
        """.trimIndent()

        val result = SafeHtmlDocument.parse(source) {}

        assertTrue(result.safeToInject)
        assertEquals(
            listOf("chrome://extensions"),
            result.urlAttributes.map { it.value },
        )
    }

    @Test
    fun `self closing title과 textarea도 matching end tag까지 본문으로 처리한다`() {
        val source = """
            <html><head><title/><a href="chrome://flags"></title></head>
            <body><textarea/><form action="chrome://inspect"></textarea>
            <a href="chrome://extensions">extensions</a></body></html>
        """.trimIndent()

        val result = SafeHtmlDocument.parse(source) {}

        assertFalse(result.safeToInject)
        assertEquals(
            listOf("chrome://extensions"),
            result.urlAttributes.map { it.value },
        )
    }

    @Test
    fun `entity escape 또는 제외 type은 대상 input으로 추측하지 않는다`() {
        val source = """
            <head></head><body>
            <input type="e&#109;ail"><input type="pass\word"><input type="hidden">
            <input type="radio"><input type="button"><input type="tel">
            </body>
        """.trimIndent()

        assertEquals(
            listOf("tel"),
            SafeHtmlDocument.parse(source) {}.inputs.map { it.normalizedType },
        )
    }

    @Test
    fun `닫히지 않은 tag와 quoted attribute는 수정하지 않고 삽입 불가로 판정한다`() {
        assertFalse(SafeHtmlDocument.parse("<head data-x='broken></head>") {}.safeToInject)
        assertFalse(SafeHtmlDocument.parse("<head></head><input type='email'") {}.safeToInject)
    }

    @Test
    fun `self closing script와 style raw text tag는 복구하지 않는다`() {
        listOf("script", "style").forEach { tag ->
            assertFalse(
                SafeHtmlDocument.parse("<head><$tag/></head>") {}.safeToInject,
                tag,
            )
        }
    }

    @Test
    fun `scanner는 16384 UTF-16 code unit 간격으로 취소를 전달한다`() {
        var checkpoints = 0
        val source = "<head></head>" + " ".repeat(16_384)

        assertFailsWith<CancellationException> {
            SafeHtmlDocument.parse(source) {
                checkpoints += 1
                if (checkpoints == 2) throw CancellationException("cancel")
            }
        }
        assertEquals(2, checkpoints)
    }

    @Test
    fun `긴 ASCII tag name을 copy하기 전 scanner 진행 중 취소를 전달한다`() {
        val source = "<" + "a".repeat(16_384 * 2)
        var checkpoints = 0
        var secondCheckpointInsideTagScan = false

        assertFailsWith<CancellationException> {
            SafeHtmlDocument.parse(source) {
                checkpoints += 1
                if (checkpoints == 2) {
                    secondCheckpointInsideTagScan = Throwable().stackTrace.any {
                        it.className.endsWith("SafeHtmlDocument") &&
                            it.methodName == "parseTag"
                    }
                    throw CancellationException("tag-name-cancel")
                }
            }
        }

        assertEquals(2, checkpoints)
        assertTrue(secondCheckpointInsideTagScan)
    }
}
