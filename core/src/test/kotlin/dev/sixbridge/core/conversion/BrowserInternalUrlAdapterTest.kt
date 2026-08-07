package dev.sixbridge.core.conversion

import dev.sixbridge.core.input.ExtensionInputKind
import dev.sixbridge.core.input.ExtensionZip
import dev.sixbridge.core.input.PortableExtensionInput
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BrowserInternalUrlAdapterTest {
    @Test
    fun `정적 내부 주소는 rule status와 path를 보고한다`() {
        val report = analyzeFixture(
            html = """
                <html><head></head><body>
                <a href="chrome://extensions">확장</a>
                <a href="chrome://password-manager/settings">암호</a>
                <a href="chrome://flags">flags</a>
                </body></html>
            """.trimIndent(),
        )
        val decision = report.adapterDecisions
            .single { it.id == ADAPTER_ID }

        assertTrue(decision.evidence.any {
            it.relativePath == "popup.html" && "status=verified" in it.detail
        })
        assertTrue(decision.evidence.any {
            it.relativePath == "popup.html" && "status=manual" in it.detail
        })
        assertTrue(decision.evidence.any {
            it.relativePath == "popup.html" && "status=unsupported" in it.detail
        })
    }

    @Test
    fun `동적 조합과 query 주소는 진단만 하고 rewrite decision에 넣지 않는다`() {
        val report = analyzeFixture(
            js = "const a='chrome://' + page; const b='chrome://extensions/?id=x';",
        )

        assertTrue(report.issues.any { it.code == UNMAPPED_ISSUE })
        assertFalse(report.adapterDecisions.any { decision ->
            decision.evidence.any { "extensions/?id=x" in it.detail }
        })
    }

    @Test
    fun `HTML과 JavaScript exact span을 상태별 목적지로 뒤 offset부터 변환한다`() {
        val prepared = prepareFixture()
        val transformed = extract(prepared.transformedSourceZip)
        val html = transformed.resolve("popup.html").readText()
        val script = transformed.resolve("popup.js").readText()
        val options = transformed.resolve("options.html").readText()

        assertTrue("href=\"internet://extension\"" in html)
        assertTrue("'internet://extension'" in script)
        assertTrue("\"internet://extension\"" in script)
        assertTrue("href=\"internet://extension\"" in options)
        assertTrue("/sixbridge/internal-url-manual-password-manager.v1.html" in html)
        assertTrue("/sixbridge/internal-url-unsupported-flags.v1.html" in html)
        assertTrue("/sixbridge/internal-url-unsupported-inspect.v1.html" in html)
        assertTrue("chrome://extensions/?id=unchanged" in html)
        assertTrue("'chrome://' + page" in script)
        assertTrue("chrome://extensions/?id=unchanged" in script)
        assertFalse("chrome://extensions\"" in html)
    }

    @Test
    fun `marker는 path rule count와 span hash만 보존한다`() {
        val transformed = extract(prepareFixture().transformedSourceZip)
        val markerText = transformed.resolve(MARKER_PATH).readText()
        val marker = Json.parseToJsonElement(markerText) as JsonObject
        val spans = marker["spans"] as JsonArray

        assertEquals(setOf("adapter", "version", "spans"), marker.keys)
        assertEquals(ADAPTER_ID, (marker["adapter"] as JsonPrimitive).content)
        assertEquals("1", (marker["version"] as JsonPrimitive).content)
        assertEquals(8, spans.size)
        spans.forEach { element ->
            val span = element as JsonObject
            assertEquals(
                setOf(
                    "path",
                    "rule",
                    "occurrenceCount",
                    "originalSpanSha256",
                    "replacementSpanSha256",
                ),
                span.keys,
            )
        }
        assertFalse("chrome://" in markerText)
        assertFalse("internet://" in markerText)
    }

    @Test
    fun `로컬 안내 asset은 외부 자원과 사용자 데이터 전달 경로가 없다`() {
        val transformed = extract(prepareFixture().transformedSourceZip)
        val assets = listOf(
            "sixbridge/internal-url-manual-password-manager.v1.html",
            "sixbridge/internal-url-unsupported-flags.v1.html",
            "sixbridge/internal-url-unsupported-inspect.v1.html",
        ).map { transformed.resolve(it).readText() }

        assertTrue(
            "<h1>이 브라우저에서는 같은 설정 화면을 자동으로 열 수 없습니다.</h1>" in assets.first(),
        )
        assertTrue(
            "<p>삼성 인터넷 설정에서 해당 항목을 직접 찾아주세요.</p>" in assets.first(),
        )
        assets.drop(1).forEach { source ->
            assertTrue("삼성 인터넷 30.0.2.61에서 제공하지 않습니다." in source)
            assertTrue("실행을 차단했습니다." in source)
        }
        assets.forEach { source ->
            listOf("<script", "<img", "<form", "http://", "https://", "?", "#", "storage")
                .forEach { forbidden -> assertFalse(forbidden in source, forbidden) }
        }
    }

    @Test
    fun `두 번째 변환은 byte identical하고 foreign asset은 rollback한다`() {
        val first = prepareFixture().transformedSourceZip
        val secondInput = PortableExtensionInput.fromZip(
            ExtensionInputKind.ZIP,
            first,
        )
        val second = prepare(secondInput).transformedSourceZip

        assertContentEquals(first, second)

        val source = fixtureRootFromFiles().also { root ->
            Files.createDirectories(root.resolve("sixbridge"))
            root.resolve("sixbridge/internal-url-unsupported-flags.v1.html")
                .writeText("foreign")
        }
        val input = PortableExtensionInput.fromZip(
            ExtensionInputKind.ZIP,
            ExtensionZip.createBytes(source, includeProjectMarker = true),
        )
        assertFailsWith<ConversionExecutionException> { prepare(input) }
    }

    @Test
    fun `marker path rule count hash와 변환 span을 재생한 proof만 applied로 인정한다`() {
        val mutations = listOf<(Path) -> Unit>(
            { root ->
                val marker = root.resolve(MARKER_PATH)
                marker.writeText(marker.readText().replaceFirst("popup.html", "missing.html"))
            },
            { root ->
                val marker = root.resolve(MARKER_PATH)
                marker.writeText(
                    marker.readText().replaceFirst(
                        "extensions.chrome.root\"",
                        "extensions.chrome.root-slash\"",
                    ),
                )
            },
            { root ->
                val marker = root.resolve(MARKER_PATH)
                marker.writeText(
                    marker.readText().replaceFirst(
                        "\"occurrenceCount\":1",
                        "\"occurrenceCount\":2",
                    ),
                )
            },
            { root ->
                val marker = root.resolve(MARKER_PATH)
                val source = marker.readText()
                val match = requireNotNull(
                    Regex("\"originalSpanSha256\":\"([0-9a-f])").find(source),
                )
                val replacement = if (match.groupValues[1] == "0") "1" else "0"
                marker.writeText(
                    source.replaceRange(
                        match.groups[1]!!.range,
                        replacement,
                    ),
                )
            },
            { root ->
                val popup = root.resolve("popup.html")
                popup.writeText(
                    popup.readText().replaceFirst(
                        "internet://extension",
                        "chrome://extensions",
                    ),
                )
            },
        )

        mutations.forEachIndexed { index, mutate ->
            val transformed = extract(prepareFixture().transformedSourceZip)
            mutate(transformed)

            assertNull(
                BrowserInternalUrlProof.detect(
                    transformed,
                    BrowserInternalUrlProof.runtimeIndex(transformed),
                    profile,
                ),
                "mutation $index",
            )
        }
    }

    private fun analyzeFixture(
        html: String = "<html><head></head><body></body></html>",
        js: String = "",
    ) = MobilePatternAnalyzer().analyze(
        fixtureRoot(html, js),
        profile,
    )

    private fun fixtureRoot(html: String, js: String): Path =
        Files.createTempDirectory("six-browser-internal-url-analysis-").also { root ->
            root.resolve("manifest.json").writeText(
                """
                {
                  "manifest_version": 3,
                  "name": "Internal URL test",
                  "version": "1",
                  "action": {"default_popup": "popup.html"}
                }
                """.trimIndent(),
            )
            root.resolve("popup.html").writeText(
                html.replace(
                    "</body>",
                    "<script src=\"popup.js\"></script></body>",
                ),
            )
            root.resolve("popup.js").writeText(js)
        }

    private fun prepareFixture(): PreparedConversion = prepare(
        PortableExtensionInput.fromZip(
            ExtensionInputKind.ZIP,
            ExtensionZip.createBytes(
                fixtureRootFromFiles(),
                includeProjectMarker = true,
            ),
        ),
    )

    private fun prepare(input: PortableExtensionInput): PreparedConversion =
        ConversionPreparationEngine().use {
            it.prepare(input, profile, emptySet())
        }

    private fun fixtureRootFromFiles(): Path {
        val fixture = Path.of("../fixtures/browser-internal-url-mapping")
        val root = Files.createTempDirectory("six-browser-internal-url-fixture-")
        listOf("manifest.json", "popup.html", "popup.js", "options.html").forEach {
            Files.copy(fixture.resolve(it), root.resolve(it))
        }
        return root
    }

    private fun extract(zip: ByteArray): Path {
        val parent = Files.createTempDirectory("six-browser-internal-url-extract-")
        return ExtensionZip.extract(zip, parent.resolve("tree"))
    }

    private val profile = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = BrowserProfile.BASELINE_VERSION,
        versionCode = 1_300_261_502,
        majorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION,
        supportedApis = emptySet(),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        supportedFeatures = setOf("browser.internal-url.extensions-root"),
    )

    private companion object {
        const val ADAPTER_ID = "browser-internal-url-mapper"
        const val MARKER_PATH = "sixbridge/browser-internal-url-mapper.v1.json"
        const val UNMAPPED_ISSUE = "browser_internal_url_unmapped"
    }
}
