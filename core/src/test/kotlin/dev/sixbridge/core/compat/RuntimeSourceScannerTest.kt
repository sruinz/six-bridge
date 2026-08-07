package dev.sixbridge.core.compat

import dev.sixbridge.core.conversion.SafeHtmlDocument
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RuntimeSourceScannerTest {
    @Test
    fun `정확한 HTML URL attribute span만 반환한다`() {
        val source =
            "<html><head></head><body><a href='chrome://extensions'>x</a>" +
                "<!-- <a href='chrome://flags'> -->" +
                "<script src=\"worker.js\">" +
                "const x=\"edge://extensions\"</script>" +
                "<form action='/save'><button formaction=\"/submit\">x</button></form>" +
                "</body></html>"

        val result = SafeHtmlDocument.parse(source) {}

        assertEquals(
            listOf("chrome://extensions", "worker.js", "/save", "/submit"),
            result.urlAttributes.map { it.value },
        )
        result.urlAttributes.forEach { attribute ->
            assertEquals(attribute.value, source.substring(attribute.contentRange))
            assertEquals(attribute.quote, source[attribute.openingQuoteOffset])
            assertEquals(attribute.quote, source[attribute.closingQuoteOffset])
        }
    }

    @Test
    fun `escape 없는 완전한 JavaScript 문자열만 rewrite span으로 반환한다`() {
        val source = """
            const a = 'chrome://extensions';
            const b = "edge://extensions/";
            const dynamic = 'chrome://' + page;
            const escaped = 'chrome:\\/\\/extensions';
            // 'chrome://flags'
        """.trimIndent()

        val literals = RuntimeSourceScanner.scanStaticStringLiterals(source) {}

        assertEquals(
            listOf("chrome://extensions", "edge://extensions/", "chrome://"),
            literals.map { it.value },
        )
        literals.forEach { literal ->
            assertEquals(literal.value, source.substring(literal.contentRange))
            assertEquals(literal.quote, source[literal.openingQuoteOffset])
            assertEquals(literal.quote, source[literal.closingQuoteOffset])
        }
    }

    @Test
    fun `exact 내부 주소의 plus 조합은 제외하고 진단 조각은 유지한다`() {
        val source = """
            const left = prefix +   'chrome://extensions';
            const right = "edge://extensions/"   + suffix;
            const diagnostic = 'chrome://' + page;
            const independent = 'chrome://extensions';
        """.trimIndent()

        assertEquals(
            listOf("chrome://", "chrome://extensions"),
            RuntimeSourceScanner.scanStaticStringLiterals(source) {}.map { it.value },
        )
    }

    @Test
    fun `BOM whitespace로 구분된 plus와 plus equals 조합도 제외한다`() {
        val source = """
            const left = prefix +﻿'chrome://extensions';
            const right = "edge://extensions/"﻿ + suffix;
            value +=﻿'chrome://extensions';
        """.trimIndent()

        assertEquals(
            emptyList(),
            RuntimeSourceScanner.scanStaticStringLiterals(source) {},
        )
    }

    @Test
    fun `comment regex template과 미종결 literal은 반환하지 않는다`() {
        val source = """
            /* 'chrome://extensions' */
            const pattern = /['"]chrome:\/\/extensions['"]/;
            const template = `chrome://extensions`;
            const escapedQuote = 'chrome:\'//extensions';
            const lineContinuation = 'chrome:\
            //extensions';
            const unterminated = 'chrome://extensions
        """.trimIndent()

        assertEquals(
            emptyList(),
            RuntimeSourceScanner.scanStaticStringLiterals(source) {},
        )
    }

    @Test
    fun `literal scanner는 16384 UTF-16 code unit 간격으로 취소를 전달한다`() {
        val source = "const value='" + "a".repeat(16_384 * 2) + "';"
        var checkpoints = 0

        assertFailsWith<CancellationException> {
            RuntimeSourceScanner.scanStaticStringLiterals(source) {
                checkpoints += 1
                if (checkpoints == 2) throw CancellationException("cancel")
            }
        }
        assertEquals(2, checkpoints)
        assertTrue(source.length > 16_384)
    }

    @Test
    fun `literal span 추출 pass에서 발생한 취소도 전달한다`() {
        val source = "const value='" + "a".repeat(16_384 * 2) + "';"
        var spanCheckpoints = 0

        assertFailsWith<CancellationException> {
            RuntimeSourceScanner.scanStaticStringLiterals(source) {
                val directScannerFrame = Throwable().stackTrace.firstOrNull {
                    it.className.endsWith("RuntimeSourceScanner") ||
                        it.className.endsWith("JavaScriptLexicalMask")
                }
                if (directScannerFrame?.methodName == "scanStaticStringLiterals") {
                    spanCheckpoints += 1
                    if (spanCheckpoints == 2) throw CancellationException("span-cancel")
                }
            }
        }
        assertEquals(2, spanCheckpoints)
    }

    @Test
    fun `plus 문맥 lookaround 중 취소도 bounded 간격으로 전달한다`() {
        val source = "prefix +" + " ".repeat(16_384 * 2) + "'chrome://extensions';"
        var contextCheckpoints = 0

        assertFailsWith<CancellationException> {
            RuntimeSourceScanner.scanStaticStringLiterals(source) {
                val directScannerFrame = Throwable().stackTrace.firstOrNull {
                    it.className.endsWith("RuntimeSourceScanner") ||
                        it.className.endsWith("JavaScriptLexicalMask")
                }
                if (directScannerFrame?.methodName?.startsWith(
                        "isConcatenatedInternalUrl",
                    ) == true
                ) {
                    contextCheckpoints += 1
                    if (contextCheckpoints == 2) {
                        throw CancellationException("context-cancel")
                    }
                }
            }
        }
        assertEquals(2, contextCheckpoints)
    }

    @Test
    fun `HTML attribute span 추출 pass에서 발생한 취소도 전달한다`() {
        val source = "<html><head></head><body><a data-long='" +
            "a".repeat(16_384 * 2) +
            "' href='chrome://extensions'>x</a></body></html>"
        var spanCheckpoints = 0

        assertFailsWith<CancellationException> {
            SafeHtmlDocument.parse(source) {
                val directScannerFrame = Throwable().stackTrace.firstOrNull {
                    it.className.endsWith("SafeHtmlDocument")
                }
                if (directScannerFrame?.methodName?.startsWith("staticUrlAttributes") == true) {
                    spanCheckpoints += 1
                    if (spanCheckpoints == 2) {
                        throw CancellationException("attribute-span-cancel")
                    }
                }
            }
        }
        assertEquals(2, spanCheckpoints)
    }
}
