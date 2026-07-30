package dev.sixbridge.manager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import dev.sixbridge.core.compat.CompatibilityStatus
import dev.sixbridge.manager.R
import dev.sixbridge.manager.conversion.IdentityRequirement
import dev.sixbridge.manager.conversion.InstallationIdentitySelection

@Composable
fun ConversionAnalysisScreen(
    state: ConversionUiState,
    message: UserMessage?,
    onAction: (ManagerAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScrollableColumn(
        modifier = modifier,
        testTagModifier = Modifier.testTag("conversion:analysis-screen"),
    ) {
        Text(
            text = stringResource(R.string.conversion_analysis_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        when (state) {
            is ConversionUiState.Building -> {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.conversion_building))
                }
            }
            is ConversionUiState.AnalysisReady -> AnalysisReadyContent(state, onAction)
            else -> Text(stringResource(R.string.conversion_restore_failed))
        }
        message?.let {
            MessageCard(
                message = it,
                onRecovery = { onAction(ManagerAction.DismissMessage) },
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

@Composable
private fun AnalysisReadyContent(
    ready: ConversionUiState.AnalysisReady,
    onAction: (ManagerAction) -> Unit,
) {
    SectionCard(stringResource(R.string.conversion_compatibility)) {
        Text(compatibilityLabel(ready.report.status))
        Text(ready.report.extensionName ?: stringResource(R.string.review_none))
        ready.report.issues.forEach { issue ->
            Text(
                if (issue.code == "analysis_budget_exceeded") {
                    stringResource(R.string.conversion_mobile_budget_exceeded)
                } else {
                    "${issue.code}: ${issue.message}"
                },
            )
        }
    }
    SectionCard(stringResource(R.string.review_permissions)) {
        ready.report.declaredPermissions.ifEmpty { setOf(stringResource(R.string.review_none)) }
            .forEach { Text(it) }
    }
    SectionCard(stringResource(R.string.conversion_apis)) {
        ready.report.usedApis.ifEmpty { setOf(stringResource(R.string.review_none)) }
            .forEach { Text(it) }
        ready.report.unsupportedApis.forEach { Text("지원되지 않음: $it") }
    }
    if (ready.report.adapterDecisions.isNotEmpty()) {
        SectionCard(stringResource(R.string.review_adapters)) {
            ready.report.adapterDecisions.forEach { decision ->
                val approvalRequired = decision.id in ready.requiredApprovals
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (approvalRequired) {
                        Checkbox(
                            checked = decision.id in ready.selectedAdapters,
                            onCheckedChange = {
                                onAction(ManagerAction.ToggleConversionAdapter(decision.id))
                            },
                            modifier = Modifier
                                .testTag("conversion:adapter:${decision.id}")
                                .semantics {
                                    selected = decision.id in ready.selectedAdapters
                                },
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${decision.id} v${decision.version}")
                        Text(
                            if (approvalRequired) {
                                stringResource(R.string.conversion_adapter_requires_approval)
                            } else {
                                stringResource(R.string.conversion_adapter_automatic)
                            },
                        )
                        decision.evidence.forEach {
                            Text("${it.relativePath}${it.line?.let { line -> ":$line" } ?: ""} · ${it.detail}")
                        }
                        decision.changes.forEach { Text("변경: $it") }
                        decision.remainingLimitations.forEach { Text("제한: $it") }
                    }
                }
            }
        }
    }
    if (ready.report.status == CompatibilityStatus.PARTIAL) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = ready.partialConfirmed,
                onCheckedChange = { onAction(ManagerAction.ConfirmConversionPartial) },
                modifier = Modifier.testTag("conversion:confirm-partial"),
            )
            Text(stringResource(R.string.conversion_confirm_partial))
        }
    }
    when (val requirement = ready.identityRequirement) {
        is IdentityRequirement.Stable -> SectionCard(
            stringResource(R.string.conversion_identity),
        ) {
            Text(stringResource(R.string.conversion_identity_stable, requirement.identityKeyPreview))
        }
        is IdentityRequirement.UserSelectionRequired ->
            IdentitySelection(requirement, ready, onAction)
    }
    if (ready.report.status in setOf(
            CompatibilityStatus.COMPATIBLE,
            CompatibilityStatus.PARTIAL,
        )
    ) {
        Button(
            onClick = { onAction(ManagerAction.BuildConversion) },
            enabled = ready.canBuild,
            modifier = Modifier.fillMaxWidth().testTag("conversion:build"),
        ) {
            Text(stringResource(R.string.conversion_build))
        }
    }
}

@Composable
private fun IdentitySelection(
    requirement: IdentityRequirement.UserSelectionRequired,
    ready: ConversionUiState.AnalysisReady,
    onAction: (ManagerAction) -> Unit,
) {
    SectionCard(stringResource(R.string.conversion_identity_choose)) {
        IdentityRadioRow(
            selected = ready.identitySelection == InstallationIdentitySelection.CreateNew,
            tag = "conversion:identity-new",
            label = stringResource(R.string.conversion_identity_new),
            onClick = { onAction(ManagerAction.SelectNewConversionIdentity) },
        )
        requirement.existingTargets.forEach { target ->
            val selected =
                (ready.identitySelection as? InstallationIdentitySelection.UseExisting)
                    ?.identitySha256 == target.identitySha256
            IdentityRadioRow(
                selected = selected,
                tag = "conversion:identity-existing:${target.identitySha256}",
                label = target.displayName,
                details = listOf(
                    "Extension ID: ${target.extensionId}",
                    "Package: ${target.packageName}",
                    "Signer SHA-256: ${target.wrapperSignerSha256}",
                ),
                onClick = {
                    onAction(
                        ManagerAction.SelectExistingConversionIdentity(target.identitySha256),
                    )
                },
            )
            if (selected) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = ready.existingIdentityConfirmed,
                        onCheckedChange = {
                            onAction(ManagerAction.ConfirmExistingConversionIdentity)
                        },
                        modifier = Modifier.testTag("conversion:confirm-existing"),
                    )
                    Text(stringResource(R.string.conversion_confirm_existing))
                }
            }
        }
    }
}

@Composable
private fun IdentityRadioRow(
    selected: Boolean,
    tag: String,
    label: String,
    details: List<String> = emptyList(),
    onClick: () -> Unit,
) {
    val description = (listOf(label) + details).joinToString(", ")
    val selectedDescription = stringResource(
        if (selected) {
            R.string.conversion_identity_selected
        } else {
            R.string.conversion_identity_not_selected
        },
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(tag)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = selectedDescription
            }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            modifier = Modifier.clearAndSetSemantics {},
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label)
            details.forEach { Text(it) }
        }
    }
}

@Composable
private fun compatibilityLabel(status: CompatibilityStatus): String = when (status) {
    CompatibilityStatus.COMPATIBLE -> stringResource(R.string.status_compatible)
    CompatibilityStatus.PARTIAL -> stringResource(R.string.status_partial)
    CompatibilityStatus.BLOCKED -> stringResource(R.string.status_blocked)
    CompatibilityStatus.UNKNOWN -> stringResource(R.string.status_unknown)
}
