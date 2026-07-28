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
        } ?: Text(stringResource(R.string.conversion_result_import_pending))
        SectionCard(stringResource(R.string.conversion_generated_bundle)) {
            Text(ready.generated.suggestedFileName)
            Text("SHA-256: ${ready.generated.sha256}")
        }
        message?.let {
            MessageCard(
                message = it,
                onRecovery = { onAction(ManagerAction.DismissMessage) },
                onDismiss = { onAction(ManagerAction.DismissMessage) },
            )
        }
        Button(
            onClick = onInstall,
            enabled = ready.review != null &&
                installState == dev.sixbridge.manager.install.InstallState.Idle,
            modifier = Modifier.fillMaxWidth().testTag("conversion:install"),
        ) {
            Text(stringResource(R.string.review_install))
        }
        OutlinedButton(
            onClick = { onAction(ManagerAction.SaveGeneratedConversion) },
            modifier = Modifier.fillMaxWidth().testTag("conversion:save"),
        ) {
            Text(stringResource(R.string.conversion_save))
        }
        OutlinedButton(
            onClick = { onAction(ManagerAction.ShareGeneratedConversion) },
            modifier = Modifier.fillMaxWidth().testTag("conversion:share"),
        ) {
            Text(stringResource(R.string.conversion_share))
        }
        Button(
            onClick = { onAction(ManagerAction.FinishConversion) },
            modifier = Modifier.fillMaxWidth().testTag("conversion:done"),
        ) {
            Text(stringResource(R.string.conversion_done))
        }
        OutlinedButton(
            onClick = { onAction(ManagerAction.CancelConversion) },
            modifier = Modifier.fillMaxWidth().testTag("conversion:cancel"),
        ) {
            Text(stringResource(R.string.conversion_cancel))
        }
    }
}
