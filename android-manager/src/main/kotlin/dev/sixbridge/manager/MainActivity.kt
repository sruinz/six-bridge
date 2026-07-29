package dev.sixbridge.manager

import android.content.ContentResolver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import dev.sixbridge.manager.ui.StartupRecoveryUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CancellationException

class MainActivity : ComponentActivity() {
    private lateinit var managerViewModel: ManagerViewModel
    private var sixPickerLaunchGate = ActivityDocumentLaunchGate()
    private var pendingConversionPicker: PendingActivityDocumentLaunch? = null
    private var pendingConversionSave: PendingActivityDocumentLaunch? = null
    private var systemInstallApkHandoff = SystemInstallApkDocumentHandoff()
    private var systemInstallApkCopyJob: Job? = null

    private val openDocument = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val completion = sixPickerLaunchGate.complete()
        completion?.completed?.let {
            managerViewModel.accept(it.sixImportAction(uri))
        }
        completion?.launchNext?.let {
            launchSixDocumentPicker()
        }
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

    private val createSystemInstallApkDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument(APK_MIME_TYPE),
    ) { uri ->
        if (uri == null) {
            systemInstallApkHandoff.cancelSelection()?.let { completion ->
                if (managerViewModel.ownsSystemInstallApkDocument(
                        completion.pending.effect,
                    )
                ) {
                    managerViewModel.accept(completion.action)
                }
                systemInstallApkHandoff.acknowledge(completion)
            }
        } else {
            systemInstallApkHandoff.selectDestination(uri)
            resumeSystemInstallApkCopy()
        }
    }

    private fun resumeSystemInstallApkCopy() {
        if (systemInstallApkCopyJob?.isActive == true) return
        systemInstallApkCopyJob = lifecycleScope.launch {
            managerViewModel.state.first {
                it.startupRecoveryState != StartupRecoveryUiState.INITIALIZING
            }
            val pending = systemInstallApkHandoff.pending ?: return@launch
            if (pending.destination == null) return@launch
            if (!managerViewModel.ownsSystemInstallApkDocument(pending.effect)) {
                systemInstallApkHandoff.clear(pending)
                return@launch
            }
            val completion = systemInstallApkHandoff.prepareCompletion { exact ->
                writeGeneratedDocument(
                    resolver = contentResolver,
                    uri = requireNotNull(exact.destination),
                    expectedHash = exact.effect.expectedSha256,
                    copy = { output -> copyFileAndSha256(exact.effect.source, output) },
                )
            } ?: return@launch
            if (managerViewModel.ownsSystemInstallApkDocument(
                    completion.pending.effect,
                )
            ) {
                managerViewModel.accept(
                    completion.action,
                )
            }
            systemInstallApkHandoff.acknowledge(completion)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = ManagerDependencies.create(application).repository
        sixPickerLaunchGate = ActivityDocumentLaunchGate(
            active = savedInstanceState?.restorePendingDocumentLaunch(
                tokenKey = null,
                ownerKey = PENDING_SIX_PICKER_OWNER,
                launchKey = PENDING_SIX_PICKER_LAUNCH_ID,
            ),
            queued = savedInstanceState?.restorePendingDocumentLaunch(
                tokenKey = null,
                ownerKey = QUEUED_SIX_PICKER_OWNER,
                launchKey = QUEUED_SIX_PICKER_LAUNCH_ID,
            ),
        )
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
        systemInstallApkHandoff =
            SystemInstallApkDocumentHandoff.restore(savedInstanceState)
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
        setContent {
            SixBridgeManagerApp(
                viewModel = managerViewModel,
                onAddSix = {
                    managerViewModel.accept(ManagerAction.OpenSixFilePicker)
                },
                onInstallReviewed = {
                    managerViewModel.accept(ManagerAction.PrepareReviewedInstall)
                },
            )
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                managerViewModel.effects.collect { effect ->
                    when (effect) {
                        is ManagerEffect.PickSixDocument -> {
                            val launch = PendingActivityDocumentLaunch(
                                token = null,
                                ownerGeneration = effect.ownerGeneration,
                                launchId = effect.launchId,
                            )
                            if (sixPickerLaunchGate.enqueue(launch) != null) {
                                launchSixDocumentPicker()
                            }
                        }
                        is ManagerEffect.RequestInstallNotificationPermission -> Unit
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
                        is ManagerEffect.CreateSystemInstallApkDocument -> {
                            systemInstallApkHandoff.begin(effect)
                            createSystemInstallApkDocument.launch(effect.suggestedFileName)
                        }
                        is ManagerEffect.ShareGeneratedBundle -> {
                            executeGeneratedShareLaunch(
                                effect = effect,
                                resolveChooser = {
                                    generatedBundleShareChooser(
                                        this@MainActivity,
                                        it.file,
                                    )
                                },
                                launchChooser = ::startActivity,
                                onAction = managerViewModel::accept,
                            )
                        }
                    }
                }
            }
        }
        resumeSystemInstallApkCopy()
    }

    override fun onResume() {
        super.onResume()
        if (::managerViewModel.isInitialized) {
            managerViewModel.accept(ManagerAction.ReturnedToForeground)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        systemInstallApkHandoff.save(outState)
        sixPickerLaunchGate.active?.save(
            outState = outState,
            tokenKey = null,
            ownerKey = PENDING_SIX_PICKER_OWNER,
            launchKey = PENDING_SIX_PICKER_LAUNCH_ID,
        )
        sixPickerLaunchGate.queued?.save(
            outState = outState,
            tokenKey = null,
            ownerKey = QUEUED_SIX_PICKER_OWNER,
            launchKey = QUEUED_SIX_PICKER_LAUNCH_ID,
        )
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

    private fun launchSixDocumentPicker() {
        openDocument.launch(SIX_MIME_TYPES)
    }

    override fun onStop() {
        ManagerAppVisibility.onActivityStopped()
        super.onStop()
    }

    private companion object {
        const val PENDING_SIX_PICKER_OWNER = "pending_six_picker_owner"
        const val PENDING_SIX_PICKER_LAUNCH_ID = "pending_six_picker_launch_id"
        const val QUEUED_SIX_PICKER_OWNER = "queued_six_picker_owner"
        const val QUEUED_SIX_PICKER_LAUNCH_ID = "queued_six_picker_launch_id"
        const val PENDING_CONVERSION_SAVE_TOKEN = "pending_conversion_save_token"
        const val PENDING_CONVERSION_SAVE_OWNER = "pending_conversion_save_owner"
        const val PENDING_CONVERSION_SAVE_LAUNCH_ID = "pending_conversion_save_launch_id"
        const val PENDING_CONVERSION_PICKER_OWNER = "pending_conversion_picker_owner"
        const val PENDING_CONVERSION_PICKER_LAUNCH_ID = "pending_conversion_picker_launch_id"
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

private const val PENDING_SYSTEM_APK_TOKEN = "pending_system_apk_token"
private const val PENDING_SYSTEM_APK_SOURCE = "pending_system_apk_source"
private const val PENDING_SYSTEM_APK_SHA256 = "pending_system_apk_sha256"
private const val PENDING_SYSTEM_APK_NAME = "pending_system_apk_name"
private const val PENDING_SYSTEM_APK_DESTINATION = "pending_system_apk_destination"

internal data class PendingSystemInstallApkDocument(
    val effect: ManagerEffect.CreateSystemInstallApkDocument,
    val destination: Uri? = null,
)

internal data class SystemInstallApkDocumentCompletion(
    val pending: PendingSystemInstallApkDocument,
    val action: ManagerAction.SystemInstallApkCreated,
)

internal class SystemInstallApkDocumentHandoff(
    pending: PendingSystemInstallApkDocument? = null,
) {
    var pending: PendingSystemInstallApkDocument? = pending
        private set

    fun begin(effect: ManagerEffect.CreateSystemInstallApkDocument) {
        pending = PendingSystemInstallApkDocument(effect)
    }

    fun selectDestination(destination: Uri) {
        pending = pending?.copy(destination = destination)
    }

    fun cancelSelection(): SystemInstallApkDocumentCompletion? {
        val exact = pending ?: return null
        return SystemInstallApkDocumentCompletion(
            pending = exact,
            action = ManagerAction.SystemInstallApkCreated(
                importToken = exact.effect.importToken,
                uri = null,
            ),
        )
    }

    suspend fun prepareCompletion(
        copy: suspend (PendingSystemInstallApkDocument) ->
            GeneratedDocumentSaveResult,
    ): SystemInstallApkDocumentCompletion? {
        val exact = pending?.takeIf { it.destination != null } ?: return null
        val result = copy(exact)
        if (pending != exact) return null
        return SystemInstallApkDocumentCompletion(
            pending = exact,
            action = ManagerAction.SystemInstallApkCreated(
                importToken = exact.effect.importToken,
                uri = exact.destination.takeIf {
                    result == GeneratedDocumentSaveResult.Success
                },
            ),
        )
    }

    fun acknowledge(completion: SystemInstallApkDocumentCompletion): Boolean {
        if (pending != completion.pending) return false
        pending = null
        return true
    }

    fun clear(expected: PendingSystemInstallApkDocument): Boolean {
        if (pending != expected) return false
        pending = null
        return true
    }

    fun save(outState: Bundle) {
        val exact = pending ?: return
        outState.putString(PENDING_SYSTEM_APK_TOKEN, exact.effect.importToken)
        outState.putString(PENDING_SYSTEM_APK_SOURCE, exact.effect.source.path)
        outState.putString(
            PENDING_SYSTEM_APK_SHA256,
            exact.effect.expectedSha256,
        )
        outState.putString(
            PENDING_SYSTEM_APK_NAME,
            exact.effect.suggestedFileName,
        )
        exact.destination?.let {
            outState.putString(PENDING_SYSTEM_APK_DESTINATION, it.toString())
        }
    }

    companion object {
        fun restore(savedState: Bundle?): SystemInstallApkDocumentHandoff {
            savedState ?: return SystemInstallApkDocumentHandoff()
            val token = savedState.getString(PENDING_SYSTEM_APK_TOKEN)
                ?: return SystemInstallApkDocumentHandoff()
            val source = savedState.getString(PENDING_SYSTEM_APK_SOURCE)
                ?: return SystemInstallApkDocumentHandoff()
            val sha256 = savedState.getString(PENDING_SYSTEM_APK_SHA256)
                ?: return SystemInstallApkDocumentHandoff()
            val name = savedState.getString(PENDING_SYSTEM_APK_NAME)
                ?: return SystemInstallApkDocumentHandoff()
            val destination = savedState
                .getString(PENDING_SYSTEM_APK_DESTINATION)
                ?.let(Uri::parse)
            return SystemInstallApkDocumentHandoff(
                PendingSystemInstallApkDocument(
                    effect = ManagerEffect.CreateSystemInstallApkDocument(
                        importToken = token,
                        source = File(source),
                        expectedSha256 = sha256,
                        suggestedFileName = name,
                    ),
                    destination = destination,
                ),
            )
        }
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

internal data class ActivityDocumentLaunchCompletion(
    val completed: PendingActivityDocumentLaunch,
    val launchNext: PendingActivityDocumentLaunch?,
)

internal class ActivityDocumentLaunchGate(
    active: PendingActivityDocumentLaunch? = null,
    queued: PendingActivityDocumentLaunch? = null,
) {
    var active: PendingActivityDocumentLaunch? = active
        private set
    var queued: PendingActivityDocumentLaunch? = queued
        private set

    init {
        require(active != null || queued == null) {
            "queued document launch에는 active launch가 필요합니다."
        }
    }

    fun enqueue(launch: PendingActivityDocumentLaunch): PendingActivityDocumentLaunch? {
        if (active == null) {
            active = launch
            return launch
        }
        queued = launch
        return null
    }

    fun complete(): ActivityDocumentLaunchCompletion? {
        val completed = active ?: return null
        val launchNext = queued
        active = launchNext
        queued = null
        return ActivityDocumentLaunchCompletion(completed, launchNext)
    }
}

internal fun PendingActivityDocumentLaunch.conversionFileAction(
    uri: Uri?,
): ManagerAction.ConversionFileSelected = ManagerAction.ConversionFileSelected(
    uri = uri,
    ownerGeneration = ownerGeneration,
    launchId = launchId,
)

internal fun PendingActivityDocumentLaunch.sixImportAction(
    uri: Uri?,
): ManagerAction.ImportSelected = ManagerAction.ImportSelected(
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
internal const val APK_MIME_TYPE = "application/vnd.android.package-archive"

internal fun copyFileAndSha256(source: File, output: OutputStream): String? {
    if (!source.isFile || Files.isSymbolicLink(source.toPath())) return null
    val digest = MessageDigest.getInstance("SHA-256")
    source.inputStream().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

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
    val root = File(context.filesDir, "generated-bundles/share-handoff").canonicalFile
    val canonicalFile = file.canonicalFile
    require(canonicalFile.parentFile == root && file.parentFile?.canonicalFile == root) {
        "공유 파일은 share-handoff direct child여야 합니다."
    }
    require(
        file.name.startsWith("share-") &&
            file.name.endsWith(".six") &&
            file.isFile &&
            !Files.isSymbolicLink(file.toPath()),
    ) {
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

internal fun <T> executeGeneratedShareLaunch(
    effect: ManagerEffect.ShareGeneratedBundle,
    resolveChooser: (ManagerEffect.ShareGeneratedBundle) -> T,
    launchChooser: (T) -> Unit,
    onAction: (ManagerAction) -> Unit,
) {
    val action = try {
        launchChooser(resolveChooser(effect))
        ManagerAction.GeneratedShareLaunchSucceeded(
            token = effect.token,
            handoffId = effect.handoffId,
        )
    } catch (_: Exception) {
        ManagerAction.GeneratedShareLaunchFailed(
            token = effect.token,
            handoffId = effect.handoffId,
        )
    }
    onAction(action)
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
    pendingInstall: ManagerEffect.RequestInstallNotificationPermission?,
    private val needsPermission: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val continueInstall: (
        ManagerEffect.RequestInstallNotificationPermission,
    ) -> Unit,
) {
    var pendingInstall: ManagerEffect.RequestInstallNotificationPermission? = pendingInstall
        private set

    fun requestInstall(effect: ManagerEffect.RequestInstallNotificationPermission) {
        if (pendingInstall != null) return
        if (!needsPermission()) {
            continueInstall(effect)
            return
        }
        pendingInstall = effect
        requestPermission()
    }

    fun onPermissionResult() {
        val effect = pendingInstall ?: return
        pendingInstall = null
        continueInstall(effect)
    }
}
