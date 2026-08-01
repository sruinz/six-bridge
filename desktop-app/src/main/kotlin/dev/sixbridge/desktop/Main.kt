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
import dev.sixbridge.desktop.profile.DesktopProfileJsonReader
import dev.sixbridge.desktop.profile.DesktopProfileOption
import dev.sixbridge.desktop.ui.DesktopAction
import dev.sixbridge.desktop.ui.DesktopApp
import dev.sixbridge.desktop.ui.DesktopProfileImportKind
import dev.sixbridge.desktop.ui.DesktopProfileImportModel
import dev.sixbridge.desktop.ui.DesktopProfileImportOutcome
import dev.sixbridge.desktop.ui.beginProfileImport
import dev.sixbridge.desktop.ui.finishProfileImport
import dev.sixbridge.desktop.ui.invalidateProfileImports
import dev.sixbridge.desktop.ui.reduceInputState
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

fun main() = application {
    val controller = remember { DesktopConversionController() }
    val paths = remember { DesktopApplicationPaths.resolve() }
    val catalog = remember(paths) { DesktopProfileCatalog(paths.dataRoot) }
    val profileReader = remember { DesktopProfileJsonReader() }
    var profileImports by remember {
        val profiles = (catalog.builtIns() + catalog.loadPersisted())
            .distinctBy(DesktopProfileOption::id)
        mutableStateOf(
            DesktopProfileImportModel(
                inputState = DesktopConversionState.Input(selectedProfile = catalog.default()),
                profiles = profiles,
            ),
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
            profileImports.inputState
        } else {
            conversionState
        }
        DesktopApp(
            state = visibleState,
            profiles = profileImports.profiles,
            onAction = { action ->
                when (action) {
                    is DesktopAction.UrlChanged,
                    is DesktopAction.LocalInputSelected,
                    is DesktopAction.ProfileSelected,
                    -> profileImports = profileImports.copy(
                        inputState = reduceInputState(
                            profileImports.inputState,
                            action,
                            profileImports.profiles,
                        ),
                    ).invalidateProfileImports()

                    is DesktopAction.ProfileJsonSelected -> {
                        val started = beginProfileImport(
                            profileImports,
                            DesktopProfileImportKind.JSON,
                        )
                        profileImports = started.model
                        scope.launch {
                            val outcome = importProfile {
                                catalog.importJson(profileReader.read(action.path))
                            }
                            profileImports = finishProfileImport(
                                profileImports,
                                started.ticket,
                                outcome,
                            )
                        }
                    }

                    is DesktopAction.BrowserApkSelected -> {
                        val started = beginProfileImport(
                            profileImports,
                            DesktopProfileImportKind.BROWSER_APK,
                        )
                        profileImports = started.model
                        scope.launch {
                            val outcome = importProfile { catalog.importApk(action.path) }
                            profileImports = finishProfileImport(
                                profileImports,
                                started.ticket,
                                outcome,
                            )
                        }
                    }

                    DesktopAction.Analyze -> {
                        profileImports = profileImports.invalidateProfileImports()
                        val input = profileImports.inputState.selectedInput
                        val profile = profileImports.inputState.selectedProfile
                            ?.takeIf { it.selectable }
                            ?.profile
                        if (input == null || profile == null) {
                            profileImports = profileImports.copy(
                                inputState = profileImports.inputState.copy(
                                    message = "입력과 신뢰할 수 있는 profile을 선택하세요.",
                                ),
                            )
                        } else {
                            controller.analyze(input, profile)
                        }
                    }

                    DesktopAction.ContinueToApproval -> controller.continueToApproval()
                    DesktopAction.Back,
                    DesktopAction.Cancel,
                    -> {
                        profileImports = profileImports.invalidateProfileImports()
                        controller.cancel()
                    }
                }
            },
        )
    }
}

private suspend fun importProfile(
    import: () -> DesktopProfileOption,
): DesktopProfileImportOutcome = try {
    DesktopProfileImportOutcome.Success(withContext(Dispatchers.IO) { import() })
} catch (error: CancellationException) {
    throw error
} catch (_: Exception) {
    DesktopProfileImportOutcome.Failure
}
