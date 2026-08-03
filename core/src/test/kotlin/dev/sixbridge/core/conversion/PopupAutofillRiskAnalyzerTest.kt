package dev.sixbridge.core.conversion

import dev.sixbridge.core.compat.AnalysisControl
import dev.sixbridge.core.compat.RuntimeResourceIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PopupAutofillRiskAnalyzerTest {
    @Test
    fun `정적 대상 input은 confirmed 선택적 adapter와 partial issue를 만든다`() {
        val fixture = fixture(
            "<html><head></head><body><input type='email'><input type='file'></body></html>",
        )

        val risk = fixture.analyze()

        assertNotNull(risk)
        assertEquals(PopupAutofillEvidenceKind.CONFIRMED, risk.kind)
        assertEquals(AdapterDisposition.OPTIONAL_APPROVAL, risk.decision.disposition)
        assertEquals("popup-native-autofill-guard", risk.decision.id)
        assertEquals(1, risk.decision.version)
        assertFalse(risk.issue.blocking)
        assertTrue(risk.decision.evidence.any {
            it.relativePath == "popup.html" && it.detail.contains("email")
        })
        assertFalse(risk.decision.evidence.any { it.detail.contains("file") })
    }

    @Test
    fun `popup 역할의 정확한 createElement input 근거는 potential로 판정한다`() {
        val fixture = fixture(
            "<html><head><script src='popup.js'></script></head><body></body></html>",
            popupScript = "document.body.append(document.createElement(\"input\"));",
        )

        val risk = fixture.analyze()

        assertNotNull(risk)
        assertEquals(PopupAutofillEvidenceKind.POTENTIAL, risk.kind)
        assertTrue(risk.decision.evidence.any { it.relativePath == "popup.js" })
    }

    @Test
    fun `template 계산 comment와 content script의 createElement는 동적 근거가 아니다`() {
        val fixture = fixture(
            "<html><head><script src='popup.js'></script></head><body></body></html>",
            popupScript = """
                // createElement('input')
                const template = `createElement('input')`;
                createElement('in' + 'put');
            """.trimIndent(),
            contentScript = "document.createElement('input');",
        )

        assertEquals(PopupAutofillEvidenceKind.MANUAL, fixture.analyze()?.kind)
    }

    @Test
    fun `입력 근거가 없는 안전한 popup도 manual 고급 선택을 제공한다`() {
        val fixture = fixture("<html><head></head><body>설정</body></html>")

        val risk = fixture.analyze()

        assertNotNull(risk)
        assertEquals(PopupAutofillEvidenceKind.MANUAL, risk.kind)
        assertEquals(AdapterDisposition.OPTIONAL_APPROVAL, risk.decision.disposition)
    }

    @Test
    fun `안전한 삽입 경계를 증명하지 못하면 unavailable로 차단한다`() {
        val fixture = fixture(
            "<html><head><body><input type='email'></body></html>",
        )

        val risk = fixture.analyze()

        assertNotNull(risk)
        assertEquals(AdapterDisposition.UNAVAILABLE, risk.decision.disposition)
        assertTrue(risk.issue.blocking)
        assertEquals(emptyList(), risk.decision.changes)
    }

    @Test
    fun `popup이 없으면 위험 결정을 추가하지 않는다`() {
        val root = Files.createTempDirectory("six-popup-risk-none-")
        val manifest = manifest(popup = null)
        val index = RuntimeResourceIndex.create(root, manifest)

        assertNull(
            PopupAutofillRiskAnalyzer().analyze(
                root,
                manifest,
                index,
                AnalysisControl(),
            ),
        )
    }

    private fun fixture(
        popup: String,
        popupScript: String? = null,
        contentScript: String? = null,
    ): Fixture {
        val root = Files.createTempDirectory("six-popup-risk-")
        root.resolve("popup.html").writeText(popup)
        popupScript?.let { root.resolve("popup.js").writeText(it) }
        contentScript?.let { root.resolve("content.js").writeText(it) }
        val manifest = manifest(
            popup = "popup.html",
            contentScript = contentScript != null,
        )
        return Fixture(root, manifest)
    }

    private fun manifest(
        popup: String?,
        contentScript: Boolean = false,
    ): JsonObject {
        val action = popup?.let { ",\"action\":{\"default_popup\":\"$it\"}" }.orEmpty()
        val content = if (contentScript) {
            ",\"content_scripts\":[{\"matches\":[\"<all_urls>\"],\"js\":[\"content.js\"]}]"
        } else {
            ""
        }
        return Json.parseToJsonElement(
            """{"manifest_version":3,"name":"Popup","version":"1"$action$content}""",
        ) as JsonObject
    }

    private data class Fixture(
        val root: Path,
        val manifest: JsonObject,
    ) {
        fun analyze(): PopupAutofillRisk? {
            val control = AnalysisControl()
            return PopupAutofillRiskAnalyzer().analyze(
                root,
                manifest,
                RuntimeResourceIndex.create(root, manifest, control),
                control,
            )
        }
    }
}
