package dev.sixbridge.manager.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.sixbridge.manager.R

@Composable
fun ConversionResultScreen(
    ready: ConversionUiState.ResultReady,
    message: UserMessage?,
    installState: dev.sixbridge.manager.install.InstallState,
    onAction: (ManagerAction) -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ownedInstallState = installState.forImportToken(ready.review?.token)
    CenteredScrollableColumn(
        modifier = modifier,
        testTagModifier = Modifier.testTag("conversion:result-screen"),
    ) {
        Text(
            text = stringResource(R.string.conversion_result_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        ready.review?.let { review ->
            SectionCard(stringResource(R.string.review_identity)) {
                Text("${review.name} · ${review.version}")
                Text("Extension ID: ${review.extensionId}")
                Text("Package: ${review.packageName}")
                Text("Signer SHA-256: ${review.signerSha256}")
                Text("versionCode: ${review.wrapperVersionCode}")
                Text(stringResource(R.string.conversion_status_summary, review.status.name))
            }
            if (review.forcedCompatibility != null) {
                SectionCard(stringResource(R.string.review_forced_title)) {
                    Text(stringResource(R.string.review_forced_warning))
                    Text("issue: ${review.forcedCompatibility.issueCodes.joinToString()}")
                }
            }
        } ?: Text(stringResource(R.string.conversion_result_import_pending))
        SectionCard(stringResource(R.string.conversion_generated_bundle)) {
            Text(ready.generated.suggestedFileName)
            Text("SHA-256: ${ready.generated.sha256}")
        }
        message?.let {
            val recoveryAction = resultRecoveryAction(it)
            MessageCard(
                message = if (recoveryAction == null) {
                    it.copy(recoveryAction = RecoveryAction.NONE)
                } else {
                    it
                },
                onRecovery = {
                    recoveryAction?.let(onAction)
                },
                onDismiss = { onAction(ManagerAction.DismissMessage) },
            )
        }
        if (ownedInstallState is
            dev.sixbridge.manager.install.InstallState.AwaitingManualInstall
        ) {
            ManualInstallHandoff(onAction)
        } else {
            Button(
                onClick = onInstall,
                enabled = ready.review != null &&
                    !ready.installOwnerActive &&
                    !ready.cleanupPending &&
                    !ready.cleanupInProgress &&
                    !ownedInstallState.isActiveInstall(),
                modifier = Modifier.fillMaxWidth().testTag("conversion:install"),
            ) {
                Text(stringResource(R.string.review_install))
            }
        }
        OutlinedButton(
            onClick = { onAction(ManagerAction.SaveGeneratedConversion) },
            enabled = !ready.cleanupPending && !ready.cleanupInProgress,
            modifier = Modifier.fillMaxWidth().testTag("conversion:save"),
        ) {
            Text(stringResource(R.string.conversion_save))
        }
        OutlinedButton(
            onClick = { onAction(ManagerAction.ShareGeneratedConversion) },
            enabled = !ready.cleanupPending && !ready.cleanupInProgress,
            modifier = Modifier.fillMaxWidth().testTag("conversion:share"),
        ) {
            Text(stringResource(R.string.conversion_share))
        }
        Button(
            onClick = { onAction(ManagerAction.FinishConversion) },
            enabled = !ready.installOwnerActive &&
                !ready.cleanupPending &&
                !ready.cleanupInProgress,
            modifier = Modifier.fillMaxWidth().testTag("conversion:done"),
        ) {
            Text(stringResource(R.string.conversion_done))
        }
        OutlinedButton(
            onClick = { onAction(ManagerAction.CancelConversion) },
            enabled = !ready.cleanupPending && !ready.cleanupInProgress,
            modifier = Modifier.fillMaxWidth().testTag("conversion:cancel"),
        ) {
            Text(stringResource(R.string.conversion_cancel))
        }
    }
}

private fun resultRecoveryAction(message: UserMessage): ManagerAction? {
    if (message.recoveryAction == RecoveryAction.CHECK_INSTALL_STATUS) {
        return ManagerAction.ConfirmSystemInstallStatus
    }
    return when (message.kind) {
        UserMessageKind.CONVERSION_CLEANUP_FAILED ->
            ManagerAction.RetryGeneratedCleanup
        UserMessageKind.CONVERSION_SAVE_FAILED,
        UserMessageKind.CONVERSION_SAVE_CLEANUP_FAILED,
        -> ManagerAction.SaveGeneratedConversion
        else -> null
    }
}

private fun dev.sixbridge.manager.install.InstallState.isActiveInstall(): Boolean =
        this == dev.sixbridge.manager.install.InstallState.UnknownSourcesPermissionRequired ||
        this is dev.sixbridge.manager.install.InstallState.PreparingApkExport ||
        this is dev.sixbridge.manager.install.InstallState.AwaitingManualInstall ||
        this is dev.sixbridge.manager.install.InstallState.Failed &&
        code in setOf(
            "CLEANUP_FAILED",
            "ABANDON_FAILED",
            "SESSION_RECOVERY_FAILED",
            "MISSING_OBSERVATION_RESET_FAILED",
        )
