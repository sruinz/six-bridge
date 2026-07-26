package dev.sixbridge.manager.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import dev.sixbridge.manager.R

@Composable
fun ReportScreen(
    item: ManagedExtensionItem?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CenteredScrollableColumn(
        modifier = modifier.testTag("report:screen"),
        testTagModifier = Modifier.testTag("report:scroll"),
    ) {
        Text(
            text = stringResource(R.string.report_title),
            style = MaterialTheme.typography.headlineMedium,
        )
        if (item == null) {
            Text(text = stringResource(R.string.report_missing))
        } else {
            val reportAvailable =
                item.reportAvailability == ReportAvailability.AVAILABLE && item.report != null
            Text(text = item.name, style = MaterialTheme.typography.titleLarge)
            Text(text = item.packageName)
            Text(text = stringResource(R.string.extension_version_code, item.installedVersionCode))
            Text(
                text = stringResource(R.string.report_verified_at_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!reportAvailable) {
                Text(
                    text = stringResource(
                        when (item.reportAvailability) {
                            ReportAvailability.UNREADABLE -> R.string.report_unreadable_state
                            ReportAvailability.MISSING,
                            ReportAvailability.AVAILABLE,
                            -> R.string.report_missing_state
                        },
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("report:availability"),
                )
            }
            SectionCard(
                title = stringResource(R.string.review_status),
                modifier = Modifier.testTag("report:status"),
            ) {
                CompatibilityStatusText(item.compatibilityStatus)
            }
            SectionCard(
                title = stringResource(R.string.review_permissions),
            ) {
                ReportList(reportAvailable, item.report?.declaredPermissions.orEmpty())
            }
            SectionCard(
                title = stringResource(R.string.review_issues),
            ) {
                ReportList(
                    reportAvailable,
                    item.report?.issues.orEmpty().map { "${it.code}: ${it.message}" },
                )
            }
            SectionCard(
                title = stringResource(R.string.review_adapters),
            ) {
                ReportList(
                    reportAvailable,
                    item.report?.adapterDecisions.orEmpty().map { decision ->
                        "${decision.id} v${decision.version} · ${decision.disposition}"
                    },
                )
            }
            SectionCard(
                title = stringResource(R.string.review_signer),
                modifier = Modifier.testTag("report:signer"),
            ) {
                Text(text = item.signerSha256)
                Text(
                    text = stringResource(R.string.review_security_notice),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.report_back))
        }
    }
}

@Composable
private fun ReportList(reportAvailable: Boolean, items: List<String>) {
    if (reportAvailable) {
        TextList(items)
    } else {
        Text(text = stringResource(R.string.report_section_unavailable))
    }
}
