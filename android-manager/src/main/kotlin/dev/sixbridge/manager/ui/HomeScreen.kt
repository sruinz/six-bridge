package dev.sixbridge.manager.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sixbridge.manager.R

@Composable
fun HomeScreen(
    state: ManagerUiState,
    onAction: (ManagerAction) -> Unit,
    onAddSix: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScrollableColumn(
        modifier = modifier,
        testTagModifier = Modifier.testTag("home:scroll"),
    ) {
        Text(
            text = stringResource(R.string.home_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Button(
            onClick = { onAction(ManagerAction.OpenSamsungAddOns) },
            modifier = Modifier.fillMaxWidth().testTag("home:open-addons"),
        ) {
            Text(text = stringResource(R.string.home_open_addons))
        }
        Text(
            text = stringResource(R.string.home_package_status_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.home_installed_title),
            style = MaterialTheme.typography.titleLarge,
        )
        if (state.installed.isEmpty()) {
            Text(text = stringResource(R.string.home_empty))
        } else {
            state.installed.forEach { item ->
                InstalledExtensionCard(item = item, onAction = onAction)
            }
        }
        if (state.importState == ImportUiState.Importing) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(text = stringResource(R.string.install_importing))
            }
        }
        state.message?.let { message ->
            MessageCard(
                message = message,
                onRecovery = {
                    when (message.recoveryAction) {
                        RecoveryAction.OPEN_SETTINGS ->
                            onAction(ManagerAction.OpenUnknownSourcesSettings)
                        RecoveryAction.CONFIRM_PARTIAL ->
                            onAction(ManagerAction.ConfirmPartial)
                        RecoveryAction.SELECT_ANOTHER_FILE,
                        -> onAddSix()
                        RecoveryAction.RETRY -> onAction(ManagerAction.RetryRecovery)
                        RecoveryAction.NONE -> onAction(ManagerAction.DismissMessage)
                    }
                },
                onDismiss = { onAction(ManagerAction.DismissMessage) },
            )
        }
        Button(
            onClick = onAddSix,
            enabled = state.importState != ImportUiState.Importing,
            modifier = Modifier.fillMaxWidth().testTag("home:add-six"),
        ) {
            Text(text = stringResource(R.string.home_add_six))
        }
    }
}

@Composable
private fun InstalledExtensionCard(
    item: ManagedExtensionItem,
    onAction: (ManagerAction) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExtensionIcon(item)
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text = item.name, style = MaterialTheme.typography.titleMedium)
                    Text(text = stringResource(R.string.extension_android_installed))
                    Text(text = item.packageName, style = MaterialTheme.typography.bodySmall)
                    Text(
                        text = stringResource(
                            R.string.extension_version_code,
                            item.installedVersionCode,
                        ),
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = { onAction(ManagerAction.OpenReport(item.packageName)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("extension:${item.packageName}:report"),
                ) {
                    Text(text = stringResource(R.string.extension_report))
                }
                OutlinedButton(
                    onClick = { onAction(ManagerAction.Remove(item.packageName)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("extension:${item.packageName}:remove"),
                ) {
                    Text(text = stringResource(R.string.extension_remove))
                }
            }
        }
    }
}

@Composable
private fun ExtensionIcon(item: ManagedExtensionItem) {
    val iconPng = item.iconPng
    val bitmap = remember(item.packageName, iconPng, iconPng?.contentHashCode()) {
        iconPng?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val accessibleName = item.name.trim().ifEmpty { item.packageName }
    val description = stringResource(R.string.extension_icon_description, accessibleName)
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = description,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(8.dp))
                .testTag("extension:${item.packageName}:icon:image"),
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .size(40.dp)
                .semantics { contentDescription = description }
                .testTag("extension:${item.packageName}:icon:fallback"),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = item.name.trim().firstOrNull()?.toString() ?: "?",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
