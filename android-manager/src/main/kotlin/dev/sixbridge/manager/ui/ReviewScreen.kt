package dev.sixbridge.manager.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.manager.R
import dev.sixbridge.manager.install.InstallState

@Composable
fun ReviewScreen(
    ready: ImportUiState.ReviewReady,
    installState: InstallState,
    message: UserMessage?,
    onAction: (ManagerAction) -> Unit,
    onInstall: () -> Unit,
    onAddSix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val review = ready.review
    val installBusy = installState != InstallState.Idle &&
        installState !is InstallState.Failed &&
        installState !is InstallState.Installed &&
        installState != InstallState.Cancelled
    CenteredScrollableColumn(
        modifier = modifier,
        testTagModifier = Modifier.testTag("review:scroll"),
    ) {
        Text(
            text = stringResource(R.string.review_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        SectionCard(stringResource(R.string.review_identity)) {
            Text(
                text = stringResource(R.string.review_name_version, review.name, review.version),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(text = stringResource(R.string.review_extension_id, review.extensionId))
            Text(text = stringResource(R.string.review_package, review.packageName))
            Text(text = stringResource(R.string.review_wrapper_version, review.wrapperVersionCode))
        }
        SectionCard(
            title = stringResource(R.string.review_status),
            modifier = Modifier.testTag("review:status"),
        ) {
            CompatibilityStatusText(review.status)
        }
        SectionCard(
            title = stringResource(R.string.review_permissions),
            modifier = Modifier.testTag("review:permissions"),
        ) {
            TextList(review.declaredPermissions)
        }
        SectionCard(
            title = stringResource(R.string.review_issues),
            modifier = Modifier.testTag("review:issues"),
        ) {
            TextList(review.issues.map { issue ->
                buildString {
                    if (issue.blocking) append("[차단] ")
                    append(issue.code)
                    append(": ")
                    append(issue.message)
                }
            })
        }
        SectionCard(
            title = stringResource(R.string.review_adapters),
            modifier = Modifier.testTag("review:adapters"),
        ) {
            TextList(review.adapterDecisions.map { decision ->
                buildString {
                    append(decision.id)
                    append(" v")
                    append(decision.version)
                    append(" · ")
                    append(decision.disposition)
                    if (decision.remainingLimitations.isNotEmpty()) {
                        append("\n")
                        append(decision.remainingLimitations.joinToString("\n"))
                    }
                }
            })
        }
        SectionCard(
            title = stringResource(R.string.review_signer),
            modifier = Modifier.testTag("review:signer"),
        ) {
            Text(text = review.signerSha256)
            Text(
                text = stringResource(R.string.review_security_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (review.status == ContractCompatibilityStatus.PARTIAL) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !ready.partialConfirmed) {
                        onAction(ManagerAction.ConfirmPartial)
                    }
                    .testTag("review:partial-confirm"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = ready.partialConfirmed,
                    onCheckedChange = { checked ->
                        if (checked) onAction(ManagerAction.ConfirmPartial)
                    },
                )
                Text(text = stringResource(R.string.review_partial_notice))
            }
        }
        InstallProgress(installState)
        message?.let { currentMessage ->
            MessageCard(
                message = currentMessage,
                onRecovery = {
                    when (currentMessage.recoveryAction) {
                        RecoveryAction.OPEN_SETTINGS ->
                            onAction(ManagerAction.OpenUnknownSourcesSettings)
                        RecoveryAction.CONFIRM_PARTIAL ->
                            onAction(ManagerAction.ConfirmPartial)
                        RecoveryAction.SELECT_ANOTHER_FILE,
                        -> onAddSix()
                        RecoveryAction.RETRY -> onAction(ManagerAction.RetryRecovery)
                        RecoveryAction.CHECK_INSTALL_STATUS ->
                            onAction(ManagerAction.ConfirmSystemInstallStatus)
                        RecoveryAction.NONE -> onAction(ManagerAction.DismissMessage)
                    }
                },
                onDismiss = { onAction(ManagerAction.DismissMessage) },
            )
        }
        if (installState is InstallState.AwaitingManualInstall) {
            ManualInstallHandoff(onAction)
        } else {
            Button(
                onClick = onInstall,
                enabled = ready.installEnabled && !installBusy,
                modifier = Modifier.fillMaxWidth().testTag("review:install"),
            ) {
                Text(text = stringResource(R.string.review_install))
            }
        }
        OutlinedButton(
            onClick = { onAction(ManagerAction.CancelReview) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.review_cancel))
        }
    }
}

@Composable
internal fun ManualInstallHandoff(
    onAction: (ManagerAction) -> Unit,
) {
    SectionCard(
        title = stringResource(R.string.manual_install_title),
        modifier = Modifier.testTag("manual-install:instructions"),
    ) {
        Text(text = stringResource(R.string.manual_install_instruction))
        Text(
            text = stringResource(R.string.manual_install_warning),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(
        onClick = { onAction(ManagerAction.OpenSamsungMyFiles) },
        modifier = Modifier.fillMaxWidth().testTag("manual-install:open-my-files"),
    ) {
        Text(text = stringResource(R.string.manual_install_open_my_files))
    }
    OutlinedButton(
        onClick = { onAction(ManagerAction.ConfirmManualInstallStatus) },
        modifier = Modifier.fillMaxWidth().testTag("manual-install:check-status"),
    ) {
        Text(text = stringResource(R.string.manual_install_check_status))
    }
}

@Composable
private fun InstallProgress(installState: InstallState) {
    val label = when (installState) {
        InstallState.PreparingSystemInstaller -> R.string.install_preparing_system
        is InstallState.PreparingApkExport -> R.string.install_preparing_system
        is InstallState.AwaitingSystemInstaller -> R.string.install_awaiting_system
        is InstallState.AwaitingManualInstall -> return
        InstallState.UnknownSourcesPermissionRequired -> R.string.install_unknown_sources
        else -> return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(text = stringResource(label))
    }
}

@Composable
internal fun TextList(items: List<String>) {
    if (items.isEmpty()) {
        Text(text = stringResource(R.string.review_none))
    } else {
        items.forEach { item -> Text(text = "• $item") }
    }
}
