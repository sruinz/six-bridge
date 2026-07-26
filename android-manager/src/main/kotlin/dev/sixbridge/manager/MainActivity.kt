package dev.sixbridge.manager

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.sixbridge.manager.app.ManagerDependencies
import dev.sixbridge.manager.install.ManagerAppVisibility
import dev.sixbridge.manager.ui.ManagerAction
import dev.sixbridge.manager.ui.ManagerEffect
import dev.sixbridge.manager.ui.ManagerViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var managerViewModel: ManagerViewModel
    private lateinit var notificationInstallGate: NotificationPermissionInstallGate

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { managerViewModel.accept(ManagerAction.ImportSelected(it)) }
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationInstallGate.onPermissionResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = ManagerDependencies.create(application).repository
        managerViewModel = ViewModelProvider(
            this,
            viewModelFactory {
                initializer {
                    ManagerViewModel(repository, createSavedStateHandle())
                }
            },
        )[ManagerViewModel::class.java]
        notificationInstallGate = NotificationPermissionInstallGate(
            awaitingPermissionResult =
                savedInstanceState?.getBoolean(AWAITING_NOTIFICATION_PERMISSION) == true,
            needsPermission = {
                Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(POST_NOTIFICATIONS_PERMISSION) !=
                    PackageManager.PERMISSION_GRANTED
            },
            requestPermission = {
                requestNotificationPermission()
            },
            continueInstall = {
                managerViewModel.accept(ManagerAction.InstallReviewedBundle)
            },
        )
        setContent {
            SixBridgeManagerApp(
                viewModel = managerViewModel,
                onAddSix = {
                    openDocument.launch(SIX_MIME_TYPES)
                },
                onInstallReviewed = notificationInstallGate::requestInstall,
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                managerViewModel.effects.collect { effect ->
                    if (effect is ManagerEffect.LaunchIntent) {
                        executeLaunch(
                            effect = effect,
                            resolveIntent = { it.intentFactory() },
                            launchIntent = ::startActivity,
                            onFailure = {
                                managerViewModel.accept(ManagerAction.LaunchFailed(it))
                            },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::managerViewModel.isInitialized) {
            managerViewModel.accept(ManagerAction.ReturnedToForeground)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::notificationInstallGate.isInitialized) {
            outState.putBoolean(
                AWAITING_NOTIFICATION_PERMISSION,
                notificationInstallGate.awaitingPermissionResult,
            )
        }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        ManagerAppVisibility.onActivityStarted()
    }

    private fun requestNotificationPermission() {
        requestNotifications.launch(POST_NOTIFICATIONS_PERMISSION)
    }

    override fun onStop() {
        ManagerAppVisibility.onActivityStopped()
        super.onStop()
    }

    private companion object {
        const val AWAITING_NOTIFICATION_PERMISSION = "awaiting_notification_permission"
        const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
        val SIX_MIME_TYPES = arrayOf(
            "application/zip",
            "application/octet-stream",
            "application/vnd.sixbridge.bundle",
        )
    }
}

internal fun <T> executeLaunch(
    effect: ManagerEffect.LaunchIntent,
    resolveIntent: (ManagerEffect.LaunchIntent) -> T,
    launchIntent: (T) -> Unit,
    onFailure: (ManagerEffect.LaunchIntent) -> Unit,
): Boolean = try {
    launchIntent(resolveIntent(effect))
    true
} catch (_: RuntimeException) {
    onFailure(effect)
    false
}

internal class NotificationPermissionInstallGate(
    awaitingPermissionResult: Boolean,
    private val needsPermission: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val continueInstall: () -> Unit,
) {
    var awaitingPermissionResult: Boolean = awaitingPermissionResult
        private set

    fun requestInstall() {
        if (awaitingPermissionResult) return
        if (!needsPermission()) {
            continueInstall()
            return
        }
        awaitingPermissionResult = true
        requestPermission()
    }

    fun onPermissionResult() {
        if (!awaitingPermissionResult) return
        awaitingPermissionResult = false
        continueInstall()
    }
}
