package dev.sixbridge.core.compat

import dev.sixbridge.core.profile.BrowserProfile
import dev.sixbridge.core.profile.WrapperContract
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CancellationException
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LargeBundleAnalysisTest {
    private val profile = BrowserProfile(
        browserPackage = "com.sec.android.app.sbrowser",
        versionName = "30.0.2.61",
        versionCode = 1_300_261_502,
        majorVersion = 30,
        supportedApis = setOf("runtime"),
        wrapperContract = WrapperContract.complete(),
        sourceApkSha256 = BrowserProfile.BASELINE_APK_SHA256,
        sourceApkSignerSha256 = BrowserProfile.SAMSUNG_SIGNER_SHA256,
    )

    @Test
    fun `4MiB declaration bundle을 desktop 정책으로 30초 안에 분석한다`() {
        withLargeExtension { source ->
            val started = System.nanoTime()

            val report = CompatibilityAnalyzer(
                AnalysisControl(AnalysisSafetyPolicy.desktop),
            ).analyzeStable(source, profile, sourceSha256 = null)

            val elapsed = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(report.status != CompatibilityStatus.UNKNOWN, report.toString())
            assertTrue(elapsed < Duration.ofSeconds(30), "elapsed=$elapsed")
        }
    }

    @Test
    fun `대형 bundle lexical scan 도중 취소를 관찰한다`() {
        withLargeExtension { source ->
            var stage: AnalysisStage? = null
            var apiScanCheckpoints = 0
            val control = AnalysisControl(
                policy = AnalysisSafetyPolicy.desktop,
                checkpoint = {
                    if (stage == AnalysisStage.API_SCAN && ++apiScanCheckpoints == 4) {
                        throw CancellationException("test")
                    }
                },
                onStage = { stage = it },
            )

            assertFailsWith<CancellationException> {
                CompatibilityAnalyzer(control).analyzeStable(
                    source,
                    profile,
                    sourceSha256 = null,
                )
            }
            assertTrue(apiScanCheckpoints >= 4)
        }
    }

    private fun <T> withLargeExtension(block: (Path) -> T): T {
        val root = Files.createTempDirectory("six-large-bundle")
        return try {
            largeExtension(root)
            block(root)
        } finally {
            check(root.toFile().deleteRecursively()) {
                "대형 bundle 테스트 디렉터리를 정리하지 못했습니다: $root"
            }
        }
    }

    private fun largeExtension(root: Path) {
        root.resolve("manifest.json").writeText(
            """{"manifest_version":3,"name":"Large bundle","version":"1"}""",
        )
        root.resolve("bundle.js").writeText(
            buildString {
                repeat(90_000) { index ->
                    append("export function f")
                    append(index)
                    append("(){return chrome.runtime.id;}\n")
                }
            },
        )
        assertTrue(Files.size(root.resolve("bundle.js")) >= 4L * 1024 * 1024)
    }
}
