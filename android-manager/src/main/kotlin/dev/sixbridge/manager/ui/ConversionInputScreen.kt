package dev.sixbridge.manager.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sixbridge.manager.R

@Composable
fun ConversionInputScreen(
    state: ManagerUiState,
    onAction: (ManagerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val acquiring = state.conversionState == ConversionUiState.Acquiring
    val conversionInputEnabled = !acquiring && !state.conversionCleanupPending
    CenteredScrollableColumn(
        modifier = modifier,
        testTagModifier = Modifier.testTag("conversion:input-screen"),
    ) {
        Text(
            text = stringResource(R.string.conversion_input_title),
            style = androidx.compose.material3.MaterialTheme.typography.headlineMedium,
        )
        Text(text = stringResource(R.string.conversion_input_description))
        OutlinedTextField(
            value = state.conversionUrl,
            onValueChange = { onAction(ManagerAction.ConversionUrlChanged(it)) },
            enabled = conversionInputEnabled,
            singleLine = true,
            label = { Text(stringResource(R.string.conversion_url_label)) },
            modifier = Modifier.fillMaxWidth().testTag("conversion:url"),
        )
        Button(
            onClick = { onAction(ManagerAction.AnalyzeConversionUrl) },
            enabled = conversionInputEnabled && state.conversionUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth().testTag("conversion:analyze"),
        ) {
            Text(stringResource(R.string.conversion_analyze))
        }
        OutlinedButton(
            onClick = { onAction(ManagerAction.OpenConversionFilePicker) },
            enabled = conversionInputEnabled,
            modifier = Modifier.fillMaxWidth().testTag("conversion:pick-file"),
        ) {
            Text(stringResource(R.string.conversion_pick_file))
        }
        if (acquiring) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.conversion_acquiring))
            }
        }
        state.message?.let { message ->
            MessageCard(
                message = message,
                onRecovery = {
                    when (message.recoveryAction) {
                        RecoveryAction.SELECT_ANOTHER_FILE ->
                            onAction(ManagerAction.OpenConversionFilePicker)
                        RecoveryAction.RETRY ->
                            onAction(ManagerAction.AnalyzeConversionUrl)
                        RecoveryAction.RETRY_INSTALL_APK_CLEANUP ->
                            message.installApkCleanupReceipt?.let {
                                onAction(ManagerAction.RetryInstallApkCleanup(it))
                            }
                        RecoveryAction.CONFIRM_INSTALL_APK_CLEANUP ->
                            message.installApkCleanupReceipt?.let {
                                onAction(ManagerAction.ConfirmInstallApkCleanup(it))
                            }
                        else -> onAction(ManagerAction.DismissMessage)
                    }
                },
                onDismiss = { onAction(ManagerAction.DismissMessage) },
            )
        }
        OutlinedButton(
            onClick = { onAction(ManagerAction.CancelConversion) },
            modifier = Modifier.fillMaxWidth().testTag("conversion:cancel"),
        ) {
            Text(stringResource(R.string.conversion_cancel))
        }
    }
}
