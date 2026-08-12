package dev.sixbridge.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.sixbridge.desktop.app.DesktopConversionState
import dev.sixbridge.desktop.conversion.DesktopExtensionInput
import dev.sixbridge.desktop.profile.DesktopProfileOption
import dev.sixbridge.desktop.profile.DesktopProfileTrust
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class DesktopInputStepTest {
    @Test
    fun `입력 화면은 URL 파일 폴더 profile과 분석 버튼을 제공한다`() = runComposeUiTest {
        setContent {
            DesktopApp(
                state = DesktopConversionState.Input(),
                profiles = profiles(),
                onAction = {},
            )
        }

        onNodeWithTag("input:url").assertExists()
        onNodeWithTag("input:pick-folder").assertExists()
        onNodeWithTag("input:pick-archive").assertExists()
        onNodeWithTag("input:drop").assertExists()
        onNodeWithTag("input:profile").assertExists()
        onNodeWithTag("input:advanced-profile").assertExists()
        onNodeWithTag("input:analyze").assertIsNotEnabled()
        onNodeWithTag("input:brand").assertExists()
        onNodeWithTag("input:step-extension").assertExists()
        onNodeWithTag("input:step-profile").assertExists()
        onNodeWithTag("input:step-ready").assertExists()
        onNodeWithTag("input:ready-summary")
            .assertExists()
            .assertTextContains("입력 없음", substring = true)
    }

    @Test
    fun `URL과 로컬 입력은 마지막 선택 하나만 유지한다`() {
        val fromUrl = reduceInputState(
            DesktopConversionState.Input(),
            DesktopAction.UrlChanged("  https://chromewebstore.google.com/detail/example  "),
            profiles(),
        )
        assertEquals(
            DesktopExtensionInput.ChromeStore("https://chromewebstore.google.com/detail/example"),
            fromUrl.selectedInput,
        )

        val local = Path.of("extension.zip")
        val fromLocal = reduceInputState(
            fromUrl,
            DesktopAction.LocalInputSelected(local),
            profiles(),
        )
        assertEquals("", fromLocal.urlText)
        assertEquals(DesktopExtensionInput.Local(local), fromLocal.selectedInput)
    }

    @Test
    fun `selectable profile ID만 입력 상태에 반영한다`() {
        val options = profiles() + DesktopProfileOption(
            id = "untrusted",
            profile = null,
            trust = DesktopProfileTrust.UNTRUSTED,
            selectable = false,
            explanation = "서명되지 않음",
        )
        val selected = reduceInputState(
            DesktopConversionState.Input(),
            DesktopAction.ProfileSelected("browser-30"),
            options,
        )
        val rejected = reduceInputState(
            selected,
            DesktopAction.ProfileSelected("untrusted"),
            options,
        )

        assertEquals("browser-30", selected.selectedProfile?.id)
        assertEquals(selected.selectedProfile, rejected.selectedProfile)
    }

    @Test
    fun `입력과 selectable profile이 준비되면 분석 action을 전달한다`() = runComposeUiTest {
        var actions = emptyList<DesktopAction>()
        val state = DesktopConversionState.Input(
            selectedInput = DesktopExtensionInput.Local(Path.of("extension.zip")),
            selectedProfile = profiles().single(),
        )
        setContent { DesktopApp(state, profiles()) { actions = actions + it } }

        onNodeWithTag("input:verified-badge").assertExists()
        onNodeWithTag("input:ready-summary")
            .assertTextContains("입력 준비됨", substring = true)
            .assertTextContains("profile 검증됨", substring = true)
        onNodeWithText("확장 분석하기").assertExists()
        onNodeWithTag("input:analyze").performScrollTo().assertIsEnabled().performClick()
        runOnIdle { assertEquals(listOf(DesktopAction.Analyze), actions) }
    }

    @Test
    fun `compact 폭에서도 모든 핵심 입력 action을 제공한다`() = runComposeUiTest {
        setContent {
            Box(Modifier.width(900.dp).height(700.dp)) {
                InputStep(DesktopConversionState.Input(), profiles(), onAction = {})
            }
        }

        onNodeWithTag("input:url").assertExists()
        onNodeWithTag("input:drop").assertExists()
        onNodeWithTag("input:profile").assertExists()
        onNodeWithTag("input:analyze").assertExists()
    }

    @Test
    fun `busy거나 선택할 수 없는 profile이면 분석할 수 없다`() {
        val input = DesktopExtensionInput.Local(Path.of("extension.zip"))
        val selectable = profiles().single()
        val blocked = selectable.copy(selectable = false)

        assertEquals(false, canAnalyze(input, selectable, busy = true))
        assertEquals(false, canAnalyze(input, blocked, busy = false))
        assertEquals(false, canAnalyze(null, selectable, busy = false))
        assertEquals(true, canAnalyze(input, selectable, busy = false))
    }

    @Test
    fun `거부된 drop 문구는 다음 정상 입력을 선택하면 지운다`() {
        val actions = mutableListOf<DesktopAction>()
        val feedback = DesktopInputFeedback(actions::add)
        feedback.reject("파일 하나만 놓으세요.")

        feedback.selectLocal(Path.of("valid.zip"))

        assertNull(feedback.dropMessage)
        assertEquals(
            listOf<DesktopAction>(DesktopAction.LocalInputSelected(Path.of("valid.zip"))),
            actions,
        )

        feedback.reject("다시 실패")
        feedback.changeUrl("https://chromewebstore.google.com/detail/example")
        assertNull(feedback.dropMessage)
    }

    private fun profiles() = listOf(
        DesktopProfileOption(
            id = "browser-30",
            profile = null,
            trust = DesktopProfileTrust.BUILT_IN,
            selectable = true,
            explanation = "Samsung Internet 30.x",
        ),
    )
}
