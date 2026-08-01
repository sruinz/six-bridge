package dev.sixbridge.desktop

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.AlertDialog
import androidx.compose.material.Button
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Text
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import dev.sixbridge.desktop.app.DesktopConversionController
import dev.sixbridge.desktop.app.DesktopConversionState
import dev.sixbridge.desktop.profile.DesktopProfileCatalog
import dev.sixbridge.desktop.profile.DesktopProfileImportController
import dev.sixbridge.desktop.profile.DesktopProfileJsonReader
import dev.sixbridge.desktop.profile.DesktopProfileOption
import dev.sixbridge.desktop.ui.DesktopAction
import dev.sixbridge.desktop.ui.DesktopApp
import dev.sixbridge.desktop.ui.DesktopProfileImportKind
import dev.sixbridge.desktop.ui.DesktopProfileImportModel
import dev.sixbridge.desktop.ui.beginProfileImport
import dev.sixbridge.desktop.ui.finishProfileImport
import dev.sixbridge.desktop.ui.invalidateProfileImports
import dev.sixbridge.desktop.ui.reduceInputState

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
    val profileImportController = remember(scope, catalog, profileReader) {
        DesktopProfileImportController(
            scope = scope,
            catalog = catalog,
            reader = profileReader,
            onOutcome = { ticket, outcome ->
                profileImports = finishProfileImport(profileImports, ticket, outcome)
            },
        )
    }
    val closeCoordinator = remember(controller, profileImportController) {
        DesktopCloseCoordinator(
            isBusy = { controller.isBusy() || profileImportController.isBusy() },
            closeProfileImports = profileImportController::close,
            closeConversion = controller::close,
            exit = ::exitApplication,
            launchInBackground = { action ->
                scope.launch(Dispatchers.IO) { action() }
            },
            postToUi = { action -> scope.launch { action() } },
        )
    }
    var showCloseConfirmation by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = {
            showCloseConfirmation = when (closeCoordinator.requestClose()) {
                DesktopCloseRequest.CONFIRM_REQUIRED -> true
                DesktopCloseRequest.CLOSE_STARTED,
                DesktopCloseRequest.EXITED -> false
            }
        },
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
                    -> {
                        profileImportController.invalidate()
                        profileImports = profileImports.copy(
                            inputState = reduceInputState(
                                profileImports.inputState,
                                action,
                                profileImports.profiles,
                            ),
                        ).invalidateProfileImports()
                    }

                    is DesktopAction.ProfileJsonSelected -> {
                        val started = beginProfileImport(
                            profileImports,
                            DesktopProfileImportKind.JSON,
                        )
                        profileImports = started.model
                        profileImportController.start(started.ticket, action.path)
                    }

                    is DesktopAction.BrowserApkSelected -> {
                        val started = beginProfileImport(
                            profileImports,
                            DesktopProfileImportKind.BROWSER_APK,
                        )
                        profileImports = started.model
                        profileImportController.start(started.ticket, action.path)
                    }

                    DesktopAction.Analyze -> {
                        profileImportController.invalidate()
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
                    DesktopAction.Back -> controller.back()
                    DesktopAction.Cancel -> {
                        profileImportController.invalidate()
                        profileImports = profileImports.invalidateProfileImports()
                        controller.cancel()
                    }
                    is DesktopAction.AdapterToggled -> controller.toggleAdapter(action.id)
                    is DesktopAction.PartialConfirmationChanged ->
                        controller.setPartialConfirmed(action.confirmed)
                    is DesktopAction.ProjectIdentitySelected ->
                        controller.selectProjectIdentity(action.value)
                    DesktopAction.BuildDraft -> controller.buildDraft()
                    is DesktopAction.SaveDraft -> controller.saveDraft(action.destination)
                    DesktopAction.RevealSavedDraft -> controller.revealSavedDraft()
                    DesktopAction.NewConversion -> {
                        profileImportController.invalidate()
                        profileImports = profileImports.invalidateProfileImports()
                        controller.reset()
                    }
                }
            },
        )
        if (showCloseConfirmation) {
            AlertDialog(
                onDismissRequest = {
                    closeCoordinator.cancelClose()
                    showCloseConfirmation = false
                },
                title = { Text("진행 중인 작업 종료") },
                text = { Text("분석·변환·저장 작업을 취소하고 SIX Bridge를 종료할까요?") },
                confirmButton = {
                    Button(
                        onClick = {
                            showCloseConfirmation = false
                            closeCoordinator.confirmClose()
                        },
                        modifier = androidx.compose.ui.Modifier.semantics {
                            testTag = "close:confirm"
                        },
                    ) { Text("종료") }
                },
                dismissButton = {
                    OutlinedButton(
                        onClick = {
                            closeCoordinator.cancelClose()
                            showCloseConfirmation = false
                        },
                        modifier = androidx.compose.ui.Modifier.semantics {
                            testTag = "close:cancel"
                        },
                    ) { Text("계속 사용") }
                },
            )
        }
    }
}
