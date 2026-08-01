package dev.sixbridge.desktop.ui

import dev.sixbridge.desktop.app.DesktopConversionState
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import dev.sixbridge.desktop.profile.DesktopProfileOption
import dev.sixbridge.desktop.profile.DesktopProfileTrust
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class DesktopProfileImportCoordinatorTest {
    @Test
    fun `늦게 끝난 이전 import 성공과 실패는 최신 결과를 덮어쓰지 않는다`() {
        val initial = model()
        val first = beginProfileImport(initial, DesktopProfileImportKind.JSON)
        val second = beginProfileImport(first.model, DesktopProfileImportKind.BROWSER_APK)
        val latestOption = option("latest", "최신 APK profile")
        val latest = finishProfileImport(
            second.model,
            second.ticket,
            DesktopProfileImportOutcome.Success(latestOption),
        )

        val afterStaleSuccess = finishProfileImport(
            latest,
            first.ticket,
            DesktopProfileImportOutcome.Success(option("stale", "오래된 JSON profile")),
        )
        val afterStaleFailure = finishProfileImport(
            latest,
            first.ticket,
            DesktopProfileImportOutcome.Failure,
        )

        assertSame(latest, afterStaleSuccess)
        assertSame(latest, afterStaleFailure)
        assertEquals("latest", latest.inputState.selectedProfile?.id)
        assertEquals(listOf("built-in", "latest"), latest.profiles.map { it.id })
    }

    @Test
    fun `입력 변경이나 분석 시작 뒤 도착한 import 결과는 무효화한다`() {
        val started = beginProfileImport(model(), DesktopProfileImportKind.JSON)
        val changedInput = started.model.copy(
            inputState = started.model.inputState.copy(
                selectedInput = DesktopExtensionInput.Local(Path.of("new.zip")),
            ),
        ).invalidateProfileImports()
        val afterInputResult = finishProfileImport(
            changedInput,
            started.ticket,
            DesktopProfileImportOutcome.Success(option("late-input", "늦은 결과")),
        )

        val analyzing = beginProfileImport(model(), DesktopProfileImportKind.BROWSER_APK)
        val invalidatedForAnalyze = analyzing.model.invalidateProfileImports()
        val afterAnalyzeResult = finishProfileImport(
            invalidatedForAnalyze,
            analyzing.ticket,
            DesktopProfileImportOutcome.Success(option("late-analysis", "늦은 분석 결과")),
        )

        assertSame(changedInput, afterInputResult)
        assertSame(invalidatedForAnalyze, afterAnalyzeResult)
    }

    @Test
    fun `현재 import 실패는 종류별 고정 문구만 표시하고 예외 경로를 노출하지 않는다`() {
        val json = beginProfileImport(model(), DesktopProfileImportKind.JSON)
        val jsonFailed = finishProfileImport(
            json.model,
            json.ticket,
            DesktopProfileImportOutcome.Failure,
        )
        val apk = beginProfileImport(jsonFailed, DesktopProfileImportKind.BROWSER_APK)
        val apkFailed = finishProfileImport(
            apk.model,
            apk.ticket,
            DesktopProfileImportOutcome.Failure,
        )

        assertEquals("profile JSON을 가져오지 못했습니다.", jsonFailed.inputState.message)
        assertEquals("Samsung Internet APK profile을 가져오지 못했습니다.", apkFailed.inputState.message)
        assertEquals(false, apkFailed.inputState.message.orEmpty().contains("/Users/private/profile.json"))
    }

    private fun model() = DesktopProfileImportModel(
        inputState = DesktopConversionState.Input(selectedProfile = option("built-in", "내장 profile")),
        profiles = listOf(option("built-in", "내장 profile")),
    )

    private fun option(id: String, explanation: String) = DesktopProfileOption(
        id = id,
        profile = null,
        trust = DesktopProfileTrust.BUILT_IN,
        selectable = true,
        explanation = explanation,
    )
}
