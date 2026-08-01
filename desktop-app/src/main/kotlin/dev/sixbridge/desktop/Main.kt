package dev.sixbridge.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.sixbridge.desktop.app.DesktopConversionController
import dev.sixbridge.desktop.app.DesktopConversionState
import dev.sixbridge.desktop.profile.DesktopProfileCatalog
import dev.sixbridge.desktop.profile.DesktopProfileOption
import dev.sixbridge.desktop.ui.DesktopAction
import dev.sixbridge.desktop.ui.DesktopApp
import dev.sixbridge.desktop.ui.reduceInputState
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() = application {
    val controller = remember { DesktopConversionController() }
    val paths = remember { DesktopApplicationPaths.resolve() }
    val catalog = remember(paths) { DesktopProfileCatalog(paths.dataRoot) }
    var profiles by remember {
        mutableStateOf(
            (catalog.builtIns() + catalog.loadPersisted()).distinctBy(DesktopProfileOption::id),
        )
    }
    var inputState by remember {
        mutableStateOf(
            DesktopConversionState.Input(selectedProfile = catalog.default()),
        )
    }
    val conversionState by controller.state.collectAsState()
    val scope = rememberCoroutineScope()

    DisposableEffect(controller) {
        onDispose(controller::close)
    }

    Window(
        onCloseRequest = ::exitApplication,
        title = DesktopApplicationMetadata.NAME,
        state = rememberWindowState(width = 1024.dp, height = 768.dp),
    ) {
        val visibleState = if (conversionState is DesktopConversionState.Input) {
            inputState
        } else {
            conversionState
        }
        DesktopApp(
            state = visibleState,
            profiles = profiles,
            onAction = { action ->
                when (action) {
                    is DesktopAction.UrlChanged,
                    is DesktopAction.LocalInputSelected,
                    is DesktopAction.ProfileSelected,
                    -> inputState = reduceInputState(inputState, action, profiles)

                    is DesktopAction.ProfileJsonSelected -> scope.launch {
                        importProfile(
                            import = { catalog.importJson(readBoundedProfile(action.path)) },
                            onImported = { option ->
                                profiles = mergeProfile(profiles, option)
                                inputState = importedProfileState(inputState, option)
                            },
                            onFailure = { message -> inputState = inputState.copy(message = message) },
                        )
                    }

                    is DesktopAction.BrowserApkSelected -> scope.launch {
                        importProfile(
                            import = { catalog.importApk(action.path) },
                            onImported = { option ->
                                profiles = mergeProfile(profiles, option)
                                inputState = importedProfileState(inputState, option)
                            },
                            onFailure = { message -> inputState = inputState.copy(message = message) },
                        )
                    }

                    DesktopAction.Analyze -> {
                        val input = inputState.selectedInput
                        val profile = inputState.selectedProfile?.takeIf { it.selectable }?.profile
                        if (input == null || profile == null) {
                            inputState = inputState.copy(message = "입력과 신뢰할 수 있는 profile을 선택하세요.")
                        } else {
                            controller.analyze(input, profile)
                        }
                    }

                    DesktopAction.ContinueToApproval -> controller.continueToApproval()
                    DesktopAction.Back,
                    DesktopAction.Cancel,
                    -> controller.cancel()
                }
            },
        )
    }
}

private suspend fun importProfile(
    import: () -> DesktopProfileOption,
    onImported: (DesktopProfileOption) -> Unit,
    onFailure: (String) -> Unit,
) {
    runCatching { withContext(Dispatchers.IO) { import() } }
        .onSuccess(onImported)
        .onFailure { onFailure(it.message ?: "profile을 가져오지 못했습니다.") }
}

private fun mergeProfile(
    profiles: List<DesktopProfileOption>,
    option: DesktopProfileOption,
): List<DesktopProfileOption> = (profiles + option).distinctBy(DesktopProfileOption::id)

private fun importedProfileState(
    state: DesktopConversionState.Input,
    option: DesktopProfileOption,
): DesktopConversionState.Input = if (option.selectable) {
    state.copy(selectedProfile = option, message = option.explanation)
} else {
    state.copy(message = option.explanation)
}

private fun readBoundedProfile(path: java.nio.file.Path): ByteArray {
    require(Files.isRegularFile(path)) { "profile JSON은 일반 파일이어야 합니다." }
    require(Files.size(path) <= MAX_PROFILE_IMPORT_BYTES) { "profile JSON이 너무 큽니다." }
    return Files.readAllBytes(path)
}

private const val MAX_PROFILE_IMPORT_BYTES = 1024L * 1024L
