package dev.sixbridge.manager

import android.content.ContentResolver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

class MainActivity : ComponentActivity() {
    private lateinit var managerViewModel: ManagerViewModel
    private lateinit var notificationInstallGate: NotificationPermissionInstallGate
    private var pendingConversionPicker: PendingActivityDocumentLaunch? = null
    private var pendingConversionSave: PendingActivityDocumentLaunch? = null

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let { managerViewModel.accept(ManagerAction.ImportSelected(it)) }
    }

    private val openConversionDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val pending = pendingConversionPicker
        pendingConversionPicker = null
        pending?.let {
            managerViewModel.accept(it.conversionFileAction(uri))
        }
    }

    private val createConversionDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(GENERATED_BUNDLE_MIME_TYPE),
    ) { uri ->
        val pending = pendingConversionSave
        pendingConversionSave = null
        if (pending != null) {
            managerViewModel.accept(pending.conversionSaveAction(uri))
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
        pendingConversionPicker = savedInstanceState?.restorePendingDocumentLaunch(
            tokenKey = null,
            ownerKey = PENDING_CONVERSION_PICKER_OWNER,
            launchKey = PENDING_CONVERSION_PICKER_LAUNCH_ID,
        )
        pendingConversionSave = savedInstanceState?.restorePendingDocumentLaunch(
            tokenKey = PENDING_CONVERSION_SAVE_TOKEN,
            ownerKey = PENDING_CONVERSION_SAVE_OWNER,
            launchKey = PENDING_CONVERSION_SAVE_LAUNCH_ID,
        )
        managerViewModel = ViewModelProvider(
            this,
            viewModelFactory {
                initializer {
                    ManagerViewModel(
                        repository = repository,
                        savedStateHandle = createSavedStateHandle(),
                        saveGeneratedDocument = { destination, expectedHash, copy ->
                            writeGeneratedDocument(
                                resolver = contentResolver,
                                uri = Uri.parse(destination),
                                expectedHash = expectedHash,
                                copy = copy,
                            )
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
                        is ManagerEffect.PickConversionDocument -> {
                            pendingConversionPicker = PendingActivityDocumentLaunch(
                                token = null,
                                ownerGeneration = effect.ownerGeneration,
                                launchId = effect.launchId,
                            )
                            openConversionDocument.launch(CONVERSION_MIME_TYPES)
                        }
                        is ManagerEffect.CreateConversionDocument -> {
                            pendingConversionSave = PendingActivityDocumentLaunch(
                                token = effect.token,
                                ownerGeneration = effect.ownerGeneration,
                                launchId = effect.launchId,
                            )
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
        pendingConversionPicker?.save(
            outState = outState,
            tokenKey = null,
            ownerKey = PENDING_CONVERSION_PICKER_OWNER,
            launchKey = PENDING_CONVERSION_PICKER_LAUNCH_ID,
        )
        pendingConversionSave?.save(
            outState = outState,
            tokenKey = PENDING_CONVERSION_SAVE_TOKEN,
            ownerKey = PENDING_CONVERSION_SAVE_OWNER,
            launchKey = PENDING_CONVERSION_SAVE_LAUNCH_ID,
        )
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
        const val PENDING_CONVERSION_SAVE_OWNER = "pending_conversion_save_owner"
        const val PENDING_CONVERSION_SAVE_LAUNCH_ID = "pending_conversion_save_launch_id"
        const val PENDING_CONVERSION_PICKER_OWNER = "pending_conversion_picker_owner"
        const val PENDING_CONVERSION_PICKER_LAUNCH_ID = "pending_conversion_picker_launch_id"
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

internal data class PendingActivityDocumentLaunch(
    val token: String?,
    val ownerGeneration: Long,
    val launchId: Long,
) {
    internal fun save(
        outState: Bundle,
        tokenKey: String?,
        ownerKey: String,
        launchKey: String,
    ) {
        tokenKey?.let { key -> token?.let { outState.putString(key, it) } }
        outState.putLong(ownerKey, ownerGeneration)
        outState.putLong(launchKey, launchId)
    }
}

internal fun PendingActivityDocumentLaunch.conversionFileAction(
    uri: Uri?,
): ManagerAction.ConversionFileSelected = ManagerAction.ConversionFileSelected(
    uri = uri,
    ownerGeneration = ownerGeneration,
    launchId = launchId,
)

internal fun PendingActivityDocumentLaunch.conversionSaveAction(
    uri: Uri?,
): ManagerAction.SaveGeneratedConversionTo = ManagerAction.SaveGeneratedConversionTo(
    token = requireNotNull(token),
    destination = uri?.toString(),
    ownerGeneration = ownerGeneration,
    launchId = launchId,
)

internal fun Bundle.restorePendingDocumentLaunch(
    tokenKey: String?,
    ownerKey: String,
    launchKey: String,
): PendingActivityDocumentLaunch? {
    if (!containsKey(ownerKey) || !containsKey(launchKey)) return null
    val token = tokenKey?.let(::getString)
    if (tokenKey != null && token == null) return null
    return PendingActivityDocumentLaunch(
        token = token,
        ownerGeneration = getLong(ownerKey),
        launchId = getLong(launchKey),
    )
}

internal const val GENERATED_BUNDLE_MIME_TYPE = "application/vnd.sixbridge.bundle"

sealed interface GeneratedDocumentSaveResult {
    data object Success : GeneratedDocumentSaveResult

    data class Failure(
        val cleanupFailed: Boolean,
    ) : GeneratedDocumentSaveResult

    data class Cancelled(
        val cleanupFailed: Boolean,
    ) : GeneratedDocumentSaveResult
}

internal suspend fun writeGeneratedDocument(
    resolver: ContentResolver,
    uri: Uri,
    expectedHash: String,
    copy: (OutputStream) -> String?,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): GeneratedDocumentSaveResult {
    val callerJob = currentCoroutineContext()[Job]
    try {
        val copiedHash = withContext(ioDispatcher) {
            val descriptor = resolver.openFileDescriptor(uri, "rwt")
                ?: throw GeneratedDocumentOpenException()
            descriptor.use {
                FileOutputStream(it.fileDescriptor).use { output ->
                    callerJob?.ensureActive()
                    val copiedHash = copy(CancellationAwareOutputStream(output, callerJob))
                    callerJob?.ensureActive()
                    output.flush()
                    callerJob?.ensureActive()
                    descriptor.checkError()
                    callerJob?.ensureActive()
                    copiedHash
                }
            }
        }
        if (copiedHash == expectedHash) {
            return GeneratedDocumentSaveResult.Success
        }
    } catch (error: CancellationException) {
        return withContext(NonCancellable) {
            val cleanupFailed = withContext(ioDispatcher) {
                !resolver.deleteGeneratedDocument(uri)
            }
            GeneratedDocumentSaveResult.Cancelled(
                cleanupFailed = cleanupFailed,
            )
        }
    } catch (_: Exception) {
        // The partial destination is removed below.
    }
    return withContext(NonCancellable) {
        withContext(ioDispatcher) {
            generatedDocumentFailure(resolver, uri)
        }
    }
}

private class GeneratedDocumentOpenException : IllegalStateException()

private class CancellationAwareOutputStream(
    output: OutputStream,
    private val callerJob: Job?,
) : FilterOutputStream(output) {
    override fun write(value: Int) {
        callerJob?.ensureActive()
        out.write(value)
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        callerJob?.ensureActive()
        out.write(bytes, offset, length)
    }
}

private fun generatedDocumentFailure(
    resolver: ContentResolver,
    uri: Uri,
): GeneratedDocumentSaveResult.Failure = GeneratedDocumentSaveResult.Failure(
    cleanupFailed = !resolver.deleteGeneratedDocument(uri),
)

private fun ContentResolver.deleteGeneratedDocument(uri: Uri): Boolean =
    try {
        delete(uri, null, null) > 0
    } catch (_: Exception) {
        false
    }

internal fun generatedBundleSendIntent(
    resolver: ContentResolver,
    uri: Uri,
): Intent = Intent(Intent.ACTION_SEND).apply {
    type = GENERATED_BUNDLE_MIME_TYPE
    putExtra(Intent.EXTRA_STREAM, uri)
    clipData = ClipData.newUri(resolver, "SIX bundle", uri)
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
        generatedBundleSendIntent(context.contentResolver, uri),
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
