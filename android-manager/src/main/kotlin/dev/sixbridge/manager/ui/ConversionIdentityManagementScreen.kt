package dev.sixbridge.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.sixbridge.manager.R
import dev.sixbridge.manager.conversion.LegacyConversionIdentity

@Composable
fun ConversionIdentityManagementScreen(
    state: ConversionIdentityManagementUiState,
    onAction: (ManagerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScrollableColumn(
        modifier = modifier,
        testTagModifier = Modifier.testTag("identity-management:screen"),
    ) {
        Text(
            text = stringResource(R.string.identity_management_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(text = stringResource(R.string.identity_management_description))
        Text(
            text = stringResource(R.string.identity_management_proof_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.loading) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator()
                Text(text = stringResource(R.string.identity_management_loading))
            }
        }
        if (state.loadFailed) {
            Text(
                text = stringResource(R.string.identity_management_load_failed),
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(
                onClick = { onAction(ManagerAction.OpenConversionIdentityManagement) },
                modifier = Modifier.fillMaxWidth().testTag("identity-management:retry"),
            ) {
                Text(text = stringResource(R.string.identity_management_retry))
            }
        }
        state.outcome?.let { outcome ->
            Text(
                text = cleanupOutcomeText(outcome),
                modifier = Modifier.testTag("identity-management:outcome"),
                color = when (outcome) {
                    is ConversionIdentityCleanupUiOutcome.Preserved ->
                        MaterialTheme.colorScheme.error
                    is ConversionIdentityCleanupUiOutcome.AlreadyAbsent,
                    is ConversionIdentityCleanupUiOutcome.Removed,
                    -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
        if (!state.loading && !state.loadFailed && state.identities.isEmpty()) {
            Text(text = stringResource(R.string.identity_management_empty))
        }
        state.identities.forEach { identity ->
            LegacyIdentityCard(
                identity = identity,
                cleanupInProgress = state.cleanupInProgressIdentitySha256 != null,
                onRequestCleanup = {
                    onAction(
                        ManagerAction.RequestLegacyIdentityCleanup(identity.identitySha256),
                    )
                },
            )
        }
        OutlinedButton(
            onClick = { onAction(ManagerAction.ReturnHome) },
            modifier = Modifier.fillMaxWidth().testTag("identity-management:back"),
        ) {
            Text(text = stringResource(R.string.identity_management_back))
        }
    }

    state.pendingCleanupIdentity?.let { identity ->
        val cleanupInProgress =
            state.cleanupInProgressIdentitySha256 == identity.identitySha256
        AlertDialog(
            onDismissRequest = {
                if (!cleanupInProgress) {
                    onAction(ManagerAction.DismissLegacyIdentityCleanupConfirmation)
                }
            },
            modifier = Modifier.testTag("identity-management:confirm-dialog"),
            title = {
                Text(text = stringResource(R.string.identity_management_confirm_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.identity_management_confirm_risk,
                            identity.displayName,
                        ),
                    )
                    Text(text = stringResource(R.string.identity_management_confirm_proof))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onAction(
                            ManagerAction.ConfirmLegacyIdentityCleanup(
                                identity.identitySha256,
                            ),
                        )
                    },
                    enabled = !cleanupInProgress,
                    modifier = Modifier.testTag(
                        "identity-management:confirm:${identity.identitySha256}",
                    ),
                ) {
                    Text(
                        text = stringResource(
                            if (cleanupInProgress) {
                                R.string.identity_management_cleanup_in_progress
                            } else {
                                R.string.identity_management_confirm
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        onAction(ManagerAction.DismissLegacyIdentityCleanupConfirmation)
                    },
                    enabled = !cleanupInProgress,
                    modifier = Modifier.testTag("identity-management:confirm-cancel"),
                ) {
                    Text(text = stringResource(R.string.identity_management_cancel))
                }
            },
        )
    }
}

@Composable
private fun LegacyIdentityCard(
    identity: LegacyConversionIdentity,
    cleanupInProgress: Boolean,
    onRequestCleanup: () -> Unit,
) {
    SectionCard(
        title = identity.displayName,
        modifier = Modifier.testTag(
            "identity-management:identity:${identity.identitySha256}",
        ),
    ) {
        Text(
            text = stringResource(R.string.identity_management_legacy_unknown),
            color = MaterialTheme.colorScheme.tertiary,
        )
        Text(text = stringResource(R.string.identity_management_package, identity.packageName))
        Text(
            text = stringResource(
                R.string.identity_management_extension_id,
                identity.extensionId,
            ),
        )
        Text(
            text = stringResource(
                R.string.identity_management_signer,
                summarizeIdentitySha256(identity.wrapperSignerSha256),
            ),
        )
        Text(
            text = stringResource(
                R.string.identity_management_identity_sha256,
                summarizeIdentitySha256(identity.identitySha256),
            ),
        )
        Text(text = stringResource(R.string.identity_management_mode, identity.mode.name))
        Button(
            onClick = onRequestCleanup,
            enabled = !cleanupInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("identity-management:request:${identity.identitySha256}"),
        ) {
            Text(text = stringResource(R.string.identity_management_request_cleanup))
        }
    }
}

@Composable
private fun cleanupOutcomeText(outcome: ConversionIdentityCleanupUiOutcome): String =
    when (outcome) {
        is ConversionIdentityCleanupUiOutcome.Removed -> stringResource(
            R.string.identity_management_removed,
            outcome.displayName,
        )
        is ConversionIdentityCleanupUiOutcome.AlreadyAbsent -> stringResource(
            R.string.identity_management_already_absent,
            outcome.displayName,
        )
        is ConversionIdentityCleanupUiOutcome.Preserved -> stringResource(
            R.string.identity_management_preserved,
            outcome.displayName,
            outcome.reason,
        )
    }

internal fun summarizeIdentitySha256(value: String): String =
    if (value.length <= SUMMARY_EDGE_LENGTH * 2) {
        value
    } else {
        "${value.take(SUMMARY_EDGE_LENGTH)}…${value.takeLast(SUMMARY_EDGE_LENGTH)}"
    }

private const val SUMMARY_EDGE_LENGTH = 12
