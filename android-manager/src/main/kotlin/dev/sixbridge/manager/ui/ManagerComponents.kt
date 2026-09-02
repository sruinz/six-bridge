package dev.sixbridge.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sixbridge.contract.bundle.ContractCompatibilityStatus
import dev.sixbridge.manager.R

internal val ContentMaxWidth = 720.dp

@Composable
internal fun CenteredScrollableColumn(
    modifier: Modifier = Modifier,
    testTagModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = ContentMaxWidth)
                .fillMaxWidth()
                .then(testTagModifier)
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(horizontal = 20.dp, vertical = 24.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            content()
        }
    }
}

@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
internal fun MessageCard(
    message: UserMessage,
    onRecovery: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = message.text,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            if (message.recoveryAction != RecoveryAction.NONE) {
                Button(onClick = onRecovery) {
                    Text(text = recoveryLabel(message.recoveryAction))
                }
            } else {
                OutlinedButton(onClick = onDismiss) {
                    Text(text = stringResource(R.string.message_dismiss))
                }
            }
        }
    }
}

@Composable
internal fun CompatibilityStatusText(status: ContractCompatibilityStatus) {
    Text(
        text = compatibilityStatusLabel(status),
        style = MaterialTheme.typography.headlineSmall,
        color = when (status) {
            ContractCompatibilityStatus.COMPATIBLE -> MaterialTheme.colorScheme.primary
            ContractCompatibilityStatus.PARTIAL -> MaterialTheme.colorScheme.tertiary
            ContractCompatibilityStatus.BLOCKED -> MaterialTheme.colorScheme.error
            ContractCompatibilityStatus.UNKNOWN -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
internal fun compatibilityStatusLabel(status: ContractCompatibilityStatus): String =
    stringResource(
        when (status) {
            ContractCompatibilityStatus.COMPATIBLE -> R.string.status_compatible
            ContractCompatibilityStatus.PARTIAL -> R.string.status_partial
            ContractCompatibilityStatus.BLOCKED -> R.string.status_blocked
            ContractCompatibilityStatus.UNKNOWN -> R.string.status_unknown
        },
    )

@Composable
private fun recoveryLabel(action: RecoveryAction): String = stringResource(
    when (action) {
        RecoveryAction.SELECT_ANOTHER_FILE -> R.string.message_recover_select
        RecoveryAction.CONFIRM_PARTIAL -> R.string.message_recover_confirm
        RecoveryAction.OPEN_SETTINGS -> R.string.message_recover_settings
        RecoveryAction.RETRY -> R.string.message_recover_retry
        RecoveryAction.RETRY_INSTALL_APK_CLEANUP -> R.string.message_retry_apk_cleanup
        RecoveryAction.CONFIRM_INSTALL_APK_CLEANUP -> R.string.message_confirm_apk_cleanup
        RecoveryAction.CHECK_INSTALL_STATUS -> R.string.message_recover_check_install
        RecoveryAction.NONE -> R.string.message_dismiss
    },
)
