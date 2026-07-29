package dev.sixbridge.manager

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sixbridge.manager.ui.HomeScreen
import dev.sixbridge.manager.ui.ConversionAnalysisScreen
import dev.sixbridge.manager.ui.ConversionInputScreen
import dev.sixbridge.manager.ui.ConversionResultScreen
import dev.sixbridge.manager.ui.ConversionUiState
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
                onInstallGenerated = {
                    viewModel.accept(ManagerAction.PrepareGeneratedInstall)
                    onInstallReviewed()
                },
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
    onInstallGenerated: () -> Unit = {
        onAction(ManagerAction.PrepareGeneratedInstall)
        onAction(ManagerAction.InstallReviewedBundle)
    },
) {
    val backAction = state.route.backAction()
    BackHandler(enabled = backAction != null) {
        backAction?.let(onAction)
    }
    val destinationCommitToken = state.destinationCommitToken
    LaunchedEffect(destinationCommitToken, state.route) {
        destinationCommitToken ?: return@LaunchedEffect
        withFrameNanos { }
        onAction(
            ManagerAction.GeneratedCleanupDestinationPresented(
                destinationCommitToken,
            ),
        )
    }
    when (val route = state.route) {
        ManagerRoute.Home -> HomeScreen(
            state = state,
            onAction = onAction,
            onAddSix = onAddSix,
            modifier = modifier,
        )
        ManagerRoute.ConversionInput -> ConversionInputScreen(
            state = state,
            onAction = onAction,
            modifier = modifier,
        )
        is ManagerRoute.ConversionAnalysis -> ConversionAnalysisScreen(
            state = state.conversionState,
            message = state.message,
            onAction = onAction,
            modifier = modifier,
        )
        is ManagerRoute.ConversionResult -> {
            val ready = state.conversionState as? ConversionUiState.ResultReady
            if (ready == null || ready.token != route.token) {
                ConversionInputScreen(
                    state = state.copy(
                        route = ManagerRoute.ConversionInput,
                        conversionState = ConversionUiState.Idle,
                    ),
                    onAction = onAction,
                    modifier = modifier,
                )
            } else {
                ConversionResultScreen(
                    ready = ready,
                    message = state.message,
                    installState = state.installState,
                    onAction = onAction,
                    onInstall = onInstallGenerated,
                    modifier = modifier,
                )
            }
        }
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
    ManagerRoute.ConversionInput,
    is ManagerRoute.ConversionAnalysis,
    is ManagerRoute.ConversionResult,
    -> ManagerAction.CancelConversion
    is ManagerRoute.Review -> ManagerAction.CancelReview
    is ManagerRoute.Report -> ManagerAction.ReturnHome
}
