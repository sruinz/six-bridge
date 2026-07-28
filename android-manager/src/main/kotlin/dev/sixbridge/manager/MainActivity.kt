package dev.sixbridge.manager

import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.core.content.FileProvider
import dev.sixbridge.manager.app.ManagerDependencies
import dev.sixbridge.manager.install.ManagerAppVisibility
import dev.sixbridge.manager.ui.ManagerAction
import dev.sixbridge.manager.ui.ManagerEffect
import dev.sixbridge.manager.ui.ManagerViewModel
import kotlinx.coroutines.launch
import java.io.File
import java.nio.file.Files

class MainActivity : ComponentActivity() {
    private lateinit var managerViewModel: ManagerViewModel
    private lateinit var notificationInstallGate: NotificationPermissionInstallGate
    private var pendingConversionSaveToken: String? = null

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { managerViewModel.accept(ManagerAction.ImportSelected(it)) }
    }

    private val openConversionDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { managerViewModel.accept(ManagerAction.ConversionFileSelected(it)) }
    }

    private val createConversionDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(GENERATED_BUNDLE_MIME_TYPE),
    ) { uri ->
        val token = pendingConversionSaveToken
        pendingConversionSaveToken = null
        if (uri != null && token != null) {
            managerViewModel.accept(
                ManagerAction.SaveGeneratedConversionTo(token, uri.toString()),
            )
        }
    }

    private val requestNotifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationInstallGate.onPermissionResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = ManagerDependencies.create(application).repository
        pendingConversionSaveToken =
            savedInstanceState?.getString(PENDING_CONVERSION_SAVE_TOKEN)
        managerViewModel = ViewModelProvider(
            this,
            viewModelFactory {
                initializer {
                    ManagerViewModel(
                        repository = repository,
                        savedStateHandle = createSavedStateHandle(),
                        openConversionOutput = { destination ->
                            contentResolver.openOutputStream(Uri.parse(destination), "w")
                        },
                    )
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
                    openSixFilePicker(
                        onAction = managerViewModel::accept,
                        launchPicker = { openDocument.launch(SIX_MIME_TYPES) },
                    )
                },
                onInstallReviewed = notificationInstallGate::requestInstall,
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                managerViewModel.effects.collect { effect ->
                    when (effect) {
                        is ManagerEffect.LaunchIntent -> executeLaunch(
                            effect = effect,
                            resolveIntent = { it.intentFactory() },
                            launchIntent = ::startActivity,
                            onSuccess = {
                                managerViewModel.accept(ManagerAction.LaunchSucceeded(it))
                            },
                            onFailure = {
                                managerViewModel.accept(ManagerAction.LaunchFailed(it))
                            },
                        )
                        ManagerEffect.PickConversionDocument ->
                            openConversionDocument.launch(CONVERSION_MIME_TYPES)
                        is ManagerEffect.CreateConversionDocument -> {
                            pendingConversionSaveToken = effect.token
                            createConversionDocument.launch(effect.suggestedFileName)
                        }
                        is ManagerEffect.ShareGeneratedBundle -> {
                            val shared = runCatching {
                                startActivity(generatedBundleShareChooser(this@MainActivity, effect.file))
                            }.isSuccess
                            if (!shared) {
                                managerViewModel.accept(ManagerAction.GeneratedShareFailed)
                            }
                        }
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
        pendingConversionSaveToken?.let {
            outState.putString(PENDING_CONVERSION_SAVE_TOKEN, it)
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
        const val PENDING_CONVERSION_SAVE_TOKEN = "pending_conversion_save_token"
        const val POST_NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"
        val SIX_MIME_TYPES = arrayOf(
            "application/zip",
            "application/octet-stream",
            "application/vnd.sixbridge.bundle",
        )
        val CONVERSION_MIME_TYPES = arrayOf(
            "application/zip",
            "application/octet-stream",
            "application/vnd.sixbridge.draft",
        )
    }
}

internal const val GENERATED_BUNDLE_MIME_TYPE = "application/vnd.sixbridge.bundle"

internal fun generatedBundleSendIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
    type = GENERATED_BUNDLE_MIME_TYPE
    putExtra(Intent.EXTRA_STREAM, uri)
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

internal fun generatedBundleShareChooser(context: Context, file: File): Intent {
    val root = File(context.filesDir, "generated-bundles").canonicalFile
    val canonicalFile = file.canonicalFile
    require(canonicalFile.parentFile == root && file.parentFile?.canonicalFile == root) {
        "공유 파일은 generated-bundles direct child여야 합니다."
    }
    require(file.name.endsWith(".six") && file.isFile && !Files.isSymbolicLink(file.toPath())) {
        "공유할 생성 bundle이 올바르지 않습니다."
    }
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.generated",
        file,
    )
    return Intent.createChooser(
        generatedBundleSendIntent(uri),
        context.getString(dev.sixbridge.manager.R.string.conversion_share_chooser),
    )
}

internal fun openSixFilePicker(
    onAction: (ManagerAction) -> Unit,
    launchPicker: () -> Unit,
) {
    onAction(ManagerAction.FilePickerOpened)
    launchPicker()
}

internal fun <T> executeLaunch(
    effect: ManagerEffect.LaunchIntent,
    resolveIntent: (ManagerEffect.LaunchIntent) -> T,
    launchIntent: (T) -> Unit,
    onSuccess: (ManagerEffect.LaunchIntent) -> Unit,
    onFailure: (ManagerEffect.LaunchIntent) -> Unit,
): Boolean {
    val intent = try {
        resolveIntent(effect)
    } catch (_: RuntimeException) {
        onFailure(effect)
        return false
    }
    try {
        launchIntent(intent)
    } catch (_: RuntimeException) {
        onFailure(effect)
        return false
    }
    onSuccess(effect)
    return true
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
