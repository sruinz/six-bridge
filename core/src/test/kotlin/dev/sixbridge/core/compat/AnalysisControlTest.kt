package dev.sixbridge.core.compat

import dev.sixbridge.core.conversion.MobilePatternAnalyzer
import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalysisControlTest {
    @Test
    fun `기존 trailing lambda는 취소 checkpoint로 유지한다`() {
        var checkpoints = 0
        val control = AnalysisControl { checkpoints++ }

        control.checkCancelled()

        assertEquals(1, checkpoints)
    }

    @Test
    fun `같은 분석 단계는 callback을 한 번만 전달한다`() {
        val stages = mutableListOf<AnalysisStage>()
        val control = AnalysisControl(onStage = stages::add)

        control.stage(AnalysisStage.API_SCAN)
        control.stage(AnalysisStage.RUNTIME_CLOSURE)
        control.stage(AnalysisStage.API_SCAN)
        control.stage(AnalysisStage.FINALIZING)

        assertEquals(listOf(AnalysisStage.API_SCAN, AnalysisStage.FINALIZING), stages)
    }

    @Test
    fun `같은 control을 독립 분석에 재사용하면 새 단계 sequence를 전달한다`() {
        val source = extension("console.log('stage reuse');")
        val stages = mutableListOf<AnalysisStage>()
        val control = AnalysisControl(onStage = stages::add)
        val analyzer = MobilePatternAnalyzer(control)

        analyzer.analyzeStableForTesting(source, profile)
        analyzer.analyzeStableForTesting(source, profile)

        assertEquals(
            listOf(
                AnalysisStage.ARCHIVE_INVENTORY,
                AnalysisStage.RUNTIME_CLOSURE,
                AnalysisStage.API_SCAN,
                AnalysisStage.MOBILE_PATTERNS,
                AnalysisStage.FINALIZING,
                AnalysisStage.ARCHIVE_INVENTORY,
                AnalysisStage.RUNTIME_CLOSURE,
                AnalysisStage.API_SCAN,
                AnalysisStage.MOBILE_PATTERNS,
                AnalysisStage.FINALIZING,
            ),
            stages,
        )
    }

    @Test
    fun `전체 분석 단계는 역행과 중복 없이 진행한다`() {
        val source = extension("console.log('stage');")
        val stages = mutableListOf<AnalysisStage>()
        val control = AnalysisControl(onStage = stages::add)

        MobilePatternAnalyzer(control).analyzeStableForTesting(source, profile)

        assertEquals(
            listOf(
                AnalysisStage.ARCHIVE_INVENTORY,
                AnalysisStage.RUNTIME_CLOSURE,
                AnalysisStage.API_SCAN,
                AnalysisStage.MOBILE_PATTERNS,
                AnalysisStage.FINALIZING,
            ),
            stages,
        )
    }

    @Test
    fun `모바일 패턴 대형 스캔은 제한된 checkpoint 안에 취소를 전달한다`() {
        val source = backgroundExtension(
            """
            chrome.storage.sync.get("value");
            ${" ".repeat(512 * 1024)}
            """.trimIndent(),
        )
        var mobilePatterns = false
        var mobileCheckpoints = 0
        val control = AnalysisControl(
            checkpoint = {
                if (mobilePatterns && ++mobileCheckpoints == 10) {
                    throw CancellationException("mobile-pattern-cancel")
                }
            },
            onStage = {
                if (it == AnalysisStage.MOBILE_PATTERNS) mobilePatterns = true
            },
        )

        val failure = assertFailsWith<CancellationException> {
            MobilePatternAnalyzer(control).analyzeStableForTesting(source, profile)
        }

        assertEquals("mobile-pattern-cancel", failure.message)
        assertEquals(10, mobileCheckpoints)
    }

    private fun extension(source: String) =
        Files.createTempDirectory("analysis-control-").also { root ->
            root.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"control","version":"1","content_scripts":[{"matches":["<all_urls>"],"js":["content.js"]}]}""",
            )
            root.resolve("content.js").writeText(source)
        }

    private fun backgroundExtension(source: String) =
        Files.createTempDirectory("analysis-control-background-").also { root ->
            root.resolve("manifest.json").writeText(
                """{"manifest_version":3,"name":"control","version":"1","background":{"service_worker":"background.js"}}""",
            )
            root.resolve("background.js").writeText(source)
        }

    private companion object {
        val profile = BrowserProfile(
            browserPackage = "com.sec.android.app.sbrowser",
            versionName = BrowserProfile.BASELINE_VERSION,
            versionCode = 1_300_261_502,
            majorVersion = BrowserProfile.SUPPORTED_MAJOR_VERSION,
            supportedApis = BrowserProfile.POC_SUPPORTED_APIS,
            wrapperContract = WrapperContract.complete(),
            sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
            sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
        )
    }
}
