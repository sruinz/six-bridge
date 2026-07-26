package dev.sixbridge.manager

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sixbridge.manager.ui.HomeScreen
import dev.sixbridge.manager.ui.ImportUiState
import dev.sixbridge.manager.ui.ManagerAction
import dev.sixbridge.manager.ui.ManagerRoute
import dev.sixbridge.manager.ui.ManagerTheme
import dev.sixbridge.manager.ui.ManagerUiState
import dev.sixbridge.manager.ui.ManagerViewModel
import dev.sixbridge.manager.ui.ReportScreen
import dev.sixbridge.manager.ui.ReviewScreen

@Composable
fun SixBridgeManagerApp(
    viewModel: ManagerViewModel,
    onAddSix: () -> Unit,
    onInstallReviewed: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ManagerTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            ManagerAppContent(
                state = state,
                onAction = viewModel::accept,
                onAddSix = onAddSix,
                onInstallReviewed = onInstallReviewed,
            )
        }
    }
}

@Composable
fun ManagerAppContent(
    state: ManagerUiState,
    onAction: (ManagerAction) -> Unit,
    onAddSix: () -> Unit,
    modifier: Modifier = Modifier,
    onInstallReviewed: () -> Unit = {
        onAction(ManagerAction.InstallReviewedBundle)
    },
) {
    val backAction = state.route.backAction()
    BackHandler(enabled = backAction != null) {
        backAction?.let(onAction)
    }
    when (val route = state.route) {
        ManagerRoute.Home -> HomeScreen(
            state = state,
            onAction = onAction,
            onAddSix = onAddSix,
            modifier = modifier,
        )
        is ManagerRoute.Review -> {
            val ready = state.importState as? ImportUiState.ReviewReady
            if (ready == null || ready.review.token != route.token) {
                HomeScreen(
                    state = state.copy(route = ManagerRoute.Home),
                    onAction = onAction,
                    onAddSix = onAddSix,
                    modifier = modifier,
                )
            } else {
                ReviewScreen(
                    ready = ready,
                    installState = state.installState,
                    message = state.message,
                    onAction = onAction,
                    onInstall = onInstallReviewed,
                    onAddSix = onAddSix,
                    modifier = modifier,
                    showNotificationPermissionNotice = Build.VERSION.SDK_INT >= 33,
                )
            }
        }
        is ManagerRoute.Report -> ReportScreen(
            item = state.installed.firstOrNull { it.packageName == route.packageName },
            onBack = { onAction(ManagerAction.ReturnHome) },
            modifier = modifier,
        )
    }
}

internal fun ManagerRoute.backAction(): ManagerAction? = when (this) {
    ManagerRoute.Home -> null
    is ManagerRoute.Review -> ManagerAction.CancelReview
    is ManagerRoute.Report -> ManagerAction.ReturnHome
}
